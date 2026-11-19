package com.mikhaildezprofi.mobileaiagent

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Safe ZIP import/export for user-selected source projects.
 * Import/export must be initiated by the user through Android's document picker.
 */
internal object CodeProjectArchive {
    data class ImportResult(val projectName: String, val filesImported: Int, val bytesImported: Long)

    fun importZip(workspace: CodeWorkspace, projectName: String, input: InputStream): ImportResult {
        validateProjectName(projectName)
        val destination = File(workspace.root, projectName).canonicalFile
        require(destination.parentFile == workspace.root && !destination.exists()) {
            "Проект с таким именем уже существует или путь недопустим."
        }

        val staging = File(workspace.root, ".jimmy-import-${UUID.randomUUID()}")
        require(staging.mkdirs()) { "Не удалось создать временный каталог импорта." }
        var files = 0
        var totalBytes = 0L
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val normalized = normalizeEntryName(entry.name)
                    if (normalized.isBlank()) {
                        zip.closeEntry()
                        continue
                    }
                    val segments = normalized.split('/')
                    require(segments.size <= MAX_DEPTH) { "Архив содержит слишком глубокий путь." }
                    if (segments.any { it in EXCLUDED_DIRECTORIES }) {
                        val discard = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(discard)
                            if (count < 0) break
                            totalBytes += count
                            require(totalBytes <= MAX_TOTAL_BYTES) {
                                "Распакованный проект превышает лимит $MAX_TOTAL_BYTES байт."
                            }
                        }
                        zip.closeEntry()
                        continue
                    }

                    val target = File(staging, normalized).canonicalFile
                    ensureInside(staging, target)
                    if (entry.isDirectory || normalized.endsWith('/')) {
                        require(target.exists() || target.mkdirs()) { "Не удалось создать каталог из архива." }
                    } else {
                        files++
                        require(files <= MAX_FILES) { "Архив содержит слишком много файлов." }
                        require(!target.exists()) { "Архив содержит повторяющийся путь: $normalized" }
                        require(target.parentFile?.let { it.exists() || it.mkdirs() } == true) {
                            "Не удалось создать каталог файла из архива."
                        }
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var fileBytes = 0L
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                fileBytes += count
                                totalBytes += count
                                require(fileBytes <= MAX_FILE_BYTES) {
                                    "Файл $normalized превышает лимит $MAX_FILE_BYTES байт."
                                }
                                require(totalBytes <= MAX_TOTAL_BYTES) {
                                    "Распакованный проект превышает лимит $MAX_TOTAL_BYTES байт."
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(files > 0) { "В архиве не найдено файлов проекта." }
            Files.move(staging.toPath(), destination.toPath())
            return ImportResult(projectName, files, totalBytes)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun exportZip(workspace: CodeWorkspace, projectName: String, output: OutputStream): Int {
        validateProjectName(projectName)
        val project = File(workspace.root, projectName).canonicalFile
        require(project.parentFile == workspace.root && project.isDirectory) {
            "Проект не найден в рабочей области."
        }
        var count = 0
        var totalBytes = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            project.walkTopDown()
                .onEnter { directory -> directory == project || directory.name !in EXCLUDED_DIRECTORIES }
                .forEach { file ->
                if (file.isDirectory) {
                    if (file != project && file.name in EXCLUDED_DIRECTORIES) return@forEach
                    return@forEach
                }
                val safe = file.canonicalFile
                ensureInside(project, safe)
                val relative = safe.path.removePrefix(project.path + File.separator)
                    .replace(File.separatorChar, '/')
                if (relative.split('/').any { it in EXCLUDED_DIRECTORIES || it.startsWith(".jimmy-") }) {
                    return@forEach
                }
                count++
                require(count <= MAX_FILES) { "Проект содержит слишком много файлов для экспорта." }
                totalBytes += safe.length()
                require(totalBytes <= MAX_TOTAL_BYTES) { "Проект превышает лимит экспорта $MAX_TOTAL_BYTES байт." }
                zip.putNextEntry(ZipEntry(relative))
                safe.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        require(count > 0) { "В проекте нет файлов для экспорта." }
        return count
    }

    private fun validateProjectName(name: String) {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) &&
            name !in EXCLUDED_DIRECTORIES && !name.startsWith(".jimmy-")
        ) { "Недопустимое имя проекта." }
    }

    private fun normalizeEntryName(name: String): String {
        require(name.isNotBlank() && !name.startsWith("/") && !name.startsWith("\\")) {
            "Архив содержит абсолютный путь."
        }
        val normalized = name.replace('\\', '/')
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        require(segments.none { it == "." || it == ".." }) { "Архив содержит выход за пределы проекта." }
        return segments.joinToString("/")
    }

    private fun ensureInside(root: File, target: File) {
        val prefix = root.canonicalPath + File.separator
        require(target == root.canonicalFile || target.path.startsWith(prefix)) {
            "Путь архива выходит за пределы каталога проекта."
        }
    }

    private const val MAX_FILES = 2_000
    private const val MAX_DEPTH = 16
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
    private val EXCLUDED_DIRECTORIES = setOf(".git", ".gradle", ".idea", "build", "node_modules")
}

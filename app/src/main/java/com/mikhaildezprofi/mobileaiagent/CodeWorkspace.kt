package com.mikhaildezprofi.mobileaiagent

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Sandboxed source workspace for Jimmy's Code Agent.
 *
 * This workspace is app-private. It never executes source code or shell commands.
 * Existing files can only be replaced with an optimistic SHA-256 precondition.
 */
internal class CodeWorkspace(rootDirectory: File) {
    val root: File = rootDirectory.canonicalFile

    init {
        require(root.exists() || root.mkdirs()) { "Не удалось создать рабочую область кода." }
        require(root.isDirectory) { "Рабочая область кода не является каталогом." }
    }

    data class ReadResult(val path: String, val content: String, val sha256: String)

    fun listFiles(relativeDirectory: String = "", maxDepth: Int = 3, maxFiles: Int = 300): List<String> {
        require(maxDepth in 0..8) { "Глубина просмотра должна быть от 0 до 8." }
        require(maxFiles in 1..500) { "Лимит файлов должен быть от 1 до 500." }
        val start = resolve(relativeDirectory, allowEmpty = true)
        require(start.isDirectory) { "Указанный путь не является каталогом." }
        val result = mutableListOf<String>()

        fun visit(directory: File, depth: Int) {
            if (depth > maxDepth || result.size >= maxFiles) return
            directory.listFiles()
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { child ->
                    if (child.name in EXCLUDED_DIRECTORIES || child.name.startsWith(".jimmy-")) return@forEach
                    val safe = child.canonicalFile
                    ensureInsideWorkspace(safe)
                    if (safe.isDirectory) {
                        visit(safe, depth + 1)
                    } else if (safe.isFile) {
                        result += relativePath(safe)
                        if (result.size >= maxFiles) return
                    }
                }
        }

        visit(start, 0)
        return result
    }

    fun readFile(relativePath: String, maxBytes: Int = MAX_FILE_BYTES): ReadResult {
        val file = resolve(relativePath)
        require(file.isFile && file.canRead()) { "Файл не найден или недоступен для чтения." }
        require(file.length() <= maxBytes) { "Файл превышает лимит чтения $maxBytes байт." }
        val bytes = file.readBytes()
        require(bytes.none { it == 0.toByte() }) { "Бинарные файлы не поддерживаются." }
        val content = String(bytes, StandardCharsets.UTF_8)
        return ReadResult(relativePath(file), content, sha256(bytes))
    }

    fun search(query: String, relativeDirectory: String = "", maxResults: Int = 40): List<String> {
        require(query.isNotBlank()) { "Строка поиска пуста." }
        require(query.length <= 200) { "Строка поиска слишком длинная." }
        require(maxResults in 1..100) { "Лимит результатов должен быть от 1 до 100." }
        val results = mutableListOf<String>()
        listFiles(relativeDirectory, maxDepth = 8, maxFiles = 500).forEach { path ->
            if (results.size >= maxResults) return@forEach
            val file = resolve(path)
            if (file.length() > MAX_FILE_BYTES) return@forEach
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
            if (bytes.any { it == 0.toByte() }) return@forEach
            val lines = String(bytes, StandardCharsets.UTF_8).lineSequence()
            lines.forEachIndexed { index, line ->
                if (line.contains(query, ignoreCase = true) && results.size < maxResults) {
                    results += "$path:${index + 1}: ${line.take(500)}"
                }
            }
        }
        return results
    }

    /**
     * Create or replace a text file. Replacing an existing file requires its current SHA-256,
     * which prevents a stale model snapshot from silently overwriting newer user changes.
     */
    fun writeFile(relativePath: String, content: String, expectedSha256: String? = null): String {
        require(content.toByteArray(StandardCharsets.UTF_8).size <= MAX_FILE_BYTES) {
            "Размер файла превышает лимит $MAX_FILE_BYTES байт."
        }
        val file = resolve(relativePath)
        require(file.name.isNotBlank()) { "Не указано имя файла." }
        require(file.parentFile?.let { it.exists() || it.mkdirs() } == true) {
            "Не удалось создать каталог файла."
        }
        if (file.exists()) {
            require(file.isFile) { "Путь уже занят каталогом." }
            require(!expectedSha256.isNullOrBlank()) {
                "Для изменения существующего файла сначала прочитайте его и передайте expected_sha256."
            }
            val current = sha256(file.readBytes())
            require(current.equals(expectedSha256, ignoreCase = true)) {
                "Файл изменился после чтения. Прочитайте актуальную версию перед повторной записью."
            }
        } else {
            require(expectedSha256.isNullOrBlank()) {
                "Для нового файла expected_sha256 должен быть пустым."
            }
        }

        val temp = File(file.parentFile, ".jimmy-${file.name}.tmp")
        try {
            temp.writeText(content, StandardCharsets.UTF_8)
            if (file.exists() && !file.delete()) error("Не удалось заменить старую версию файла.")
            if (!temp.renameTo(file)) error("Не удалось атомарно сохранить файл.")
        } finally {
            if (temp.exists()) temp.delete()
        }
        return sha256(file.readBytes())
    }

    private fun resolve(relativePath: String, allowEmpty: Boolean = false): File {
        require(relativePath.indexOf('\u0000') < 0) { "Путь содержит недопустимый символ." }
        require(!relativePath.startsWith("/") && !relativePath.startsWith("\\")) {
            "Разрешены только относительные пути внутри рабочей области."
        }
        require(allowEmpty || relativePath.isNotBlank()) { "Путь к файлу пуст." }
        val target = if (relativePath.isBlank()) root else File(root, relativePath).canonicalFile
        ensureInsideWorkspace(target)
        val segments = relativePath.replace('\\', '/').split('/')
        require(segments.none { it == ".." || it in EXCLUDED_DIRECTORIES || it.startsWith(".jimmy-") }) {
            "Путь обращается к запрещённому каталогу или служебному файлу."
        }
        return target
    }

    private fun ensureInsideWorkspace(file: File) {
        val rootPath = root.path + File.separator
        require(file == root || file.path.startsWith(rootPath)) {
            "Выход за пределы рабочей области запрещён."
        }
    }

    private fun relativePath(file: File): String =
        file.canonicalPath.removePrefix(root.path).trimStart(File.separatorChar).replace(File.separatorChar, '/')

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_FILE_BYTES = 512 * 1024
        private val EXCLUDED_DIRECTORIES = setOf(
            ".git", ".gradle", ".idea", "build", "node_modules", ".jimmy-trash"
        )
    }
}

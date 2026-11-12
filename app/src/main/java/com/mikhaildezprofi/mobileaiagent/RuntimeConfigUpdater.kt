package com.mikhaildezprofi.mobileaiagent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads data-only runtime configuration from the pinned GitHub mirror.
 * It never downloads or executes code. Files are staged, size-limited and
 * SHA-256 checked before the active bundle is replaced.
 */
object RuntimeConfigUpdater {
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/mikhaildezprofi-glitch/mobile-ai-agent/sync/current/runtime-config/manifest.json"
    private const val BASE_URL =
        "https://raw.githubusercontent.com/mikhaildezprofi-glitch/mobile-ai-agent/sync/current/runtime-config/"
    private const val MAX_FILE_BYTES = 256 * 1024
    private const val MAX_TOTAL_BYTES = 512 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    data class Result(
        val version: String,
        val downloadedFiles: Int,
        val promptApplied: Boolean
    )

    suspend fun downloadAndApply(context: Context): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val manifestBytes = download(MANIFEST_URL, 32 * 1024)
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        require(manifest.optInt("schemaVersion") == 1) { "Неподдерживаемая схема runtime manifest." }
        require(manifest.optJSONObject("security")?.optBoolean("remoteCodeAllowed", true) == false) {
            "Manifest разрешает удалённое выполнение кода. Обновление отклонено."
        }
        require(manifest.optJSONObject("security")?.optBoolean("containsSecrets", true) == false) {
            "В manifest отмечено наличие секретов. Обновление отклонено."
        }
        require(manifest.optJSONObject("source")?.optString("baseUrl") == BASE_URL) {
            "Источник конфигурации не совпадает с доверенным адресом."
        }

        val files = manifest.optJSONObject("files") ?: error("В manifest отсутствует список файлов.")
        val hashes = manifest.optJSONObject("sha256") ?: error("В manifest отсутствуют SHA-256.")
        val relativePaths = linkedSetOf<String>()
        val requiredKeys = listOf(
            "ui", "modules", "models", "routing", "prompts", "commands",
            "responseProfiles", "privacy", "voice", "network", "diagnostics"
        )
        requiredKeys.forEach { key ->
            val path = files.optString(key)
            require(path.isNotBlank() && isSafeRelativePath(path)) { "Недопустимый путь файла: $key" }
            relativePaths += path
        }

        val stageRoot = File(app.filesDir, "runtime-remote")
        if (!stageRoot.exists()) stageRoot.mkdirs()
        val staging = File(stageRoot, "staging-${System.currentTimeMillis()}")
        require(staging.mkdirs()) { "Не удалось создать временный каталог конфигурации." }

        var totalBytes = 0
        try {
            relativePaths.forEach { path ->
                val expectedHash = hashes.optString(path).lowercase()
                require(expectedHash.matches(Regex("[0-9a-f]{64}"))) {
                    "Для $path отсутствует корректная SHA-256."
                }
                val bytes = download(BASE_URL + path, MAX_FILE_BYTES)
                totalBytes += bytes.size
                require(totalBytes <= MAX_TOTAL_BYTES) { "Размер runtime bundle превышает лимит." }
                val actualHash = sha256(bytes)
                require(actualHash == expectedHash) { "SHA-256 не совпадает для $path." }

                if (path.endsWith(".json")) {
                    JSONObject(bytes.toString(Charsets.UTF_8))
                } else {
                    require(path.startsWith("prompts/") && path.endsWith(".txt")) {
                        "Запрещённый тип файла: $path"
                    }
                    require(bytes.toString(Charsets.UTF_8).isNotBlank()) { "Пустой prompt: $path" }
                }

                val output = File(staging, path)
                require(output.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    "Выход за пределы staging каталога."
                }
                output.parentFile?.mkdirs()
                output.writeBytes(bytes)
            }

            val promptIndex = JSONObject(File(staging, files.getString("prompts")).readText())
            val defaultPromptId = promptIndex.optString("default")
            val profiles = promptIndex.optJSONArray("profiles") ?: error("Нет списка prompt profiles.")
            val defaultProfile = (0 until profiles.length())
                .mapNotNull { profiles.optJSONObject(it) }
                .firstOrNull { it.optString("id") == defaultPromptId }
                ?: error("Не найден профиль prompt по умолчанию.")
            val promptFileName = defaultProfile.optString("file")
            require(promptFileName.matches(Regex("[A-Za-z0-9_-]+\\.txt"))) {
                "Недопустимое имя файла prompt."
            }
            val promptRelativePath = "prompts/$promptFileName"
            require(promptRelativePath in relativePaths) { "Prompt отсутствует в проверенном bundle." }
            val newDefaultPrompt = File(staging, promptRelativePath).readText()

            val manifestVersion = manifest.optString("configVersion")
            require(manifestVersion.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
                "Недопустимая версия конфигурации."
            }

            val current = File(stageRoot, "current")
            val previousPrompt = File(current, promptRelativePath).takeIf { it.isFile }?.readText()
            val localPromptFile = File(app.filesDir, "runtime/system_prompt.txt")
            val localPrompt = localPromptFile.takeIf { it.isFile }?.readText()
            val shouldApplyPrompt = localPrompt.isNullOrBlank() ||
                localPrompt == RuntimeFiles.DEFAULT_PROMPT ||
                (previousPrompt != null && localPrompt == previousPrompt)

            val backup = File(stageRoot, "previous")
            if (backup.exists()) backup.deleteRecursively()
            if (current.exists()) {
                require(current.renameTo(backup)) { "Не удалось сохранить предыдущую конфигурацию." }
            }
            val finalDir = File(stageRoot, "current")
            if (!staging.renameTo(finalDir)) {
                if (backup.exists()) backup.renameTo(finalDir)
                error("Не удалось активировать runtime bundle.")
            }

            if (shouldApplyPrompt) {
                localPromptFile.parentFile?.mkdirs()
                val tempPrompt = File(localPromptFile.parentFile, "system_prompt.txt.tmp")
                tempPrompt.writeText(newDefaultPrompt)
                if (localPromptFile.exists()) localPromptFile.delete()
                require(tempPrompt.renameTo(localPromptFile)) { "Bundle загружен, но не удалось применить prompt." }
            }

            Result(manifestVersion, relativePaths.size, shouldApplyPrompt)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.startsWith("/") || path.contains("..") || path.contains('\\')) return false
        return path.matches(Regex("[A-Za-z0-9_./-]{1,120}")) &&
            (path.endsWith(".json") || (path.startsWith("prompts/") && path.endsWith(".txt")))
    }

    private fun download(address: String, limit: Int): ByteArray {
        require(address.startsWith(BASE_URL) || address == MANIFEST_URL) { "Недоверенный URL." }
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json, text/plain")
            setRequestProperty("User-Agent", "MOBILE-AI-AGENT-RuntimeConfig/1")
        }
        try {
            val code = connection.responseCode
            require(code == HttpURLConnection.HTTP_OK) { "Сервер конфигурации ответил HTTP $code." }
            val declaredLength = connection.contentLengthLong
            require(declaredLength < 0 || declaredLength <= limit) { "Файл превышает допустимый размер." }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit) { "Файл превышает допустимый размер." }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

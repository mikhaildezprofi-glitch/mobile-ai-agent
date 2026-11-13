package com.mikhaildezprofi.mobileaiagent

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * User-triggered, local-only backup for data that would otherwise be lost on reinstall.
 * API credentials and model binaries are deliberately excluded.
 */
object AppBackupManager {
    private const val FORMAT = "mobile-ai-agent-backup"
    private const val SCHEMA_VERSION = 1
    private const val MAX_BACKUP_BYTES = 8 * 1024 * 1024
    private const val MAX_PROMPT_CHARS = 64 * 1024

    private val runtimePreferenceKeys = setOf(
        "mode",
        "selectedPromptProfile",
        "selectedResponseProfile",
        "customPrompt",
        "module.attachments",
        "module.voiceInput",
        "module.cameraCapture",
        "module.diagnostics",
        "module.videoFrameExtraction",
        "module.documentReading",
        "module.visionInference",
        "module.geminiApi"
    )

    private val runtimeFiles = listOf("system_prompt.txt", "ui_config.json", "modules.json")

    fun export(context: Context, destination: Uri) {
        val chats = context.getSharedPreferences("agent_chats", Context.MODE_PRIVATE)
            .getString("history", "[]") ?: "[]"
        validateChatHistory(chats)

        val preferences = JSONObject()
        val runtimePrefs = context.getSharedPreferences("runtime_config_selection", Context.MODE_PRIVATE)
        runtimePrefs.all.forEach { (key, value) ->
            if (key in runtimePreferenceKeys) {
                when (value) {
                    is String, is Boolean, is Int, is Long, is Float ->
                        preferences.put(key, value)
                }
            }
        }

        val files = JSONObject()
        val runtimeDirectory = java.io.File(context.filesDir, "runtime")
        runtimeFiles.forEach { name ->
            val file = java.io.File(runtimeDirectory, name)
            if (file.isFile) {
                val content = file.readText()
                require(content.length <= MAX_PROMPT_CHARS) { "Файл настроек слишком большой: $name" }
                files.put(name, content)
            }
        }

        val backup = JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("applicationId", context.packageName)
            .put("chatHistory", chats)
            .put("runtimePreferences", preferences)
            .put("runtimeFiles", files)

        val bytes = backup.toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BACKUP_BYTES) { "Резервная копия превышает 8 МБ." }
        val output = context.contentResolver.openOutputStream(destination, "wt")
            ?: error("Android не открыл файл для записи.")
        output.use { it.write(bytes); it.flush() }
    }

    fun restore(context: Context, source: Uri) {
        val input = context.contentResolver.openInputStream(source)
            ?: error("Android не открыл файл резервной копии.")
        val bytes = input.use { stream ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = stream.read(chunk)
                if (count < 0) break
                require(buffer.size() + count <= MAX_BACKUP_BYTES) {
                    "Файл резервной копии превышает 8 МБ."
                }
                buffer.write(chunk, 0, count)
            }
            buffer.toByteArray()
        }

        val backup = JSONObject(String(bytes, Charsets.UTF_8))
        require(backup.optString("format") == FORMAT) { "Это не резервная копия MOBILE AI AGENT." }
        require(backup.optInt("schemaVersion") == SCHEMA_VERSION) { "Версия резервной копии не поддерживается." }
        require(backup.optString("applicationId") == context.packageName) {
            "Резервная копия создана для другого приложения."
        }

        val chats = backup.optString("chatHistory", "[]")
        validateChatHistory(chats)

        val preferences = backup.optJSONObject("runtimePreferences") ?: JSONObject()
        val validatedPreferences = JSONObject()
        val keys = preferences.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in runtimePreferenceKeys) continue
            val value = preferences.get(key)
            when (key) {
                "mode" -> require(value is String && (value == "local" || value == "hybrid"))
                "selectedPromptProfile", "selectedResponseProfile" ->
                    require(value is String && value.matches(Regex("[A-Za-z0-9_-]{1,48}")))
                "customPrompt", "module.attachments", "module.voiceInput",
                "module.cameraCapture", "module.diagnostics", "module.videoFrameExtraction",
                "module.documentReading", "module.visionInference", "module.geminiApi" ->
                    require(value is Boolean)
            }
            validatedPreferences.put(key, value)
        }

        val files = backup.optJSONObject("runtimeFiles") ?: JSONObject()
        val validatedFiles = linkedMapOf<String, String>()
        for (name in runtimeFiles) {
            if (!files.has(name)) continue
            val content = files.optString(name)
            require(content.length <= MAX_PROMPT_CHARS) { "Файл настроек слишком большой: $name" }
            when (name) {
                "ui_config.json" -> {
                    val config = JSONObject(content)
                    val lines = config.optInt("inputMaxLines", 1)
                    require(lines in 1..3) { "Некорректная конфигурация интерфейса." }
                }
                "modules.json" -> JSONObject(content)
            }
            validatedFiles[name] = content
        }

        // Validate the complete backup before changing any app data.
        val runtimeDirectory = java.io.File(context.filesDir, "runtime")
        require(runtimeDirectory.exists() || runtimeDirectory.mkdirs()) {
            "Не удалось подготовить папку настроек."
        }
        validatedFiles.forEach { (name, content) ->
            val target = java.io.File(runtimeDirectory, name)
            val temporary = java.io.File(runtimeDirectory, "$name.restore.tmp")
            temporary.writeText(content)
            if (target.exists() && !target.delete()) {
                temporary.delete()
                error("Не удалось обновить файл настроек: $name")
            }
            if (!temporary.renameTo(target)) {
                temporary.delete()
                error("Не удалось сохранить файл настроек: $name")
            }
        }

        context.getSharedPreferences("agent_chats", Context.MODE_PRIVATE)
            .edit().putString("history", chats).commit()
        val editor = context.getSharedPreferences("runtime_config_selection", Context.MODE_PRIVATE).edit()
        runtimePreferenceKeys.forEach(editor::remove)
        val restoredKeys = validatedPreferences.keys()
        while (restoredKeys.hasNext()) {
            val key = restoredKeys.next()
            when (val value = validatedPreferences.get(key)) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
            }
        }
        check(editor.commit()) { "Не удалось восстановить настройки приложения." }
    }

    private fun validateChatHistory(raw: String) {
        require(raw.length <= MAX_BACKUP_BYTES) { "История чатов слишком большая." }
        val chats = JSONArray(raw)
        require(chats.length() <= 50) { "В резервной копии больше 50 чатов." }
        for (i in 0 until chats.length()) {
            val chat = chats.optJSONObject(i) ?: error("Повреждена запись чата.")
            val messages = chat.optJSONArray("messages") ?: JSONArray()
            require(messages.length() <= 200) { "В одном чате слишком много сообщений." }
            for (j in 0 until messages.length()) {
                val message = messages.optJSONObject(j) ?: error("Повреждено сообщение в чате.")
                require(message.optString("speaker").length <= 80)
                require(message.optString("text").length <= 250_000)
            }
        }
    }
}

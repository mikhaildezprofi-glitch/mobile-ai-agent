package com.mikhaildezprofi.mobileaiagent

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * User-editable runtime files. These files are read on app start and can be
 * reloaded while the app is running; they do not contain executable code.
 */
data class AgentRuntimeConfig(
    val inputMaxLines: Int = 1,
    val attachmentsEnabled: Boolean = true,
    val voiceEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
    val diagnosticsEnabled: Boolean = true
)

data class AgentRuntimeFiles(
    val config: AgentRuntimeConfig,
    val systemPrompt: String,
    val directory: File
)

object RuntimeFiles {
    const val DEFAULT_PROMPT =
        "Ты — мобильный ИИ-ассистент. Отвечай на языке пользователя, ясно и по существу. " +
        "Если не уверен — прямо сообщи об этом. Не утверждай, что выполнил действие, пока оно не проверено."

    private fun runtimeDir(context: Context): File =
        File(context.filesDir, "runtime").apply { if (!exists()) mkdirs() }

    fun load(context: Context): AgentRuntimeFiles {
        val dir = runtimeDir(context)
        val configFile = File(dir, "ui_config.json")
        val promptFile = File(dir, "system_prompt.txt")
        val modulesFile = File(dir, "modules.json")

        if (!configFile.exists()) {
            configFile.writeText(
                """{
  "inputMaxLines": 1
}"""
            )
        }
        if (!promptFile.exists()) promptFile.writeText(DEFAULT_PROMPT)
        if (!modulesFile.exists()) {
            modulesFile.writeText(
                """{
  "attachments": true,
  "voice": true,
  "camera": true,
  "diagnostics": true
}"""
            )
        }

        val ui = runCatching { JSONObject(configFile.readText()) }.getOrElse { JSONObject() }
        val modules = runCatching { JSONObject(modulesFile.readText()) }.getOrElse { JSONObject() }
        val config = AgentRuntimeConfig(
            inputMaxLines = ui.optInt("inputMaxLines", 1).coerceIn(1, 3),
            attachmentsEnabled = modules.optBoolean("attachments", true),
            voiceEnabled = modules.optBoolean("voice", true),
            cameraEnabled = modules.optBoolean("camera", true),
            diagnosticsEnabled = modules.optBoolean("diagnostics", true)
        )
        val prompt = runCatching { promptFile.readText() }
            .getOrDefault(DEFAULT_PROMPT)
            .ifBlank { DEFAULT_PROMPT }
        return AgentRuntimeFiles(config, prompt, dir)
    }

    fun save(context: Context, config: AgentRuntimeConfig, prompt: String) {
        val dir = runtimeDir(context)
        File(dir, "ui_config.json").writeText(
            JSONObject()
                .put("inputMaxLines", config.inputMaxLines.coerceIn(1, 3))
                .toString(2)
        )
        File(dir, "modules.json").writeText(
            JSONObject()
                .put("attachments", config.attachmentsEnabled)
                .put("voice", config.voiceEnabled)
                .put("camera", config.cameraEnabled)
                .put("diagnostics", config.diagnosticsEnabled)
                .toString(2)
        )
        File(dir, "system_prompt.txt").writeText(prompt.ifBlank { DEFAULT_PROMPT })
    }
}

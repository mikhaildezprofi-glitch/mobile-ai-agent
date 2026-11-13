package com.mikhaildezprofi.mobileaiagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Runtime-only data loaded from the verified remote bundle and local preferences. */
data class RuntimePromptProfile(
    val id: String,
    val label: String,
    val file: String,
    val content: String
)

data class RuntimeResponseProfile(
    val id: String,
    val label: String,
    val maxOutputTokens: Int,
    val temperature: Double
)

data class AgentRoutingConfig(
    val defaultMode: String = "hybrid",
    val fallbackToLocal: Boolean = true,
    val cloudPreferredTasks: Set<String> = setOf("general", "code", "complex_reasoning"),
    val localOnlyTasks: Set<String> = setOf("file_analysis", "image_analysis", "device_action"),
    val currentInformationEnabled: Boolean = false,
    val localMaxTokens: Int = 512,
    val cloudMaxTokens: Int = 2048,
    val requestTimeoutSeconds: Int = 60
) {
    fun prefersCloud(taskId: String): Boolean =
        taskId.lowercase() in cloudPreferredTasks && taskId.lowercase() !in localOnlyTasks
}

/**
 * Runtime controls are data only. Remote flags can switch existing capabilities,
 * but cannot make an unimplemented or unavailable feature appear operational.
 */
data class AgentRuntimeConfig(
    val inputMaxLines: Int = 1,
    val attachmentsEnabled: Boolean = true,
    val voiceEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
    val diagnosticsEnabled: Boolean = true,
    val videoFrameExtractionEnabled: Boolean = true,
    val documentReadingEnabled: Boolean = true,
    val visionInferenceEnabled: Boolean = true,
    val geminiApiEnabled: Boolean = true,
    val webSearchEnabled: Boolean = false,
    val androidActionsEnabled: Boolean = false,
    val wakeWordEnabled: Boolean = false,
    val textToSpeechEnabled: Boolean = false,
    val routing: AgentRoutingConfig = AgentRoutingConfig(),
    val promptProfiles: List<RuntimePromptProfile> = emptyList(),
    val selectedPromptProfileId: String = "assistant_ru",
    val responseProfiles: List<RuntimeResponseProfile> = emptyList(),
    val selectedResponseProfileId: String = "compact",
    val maxOutputTokens: Int = 512,
    val temperature: Double = 0.6,
    val maxPromptCharacters: Int = 24_000,
    val maxAttachmentCount: Int = 10,
    val maxHistoryMessages: Int = 100,
    val autoScrollDuringGeneration: Boolean = true,
    val placeholderNewChat: String = "Спросить AI",
    val placeholderReply: String = "Ответить AI",
    val voiceLanguage: String = "ru-RU",
    val geminiModelId: String = "gemini-3.5-flash-lite",
    val networkConnectTimeoutSeconds: Int = 15,
    val networkReadTimeoutSeconds: Int = 60
)

data class AgentRuntimeFiles(
    val config: AgentRuntimeConfig,
    val systemPrompt: String,
    val directory: File,
    val customPrompt: Boolean
)

object RuntimeFiles {
    const val DEFAULT_PROMPT =
        "Ты — мобильный ИИ-ассистент. Отвечай на языке пользователя, ясно и по существу. " +
        "Если не уверен — прямо сообщи об этом. Не утверждай, что выполнил действие, пока оно не проверено."

    private const val PREFS = "runtime_config_selection"

    private fun runtimeDir(context: Context): File =
        File(context.filesDir, "runtime").apply { if (!exists()) mkdirs() }

    private fun remoteDir(context: Context): File =
        File(context.filesDir, "runtime-remote/current")

    private fun readJson(file: File): JSONObject =
        runCatching { if (file.isFile) JSONObject(file.readText()) else JSONObject() }
            .getOrElse { JSONObject() }

    private fun jsonStrings(array: JSONArray?): Set<String> =
        if (array == null) emptySet() else buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let { add(it.lowercase()) }
            }
        }

    private fun moduleEnabled(
        remote: JSONObject,
        key: String,
        legacy: JSONObject,
        legacyKey: String,
        fallback: Boolean
    ): Boolean {
        val entry = remote.optJSONObject(key)
        return if (entry != null) entry.optBoolean("enabled", fallback)
        else legacy.optBoolean(legacyKey, fallback)
    }

    fun initialHybridMode(context: Context, geminiKeyAvailable: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("mode", null)
        if (stored != null) return stored == "hybrid" && geminiKeyAvailable
        val routing = readJson(File(remoteDir(context), "routing.json"))
        return routing.optString("defaultMode", "hybrid") == "hybrid" && geminiKeyAvailable
    }

    fun saveMode(context: Context, hybrid: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("mode", if (hybrid) "hybrid" else "local").apply()
    }

    fun load(context: Context): AgentRuntimeFiles {
        val dir = runtimeDir(context)
        val remote = remoteDir(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val configFile = File(dir, "ui_config.json")
        val promptFile = File(dir, "system_prompt.txt")
        val legacyModulesFile = File(dir, "modules.json")

        if (!configFile.exists()) configFile.writeText("""{"inputMaxLines":1}""")
        if (!promptFile.exists()) promptFile.writeText(DEFAULT_PROMPT)
        if (!legacyModulesFile.exists()) legacyModulesFile.writeText(
            """{"attachments":true,"voice":true,"camera":true,"diagnostics":true}"""
        )

        val legacyUi = readJson(configFile)
        val legacyModules = readJson(legacyModulesFile)
        val ui = readJson(File(remote, "ui.json"))
        val modules = readJson(File(remote, "modules.json"))
        val routingJson = readJson(File(remote, "routing.json"))
        val privacy = readJson(File(remote, "privacy.json"))
        val promptsJson = readJson(File(remote, "prompts/index.json"))
        val responsesJson = readJson(File(remote, "response_profiles.json"))
        val modelsJson = readJson(File(remote, "models.json"))
        val networkJson = readJson(File(remote, "network.json"))
        val voiceJson = readJson(File(remote, "voice.json"))
        val diagnosticsJson = readJson(File(remote, "diagnostics.json"))

        fun module(key: String, legacyKey: String, fallback: Boolean): Boolean {
            val remoteValue = moduleEnabled(modules, key, legacyModules, legacyKey, fallback)
            return if (prefs.contains("module.$key")) prefs.getBoolean("module.$key", remoteValue)
            else remoteValue
        }

        val promptProfiles = buildList {
            val profiles = promptsJson.optJSONArray("profiles") ?: JSONArray()
            for (index in 0 until profiles.length()) {
                val item = profiles.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val fileName = item.optString("file")
                if (!id.matches(Regex("[A-Za-z0-9_-]{1,48}")) ||
                    !fileName.matches(Regex("[A-Za-z0-9_-]+\\.txt"))) continue
                val file = File(remote, "prompts/$fileName")
                if (!file.isFile) continue
                add(RuntimePromptProfile(
                    id = id,
                    label = item.optString("description", id),
                    file = fileName,
                    content = runCatching { file.readText() }.getOrDefault("").trim()
                ))
            }
        }

        val defaultPromptId = promptsJson.optString("default", "assistant_ru")
        val selectedPromptId = prefs.getString("selectedPromptProfile", null)
            ?.takeIf { id -> promptProfiles.any { it.id == id } }
            ?: defaultPromptId.takeIf { id -> promptProfiles.any { it.id == id } }
            ?: promptProfiles.firstOrNull()?.id.orEmpty()
        val selectedPrompt = promptProfiles.firstOrNull { it.id == selectedPromptId }?.content
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_PROMPT
        val savedPrompt = runCatching { promptFile.readText() }.getOrDefault(DEFAULT_PROMPT)
        val customPrompt = if (prefs.contains("customPrompt")) {
            prefs.getBoolean("customPrompt", false)
        } else {
            savedPrompt.isNotBlank() && savedPrompt != DEFAULT_PROMPT &&
                promptProfiles.none { it.content == savedPrompt }
        }
        if (!prefs.contains("customPrompt")) {
            prefs.edit().putBoolean("customPrompt", customPrompt).apply()
        }
        val effectivePrompt = if (customPrompt) savedPrompt else selectedPrompt

        val responseProfiles = buildList {
            val profiles = responsesJson.optJSONArray("profiles") ?: JSONArray()
            for (index in 0 until profiles.length()) {
                val item = profiles.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (!id.matches(Regex("[A-Za-z0-9_-]{1,48}"))) continue
                add(RuntimeResponseProfile(
                    id = id,
                    label = item.optString("label", id),
                    maxOutputTokens = item.optInt("maxOutputTokens", 512).coerceIn(64, 8192),
                    temperature = item.optDouble("temperature", 0.6).coerceIn(0.0, 2.0)
                ))
            }
        }
        val defaultResponseId = responsesJson.optString("default", "compact")
        val selectedResponseId = prefs.getString("selectedResponseProfile", null)
            ?.takeIf { id -> responseProfiles.any { it.id == id } }
            ?: defaultResponseId.takeIf { id -> responseProfiles.any { it.id == id } }
            ?: responseProfiles.firstOrNull()?.id.orEmpty()
        val responseProfile = responseProfiles.firstOrNull { it.id == selectedResponseId }
        val modelEntries = modelsJson.optJSONArray("models") ?: JSONArray()
        val geminiModelId = (0 until modelEntries.length())
            .mapNotNull { modelEntries.optJSONObject(it) }
            .firstOrNull { it.optString("provider") == "google_gemini" && it.optBoolean("enabled", false) }
            ?.optString("modelId")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) }
            ?: "gemini-3.5-flash-lite"
        val networkTimeouts = networkJson.optJSONObject("timeouts")
        val voiceLanguage = voiceJson.optJSONObject("input")?.optString("language", "ru-RU")
            ?.takeIf { it.matches(Regex("[a-z]{2}-[A-Z]{2}")) } ?: "ru-RU"

        val routing = AgentRoutingConfig(
            defaultMode = routingJson.optString("defaultMode", "hybrid")
                .takeIf { it == "local" || it == "hybrid" } ?: "hybrid",
            fallbackToLocal = routingJson.optBoolean("fallbackToLocal", true),
            cloudPreferredTasks = routingJson.optJSONArray("cloudPreferredTasks")
                ?.let { jsonStrings(it) }
                ?: setOf("general", "code", "complex_reasoning"),
            localOnlyTasks = jsonStrings(routingJson.optJSONArray("localOnlyTasks")) +
                setOf("file_analysis", "image_analysis", "device_action"),
            currentInformationEnabled = routingJson.optJSONObject("currentInformation")
                ?.optBoolean("enabled", false) == true,
            localMaxTokens = routingJson.optJSONObject("generation")
                ?.optInt("localMaxTokens", 512)?.coerceIn(64, 8192) ?: 512,
            cloudMaxTokens = routingJson.optJSONObject("generation")
                ?.optInt("cloudMaxTokens", 2048)?.coerceIn(64, 8192) ?: 2048,
            requestTimeoutSeconds = routingJson.optJSONObject("generation")
                ?.optInt("requestTimeoutSeconds", 60)?.coerceIn(5, 300) ?: 60
        )

        val legacyInputLines = legacyUi.optInt("inputMaxLines", 1)
        val maxOutput = responseProfile?.maxOutputTokens ?: 512
        val config = AgentRuntimeConfig(
            inputMaxLines = ui.optInt("inputMaxLines", legacyInputLines).coerceIn(1, 3),
            attachmentsEnabled = module("attachments", "attachments", true),
            voiceEnabled = module("voiceInput", "voice", true) &&
                voiceJson.optJSONObject("input")?.optBoolean("enabled", true) != false,
            cameraEnabled = module("cameraCapture", "camera", true),
            diagnosticsEnabled = if (prefs.contains("module.diagnostics")) {
                prefs.getBoolean("module.diagnostics", diagnosticsJson.optBoolean("enabled", true))
            } else {
                diagnosticsJson.optBoolean("enabled", legacyModules.optBoolean("diagnostics", true))
            },
            videoFrameExtractionEnabled = module("videoFrameExtraction", "videoFrameExtraction", true),
            documentReadingEnabled = module("documentReading", "documentReading", true),
            visionInferenceEnabled = module("visionInference", "visionInference", true),
            geminiApiEnabled = module("geminiApi", "geminiApi", true),
            webSearchEnabled = module("webSearch", "webSearch", false),
            androidActionsEnabled = module("androidActions", "androidActions", false),
            wakeWordEnabled = module("wakeWordJimmy", "wakeWordJimmy", false) &&
                voiceJson.optJSONObject("wakeWord")?.optBoolean("enabled", false) == true,
            textToSpeechEnabled = module("textToSpeech", "textToSpeech", false) &&
                voiceJson.optJSONObject("output")?.optBoolean("textToSpeechEnabled", false) == true,
            routing = routing,
            promptProfiles = promptProfiles,
            selectedPromptProfileId = selectedPromptId,
            responseProfiles = responseProfiles,
            selectedResponseProfileId = selectedResponseId,
            maxOutputTokens = maxOutput,
            temperature = responseProfile?.temperature ?: 0.6,
            maxPromptCharacters = ui.optJSONObject("limits")
                ?.optInt("maxPromptCharacters", 24_000)?.coerceIn(1024, 100_000) ?: 24_000,
            maxAttachmentCount = ui.optJSONObject("limits")
                ?.optInt("maxAttachmentCount", 10)?.coerceIn(1, 10) ?: 10,
            maxHistoryMessages = ui.optJSONObject("limits")
                ?.optInt("maxHistoryMessages", 100)?.coerceIn(2, 200)?.let { it - (it % 2) } ?: 100,
            autoScrollDuringGeneration = ui.optJSONObject("chat")
                ?.optBoolean("autoScrollDuringGeneration", true) ?: true,
            placeholderNewChat = ui.optJSONObject("composer")
                ?.optString("placeholderNewChat", "Спросить AI") ?: "Спросить AI",
            placeholderReply = ui.optJSONObject("composer")
                ?.optString("placeholderReply", "Ответить AI") ?: "Ответить AI",
            voiceLanguage = voiceLanguage,
            geminiModelId = geminiModelId,
            networkConnectTimeoutSeconds = networkTimeouts?.optInt("connectSeconds", 15)?.coerceIn(5, 120) ?: 15,
            networkReadTimeoutSeconds = minOf(
                networkTimeouts?.optInt("readSeconds", 60)?.coerceIn(5, 300) ?: 60,
                routing.requestTimeoutSeconds
            )
        )
        return AgentRuntimeFiles(config, effectivePrompt, dir, customPrompt)
    }

    fun clearModuleOverrides(context: Context) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        listOf(
            "attachments", "voiceInput", "cameraCapture", "diagnostics",
            "videoFrameExtraction", "documentReading", "visionInference", "geminiApi"
        ).forEach { editor.remove("module.$it") }
        editor.apply()
    }

    fun save(
        context: Context,
        config: AgentRuntimeConfig,
        prompt: String,
        selectedPromptProfileId: String = config.selectedPromptProfileId,
        selectedResponseProfileId: String = config.selectedResponseProfileId,
        moduleOverrides: Set<String> = emptySet()
    ) {
        val dir = runtimeDir(context)
        val promptProfile = config.promptProfiles.firstOrNull { it.id == selectedPromptProfileId }
        val isCustomPrompt = prompt.isNotBlank() && (
            if (promptProfile != null) prompt != promptProfile.content
            else prompt != DEFAULT_PROMPT
        )
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putString("selectedPromptProfile", selectedPromptProfileId)
            .putString("selectedResponseProfile", selectedResponseProfileId)
            .putBoolean("customPrompt", isCustomPrompt)
        val moduleValues = mapOf(
            "attachments" to config.attachmentsEnabled,
            "voiceInput" to config.voiceEnabled,
            "cameraCapture" to config.cameraEnabled,
            "diagnostics" to config.diagnosticsEnabled,
            "videoFrameExtraction" to config.videoFrameExtractionEnabled,
            "documentReading" to config.documentReadingEnabled,
            "visionInference" to config.visionInferenceEnabled,
            "geminiApi" to config.geminiApiEnabled
        )
        moduleOverrides.forEach { key ->
            moduleValues[key]?.let { value -> editor.putBoolean("module.$key", value) }
        }
        editor.apply()

        File(dir, "ui_config.json").writeText(
            JSONObject().put("inputMaxLines", config.inputMaxLines.coerceIn(1, 3)).toString(2)
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
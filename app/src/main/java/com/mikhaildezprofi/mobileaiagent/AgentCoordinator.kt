package com.mikhaildezprofi.mobileaiagent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Central request coordinator. It classifies intent, selects a provider and
 * keeps provider fallback decisions in one place instead of the UI.
 */
enum class AgentTaskKind {
    GENERAL, CODE, COMPLEX_REASONING, CURRENT_INFORMATION,
    FILE_ANALYSIS, IMAGE_ANALYSIS, DEVICE_ACTION
}

enum class AgentProviderKind { LOCAL, CLOUD }

data class AgentRoute(
    val task: AgentTaskKind,
    val provider: AgentProviderKind,
    val reason: String,
    val warning: String? = null
)

interface AgentModelProvider {
    val id: String
    val isAvailable: Boolean
    fun generate(prompt: String, maxTokens: Int): Flow<String>
}

private object UnavailableLocalProvider : AgentModelProvider {
    override val id: String = "llama.cpp / Qwen3"
    override val isAvailable: Boolean = false
    override fun generate(prompt: String, maxTokens: Int): Flow<String> = flow {
        throw IllegalStateException("Нативный локальный движок не инициализирован.")
    }
}

private class LocalLlamaProvider(
    private val engine: InferenceEngine
) : AgentModelProvider {
    override val id: String = "llama.cpp / Qwen3"
    override val isAvailable: Boolean
        get() = engine.state.value is InferenceEngine.State.ModelReady

    override fun generate(prompt: String, maxTokens: Int): Flow<String> =
        engine.sendUserPrompt(prompt, maxTokens)
}

/**
 * Cloud providers implement the same contract. No cloud endpoint or API key is
 * bundled in this build; a provider is considered usable only after explicit setup.
 */
interface CloudModelProvider : AgentModelProvider

class AgentCoordinator(
    private val local: AgentModelProvider,
    private val cloud: CloudModelProvider? = null
) {
    fun capabilities(): List<AgentCapability> = AgentToolRegistry.forRuntime(
        localReady = local.isAvailable,
        cloudReady = cloud?.isAvailable == true,
        fileReaderReady = true
    ).all()

    fun classify(prompt: String): AgentTaskKind {
        val value = prompt.lowercase()
        return when {
            listOf("открой", "нажми", "включи", "выключи", "удали", "установи",
                "запусти приложение", "отправь сообщение", "позвони").any(value::contains) ->
                AgentTaskKind.DEVICE_ACTION
            listOf("последние новости", "сейчас", "сегодня", "текущая цена",
                "курс валют", "погода", "найди в интернете", "актуальная информация").any(value::contains) ->
                AgentTaskKind.CURRENT_INFORMATION
            listOf("прикреплённые файлы", "вложенные файлы", "пользователь добавил вложения",
                "содержимое выбранных пользователем файлов", "прочитай файл",
                "проанализируй документ", "содержимое документа").any(value::contains) ->
                AgentTaskKind.FILE_ANALYSIS
            listOf("на изображении", "на этой фотографии", "посмотри фото",
                "что на картинке", "проанализируй видео").any(value::contains) ->
                AgentTaskKind.IMAGE_ANALYSIS
            listOf("напиши код", "программа", "функцию на ", "скрипт", "исправь код",
                "разработай приложение", "реализуй").any(value::contains) ->
                AgentTaskKind.CODE
            listOf("подробно проанализируй", "сравни варианты", "разбери по шагам",
                "сложная задача", "составь стратегию", "архитектура").any(value::contains) ->
                AgentTaskKind.COMPLEX_REASONING
            else -> AgentTaskKind.GENERAL
        }
    }

    fun route(prompt: String, hybridMode: Boolean): AgentRoute {
        val task = classify(prompt)
        // Hybrid mode sends ordinary text, coding and reasoning to Gemini.
        // Files and images stay local; live web search and Android control are not connected.
        val cloudPreferred = task in setOf(
            AgentTaskKind.GENERAL,
            AgentTaskKind.COMPLEX_REASONING,
            AgentTaskKind.CODE
        )
        if (hybridMode && cloudPreferred && cloud?.isAvailable == true) {
            return AgentRoute(task, AgentProviderKind.CLOUD, "Задача направлена облачному провайдеру.")
        }

        val warning = when {
            task == AgentTaskKind.FILE_ANALYSIS ->
                "Выбранные TXT, исходники и поддерживаемые DOCX/XLSX/PPTX читаются локально. PDF и бинарные форматы пока не извлекаются."
            task == AgentTaskKind.IMAGE_ANALYSIS ->
                "Инструмент анализа изображений ещё не подключён. Изображение не передаётся модели."
            task == AgentTaskKind.DEVICE_ACTION ->
                "Инструменты управления Android ещё не подключены. Действие не выполняется."
            task == AgentTaskKind.CURRENT_INFORMATION ->
                "Поиск в интернете ещё не подключён. Локальная модель не получает свежие данные."
            !local.isAvailable -> "Локальная модель не готова."
            hybridMode && cloudPreferred && cloud == null ->
                "Облачный провайдер ещё не подключён. Запрос останется локальным."
            hybridMode && cloudPreferred && cloud?.isAvailable == false ->
                "Облачный провайдер не настроен. Используется локальная модель."
            else -> null
        }
        return AgentRoute(
            task = task,
            provider = AgentProviderKind.LOCAL,
            reason = when {
                !hybridMode && cloudPreferred -> "Выбран локальный режим."
                cloudPreferred -> "Используется локальная модель: облачный провайдер недоступен."
                else -> "Используется локальная модель."
            },
            warning = warning
        )
    }

    fun generate(prompt: String, maxTokens: Int, hybridMode: Boolean): Flow<String> = flow {
        val decision = route(prompt, hybridMode)
        Log.i(TAG, "task=${decision.task}, provider=${decision.provider}, reason=${decision.reason}")
        Log.i(TAG, "capabilities=${capabilities().joinToString { "${it.id}:${it.available}" }}")
        val providerPrompt = if (decision.provider == AgentProviderKind.CLOUD) {
            prompt.removeSuffix("/no_think").trimEnd()
        } else prompt
        when (decision.provider) {
            AgentProviderKind.LOCAL -> {
                check(local.isAvailable) { "Локальная модель не готова к генерации." }
                emitAll(local.generate(prompt, maxTokens))
            }
            AgentProviderKind.CLOUD -> {
                val provider = cloud ?: error("Облачный провайдер не подключён.")
                check(provider.isAvailable) { "Облачный провайдер не настроен." }
                try {
                    emitAll(provider.generate(providerPrompt, maxTokens))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Cloud request failed; switching to local provider (${error.javaClass.simpleName}).")
                    check(local.isAvailable) {
                        "Gemini недоступен (${error.message ?: error.javaClass.simpleName}), локальная модель не готова."
                    }
                    emit("Gemini недоступен (${error.message ?: error.javaClass.simpleName}). Переключаюсь на локальную модель.\n\n")
                    emitAll(local.generate(prompt, maxTokens))
                }
            }
        }
    }

    companion object {
        private const val TAG = "AgentCoordinator"

        fun forLocalEngine(engine: InferenceEngine): AgentCoordinator =
            AgentCoordinator(LocalLlamaProvider(engine))

        fun forContext(context: Context, engine: InferenceEngine?): AgentCoordinator {
            val keyStore = GeminiApiKeyStore(context.applicationContext)
            return AgentCoordinator(
                local = engine?.let(::LocalLlamaProvider) ?: UnavailableLocalProvider,
                cloud = GeminiCloudProvider(keyStore)
            )
        }
    }
}

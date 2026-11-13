package com.mikhaildezprofi.mobileaiagent

import android.content.Context
import android.util.Log
import com.arm.aichat.InferenceEngine
import com.arm.aichat.VisionInferenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach

/**
 * Central request coordinator.
 *
 * This class is deliberately limited to routing and provider execution.
 * Android actions and web search must remain blocked until real executors exist.
 */
enum class AgentTaskKind {
    GENERAL, CODE, COMPLEX_REASONING, CURRENT_INFORMATION,
    FILE_ANALYSIS, IMAGE_ANALYSIS, DEVICE_ACTION
}

enum class AgentProviderKind { LOCAL, CLOUD, BLOCKED }

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

/** Cloud providers implement the same contract; credentials are stored separately. */
interface CloudModelProvider : AgentModelProvider

class AgentCoordinator(
    private val local: AgentModelProvider,
    private val cloud: CloudModelProvider? = null,
    private val visionReady: Boolean = false
) {
    fun capabilities(): List<AgentCapability> = AgentToolRegistry.forRuntime(
        localReady = local.isAvailable,
        cloudReady = cloud?.isAvailable == true,
        fileReaderReady = true,
        visionReady = visionReady
    ).all()

    fun classify(prompt: String): AgentTaskKind {
        val value = prompt.lowercase()
        return when {
            listOf(
                "последние новости", "сейчас", "сегодня", "текущая цена",
                "курс валют", "погода", "найди в интернете", "актуальная информация",
                "поищи в интернете", "что происходит сегодня"
            ).any(value::contains) -> AgentTaskKind.CURRENT_INFORMATION

            listOf(
                "открой", "нажми", "включи", "выключи", "удали", "установи",
                "запусти приложение", "отправь сообщение", "позвони", "измени настройку"
            ).any(value::contains) -> AgentTaskKind.DEVICE_ACTION

            listOf(
                "прикреплённые файлы", "вложенные файлы", "пользователь добавил вложения",
                "содержимое выбранных пользователем файлов", "прочитай файл",
                "проанализируй документ", "содержимое документа"
            ).any(value::contains) -> AgentTaskKind.FILE_ANALYSIS

            listOf(
                "на изображении", "на этой фотографии", "посмотри фото",
                "что на картинке", "проанализируй видео", "что изображено"
            ).any(value::contains) -> AgentTaskKind.IMAGE_ANALYSIS

            listOf(
                "напиши код", "программа", "функцию на ", "скрипт", "исправь код",
                "разработай приложение", "реализуй"
            ).any(value::contains) -> AgentTaskKind.CODE

            listOf(
                "подробно проанализируй", "сравни варианты", "разбери по шагам",
                "сложная задача", "составь стратегию", "архитектура"
            ).any(value::contains) -> AgentTaskKind.COMPLEX_REASONING

            else -> AgentTaskKind.GENERAL
        }
    }

    fun route(prompt: String, hybridMode: Boolean): AgentRoute {
        val task = classify(prompt)

        // Never pretend a language model has executed an Android action or searched the web.
        when (task) {
            AgentTaskKind.DEVICE_ACTION -> return AgentRoute(
                task = task,
                provider = AgentProviderKind.BLOCKED,
                reason = "Для этой задачи нужен исполнитель Android-действий.",
                warning = "Управление Android ещё не подключено. Действие не выполнялось."
            )
            AgentTaskKind.CURRENT_INFORMATION -> return AgentRoute(
                task = task,
                provider = AgentProviderKind.BLOCKED,
                reason = "Для этой задачи нужен инструмент интернет-поиска.",
                warning = "Интернет-поиск ещё не подключён. Актуальные данные не проверялись."
            )
            else -> Unit
        }

        val cloudPreferred = task in setOf(
            AgentTaskKind.GENERAL,
            AgentTaskKind.COMPLEX_REASONING,
            AgentTaskKind.CODE
        )

        if (hybridMode && cloudPreferred && cloud?.isAvailable == true) {
            return AgentRoute(
                task = task,
                provider = AgentProviderKind.CLOUD,
                reason = "Задача направлена облачному провайдеру."
            )
        }

        val warning = when {
            !local.isAvailable -> "Локальная модель не готова."
            hybridMode && cloudPreferred && cloud == null ->
                "Облачный провайдер ещё не подключён. Используется локальная модель."
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

        if (decision.provider == AgentProviderKind.BLOCKED) {
            throw IllegalStateException(decision.warning ?: decision.reason)
        }

        val providerPrompt = if (decision.provider == AgentProviderKind.CLOUD) {
            prompt.removeSuffix("/no_think").trimEnd()
        } else prompt

        when (decision.provider) {
            AgentProviderKind.LOCAL -> {
                check(local.isAvailable) { decision.warning ?: "Локальная модель не готова к генерации." }
                emitAll(local.generate(prompt, maxTokens))
            }
            AgentProviderKind.CLOUD -> {
                val provider = cloud ?: error("Облачный провайдер не подключён.")
                check(provider.isAvailable) { "Облачный провайдер не настроен." }
                var cloudProducedOutput = false
                provider.generate(providerPrompt, maxTokens)
                    .onEach { chunk -> if (chunk.isNotEmpty()) cloudProducedOutput = true }
                    .catch { error ->
                        if (error is CancellationException) throw error
                        // Do not append a second answer after a provider has already emitted text.
                        if (cloudProducedOutput) throw error
                        Log.w(TAG, "Cloud request failed; considering local fallback (${error.javaClass.simpleName}).")
                        check(local.isAvailable) {
                            "Gemini недоступен (${error.message ?: error.javaClass.simpleName}), локальная модель не готова."
                        }
                        emit("Gemini недоступен (${error.message ?: error.javaClass.simpleName}). Переключаюсь на локальную модель.\n\n")
                        emitAll(local.generate(prompt, maxTokens))
                    }
                    .collect { emit(it) }
            }
            AgentProviderKind.BLOCKED -> error("Заблокированный маршрут не должен запускать генерацию.")
        }
    }

    companion object {
        private const val TAG = "AgentCoordinator"

        fun forLocalEngine(engine: InferenceEngine): AgentCoordinator =
            AgentCoordinator(
                local = LocalLlamaProvider(engine),
                visionReady = (engine as? VisionInferenceEngine)?.isVisionReady() == true
            )

        fun forContext(context: Context, engine: InferenceEngine?): AgentCoordinator {
            val keyStore = GeminiApiKeyStore(context.applicationContext)
            return AgentCoordinator(
                local = engine?.let(::LocalLlamaProvider) ?: UnavailableLocalProvider,
                cloud = GeminiCloudProvider(keyStore),
                visionReady = (engine as? VisionInferenceEngine)?.isVisionReady() == true
            )
        }
    }
}

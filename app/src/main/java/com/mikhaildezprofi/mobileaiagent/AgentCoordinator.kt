package com.mikhaildezprofi.mobileaiagent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

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
    fun generate(prompt: String, maxTokens: Int, temperature: Double): Flow<String> =
        generate(prompt, maxTokens)
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

interface CloudModelProvider : AgentModelProvider

class AgentCoordinator(
    private val local: AgentModelProvider,
    private val cloud: CloudModelProvider? = null,
    private val allowCloud: Boolean = true
) {
    fun capabilities(): List<AgentCapability> = AgentToolRegistry.forRuntime(
        localReady = local.isAvailable,
        cloudReady = allowCloud && cloud?.isAvailable == true,
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

    fun route(
        prompt: String,
        hybridMode: Boolean,
        routing: AgentRoutingConfig = AgentRoutingConfig()
    ): AgentRoute {
        val task = classify(prompt)
        val taskId = task.name.lowercase()
        val cloudPreferred = routing.prefersCloud(taskId)
        val localOnly = taskId in routing.localOnlyTasks
        val cloudAllowed = allowCloud && hybridMode && cloudPreferred && !localOnly

        if (cloudAllowed && cloud?.isAvailable == true) {
            return AgentRoute(task, AgentProviderKind.CLOUD, "Задача направлена облачному провайдеру по runtime-маршруту.")
        }

        val warning = when {
            task == AgentTaskKind.FILE_ANALYSIS ->
                "Выбранные файлы обрабатываются локально. Передача вложений в облако запрещена текущей политикой."
            task == AgentTaskKind.IMAGE_ANALYSIS ->
                "Анализ изображений выполняется только совместимым локальным vision-модулем."
            task == AgentTaskKind.DEVICE_ACTION ->
                "Инструменты управления Android ещё не подключены. Действие не выполняется."
            task == AgentTaskKind.CURRENT_INFORMATION && !routing.currentInformationEnabled ->
                "Поиск актуальной информации выключен в runtime-конфигурации и не подключён в клиенте."
            task == AgentTaskKind.CURRENT_INFORMATION ->
                "Флаг поиска включён, но поисковый провайдер ещё не встроен в APK."
            !local.isAvailable -> "Локальная модель не готова."
            cloudPreferred && !allowCloud ->
                "Облачный API отключён runtime-настройками. Используется локальная модель."
            hybridMode && cloudPreferred && cloud == null ->
                "Облачный провайдер не подключён. Запрос останется локальным."
            hybridMode && cloudPreferred && cloud?.isAvailable == false ->
                "Облачный провайдер не настроен. Используется локальная модель."
            else -> null
        }
        return AgentRoute(
            task = task,
            provider = AgentProviderKind.LOCAL,
            reason = when {
                !hybridMode && cloudPreferred -> "Выбран локальный режим."
                cloudPreferred -> "Используется локальная модель согласно fallback-политике."
                else -> "Используется локальная модель согласно runtime-маршруту."
            },
            warning = warning
        )
    }

    fun generate(
        prompt: String,
        maxTokens: Int,
        hybridMode: Boolean,
        routing: AgentRoutingConfig = AgentRoutingConfig(),
        systemPrompt: String = "",
        temperature: Double = 0.6
    ): Flow<String> = flow {
        val decision = route(prompt, hybridMode, routing)
        val taskId = decision.task.name.lowercase()
        val cloudWasRequested = hybridMode && routing.prefersCloud(taskId) &&
            taskId !in routing.localOnlyTasks
        if (cloudWasRequested && decision.provider == AgentProviderKind.LOCAL &&
            !routing.fallbackToLocal && allowCloud
        ) {
            throw IllegalStateException("Облачный провайдер недоступен, а fallbackToLocal выключен.")
        }

        val providerLimit = if (decision.provider == AgentProviderKind.CLOUD) {
            routing.cloudMaxTokens
        } else routing.localMaxTokens
        val effectiveMaxTokens = maxTokens.coerceIn(64, 8192).coerceAtMost(providerLimit)
        Log.i(TAG, "task=${decision.task}, provider=${decision.provider}, reason=${decision.reason}, maxTokens=$effectiveMaxTokens")
        Log.i(TAG, "capabilities=${capabilities().joinToString { "${it.id}:${it.available}" }}")

        when (decision.provider) {
            AgentProviderKind.LOCAL -> {
                check(local.isAvailable) { "Локальная модель не готова к генерации." }
                emitAll(local.generate(prompt, effectiveMaxTokens))
            }
            AgentProviderKind.CLOUD -> {
                val provider = cloud ?: error("Облачный провайдер не подключён.")
                check(provider.isAvailable) { "Облачный провайдер не настроен." }
                val userPrompt = prompt.removeSuffix("/no_think").trimEnd()
                val providerPrompt = if (systemPrompt.isBlank()) userPrompt else
                    "Системные инструкции:\n$systemPrompt\n\nЗапрос пользователя:\n$userPrompt"
                try {
                    emitAll(provider.generate(providerPrompt, effectiveMaxTokens, temperature.coerceIn(0.0, 2.0)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Cloud request failed; switching to local provider (${error.javaClass.simpleName}).")
                    check(routing.fallbackToLocal && local.isAvailable) {
                        "Gemini недоступен (${error.message ?: error.javaClass.simpleName}); локальный fallback запрещён или модель не готова."
                    }
                    emit("Gemini недоступен (${error.message ?: error.javaClass.simpleName}). Переключаюсь на локальную модель.\n\n")
                    emitAll(local.generate(prompt, maxTokens.coerceIn(64, 8192).coerceAtMost(routing.localMaxTokens)))
                }
            }
        }
    }

    companion object {
        private const val TAG = "AgentCoordinator"

        fun forLocalEngine(engine: InferenceEngine): AgentCoordinator =
            AgentCoordinator(LocalLlamaProvider(engine))

        fun forContext(
            context: Context,
            engine: InferenceEngine?,
            allowCloud: Boolean = true,
            cloudModelId: String = "gemini-3.5-flash-lite",
            connectTimeoutSeconds: Int = 15,
            readTimeoutSeconds: Int = 60
        ): AgentCoordinator {
            val keyStore = GeminiApiKeyStore(context.applicationContext)
            return AgentCoordinator(
                local = engine?.let(::LocalLlamaProvider) ?: UnavailableLocalProvider,
                cloud = if (allowCloud) GeminiCloudProvider(
                    keyStore,
                    model = cloudModelId,
                    connectTimeoutMs = connectTimeoutSeconds.coerceIn(5, 120) * 1000,
                    readTimeoutMs = readTimeoutSeconds.coerceIn(5, 300) * 1000
                ) else null,
                allowCloud = allowCloud
            )
        }
    }
}
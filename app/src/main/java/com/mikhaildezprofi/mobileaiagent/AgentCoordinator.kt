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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val visionReady: Boolean = false,
    private val planExecutionMutex: Mutex = Mutex(),
    private val executorRegistry: AgentExecutorRegistry = AgentExecutorRegistry.default()
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

    /**
     * Asks the best available coordinator model to plan complex/code requests, then executes
     * the validated plan through the existing provider router. Simple requests keep the fast path.
     */
    fun generateWithPlanning(
        prompt: String,
        maxTokens: Int,
        hybridMode: Boolean
    ): Flow<String> = flow {
        val task = classify(prompt)
        val needsPlan = task == AgentTaskKind.CODE || task == AgentTaskKind.COMPLEX_REASONING ||
            prompt.length > 1_200 || listOf("поэтапно", "несколько этапов", "сначала", "затем", "проект целиком")
                .any { phrase -> prompt.lowercase().contains(phrase) }
        if (!needsPlan) {
            emitAll(generate(prompt, maxTokens, hybridMode))
            return@flow
        }

        val plannerProvider = if (hybridMode && cloud?.isAvailable == true) cloud else local
        check(plannerProvider.isAvailable) {
            "Для составления плана нужна доступная модель. Настройте Gemini или загрузите локальную модель."
        }
        val plannerText = StringBuilder()
        try {
            plannerProvider.generate(AgentTaskPlanner.buildPrompt(prompt), AgentTaskPlanner.MAX_PLANNER_TOKENS)
                .collect { plannerText.append(it) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "Planning request failed; using single-step execution (${error.javaClass.simpleName}).")
        }

        val plan = if (plannerText.isNotBlank()) {
            runCatching { AgentTaskPlanner.parse(plannerText.toString(), prompt) }
                .onFailure { Log.w(TAG, "Planner response rejected; using single-step execution (${it.javaClass.simpleName}).") }
                .getOrNull()
        } else null
        val validatedPlan = plan ?: AgentTaskPlan.single(prompt).also {
            emit("Не удалось получить корректный план. Выполняю запрос одним шагом.\n\n")
        }

        executePlan(validatedPlan, maxTokens, hybridMode).collect { event ->
            when (event) {
                is AgentTaskEvent.PlanStarted -> if (event.plan.steps.size > 1) {
                    emit("План: ${event.plan.title} (${event.plan.steps.size} шага).\n\n")
                }
                is AgentTaskEvent.StepStarted -> if (event.total > 1) {
                    emit("\n[${event.index}/${event.total}] ${event.step.title} · ${event.step.agentRole}\n")
                }
                is AgentTaskEvent.StepOutput -> emit(event.text)
                is AgentTaskEvent.StepCompleted -> Unit
                is AgentTaskEvent.PlanCompleted -> Unit
            }
        }
    }

    /** Executes a validated plan sequentially; each step receives only completed dependency results. */
    fun executePlan(
        plan: AgentTaskPlan,
        maxTokensPerStep: Int,
        hybridMode: Boolean
    ): Flow<AgentTaskEvent> = flow {
        plan.validate()
        planExecutionMutex.withLock {
            emit(AgentTaskEvent.PlanStarted(plan))
            val results = linkedMapOf<String, String>()
            plan.steps.forEachIndexed { index, step ->
                emit(AgentTaskEvent.StepStarted(step, index + 1, plan.steps.size))
                val dependencyContext = step.dependsOn.joinToString("\n\n") { dependencyId ->
                    val dependency = plan.steps.first { it.id == dependencyId }
                    "Результат шага «${dependency.title}»:\n${results.getValue(dependencyId)}"
                }
                val stepPrompt = buildString {
                    appendLine("Ты выполняешь шаг ${index + 1} из ${plan.steps.size} общего плана «${plan.title}».")
                    appendLine("Исходная задача: ${plan.originalPrompt}")
                    if (dependencyContext.isNotBlank()) {
                        appendLine("Результаты необходимых предыдущих шагов:")
                        appendLine(dependencyContext)
                    }
                    appendLine("Роль исполнителя: ${step.agentRole}")
                    appendLine("Текущее задание: ${step.prompt}")
                    append("Выполни только это задание. Не утверждай, что внешние действия выполнены, если они не запускались и не проверялись.")
                }
                val result = StringBuilder()
                val executor = executorRegistry.resolve(step.agentRole)
                executor.execute(stepPrompt, maxTokensPerStep) { specializedPrompt, tokenLimit ->
                    generate(specializedPrompt, tokenLimit, hybridMode)
                }.collect { chunk ->
                    result.append(chunk)
                    emit(AgentTaskEvent.StepOutput(step.id, chunk))
                }
                val completed = result.toString()
                results[step.id] = completed
                emit(AgentTaskEvent.StepCompleted(step, completed))
            }
            emit(AgentTaskEvent.PlanCompleted(plan.id, results.toMap()))
        }
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

package com.mikhaildezprofi.mobileaiagent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCoordinatorTest {
    private class FakeProvider(
        override val id: String,
        override val isAvailable: Boolean = true
    ) : AgentModelProvider {
        override fun generate(prompt: String, maxTokens: Int): Flow<String> =
            flowOf("$id:$prompt")
    }

    private class FakeCloudProvider(
        available: Boolean = true
    ) : CloudModelProvider {
        override val id: String = "fake-cloud"
        override val isAvailable: Boolean = available
        override fun generate(prompt: String, maxTokens: Int): Flow<String> =
            flowOf("cloud:$prompt")
    }

    private class FakeMultimodalProvider : AgentModelProvider, AgentMultimodalModelProvider {
        override val id: String = "fake-pocketpal"
        override val isAvailable: Boolean = true
        var receivedPaths: List<String> = emptyList()
        var receivedModelPath: String = ""

        override fun generate(prompt: String, maxTokens: Int): Flow<String> = flowOf("text:$prompt")

        override fun generateWithImages(
            prompt: String,
            imagePaths: List<String>,
            modelPath: String,
            projectorPath: String,
            maxTokens: Int,
            temperature: Double,
            systemPrompt: String
        ): Flow<String> {
            receivedPaths = imagePaths
            receivedModelPath = modelPath
            return flowOf("vision:$prompt")
        }
    }


    private class ScriptedCloudProvider(
        private val script: (Int, String) -> Flow<String>
    ) : CloudModelProvider {
        override val id: String = "scripted-cloud"
        override val isAvailable: Boolean = true
        var calls: Int = 0
            private set
        val prompts = mutableListOf<String>()

        override fun generate(prompt: String, maxTokens: Int): Flow<String> {
            calls += 1
            prompts += prompt
            return script(calls, prompt)
        }
    }

    private class InMemoryTaskStore : AgentTaskPersistence {
        private val plans = linkedMapOf<String, SavedAgentPlan>()

        override fun savePlan(plan: AgentTaskPlan) {
            if (plan.id !in plans) {
                plans[plan.id] = SavedAgentPlan(
                    plan = plan,
                    status = AgentPlanStatus.QUEUED,
                    steps = plan.steps.map { SavedAgentStep(it, AgentStepStatus.PENDING) },
                    updatedAt = 1L
                )
            }
        }

        override fun setPlanStatus(planId: String, status: AgentPlanStatus) {
            val saved = plans.getValue(planId)
            plans[planId] = saved.copy(status = status, updatedAt = saved.updatedAt + 1)
        }

        override fun setStepStatus(planId: String, stepId: String, status: AgentStepStatus, error: String?) {
            val saved = plans.getValue(planId)
            plans[planId] = saved.copy(
                steps = saved.steps.map {
                    if (it.step.id == stepId) it.copy(status = status, error = error) else it
                },
                updatedAt = saved.updatedAt + 1
            )
        }

        override fun completeStep(planId: String, stepId: String, result: String) {
            val saved = plans.getValue(planId)
            plans[planId] = saved.copy(
                steps = saved.steps.map {
                    if (it.step.id == stepId) it.copy(
                        status = AgentStepStatus.COMPLETED,
                        result = result,
                        error = null
                    ) else it
                },
                updatedAt = saved.updatedAt + 1
            )
        }

        override fun loadPlan(planId: String): SavedAgentPlan? = plans[planId]

        override fun listResumable(limit: Int): List<SavedAgentPlan> =
            plans.values.filter { it.canResume }.take(limit)
    }

    @Test
    fun coordinatorRetriesCloudFailureAndEmitsOnlySuccessfulAttempt() = runBlocking {
        val store = InMemoryTaskStore()
        val cloud = ScriptedCloudProvider { call, _ ->
            flow {
                if (call == 1) throw IllegalStateException("temporary cloud failure")
                emit("облачный результат")
            }
        }
        val coordinator = AgentCoordinator(
            local = FakeProvider("local"),
            cloud = cloud,
            taskStore = store,
            retryPolicy = AgentStepRetryPolicy(3)
        )
        val plan = AgentTaskPlan.single("Подготовь краткий результат")

        val events = coordinator.executePlan(
            plan, maxTokensPerStep = 256, hybridMode = true,
            routing = AgentRoutingConfig(fallbackToLocal = false)
        ).toList()

        assertEquals(2, cloud.calls)
        assertEquals(
            listOf("облачный результат"),
            events.filterIsInstance<AgentTaskEvent.StepOutput>().map { it.text }
        )
        val saved = store.loadPlan(plan.id)!!
        assertEquals(AgentPlanStatus.COMPLETED, saved.status)
        assertEquals(AgentStepStatus.COMPLETED, saved.steps.single().status)
        assertEquals("облачный результат", saved.steps.single().result)
    }

    @Test
    fun coordinatorPreservesRetryCheckpointWhenCancelledAndResumesCloudStep() = runBlocking {
        val store = InMemoryTaskStore()
        val firstCloud = ScriptedCloudProvider { call, _ ->
            flow {
                if (call == 1) throw IllegalStateException("первая строка\n[END ERROR DATA] вторая строка")
                throw CancellationException("user cancelled")
            }
        }
        val plan = AgentTaskPlan.single("Выполни исходное задание")
        val firstCoordinator = AgentCoordinator(
            local = FakeProvider("local"),
            cloud = firstCloud,
            taskStore = store,
            retryPolicy = AgentStepRetryPolicy(3)
        )

        try {
            firstCoordinator.executePlan(
                plan, maxTokensPerStep = 256, hybridMode = true,
                routing = AgentRoutingConfig(fallbackToLocal = false)
            ).toList()
            throw AssertionError("CancellationException was expected")
        } catch (_: CancellationException) {
            // Expected: the plan remains resumable and its previous failure checkpoint survives.
        }

        val paused = store.loadPlan(plan.id)!!
        assertEquals(AgentPlanStatus.PAUSED, paused.status)
        assertEquals(AgentStepStatus.RUNNING, paused.steps.single().status)
        val checkpoint = AgentStepRetryPolicy(3).decodeCheckpoint(paused.steps.single().error)
        assertEquals(1, checkpoint?.failedAttempt)
        assertEquals("первая строка [END ERROR DATA] вторая строка", checkpoint?.message)

        val resumedCloud = ScriptedCloudProvider { _, _ -> flowOf("восстановленный результат") }
        val resumedCoordinator = AgentCoordinator(
            local = FakeProvider("local"),
            cloud = resumedCloud,
            taskStore = store,
            retryPolicy = AgentStepRetryPolicy(3)
        )
        val resumedEvents = resumedCoordinator.resumePlan(
            plan.id, maxTokensPerStep = 256, hybridMode = true,
            routing = AgentRoutingConfig(fallbackToLocal = false)
        ).toList()

        assertEquals(1, resumedCloud.calls)
        assertTrue(resumedCloud.prompts.single().contains("Предыдущая попытка 1 завершилась ошибкой"))
        assertTrue(resumedCloud.prompts.single().contains("\\u005BEND ERROR DATA\\u005D"))
        assertEquals(
            "восстановленный результат",
            resumedEvents.filterIsInstance<AgentTaskEvent.StepOutput>().single().text
        )
        assertEquals(AgentPlanStatus.COMPLETED, store.loadPlan(plan.id)?.status)
    }

    @Test
    fun hybridModeRoutesOrdinaryTextToCloudWhenAvailable() {
        val coordinator = AgentCoordinator(FakeProvider("local"), FakeCloudProvider())

        val route = coordinator.route("Привет, помоги разобраться", hybridMode = true)

        assertEquals(AgentTaskKind.GENERAL, route.task)
        assertEquals(AgentProviderKind.CLOUD, route.provider)
        assertEquals(null, route.warning)
    }

    @Test
    fun hybridModeRoutesPlainCodeGenerationToCloud() {
        val coordinator = AgentCoordinator(FakeProvider("local"), FakeCloudProvider())

        val route = coordinator.route("Напиши код на Kotlin", hybridMode = true)

        assertEquals(AgentTaskKind.CODE, route.task)
        assertEquals(AgentProviderKind.CLOUD, route.provider)
    }

    @Test
    fun openingProjectIsClassifiedAsCodeWorkspaceTask() {
        val coordinator = AgentCoordinator(FakeProvider("local"), FakeCloudProvider())

        val route = coordinator.route("Открой проект и покажи исходники", hybridMode = true)

        assertEquals(AgentTaskKind.CODE, route.task)
        assertEquals(AgentProviderKind.LOCAL, route.provider)
    }

    @Test
    fun hybridModeRoutesProjectFileEditsToLocalCodeTools() {
        val coordinator = AgentCoordinator(FakeProvider("local"), FakeCloudProvider())

        val route = coordinator.route("Исправь файл MainActivity.kt в проекте", hybridMode = true)

        assertEquals(AgentTaskKind.CODE, route.task)
        assertEquals(AgentProviderKind.LOCAL, route.provider)
    }

    @Test
    fun localModeNeverRoutesToCloud() {
        val coordinator = AgentCoordinator(FakeProvider("local"), FakeCloudProvider())

        val route = coordinator.route("Напиши код на Kotlin", hybridMode = false)

        assertEquals(AgentTaskKind.CODE, route.task)
        assertEquals(AgentProviderKind.LOCAL, route.provider)
    }

    @Test
    fun sensitiveAndUnsupportedTasksStayLocalInHybridMode() {
        val coordinator = AgentCoordinator(FakeProvider("local"), FakeCloudProvider())

        val cases = listOf(
            "посмотри фото" to AgentTaskKind.IMAGE_ANALYSIS,
            "прочитай файл" to AgentTaskKind.FILE_ANALYSIS,
            "открой настройки" to AgentTaskKind.DEVICE_ACTION,
            "погода сегодня" to AgentTaskKind.CURRENT_INFORMATION
        )

        cases.forEach { (prompt, expectedTask) ->
            val route = coordinator.route(prompt, hybridMode = true)
            assertEquals(prompt, expectedTask, route.task)
            assertEquals(prompt, AgentProviderKind.LOCAL, route.provider)
            assertNotNull(prompt, route.warning)
        }
    }

    @Test
    fun hybridModeFallsBackToLocalWhenCloudIsNotConfigured() {
        val coordinator = AgentCoordinator(FakeProvider("local"), FakeCloudProvider(available = false))

        val route = coordinator.route("Привет", hybridMode = true)

        assertEquals(AgentProviderKind.LOCAL, route.provider)
        assertTrue(route.warning?.contains("не настроен") == true)
    }

    @Test
    fun complexReasoningIsRecognizedAndUsesCloudInHybridMode() {
        val coordinator = AgentCoordinator(FakeProvider("local"), FakeCloudProvider())

        val route = coordinator.route("Составь стратегию проекта", hybridMode = true)

        assertEquals(AgentTaskKind.COMPLEX_REASONING, route.task)
        assertEquals(AgentProviderKind.CLOUD, route.provider)
    }

    @Test
    fun runtimeRoutingCanMoveGeneralRequestsToLocalOnly() {
        val coordinator = AgentCoordinator(FakeProvider("local"), FakeCloudProvider())
        val routing = AgentRoutingConfig(
            cloudPreferredTasks = emptySet(),
            localOnlyTasks = setOf("file_analysis", "image_analysis", "device_action")
        )

        val route = coordinator.route("Привет, помоги разобраться", hybridMode = true, routing = routing)

        assertEquals(AgentProviderKind.LOCAL, route.provider)
    }

    @Test
    fun runtimeLocalOnlyRuleOverridesCloudPreference() {
        val coordinator = AgentCoordinator(FakeProvider("local"), FakeCloudProvider())
        val routing = AgentRoutingConfig(
            cloudPreferredTasks = setOf("general", "code"),
            localOnlyTasks = setOf("general")
        )

        val route = coordinator.route("Привет", hybridMode = true, routing = routing)

        assertEquals(AgentProviderKind.LOCAL, route.provider)
    }

    @Test
    fun runtimeRoutingStillHonorsHybridOff() {
        val coordinator = AgentCoordinator(FakeProvider("local"), FakeCloudProvider())
        val routing = AgentRoutingConfig(cloudPreferredTasks = setOf("general"))

        val route = coordinator.route("Привет", hybridMode = false, routing = routing)

        assertEquals(AgentProviderKind.LOCAL, route.provider)
    }

    @Test
    fun visualSummaryCanBeRoutedToCloudAsGeneralLanguageTask() {
        val coordinator = AgentCoordinator(FakeProvider("local"), FakeCloudProvider())

        val route = coordinator.route(
            prompt = "Запрос пользователя: что на фото? Локальный анализ: на изображении собака.",
            hybridMode = true,
            taskOverride = AgentTaskKind.GENERAL
        )

        assertEquals(AgentTaskKind.GENERAL, route.task)
        assertEquals(AgentProviderKind.CLOUD, route.provider)
    }

    @Test
    fun imageInferenceUsesLocalMultimodalProviderAndNeverCloud() = runBlocking {
        val local = FakeMultimodalProvider()
        val coordinator = AgentCoordinator(local, FakeCloudProvider())

        val output = coordinator.generateWithImages(
            prompt = "Опиши изображение",
            imagePaths = listOf("/tmp/photo-1.jpg", "/tmp/photo-2.jpg"),
            modelPath = "/models/SmolVLM.gguf",
            projectorPath = "/models/mmproj.gguf",
            maxTokens = 512,
            systemPrompt = "Отвечай кратко"
        ).toList()

        assertEquals(listOf("vision:Опиши изображение"), output)
        assertEquals(listOf("/tmp/photo-1.jpg", "/tmp/photo-2.jpg"), local.receivedPaths)
        assertEquals("/models/SmolVLM.gguf", local.receivedModelPath)
    }

    @Test
    fun plannerPromptDefinesSupportedRolesAndStepLimit() {
        val prompt = AgentTaskPlanner.buildPrompt("Разработай приложение")

        assertTrue(prompt.contains("12 шагов"))
        assertTrue(prompt.contains("developer"))
        assertTrue(prompt.contains("depends_on"))
    }

    @Test
    fun executorRegistryFallsBackToGeneralForUnknownRole() {
        val registry = AgentExecutorRegistry.default()

        assertEquals(AgentRole.DEVELOPER, registry.resolve("developer").role)
        assertEquals(AgentRole.GENERAL, registry.resolve("missing-role").role)
    }

    @Test
    fun taskPlanRejectsForwardDependencies() {
        val plan = AgentTaskPlan(
            title = "Некорректный план",
            originalPrompt = "Проверка",
            steps = listOf(
                AgentTaskStep(id = "first", title = "Первый", prompt = "Шаг", dependsOn = listOf("second")),
                AgentTaskStep(id = "second", title = "Второй", prompt = "Шаг")
            )
        )

        var rejected = false
        try {
            plan.validate()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun currentInformationNeverBypassesMissingSearchProvider() {
        val coordinator = AgentCoordinator(FakeProvider("local"), FakeCloudProvider())
        val routing = AgentRoutingConfig(
            cloudPreferredTasks = setOf("current_information"),
            currentInformationEnabled = true
        )

        val route = coordinator.route("Какая сейчас погода?", hybridMode = true, routing = routing)

        assertEquals(AgentTaskKind.CURRENT_INFORMATION, route.task)
        assertEquals(AgentProviderKind.LOCAL, route.provider)
        assertTrue(route.warning?.contains("поисковый провайдер") == true)
    }
}

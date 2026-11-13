package com.mikhaildezprofi.mobileaiagent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCoordinatorTest {
    @Test
    fun hybridTextAndCodePreferCloudWhenConfigured() {
        val coordinator = AgentCoordinator(
            local = FakeProvider("local", true),
            cloud = FakeCloudProvider("cloud", true)
        )
        assertEquals(AgentProviderKind.CLOUD, coordinator.route("Привет", true).provider)
        assertEquals(AgentProviderKind.CLOUD, coordinator.route("Напиши код на Kotlin", true).provider)
    }

    @Test
    fun localModeNeverRoutesToCloud() {
        val coordinator = AgentCoordinator(
            local = FakeProvider("local", true),
            cloud = FakeCloudProvider("cloud", true)
        )
        assertEquals(AgentProviderKind.LOCAL, coordinator.route("Привет", false).provider)
    }

    @Test
    fun unsupportedWebAndDeviceActionsAreBlocked() {
        val coordinator = AgentCoordinator(local = FakeProvider("local", true))
        assertEquals(AgentProviderKind.BLOCKED, coordinator.route("Какая погода сегодня?", true).provider)
        assertEquals(AgentProviderKind.BLOCKED, coordinator.route("Открой настройки", true).provider)
    }

    @Test
    fun cloudFailureBeforeOutputFallsBackToLocal() = runBlocking {
        val local = FakeProvider("local", true, "local-answer")
        val cloud = object : CloudModelProvider {
            override val id = "cloud"
            override val isAvailable = true
            override fun generate(prompt: String, maxTokens: Int): Flow<String> = flow {
                throw IllegalStateException("temporary cloud error")
            }
        }
        val result = AgentCoordinator(local, cloud)
            .generate("Привет", 128, hybridMode = true)
            .toList()
            .joinToString("")

        assertTrue(result.contains("Переключаюсь на локальную модель"))
        assertTrue(result.endsWith("local-answer"))
    }

    @Test
    fun cloudFailureAfterPartialOutputDoesNotAppendLocalAnswer() = runBlocking {
        val local = FakeProvider("local", true, "local-answer")
        val cloud = object : CloudModelProvider {
            override val id = "cloud"
            override val isAvailable = true
            override fun generate(prompt: String, maxTokens: Int): Flow<String> = flow {
                emit("partial-cloud-answer")
                throw IllegalStateException("stream interrupted")
            }
        }

        var failed = false
        try {
            AgentCoordinator(local, cloud)
                .generate("Привет", 128, hybridMode = true)
                .toList()
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        assertFalse(local.wasCalled)
    }

    @Test
    fun planRunsStepsInOrderAndPassesDependencyResults() = runBlocking {
        val prompts = mutableListOf<String>()
        val provider = object : AgentModelProvider {
            override val id = "local"
            override val isAvailable = true
            override fun generate(prompt: String, maxTokens: Int): Flow<String> = flow {
                prompts += prompt
                emit("result-${prompts.size}")
            }
        }
        val first = AgentTaskStep(id = "research", title = "Собрать данные", prompt = "Собери исходные данные")
        val second = AgentTaskStep(
            id = "summary", title = "Сделать вывод", prompt = "Сформулируй вывод",
            dependsOn = listOf("research")
        )
        val plan = AgentTaskPlan(title = "Анализ", originalPrompt = "Проанализируй тему", steps = listOf(first, second))

        val events = AgentCoordinator(provider).executePlan(plan, 128, false).toList()

        assertEquals(2, prompts.size)
        assertTrue(prompts[1].contains("Результат шага «Собрать данные»"))
        assertTrue(prompts[1].contains("result-1"))
        assertEquals(2, events.filterIsInstance<AgentTaskEvent.StepCompleted>().size)
        assertTrue(events.last() is AgentTaskEvent.PlanCompleted)
    }

    @Test
    fun complexRequestUsesCloudCoordinatorPlanThenExecutesItsSteps() = runBlocking {
        val cloudPrompts = mutableListOf<String>()
        val cloud = object : CloudModelProvider {
            override val id = "gemini"
            override val isAvailable = true
            override fun generate(prompt: String, maxTokens: Int): Flow<String> = flow {
                cloudPrompts += prompt
                if (prompt.contains("главный координатор ИИ-агента")) {
                    emit("""{"title":"План","steps":[{"id":"a","title":"Анализ","task":"Проанализируй задачу","agent":"research","depends_on":[]},{"id":"b","title":"Вывод","task":"Сформулируй вывод","agent":"writer","depends_on":["a"]}]}""")
                } else {
                    emit("step-result-${cloudPrompts.size}")
                }
            }
        }

        val output = AgentCoordinator(FakeProvider("local", true), cloud)
            .generateWithPlanning("Разработай приложение и подробно проанализируй архитектуру", 256, true)
            .toList()
            .joinToString("")

        assertEquals(3, cloudPrompts.size)
        assertTrue(cloudPrompts[0].contains("главный координатор ИИ-агента"))
        assertTrue(cloudPrompts[2].contains("Роль исполнителя: writer"))
        assertTrue(output.contains("[1/2] Анализ"))
        assertTrue(output.contains("step-result-2"))
        assertTrue(output.contains("step-result-3"))
    }

    @Test
    fun plannerParsesStructuredJsonAndPreservesRolesAndDependencies() {
        val raw = """
            ```json
            {
              "title": "Разработка",
              "steps": [
                {"id":"research", "title":"Исследование", "task":"Изучи требования", "agent":"research", "depends_on":[]},
                {"id":"build", "title":"Разработка", "task":"Напиши реализацию", "agent":"developer", "depends_on":["research"]}
              ]
            }
            ```
        """.trimIndent()

        val plan = AgentTaskPlanner.parse(raw, "Создай приложение")

        assertEquals("Разработка", plan.title)
        assertEquals("developer", plan.steps[1].agentRole)
        assertEquals(listOf("research"), plan.steps[1].dependsOn)
    }

    @Test
    fun plannerRejectsUnknownDependenciesAndTooManySteps() {
        val unknownDependency = """{"title":"x","steps":[{"id":"a","task":"A","depends_on":["missing"]}]}"""
        var rejected = false
        try {
            AgentTaskPlanner.parse(unknownDependency, "task")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)

        val tooMany = (1..(AgentTaskPlan.MAX_STEPS + 1)).joinToString(",") {
            """{"id":"s$it","task":"step $it","depends_on":[]}"""
        }
        rejected = false
        try {
            AgentTaskPlanner.parse("""{"title":"x","steps":[$tooMany]}""", "task")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun planRejectsForwardDependencies() {
        val stepA = AgentTaskStep(id = "a", title = "A", prompt = "A", dependsOn = listOf("b"))
        val stepB = AgentTaskStep(id = "b", title = "B", prompt = "B")
        val plan = AgentTaskPlan(title = "Invalid", originalPrompt = "Task", steps = listOf(stepA, stepB))

        var rejected = false
        try {
            plan.validate()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun visionCapabilityReflectsRuntimeState() {
        val ready = AgentCoordinator(FakeProvider("local", true), visionReady = true)
        val notReady = AgentCoordinator(FakeProvider("local", true), visionReady = false)

        assertTrue(ready.capabilities().first { it.id == AgentCapabilityId.IMAGE_UNDERSTANDING }.available)
        assertFalse(notReady.capabilities().first { it.id == AgentCapabilityId.IMAGE_UNDERSTANDING }.available)
    }

    @Test
    fun executorRegistrySelectsSpecializedRoleAndFallsBackForUnknownRole() {
        val registry = AgentExecutorRegistry.default()
        assertTrue(registry.supportedRoles().containsAll(
            setOf("general", "research", "analyst", "developer", "tester", "reviewer", "writer")
        ))
        assertEquals(
            AgentRole.DEVELOPER,
            registry.resolve("developer").role
        )
        assertEquals(
            AgentRole.GENERAL,
            registry.resolve("unregistered_tool").role
        )
    }

    @Test
    fun developerExecutorAddsItsOwnInstructionsBeforeCallingProvider() = runBlocking {
        var capturedPrompt = ""
        val registry = AgentExecutorRegistry.default()
        val executor = registry.resolve("developer")
        executor.execute("Напиши функцию", 128) { prompt, _ ->
            flow {
                capturedPrompt = prompt
                emit("код")
            }
        }.toList()

        assertTrue(capturedPrompt.contains("Специализация исполнителя: developer"))
        assertTrue(capturedPrompt.contains("Работай как разработчик"))
        assertTrue(capturedPrompt.contains("Напиши функцию"))
    }

    @Test
    fun resumePlanKeepsCompletedStepAndRunsOnlyPendingSteps() = runBlocking {
        val first = AgentTaskStep(id = "first", title = "Первый", prompt = "Первый шаг")
        val second = AgentTaskStep(
            id = "second", title = "Второй", prompt = "Второй шаг",
            dependsOn = listOf("first")
        )
        val plan = AgentTaskPlan(
            id = "saved-plan", title = "Восстановление", originalPrompt = "Общая задача",
            steps = listOf(first, second)
        )
        val store = MemoryTaskStore()
        store.savePlan(plan)
        store.completeStep(plan.id, first.id, "Уже сохранённый результат")
        store.setPlanStatus(plan.id, AgentPlanStatus.PAUSED)

        val prompts = mutableListOf<String>()
        val provider = object : AgentModelProvider {
            override val id = "local"
            override val isAvailable = true
            override fun generate(prompt: String, maxTokens: Int): Flow<String> = flow {
                prompts += prompt
                emit("Новый результат")
            }
        }

        val events = AgentCoordinator(provider, taskStore = store)
            .resumePlan(plan.id, 128, false).toList()

        assertEquals(1, prompts.size)
        assertTrue(prompts.single().contains("Уже сохранённый результат"))
        assertTrue(events.last() is AgentTaskEvent.PlanCompleted)
        assertEquals(AgentPlanStatus.COMPLETED, store.loadPlan(plan.id)?.status)
        assertEquals(AgentStepStatus.COMPLETED, store.loadPlan(plan.id)?.steps?.last()?.status)
    }

    private class MemoryTaskStore : AgentTaskPersistence {
        private val records = linkedMapOf<String, SavedAgentPlan>()

        override fun savePlan(plan: AgentTaskPlan) {
            if (plan.id !in records) {
                records[plan.id] = SavedAgentPlan(
                    plan, AgentPlanStatus.QUEUED,
                    plan.steps.map { SavedAgentStep(it, AgentStepStatus.PENDING) },
                    System.currentTimeMillis()
                )
            } else {
                setPlanStatus(plan.id, AgentPlanStatus.RUNNING)
            }
        }

        override fun setPlanStatus(planId: String, status: AgentPlanStatus) {
            val current = records.getValue(planId)
            records[planId] = current.copy(status = status, updatedAt = System.currentTimeMillis())
        }

        override fun setStepStatus(
            planId: String, stepId: String, status: AgentStepStatus, error: String?
        ) {
            val current = records.getValue(planId)
            records[planId] = current.copy(
                steps = current.steps.map {
                    if (it.step.id == stepId) it.copy(status = status, error = error) else it
                },
                updatedAt = System.currentTimeMillis()
            )
        }

        override fun completeStep(planId: String, stepId: String, result: String) {
            val current = records.getValue(planId)
            records[planId] = current.copy(
                steps = current.steps.map {
                    if (it.step.id == stepId) it.copy(
                        status = AgentStepStatus.COMPLETED, result = result, error = null
                    ) else it
                },
                updatedAt = System.currentTimeMillis()
            )
        }

        override fun loadPlan(planId: String): SavedAgentPlan? = records[planId]

        override fun listResumable(limit: Int): List<SavedAgentPlan> =
            records.values.filter { it.canResume }.take(limit)
    }

    private open class FakeProvider(
        override val id: String,
        override val isAvailable: Boolean,
        private val answer: String = "local-answer"
    ) : AgentModelProvider {
        var wasCalled: Boolean = false
            private set

        override fun generate(prompt: String, maxTokens: Int): Flow<String> = flow {
            wasCalled = true
            emit(answer)
        }
    }

    private class FakeCloudProvider(
        id: String,
        isAvailable: Boolean
    ) : FakeProvider(id, isAvailable), CloudModelProvider
}

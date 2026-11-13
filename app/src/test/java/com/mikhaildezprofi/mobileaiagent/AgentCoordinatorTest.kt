package com.mikhaildezprofi.mobileaiagent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

    @Test
    fun hybridModeRoutesOrdinaryTextToCloudWhenAvailable() {
        val coordinator = AgentCoordinator(FakeProvider("local"), FakeCloudProvider())

        val route = coordinator.route("Привет, помоги разобраться", hybridMode = true)

        assertEquals(AgentTaskKind.GENERAL, route.task)
        assertEquals(AgentProviderKind.CLOUD, route.provider)
        assertEquals(null, route.warning)
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

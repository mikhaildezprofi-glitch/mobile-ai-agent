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
}

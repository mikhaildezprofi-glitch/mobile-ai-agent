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
    fun visionCapabilityReflectsRuntimeState() {
        val ready = AgentCoordinator(FakeProvider("local", true), visionReady = true)
        val notReady = AgentCoordinator(FakeProvider("local", true), visionReady = false)

        assertTrue(ready.capabilities().first { it.id == AgentCapabilityId.IMAGE_UNDERSTANDING }.available)
        assertFalse(notReady.capabilities().first { it.id == AgentCapabilityId.IMAGE_UNDERSTANDING }.available)
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

package com.mikhaildezprofi.mobileaiagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketPalNativeBridgeTest {
    @Test
    fun parsesStructuredToolCallsFromNativeResult() {
        val result = PocketPalNativeBridge.parseResult(
            """{"content":"","reasoning_content":"hidden","tool_calls":[{"id":"call_1","name":"calculate","arguments":"{\"expression\":\"6*7\"}"}],"interrupted":false}"""
        )

        assertEquals(1, result.toolCalls.size)
        assertEquals("calculate", result.toolCalls.single().name)
        assertEquals("{\"expression\":\"6*7\"}", result.toolCalls.single().argumentsJson)
        assertEquals("hidden", result.reasoning)
        assertFalse(result.interrupted)
    }

    @Test
    fun parsesOrdinaryTextResponseWithoutToolCalls() {
        val result = PocketPalNativeBridge.parseResult(
            """{"content":"Ответ 42","reasoning_content":"","tool_calls":[],"interrupted":false}"""
        )

        assertEquals("Ответ 42", result.content)
        assertTrue(result.toolCalls.isEmpty())
        assertFalse(result.interrupted)
    }

    @Test
    fun keepsInterruptedState() {
        val result = PocketPalNativeBridge.parseResult(
            """{"content":"","reasoning_content":"","tool_calls":[],"interrupted":true}"""
        )
        assertTrue(result.interrupted)
    }

    @Test
    fun exposesOnlyAllowlistedStructuredToolSchemas() {
        val tools = AgentTalentRegistry.default().openAiToolDefinitions()
        assertEquals(2, tools.length())
        assertEquals("calculate", tools.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("datetime", tools.getJSONObject(1).getJSONObject("function").getString("name"))
        assertFalse(tools.toString().contains("shell"))
        assertFalse(tools.toString().contains("adb"))
    }
}

package com.mikhaildezprofi.mobileaiagent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTalentsTest {
    @Test
    fun calculatorSupportsPrecedenceParenthesesAndFunctions() {
        val calculator = CalculateTalent()
        assertEquals("2*(3+4) = 14", calculator.execute(JSONObject().put("expression", "2*(3+4)")))
        assertEquals("sqrt(144)+pi = 15.14159265", calculator.execute(JSONObject().put("expression", "sqrt(144)+pi")))
    }

    @Test
    fun calculatorRejectsDivisionByZeroAndCodeLikeInput() {
        val calculator = CalculateTalent()
        assertTrue(runCatching { calculator.execute(JSONObject().put("expression", "1/0")) }.isFailure)
        assertTrue(runCatching { calculator.execute(JSONObject().put("expression", "1.constructor")) }.isFailure)
    }

    @Test
    fun datetimeRejectsUnknownTimezone() {
        assertTrue(runCatching {
            DateTimeTalent().execute(JSONObject().put("timezone", "Not/A_Real_Zone"))
        }.isFailure)
    }

    @Test
    fun registryReturnsErrorForUnknownTool() {
        val result = AgentTalentRegistry.default().execute(
            AgentToolInvocation("delete_everything", JSONObject())
        )
        assertTrue(result.contains("не зарегистрирован"))
    }

    @Test
    fun registryExposesOnlyStructuredAllowlistedTools() {
        val tools = AgentTalentRegistry.default().openAiToolDefinitions()
        assertEquals(2, tools.length())
        assertEquals("calculate", tools.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("datetime", tools.getJSONObject(1).getJSONObject("function").getString("name"))
        assertTrue(!tools.toString().contains("shell"))
        assertTrue(!tools.toString().contains("adb"))
    }
}

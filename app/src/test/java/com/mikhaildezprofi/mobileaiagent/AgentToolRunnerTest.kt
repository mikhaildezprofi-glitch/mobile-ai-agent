package com.mikhaildezprofi.mobileaiagent

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class AgentToolRunnerTest {
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
    fun protocolOnlyAcceptsExplicitToolCallEnvelope() {
        assertEquals(null, AgentToolProtocol.parseInvocation("Обычный ответ."))
        val call = AgentToolProtocol.parseInvocation(
            """{"tool_call":{"name":"calculate","arguments":{"expression":"6*7"}}}"""
        )
        assertEquals("calculate", call?.name)
        assertEquals("6*7", call?.arguments?.getString("expression"))
    }

    @Test
    fun runnerExecutesToolThenReturnsFinalAnswer() = runBlocking {
        var invocations = 0
        val runner = AgentToolRunner()
        val output = StringBuilder()
        runner.run("Сколько будет 6 умножить на 7?", 256) { _, _ ->
            invocations++
            if (invocations == 1) flowOf(
                """{"tool_call":{"name":"calculate","arguments":{"expression":"6*7"}}}"""
            ) else flowOf("Ответ: 42")
        }.collect { chunk -> output.append(chunk) }

        assertEquals(2, invocations)
        assertTrue(output.toString().contains("42"))
    }

    @Test
    fun runnerStopsAfterFiveToolCallsAndRequestsFinalAnswer() = runBlocking {
        var invocations = 0
        val repeatedCall = """{"tool_call":{"name":"datetime","arguments":{}}}"""
        val output = StringBuilder()
        AgentToolRunner().run("Тест лимита", 128) { _, _ ->
            invocations++
            if (invocations <= AgentToolRunner.DEFAULT_MAX_TOOL_CALLS) flowOf(repeatedCall)
            else flowOf("Готово")
        }.collect(output::append)

        assertEquals(AgentToolRunner.DEFAULT_MAX_TOOL_CALLS + 1, invocations)
        assertEquals("Готово", output.toString())
    }

    @Test
    fun unknownToolIsReturnedAsAnErrorInsteadOfExecuted() {
        val result = AgentTalentRegistry.default().execute(
            AgentToolInvocation("delete_everything", JSONObject())
        )
        assertTrue(result.contains("не зарегистрирован"))
    }
}

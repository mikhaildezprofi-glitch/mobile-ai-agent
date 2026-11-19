package com.mikhaildezprofi.mobileaiagent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PocketPalStructuredAgentRunnerTest {
    private class FakeEngine(
        private val responses: ArrayDeque<PocketPalGenerationResult>
    ) : PocketPalGenerationEngine {
        val requests = mutableListOf<Pair<JSONArray, JSONArray>>()

        override fun generate(
            messages: JSONArray,
            tools: JSONArray,
            maxTokens: Int,
            temperature: Float,
            listener: PocketPalSnapshotListener?
        ): PocketPalGenerationResult {
            requests += JSONArray(messages.toString()) to JSONArray(tools.toString())
            return responses.removeFirst()
        }
    }

    private fun toolCall(id: String, name: String, arguments: String) =
        PocketPalToolCall(id, name, arguments)

    private fun result(
        content: String = "",
        calls: List<PocketPalToolCall> = emptyList()
    ) = PocketPalGenerationResult(content, "", calls, false)

    @Test
    fun executesStructuredCalculationAndReturnsFinalAnswer() {
        val engine = FakeEngine(
            ArrayDeque(
                listOf(
                    result(calls = listOf(toolCall("call_1", "calculate", """{"expression":"6*7"}"""))),
                    result("Ответ: 42")
                )
            )
        )

        val final = PocketPalStructuredAgentRunner(engine).generate(
            JSONArray().put(JSONObject().put("role", "user").put("content", "Сколько будет 6*7?")),
            maxTokens = 256,
            temperature = 0.2f
        )

        assertEquals("Ответ: 42", final.content)
        assertEquals(2, engine.requests.size)
        val continuation = engine.requests[1].first
        assertEquals("assistant", continuation.getJSONObject(1).getString("role"))
        assertEquals("tool", continuation.getJSONObject(2).getString("role"))
        assertTrue(continuation.getJSONObject(2).getString("content").contains("42"))
        assertEquals(2, engine.requests[0].second.length())
    }

    @Test
    fun generatedIdsRemainUniqueForMultipleCallsWithoutIds() {
        val engine = FakeEngine(
            ArrayDeque(
                listOf(
                    result(calls = listOf(
                        toolCall("", "datetime", "{}"),
                        toolCall("", "datetime", "{}")
                    )),
                    result("Готово")
                )
            )
        )

        PocketPalStructuredAgentRunner(engine).generate(
            JSONArray().put(JSONObject().put("role", "user").put("content", "Дата и время")),
            maxTokens = 256,
            temperature = 0.2f
        )

        val continuation = engine.requests[1].first
        val assistantCalls = continuation.getJSONObject(1).getJSONArray("tool_calls")
        val firstId = assistantCalls.getJSONObject(0).getString("id")
        val secondId = assistantCalls.getJSONObject(1).getString("id")
        assertFalse(firstId == secondId)
        assertEquals(firstId, continuation.getJSONObject(2).getString("tool_call_id"))
        assertEquals(secondId, continuation.getJSONObject(3).getString("tool_call_id"))
    }

    @Test
    fun codeAgentToolWritesOnlyInsideWorkspaceAndReturnsResultToModel() {
        val root = Files.createTempDirectory("jimmy-code-agent-runner").toFile()
        try {
            val workspace = CodeWorkspace(root)
            val codeTools = CodeAgentToolRegistry(workspace)
            val engine = FakeEngine(
                ArrayDeque(
                    listOf(
                        result(calls = listOf(toolCall(
                            "code_call_1",
                            "code_write_file",
                            """{"path":"src/Hello.kt","content":"fun hello() = 42"}"""
                        ))),
                        result("Файл создан в рабочей области.")
                    )
                )
            )

            val final = PocketPalStructuredAgentRunner(
                engine,
                codeTools = codeTools
            ).generate(
                JSONArray().put(JSONObject().put("role", "user").put("content", "Создай файл Hello.kt")),
                maxTokens = 256,
                temperature = 0.2f
            )

            assertEquals("Файл создан в рабочей области.", final.content)
            assertEquals("fun hello() = 42", workspace.readFile("src/Hello.kt").content)
            val schemas = engine.requests.first().second.toString()
            assertTrue(schemas.contains("code_read_file"))
            assertTrue(schemas.contains("code_write_file"))
            val toolReply = engine.requests[1].first.getJSONObject(2)
            assertEquals("tool", toolReply.getString("role"))
            assertTrue(toolReply.getString("content").contains("saved_in_private_workspace"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun visionModeDoesNotExposeTextToolSchemas() {
        val engine = FakeEngine(ArrayDeque(listOf(result("Описание изображения"))))

        val final = PocketPalStructuredAgentRunner(engine, enableTools = false).generate(
            JSONArray().put(JSONObject().put("role", "user").put("content", "Что на фото?")),
            maxTokens = 256,
            temperature = 0.2f
        )

        assertEquals("Описание изображения", final.content)
        assertEquals(0, engine.requests.single().second.length())
    }

    @Test
    fun hardLimitDisablesFurtherToolExecution() {
        val repeated = result(calls = listOf(toolCall("repeat", "datetime", "{}")))
        val engine = FakeEngine(
            ArrayDeque(listOf(repeated, repeated, repeated, result("Остановлено")))
        )

        val final = PocketPalStructuredAgentRunner(engine, maxToolCalls = 2).generate(
            JSONArray().put(JSONObject().put("role", "user").put("content", "Вызови инструмент")),
            maxTokens = 256,
            temperature = 0.2f
        )

        assertEquals("Остановлено", final.content)
        assertEquals(4, engine.requests.size)
        assertEquals(2, engine.requests.count { it.second.length() > 0 })
        assertTrue(engine.requests[2].second.length() == 0)
        assertTrue(engine.requests[3].second.length() == 0)
        val rejectedToolResult = engine.requests[3].first.getJSONObject(6).getString("content")
        assertTrue(rejectedToolResult.contains("лимит"))
    }
}

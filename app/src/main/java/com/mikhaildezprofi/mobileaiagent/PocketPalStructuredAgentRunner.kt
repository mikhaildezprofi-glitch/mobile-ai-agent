package com.mikhaildezprofi.mobileaiagent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Structured PocketPal tool loop. The model emits parsed tool_calls from the native
 * chat parser; no JSON-in-prose envelope is required on this path.
 */
internal class PocketPalStructuredAgentRunner(
    private val bridge: PocketPalGenerationEngine,
    private val registry: AgentTalentRegistry = AgentTalentRegistry.default(),
    private val maxToolCalls: Int = MAX_TOOL_CALLS
) {
    fun generate(
        messages: JSONArray,
        maxTokens: Int,
        temperature: Float,
        listener: PocketPalSnapshotListener? = null
    ): PocketPalGenerationResult {
        val workingMessages = JSONArray(messages.toString())
        val toolSchemas = registry.openAiToolDefinitions()
        var executedCalls = 0
        var toolsEnabled = true

        repeat(maxToolCalls + 1) {
            listener?.onTurnStarted()
            val result = bridge.generate(
                messages = workingMessages,
                tools = if (toolsEnabled) toolSchemas else JSONArray(),
                maxTokens = maxTokens,
                temperature = temperature,
                listener = listener
            )

            if (result.toolCalls.isEmpty()) {
                return result
            }

            val callIds = result.toolCalls.mapIndexed { index, call ->
                call.id.ifBlank { "jimmy_call_${executedCalls + index + 1}" }
            }
            val assistantCalls = JSONArray()
            result.toolCalls.forEachIndexed { index, call ->
                assistantCalls.put(
                    JSONObject()
                        .put("id", callIds[index])
                        .put("type", "function")
                        .put("function", JSONObject()
                            .put("name", call.name)
                            .put("arguments", call.argumentsJson))
                )
            }
            workingMessages.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", result.content)
                    .put("tool_calls", assistantCalls)
            )

            result.toolCalls.forEachIndexed { index, call ->
                val id = callIds[index]
                val toolResult = if (executedCalls < maxToolCalls) {
                    val arguments = runCatching { JSONObject(call.argumentsJson) }
                        .getOrElse { JSONObject() }
                    registry.execute(AgentToolInvocation(call.name, arguments))
                } else {
                    "Инструмент не выполнен: достигнут лимит $maxToolCalls вызовов на один запрос."
                }
                workingMessages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("name", call.name)
                        .put("content", toolResult)
                )
                executedCalls++
            }

            if (executedCalls >= maxToolCalls) {
                toolsEnabled = false
            }
        }

        // The final turn has no tool definitions. This prevents a model that keeps
        // requesting tools from escaping the hard execution limit.
        listener?.onTurnStarted()
        return bridge.generate(
            messages = workingMessages,
            tools = JSONArray(),
            maxTokens = maxTokens,
            temperature = temperature,
            listener = listener
        )
    }

    companion object {
        const val MAX_TOOL_CALLS = 5
    }
}

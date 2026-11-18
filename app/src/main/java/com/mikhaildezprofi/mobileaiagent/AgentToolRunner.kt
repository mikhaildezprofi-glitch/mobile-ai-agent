package com.mikhaildezprofi.mobileaiagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * Bounded, sequential tool loop adapted to the app's text-streaming InferenceEngine API.
 * The current native bridge does not expose structured function-call deltas, so the model
 * emits a strict JSON envelope which is validated before dispatch.
 */
class AgentToolRunner(
    private val registry: AgentTalentRegistry = AgentTalentRegistry.default(),
    private val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS
) {
    init {
        require(maxToolCalls in 0..MAX_ALLOWED_TOOL_CALLS)
    }

    fun run(
        prompt: String,
        maxTokens: Int,
        generate: (String, Int) -> Flow<String>
    ): Flow<String> = flow {
        require(prompt.isNotBlank()) { "Запрос пуст." }
        val toolResults = mutableListOf<String>()
        var currentPrompt = prompt + "\n\n" + AgentToolProtocol.instructions(registry)
        var calls = 0

        while (true) {
            val response = StringBuilder()
            try {
                generate(currentPrompt, maxTokens.coerceIn(64, 8192)).collect { chunk -> response.append(chunk) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }

            val invocation = AgentToolProtocol.parseInvocation(response.toString())
            if (invocation == null) {
                emit(response.toString())
                return@flow
            }

            if (calls >= maxToolCalls) {
                val finalResponse = StringBuilder()
                val finalPrompt = AgentToolProtocol.continuation(prompt, toolResults) +
                    "\n\nЛимит инструментов исчерпан. Сейчас дай окончательный ответ без JSON и без новых вызовов."
                try {
                    generate(finalPrompt, maxTokens.coerceIn(64, 8192)).collect { chunk -> finalResponse.append(chunk) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                }
                emit(finalResponse.toString().ifBlank {
                    "Не удалось получить итоговый ответ после достижения лимита инструментов."
                })
                return@flow
            }

            val result = registry.execute(invocation)
            calls++
            toolResults += "Вызов $calls: ${invocation.name}\nРезультат: $result"
            currentPrompt = AgentToolProtocol.continuation(prompt, toolResults) +
                "\n\nЕсли данных достаточно, дай окончательный ответ обычным текстом. Если нужен ещё один инструмент, верни JSON по заданному протоколу."
        }
    }

    companion object {
        const val DEFAULT_MAX_TOOL_CALLS = 5
        const val MAX_ALLOWED_TOOL_CALLS = 5
    }
}

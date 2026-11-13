package com.mikhaildezprofi.mobileaiagent

import kotlinx.coroutines.flow.Flow

/**
 * Contract for a specialized task executor.
 * Executors may add role-specific constraints, while the coordinator owns provider selection.
 */
interface AgentExecutor {
    val role: AgentRole

    fun execute(
        taskPrompt: String,
        maxTokens: Int,
        generate: (String, Int) -> Flow<String>
    ): Flow<String>
}

enum class AgentRole(val key: String, val instruction: String) {
    GENERAL("general", "Реши задачу точно и по существу."),
    RESEARCH("research", "Работай как исследователь: отделяй известные факты от предположений. Не выдумывай источники и результаты поиска. Если внешние источники не предоставлены, прямо укажи это."),
    ANALYST("analyst", "Работай как аналитик: разложи проблему на причины, ограничения, варианты и последствия. Покажи логику выводов."),
    DEVELOPER("developer", "Работай как разработчик: предложи конкретную реализацию, учитывай существующую архитектуру, совместимость и обработку ошибок. Не утверждай, что код запущен или протестирован без фактического выполнения."),
    TESTER("tester", "Работай как тестировщик: ищи граничные случаи, регрессии и проверяемые критерии. Не заявляй о прохождении тестов без их запуска."),
    REVIEWER("reviewer", "Работай как независимый ревьюер: ищи дефекты, риски безопасности, несоответствия требованиям и недостающие проверки. Не переписывай задачу без необходимости."),
    WRITER("writer", "Работай как редактор: подготовь ясный, законченный и структурированный текст, соответствующий исходной задаче.")
}

private class PromptAgentExecutor(
    override val role: AgentRole
) : AgentExecutor {
    override fun execute(
        taskPrompt: String,
        maxTokens: Int,
        generate: (String, Int) -> Flow<String>
    ): Flow<String> {
        val specializedPrompt = buildString {
            appendLine("Специализация исполнителя: ${role.key}.")
            appendLine(role.instruction)
            appendLine("Соблюдай границы доступных возможностей. Не имитируй инструменты, которых нет.")
            appendLine()
            append(taskPrompt)
        }
        return generate(specializedPrompt, maxTokens)
    }
}

/** Immutable role registry. Unknown planner roles safely fall back to the general executor. */
class AgentExecutorRegistry private constructor(
    private val executors: Map<AgentRole, AgentExecutor>
) {
    fun resolve(roleName: String): AgentExecutor {
        val normalized = roleName.trim().lowercase()
        val role = AgentRole.entries.firstOrNull { it.key == normalized } ?: AgentRole.GENERAL
        return executors[role] ?: executors.getValue(AgentRole.GENERAL)
    }

    fun supportedRoles(): Set<String> = executors.keys.mapTo(linkedSetOf()) { it.key }

    companion object {
        fun default(): AgentExecutorRegistry = AgentExecutorRegistry(
            AgentRole.entries.associateWith(::PromptAgentExecutor)
        )
    }
}

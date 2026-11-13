package com.mikhaildezprofi.mobileaiagent

import java.util.UUID

/** A validated, ordered task graph prepared by the planner. */
data class AgentTaskPlan(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val originalPrompt: String,
    val steps: List<AgentTaskStep>
) {
    fun validate(maxSteps: Int = MAX_STEPS) {
        require(id.isNotBlank()) { "У плана отсутствует идентификатор." }
        require(title.isNotBlank()) { "У плана отсутствует название." }
        require(originalPrompt.isNotBlank()) { "Исходный запрос пуст." }
        require(steps.isNotEmpty()) { "План не содержит шагов." }
        require(steps.size <= maxSteps) { "План превышает лимит $maxSteps шагов." }

        val seen = mutableSetOf<String>()
        steps.forEach { step ->
            require(step.id.isNotBlank() && seen.add(step.id)) { "Идентификатор шага пуст или повторяется: ${step.id}" }
            require(step.title.isNotBlank() && step.prompt.isNotBlank()) { "Шаг ${step.id} не содержит задания." }
            require(step.id !in step.dependsOn) { "Шаг ${step.id} зависит от самого себя." }
            require(step.dependsOn.all(seen::contains)) {
                "Зависимости шага ${step.id} должны ссылаться только на предыдущие шаги."
            }
        }
    }

    companion object {
        const val MAX_STEPS = 12

        fun single(prompt: String, title: String = "Выполнить запрос") = AgentTaskPlan(
            title = title,
            originalPrompt = prompt,
            steps = listOf(AgentTaskStep(title = title, prompt = prompt))
        )
    }
}

data class AgentTaskStep(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val prompt: String,
    val dependsOn: List<String> = emptyList()
)

sealed interface AgentTaskEvent {
    data class PlanStarted(val plan: AgentTaskPlan) : AgentTaskEvent
    data class StepStarted(val step: AgentTaskStep, val index: Int, val total: Int) : AgentTaskEvent
    data class StepOutput(val stepId: String, val text: String) : AgentTaskEvent
    data class StepCompleted(val step: AgentTaskStep, val result: String) : AgentTaskEvent
    data class PlanCompleted(val planId: String, val results: Map<String, String>) : AgentTaskEvent
}

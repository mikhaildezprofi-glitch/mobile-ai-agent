package com.mikhaildezprofi.mobileaiagent

import org.json.JSONObject
import java.util.UUID

/** Converts a coordinator model's structured response into a strictly validated plan. */
object AgentTaskPlanner {
    const val MAX_PLANNER_TOKENS = 768
    private const val MAX_STEP_PROMPT_CHARS = 4_000

    fun buildPrompt(userRequest: String): String = """
        Ты — главный координатор ИИ-агента. Разбей запрос пользователя на минимальное число
        последовательных задач, если это действительно нужно. Простую задачу оформи одним шагом.
        Верни ТОЛЬКО корректный JSON без Markdown и пояснений в формате:
        {
          "title": "Краткая цель",
          "steps": [
            {"id":"step_1", "title":"Краткое название", "task":"Точное задание исполнителю", "agent":"research", "depends_on":[]}
          ]
        }
        Правила:
        - Не более ${AgentTaskPlan.MAX_STEPS} шагов.
        - Порядок шагов должен быть исполнимым; depends_on содержит только ID предыдущих шагов.
        - agent — краткая роль исполнителя (например research, developer, reviewer, tester, writer).
        - Каждый task должен быть самостоятельным и содержать достаточно контекста.
        - Не создавай шаги для несуществующих инструментов, интернет-поиска или управления Android.
        - Не утверждай, что внешние действия выполнены. Опиши их как требующие отдельного инструмента.
        - Не включай секреты, ключи API или выдуманные результаты.
        - Не разбивай простые вопросы без необходимости.

        Запрос пользователя:
        $userRequest
    """.trimIndent()

    fun parse(raw: String, originalPrompt: String): AgentTaskPlan {
        val jsonText = extractJson(raw)
        val root = JSONObject(jsonText)
        val array = root.optJSONArray("steps")
            ?: throw IllegalArgumentException("Планировщик не вернул массив steps.")
        require(array.length() in 1..AgentTaskPlan.MAX_STEPS) {
            "Планировщик вернул недопустимое число шагов: ${array.length()}"
        }
        val steps = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                    ?: throw IllegalArgumentException("Шаг ${index + 1} имеет неверный формат.")
                val task = item.optString("task").trim()
                require(task.isNotBlank() && task.length <= MAX_STEP_PROMPT_CHARS) {
                    "Задание шага ${index + 1} пустое или слишком длинное."
                }
                val depends = item.optJSONArray("depends_on")?.let { dependencies ->
                    buildList {
                        for (dependencyIndex in 0 until dependencies.length()) {
                            add(dependencies.optString(dependencyIndex))
                        }
                    }
                }.orEmpty()
                val id = item.optString("id").takeIf(String::isNotBlank) ?: "step_${index + 1}_${UUID.randomUUID()}"
                val role = item.optString("agent", "general").trim().ifBlank { "general" }
                require(role.length <= 40 && role.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                    "Недопустимая роль исполнителя в шаге ${index + 1}."
                }
                add(
                    AgentTaskStep(
                        id = id,
                        title = item.optString("title").trim().ifBlank { "Шаг ${index + 1}" },
                        prompt = task,
                        dependsOn = depends,
                        agentRole = role
                    )
                )
            }
        }
        return AgentTaskPlan(
            title = root.optString("title").trim().ifBlank { "Выполнение задачи" },
            originalPrompt = originalPrompt,
            steps = steps
        ).also { it.validate() }
    }

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        val withoutFence = trimmed
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = withoutFence.indexOf('{')
        val end = withoutFence.lastIndexOf('}')
        require(start >= 0 && end > start) { "Планировщик не вернул JSON-объект." }
        return withoutFence.substring(start, end + 1)
    }
}

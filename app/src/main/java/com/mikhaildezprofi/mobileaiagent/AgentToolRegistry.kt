package com.mikhaildezprofi.mobileaiagent

/** Describes only capabilities that are actually implemented and ready at runtime. */
enum class AgentCapabilityId {
    LOCAL_TEXT_GENERATION,
    GEMINI_TEXT_GENERATION,
    USER_SELECTED_TEXT_FILE_READING,
    IMAGE_UNDERSTANDING,
    WEB_SEARCH,
    ANDROID_DEVICE_CONTROL
}

data class AgentCapability(
    val id: AgentCapabilityId,
    val title: String,
    val available: Boolean,
    val detail: String
)

class AgentToolRegistry private constructor(private val entries: List<AgentCapability>) {
    fun all(): List<AgentCapability> = entries.toList()
    fun isAvailable(id: AgentCapabilityId): Boolean = entries.any { it.id == id && it.available }

    companion object {
        fun forRuntime(
            localReady: Boolean,
            cloudReady: Boolean,
            fileReaderReady: Boolean = true,
            visionReady: Boolean = false
        ) = AgentToolRegistry(listOf(
            AgentCapability(
                AgentCapabilityId.LOCAL_TEXT_GENERATION,
                "Локальная генерация текста",
                localReady,
                if (localReady) "Qwen3 через llama.cpp" else "Локальная модель не загружена"
            ),
            AgentCapability(
                AgentCapabilityId.GEMINI_TEXT_GENERATION,
                "Облачная генерация текста",
                cloudReady,
                if (cloudReady) "API-ключ сохранён; доступность Gemini проверяется при запросе. Передаётся только текст."
                else "API-ключ Gemini не настроен"
            ),
            AgentCapability(
                AgentCapabilityId.USER_SELECTED_TEXT_FILE_READING,
                "Чтение выбранных файлов",
                fileReaderReady,
                "TXT, исходный код, DOCX, XLSX, PPTX и PDF с текстовым слоем. Лимит 5 МБ на файл; PDF до 30 страниц. Сканированные PDF требуют OCR."
            ),
            AgentCapability(
                AgentCapabilityId.IMAGE_UNDERSTANDING,
                "Анализ изображений",
                visionReady,
                if (visionReady) "SmolVLM и совместимый mmproj загружены. Поддерживается локальный анализ фотографий и извлечённых кадров видео."
                else "Для анализа требуется загрузить совместимую vision-модель и mmproj."
            ),
            AgentCapability(
                AgentCapabilityId.WEB_SEARCH,
                "Поиск в интернете",
                false,
                "Поисковый инструмент ещё не подключён."
            ),
            AgentCapability(
                AgentCapabilityId.ANDROID_DEVICE_CONTROL,
                "Управление Android",
                false,
                "Исполнитель Android-действий и система подтверждения ещё не подключены."
            )
        ))
    }
}

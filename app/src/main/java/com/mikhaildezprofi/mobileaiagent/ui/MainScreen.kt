package com.mikhaildezprofi.mobileaiagent.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class AgentMode { LOCAL, HYBRID }
private data class ChatMessage(val speaker: String, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(AgentMode.LOCAL) }
    var status by remember { mutableStateOf("Инициализация локального движка llama.cpp…") }
    var diagnostics by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf<InferenceEngine?>(null) }
    var modelReady by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }

    LaunchedEffect(appContext) {
        try {
            val instance = withContext(Dispatchers.IO) {
                AiChat.getInferenceEngine(appContext)
            }
            val initialState = withContext(Dispatchers.IO) {
                instance.state.first {
                    it !is InferenceEngine.State.Uninitialized &&
                        it !is InferenceEngine.State.Initializing
                }
            }
            if (initialState is InferenceEngine.State.Error) {
                throw initialState.exception
            }
            engine = instance
            if (initialState is InferenceEngine.State.ModelReady) {
                modelReady = true
                status = "Локальный движок готов. Модель уже загружена."
            } else {
                status = "llama.cpp готов. Выберите GGUF-модель."
            }
        } catch (error: Exception) {
            status = "Ошибка инициализации: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    fun loadSelectedModel(uri: Uri) {
        scope.launch {
            busy = true
            status = "Копирование GGUF в память приложения. Это может занять несколько минут…"
            try {
                val modelFile = withContext(Dispatchers.IO) {
                    val modelsDir = File(appContext.filesDir, "models")
                    if (!modelsDir.exists() && !modelsDir.mkdirs()) {
                        error("Не удалось создать папку моделей")
                    }
                    val destination = File(modelsDir, "Qwen3-1.7B-Q4_K_M.gguf")
                    val source = appContext.contentResolver.openInputStream(uri)
                        ?: error("Android не смог открыть выбранный файл")
                    source.use { inputStream ->
                        destination.outputStream().buffered().use { outputStream ->
                            inputStream.copyTo(outputStream, 1024 * 1024)
                        }
                    }
                    destination
                }

                status = "Загрузка модели в оперативную память…"
                val activeEngine = engine ?: error("Движок ещё не инициализирован")
                withContext(Dispatchers.IO) {
                    activeEngine.loadModel(modelFile.absolutePath)
                    activeEngine.setSystemPrompt(
                        "Ты — мобильный ИИ-ассистент. Отвечай на языке пользователя, ясно и по существу."
                    )
                }
                modelReady = true
                status = "Модель Qwen3-1.7B загружена. Можно общаться офлайн."
            } catch (error: Exception) {
                modelReady = false
                status = "Ошибка загрузки модели: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) loadSelectedModel(uri)
    }

    fun sendMessage() {
        val prompt = input.trim()
        val activeEngine = engine
        if (prompt.isEmpty() || activeEngine == null || !modelReady || busy) return

        input = ""
        messages.add(ChatMessage("Вы", prompt))
        messages.add(ChatMessage("MOBILE AI", ""))
        busy = true
        status = "Модель генерирует ответ…"

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    activeEngine.sendUserPrompt(prompt, 512).collect { token ->
                        withContext(Dispatchers.Main) {
                            val lastIndex = messages.lastIndex
                            if (lastIndex >= 0) {
                                val previous = messages[lastIndex]
                                messages[lastIndex] = previous.copy(text = previous.text + token)
                            }
                        }
                    }
                }
                status = "Ответ готов. Локальная модель работает."
            } catch (error: Exception) {
                val lastIndex = messages.lastIndex
                if (lastIndex >= 0 && messages[lastIndex].speaker == "MOBILE AI" &&
                    messages[lastIndex].text.isBlank()
                ) {
                    messages[lastIndex] = messages[lastIndex].copy(
                        text = "Ошибка генерации: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
                status = "Ошибка генерации: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("MOBILE AI AGENT") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Ваш персональный AI-ассистент", style = MaterialTheme.typography.titleMedium)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Режим работы", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Локальный")
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = mode == AgentMode.HYBRID,
                            onCheckedChange = {
                                mode = if (it) AgentMode.HYBRID else AgentMode.LOCAL
                            }
                        )
                        Text("Гибридный", modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(
                        if (mode == AgentMode.LOCAL)
                            "Локальный режим. Ответы выбранной модели обрабатываются на телефоне."
                        else
                            "Гибридный режим выбран. Облачные провайдеры будут подключены отдельным этапом."
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Состояние агента", style = MaterialTheme.typography.titleMedium)
                    Text(status)
                    OutlinedButton(
                        onClick = {
                            status = if (engine != null) {
                                "llama.cpp инициализирован."
                            } else {
                                "Движок ещё запускается или завершился с ошибкой."
                            }
                        }
                    ) {
                        Text("Проверить состояние")
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Локальная модель", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (modelReady) "Qwen3-1.7B-Q4_K_M.gguf — загружена"
                        else "Выберите ранее скачанный GGUF-файл из папки Download."
                    )
                    OutlinedButton(
                        enabled = engine != null && !busy && !modelReady,
                        onClick = { modelPicker.launch(arrayOf("*/*")) }
                    ) {
                        Text(if (modelReady) "Модель загружена" else "Выбрать GGUF-модель")
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Локальный чат", style = MaterialTheme.typography.titleMedium)
                    if (messages.isEmpty()) {
                        Text("После загрузки модели отправьте первое сообщение.")
                    } else {
                        messages.takeLast(12).forEach { message ->
                            Text(
                                "${message.speaker}: " +
                                    if (message.text.isBlank()) "…" else message.text
                            )
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = modelReady && !busy,
                        label = { Text("Сообщение") },
                        minLines = 2
                    )
                    Button(
                        onClick = { sendMessage() },
                        enabled = modelReady && !busy && input.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (busy) "Подождите…" else "Отправить")
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Возможности", style = MaterialTheme.typography.titleMedium)
                    Text("• Локальная языковая модель — подключается через llama.cpp")
                    Text("• Облачные модели — опционально, следующий этап")
                    Text("• Голос, камера и анализ файлов — запланировано")
                    Text("• Инструменты Android — только с разрешения пользователя")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Диагностический режим")
                Spacer(Modifier.weight(1f))
                Switch(checked = diagnostics, onCheckedChange = { diagnostics = it })
            }
            if (diagnostics) {
                Text("Версия 0.2.0 • llama.cpp • GGUF • Android arm64-v8a")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

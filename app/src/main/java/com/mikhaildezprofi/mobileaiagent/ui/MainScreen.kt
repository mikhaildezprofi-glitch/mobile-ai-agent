package com.mikhaildezprofi.mobileaiagent.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.VisionInferenceEngine
import com.mikhaildezprofi.mobileaiagent.AgentCoordinator
import com.mikhaildezprofi.mobileaiagent.AgentTaskStore
import com.mikhaildezprofi.mobileaiagent.SavedAgentPlan
import com.mikhaildezprofi.mobileaiagent.AgentTaskEvent
import com.mikhaildezprofi.mobileaiagent.AgentProviderKind
import com.mikhaildezprofi.mobileaiagent.ImageAttachmentSource
import com.mikhaildezprofi.mobileaiagent.ImageAttachmentStager
import com.mikhaildezprofi.mobileaiagent.VideoFrameExtractor
import com.mikhaildezprofi.mobileaiagent.AgentToolRegistry
import com.mikhaildezprofi.mobileaiagent.SafeDocumentReader
import com.mikhaildezprofi.mobileaiagent.GeminiApiKeyStore
import com.mikhaildezprofi.mobileaiagent.InstalledGeminiBridge
import com.mikhaildezprofi.mobileaiagent.AgentRuntimeConfig
import com.mikhaildezprofi.mobileaiagent.RuntimeFiles
import com.mikhaildezprofi.mobileaiagent.GenerationForegroundService
import com.mikhaildezprofi.mobileaiagent.BackgroundGenerationController
import com.mikhaildezprofi.mobileaiagent.DevicePerformanceMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

private fun taskPlanStatusLabel(status: com.mikhaildezprofi.mobileaiagent.AgentPlanStatus): String =
    when (status) {
        com.mikhaildezprofi.mobileaiagent.AgentPlanStatus.QUEUED -> "В очереди"
        com.mikhaildezprofi.mobileaiagent.AgentPlanStatus.RUNNING -> "Выполняется"
        com.mikhaildezprofi.mobileaiagent.AgentPlanStatus.PAUSED -> "Приостановлена"
        com.mikhaildezprofi.mobileaiagent.AgentPlanStatus.COMPLETED -> "Завершена"
        com.mikhaildezprofi.mobileaiagent.AgentPlanStatus.FAILED -> "Ошибка"
    }

private fun taskStepStatusLabel(status: com.mikhaildezprofi.mobileaiagent.AgentStepStatus): String =
    when (status) {
        com.mikhaildezprofi.mobileaiagent.AgentStepStatus.PENDING -> "Ожидает"
        com.mikhaildezprofi.mobileaiagent.AgentStepStatus.RUNNING -> "Выполняется"
        com.mikhaildezprofi.mobileaiagent.AgentStepStatus.COMPLETED -> "Готово"
        com.mikhaildezprofi.mobileaiagent.AgentStepStatus.FAILED -> "Ошибка"
    }

private enum class AgentMode { LOCAL, HYBRID }

private data class SavedChat(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val updatedAt: Long
)

private fun loadSavedChats(context: Context): List<SavedChat> = try {
    val raw = context.getSharedPreferences("agent_chats", Context.MODE_PRIVATE)
        .getString("history", "[]") ?: "[]"
    val array = JSONArray(raw)
    buildList {
        for (i in 0 until array.length()) {
            val chat = array.getJSONObject(i)
            val messageArray = chat.optJSONArray("messages") ?: JSONArray()
            val parsedMessages = buildList {
                for (j in 0 until messageArray.length()) {
                    val message = messageArray.getJSONObject(j)
                    add(ChatMessage(message.optString("speaker"), message.optString("text")))
                }
            }
            add(SavedChat(
                chat.optString("id"), chat.optString("title", "Чат"),
                parsedMessages, chat.optLong("updatedAt")
            ))
        }
    }.sortedByDescending { it.updatedAt }
} catch (_: Exception) { emptyList() }

private fun saveSavedChats(context: Context, chats: List<SavedChat>) {
    val array = JSONArray()
    chats.take(50).forEach { chat ->
        val messageArray = JSONArray()
        chat.messages.forEach { message ->
            messageArray.put(JSONObject().put("speaker", message.speaker).put("text", message.text))
        }
        array.put(JSONObject()
            .put("id", chat.id)
            .put("title", chat.title)
            .put("updatedAt", chat.updatedAt)
            .put("messages", messageArray))
    }
    context.getSharedPreferences("agent_chats", Context.MODE_PRIVATE)
        .edit().putString("history", array.toString()).apply()
}


@Composable
private fun AttachmentMenu(expanded: Boolean, dismiss: () -> Unit, cameraEnabled: Boolean,
    filePicker: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    imagePicker: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    videoPicker: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    cameraLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Void?, Bitmap?>) {
    DropdownMenu(expanded = expanded, onDismissRequest = dismiss) {
        DropdownMenuItem(text = { Text("Файлы") }, leadingIcon = { Icon(Icons.Default.AttachFile, null) }, onClick = { dismiss(); filePicker.launch(arrayOf("*/*")) })
        DropdownMenuItem(text = { Text("Фото из галереи") }, leadingIcon = { Icon(Icons.Default.Image, null) }, onClick = { dismiss(); imagePicker.launch(arrayOf("image/*")) })
        DropdownMenuItem(text = { Text("Видео из галереи") }, leadingIcon = { Icon(Icons.Default.Movie, null) }, onClick = { dismiss(); videoPicker.launch(arrayOf("video/*")) })
        if (cameraEnabled) DropdownMenuItem(text = { Text("Камера") }, leadingIcon = { Icon(Icons.Default.CameraAlt, null) }, onClick = { dismiss(); cameraLauncher.launch(null) })
    }
}

@Composable
private fun ComposerInput(value: String, onValueChange: (String) -> Unit, placeholder: String,
    maxHeight: androidx.compose.ui.unit.Dp, attachmentsEnabled: Boolean, voiceEnabled: Boolean,
    canSend: Boolean, onAdd: () -> Unit, addMenu: @Composable () -> Unit,
    onExpand: () -> Unit, onMic: () -> Unit, onSend: () -> Unit) {
    Surface(Modifier.fillMaxWidth().heightIn(max = maxHeight), shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth()) {
            BasicTextField(value = value, onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp, max = (maxHeight - 58.dp).coerceAtLeast(32.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default), minLines = 1, maxLines = Int.MAX_VALUE,
                decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
            ComposerButtons(attachmentsEnabled, voiceEnabled, canSend, onAdd, addMenu, onExpand, onMic, onSend)
        }
    }
}

@Composable
private fun ComposerButtons(attachmentsEnabled: Boolean, voiceEnabled: Boolean, canSend: Boolean,
    onAdd: () -> Unit, addMenu: @Composable () -> Unit, onExpand: (() -> Unit)?, onMic: () -> Unit, onSend: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (attachmentsEnabled) Box { IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Вложения") }; addMenu() }
        Spacer(Modifier.weight(1f))
        if (onExpand != null) IconButton(onClick = onExpand) { ExpansionArrows(inward = false) }
        if (voiceEnabled) IconButton(onClick = onMic) { Icon(Icons.Default.Mic, "Голосовой ввод") }
        IconButton(onClick = onSend, enabled = canSend) { Icon(Icons.Default.ArrowUpward, "Отправить", tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ExpansionArrows(inward: Boolean) {
    val color = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val cornerA = Offset(size.width * .12f, size.height * .12f)
        val cornerB = Offset(size.width * .88f, size.height * .88f)
        val tipA = if (inward) center else cornerA
        val tipB = if (inward) center else cornerB
        val fromA = if (inward) cornerA else center
        val fromB = if (inward) cornerB else center
        val stroke = 2.dp.toPx(); val d = size.minDimension * .22f
        drawLine(color, fromA, tipA, strokeWidth = stroke); drawLine(color, fromB, tipB, strokeWidth = stroke)
        if (inward) {
            drawLine(color, tipA, Offset(tipA.x-d, tipA.y), strokeWidth=stroke); drawLine(color, tipA, Offset(tipA.x, tipA.y-d), strokeWidth=stroke)
            drawLine(color, tipB, Offset(tipB.x+d, tipB.y), strokeWidth=stroke); drawLine(color, tipB, Offset(tipB.x, tipB.y+d), strokeWidth=stroke)
        } else {
            drawLine(color, tipA, Offset(tipA.x+d, tipA.y), strokeWidth=stroke); drawLine(color, tipA, Offset(tipA.x, tipA.y+d), strokeWidth=stroke)
            drawLine(color, tipB, Offset(tipB.x-d, tipB.y), strokeWidth=stroke); drawLine(color, tipB, Offset(tipB.x, tipB.y-d), strokeWidth=stroke)
        }
    }
}

// Hide model reasoning while preserving the final answer, including during streaming.
private fun visibleChatText(raw: String): String {
    val marker = "<think>"
    val closing = "</think>"
    val start = raw.indexOf(marker, ignoreCase = true)
    if (start >= 0) {
        val end = raw.indexOf(closing, startIndex = start + marker.length, ignoreCase = true)
        return if (end < 0) raw.substring(0, start).trimEnd()
        else (raw.substring(0, start) + visibleChatText(raw.substring(end + closing.length))).trim()
    }
    val clean = raw.replace(closing, "", ignoreCase = true)
    // Avoid briefly exposing a partial tag when tokens arrive in small chunks.
    val maxPartial = minOf(marker.length - 1, clean.length)
    for (length in maxPartial downTo 1) {
        if (marker.startsWith(clean.takeLast(length), ignoreCase = true)) {
            return clean.dropLast(length).trimEnd()
        }
    }
    return clean
}

private data class ChatAttachment(
    val name: String,
    val type: String,
    val uri: Uri? = null,
    val bitmap: Bitmap? = null
)

private const val MAX_CHAT_ATTACHMENTS = 10

private fun addAttachmentsSafely(
    target: MutableList<ChatAttachment>,
    incoming: List<ChatAttachment>,
    onLimitReached: () -> Unit
) {
    var limitReached = false
    incoming.forEach { attachment ->
        val duplicate = target.any { existing ->
            attachment.uri != null && existing.uri == attachment.uri && existing.type == attachment.type
        }
        if (duplicate) return@forEach
        if (target.size >= MAX_CHAT_ATTACHMENTS) {
            limitReached = true
        } else {
            target.add(attachment)
        }
    }
    if (limitReached) onLimitReached()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val geminiKeyStore = remember { GeminiApiKeyStore(appContext) }
    var geminiAppInstalled by remember { mutableStateOf(InstalledGeminiBridge.isInstalled(appContext)) }
    var geminiAppStatus by remember { mutableStateOf("") }
    var geminiApiKeyDraft by remember { mutableStateOf("") }
    var geminiKeyConfigured by remember { mutableStateOf(geminiKeyStore.hasKey()) }
    var geminiKeyStatus by remember {
        mutableStateOf(if (geminiKeyStore.hasKey()) "Ключ сохранён в Android Keystore." else "Ключ не настроен.")
    }

    var mode by remember {
        mutableStateOf(if (geminiKeyStore.hasKey()) AgentMode.HYBRID else AgentMode.LOCAL)
    }
    var status by remember { mutableStateOf("Инициализация llama.cpp…") }
    var diagnostics by remember { mutableStateOf(false) }
    var diagnosticDetails by remember { mutableStateOf("") }
    var performanceDetails by remember { mutableStateOf("Метрики появятся во время генерации.") }
    var engine by remember { mutableStateOf<InferenceEngine?>(null) }
    var modelReady by remember { mutableStateOf(false) }
    var visionReady by remember { mutableStateOf(false) }
    var visionRevision by remember { mutableStateOf(0) }
    var busy by ChatSessionState.busy
    var input by remember { mutableStateOf("") }
    var inputFullscreen by remember { mutableStateOf(false) }
    var showPlusMenu by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showCapabilitiesDialog by remember { mutableStateOf(false) }
    var showTaskHistoryDialog by remember { mutableStateOf(false) }
    var selectedTaskPlan by remember { mutableStateOf<SavedAgentPlan?>(null) }
    var planPendingDeletion by remember { mutableStateOf<SavedAgentPlan?>(null) }
    var agentTaskHistory by remember { mutableStateOf<List<SavedAgentPlan>>(emptyList()) }

    // Runtime files are editable independently from the installed APK.
    var runtimeFiles by remember { mutableStateOf(RuntimeFiles.load(appContext)) }
    var runtimeConfig by remember { mutableStateOf(runtimeFiles.config) }
    var systemPrompt by remember { mutableStateOf(runtimeFiles.systemPrompt) }
    var promptDraft by remember { mutableStateOf(systemPrompt) }
    var configDraft by remember { mutableStateOf(runtimeConfig) }

    val messages = ChatSessionState.messages
    val chatHistory = remember { mutableStateListOf<SavedChat>().apply { addAll(loadSavedChats(appContext)) } }
    var currentChatId by ChatSessionState.currentChatId
    val chatListState = rememberLazyListState()
    var resumableAgentPlans by remember { mutableStateOf<List<SavedAgentPlan>>(emptyList()) }

    // Restore the most recently saved chat after Android has recreated the process.
    // Activity recreation in the same process keeps ChatSessionState and skips this path.
    LaunchedEffect(appContext) {
        if (messages.isEmpty()) {
            val latestSavedChat = chatHistory.firstOrNull()
            if (latestSavedChat != null) {
                messages.addAll(latestSavedChat.messages)
                currentChatId = latestSavedChat.id
            }
        }
    }

    // Discover interrupted task plans after startup or when a generation ends.
    LaunchedEffect(appContext, busy) {
        if (!busy) {
            val history = withContext(Dispatchers.IO) {
                AgentTaskStore.get(appContext).listHistory()
            }
            agentTaskHistory = history
            resumableAgentPlans = history.filter { it.canResume }
        }
    }

    // Reflect actual native projector readiness in the capability list.
    LaunchedEffect(engine, modelReady, visionRevision) {
        val visionEngine = engine as? VisionInferenceEngine
        visionReady = if (modelReady && visionEngine != null) {
            try { withContext(Dispatchers.IO) { visionEngine.isVisionReady() } }
            catch (_: Exception) { false }
        } else false
    }

    // Sample the actual Android app process while native inference is running.
    LaunchedEffect(busy) {
        if (busy) {
            val monitor = DevicePerformanceMonitor(appContext)
            while (busy) {
                try {
                    val snapshot = withContext(Dispatchers.IO) { monitor.sample() }
                    performanceDetails = snapshot.asText()
                } catch (error: Exception) {
                    performanceDetails = "Не удалось получить метрики: ${error.message ?: error.javaClass.simpleName}"
                }
                delay(1000)
            }
        }
    }
    // Persist only when a chat changes or generation starts/finishes. The previous
    // implementation serialized the entire chat history for every generated token.
    LaunchedEffect(messages.size, currentChatId, busy) {
        if (messages.isNotEmpty()) {
            val title = messages.firstOrNull { it.speaker == "Вы" }?.text
                ?.replace("\n", " ")?.take(42)?.ifBlank { "Новый чат" } ?: "Новый чат"
            val saved = SavedChat(currentChatId, title, messages.toList(), System.currentTimeMillis())
            chatHistory.removeAll { it.id == currentChatId }
            chatHistory.add(0, saved)
            while (chatHistory.size > 50) chatHistory.removeAt(chatHistory.lastIndex)
            saveSavedChats(appContext, chatHistory)
        }
    }

    // Persist partial assistant output periodically while native inference is active.
    // This cannot resume llama.cpp after process death, but it preserves already
    // generated text so Android process reclamation does not erase the partial reply.
    LaunchedEffect(busy, currentChatId) {
        while (busy) {
            delay(2000)
            if (busy && messages.isNotEmpty()) {
                val title = messages.firstOrNull { it.speaker == "Вы" }?.text
                    ?.replace("\n", " ")?.take(42)?.ifBlank { "Новый чат" } ?: "Новый чат"
                val saved = SavedChat(currentChatId, title, messages.toList(), System.currentTimeMillis())
                chatHistory.removeAll { it.id == currentChatId }
                chatHistory.add(0, saved)
                while (chatHistory.size > 50) chatHistory.removeAt(chatHistory.lastIndex)
                saveSavedChats(appContext, chatHistory)
            }
        }
    }

    // Keep the newest text visible without forcing a scroll/recomposition on every token.
    LaunchedEffect(messages.size, busy) {
        if (busy) {
            while (busy) {
                if (messages.isNotEmpty()) chatListState.scrollToItem(messages.size)
                delay(250)
            }
        } else if (messages.isNotEmpty()) {
            chatListState.scrollToItem(messages.size)
        }
    }
    val attachments = remember { mutableStateListOf<ChatAttachment>() }

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
                status = "Локальная модель уже загружена."
            } else {
                val preferences = appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
                val savedModelPath = preferences.getString("model_path", null)
                val preferredModel = savedModelPath?.let { File(it) }
                // The preference can be absent after a settings reset while the copied
                // model is still safely stored in the app's private models directory.
                val fallbackModel = File(appContext.filesDir, "models/Qwen3-1.7B-Q4_K_M.gguf")
                val savedModel = listOfNotNull(preferredModel, fallbackModel)
                    .firstOrNull { it.isFile && it.canRead() && it.length() > 0L }
                if (initialState is InferenceEngine.State.Initialized && savedModel != null) {
                    status = "Восстановление локальной модели…"
                    withContext(Dispatchers.IO) {
                        instance.loadModel(savedModel.absolutePath)
                        if (systemPrompt.isNotBlank()) instance.setSystemPrompt(systemPrompt)
                        if (savedModel.name.contains("SmolVLM", ignoreCase = true)) {
                            val savedProjector = preferences.getString("vision_mmproj_path", null)?.let { File(it) }
                            val expectedProjector = File(savedModel.parentFile, "mmproj-SmolVLM-500M-Instruct-Q8_0.gguf")
                            val projector = listOfNotNull(savedProjector, expectedProjector)
                                .firstOrNull { it.isFile && it.canRead() && it.length() > 0L }
                            val vision = instance as? VisionInferenceEngine
                            if (projector != null && vision != null) {
                                vision.loadVisionProjector(projector.absolutePath)
                            }
                        }
                    }
                    preferences.edit().putString("model_path", savedModel.absolutePath).commit()
                    modelReady = instance.state.value is InferenceEngine.State.ModelReady
                    status = if (modelReady) {
                        "Локальная модель восстановлена. Можно общаться офлайн."
                    } else {
                        "Модель найдена, но движок не подтвердил готовность. Проверьте диагностику."
                    }
                } else {
                    status = if (savedModel == null) {
                        "Движок готов. Сохранённая модель не найдена — выберите GGUF."
                    } else {
                        "Движок ещё не готов к восстановлению модели."
                    }
                }
            }
        } catch (error: LinkageError) {
            engine = null
            modelReady = false
            status = "Нативный движок недоступен: ${error.message ?: error.javaClass.simpleName}. Гибридный режим остаётся доступен."
        } catch (error: Exception) {
            engine = null
            modelReady = false
            status = "Ошибка инициализации: ${error.message ?: error.javaClass.simpleName}. Гибридный режим остаётся доступен."
        }
    }

    fun loadSelectedModel(uri: Uri) {
        scope.launch {
            busy = true
            status = "Копирование GGUF-файла…"
            try {
                val selectedName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                }?.substringAfterLast('/')?.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                    ?.takeIf { it.isNotBlank() && it != "." && it != ".." }
                    ?: "selected-model.gguf"
                val isProjector = selectedName.contains("mmproj", ignoreCase = true)
                val modelFile = withContext(Dispatchers.IO) {
                    val modelsDir = File(appContext.filesDir, "models")
                    if (!modelsDir.exists() && !modelsDir.mkdirs()) {
                        error("Не удалось создать папку моделей")
                    }
                    val destination = File(modelsDir, selectedName)
                    val source = appContext.contentResolver.openInputStream(uri)
                        ?: error("Android не смог открыть выбранный файл")
                    val temporary = File(modelsDir, "$selectedName.partial")
                    try {
                        source.use { stream ->
                            temporary.outputStream().buffered().use { output ->
                                stream.copyTo(output, 1024 * 1024)
                            }
                        }
                        if (temporary.length() <= 0L) error("Выбранный файл пуст.")
                        if (destination.exists() && !destination.delete()) error("Не удалось заменить старую копию $selectedName")
                        if (!temporary.renameTo(destination)) error("Не удалось сохранить $selectedName")
                    } catch (error: Exception) {
                        temporary.delete()
                        throw error
                    }
                    destination
                }

                val activeEngine = engine ?: error("Движок ещё не инициализирован")
                val preferences = appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
                if (isProjector) {
                    val vision = activeEngine as? VisionInferenceEngine
                        ?: error("Текущий движок не поддерживает мультимодальную обработку.")
                    status = "Загрузка vision-проектора…"
                    withContext(Dispatchers.IO) {
                        check(activeEngine.state.value is InferenceEngine.State.ModelReady) {
                            "Сначала загрузите совместимую vision-модель, затем выберите mmproj."
                        }
                        vision.loadVisionProjector(modelFile.absolutePath)
                    }
                    preferences.edit().putString("vision_mmproj_path", modelFile.absolutePath).apply()
                    visionRevision++
                    modelReady = true
                    status = "Vision-проектор загружен. Локальный анализ фото включён."
                } else {
                    status = "Загрузка модели в оперативную память…"
                    var projectorLoaded = false
                    withContext(Dispatchers.IO) {
                        if (activeEngine.state.value is InferenceEngine.State.ModelReady ||
                            activeEngine.state.value is InferenceEngine.State.Error
                        ) activeEngine.cleanUp()
                        activeEngine.loadModel(modelFile.absolutePath)
                        if (systemPrompt.isNotBlank()) activeEngine.setSystemPrompt(systemPrompt)
                        if (modelFile.name.contains("SmolVLM", ignoreCase = true)) {
                            val expectedProjector = File(modelFile.parentFile, "mmproj-SmolVLM-500M-Instruct-Q8_0.gguf")
                            val savedProjector = preferences.getString("vision_mmproj_path", null)?.let { File(it) }
                            val projector = listOfNotNull(savedProjector, expectedProjector)
                                .firstOrNull { it.isFile && it.canRead() && it.length() > 0L }
                            val vision = activeEngine as? VisionInferenceEngine
                            if (projector != null && vision != null) {
                                vision.loadVisionProjector(projector.absolutePath)
                                preferences.edit().putString("vision_mmproj_path", projector.absolutePath).apply()
                                projectorLoaded = true
                            }
                        }
                    }
                    preferences.edit().putString("model_path", modelFile.absolutePath).apply()
                    visionRevision++
                    modelReady = activeEngine.state.value is InferenceEngine.State.ModelReady
                    status = if (projectorLoaded) {
                        "SmolVLM загружена вместе с vision-проектором. Анализ фото включён."
                    } else {
                        "Модель $selectedName загружена. Можно работать локально."
                    }
                }
                showModelDialog = false
            } catch (error: Exception) {
                modelReady = engine?.state?.value is InferenceEngine.State.ModelReady
                visionRevision++
                status = "Ошибка загрузки: ${error.message ?: error.javaClass.simpleName}"
                diagnosticDetails = buildString {
                    appendLine("Класс ошибки: ${error.javaClass.name}")
                    appendLine("Сообщение: ${error.message ?: "отсутствует"}")
                    var cause = error.cause
                    var depth = 1
                    while (cause != null && depth <= 10) {
                        appendLine("Причина $depth: ${cause.javaClass.name}: ${cause.message}")
                        cause = cause.cause
                        depth++
                    }
                    appendLine()
                    append(error.stackTraceToString())
                }.take(12000)
            } finally {
                busy = false
            }
        }
    }

    fun retainReadPermission(uri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure {
            // Some document providers do not support persistable URI grants.
        }
    }

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            retainReadPermission(uri)
            loadSelectedModel(uri)
        }
    }

    fun displayName(uri: Uri): String {
        var name = "Вложение"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(index) ?: name
            }
        }
        return name
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach(::retainReadPermission)
        addAttachmentsSafely(attachments, uris.map { ChatAttachment(displayName(it), "Файл", it) }) {
            status = "Можно добавить не более $MAX_CHAT_ATTACHMENTS вложений в одно сообщение."
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach(::retainReadPermission)
        addAttachmentsSafely(attachments, uris.map { ChatAttachment(displayName(it), "Фото", it) }) {
            status = "Можно добавить не более $MAX_CHAT_ATTACHMENTS вложений в одно сообщение."
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach(::retainReadPermission)
        addAttachmentsSafely(attachments, uris.map { ChatAttachment(displayName(it), "Видео", it) }) {
            status = "Можно добавить не более $MAX_CHAT_ATTACHMENTS вложений в одно сообщение."
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            addAttachmentsSafely(attachments, listOf(ChatAttachment("Фото с камеры", "Фото", bitmap = bitmap))) {
                status = "Можно добавить не более $MAX_CHAT_ATTACHMENTS вложений в одно сообщение."
            }
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            input = if (input.isBlank()) spoken else "$input $spoken"
        }
    }

    fun startSpeechRecognition() {
        val launchSpeech = {
            try {
                if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
                    status = "Сервис распознавания речи не найден на устройстве."
                } else {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите…")
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    }
                    speechLauncher.launch(intent)
                }
            } catch (error: Exception) {
                status = "Не удалось запустить микрофон: ${error.message}"
            }
        }

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            launchSpeech()
        } else {
            status = "Нужно разрешить доступ к микрофону."
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSpeechRecognition()
        else status = "Доступ к микрофону не предоставлен."
    }

    fun resumeSavedAgentPlan(saved: SavedAgentPlan) {
        if (busy) return
        val hybridMode = mode == AgentMode.HYBRID && geminiKeyConfigured
        if (!modelReady && !hybridMode) {
            status = "Для продолжения задачи загрузите локальную модель или настройте Gemini."
            return
        }
        messages.add(ChatMessage("Вы", "Продолжить задачу: ${saved.plan.title}"))
        messages.add(ChatMessage("MOBILE AI", ""))
        currentChatId = System.currentTimeMillis().toString()
        busy = true
        status = "Восстановление задачи…"
        val coordinator = AgentCoordinator.forContext(appContext, engine)
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, GenerationForegroundService::class.java)
                    .setAction(GenerationForegroundService.ACTION_START)
                    .putExtra(GenerationForegroundService.EXTRA_LOCAL_INFERENCE, !hybridMode)
            )
        } catch (error: Exception) {
            busy = false
            status = "Не удалось запустить фоновую службу: ${error.message ?: error.javaClass.simpleName}"
            return
        }
        val job = BackgroundGenerationController.scope.launch {
            val pending = StringBuilder()
            suspend fun flush() {
                if (pending.isEmpty()) return
                val chunk = pending.toString()
                pending.setLength(0)
                withContext(Dispatchers.Main) {
                    val index = messages.lastIndex
                    if (index >= 0) messages[index] = messages[index].copy(text = messages[index].text + chunk)
                }
            }
            var title = "Ответ готов"
            var notification = "Продолженная задача завершена. Нажмите, чтобы открыть чат."
            try {
                withContext(Dispatchers.IO) {
                    coordinator.resumePlan(saved.plan.id, 4096, hybridMode).collect { event ->
                        if (event is AgentTaskEvent.StepOutput) pending.append(event.text)
                        if (pending.length >= 256) flush()
                    }
                }
                flush()
                status = "Задача продолжена и завершена."
            } catch (error: CancellationException) {
                withContext(Dispatchers.NonCancellable) { flush() }
                status = "Продолжение задачи остановлено. Прогресс сохранён."
                title = "Задача приостановлена"
                notification = "Прогресс сохранён. Можно продолжить из меню."
                throw error
            } catch (error: Exception) {
                flush()
                val message = "Не удалось продолжить задачу: ${error.message ?: error.javaClass.simpleName}"
                val index = messages.lastIndex
                if (index >= 0) messages[index] = messages[index].copy(text = message)
                status = message
                title = "Ошибка продолжения задачи"
                notification = message
            } finally {
                busy = false
                BackgroundGenerationController.currentJob = null
                appContext.startService(
                    Intent(appContext, GenerationForegroundService::class.java)
                        .setAction(GenerationForegroundService.ACTION_COMPLETE)
                        .putExtra(GenerationForegroundService.EXTRA_TITLE, title)
                        .putExtra(GenerationForegroundService.EXTRA_MESSAGE, notification)
                )
                val history = withContext(NonCancellable + Dispatchers.IO) {
                    AgentTaskStore.get(appContext).listHistory()
                }
                agentTaskHistory = history
                resumableAgentPlans = history.filter { it.canResume }
            }
        }
        BackgroundGenerationController.currentJob = job
    }

    fun sendMessage() {
        val userText = input.trim()
        if ((userText.isEmpty() && attachments.isEmpty()) || busy) return

        val attached = attachments.toList()
        val cloudOnlyTextAllowed = attached.isEmpty() &&
            mode == AgentMode.HYBRID && geminiKeyConfigured
        if (!modelReady && !cloudOnlyTextAllowed) {
            status = if (attached.isNotEmpty()) {
                "Для обработки вложений нужна загруженная локальная модель."
            } else {
                "Загрузите локальную модель или настройте Gemini и включите гибридный режим."
            }
            return
        }
        val prompt = buildString {
            append(userText)
            if (attached.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("Пользователь добавил вложения:")
                attached.forEach { appendLine("- ${it.type}: ${it.name}") }
                append("Поддерживаемые текстовые файлы, исходники, DOCX, XLSX, PPTX и PDF обрабатываются локально; сканированным PDF нужен OCR. Фото анализируются локальной vision-моделью, а из видео извлекается до четырёх кадров. Вложения не отправляются в облако.")
            }
            // Qwen3 supports /no_think to skip the hidden reasoning channel
            // and produce a user-facing answer within the generation token budget.
            if (isNotEmpty()) appendLine()
            append("/no_think")
        }

        val visibleText = buildString {
            append(userText.ifBlank { "Вложения" })
            if (attached.isNotEmpty()) {
                appendLine()
                append(attached.joinToString { "📎 ${it.name}" })
            }
        }

        input = ""
        attachments.clear()
        messages.add(ChatMessage("Вы", visibleText))
        messages.add(ChatMessage("MOBILE AI", ""))
        busy = true
        status = "Подготовка ответа…"

        val activeEngine = engine
        val coordinator = AgentCoordinator.forContext(appContext, activeEngine)
        val route = coordinator.route(
            prompt,
            hybridMode = mode == AgentMode.HYBRID && attached.isEmpty()
        )
        status = route.warning ?: route.reason
        if (route.provider == AgentProviderKind.BLOCKED) {
            val message = route.warning ?: route.reason
            val index = messages.lastIndex
            if (index >= 0 && messages[index].speaker == "MOBILE AI") {
                messages[index] = messages[index].copy(text = message)
            }
            attached.forEach { attachment ->
                attachment.bitmap?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            }
            busy = false
            status = message
            return
        }
        if (route.provider == AgentProviderKind.LOCAL && !modelReady) {
            val message = route.warning
                ?: "Для этой задачи нужна загруженная локальная модель. В гибридном режиме Gemini обрабатывает обычный текст, код и сложный анализ; файлы и изображения требуют локального движка."
            val index = messages.lastIndex
            if (index >= 0 && messages[index].speaker == "MOBILE AI") {
                messages[index] = messages[index].copy(text = message)
            }
            attached.forEach { attachment ->
                attachment.bitmap?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            }
            busy = false
            status = message
            return
        }
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, GenerationForegroundService::class.java)
                    .setAction(GenerationForegroundService.ACTION_START)
                    .putExtra(
                        GenerationForegroundService.EXTRA_LOCAL_INFERENCE,
                        route.provider == AgentProviderKind.LOCAL
                    )
            )
        } catch (error: Exception) {
            busy = false
            status = "Не удалось запустить фоновую службу: ${error.message ?: error.javaClass.simpleName}"
            return
        }
        val generationJob = BackgroundGenerationController.scope.launch {
            var notificationTitle = "Ответ готов"
            var notificationMessage = "MOBILE AI завершил генерацию. Нажмите, чтобы открыть чат."
            val pendingTokens = StringBuilder()
            var lastUiFlush = SystemClock.elapsedRealtime()
            suspend fun flushPendingTokens() {
                if (pendingTokens.isEmpty()) return
                val chunk = pendingTokens.toString()
                pendingTokens.setLength(0)
                withContext(Dispatchers.Main) {
                    val index = messages.lastIndex
                    if (index >= 0) {
                        val previous = messages[index]
                        messages[index] = previous.copy(text = previous.text + chunk)
                    }
                }
            }
            try {
                val textFileUris = attached.filter { it.type == "Файл" }.mapNotNull { it.uri }
                val effectivePrompt = withContext(Dispatchers.IO) {
                    SafeDocumentReader.appendSelectedFiles(prompt, appContext.contentResolver, textFileUris)
                }
                val imageSources = attached.filter { it.type == "Фото" }.mapNotNull { attachment ->
                    when {
                        attachment.uri != null -> ImageAttachmentSource(attachment.name, uri = attachment.uri)
                        attachment.bitmap != null -> ImageAttachmentSource(attachment.name, bitmap = attachment.bitmap)
                        else -> null
                    }
                }
                val videoAttachments = attached.filter { it.type == "Видео" && it.uri != null }
                val hasVisualAttachments = imageSources.isNotEmpty() || videoAttachments.isNotEmpty()
                val visionEngine = activeEngine as? VisionInferenceEngine
                val visionReady = hasVisualAttachments && visionEngine?.isVisionReady() == true

                if (visionReady && visionEngine != null) {
                    status = if (videoAttachments.isNotEmpty()) "Извлечение кадров видео и подготовка изображений…"
                        else "Подготовка изображений для локального анализа…"
                    val extractedVideoFrames = mutableListOf<ImageAttachmentSource>()
                    try {
                        var remainingFrames = (4 - imageSources.size).coerceAtLeast(0)
                        for (video in videoAttachments) {
                            if (remainingFrames <= 0) break
                            val uri = video.uri ?: continue
                            val frames = withContext(Dispatchers.IO) {
                                VideoFrameExtractor.extractFrames(appContext, uri, video.name, remainingFrames)
                            }
                            extractedVideoFrames += frames
                            remainingFrames -= frames.size
                        }
                        val visualSources = imageSources + extractedVideoFrames
                        if (visualSources.isEmpty()) error("Не удалось подготовить изображения или кадры видео.")
                        val videoNote = if (videoAttachments.isNotEmpty()) {
                            "\n\nК видео извлечено ${extractedVideoFrames.size} ключевых кадров. Анализ основан на этих кадрах, а не на каждом мгновении ролика."
                        } else ""
                        val stagedImages = ImageAttachmentStager.stage(appContext, visualSources)
                        try {
                            withContext(Dispatchers.IO) {
                                visionEngine.sendUserPromptWithImages(
                                    message = effectivePrompt + videoNote,
                                    imagePaths = stagedImages.files.map { it.absolutePath },
                                    predictLength = 1024
                                ).collect { token ->
                                    pendingTokens.append(token)
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastUiFlush >= 100L) {
                                        flushPendingTokens()
                                        lastUiFlush = now
                                    }
                                }
                            }
                        } finally {
                            stagedImages.close()
                        }
                    } finally {
                        extractedVideoFrames.forEach { source ->
                            source.bitmap?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
                        }
                    }
                } else {
                    val safePrompt = if (hasVisualAttachments) {
                        effectivePrompt + "\n\nСистемное ограничение: приложены фото или видео, но совместимая vision-модель и mmproj не загружены. Не делай вид, что видел изображения или видео; честно сообщи, что их содержимое сейчас недоступно."
                    } else effectivePrompt
                    withContext(Dispatchers.IO) {
                        coordinator.generateWithPlanning(
                            prompt = safePrompt,
                            maxTokens = 4096,
                            // File contents remain on-device until an explicit cloud-file policy exists.
                            hybridMode = mode == AgentMode.HYBRID && attached.isEmpty()
                        ).collect { token ->
                            pendingTokens.append(token)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUiFlush >= 100L) {
                                flushPendingTokens()
                                lastUiFlush = now
                            }
                        }
                    }
                }
                flushPendingTokens()
                val finalIndex = messages.lastIndex
                if (finalIndex >= 0 && messages[finalIndex].speaker == "MOBILE AI" &&
                    visibleChatText(messages[finalIndex].text).isBlank()
                ) {
                    messages[finalIndex] = messages[finalIndex].copy(
                        text = "Модель завершила генерацию, но не сформировала видимый ответ. Попробуйте повторить запрос. Для Qwen3 включён режим без скрытых рассуждений."
                    )
                    status = "Генерация завершена без видимого ответа."
                    notificationTitle = "Ответ не сформирован"
                    notificationMessage = "Модель завершила генерацию без текста ответа. Нажмите, чтобы открыть чат."
                } else {
                    status = "Ответ готов."
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable) { flushPendingTokens() }
                status = "Генерация остановлена. Частичный ответ сохранён."
                notificationTitle = "Генерация остановлена"
                notificationMessage = "Частичный ответ сохранён. Нажмите, чтобы открыть чат."
                throw error
            } catch (error: Exception) {
                val index = messages.lastIndex
                if (index >= 0 && messages[index].speaker == "MOBILE AI") {
                    messages[index] = messages[index].copy(
                        text = "Ошибка генерации: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
                status = "Ошибка генерации: ${error.message ?: error.javaClass.simpleName}"
                notificationTitle = "Ошибка генерации"
                notificationMessage = error.message ?: error.javaClass.simpleName
            } finally {
                attached.forEach { attachment ->
                    attachment.bitmap?.let { bitmap ->
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
                busy = false
                BackgroundGenerationController.currentJob = null
                appContext.startService(
                    Intent(appContext, GenerationForegroundService::class.java)
                        .setAction(GenerationForegroundService.ACTION_COMPLETE)
                        .putExtra(GenerationForegroundService.EXTRA_TITLE, notificationTitle)
                        .putExtra(GenerationForegroundService.EXTRA_MESSAGE, notificationMessage)
                )
            }
        }
        BackgroundGenerationController.currentJob = generationJob
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(20.dp))
                Text(
                    "MOBILE AI",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationDrawerItem(
                    label = { Text("Новый чат") },
                    selected = false,
                    icon = { Icon(Icons.Default.AddComment, contentDescription = null) },
                    onClick = {
                        if (!busy) {
                            messages.clear()
                            attachments.clear()
                            input = ""
                            currentChatId = System.currentTimeMillis().toString()
                        }
                        scope.launch { drawerState.close() }
                    }
                )
                resumableAgentPlans.forEach { savedPlan ->
                    val completedCount = savedPlan.steps.count {
                        it.status == com.mikhaildezprofi.mobileaiagent.AgentStepStatus.COMPLETED
                    }
                    NavigationDrawerItem(
                        label = {
                            Column {
                                Text("Продолжить: ${savedPlan.plan.title}", maxLines = 1)
                                Text(
                                    "Готово ${completedCount}/${savedPlan.steps.size} шагов",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        },
                        selected = false,
                        icon = { Icon(Icons.Default.Memory, contentDescription = null) },
                        enabled = !busy,
                        onClick = {
                            scope.launch { drawerState.close() }
                            resumeSavedAgentPlan(savedPlan)
                        }
                    )
                }
                NavigationDrawerItem(
                    label = { Text("История задач") },
                    selected = false,
                    icon = { Icon(Icons.Default.Memory, contentDescription = null) },
                    onClick = {
                        showTaskHistoryDialog = true
                        scope.launch { drawerState.close() }
                    }
                )
                NavigationDrawerItem(
                    label = { Text("Модель и режим работы") },
                    selected = false,
                    icon = { Icon(Icons.Default.Memory, contentDescription = null) },
                    onClick = {
                        showModelDialog = true
                        scope.launch { drawerState.close() }
                    }
                )
                NavigationDrawerItem(
                    label = { Text("Возможности ИИ") },
                    selected = false,
                    icon = { Icon(Icons.Default.Memory, contentDescription = null) },
                    onClick = {
                        showCapabilitiesDialog = true
                        scope.launch { drawerState.close() }
                    }
                )
                NavigationDrawerItem(
                    label = { Text("Настройки") },
                    selected = false,
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    onClick = {
                        promptDraft = systemPrompt
                        configDraft = runtimeConfig
                        showSettingsDialog = true
                        scope.launch { drawerState.close() }
                    }
                )
                if (runtimeConfig.diagnosticsEnabled) {
                    NavigationDrawerItem(
                        label = { Text("Диагностика") },
                        selected = false,
                        icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                        onClick = {
                            showDiagnosticsDialog = true
                            scope.launch { drawerState.close() }
                        }
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "Недавние",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                chatHistory.take(25).forEach { savedChat ->
                    NavigationDrawerItem(
                        label = { Text(savedChat.title, maxLines = 1) },
                        selected = savedChat.id == currentChatId,
                        onClick = {
                            if (!busy) {
                                messages.clear()
                                messages.addAll(savedChat.messages)
                                currentChatId = savedChat.id
                                input = ""
                                attachments.clear()
                            }
                            scope.launch { drawerState.close() }
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (modelReady) "Qwen3 загружена" else "Модель не загружена",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("MOBILE AI", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(6.dp))
                            Text("⌄", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(Icons.Default.Menu, contentDescription = "Открыть меню")
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = !busy,
                            onClick = {
                                messages.clear()
                                attachments.clear()
                                input = ""
                                currentChatId = System.currentTimeMillis().toString()
                                status = if (modelReady) "Новый чат" else status
                            }
                        ) {
                            Icon(Icons.Default.AddComment, contentDescription = "Новый чат")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            bottomBar = {
                Surface(shadowElevation = 8.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        if (attachments.isNotEmpty()) {
                            attachments.forEachIndexed { index, attachment ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        when (attachment.type) {
                                            "Фото" -> Icons.Default.Image
                                            "Видео" -> Icons.Default.Movie
                                            else -> Icons.Default.AttachFile
                                        },
                                        contentDescription = null
                                    )
                                    Text(
                                        attachment.name,
                                        modifier = Modifier.weight(1f).padding(8.dp),
                                        maxLines = 1
                                    )
                                    IconButton(onClick = { attachments.removeAt(index) }) {
                                        Icon(Icons.Default.Close, contentDescription = "Удалить")
                                    }
                                }
                                attachment.bitmap?.let { bitmap ->
                                    ComposeImage(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Фото с камеры",
                                        modifier = Modifier.size(88.dp)
                                    )
                                }
                            }
                        }

                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            ComposerInput(
                                value = input,
                                onValueChange = { input = it },
                                placeholder = if (messages.isEmpty()) "Спросить AI" else "Ответить AI",
                                maxHeight = (maxHeight - 8.dp).coerceAtLeast(96.dp),
                                attachmentsEnabled = runtimeConfig.attachmentsEnabled,
                                voiceEnabled = runtimeConfig.voiceEnabled,
                                canSend = !busy && (input.isNotBlank() || attachments.isNotEmpty()) &&
                                    (if (attachments.isNotEmpty()) modelReady
                                    else modelReady || (mode == AgentMode.HYBRID && geminiKeyConfigured)),
                                onAdd = { showPlusMenu = true },
                                addMenu = { AttachmentMenu(showPlusMenu, { showPlusMenu = false }, runtimeConfig.cameraEnabled, filePicker, imagePicker, videoPicker, cameraLauncher) },
                                onExpand = { inputFullscreen = true },
                                onMic = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startSpeechRecognition()
                                    else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                onSend = { sendMessage() }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Чем могу помочь?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (modelReady) "Напишите сообщение или задайте вопрос голосом."
                        else if (mode == AgentMode.HYBRID && geminiKeyConfigured) "Текстовые запросы доступны через Gemini. Для файлов и изображений нужна локальная модель."
                        else "Откройте меню и загрузите GGUF-модель или настройте Gemini в гибридном режиме.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = chatListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages) { message ->
                        val isUser = message.speaker == "Вы"
                        val displayText = if (isUser) message.text else visibleChatText(message.text)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                        ) {
                            val messageContent: @Composable () -> Unit = {
                                Column(Modifier.padding(if (isUser) 12.dp else 2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            message.speaker,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (displayText.isNotBlank()) {
                                            IconButton(
                                                onClick = {
                                                    val clipboard = context.getSystemService(
                                                        Context.CLIPBOARD_SERVICE
                                                    ) as ClipboardManager
                                                    clipboard.setPrimaryClip(
                                                        ClipData.newPlainText("Сообщение", displayText)
                                                    )
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.ContentCopy,
                                                    contentDescription = "Копировать",
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                    SelectionContainer {
                                        Text(
                                            if (displayText.isBlank()) "…" else displayText
                                        )
                                    }
                                }
                            }
                            if (isUser) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(0.88f),
                                    shape = RoundedCornerShape(22.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) { messageContent() }
                            } else {
                                Box(Modifier.fillMaxWidth(0.96f)) { messageContent() }
                            }
                        }
                    }
                    // A footer after the last message gives LazyColumn a real
                    // target at the very end, so long answers can scroll fully.
                    item {
                        Spacer(Modifier.height(1.dp))
                    }
                }
            }
        }
    }

    if (showTaskHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showTaskHistoryDialog = false },
            title = { Text("История задач") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (agentTaskHistory.isEmpty()) {
                        Text("Сохранённых задач пока нет.")
                    } else {
                        agentTaskHistory.forEach { saved ->
                            val done = saved.steps.count { it.status == com.mikhaildezprofi.mobileaiagent.AgentStepStatus.COMPLETED }
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                onClick = {
                                    selectedTaskPlan = saved
                                    showTaskHistoryDialog = false
                                }
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(saved.plan.title, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${taskPlanStatusLabel(saved.status)} · $done/${saved.steps.size} шагов",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTaskHistoryDialog = false }) { Text("Закрыть") }
            }
        )
    }

    selectedTaskPlan?.let { saved ->
        val done = saved.steps.count { it.status == com.mikhaildezprofi.mobileaiagent.AgentStepStatus.COMPLETED }
        AlertDialog(
            onDismissRequest = { selectedTaskPlan = null },
            title = { Text(saved.plan.title) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("${taskPlanStatusLabel(saved.status)} · $done/${saved.steps.size} шагов")
                    Text("Исходная задача", fontWeight = FontWeight.SemiBold)
                    Text(saved.plan.originalPrompt, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    saved.steps.forEachIndexed { index, step ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${index + 1}. ${step.step.title} · ${taskStepStatusLabel(step.status)}", fontWeight = FontWeight.SemiBold)
                            Text("Исполнитель: ${step.step.agentRole}", style = MaterialTheme.typography.labelSmall)
                            if (step.result.isNotBlank()) {
                                Text("Результат", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                SelectionContainer { Text(step.result, style = MaterialTheme.typography.bodySmall) }
                            }
                            step.error?.takeIf { it.isNotBlank() }?.let {
                                Text("Ошибка: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (index != saved.steps.lastIndex) HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { planPendingDeletion = saved; selectedTaskPlan = null }) { Text("Удалить") }
                    if (saved.canResume) {
                        TextButton(onClick = { selectedTaskPlan = null; resumeSavedAgentPlan(saved) }, enabled = !busy) { Text("Продолжить") }
                    }
                    TextButton(onClick = { selectedTaskPlan = null }) { Text("Закрыть") }
                }
            }
        )
    }

    planPendingDeletion?.let { saved ->
        AlertDialog(
            onDismissRequest = { planPendingDeletion = null },
            title = { Text("Удалить задачу?") },
            text = { Text("Будут удалены план «${saved.plan.title}» и все сохранённые результаты его шагов. Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(onClick = {
                    val id = saved.plan.id
                    planPendingDeletion = null
                    scope.launch {
                        withContext(Dispatchers.IO) { AgentTaskStore.get(appContext).deletePlan(id) }
                        val history = withContext(Dispatchers.IO) { AgentTaskStore.get(appContext).listHistory() }
                        agentTaskHistory = history
                        resumableAgentPlans = history.filter { it.canResume }
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { planPendingDeletion = null }) { Text("Отмена") }
            }
        )
    }

    if (showCapabilitiesDialog) {
        val capabilities = AgentToolRegistry.forRuntime(
            localReady = modelReady,
            cloudReady = geminiKeyConfigured,
            fileReaderReady = true,
            visionReady = visionReady
        ).all()
        AlertDialog(
            onDismissRequest = { showCapabilitiesDialog = false },
            title = { Text("Возможности ИИ") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Список показывает только реально подключённые функции. Наличие API-ключа не означает, что облачный запрос уже проверен.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    capabilities.forEach { capability ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (capability.available) "●" else "○", color = if (capability.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(capability.title, fontWeight = FontWeight.SemiBold)
                                Text(if (capability.available) "Доступно" else "Не подключено", style = MaterialTheme.typography.labelSmall, color = if (capability.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(capability.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCapabilitiesDialog = false }) { Text("Закрыть") }
            }
        )
    }

    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("Модель и режим") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(status)
                    Text(if (modelReady) "Локальная модель загружена" else "Модель не загружена")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Локальный режим")
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = mode == AgentMode.HYBRID,
                            onCheckedChange = {
                                mode = if (it) AgentMode.HYBRID else AgentMode.LOCAL
                            }
                        )
                        Text("Гибридный")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = engine != null && !busy,
                    onClick = { modelPicker.launch(arrayOf("*/*")) }
                ) { Text("Выбрать GGUF") }
            },
            dismissButton = {
                TextButton(onClick = { showModelDialog = false }) { Text("Закрыть") }
            }
        )
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Настройки и runtime-файлы") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Режим: ${if (mode == AgentMode.LOCAL) "Локальный" else "Гибридный"}")
                    Text("Параметры интерфейса и промпт сохраняются отдельно от APK.")
                    HorizontalDivider()
                    Text("Облачная модель Gemini", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (geminiKeyConfigured) "API-ключ сохранён и зашифрован на устройстве."
                        else "API-ключ не задан. В гибридном режиме используется локальная модель.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = geminiApiKeyDraft,
                        onValueChange = { geminiApiKeyDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Gemini API key") },
                        placeholder = { Text("Вставьте ключ из Google AI Studio") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = { Text("Ключ не записывается в исходники и не выводится в журнал.") }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = geminiApiKeyDraft.isNotBlank(),
                            onClick = {
                                val keyToSave = geminiApiKeyDraft
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { geminiKeyStore.save(keyToSave) }
                                        geminiApiKeyDraft = ""
                                        geminiKeyConfigured = true
                                        geminiKeyStatus = "Ключ сохранён в Android Keystore."
                                        status = "Gemini API-ключ сохранён."
                                    } catch (error: Exception) {
                                        geminiKeyStatus = "Не удалось сохранить ключ: ${error.message ?: error.javaClass.simpleName}"
                                    }
                                }
                            }
                        ) { Text("Сохранить ключ") }
                        TextButton(
                            enabled = geminiKeyConfigured,
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { geminiKeyStore.clear() }
                                    geminiApiKeyDraft = ""
                                    geminiKeyConfigured = false
                                    geminiKeyStatus = "Ключ удалён с устройства."
                                    status = "Gemini API-ключ удалён."
                                }
                            }
                        ) { Text("Удалить ключ") }
                    }
                    Text(geminiKeyStatus, style = MaterialTheme.typography.bodySmall)
                    Text("Gemini 3.5 Flash-Lite • бесплатный уровень при наличии квоты Google")
                    HorizontalDivider()
                    Text("Установленное приложение Gemini", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (geminiAppInstalled) "Gemini найден на устройстве."
                        else "Приложение Gemini не обнаружено.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            enabled = geminiAppInstalled,
                            onClick = {
                                geminiAppStatus = if (InstalledGeminiBridge.open(context)) {
                                    "Gemini запущен. Ответ будет находиться в приложении Gemini."
                                } else {
                                    "Не удалось открыть Gemini. Проверьте установку приложения."
                                }
                            }
                        ) { Text("Открыть Gemini") }
                        TextButton(
                            enabled = geminiAppInstalled && input.isNotBlank(),
                            onClick = {
                                geminiAppStatus = if (InstalledGeminiBridge.sharePrompt(context, input)) {
                                    "Открылось системное меню передачи. Выберите Gemini вручную."
                                } else {
                                    "Не удалось открыть меню передачи запроса."
                                }
                            }
                        ) { Text("Передать текущий запрос") }
                    }
                    TextButton(
                        onClick = {
                            geminiAppInstalled = InstalledGeminiBridge.isInstalled(appContext)
                            geminiAppStatus = if (geminiAppInstalled) {
                                "Проверка завершена: Gemini установлен."
                            } else {
                                "Проверка завершена: Gemini не найден."
                            }
                        }
                    ) { Text("Проверить установку") }
                    if (geminiAppStatus.isNotBlank()) {
                        Text(geminiAppStatus, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    Text("Системный промпт")
                    OutlinedTextField(
                        value = promptDraft,
                        onValueChange = { promptDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5
                    )
                    Text("Встроенные модули")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = configDraft.attachmentsEnabled,
                            onCheckedChange = { configDraft = configDraft.copy(attachmentsEnabled = it) }
                        )
                        Text("Вложения")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = configDraft.voiceEnabled,
                            onCheckedChange = { configDraft = configDraft.copy(voiceEnabled = it) }
                        )
                        Text("Голосовой ввод")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = configDraft.cameraEnabled,
                            onCheckedChange = { configDraft = configDraft.copy(cameraEnabled = it) }
                        )
                        Text("Камера")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = configDraft.diagnosticsEnabled,
                            onCheckedChange = { configDraft = configDraft.copy(diagnosticsEnabled = it) }
                        )
                        Text("Диагностика")
                    }
                    TextButton(
                        onClick = {
                            val loaded = RuntimeFiles.load(appContext)
                            runtimeFiles = loaded
                            runtimeConfig = loaded.config
                            configDraft = loaded.config
                            systemPrompt = loaded.systemPrompt
                            promptDraft = loaded.systemPrompt
                            engine?.let { active ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        if (modelReady) active.setSystemPrompt(loaded.systemPrompt)
                                    }
                                }
                            }
                            status = "Runtime-файлы перечитаны."
                        }
                    ) {
                        Text("Перечитать файлы")
                    }
                    Text(
                        "Файлы: files/runtime/ui_config.json, modules.json и system_prompt.txt",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val savedConfig = configDraft.copy(
                            inputMaxLines = configDraft.inputMaxLines.coerceIn(1, 3)
                        )
                        RuntimeFiles.save(appContext, savedConfig, promptDraft)
                        runtimeFiles = RuntimeFiles.load(appContext)
                        runtimeConfig = runtimeFiles.config
                        systemPrompt = runtimeFiles.systemPrompt
                        engine?.let { active ->
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        if (modelReady) active.setSystemPrompt(systemPrompt)
                                    }
                                    status = "Настройки и промпт сохранены."
                                } catch (error: Exception) {
                                    status = "Настройки сохранены; промпт применится после перезапуска модели: ${error.message}"
                                }
                            }
                        }
                        showSettingsDialog = false
                    }
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (showDiagnosticsDialog) {
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text("Диагностика") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Версия 0.2.0 • llama.cpp • GGUF • Android arm64-v8a")
                    Text(status)
                    Text("Производительность устройства", fontWeight = FontWeight.SemiBold)
                    SelectionContainer {
                        Text(performanceDetails, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Подробная диагностика")
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = diagnostics,
                            onCheckedChange = { diagnostics = it }
                        )
                    }
                    if (diagnostics) {
                        SelectionContainer {
                            Text(
                                diagnosticDetails.ifBlank { "Подробностей ошибок пока нет." },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(
                        Context.CLIPBOARD_SERVICE
                    ) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("Диагностика", "$status\n$performanceDetails\n$diagnosticDetails")
                    )
                }) { Text("Копировать") }
            },
            dismissButton = {
                TextButton(onClick = { showDiagnosticsDialog = false }) { Text("Закрыть") }
            }
        )
    }

    if (inputFullscreen) {
        Dialog(
            onDismissRequest = { inputFullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(0.dp)
            ) {
                // Keep the composer controls anchored to the visible bottom edge,
                // including when the keyboard is open.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .navigationBarsPadding()
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 20.dp, end = 20.dp, top = 64.dp, bottom = 76.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        minLines = 1,
                        maxLines = Int.MAX_VALUE,
                        decorationBox = { inner ->
                            Box {
                                if (input.isEmpty()) {
                                    Text(
                                        if (messages.isEmpty()) "Спросить AI" else "Ответить AI",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                inner()
                            }
                        }
                    )
                    IconButton(
                        onClick = { inputFullscreen = false },
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    ) {
                        ExpansionArrows(inward = true)
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        ComposerButtons(
                            attachmentsEnabled = runtimeConfig.attachmentsEnabled,
                            voiceEnabled = runtimeConfig.voiceEnabled,
                            canSend = modelReady && !busy && (input.isNotBlank() || attachments.isNotEmpty()),
                            onAdd = { showPlusMenu = true },
                            addMenu = {
                                AttachmentMenu(
                                    showPlusMenu,
                                    { showPlusMenu = false },
                                    runtimeConfig.cameraEnabled,
                                    filePicker,
                                    imagePicker,
                                    videoPicker,
                                    cameraLauncher
                                )
                            },
                            onExpand = null,
                            onMic = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startSpeechRecognition()
                                else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onSend = { sendMessage() }
                        )
                    }
                }
            }
        }
    }
}

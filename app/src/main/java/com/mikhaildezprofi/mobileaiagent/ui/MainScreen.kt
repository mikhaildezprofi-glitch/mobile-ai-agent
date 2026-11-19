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
import androidx.compose.material.icons.filled.History
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
import com.mikhaildezprofi.mobileaiagent.AgentCoordinator
import com.mikhaildezprofi.mobileaiagent.AgentTaskStore
import com.mikhaildezprofi.mobileaiagent.AgentTaskKind
import com.mikhaildezprofi.mobileaiagent.AppBackupManager
import com.mikhaildezprofi.mobileaiagent.VisionModelAssets
import com.mikhaildezprofi.mobileaiagent.AgentProviderKind
import com.mikhaildezprofi.mobileaiagent.ImageAttachmentSource
import com.mikhaildezprofi.mobileaiagent.ImageAttachmentStager
import com.mikhaildezprofi.mobileaiagent.VideoFrameExtractor
import com.mikhaildezprofi.mobileaiagent.AgentToolRegistry
import com.mikhaildezprofi.mobileaiagent.CodeProjectArchive
import com.mikhaildezprofi.mobileaiagent.CodeWorkspace
import com.mikhaildezprofi.mobileaiagent.SafeDocumentReader
import com.mikhaildezprofi.mobileaiagent.GeminiApiKeyStore
import com.mikhaildezprofi.mobileaiagent.GitHubTokenStore
import com.mikhaildezprofi.mobileaiagent.GitHubApiClient
import com.mikhaildezprofi.mobileaiagent.GitHubWorkingBranch
import com.mikhaildezprofi.mobileaiagent.GitHubWorkspaceFile
import com.mikhaildezprofi.mobileaiagent.GeminiCloudProvider
import com.mikhaildezprofi.mobileaiagent.InstalledGeminiBridge
import com.mikhaildezprofi.mobileaiagent.AgentRuntimeConfig
import com.mikhaildezprofi.mobileaiagent.RuntimeFiles
import com.mikhaildezprofi.mobileaiagent.RuntimeConfigUpdater
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
    maxCount: Int = MAX_CHAT_ATTACHMENTS,
    onLimitReached: () -> Unit
) {
    var limitReached = false
    incoming.forEach { attachment ->
        val duplicate = target.any { existing ->
            attachment.uri != null && existing.uri == attachment.uri && existing.type == attachment.type
        }
        if (duplicate) return@forEach
        if (target.size >= maxCount.coerceIn(1, MAX_CHAT_ATTACHMENTS)) {
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
    val appVersion = remember(appContext) {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "неизвестна"
    }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val geminiKeyStore = remember { GeminiApiKeyStore(appContext) }
    val githubTokenStore = remember { GitHubTokenStore(appContext) }
    var githubTokenDraft by remember { mutableStateOf("") }
    var githubTokenConfigured by remember { mutableStateOf(githubTokenStore.hasToken()) }
    var githubTokenStatus by remember { mutableStateOf(if (githubTokenStore.hasToken()) "GitHub-токен сохранён в Android Keystore." else "GitHub не подключён.") }
    var githubConnectionBusy by remember { mutableStateOf(false) }
    var githubConnectionStatus by remember { mutableStateOf("") }
    var geminiAppInstalled by remember { mutableStateOf(InstalledGeminiBridge.isInstalled(appContext)) }
    var geminiAppStatus by remember { mutableStateOf("") }
    var geminiApiKeyDraft by remember { mutableStateOf("") }
    var geminiKeyConfigured by remember { mutableStateOf(geminiKeyStore.hasKey()) }
    var geminiTestBusy by remember { mutableStateOf(false) }
    var geminiTestStatus by remember { mutableStateOf("") }
    var geminiKeyStatus by remember {
        mutableStateOf(if (geminiKeyStore.hasKey()) "Ключ сохранён в Android Keystore." else "Ключ не настроен.")
    }

    var mode by remember {
        mutableStateOf(
            if (RuntimeFiles.initialHybridMode(appContext, geminiKeyStore.hasKey())) AgentMode.HYBRID
            else AgentMode.LOCAL
        )
    }
    var status by remember { mutableStateOf("Инициализация llama.cpp…") }
    var diagnostics by remember { mutableStateOf(false) }
    var diagnosticDetails by remember { mutableStateOf("") }
    var performanceDetails by remember { mutableStateOf("Метрики появятся во время генерации.") }
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
    var showCodeProjectDialog by remember { mutableStateOf(false) }
    var codeProjectName by remember {
        mutableStateOf(appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
            .getString("active_code_project", "project") ?: "project")
    }
    var codeProjectStatus by remember { mutableStateOf("Импортируйте ZIP-архив проекта в приватную рабочую область Джимми.") }
    var githubProjectBusy by remember { mutableStateOf(false) }
    var githubWorkingBranch by remember(appContext) {
        val prefs = appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
        val name = prefs.getString("github_working_branch", null)
        val base = prefs.getString("github_working_base", null)
        val sha = prefs.getString("github_working_sha", null)
        mutableStateOf(
            if (name != null && base != null && sha != null &&
                GitHubApiClient.isAgentBranch(name) && GitHubApiClient.isSafeBranchName(base) &&
                sha.matches(Regex("[a-fA-F0-9]{40}"))) {
                GitHubWorkingBranch(name, base, sha)
            } else null
        )
    }
    var githubPendingFiles by remember { mutableStateOf<List<GitHubWorkspaceFile>>(emptyList()) }
    var githubPendingDeletePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var githubDeleteConfirmed by remember { mutableStateOf(false) }
    var githubPreviewText by remember { mutableStateOf("") }
    var githubProjectStatus by remember { mutableStateOf("") }
    var githubLastRunId by remember(appContext) { mutableStateOf(appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE).getLong("github_last_run_id", 0L).takeIf { it > 0L }) }
    var selectedTaskPlan by remember { mutableStateOf<com.mikhaildezprofi.mobileaiagent.SavedAgentPlan?>(null) }
    var planPendingDeletion by remember { mutableStateOf<com.mikhaildezprofi.mobileaiagent.SavedAgentPlan?>(null) }
    var agentTaskHistory by remember { mutableStateOf<List<com.mikhaildezprofi.mobileaiagent.SavedAgentPlan>>(emptyList()) }
    var visionDownloadBusy by remember { mutableStateOf(false) }
    var visionDownloadProgress by remember { mutableFloatStateOf(0f) }
    val visionAssetsInitiallyInstalled = remember(appContext) {
        VisionModelAssets.areAssetsPresent(appContext)
    }
    var visionDownloadStatus by remember {
        mutableStateOf(
            if (visionAssetsInitiallyInstalled) {
                "Файлы SmolVLM найдены. SHA-256 будет проверен перед загрузкой."
            } else {
                "Vision-модель ещё не установлена."
            }
        )
    }
    val qwenModelFile = remember(appContext) {
        File(appContext.filesDir, "models/Qwen3-1.7B-Q4_K_M.gguf")
    }

    // Runtime files are editable independently from the installed APK.
    var runtimeFiles by remember { mutableStateOf(RuntimeFiles.load(appContext)) }
    var runtimeConfig by remember { mutableStateOf(runtimeFiles.config) }
    var systemPrompt by remember { mutableStateOf(runtimeFiles.systemPrompt) }
    var promptDraft by remember { mutableStateOf(systemPrompt) }
    var configDraft by remember { mutableStateOf(runtimeConfig) }
    var runtimeUpdateInProgress by remember { mutableStateOf(false) }
    var selectedPromptProfileId by remember { mutableStateOf(runtimeConfig.selectedPromptProfileId) }
    var selectedResponseProfileId by remember { mutableStateOf(runtimeConfig.selectedResponseProfileId) }
    var promptProfileMenuExpanded by remember { mutableStateOf(false) }
    var responseProfileMenuExpanded by remember { mutableStateOf(false) }

    val messages = ChatSessionState.messages
    val chatHistory = remember { mutableStateListOf<SavedChat>().apply { addAll(loadSavedChats(appContext)) } }
    var currentChatId by ChatSessionState.currentChatId
    val chatListState = rememberLazyListState()

    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AppBackupManager.export(appContext, uri) }
            }.onSuccess {
                status = "Резервная копия сохранена. Gemini API-ключ и файл модели в неё не входят."
            }.onFailure { error ->
                status = "Не удалось создать резервную копию: ${error.message ?: "ошибка"}"
            }
        }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AppBackupManager.restore(appContext, uri) }
            }.onSuccess {
                chatHistory.clear()
                chatHistory.addAll(loadSavedChats(appContext))
                messages.clear()
                val latest = chatHistory.firstOrNull()
                if (latest != null) {
                    messages.addAll(latest.messages.takeLast(runtimeConfig.maxHistoryMessages))
                    currentChatId = latest.id
                } else {
                    currentChatId = System.currentTimeMillis().toString()
                }
                runtimeFiles = RuntimeFiles.load(appContext)
                runtimeConfig = runtimeFiles.config
                systemPrompt = runtimeFiles.systemPrompt
                promptDraft = systemPrompt
                configDraft = runtimeConfig
                selectedPromptProfileId = runtimeConfig.selectedPromptProfileId
                selectedResponseProfileId = runtimeConfig.selectedResponseProfileId
                mode = if (RuntimeFiles.initialHybridMode(appContext, geminiKeyStore.hasKey())) {
                    AgentMode.HYBRID
                } else AgentMode.LOCAL
                status = "Данные восстановлены. Gemini API-ключ нужно настроить заново; модель Qwen можно выбрать из Download."
            }.onFailure { error ->
                status = "Не удалось восстановить резервную копию: ${error.message ?: "ошибка"}"
            }
        }
    }

    val codeProjectImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val inputStream = appContext.contentResolver.openInputStream(uri)
                        ?: error("Не удалось открыть выбранный ZIP-архив.")
                    inputStream.use {
                        CodeProjectArchive.importZip(
                            CodeWorkspace(File(appContext.filesDir, "code-workspace")),
                            codeProjectName.trim(),
                            it
                        )
                    }
                }
            }.onSuccess { imported ->
                appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
                    .edit().putString("active_code_project", imported.projectName).apply()
                codeProjectName = imported.projectName
                codeProjectStatus = "Импортирован проект «${imported.projectName}»: ${imported.filesImported} файлов, ${imported.bytesImported / 1024} КБ."
                status = codeProjectStatus
            }.onFailure { error ->
                codeProjectStatus = "Ошибка импорта: ${error.message ?: error.javaClass.simpleName}"
                status = codeProjectStatus
            }
        }
    }
    val codeProjectExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val outputStream = appContext.contentResolver.openOutputStream(uri, "w")
                        ?: error("Не удалось создать ZIP-файл.")
                    CodeProjectArchive.exportZip(
                        CodeWorkspace(File(appContext.filesDir, "code-workspace")),
                        codeProjectName.trim(),
                        outputStream
                    )
                }
            }.onSuccess { count ->
                codeProjectStatus = "Экспортирован проект «${codeProjectName.trim()}»: $count файлов."
                status = codeProjectStatus
            }.onFailure { error ->
                codeProjectStatus = "Ошибка экспорта: ${error.message ?: error.javaClass.simpleName}"
                status = codeProjectStatus
            }
        }
    }

    // Apply runtime history limits immediately, including after a remote config refresh.
    LaunchedEffect(runtimeConfig.maxHistoryMessages) {
        while (messages.size > runtimeConfig.maxHistoryMessages) messages.removeAt(0)
    }

    LaunchedEffect(appContext) {
        if (messages.isEmpty()) {
            val latestSavedChat = chatHistory.firstOrNull()
            if (latestSavedChat != null) {
                messages.addAll(latestSavedChat.messages.takeLast(runtimeConfig.maxHistoryMessages))
                currentChatId = latestSavedChat.id
            }
        }
    }

    // Reflect installed PocketPal MTMD assets, not the retired legacy engine state.
    LaunchedEffect(appContext, modelReady, visionRevision) {
        visionReady = withContext(Dispatchers.IO) {
            val preferences = appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
            val selectedVision = preferences.getString("vision_model_path", null)?.let { File(it) }
            val selectedProjector = preferences.getString("vision_mmproj_path", null)?.let { File(it) }
            (selectedVision?.isFile == true && selectedVision.canRead() &&
                selectedProjector?.isFile == true && selectedProjector.canRead()) ||
                VisionModelAssets.areAssetsPresent(appContext)
        }
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
    LaunchedEffect(messages.size, busy, runtimeConfig.autoScrollDuringGeneration) {
        if (runtimeConfig.autoScrollDuringGeneration) {
            if (busy) {
                while (busy) {
                    if (messages.isNotEmpty()) chatListState.scrollToItem(messages.size)
                    delay(250)
                }
            } else if (messages.isNotEmpty()) {
                chatListState.scrollToItem(messages.size)
            }
        }
    }
    val attachments = remember { mutableStateListOf<ChatAttachment>() }
    // One coordinator belongs to the chat session and is rebuilt only when provider
    // settings or the loaded local engine change.
    val coordinator = remember(
        runtimeConfig.geminiApiEnabled,
        runtimeConfig.geminiModelId,
        runtimeConfig.networkConnectTimeoutSeconds,
        runtimeConfig.networkReadTimeoutSeconds
    ) {
        AgentCoordinator.forContext(
            appContext,
            allowCloud = runtimeConfig.geminiApiEnabled,
            cloudModelId = runtimeConfig.geminiModelId,
            connectTimeoutSeconds = runtimeConfig.networkConnectTimeoutSeconds,
            readTimeoutSeconds = runtimeConfig.networkReadTimeoutSeconds
        )
    }

    // Restore the most recently saved chat after Android has recreated the process.
    // Activity recreation in the same process keeps ChatSessionState and skips this path.
    LaunchedEffect(coordinator, busy) {
        if (!busy) {
            agentTaskHistory = withContext(Dispatchers.IO) {
                AgentTaskStore.get(appContext).listHistory()
            }
        }
    }

    LaunchedEffect(appContext) {
        val preferences = appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
        val candidates = listOfNotNull(
            preferences.getString("model_path", null)?.let { File(it) },
            preferences.getString("text_model_path", null)?.let { File(it) },
            preferences.getString("vision_model_path", null)?.let { File(it) },
            File(appContext.filesDir, "models/Qwen3-1.7B-Q4_K_M.gguf")
        )
        val savedModel = candidates.firstOrNull {
            it.isFile && it.canRead() && it.length() > 0L
        }
        val isSavedVisionModel = savedModel?.name?.contains("SmolVLM", ignoreCase = true) == true
        val selectedVision = preferences.getString("vision_model_path", null)?.let { File(it) }
        val selectedProjector = preferences.getString("vision_mmproj_path", null)?.let { File(it) }
        modelReady = savedModel != null
        visionReady = (selectedVision?.isFile == true && selectedVision.canRead() &&
            selectedProjector?.isFile == true && selectedProjector.canRead()) ||
            VisionModelAssets.areAssetsPresent(appContext)
        status = when {
            isSavedVisionModel && modelReady ->
                "SmolVLM выбрана. PocketPal загрузит её вместе с проектором при анализе изображений."
            modelReady ->
                "PocketPal готов. Локальная модель загрузится при первом запросе."
            else ->
                "PocketPal готов. Выберите GGUF-модель для локальной работы."
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

                val preferences = appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
                if (isProjector) {
                    val selectedVision = preferences.getString("vision_model_path", null)?.let { File(it) }
                        ?: preferences.getString("model_path", null)?.let { File(it) }
                    check(selectedVision?.isFile == true &&
                        selectedVision.name.contains("SmolVLM", ignoreCase = true)) {
                        "Сначала выберите совместимую SmolVLM-модель, затем её mmproj."
                    }
                    preferences.edit().putString("vision_mmproj_path", modelFile.absolutePath).apply()
                    visionRevision++
                    modelReady = selectedVision.isFile && selectedVision.canRead()
                    visionReady = modelReady
                    status = "Vision-проектор сохранён. PocketPal загрузит его вместе со SmolVLM при анализе фото."
                } else {
                    val isVisionModel = modelFile.name.contains("SmolVLM", ignoreCase = true)
                    val modelEditor = preferences.edit().putString("model_path", modelFile.absolutePath)
                    var projectorLoaded = false
                    if (isVisionModel) {
                        modelEditor.putString("vision_model_path", modelFile.absolutePath)
                        val expectedProjector = File(modelFile.parentFile, VisionModelAssets.PROJECTOR_FILE)
                        val savedProjector = preferences.getString("vision_mmproj_path", null)?.let { File(it) }
                        val projector = listOfNotNull(savedProjector, expectedProjector)
                            .firstOrNull { it.isFile && it.canRead() && it.length() > 0L }
                        if (projector != null) {
                            modelEditor.putString("vision_mmproj_path", projector.absolutePath)
                            projectorLoaded = true
                        }
                    } else {
                        modelEditor.putString("text_model_path", modelFile.absolutePath)
                    }
                    modelEditor.apply()
                    visionRevision++
                    modelReady = modelFile.isFile && modelFile.canRead() && modelFile.length() > 0L
                    visionReady = isVisionModel && projectorLoaded
                    status = if (isVisionModel && projectorLoaded) {
                        "SmolVLM и mmproj выбраны. PocketPal загрузит их при анализе изображения."
                    } else if (isVisionModel) {
                        "SmolVLM выбрана. Выберите совместимый mmproj для анализа изображений."
                    } else {
                        "Модель ${selectedName} выбрана для PocketPal. Она загрузится при первом запросе."
                    }
                }
                showModelDialog = false
            } catch (error: Exception) {
                modelReady = appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
                    .getString("model_path", null)?.let { File(it).isFile } == true
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

    suspend fun activateModelFileNow(modelFile: File, projectorFile: File?) {
        check(modelFile.isFile && modelFile.canRead() && modelFile.length() > 0L) {
            "Файл модели не найден или недоступен."
        }
        val preferences = appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
        val isVisionModel = modelFile.name.contains("SmolVLM", ignoreCase = true) || projectorFile != null
        val editor = preferences.edit().putString("model_path", modelFile.absolutePath)
        if (isVisionModel) {
            editor.putString("vision_model_path", modelFile.absolutePath)
            if (projectorFile != null) {
                check(projectorFile.isFile && projectorFile.canRead() && projectorFile.length() > 0L) {
                    "Vision-проектор не найден."
                }
                editor.putString("vision_mmproj_path", projectorFile.absolutePath)
            }
        } else {
            editor.putString("text_model_path", modelFile.absolutePath)
        }
        check(editor.commit()) { "Не удалось сохранить выбранную модель." }
        modelReady = true
        visionReady = isVisionModel && projectorFile?.isFile == true
        visionRevision++
        status = when {
            isVisionModel && visionReady ->
                "SmolVLM и mmproj выбраны. PocketPal загрузит их при анализе изображения."
            isVisionModel ->
                "SmolVLM выбрана. Для анализа требуется совместимый mmproj."
            else ->
                "Модель ${modelFile.name} выбрана для PocketPal. Она загрузится при первом запросе."
        }
    }

    fun activateModelFile(modelFile: File, projectorFile: File?) {
        if (busy || visionDownloadBusy) return
        scope.launch {
            busy = true
            try {
                activateModelFileNow(modelFile, projectorFile)
            } catch (error: Exception) {
                modelReady = appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
                    .getString("model_path", null)?.let { File(it).isFile } == true
                visionRevision++
                status = "Не удалось переключить модель: ${error.message ?: error.javaClass.simpleName}"
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
        addAttachmentsSafely(attachments, uris.map { ChatAttachment(displayName(it), "Файл", it) }, runtimeConfig.maxAttachmentCount) {
            status = "Можно добавить не более ${runtimeConfig.maxAttachmentCount} вложений в одно сообщение."
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach(::retainReadPermission)
        addAttachmentsSafely(attachments, uris.map { ChatAttachment(displayName(it), "Фото", it) }, runtimeConfig.maxAttachmentCount) {
            status = "Можно добавить не более ${runtimeConfig.maxAttachmentCount} вложений в одно сообщение."
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach(::retainReadPermission)
        addAttachmentsSafely(attachments, uris.map { ChatAttachment(displayName(it), "Видео", it) }, runtimeConfig.maxAttachmentCount) {
            status = "Можно добавить не более ${runtimeConfig.maxAttachmentCount} вложений в одно сообщение."
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            addAttachmentsSafely(attachments, listOf(ChatAttachment("Фото с камеры", "Фото", bitmap = bitmap)), runtimeConfig.maxAttachmentCount) {
                status = "Можно добавить не более ${runtimeConfig.maxAttachmentCount} вложений в одно сообщение."
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
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, runtimeConfig.voiceLanguage)
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

    fun refreshAgentTaskHistory() {
        scope.launch {
            agentTaskHistory = withContext(Dispatchers.IO) {
                AgentTaskStore.get(appContext).listHistory()
            }
        }
    }

    fun resumeSavedAgentPlan(saved: com.mikhaildezprofi.mobileaiagent.SavedAgentPlan) {
        if (busy) return
        messages.add(ChatMessage("Вы", "Продолжить задачу: ${saved.plan.title}"))
        messages.add(ChatMessage("MOBILE AI", ""))
        busy = true
        status = "Возобновляю сохранённую задачу…"
        val resumeJob = BackgroundGenerationController.scope.launch {
            var notificationTitle = "Ответ готов"
            var notificationMessage = "MOBILE AI завершил продолжение задачи."
            try {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, GenerationForegroundService::class.java)
                        .setAction(GenerationForegroundService.ACTION_START)
                        .putExtra(
                            GenerationForegroundService.EXTRA_LOCAL_INFERENCE,
                            mode == AgentMode.LOCAL || !geminiKeyConfigured
                        )
                )
                withContext(Dispatchers.IO) {
                    coordinator.resumePlan(
                        planId = saved.plan.id,
                        maxTokensPerStep = runtimeConfig.maxOutputTokens,
                        hybridMode = mode == AgentMode.HYBRID && geminiKeyConfigured,
                        routing = runtimeConfig.routing,
                        systemPrompt = systemPrompt,
                        temperature = runtimeConfig.temperature
                    ).collect { event ->
                        if (event is com.mikhaildezprofi.mobileaiagent.AgentTaskEvent.StepOutput) {
                            withContext(Dispatchers.Main) {
                                val index = messages.lastIndex
                                if (index >= 0) {
                                    messages[index] = messages[index].copy(text = messages[index].text + event.text)
                                }
                            }
                        }
                    }
                }
                status = "Задача завершена."
            } catch (error: CancellationException) {
                notificationTitle = "Задача приостановлена"
                notificationMessage = "Сохранённые результаты шагов доступны в истории задач."
                throw error
            } catch (error: Exception) {
                val message = "Ошибка продолжения: ${error.message ?: error.javaClass.simpleName}"
                withContext(Dispatchers.Main) {
                    val index = messages.lastIndex
                    if (index >= 0) messages[index] = messages[index].copy(text = message)
                }
                status = message
                notificationTitle = "Ошибка задачи"
                notificationMessage = error.message ?: error.javaClass.simpleName
            } finally {
                withContext(Dispatchers.Main) {
                    busy = false
                    refreshAgentTaskHistory()
                }
                appContext.startService(
                    Intent(appContext, GenerationForegroundService::class.java)
                        .setAction(GenerationForegroundService.ACTION_COMPLETE)
                        .putExtra(GenerationForegroundService.EXTRA_TITLE, notificationTitle)
                        .putExtra(GenerationForegroundService.EXTRA_MESSAGE, notificationMessage)
                )
                BackgroundGenerationController.currentJob = null
            }
        }
        BackgroundGenerationController.currentJob = resumeJob
    }

    fun sendMessage() {
        val rawUserText = input.trim()
        val userText = rawUserText.take(runtimeConfig.maxPromptCharacters)
        val promptWasTruncated = rawUserText.length > userText.length
        val cloudOnlyReady = mode == AgentMode.HYBRID &&
            geminiKeyConfigured && runtimeConfig.geminiApiEnabled
        if ((userText.isEmpty() && attachments.isEmpty()) ||
            (!modelReady && !cloudOnlyReady) || busy
        ) return

        val attached = attachments.toList()
        val prompt = buildString {
            append(userText)
            if (attached.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("Пользователь добавил вложения:")
                attached.forEach { appendLine("- ${it.type}: ${it.name}") }
                append("Текстовые файлы, DOCX, XLSX, PPTX и PDF читаются локально, если формат поддерживается. Фото и видео автоматически направляются локальному Vision-анализатору; координатор затем выбирает языковую модель для итогового ответа.")
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
        while (messages.size > runtimeConfig.maxHistoryMessages) messages.removeAt(0)
        busy = true
        status = "Модель генерирует ответ…" +
            if (promptWasTruncated) " Текст ограничен лимитом runtime-конфигурации (${runtimeConfig.maxPromptCharacters} символов)." else ""

        val route = coordinator.route(
            prompt,
            hybridMode = mode == AgentMode.HYBRID && attached.isEmpty(),
            routing = runtimeConfig.routing
        )
        status = (route.warning ?: route.reason) +
            if (promptWasTruncated) " Текст запроса сокращён до ${runtimeConfig.maxPromptCharacters} символов." else ""
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
                    val withDocuments = if (runtimeConfig.documentReadingEnabled) {
                        SafeDocumentReader.appendSelectedFiles(prompt, appContext.contentResolver, textFileUris)
                    } else if (textFileUris.isNotEmpty()) {
                        prompt + "\n\nЧтение документов отключено в runtime-настройках. Содержимое файлов не прочитано."
                    } else prompt
                    if (!runtimeConfig.videoFrameExtractionEnabled && attached.any { it.type == "Видео" }) {
                        withDocuments + "\n\nИзвлечение кадров видео отключено в runtime-настройках. Содержимое видео недоступно."
                    } else withDocuments
                }
                val priorMessages = messages.dropLast(2).takeLast(runtimeConfig.maxHistoryMessages)
                val historyContext = priorMessages.joinToString("\n") { message ->
                    "${message.speaker}: ${message.text}"
                }.takeLast((runtimeConfig.maxPromptCharacters / 2).coerceAtLeast(512))
                val conversationPrompt = if (historyContext.isBlank()) effectivePrompt else buildString {
                    appendLine("История текущего диалога:")
                    appendLine(historyContext)
                    appendLine()
                    appendLine("Новый запрос пользователя:")
                    append(effectivePrompt)
                }
                val imageSources = attached.filter { it.type == "Фото" }.mapNotNull { attachment ->
                    when {
                        attachment.uri != null -> ImageAttachmentSource(attachment.name, uri = attachment.uri)
                        attachment.bitmap != null -> ImageAttachmentSource(attachment.name, bitmap = attachment.bitmap)
                        else -> null
                    }
                }
                val hasVideoAttachment = attached.any { it.type == "Видео" }
                val hasVisualAttachments = imageSources.isNotEmpty() || hasVideoAttachment
                if (hasVisualAttachments) {
                    check(runtimeConfig.visionInferenceEnabled) {
                        "Vision-модуль отключён в настройках. Включите его, чтобы анализировать фото и видео."
                    }
                    if (hasVideoAttachment) {
                        check(runtimeConfig.videoFrameExtractionEnabled) {
                            "Извлечение кадров видео отключено в настройках."
                        }
                    }
                    var stagedImages: com.mikhaildezprofi.mobileaiagent.StagedImageFiles? = null
                    val extractedVideoFrames = mutableListOf<ImageAttachmentSource>()
                    val visionSummary = StringBuilder()
                    try {
                        visionDownloadBusy = true
                        visionDownloadProgress = 0f
                        visionDownloadStatus = "Проверка и подготовка PocketPal Vision-модели…"
                        status = "Координатор подготавливает локальный PocketPal MTMD…"
                        val modelPreferences = appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
                        val savedVisionModel = modelPreferences.getString("vision_model_path", null)?.let { File(it) }
                        val savedProjector = modelPreferences.getString("vision_mmproj_path", null)?.let { File(it) }
                        val officialModel = VisionModelAssets.modelFile(appContext)
                        val useSelectedPair = savedVisionModel?.isFile == true &&
                            savedVisionModel.canRead() && savedProjector?.isFile == true &&
                            savedProjector.canRead() && savedVisionModel.absolutePath != officialModel.absolutePath
                        val assets = if (useSelectedPair) {
                            VisionModelAssets.Assets(savedVisionModel!!, savedProjector!!)
                        } else {
                            VisionModelAssets.download(appContext) { progress ->
                                withContext(Dispatchers.Main) {
                                    visionDownloadProgress = progress.fraction
                                    val currentMb = progress.fileBytes / (1024L * 1024L)
                                    val totalMb = progress.fileTotalBytes / (1024L * 1024L)
                                    val overallMb = progress.completedBytes / (1024L * 1024L)
                                    val overallTotalMb = progress.totalBytes / (1024L * 1024L)
                                    visionDownloadStatus =
                                        "${progress.fileName}: $currentMb / $totalMb МБ • всего $overallMb / $overallTotalMb МБ"
                                }
                            }
                        }
                        modelPreferences.edit()
                            .putString("vision_model_path", assets.model.absolutePath)
                            .putString("vision_mmproj_path", assets.projector.absolutePath)
                            .apply()
                        visionReady = true
                        visionRevision++
                        visionDownloadStatus = "Файлы Vision проверены. Загружаю PocketPal…"

                        status = if (hasVideoAttachment) {
                            "Извлечение ключевых кадров видео и анализ через PocketPal…"
                        } else {
                            "Локальный анализ фото через PocketPal…"
                        }
                        val selectedImageSources = imageSources.take(if (hasVideoAttachment) 3 else 4)
                        var remainingFrames = (4 - selectedImageSources.size).coerceAtLeast(0)
                        for (video in attached.filter { it.type == "Видео" && it.uri != null }) {
                            if (remainingFrames <= 0) break
                            val frames = withContext(Dispatchers.IO) {
                                VideoFrameExtractor.extractFrames(
                                    appContext, video.uri!!, video.name, remainingFrames
                                )
                            }
                            extractedVideoFrames += frames
                            remainingFrames -= frames.size
                        }
                        val visualSources = selectedImageSources + extractedVideoFrames
                        check(visualSources.isNotEmpty()) {
                            "Не удалось получить изображения или кадры из выбранного видео."
                        }
                        val videoNote = if (hasVideoAttachment) {
                            "\n\nВидео представлено ${extractedVideoFrames.size} ключевыми кадрами. Не делай выводов о промежутках между кадрами."
                        } else ""
                        val omittedImages = imageSources.size - selectedImageSources.size
                        val imageLimitNote = if (omittedImages > 0) {
                            "\n\nДля одного запроса выбраны первые ${selectedImageSources.size} фото; ещё $omittedImages фото пропущено из-за лимита обработки."
                        } else ""
                        stagedImages = ImageAttachmentStager.stage(appContext, visualSources)
                        withContext(Dispatchers.IO) {
                            coordinator.generateWithImages(
                                prompt = effectivePrompt + imageLimitNote + videoNote,
                                imagePaths = stagedImages!!.files.map { it.absolutePath },
                                modelPath = assets.model.absolutePath,
                                projectorPath = assets.projector.absolutePath,
                                maxTokens = minOf(
                                    1024, runtimeConfig.maxOutputTokens, runtimeConfig.routing.localMaxTokens
                                ),
                                temperature = runtimeConfig.temperature,
                                systemPrompt = systemPrompt
                            ).collect { token -> visionSummary.append(token) }
                        }
                        check(visionSummary.isNotBlank()) {
                            "PocketPal Vision-модель не вернула описание содержимого."
                        }
                        visionDownloadStatus = "PocketPal Vision-анализ завершён."
                    } finally {
                        stagedImages?.close()
                        extractedVideoFrames.forEach { source ->
                            source.bitmap?.let { bitmap ->
                                if (!bitmap.isRecycled) bitmap.recycle()
                            }
                        }
                        visionDownloadBusy = false
                    }

                    // Original photo/video files stay on-device. Only the text summary
                    // produced by the local Vision model can be routed to the cloud.
                    val languagePrompt = buildString {
                        if (historyContext.isNotBlank()) {
                            appendLine("История текущего диалога:")
                            appendLine(historyContext)
                            appendLine()
                        }
                        appendLine("Запрос пользователя:")
                        appendLine(userText.ifBlank { "Проанализируй приложенные визуальные материалы." })
                        appendLine()
                        appendLine("Результат локального анализа визуальных данных:")
                        appendLine(visionSummary.toString().trim())
                        appendLine()
                        append("Подготовь точный ответ на запрос пользователя. Не утверждай, что видел детали, которых нет в описании.")
                    }
                    status = "Vision-анализ готов. Координатор выбирает языковую модель…"
                    withContext(Dispatchers.IO) {
                        coordinator.generate(
                            prompt = languagePrompt,
                            maxTokens = runtimeConfig.maxOutputTokens,
                            hybridMode = mode == AgentMode.HYBRID && textFileUris.isEmpty(),
                            routing = runtimeConfig.routing,
                            systemPrompt = systemPrompt,
                            temperature = runtimeConfig.temperature,
                            taskOverride = AgentTaskKind.GENERAL
                        ).collect { token ->
                            pendingTokens.append(token)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUiFlush >= 100L) {
                                flushPendingTokens()
                                lastUiFlush = now
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        coordinator.generateWithPlanning(
                            prompt = conversationPrompt,
                            maxTokens = runtimeConfig.maxOutputTokens,
                            // Documents and other attachments stay local unless a separate policy is added.
                            hybridMode = mode == AgentMode.HYBRID && attached.isEmpty(),
                            routing = runtimeConfig.routing,
                            systemPrompt = systemPrompt,
                            temperature = runtimeConfig.temperature
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
                NavigationDrawerItem(
                    label = { Text("История задач") },
                    selected = false,
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    onClick = {
                        refreshAgentTaskHistory()
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
                    label = { Text("Проекты Code Agent") },
                    selected = false,
                    icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                    onClick = {
                        showCodeProjectDialog = true
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
                        selectedPromptProfileId = runtimeConfig.selectedPromptProfileId
                        selectedResponseProfileId = runtimeConfig.selectedResponseProfileId
                        showSettingsDialog = true
                        scope.launch { drawerState.close() }
                    }
                )
                NavigationDrawerItem(
                    label = { Text("Экспорт резервной копии") },
                    selected = false,
                    icon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    onClick = {
                        if (!busy) {
                            if (messages.isNotEmpty()) {
                                val title = messages.firstOrNull { it.speaker == "Вы" }?.text
                                    ?.replace("\n", " ")?.take(42)?.ifBlank { "Новый чат" } ?: "Новый чат"
                                val active = SavedChat(currentChatId, title, messages.toList(), System.currentTimeMillis())
                                val snapshot = (listOf(active) + chatHistory.filterNot { it.id == currentChatId }).take(50)
                                chatHistory.clear()
                                chatHistory.addAll(snapshot)
                                saveSavedChats(appContext, snapshot)
                            }
                            backupExportLauncher.launch("MOBILE_AI_AGENT_backup.json")
                        } else {
                            status = "Дождитесь завершения генерации перед экспортом."
                        }
                        scope.launch { drawerState.close() }
                    }
                )
                NavigationDrawerItem(
                    label = { Text("Восстановить резервную копию") },
                    selected = false,
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    onClick = {
                        if (!busy) backupImportLauncher.launch(arrayOf("application/json", "text/json", "*/*"))
                        else status = "Дождитесь завершения генерации перед восстановлением."
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
                                messages.addAll(savedChat.messages.takeLast(runtimeConfig.maxHistoryMessages))
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
                                placeholder = if (messages.isEmpty()) runtimeConfig.placeholderNewChat else runtimeConfig.placeholderReply,
                                maxHeight = (maxHeight - 8.dp).coerceAtLeast(96.dp),
                                attachmentsEnabled = runtimeConfig.attachmentsEnabled,
                                voiceEnabled = runtimeConfig.voiceEnabled,
                                canSend = (modelReady || (mode == AgentMode.HYBRID && geminiKeyConfigured && runtimeConfig.geminiApiEnabled)) &&
                                    !busy && (input.isNotBlank() || attachments.isNotEmpty()),
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
                        if (modelReady) "Координатор автоматически выбирает локальную, Vision или облачную модель."
                        else if (mode == AgentMode.HYBRID && geminiKeyConfigured && runtimeConfig.geminiApiEnabled) "Облачный режим готов. Для локальной и Vision-обработки загрузите Qwen."
                        else "Откройте меню и настройте модель или Gemini API.",
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

    if (showCapabilitiesDialog) {
        val capabilities = AgentToolRegistry.forRuntime(
            localReady = modelReady,
            cloudReady = geminiKeyConfigured && runtimeConfig.geminiApiEnabled,
            fileReaderReady = runtimeConfig.documentReadingEnabled,
            visionReady = visionReady && runtimeConfig.visionInferenceEnabled,
            codeWorkspaceReady = modelReady
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
                    Text(
                        "Пока не подключены: веб-поиск, управление Android, пробуждение по слову «Джимми» и синтез речи. Удалённые флаги не включают отсутствующий код.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                    SelectionContainer { Text(saved.plan.originalPrompt, style = MaterialTheme.typography.bodySmall) }
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

    if (showCodeProjectDialog) {
        AlertDialog(
            onDismissRequest = { showCodeProjectDialog = false },
            title = { Text("Проекты Code Agent") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = codeProjectName,
                        onValueChange = { codeProjectName = it },
                        label = { Text("Имя проекта") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Проекты хранятся в приватной папке приложения. Импортируйте ZIP, затем попросите Джимми изучить или изменить файлы проекта.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(codeProjectStatus, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("GitHub: отправка проекта и тестирование", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Создаётся отдельная ветка code-agent/*. Перед отправкой показывается список файлов. Удаление отключено по умолчанию и доступно только для файлов, ранее синхронизированных Джимми в этой ветке.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    githubWorkingBranch?.let { working ->
                        Text("Рабочая ветка: ${working.name}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (githubPreviewText.isNotBlank()) {
                        SelectionContainer {
                            Text(githubPreviewText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (githubPendingDeletePaths.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = githubDeleteConfirmed,
                                onCheckedChange = { githubDeleteConfirmed = it }
                            )
                            Text(
                                "Подтверждаю удаление ${githubPendingDeletePaths.size} файлов из списка выше",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (githubProjectStatus.isNotBlank()) {
                        Text(githubProjectStatus, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            enabled = githubTokenConfigured && !githubProjectBusy && codeProjectName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")),
                            onClick = {
                                githubProjectBusy = true
                                githubProjectStatus = "Создание изолированной ветки GitHub…"
                                scope.launch {
                                    try {
                                        val working = withContext(Dispatchers.IO) {
                                            GitHubApiClient(githubTokenStore).createWorkingBranch()
                                        }
                                        githubWorkingBranch = working
                                        appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE).edit()
                                            .putString("github_working_branch", working.name)
                                            .putString("github_working_base", working.baseBranch)
                                            .putString("github_working_sha", working.baseSha)
                                            .remove("github_last_run_id")
                                            .apply()
                                        githubPendingFiles = emptyList()
                                        githubPendingDeletePaths = emptyList()
                                        githubDeleteConfirmed = false
                                        githubPreviewText = ""
                                        githubLastRunId = null
                                        githubProjectStatus = "Создана ветка ${working.name} от ${working.baseBranch}."
                                    } catch (error: Exception) {
                                        githubProjectStatus = "Не удалось создать ветку: ${error.message ?: error.javaClass.simpleName}"
                                    } finally {
                                        githubProjectBusy = false
                                    }
                                }
                            }
                        ) { Text("Создать ветку") }
                        TextButton(
                            enabled = !githubProjectBusy && codeProjectName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")),
                            onClick = {
                                githubProjectBusy = true
                                githubProjectStatus = "Чтение проекта и проверка файлов…"
                                scope.launch {
                                    try {
                                        val files = withContext(Dispatchers.IO) {
                                            val root = File(appContext.filesDir, "code-workspace").canonicalFile
                                            val project = File(root, codeProjectName.trim()).canonicalFile
                                            require(project.parentFile == root && project.isDirectory) {
                                                "Сначала выберите существующий проект."
                                            }
                                            val workspace = CodeWorkspace(project)
                                            val paths = workspace.listFiles(maxDepth = 8, maxFiles = 500)
                                            require(paths.size <= 100) {
                                                "В проекте ${paths.size} файлов. GitHub отправка ограничена 100 файлами за один коммит."
                                            }
                                            val prepared = mutableListOf<GitHubWorkspaceFile>()
                                            var totalBytes = 0L
                                            paths.forEach { relative ->
                                                GitHubApiClient.normalizeWorkspacePath(relative)
                                                val read = workspace.readFile(relative)
                                                val fileBytes = read.content.toByteArray(Charsets.UTF_8).size
                                                require(fileBytes <= 512 * 1024) { "Файл превышает лимит безопасной отправки: $relative" }
                                                totalBytes += fileBytes
                                                require(totalBytes <= 5L * 1024 * 1024) {
                                                    "Общий размер проекта превышает лимит отправки 5 MiB."
                                                }
                                                prepared += GitHubWorkspaceFile(relative, read.content)
                                            }
                                            prepared
                                        }
                                        val workingName = githubWorkingBranch?.name
                                        val prefs = appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
                                        val managedKey = workingName?.let { "github_managed_paths_$it" }
                                        val previouslyManaged = managedKey?.let { prefs.getStringSet(it, emptySet())?.toSet() }.orEmpty()
                                        val currentPaths = files.map { it.path }.toSet()
                                        val deletions = previouslyManaged.filterNot { it in currentPaths }.sorted()
                                        require(files.isNotEmpty() || deletions.isNotEmpty()) {
                                            "Проект пуст и в этой ветке нет ранее синхронизированных файлов для удаления."
                                        }
                                        githubPendingFiles = files
                                        githubPendingDeletePaths = deletions
                                        githubDeleteConfirmed = false
                                        githubPreviewText = buildString {
                                            append("Предпросмотр изменений: ${files.size} файлов, ")
                                            append(files.sumOf { it.content.toByteArray(Charsets.UTF_8).size } / 1024)
                                            append(" КБ\\n")
                                            files.forEach { append("• + ").append(it.path).append('\\n') }
                                            if (deletions.isNotEmpty()) {
                                                append("\\nКандидаты на удаление (не выбраны автоматически):\\n")
                                                deletions.forEach { append("• − ").append(it).append('\\n') }
                                            } else {
                                                append("\\nУдалений нет. Файлы из базовой ветки не затрагиваются.")
                                            }
                                        }
                                        githubProjectStatus = if (workingName == null) {
                                            "Предпросмотр готов. Для сопоставления с предыдущей синхронизацией сначала создайте рабочую ветку."
                                        } else {
                                            "Предпросмотр готов. Ничего не отправлено и не удалено."
                                        }
                                    } catch (error: Exception) {
                                        githubPendingFiles = emptyList()
                                        githubPendingDeletePaths = emptyList()
                                        githubDeleteConfirmed = false
                                        githubPreviewText = ""
                                        githubProjectStatus = "Предпросмотр остановлен: ${error.message ?: error.javaClass.simpleName}"
                                    } finally {
                                        githubProjectBusy = false
                                    }
                                }
                            }
                        ) { Text("Предпросмотр") }
                    }
                    TextButton(
                        enabled = githubTokenConfigured && githubWorkingBranch != null &&
                            (githubPendingFiles.isNotEmpty() || (githubDeleteConfirmed && githubPendingDeletePaths.isNotEmpty())) && !githubProjectBusy,
                        onClick = {
                            val working = githubWorkingBranch ?: return@TextButton
                            val files = githubPendingFiles
                            val deletePaths = if (githubDeleteConfirmed) githubPendingDeletePaths else emptyList()
                            githubProjectBusy = true
                            githubProjectStatus = "Отправка подтверждённого списка файлов в ${working.name}…"
                            scope.launch {
                                try {
                                    val committed = withContext(Dispatchers.IO) {
                                        GitHubApiClient(githubTokenStore).commitWorkspaceChanges(
                                            branch = working.name,
                                            expectedHeadSha = working.baseSha,
                                            files = files,
                                            message = "Code Agent: update ${codeProjectName.trim()}",
                                            deletePaths = deletePaths
                                        )
                                    }
                                    val updatedBranch = working.copy(baseSha = committed.commitSha)
                                    githubWorkingBranch = updatedBranch
                                    appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE).edit()
                                        .putString("github_working_sha", updatedBranch.baseSha)
                                        .apply()
                                    val managedKey = "github_managed_paths_${working.name}"
                                    val prefs = appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
                                    val previousManaged = prefs.getStringSet(managedKey, emptySet())?.toSet().orEmpty()
                                    val nextManaged = (previousManaged + files.map { it.path }).minus(deletePaths.toSet())
                                    prefs.edit().putStringSet(managedKey, nextManaged).apply()
                                    githubPendingFiles = emptyList()
                                    githubPendingDeletePaths = emptyList()
                                    githubDeleteConfirmed = false
                                    githubProjectStatus = "Отправлено изменений: ${committed.changedFiles} (удалено: ${deletePaths.size}). Commit ${committed.commitSha.take(12)}. Запускаю unit-тесты…"
                                    withContext(Dispatchers.IO) {
                                        GitHubApiClient(githubTokenStore).dispatchUnitTests(working.name)
                                    }
                                    githubProjectStatus = "Коммит создан. Unit-тесты отправлены в GitHub Actions для ${working.name}."
                                    delay(2500)
                                    val run = withContext(Dispatchers.IO) {
                                        GitHubApiClient(githubTokenStore).listUnitTestRuns(working.name, 5)
                                            .firstOrNull { it.headSha == committed.commitSha }
                                    }
                                    if (run != null) {
                                        githubLastRunId = run.id
                                        appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE).edit()
                                            .putLong("github_last_run_id", run.id).apply()
                                        githubProjectStatus = "Unit-тесты: ${run.status}${run.conclusion?.let { " / $it" } ?: ""}. Run #${run.id}."
                                    } else {
                                        githubProjectStatus = "Запуск принят GitHub. Результат пока не появился; нажмите «Обновить статус тестов» позже."
                                    }
                                } catch (error: Exception) {
                                    githubProjectStatus = "GitHub операция завершилась ошибкой: ${error.message ?: error.javaClass.simpleName}"
                                } finally {
                                    githubProjectBusy = false
                                }
                            }
                        }
                    ) { Text("Подтвердить отправку и запустить тесты") }
                    TextButton(
                        enabled = githubTokenConfigured && githubWorkingBranch != null && !githubProjectBusy,
                        onClick = {
                            val working = githubWorkingBranch ?: return@TextButton
                            githubProjectBusy = true
                            scope.launch {
                                try {
                                    val run = withContext(Dispatchers.IO) {
                                        GitHubApiClient(githubTokenStore).listUnitTestRuns(working.name, 5).firstOrNull()
                                    }
                                    if (run == null) {
                                        githubProjectStatus = "GitHub пока не вернул запуск для этой ветки."
                                    } else {
                                        githubLastRunId = run.id
                                        appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE).edit()
                                            .putLong("github_last_run_id", run.id).apply()
                                        githubProjectStatus = "Run #${run.id}: ${run.status}${run.conclusion?.let { " / $it" } ?: ""}. ${run.htmlUrl}"
                                    }
                                } catch (error: Exception) {
                                    githubProjectStatus = "Не удалось получить статус: ${error.message ?: error.javaClass.simpleName}"
                                } finally {
                                    githubProjectBusy = false
                                }
                            }
                        }
                    ) { Text("Обновить статус тестов") }
                    githubLastRunId?.let { runId ->
                        TextButton(
                            enabled = githubTokenConfigured && !githubProjectBusy,
                            onClick = {
                                githubProjectBusy = true
                                scope.launch {
                                    try {
                                        val report = withContext(Dispatchers.IO) {
                                            val api = GitHubApiClient(githubTokenStore)
                                            val jobs = api.listRunJobs(runId)
                                            jobs.joinToString("\n\n") { job ->
                                                "• ${job.name}: ${job.status} / ${job.conclusion ?: "выполняется"}\n" +
                                                    if (job.conclusion == "failure") api.getRunJobLog(runId, job.id).take(12000)
                                                    else ""
                                            }
                                        }
                                        githubProjectStatus = report.ifBlank { "GitHub Actions пока не вернул сведения о шагах." }
                                    } catch (error: Exception) {
                                        githubProjectStatus = "Не удалось прочитать журнал: ${error.message ?: error.javaClass.simpleName}"
                                    } finally {
                                        githubProjectBusy = false
                                    }
                                }
                            }
                        ) { Text("Показать шаги и ошибки тестов") }
                    }
                    TextButton(
                        enabled = !busy && codeProjectName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")),
                        onClick = {
                            val workspaceRoot = File(appContext.filesDir, "code-workspace").canonicalFile
                            val selectedProject = File(workspaceRoot, codeProjectName.trim()).canonicalFile
                            if (selectedProject.parentFile == workspaceRoot && selectedProject.isDirectory) {
                                appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
                                    .edit().putString("active_code_project", codeProjectName.trim()).apply()
                                codeProjectStatus = "Активен проект «${codeProjectName.trim()}». Инструменты Code Agent ограничены его папкой."
                                status = codeProjectStatus
                            } else {
                                codeProjectStatus = "Проект с таким именем не найден в рабочей области."
                            }
                        }
                    ) { Text("Выбрать проект для Джимми") }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        enabled = !busy && codeProjectName.isNotBlank(),
                        onClick = {
                            codeProjectImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                        }
                    ) { Text("Импорт ZIP") }
                    TextButton(
                        enabled = !busy && codeProjectName.isNotBlank(),
                        onClick = {
                            codeProjectExportLauncher.launch("${codeProjectName.trim()}.zip")
                        }
                    ) { Text("Экспорт ZIP") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCodeProjectDialog = false }) { Text("Закрыть") }
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
                        refreshAgentTaskHistory()
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { planPendingDeletion = null }) { Text("Отмена") }
            }
        )
    }

    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("Модель и режим") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(status)
                    Text(
                        when {
                            visionReady -> "Активна SmolVLM: локальный анализ изображений доступен."
                            modelReady -> "Локальная текстовая модель загружена."
                            else -> "Локальная модель не загружена."
                        }
                    )
                    HorizontalDivider()
                    Text("Vision-модель для фото и кадров видео", fontWeight = FontWeight.SemiBold)
                    Text("SmolVLM 500M Q8_0 • 436,8 МБ + проектор 108,8 МБ. Файлы хранятся отдельно от Qwen; одновременно загружается только одна модель.")
                    if (visionDownloadBusy) {
                        LinearProgressIndicator(progress = visionDownloadProgress, modifier = Modifier.fillMaxWidth())
                    }
                    Text(visionDownloadStatus, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Ручное переключение не требуется. При первом фото или видео координатор сам скачает и проверит SmolVLM, временно загрузит её, выполнит анализ и вернёт языковую модель. Исходные медиа остаются на телефоне; в гибридном режиме в облако может уйти только текстовое резюме локального анализа.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Локальный режим")
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = mode == AgentMode.HYBRID,
                            onCheckedChange = {
                                mode = if (it) AgentMode.HYBRID else AgentMode.LOCAL
                                RuntimeFiles.saveMode(appContext, mode == AgentMode.HYBRID)
                            }
                        )
                        Text("Гибридный")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
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
                                        geminiTestStatus = ""
                                        geminiKeyStatus = "Ключ сохранён в Android Keystore."
                                        status = "Gemini API-ключ сохранён."
                                    } catch (error: Exception) {
                                        geminiKeyStatus = "Не удалось сохранить ключ: ${error.message ?: error.javaClass.simpleName}"
                                    }
                                }
                            }
                        ) { Text("Сохранить ключ") }
                        TextButton(
                            enabled = geminiKeyConfigured && !geminiTestBusy,
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { geminiKeyStore.clear() }
                                    geminiApiKeyDraft = ""
                                    geminiKeyConfigured = false
                                    geminiTestStatus = ""
                                    geminiKeyStatus = "Ключ удалён с устройства."
                                    status = "Gemini API-ключ удалён."
                                }
                            }
                        ) { Text("Удалить ключ") }
                    }
                    Text(geminiKeyStatus, style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        enabled = geminiKeyConfigured && !geminiTestBusy,
                        onClick = {
                            geminiTestBusy = true
                            geminiTestStatus = "Проверка ключа и доступности модели…"
                            scope.launch {
                                try {
                                    geminiTestStatus = GeminiCloudProvider(
                                        geminiKeyStore,
                                        model = runtimeConfig.geminiModelId,
                                        connectTimeoutMs = runtimeConfig.networkConnectTimeoutSeconds * 1000,
                                        readTimeoutMs = runtimeConfig.networkReadTimeoutSeconds * 1000
                                    ).testConnection()
                                } catch (error: Exception) {
                                    geminiTestStatus = "Проверка не пройдена: ${error.message ?: error.javaClass.simpleName}"
                                } finally {
                                    geminiTestBusy = false
                                }
                            }
                        }
                    ) {
                        Text(if (geminiTestBusy) "Проверяем…" else "Проверить подключение Gemini")
                    }
                    if (geminiTestStatus.isNotBlank()) {
                        Text(geminiTestStatus, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${runtimeConfig.geminiModelId} • бесплатный уровень при наличии квоты Google")
                    HorizontalDivider()
                    Text("GitHub Code Agent", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (githubTokenConfigured) "Токен сохранён в Android Keystore. Сам токен повторно не отображается."
                        else "Токен не настроен. Для подключения используйте fine-grained personal access token.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = githubTokenDraft,
                        onValueChange = { githubTokenDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("GitHub fine-grained token") },
                        placeholder = { Text("Вставьте токен GitHub") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = { Text("Минимальные права для этого репозитория: Contents Read and write; Actions Read and write. Metadata Read предоставляется автоматически. Не отправляйте токен в чат и не добавляйте его в Git.") }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = githubTokenDraft.isNotBlank(),
                            onClick = {
                                val tokenToSave = githubTokenDraft
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { githubTokenStore.save(tokenToSave) }
                                        githubTokenDraft = ""
                                        githubTokenConfigured = true
                                        githubTokenStatus = "GitHub-токен зашифрован и сохранён."
                                        status = "GitHub-токен сохранён локально."
                                    } catch (error: Exception) {
                                        githubTokenStatus = "Не удалось сохранить токен: ${error.message ?: error.javaClass.simpleName}"
                                    }
                                }
                            }
                        ) { Text("Сохранить токен") }
                        TextButton(
                            enabled = githubTokenConfigured,
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { githubTokenStore.clear() }
                                    githubTokenDraft = ""
                                    githubTokenConfigured = false
                                    githubTokenStatus = "GitHub-токен удалён с устройства."
                                }
                            }
                        ) { Text("Удалить токен") }
                    }
                    Text(githubTokenStatus, style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        enabled = githubTokenConfigured && !githubConnectionBusy,
                        onClick = {
                            githubConnectionBusy = true
                            githubConnectionStatus = "Проверка доступа к репозиторию…"
                            scope.launch {
                                try {
                                    val info = withContext(Dispatchers.IO) {
                                        GitHubApiClient(githubTokenStore).validateConnection()
                                    }
                                    githubConnectionStatus = "Подключение подтверждено: ${info.fullName}, базовая ветка ${info.defaultBranch}."
                                } catch (error: Exception) {
                                    githubConnectionStatus = "Проверка не пройдена: ${error.message ?: error.javaClass.simpleName}"
                                } finally {
                                    githubConnectionBusy = false
                                }
                            }
                        }
                    ) {
                        Text(if (githubConnectionBusy) "Проверяем GitHub…" else "Проверить подключение GitHub")
                    }
                    if (githubConnectionStatus.isNotBlank()) {
                        Text(githubConnectionStatus, style = MaterialTheme.typography.bodySmall)
                    }
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
                    Text("Профиль помощника", fontWeight = FontWeight.SemiBold)
                    Box {
                        TextButton(onClick = { promptProfileMenuExpanded = true }) {
                            Text(configDraft.promptProfiles.firstOrNull { it.id == selectedPromptProfileId }?.label
                                ?: selectedPromptProfileId)
                        }
                        DropdownMenu(
                            expanded = promptProfileMenuExpanded,
                            onDismissRequest = { promptProfileMenuExpanded = false }
                        ) {
                            configDraft.promptProfiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text(profile.label) },
                                    onClick = {
                                        selectedPromptProfileId = profile.id
                                        configDraft = configDraft.copy(selectedPromptProfileId = profile.id)
                                        promptDraft = profile.content
                                        promptProfileMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Text("Формат ответа", fontWeight = FontWeight.SemiBold)
                    Box {
                        TextButton(onClick = { responseProfileMenuExpanded = true }) {
                            Text(configDraft.responseProfiles.firstOrNull { it.id == selectedResponseProfileId }?.label
                                ?: selectedResponseProfileId)
                        }
                        DropdownMenu(
                            expanded = responseProfileMenuExpanded,
                            onDismissRequest = { responseProfileMenuExpanded = false }
                        ) {
                            configDraft.responseProfiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text(profile.label) },
                                    onClick = {
                                        selectedResponseProfileId = profile.id
                                        configDraft = configDraft.copy(
                                            selectedResponseProfileId = profile.id,
                                            maxOutputTokens = profile.maxOutputTokens,
                                            temperature = profile.temperature
                                        )
                                        responseProfileMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        "Лимит ответа: ${configDraft.maxOutputTokens} токенов • temperature: ${configDraft.temperature}. Температура применяется к Gemini; локальный PocketPal получает temperature при каждом запросе.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Системный промпт")
                    OutlinedTextField(
                        value = promptDraft,
                        onValueChange = { promptDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5
                    )
                    Text("Встроенные модули")
                    Text(
                        "Недоступные в этой сборке функции нельзя активировать одной настройкой: веб-поиск, Android-действия, wake word и TTS остаются выключены.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = configDraft.documentReadingEnabled,
                            onCheckedChange = { configDraft = configDraft.copy(documentReadingEnabled = it) }
                        )
                        Text("Чтение документов")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = configDraft.videoFrameExtractionEnabled,
                            onCheckedChange = { configDraft = configDraft.copy(videoFrameExtractionEnabled = it) }
                        )
                        Text("Извлечение кадров видео")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = configDraft.visionInferenceEnabled,
                            onCheckedChange = { configDraft = configDraft.copy(visionInferenceEnabled = it) }
                        )
                        Text("Локальный анализ изображений")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = configDraft.geminiApiEnabled,
                            onCheckedChange = { configDraft = configDraft.copy(geminiApiEnabled = it) }
                        )
                        Text("Gemini API")
                    }
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
                            RuntimeFiles.clearModuleOverrides(appContext)
                            val loaded = RuntimeFiles.load(appContext)
                            runtimeFiles = loaded
                            runtimeConfig = loaded.config
                            configDraft = loaded.config
                            selectedPromptProfileId = loaded.config.selectedPromptProfileId
                            selectedResponseProfileId = loaded.config.selectedResponseProfileId
                            status = "Локальные переопределения модулей сброшены. Применены значения из runtime JSON."
                        }
                    ) { Text("Сбросить локальные переопределения модулей") }
                    TextButton(
                        enabled = !runtimeUpdateInProgress,
                        onClick = {
                            runtimeUpdateInProgress = true
                            scope.launch {
                                try {
                                    val result = RuntimeConfigUpdater.downloadAndApply(appContext)
                                    val loaded = RuntimeFiles.load(appContext)
                                    runtimeFiles = loaded
                                    runtimeConfig = loaded.config
                                    configDraft = loaded.config
                                    selectedPromptProfileId = loaded.config.selectedPromptProfileId
                                    selectedResponseProfileId = loaded.config.selectedResponseProfileId
                                    if (!loaded.customPrompt) {
                                        systemPrompt = loaded.systemPrompt
                                        promptDraft = loaded.systemPrompt
                                    }
                                    status = "Конфигурация ${result.version} проверена SHA-256 и загружена (${result.downloadedFiles} файлов)." +
                                        if (loaded.customPrompt) " Пользовательский промпт сохранён."
                                        else " Профиль ${loaded.config.selectedPromptProfileId} активен."
                                } catch (error: Exception) {
                                    status = "Обновление конфигурации не выполнено: ${error.message ?: error.javaClass.simpleName}"
                                } finally {
                                    runtimeUpdateInProgress = false
                                }
                            }
                        }
                    ) {
                        Text(if (runtimeUpdateInProgress) "Загрузка и проверка…" else "Скачать обновления конфигурации")
                    }
                    TextButton(
                        onClick = {
                            val loaded = RuntimeFiles.load(appContext)
                            runtimeFiles = loaded
                            runtimeConfig = loaded.config
                            configDraft = loaded.config
                            selectedPromptProfileId = loaded.config.selectedPromptProfileId
                            selectedResponseProfileId = loaded.config.selectedResponseProfileId
                            systemPrompt = loaded.systemPrompt
                            promptDraft = loaded.systemPrompt
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
                        RuntimeFiles.save(
                            appContext, savedConfig, promptDraft,
                            selectedPromptProfileId,
                            selectedResponseProfileId,
                            moduleOverrides = buildSet {
                                if (savedConfig.attachmentsEnabled != runtimeConfig.attachmentsEnabled) add("attachments")
                                if (savedConfig.voiceEnabled != runtimeConfig.voiceEnabled) add("voiceInput")
                                if (savedConfig.cameraEnabled != runtimeConfig.cameraEnabled) add("cameraCapture")
                                if (savedConfig.diagnosticsEnabled != runtimeConfig.diagnosticsEnabled) add("diagnostics")
                                if (savedConfig.videoFrameExtractionEnabled != runtimeConfig.videoFrameExtractionEnabled) add("videoFrameExtraction")
                                if (savedConfig.documentReadingEnabled != runtimeConfig.documentReadingEnabled) add("documentReading")
                                if (savedConfig.visionInferenceEnabled != runtimeConfig.visionInferenceEnabled) add("visionInference")
                                if (savedConfig.geminiApiEnabled != runtimeConfig.geminiApiEnabled) add("geminiApi")
                            }
                        )
                        runtimeFiles = RuntimeFiles.load(appContext)
                        runtimeConfig = runtimeFiles.config
                        selectedPromptProfileId = runtimeFiles.config.selectedPromptProfileId
                        selectedResponseProfileId = runtimeFiles.config.selectedResponseProfileId
                        systemPrompt = runtimeFiles.systemPrompt
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
                    Text("Версия $appVersion • llama.cpp • GGUF • Android arm64-v8a")
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
                                        if (messages.isEmpty()) runtimeConfig.placeholderNewChat else runtimeConfig.placeholderReply,
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
                            canSend = (modelReady || (mode == AgentMode.HYBRID && geminiKeyConfigured && runtimeConfig.geminiApiEnabled)) &&
                                    !busy && (input.isNotBlank() || attachments.isNotEmpty()),
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

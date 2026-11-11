package com.mikhaildezprofi.mobileaiagent.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/** Process-wide chat state so an Activity recreation does not detach an active generation from its UI. */
internal data class ChatMessage(
    val speaker: String,
    val text: String
)

internal object ChatSessionState {
    val messages = mutableStateListOf<ChatMessage>()
    val busy = mutableStateOf(false)
    val currentChatId = mutableStateOf(System.currentTimeMillis().toString())
}

package com.mikhaildezprofi.mobileaiagent

import org.json.JSONArray
import org.json.JSONObject

/** Snapshot callback from PocketPal's native completion parser. */
internal fun interface PocketPalSnapshotListener {
    fun onSnapshot(content: String, reasoning: String)
}

internal data class PocketPalToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

internal data class PocketPalGenerationResult(
    val content: String,
    val reasoning: String,
    val toolCalls: List<PocketPalToolCall>,
    val interrupted: Boolean
)

/**
 * Kotlin/JNI host adapter for PocketPal's native llama.rn core.
 *
 * This class deliberately does not depend on React Native, JSI, fbjni or the PocketPal UI.
 * It owns one native context and serializes model lifecycle and generation through JNI.
 */
internal class PocketPalNativeBridge : AutoCloseable {
    private var handle: Long = nativeCreateContext()

    init {
        check(handle != 0L) { "Не удалось создать нативный контекст PocketPal." }
    }

    @Synchronized
    fun loadModel(modelPath: String, contextSize: Int = 4096, threads: Int = 4) {
        check(handle != 0L) { "Нативный контекст PocketPal закрыт." }
        require(modelPath.isNotBlank()) { "Путь к GGUF-модели пуст." }
        check(nativeLoadModel(handle, modelPath, contextSize, threads)) {
            "Не удалось загрузить GGUF-модель через PocketPal."
        }
    }

    fun generate(
        messages: JSONArray,
        tools: JSONArray = JSONArray(),
        maxTokens: Int = 1024,
        temperature: Float = 0.7f,
        listener: PocketPalSnapshotListener? = null
    ): PocketPalGenerationResult {
        val currentHandle = synchronized(this) {
            check(handle != 0L) { "Нативный контекст PocketPal закрыт." }
            handle
        }
        val raw = nativeGenerate(
            currentHandle,
            messages.toString(),
            tools.toString(),
            maxTokens.coerceIn(1, 8192),
            temperature.coerceIn(0f, 2f),
            listener
        )
        return parseResult(raw)
    }

    fun cancel() {
        val currentHandle = synchronized(this) { handle }
        if (currentHandle != 0L) nativeCancel(currentHandle)
    }

    @Synchronized
    override fun close() {
        val currentHandle = handle
        handle = 0L
        if (currentHandle != 0L) nativeRelease(currentHandle)
    }

    private external fun nativeCreateContext(): Long
    private external fun nativeLoadModel(handle: Long, modelPath: String, contextSize: Int, threads: Int): Boolean
    private external fun nativeGenerate(
        handle: Long,
        messagesJson: String,
        toolsJson: String,
        maxTokens: Int,
        temperature: Float,
        listener: PocketPalSnapshotListener?
    ): String
    private external fun nativeCancel(handle: Long)
    private external fun nativeRelease(handle: Long)

    companion object {
        init {
            System.loadLibrary("rnllama")
            System.loadLibrary("jimmy_pocketpal_jni")
        }

        internal fun parseResult(raw: String): PocketPalGenerationResult {
            val root = JSONObject(raw)
            val calls = root.optJSONArray("tool_calls") ?: JSONArray()
            val toolCalls = buildList {
                for (index in 0 until calls.length()) {
                    val call = calls.optJSONObject(index) ?: continue
                    val name = call.optString("name").trim()
                    if (name.isBlank()) continue
                    add(
                        PocketPalToolCall(
                            id = call.optString("id"),
                            name = name,
                            argumentsJson = call.optString("arguments", "{}")
                        )
                    )
                }
            }
            return PocketPalGenerationResult(
                content = root.optString("content"),
                reasoning = root.optString("reasoning_content"),
                toolCalls = toolCalls,
                interrupted = root.optBoolean("interrupted", false)
            )
        }
    }
}

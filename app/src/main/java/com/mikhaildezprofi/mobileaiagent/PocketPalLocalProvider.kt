package com.mikhaildezprofi.mobileaiagent

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Primary local text provider backed by PocketPal's llama.rn engine.
 * Model paths are read from the same preference written by the model picker.
 */
internal class PocketPalLocalProvider(context: Context) : AgentModelProvider, AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    @Volatile private var loadedPath: String? = null
    @Volatile private var bridge: PocketPalNativeBridge? = null

    override val id: String = "PocketPal llama.rn"
    override val isAvailable: Boolean
        get() = currentModelFile()?.let { it.isFile && it.canRead() && it.length() > 0L } == true

    private fun currentModelFile(): File? {
        val preferences = appContext.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
        val saved = preferences.getString("model_path", null)?.let(::File)
            ?.takeIf { !it.name.contains("SmolVLM", ignoreCase = true) &&
                !it.name.contains("mmproj", ignoreCase = true) }
        val fallback = File(appContext.filesDir, "models/Qwen3-1.7B-Q4_K_M.gguf")
        return listOfNotNull(saved, fallback).firstOrNull {
            it.isFile && it.canRead() && it.length() > 0L
        }
    }

    private fun ensureEngine(model: File): PocketPalNativeBridge {
        val path = model.absolutePath
        synchronized(lock) {
            val active = bridge
            if (active != null && loadedPath == path) return active

            active?.close()
            val next = PocketPalNativeBridge()
            try {
                val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                next.loadModel(path, contextSize = 4096, threads = cores)
                bridge = next
                loadedPath = path
                return next
            } catch (error: Exception) {
                next.close()
                bridge = null
                loadedPath = null
                throw error
            }
        }
    }

    override fun generate(prompt: String, maxTokens: Int): Flow<String> =
        generate(prompt, maxTokens, 0.7, "")

    override fun generate(prompt: String, maxTokens: Int, temperature: Double): Flow<String> =
        generate(prompt, maxTokens, temperature, "")

    override fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Double,
        systemPrompt: String
    ): Flow<String> = channelFlow {
        require(prompt.isNotBlank()) { "Запрос пуст." }
        val model = currentModelFile()
            ?: throw IllegalStateException("Файл локальной GGUF-модели не найден.")
        val worker = launch(Dispatchers.IO) {
            try {
                val active = ensureEngine(model)
                val messages = JSONArray()
                if (systemPrompt.isNotBlank()) {
                    messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
                }
                messages.put(JSONObject().put("role", "user").put("content", prompt))
                var previousSnapshot = ""
                val listener = object : PocketPalSnapshotListener {
                    override fun onTurnStarted() {
                        previousSnapshot = ""
                    }

                    override fun onSnapshot(content: String, reasoning: String) {
                        // Native parser snapshots are cumulative. Convert them to deltas
                        // for the existing Compose chat collector. Reasoning stays hidden.
                        val delta = if (content.startsWith(previousSnapshot)) {
                            content.substring(previousSnapshot.length)
                        } else {
                            content
                        }
                        previousSnapshot = content
                        if (delta.isNotEmpty()) trySend(delta)
                    }
                }
                val result = PocketPalStructuredAgentRunner(active).generate(
                    messages = messages,
                    maxTokens = maxTokens.coerceIn(64, 8192),
                    temperature = temperature.toFloat().coerceIn(0f, 2f),
                    listener = listener
                )
                if (result.interrupted) {
                    throw CancellationException("PocketPal generation was interrupted.")
                }
                if (result.content.isNotBlank() && previousSnapshot != result.content) {
                    val delta = if (result.content.startsWith(previousSnapshot)) {
                        result.content.substring(previousSnapshot.length)
                    } else {
                        result.content
                    }
                    if (delta.isNotEmpty()) trySend(delta)
                }
                close()
            } catch (cancelled: CancellationException) {
                close(cancelled)
            } catch (error: Exception) {
                close(error)
            }
        }
        awaitClose {
            bridge?.cancel()
            worker.cancel()
        }
    }

    override fun close() {
        synchronized(lock) {
            bridge?.close()
            bridge = null
            loadedPath = null
        }
    }
}

package com.mikhaildezprofi.mobileaiagent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Gemini REST provider. API key is loaded from encrypted storage and never logged. */
class GeminiCloudProvider(
    private val keyStore: GeminiApiKeyStore,
    private val model: String = DEFAULT_MODEL
) : CloudModelProvider {
    override val id: String = "Google Gemini API"
    override val isAvailable: Boolean get() = keyStore.hasKey()

    override fun generate(prompt: String, maxTokens: Int): Flow<String> = flow {
        val key = keyStore.read()
            ?: throw IllegalStateException("Gemini API-ключ не настроен или не может быть расшифрован.")
        val answer = withContext(Dispatchers.IO) {
            request(prompt, maxTokens.coerceIn(64, 8192), key)
        }
        if (answer.isNotBlank()) emit(answer)
        else throw IllegalStateException("Gemini вернул пустой ответ.")
    }

    private fun request(prompt: String, maxTokens: Int, apiKey: String): String {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val body = JSONObject()
            .put("contents", org.json.JSONArray().put(
                JSONObject().put("role", "user").put(
                    "parts", org.json.JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching {
                    JSONObject(response).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                Log.w(TAG, "Gemini HTTP error code=$code")
                val detail = message.take(180)
                throw IllegalStateException(when (code) {
                    400 -> "Gemini отклонил запрос: проверьте модель и формат запроса."
                    401, 403 -> "Gemini не принял API-ключ или проект не имеет доступа к API."
                    429 -> "Исчерпана бесплатная квота Gemini или превышен лимит запросов."
                    else -> "Ошибка Gemini API (HTTP $code): $detail"
                })
            }
            val candidates = JSONObject(response).optJSONArray("candidates")
                ?: throw IllegalStateException("Gemini не вернул candidates.")
            val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini вернул ответ без текста.")
            return buildString {
                for (i in 0 until parts.length()) {
                    parts.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }?.let {
                        if (isNotEmpty()) append('\n')
                        append(it)
                    }
                }
            }
        } finally { connection?.disconnect() }
    }

    private companion object {
        const val TAG = "GeminiCloudProvider"
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
    }
}

package com.mikhaildezprofi.mobileaiagent

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads the official SmolVLM GGUF pair and verifies SHA-256 before use. */
object VisionModelAssets {
    const val MODEL_FILE = "SmolVLM-500M-Instruct-Q8_0.gguf"
    const val PROJECTOR_FILE = "mmproj-SmolVLM-500M-Instruct-Q8_0.gguf"

    private const val MODEL_SIZE = 436_806_912L
    private const val PROJECTOR_SIZE = 108_783_360L
    private const val MODEL_SHA256 = "9d4612de6a42214499e301494a3ecc2be0abdd9de44e663bda63f1152fad1bf4"
    private const val PROJECTOR_SHA256 = "d1eb8b6b23979205fdf63703ed10f788131a3f812c7b1f72e0119d5d81295150"
    private const val BASE_URL =
        "https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF/resolve/main/"

    data class Assets(val model: File, val projector: File)
    data class Progress(
        val fileName: String,
        val fileBytes: Long,
        val fileTotalBytes: Long,
        val completedBytes: Long,
        val totalBytes: Long
    ) {
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f
            else (completedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models"), MODEL_FILE)

    fun projectorFile(context: Context): File =
        File(File(context.filesDir, "models"), PROJECTOR_FILE)

    fun areAssetsPresent(context: Context): Boolean =
        modelFile(context).let { it.isFile && it.length() == MODEL_SIZE } &&
            projectorFile(context).let { it.isFile && it.length() == PROJECTOR_SIZE }

    suspend fun download(
        context: Context,
        onProgress: suspend (Progress) -> Unit
    ): Assets = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "models")
        check(directory.exists() || directory.mkdirs()) {
            "Не удалось создать внутреннюю папку моделей."
        }

        val model = modelFile(context)
        val projector = projectorFile(context)
        val total = MODEL_SIZE + PROJECTOR_SIZE
        val modelValid = isValid(model, MODEL_SIZE, MODEL_SHA256)
        val projectorValid = isValid(projector, PROJECTOR_SIZE, PROJECTOR_SHA256)
        val missing = (if (modelValid) 0L else MODEL_SIZE) +
            (if (projectorValid) 0L else PROJECTOR_SIZE)
        val available = StatFs(directory.absolutePath).availableBytes
        check(available >= missing + 64L * 1024L * 1024L) {
            "Недостаточно свободного места. Требуется ещё примерно " +
                "${(missing + 64L * 1024L * 1024L) / (1024L * 1024L)} МБ."
        }

        var completed = 0L
        if (!modelValid) {
            downloadOne(MODEL_FILE, MODEL_SIZE, MODEL_SHA256, model, completed, total, onProgress)
        }
        completed += MODEL_SIZE
        if (!projectorValid) {
            downloadOne(PROJECTOR_FILE, PROJECTOR_SIZE, PROJECTOR_SHA256,
                projector, completed, total, onProgress)
        }
        onProgress(Progress(PROJECTOR_FILE, PROJECTOR_SIZE, PROJECTOR_SIZE, total, total))
        Assets(model, projector)
    }

    private suspend fun downloadOne(
        name: String,
        expectedSize: Long,
        expectedSha256: String,
        destination: File,
        completedBefore: Long,
        totalBytes: Long,
        onProgress: suspend (Progress) -> Unit
    ) {
        val partial = File(destination.parentFile, "$name.partial")
        val connection = URL(BASE_URL + name + "?download=true")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "MOBILE-AI-AGENT/0.3.9")
        connection.setRequestProperty("Accept-Encoding", "identity")

        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Hugging Face вернул HTTP ${connection.responseCode} для $name."
            }
            val reportedLength = connection.contentLengthLong
            check(reportedLength < 0L || reportedLength == expectedSize) {
                "Размер $name не совпадает с ожидаемым: $reportedLength байт."
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastReport = 0L
            onProgress(Progress(name, 0L, expectedSize, completedBefore, totalBytes))
            connection.inputStream.buffered().use { input ->
                FileOutputStream(partial).buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        if (downloaded - lastReport >= 1024L * 1024L) {
                            lastReport = downloaded
                            onProgress(Progress(name, downloaded, expectedSize,
                                completedBefore + downloaded, totalBytes))
                        }
                    }
                    output.flush()
                }
            }

            check(downloaded == expectedSize && partial.length() == expectedSize) {
                "Загрузка $name неполная: получено $downloaded из $expectedSize байт."
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            check(actualHash.equals(expectedSha256, ignoreCase = true)) {
                "SHA-256 не совпал для $name. Файл не будет загружен в модель."
            }
            if (destination.exists()) {
                check(destination.delete()) { "Не удалось заменить старый файл $name." }
            }
            check(partial.renameTo(destination)) { "Не удалось сохранить проверенный файл $name." }
            onProgress(Progress(name, expectedSize, expectedSize,
                completedBefore + expectedSize, totalBytes))
        } catch (error: Exception) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun isValid(file: File, expectedSize: Long, expectedSha256: String): Boolean {
        if (!file.isFile || file.length() != expectedSize) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedSha256, ignoreCase = true)
    }
}

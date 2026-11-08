package com.mikhaildezprofi.mobileaiagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** A selected image. Exactly one of [uri] or [bitmap] must be supplied. */
data class ImageAttachmentSource(
    val displayName: String,
    val uri: Uri? = null,
    val bitmap: Bitmap? = null,
)

/** Owns temporary JPEGs and removes them when the inference request finishes. */
class StagedImageFiles internal constructor(val files: List<File>) : AutoCloseable {
    override fun close() {
        files.forEach { file -> runCatching { if (file.exists()) file.delete() } }
    }
}

/**
 * Converts SAF/camera images into bounded, app-private JPEG files for llama.cpp MTMD.
 * URI input is copied with a hard byte limit, dimensions are inspected before decoding,
 * and images are downscaled before reaching native code.
 */
object ImageAttachmentStager {
    private const val TAG = "ImageAttachmentStager"
    private const val MAX_IMAGES = 4
    private const val MAX_INPUT_BYTES = 25L * 1024L * 1024L
    private const val MAX_INPUT_PIXELS = 80_000_000L
    private const val MAX_EDGE = 1280
    private const val JPEG_QUALITY = 88

    suspend fun stage(context: Context, sources: List<ImageAttachmentSource>): StagedImageFiles =
        withContext(Dispatchers.IO) {
            require(sources.isNotEmpty()) { "Не выбраны изображения." }
            require(sources.size <= MAX_IMAGES) { "За один запрос можно обработать не более $MAX_IMAGES фото." }
            val outputDir = File(context.cacheDir, "vision-inputs")
            if (!outputDir.exists() && !outputDir.mkdirs()) throw IOException("Не удалось создать временную папку изображений.")
            val staged = mutableListOf<File>()
            val rawFiles = mutableListOf<File>()
            try {
                sources.forEachIndexed { index, source ->
                    require((source.uri != null) xor (source.bitmap != null)) {
                        "Вложение ${source.displayName} должно содержать URI или Bitmap."
                    }
                    val decoded = if (source.bitmap != null) {
                        val pixels = source.bitmap.width.toLong() * source.bitmap.height.toLong()
                        require(pixels <= MAX_INPUT_PIXELS) { "Изображение ${source.displayName} слишком большое." }
                        source.bitmap
                    } else {
                        val raw = File(outputDir, "${UUID.randomUUID()}-$index.input")
                        rawFiles += raw
                        copyUriBounded(context, source.uri!!, raw, source.displayName)
                        decodeFile(raw, source.displayName)
                    }
                    try {
                        val normalized = downscale(decoded)
                        try {
                            val target = File(outputDir, "${UUID.randomUUID()}-$index.jpg")
                            FileOutputStream(target).use { stream ->
                                if (!normalized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                                    throw IOException("Не удалось преобразовать ${source.displayName} в JPEG.")
                                }
                                stream.fd.sync()
                            }
                            if (target.length() <= 0L) throw IOException("Получен пустой JPEG: ${source.displayName}")
                            staged += target
                        } finally {
                            if (normalized !== decoded) normalized.recycle()
                        }
                    } finally {
                        if (source.bitmap == null) decoded.recycle()
                    }
                }
                Log.i(TAG, "Staged ${staged.size} image(s), total=${staged.sumOf { it.length() }} bytes")
                StagedImageFiles(staged.toList())
            } catch (error: Exception) {
                staged.forEach { runCatching { it.delete() } }
                throw error
            } finally {
                rawFiles.forEach { runCatching { it.delete() } }
            }
        }

    private fun copyUriBounded(context: Context, uri: Uri, target: File, name: String) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть изображение: $name")
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_INPUT_BYTES) throw IOException("Файл $name превышает лимит 25 МБ.")
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
                if (total == 0L) throw IOException("Файл $name пуст.")
            }
        }
    }

    private fun decodeFile(file: File, name: String): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Файл не является поддерживаемым изображением: $name" }
        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        require(pixels <= MAX_INPUT_PIXELS) { "Изображение $name слишком большое (${bounds.outWidth}×${bounds.outHeight})." }
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_EDGE * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IOException("Не удалось декодировать изображение: $name")
    }

    private fun downscale(source: Bitmap): Bitmap {
        val edge = maxOf(source.width, source.height)
        if (edge <= MAX_EDGE && source.config == Bitmap.Config.ARGB_8888) return source
        val ratio = minOf(1f, MAX_EDGE.toFloat() / edge.toFloat())
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        return if (scaled.config == Bitmap.Config.ARGB_8888) scaled else scaled.copy(Bitmap.Config.ARGB_8888, false)
    }
}

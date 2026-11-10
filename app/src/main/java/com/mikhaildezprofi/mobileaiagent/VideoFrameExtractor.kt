package com.mikhaildezprofi.mobileaiagent

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.IOException

/** Extracts a small, evenly spaced set of bounded frames from a selected video. */
object VideoFrameExtractor {
    private const val MAX_EDGE = 1280
    private const val MAX_FRAMES = 4

    fun extractFrames(
        context: Context,
        uri: Uri,
        displayName: String,
        requestedFrames: Int = MAX_FRAMES,
    ): List<ImageAttachmentSource> {
        val frameLimit = requestedFrames.coerceIn(1, MAX_FRAMES)
        val retriever = MediaMetadataRetriever()
        val result = mutableListOf<ImageAttachmentSource>()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: throw IOException("Не удалось определить длительность видео: $displayName")
            if (durationMs <= 0L) throw IOException("Видео пустое или не поддерживается: $displayName")

            // Avoid the very first/last frame, which is often a transition or black frame.
            for (index in 0 until frameLimit) {
                val fraction = (index + 1).toDouble() / (frameLimit + 1).toDouble()
                val timeUs = (durationMs * 1000.0 * fraction).toLong()
                val original = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                val bounded = try {
                    boundFrame(original)
                } finally {
                    if (!original.isRecycled) original.recycle()
                }
                result += ImageAttachmentSource(
                    displayName = "$displayName, кадр ${index + 1}",
                    bitmap = bounded,
                )
            }
            if (result.isEmpty()) throw IOException("Не удалось извлечь кадры из видео: $displayName")
            return result
        } catch (error: Exception) {
            result.forEach { source -> source.bitmap?.let { if (!it.isRecycled) it.recycle() } }
            throw error
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun boundFrame(source: Bitmap): Bitmap {
        val edge = maxOf(source.width, source.height)
        val ratio = minOf(1f, MAX_EDGE.toFloat() / edge.toFloat())
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(source, width, height, true)
        return try {
            resized.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IOException("Не удалось подготовить кадр видео.")
        } finally {
            if (resized !== source && !resized.isRecycled) resized.recycle()
        }
    }
}

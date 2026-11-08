package com.arm.aichat

import kotlinx.coroutines.flow.Flow

/** Optional multimodal capability implemented by engines with a loaded vision projector. */
interface VisionInferenceEngine {
    /** True only while a compatible mmproj is loaded for the active text model. */
    suspend fun isVisionReady(): Boolean

    /** Load an mmproj GGUF compatible with the currently loaded text model. */
    suspend fun loadVisionProjector(pathToMmproj: String)

    /** Process one or more local image files together with a text prompt. */
    fun sendUserPromptWithImages(
        message: String,
        imagePaths: List<String>,
        predictLength: Int = InferenceEngine.DEFAULT_PREDICT_LENGTH,
    ): Flow<String>
}

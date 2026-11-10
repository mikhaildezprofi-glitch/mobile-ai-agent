from pathlib import Path

root = Path("third_party/llama.cpp/examples/llama.android/lib")
cpp = root / "src/main/cpp/ai_chat.cpp"
header = root / "src/main/cpp/logging.h"
native_cmake = root / "src/main/cpp/CMakeLists.txt"
kt = root / "src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt"
android_gradle = root / "build.gradle.kts"
vision_interface = root / "src/main/java/com/arm/aichat/VisionInferenceEngine.kt"

def replace_once(path, old, new):
    content = path.read_text()
    count = content.count(old)
    if count != 1:
        raise SystemExit(f"ERROR: {path}: expected exactly one match, found {count}")
    path.write_text(content.replace(old, new, 1))

# Capture llama.cpp diagnostics for Kotlin exceptions.
replace_once(
    header,
    "#pragma once\n#include <android/log.h>",
    "#pragma once\n#include <android/log.h>\n#include <string>\n#include <mutex>\n\nstatic std::string aichat_last_error;\nstatic std::mutex aichat_error_mutex;"
)

replace_once(
    header,
    "    const int prio = android_log_prio_from_ggml(level);\n    if (!ai_should_log(prio)) return;\n    __android_log_write(prio, LOG_TAG, text);",
    """    const int prio = android_log_prio_from_ggml(level);
    {
        std::lock_guard<std::mutex> lock(aichat_error_mutex);
        if (text != nullptr) {
            aichat_last_error += text;
            if (aichat_last_error.size() > 12000) {
                aichat_last_error.erase(0, aichat_last_error.size() - 12000);
            }
        }
    }
    if (!ai_should_log(prio)) return;
    __android_log_write(prio, LOG_TAG, text);"""
)

replace_once(
    cpp,
    '#include "llama.h"',
    '#include "llama.h"\n#include "mtmd.h"\n#include "mtmd-helper.h"'
)

replace_once(
    cpp,
    "static common_sampler                   * g_sampler;",
    "static common_sampler                   * g_sampler;\nstatic mtmd_context                       * g_mtmd = nullptr;"
)

replace_once(
    cpp,
    "    llama_model_params model_params = llama_model_default_params();\n\n    const auto *model_path",
    """    {
        std::lock_guard<std::mutex> lock(aichat_error_mutex);
        aichat_last_error.clear();
    }
    llama_model_params model_params = llama_model_default_params();

    const auto *model_path"""
)

replace_once(
    cpp,
    """    g_model = model;
    return 0;
}

static llama_context *init_context""",
    """    g_model = model;
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_lastError(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(aichat_error_mutex);
    return env->NewStringUTF(aichat_last_error.c_str());
}

static llama_context *init_context"""
)

replace_once(
    cpp,
    "    stop_generation_position = current_position + user_prompt_size + n_predict;",
    "    stop_generation_position = current_position + n_predict;"
)

vision_native = r'''
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_loadVisionProjectorNative(
        JNIEnv *env, jobject /*unused*/, jstring jprojector_path) {
    if (g_model == nullptr || g_context == nullptr) {
        LOGe("%s: text model is not loaded", __func__);
        return 1;
    }

    const char *projector_path = env->GetStringUTFChars(jprojector_path, nullptr);
    if (projector_path == nullptr) {
        return 2;
    }

    {
        std::lock_guard<std::mutex> lock(aichat_error_mutex);
        aichat_last_error.clear();
    }
    mtmd_helper_log_set(aichat_android_log_callback, nullptr);
    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = false;
    params.print_timings = false;
    params.warmup = false;
    params.n_threads = std::max(N_THREADS_MIN, std::min(
            N_THREADS_MAX, (int) sysconf(_SC_NPROCESSORS_ONLN) - N_THREADS_HEADROOM));

    const int n_ubatch = (int) llama_n_ubatch(g_context);
    if (n_ubatch > 0 && (params.image_max_tokens <= 0 || params.image_max_tokens > n_ubatch)) {
        params.image_max_tokens = n_ubatch;
        params.image_min_tokens = std::min(params.image_min_tokens, n_ubatch);
    }

    mtmd_context *vision = mtmd_init_from_file(projector_path, g_model, params);
    env->ReleaseStringUTFChars(jprojector_path, projector_path);
    if (vision == nullptr) {
        LOGe("%s: failed to load projector", __func__);
        return 3;
    }
    if (!mtmd_support_vision(vision)) {
        LOGe("%s: projector does not provide vision input", __func__);
        mtmd_free(vision);
        return 4;
    }

    if (g_mtmd != nullptr) {
        mtmd_free(g_mtmd);
    }
    g_mtmd = vision;
    LOGi("%s: vision projector loaded", __func__);
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPromptWithImagesNative(
        JNIEnv *env, jobject /*unused*/, jstring juser_prompt,
        jobjectArray jimage_paths, jint n_predict) {
    if (g_mtmd == nullptr || g_context == nullptr || g_model == nullptr) {
        LOGe("%s: vision backend is not initialized", __func__);
        return 1;
    }

    const jsize image_count = env->GetArrayLength(jimage_paths);
    if (image_count <= 0) {
        return 2;
    }

    const char *raw_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    if (raw_prompt == nullptr) {
        return 3;
    }
    std::string user_prompt(raw_prompt);
    env->ReleaseStringUTFChars(juser_prompt, raw_prompt);
    if (user_prompt.empty()) {
        user_prompt = "Опиши приложенные изображения.";
    }

    mtmd::bitmaps bitmaps;
    std::vector<const mtmd_bitmap *> bitmap_ptrs;
    bitmap_ptrs.reserve((size_t) image_count);
    const std::string marker = mtmd_default_marker();

    for (jsize i = 0; i < image_count; ++i) {
        jstring jpath = (jstring) env->GetObjectArrayElement(jimage_paths, i);
        if (jpath == nullptr) {
            return 4;
        }
        const char *path = env->GetStringUTFChars(jpath, nullptr);
        if (path == nullptr) {
            env->DeleteLocalRef(jpath);
            return 5;
        }

        auto wrapper = mtmd_helper_bitmap_init_from_file(
                g_mtmd, path, false, mtmd_helper_init_opt_default());
        env->ReleaseStringUTFChars(jpath, path);
        env->DeleteLocalRef(jpath);

        if (wrapper.video_ctx != nullptr) {
            mtmd_helper_video_free(wrapper.video_ctx);
        }
        if (wrapper.bitmap == nullptr) {
            LOGe("%s: failed to decode image %d", __func__, (int) i);
            return 6;
        }

        bitmaps.entries.emplace_back(wrapper.bitmap);
        bitmap_ptrs.push_back(wrapper.bitmap);
        user_prompt += "\n";
        user_prompt += marker;
    }

    reset_short_term_states();
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    std::string formatted_user_prompt(user_prompt);
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }

    mtmd_input_text text = {
        formatted_user_prompt.data(),
        formatted_user_prompt.size(),
        has_chat_template,
        has_chat_template
    };
    mtmd::input_chunks chunks(mtmd_input_chunks_init());
    if (!chunks.ptr) {
        return 7;
    }

    int32_t result = mtmd_tokenize(
            g_mtmd, chunks.ptr.get(), &text, bitmap_ptrs.data(), bitmap_ptrs.size());
    if (result != 0) {
        LOGe("%s: mtmd_tokenize failed: %d", __func__, result);
        return 8;
    }

    llama_pos new_n_past = current_position;
    result = mtmd_helper_eval_chunks(
            g_mtmd, g_context, chunks.ptr.get(), current_position,
            0, BATCH_SIZE, true, &new_n_past);
    if (result != 0) {
        LOGe("%s: mtmd_helper_eval_chunks failed: %d", __func__, result);
        return 9;
    }

    current_position = new_n_past;
    stop_generation_position = current_position + std::max(1, (int) n_predict);
    LOGi("%s: processed %d image(s), current_position=%d",
         __func__, (int) image_count, (int) current_position);
    return 0;
}
'''

replace_once(
    cpp,
    "static bool is_valid_utf8(const char *string) {",
    vision_native + "\nstatic bool is_valid_utf8(const char *string) {"
)

replace_once(
    cpp,
    """    // Free up resources
    common_sampler_free(g_sampler);""",
    """    // Free multimodal resources before unloading the text model.
    if (g_mtmd != nullptr) {
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
    }

    // Free up resources
    common_sampler_free(g_sampler);"""
)

replace_once(
    cpp,
    """Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}""",
    """Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    if (g_mtmd != nullptr) {
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
    }
    llama_backend_free();
}"""
)

# The Android sample hard-coded OpenMP ON for arm64. Force it OFF to avoid unresolved __kmpc_* symbols.
replace_once(native_cmake, "        set(GGML_OPENMP ON)", "        set(GGML_OPENMP OFF)")

old_include = (
    "target_include_directories(" + "$" + "{CMAKE_PROJECT_NAME} PRIVATE\n"
    "        " + "$" + "{LLAMA_SRC}\n"
    "        " + "$" + "{LLAMA_SRC}/common\n"
    "        " + "$" + "{LLAMA_SRC}/include\n"
    "        " + "$" + "{LLAMA_SRC}/ggml/include\n"
    "        " + "$" + "{LLAMA_SRC}/ggml/src)"
)
new_include = (
    "target_include_directories(" + "$" + "{CMAKE_PROJECT_NAME} PRIVATE\n"
    "        " + "$" + "{LLAMA_SRC}\n"
    "        " + "$" + "{LLAMA_SRC}/common\n"
    "        " + "$" + "{LLAMA_SRC}/include\n"
    "        " + "$" + "{LLAMA_SRC}/ggml/include\n"
    "        " + "$" + "{LLAMA_SRC}/ggml/src\n"
    "        " + "$" + "{LLAMA_SRC}/tools/mtmd)"
)
replace_once(native_cmake, old_include, new_include)

old_link = (
    "target_link_libraries(" + "$" + "{CMAKE_PROJECT_NAME}\n"
    "        llama\n"
    "        llama-common\n"
    "        android\n"
    "        log)"
)
new_link = (
    "target_link_libraries(" + "$" + "{CMAKE_PROJECT_NAME}\n"
    "        llama\n"
    "        llama-common\n"
    "        mtmd\n"
    "        android\n"
    "        log)"
)
replace_once(native_cmake, old_link, new_link)

replace_once(
    android_gradle,
    'arguments += "-DCMAKE_BUILD_TYPE=Release"',
    'arguments += "-DCMAKE_BUILD_TYPE=Release"\n                arguments += "-DGGML_OPENMP=OFF"\n                arguments += "-DLLAMA_BUILD_MTMD=ON"'
)

vision_interface.write_text("""package com.arm.aichat

import kotlinx.coroutines.flow.Flow

/**
 * Optional multimodal extension implemented by llama.cpp's libmtmd backend.
 */
interface VisionInferenceEngine {
    fun isVisionReady(): Boolean
    fun loadVisionProjector(projectorPath: String)
    fun sendUserPromptWithImages(
        message: String,
        imagePaths: List<String>,
        predictLength: Int
    ): Flow<String>
}
""", encoding="utf-8")

replace_once(
    kt,
    "import com.arm.aichat.InferenceEngine",
    "import com.arm.aichat.InferenceEngine\nimport com.arm.aichat.VisionInferenceEngine"
)

replace_once(kt, ") : InferenceEngine {", ") : InferenceEngine, VisionInferenceEngine {")

replace_once(
    kt,
    "    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int",
    """    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int

    private external fun lastError(): String

    private external fun loadVisionProjectorNative(projectorPath: String): Int

    private external fun processUserPromptWithImagesNative(
        userPrompt: String,
        imagePaths: Array<String>,
        predictLength: Int
    ): Int"""
)

replace_once(
    kt,
    """    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    private var _readyForSystemPrompt = false""",
    """    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    override fun isVisionReady(): Boolean = _visionReady

    override fun loadVisionProjector(projectorPath: String) {
        runBlocking(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot load vision projector in " + _state.value.javaClass.simpleName
            }
            File(projectorPath).let {
                require(it.exists() && it.isFile && it.canRead()) {
                    "Vision projector file is missing or unreadable: " + projectorPath
                }
            }
            _visionReady = false
            val result = loadVisionProjectorNative(projectorPath)
            if (result != 0) {
                val details = lastError().takeLast(8000)
                throw IOException("Failed to load vision projector (code " + result + "). " + details)
            }
            _visionReady = true
        }
    }

    private var _readyForSystemPrompt = false"""
)

replace_once(
    kt,
    """    private var _readyForSystemPrompt = false
    @Volatile
    private var _cancelGeneration = false""",
    """    private var _readyForSystemPrompt = false
    @Volatile
    private var _visionReady = false
    @Volatile
    private var _cancelGeneration = false"""
)

replace_once(
    kt,
    """                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.LoadingModel""",
    """                _readyForSystemPrompt = false
                _visionReady = false
                _state.value = InferenceEngine.State.LoadingModel"""
)

replace_once(
    kt,
    """            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library", e)
                throw e
            }""",
    """            } catch (e: LinkageError) {
                val wrapped = RuntimeException("Native library initialization failed: " + e.message, e)
                _state.value = InferenceEngine.State.Error(wrapped)
                Log.e(TAG, "Failed to load native library", e)
            } catch (e: Exception) {
                _state.value = InferenceEngine.State.Error(e)
                Log.e(TAG, "Failed to load native library", e)
            }"""
)

vision_flow = """
    override fun sendUserPromptWithImages(
        message: String,
        imagePaths: List<String>,
        predictLength: Int,
    ): Flow<String> = flow {
        require(imagePaths.isNotEmpty()) { "At least one image is required." }
        check(_visionReady) { "Vision projector is not loaded." }
        check(_state.value is InferenceEngine.State.ModelReady) {
            "Image prompt discarded due to: " + _state.value.javaClass.simpleName
        }

        try {
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingUserPrompt
            val prompt = if (message.isBlank()) "Опиши приложенные изображения." else message
            val result = processUserPromptWithImagesNative(prompt, imagePaths.toTypedArray(), predictLength)
            if (result != 0) {
                val details = lastError().takeLast(8000)
                throw IOException("Multimodal prompt failed (code " + result + "). " + details)
            }

            _state.value = InferenceEngine.State.Generating
            while (!_cancelGeneration) {
                generateNextToken()?.let { token ->
                    if (token.isNotEmpty()) emit(token)
                } ?: break
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

"""

replace_once(
    kt,
    """    /**
     * Benchmark the model
     */""",
    vision_flow + """    /**
     * Benchmark the model
     */"""
)

replace_once(
    kt,
    """                    unload()

                    _state.value = InferenceEngine.State.Initialized""",
    """                    unload()
                    _visionReady = false

                    _state.value = InferenceEngine.State.Initialized"""
)

replace_once(
    kt,
    """                is InferenceEngine.State.Error -> {
                    Log.i(TAG, "Resetting error states...")""",
    """                is InferenceEngine.State.Error -> {
                    _visionReady = false
                    Log.i(TAG, "Resetting error states...")"""
)

replace_once(
    kt,
    """    override fun destroy() {
        _cancelGeneration = true""",
    """    override fun destroy() {
        _cancelGeneration = true
        _visionReady = false"""
)

print("Patched llama.cpp: diagnostics, Android-safe OpenMP, libmtmd vision backend, and native-init fallback.")

#include <jni.h>
#include <android/log.h>

#include "rn-llama.h"
#include "rn-completion.h"
#include "chat.h"
#include "common.h"
#include "sampling.h"
#include "nlohmann/json.hpp"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <stdexcept>
#include <vector>

using json = nlohmann::ordered_json;

namespace {

constexpr const char * TAG = "JimmyPocketPal";
std::once_flag backend_once;

struct NativeContext {
    std::mutex mutex;
    std::atomic<bool> cancelled{false};
    std::unique_ptr<rnllama::llama_rn_context> context;
};

std::vector<std::string> normalizeMultimodalMessages(json & messages) {
    std::vector<std::string> mediaPaths;
    if (!messages.is_array()) {
        throw std::runtime_error("Chat messages must be a JSON array.");
    }

    for (auto & message : messages) {
        if (!message.is_object()) continue;
        auto content = message.find("content");
        if (content == message.end() || !content->is_array()) continue;

        json normalized = json::array();
        for (const auto & part : *content) {
            if (!part.is_object()) {
                normalized.push_back(part);
                continue;
            }

            const std::string type = part.value("type", std::string());
            if (type == "image_url") {
                const auto image = part.find("image_url");
                const std::string mediaPath = image != part.end() && image->is_object()
                    ? image->value("url", std::string())
                    : std::string();
                if (mediaPath.empty()) {
                    throw std::runtime_error("Image attachment is missing image_url.url.");
                }
                std::string localPath = mediaPath;
                if (localPath.compare(0, 7, "file://") == 0) localPath.erase(0, 7);
                mediaPaths.push_back(std::move(localPath));
                normalized.push_back(json::object({
                    {"type", "text"},
                    {"text", "<__media__>"}
                }));
            } else if (type == "input_audio") {
                const auto audio = part.find("input_audio");
                if (audio == part.end() || !audio->is_object()) {
                    throw std::runtime_error("Audio attachment is missing input_audio.");
                }
                std::string mediaPath = audio->value("url", std::string());
                if (!mediaPath.empty()) {
                    if (mediaPath.compare(0, 7, "file://") == 0) mediaPath.erase(0, 7);
                } else {
                    mediaPath = audio->value("data", std::string());
                }
                if (mediaPath.empty()) {
                    throw std::runtime_error("Audio attachment requires a local url or data URL.");
                }
                mediaPaths.push_back(std::move(mediaPath));
                normalized.push_back(json::object({
                    {"type", "text"},
                    {"text", "<__media__>"}
                }));
            } else {
                normalized.push_back(part);
            }
        }
        message["content"] = std::move(normalized);
    }

    return mediaPaths;
}

std::string fromJavaString(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    std::string output;
    output.reserve(static_cast<size_t>(length) * 3);

    for (jsize index = 0; index < length; ++index) {
        uint32_t codePoint = chars[index];
        if (codePoint >= 0xd800 && codePoint <= 0xdbff &&
            index + 1 < length && chars[index + 1] >= 0xdc00 && chars[index + 1] <= 0xdfff) {
            codePoint = 0x10000 + ((codePoint - 0xd800) << 10) +
                (static_cast<uint32_t>(chars[++index]) - 0xdc00);
        } else if (codePoint >= 0xd800 && codePoint <= 0xdfff) {
            codePoint = 0xfffd;
        }

        if (codePoint <= 0x7f) {
            output.push_back(static_cast<char>(codePoint));
        } else if (codePoint <= 0x7ff) {
            output.push_back(static_cast<char>(0xc0 | (codePoint >> 6)));
            output.push_back(static_cast<char>(0x80 | (codePoint & 0x3f)));
        } else if (codePoint <= 0xffff) {
            output.push_back(static_cast<char>(0xe0 | (codePoint >> 12)));
            output.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3f)));
            output.push_back(static_cast<char>(0x80 | (codePoint & 0x3f)));
        } else {
            output.push_back(static_cast<char>(0xf0 | (codePoint >> 18)));
            output.push_back(static_cast<char>(0x80 | ((codePoint >> 12) & 0x3f)));
            output.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3f)));
            output.push_back(static_cast<char>(0x80 | (codePoint & 0x3f)));
        }
    }

    env->ReleaseStringChars(value, chars);
    return output;
}

std::u16string utf8ToUtf16(const std::string & value) {
    std::u16string output;
    output.reserve(value.size());

    for (size_t index = 0; index < value.size();) {
        const auto lead = static_cast<uint8_t>(value[index]);
        uint32_t codePoint = 0;
        size_t length = 0;

        if (lead <= 0x7f) {
            codePoint = lead;
            length = 1;
        } else if ((lead & 0xe0) == 0xc0) {
            codePoint = lead & 0x1f;
            length = 2;
        } else if ((lead & 0xf0) == 0xe0) {
            codePoint = lead & 0x0f;
            length = 3;
        } else if ((lead & 0xf8) == 0xf0) {
            codePoint = lead & 0x07;
            length = 4;
        } else {
            output.push_back(u'\ufffd');
            ++index;
            continue;
        }

        bool valid = index + length <= value.size();
        for (size_t offset = 1; valid && offset < length; ++offset) {
            const auto continuation = static_cast<uint8_t>(value[index + offset]);
            if ((continuation & 0xc0) != 0x80) {
                valid = false;
                break;
            }
            codePoint = (codePoint << 6) | (continuation & 0x3f);
        }

        const bool overlong =
            (length == 2 && codePoint < 0x80) ||
            (length == 3 && codePoint < 0x800) ||
            (length == 4 && codePoint < 0x10000);
        if (!valid || overlong || codePoint > 0x10ffff ||
            (codePoint >= 0xd800 && codePoint <= 0xdfff)) {
            output.push_back(u'\ufffd');
            ++index;
            continue;
        }

        if (codePoint <= 0xffff) {
            output.push_back(static_cast<char16_t>(codePoint));
        } else {
            codePoint -= 0x10000;
            output.push_back(static_cast<char16_t>(0xd800 + (codePoint >> 10)));
            output.push_back(static_cast<char16_t>(0xdc00 + (codePoint & 0x3ff)));
        }
        index += length;
    }

    return output;
}

jstring toJavaString(JNIEnv * env, const std::string & value) {
    const std::u16string utf16 = utf8ToUtf16(value);
    return env->NewString(
        reinterpret_cast<const jchar *>(utf16.data()),
        static_cast<jsize>(utf16.size()));
}

void throwIllegalState(JNIEnv * env, const std::string & message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
        env->DeleteLocalRef(exceptionClass);
    }
}

NativeContext * getContext(jlong handle) {
    return reinterpret_cast<NativeContext *>(handle);
}

void notifySnapshot(
    JNIEnv * env,
    jobject callback,
    jmethodID method,
    const std::string & content,
    const std::string & reasoning) {
    if (callback == nullptr || method == nullptr) return;
    jstring contentValue = toJavaString(env, content);
    jstring reasoningValue = toJavaString(env, reasoning);
    env->CallVoidMethod(callback, method, contentValue, reasoningValue);
    env->DeleteLocalRef(contentValue);
    env->DeleteLocalRef(reasoningValue);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_mikhaildezprofi_mobileaiagent_PocketPalNativeBridge_nativeCreateContext(
    JNIEnv * /*env*/, jobject /*thiz*/) {
    try {
        return reinterpret_cast<jlong>(new NativeContext());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mikhaildezprofi_mobileaiagent_PocketPalNativeBridge_nativeLoadModel(
    JNIEnv * env,
    jobject /*thiz*/,
    jlong handle,
    jstring modelPath,
    jint contextSize,
    jint threadCount) {
    NativeContext * native = getContext(handle);
    if (native == nullptr) {
        throwIllegalState(env, "PocketPal native context is not initialized.");
        return JNI_FALSE;
    }

    const std::string path = fromJavaString(env, modelPath);
    if (path.empty()) {
        throwIllegalState(env, "Model path is empty.");
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(native->mutex);
    native->cancelled.store(false);

    try {
        std::call_once(backend_once, [] {
            llama_backend_init();
            rnllama::install_ggml_abort_handler();
        });

        native->context.reset();
        auto context = std::make_unique<rnllama::llama_rn_context>();

        common_params params;
        params.model.path = path;
        params.n_ctx = std::clamp(static_cast<int>(contextSize), 512, 32768);
        params.n_batch = 256;
        params.n_ubatch = 128;
        params.n_parallel = 1;
        params.n_gpu_layers = 0;
        params.fit_params = false;
        params.ctx_shift = true;
        const int threads = std::clamp(static_cast<int>(threadCount), 1, 8);
        params.cpuparams.n_threads = threads;
        params.cpuparams_batch.n_threads = threads;
        params.sampling.temp = 0.7f;
        params.n_predict = 512;

        if (!context->loadModel(params)) {
            throw std::runtime_error("PocketPal llama.rn could not load the GGUF model.");
        }
        context->attachThreadpoolsIfAvailable();
        native->context = std::move(context);
        __android_log_print(ANDROID_LOG_INFO, TAG, "PocketPal model loaded: %s", path.c_str());
        return JNI_TRUE;
    } catch (const std::exception & error) {
        native->context.reset();
        throwIllegalState(env, error.what());
        return JNI_FALSE;
    } catch (...) {
        native->context.reset();
        throwIllegalState(env, "Unknown error while loading PocketPal model.");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mikhaildezprofi_mobileaiagent_PocketPalNativeBridge_nativeGenerate(
    JNIEnv * env,
    jobject /*thiz*/,
    jlong handle,
    jstring messagesJson,
    jstring toolsJson,
    jint maxTokens,
    jfloat temperature,
    jobject callback) {
    NativeContext * native = getContext(handle);
    if (native == nullptr) {
        throwIllegalState(env, "PocketPal native context is not initialized.");
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(native->mutex);
    if (!native->context || native->context->completion == nullptr) {
        throwIllegalState(env, "PocketPal model is not loaded.");
        return nullptr;
    }

    native->cancelled.store(false);
    auto * context = native->context.get();
    auto * completion = context->completion;

    jclass callbackClass = callback != nullptr ? env->GetObjectClass(callback) : nullptr;
    jmethodID snapshotMethod = callbackClass != nullptr
        ? env->GetMethodID(callbackClass, "onSnapshot", "(Ljava/lang/String;Ljava/lang/String;)V")
        : nullptr;
    if (callbackClass != nullptr) env->DeleteLocalRef(callbackClass);
    if (callback != nullptr && snapshotMethod == nullptr) {
        throwIllegalState(env, "PocketPal callback does not implement onSnapshot(String, String).");
        return nullptr;
    }

    try {
        std::string messages = fromJavaString(env, messagesJson);
        const std::string tools = fromJavaString(env, toolsJson);
        if (messages.empty()) {
            throw std::invalid_argument("Chat messages JSON is empty.");
        }
        json parsedMessages = json::parse(messages);
        const std::vector<std::string> mediaPaths = normalizeMultimodalMessages(parsedMessages);
        messages = parsedMessages.dump();
        if (!mediaPaths.empty() && !context->isMultimodalEnabled()) {
            throw std::runtime_error("Image/audio input requires a loaded PocketPal multimodal projector.");
        }
        if (!tools.empty()) {
            const json parsedTools = json::parse(tools);
            if (!parsedTools.is_array()) {
                throw std::invalid_argument("Tool definitions must be a JSON array.");
            }
        }

        common_chat_params chat = context->getFormattedChatWithJinja(
            messages,
            "",
            "",
            tools,
            false,
            tools.empty() ? "" : "auto",
            true,
            "none",
            true);

        // rewind() clears the previous completion's sampler state and stop rules.
        // Apply this request's Jinja-derived grammar only after rewind().
        completion->rewind();
        context->params.prompt = chat.prompt;
        context->params.n_predict = std::clamp(static_cast<int>(maxTokens), 1, 8192);
        context->params.sampling.temp = std::clamp(static_cast<float>(temperature), 0.0f, 2.0f);
        context->params.sampling.grammar = chat.grammar.empty()
            ? common_grammar()
            : common_grammar(COMMON_GRAMMAR_TYPE_TOOL_CALLS, chat.grammar);
        context->params.sampling.grammar_lazy = chat.grammar_lazy;
        context->params.sampling.grammar_triggers = chat.grammar_triggers;
        context->params.sampling.generation_prompt = chat.generation_prompt;
        context->params.antiprompt = chat.additional_stops;

        if (!completion->initSampling()) {
            throw std::runtime_error("PocketPal sampler initialization failed.");
        }

        completion->prefill_text.clear();
        completion->beginCompletion(
            static_cast<int>(chat.format),
            COMMON_REASONING_FORMAT_NONE,
            chat.generation_prompt,
            chat.parser);

        bool completionStarted = true;
        std::string lastContent;
        std::string lastReasoning;
        try {
            completion->loadPrompt(mediaPaths);
            while (completion->has_next_token && !completion->is_interrupted) {
                if (native->cancelled.load()) {
                    completion->is_interrupted = true;
                    break;
                }

                const auto token = completion->doCompletion();
                if (token.tok == -1 || completion->incomplete) continue;

                const auto partial = completion->parseChatOutput(true);
                if (partial.content != lastContent || partial.reasoning_content != lastReasoning) {
                    lastContent = partial.content;
                    lastReasoning = partial.reasoning_content;
                    notifySnapshot(env, callback, snapshotMethod, lastContent, lastReasoning);
                    if (env->ExceptionCheck()) {
                        native->cancelled.store(true);
                        completion->is_interrupted = true;
                        break;
                    }
                }
            }

            common_perf_print(context->ctx, completion->ctx_sampling);
            completion->endCompletion();
            completionStarted = false;
        } catch (...) {
            if (completionStarted && completion->is_predicting) {
                completion->endCompletion();
            }
            throw;
        }

        const auto finalOutput = completion->parseChatOutput(false);
        json toolCalls = json::array();
        for (const auto & call : finalOutput.tool_calls) {
            toolCalls.push_back(json::object({
                {"id", call.id},
                {"name", call.name},
                {"arguments", call.arguments}
            }));
        }

        json result = json::object({
            {"content", finalOutput.content},
            {"reasoning_content", finalOutput.reasoning_content},
            {"tool_calls", toolCalls},
            {"interrupted", native->cancelled.load() || completion->is_interrupted}
        });
        return toJavaString(env, result.dump());
    } catch (const std::exception & error) {
        throwIllegalState(env, error.what());
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "Unknown error during PocketPal generation.");
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mikhaildezprofi_mobileaiagent_PocketPalNativeBridge_nativeLoadVisionProjector(
    JNIEnv * env, jobject /*thiz*/, jlong handle, jstring projectorPath) {
    NativeContext * native = getContext(handle);
    if (native == nullptr) {
        throwIllegalState(env, "PocketPal native context is not initialized.");
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(native->mutex);
    if (!native->context || native->context->model == nullptr || native->context->ctx == nullptr) {
        throwIllegalState(env, "Load a PocketPal model before its multimodal projector.");
        return JNI_FALSE;
    }

    try {
        const std::string path = fromJavaString(env, projectorPath);
        if (path.empty()) throw std::runtime_error("Multimodal projector path is empty.");
        native->context->releaseMultimodal();
        if (!native->context->initMultimodal(path, false)) {
            throw std::runtime_error("PocketPal could not initialize the multimodal projector.");
        }
        if (!native->context->isMultimodalSupportVision()) {
            native->context->releaseMultimodal();
            throw std::runtime_error("Loaded projector does not expose vision support.");
        }
        __android_log_print(ANDROID_LOG_INFO, TAG, "PocketPal vision projector loaded: %s", path.c_str());
        return JNI_TRUE;
    } catch (const std::exception & error) {
        throwIllegalState(env, error.what());
        return JNI_FALSE;
    } catch (...) {
        throwIllegalState(env, "Unknown error while loading PocketPal vision projector.");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mikhaildezprofi_mobileaiagent_PocketPalNativeBridge_nativeReleaseVisionProjector(
    JNIEnv * env, jobject /*thiz*/, jlong handle) {
    NativeContext * native = getContext(handle);
    if (native == nullptr) return;
    std::lock_guard<std::mutex> lock(native->mutex);
    if (native->context) native->context->releaseMultimodal();
}

extern "C" JNIEXPORT void JNICALL
Java_com_mikhaildezprofi_mobileaiagent_PocketPalNativeBridge_nativeCancel(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    NativeContext * native = getContext(handle);
    if (native != nullptr) {
        native->cancelled.store(true);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mikhaildezprofi_mobileaiagent_PocketPalNativeBridge_nativeRelease(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    NativeContext * native = getContext(handle);
    if (native == nullptr) return;
    native->cancelled.store(true);
    {
        std::lock_guard<std::mutex> lock(native->mutex);
        native->context.reset();
    }
    delete native;
}

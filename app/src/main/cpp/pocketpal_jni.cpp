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

std::string fromJavaString(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
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
        const std::string messages = fromJavaString(env, messagesJson);
        const std::string tools = fromJavaString(env, toolsJson);
        if (messages.empty()) {
            throw std::invalid_argument("Chat messages JSON is empty.");
        }
        const json parsedMessages = json::parse(messages);
        if (!parsedMessages.is_array()) {
            throw std::invalid_argument("Chat messages must be a JSON array.");
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
            completion->loadPrompt({});
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

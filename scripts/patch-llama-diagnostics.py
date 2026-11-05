from pathlib import Path

root = Path("third_party/llama.cpp/examples/llama.android/lib")
cpp = root / "src/main/cpp/ai_chat.cpp"
header = root / "src/main/cpp/logging.h"
kt = root / "src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt"

def replace_once(path, old, new):
    content = path.read_text()
    count = content.count(old)
    if count != 1:
        raise SystemExit(f"ОШИБКА: {path}: ожидалось одно совпадение, найдено {count}")
    path.write_text(content.replace(old, new, 1))

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
    kt,
    "    private external fun load(modelPath: String): Int",
    """    private external fun load(modelPath: String): Int

    @FastNative
    private external fun lastError(): String"""
)

replace_once(
    kt,
    """                load(pathToModel).let {
                    // TODO-han.yin: find a better way to pass other error codes
                    if (it != 0) throw UnsupportedArchitectureException()
                }""",
    """                load(pathToModel).let { result ->
                    if (result != 0) {
                        val details = lastError().takeLast(10000)
                        throw IOException(
                            "llama.cpp не смог загрузить GGUF (код $result). " +
                            if (details.isBlank()) "Нативный загрузчик не предоставил подробностей."
                            else "\\nПодробности:\\n$details"
                        )
                    }
                }"""
)

print("Готово: добавлена передача нативной диагностики в Kotlin.")

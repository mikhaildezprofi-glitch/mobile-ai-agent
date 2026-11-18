cmake_minimum_required(VERSION 3.22.1)

project(jimmy_pocketpal_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Exynos/ARM64 CPU build first. Qualcomm-only backends are intentionally excluded.
set(RNLLAMA_ANDROID_VARIANTS "rnllama" CACHE STRING "Build only the generic CPU variant" FORCE)

set(POCKETPAL_LLAMA_RN_ROOT
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/pocketpal-llama-rn"
    CACHE PATH "Pinned PocketPal llama.rn source checkout")

if(NOT EXISTS "${POCKETPAL_LLAMA_RN_ROOT}/android/src/main/rnllama/CMakeLists.txt")
    message(FATAL_ERROR
        "PocketPal llama.rn source is missing at ${POCKETPAL_LLAMA_RN_ROOT}. "
        "Checkout the pinned a-ghorbani/llama.rn revision before configuring.")
endif()

# Reuse PocketPal's exact native source/include manifest, but not its React Native JSI layer.
include("${POCKETPAL_LLAMA_RN_ROOT}/cmake/rnllama-sources.cmake")
add_subdirectory(
    "${POCKETPAL_LLAMA_RN_ROOT}/android/src/main/rnllama"
    "${CMAKE_CURRENT_BINARY_DIR}/pocketpal-rnllama")

add_library(jimmy_pocketpal_jni SHARED pocketpal_jni.cpp)
target_include_directories(jimmy_pocketpal_jni PRIVATE ${RNLLAMA_INCLUDE_DIRS})

find_library(ANDROID_LOG_LIB log)
target_link_libraries(jimmy_pocketpal_jni PRIVATE rnllama ${ANDROID_LOG_LIB} android)

target_compile_options(jimmy_pocketpal_jni PRIVATE
    -O3
    -DNDEBUG
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections)

target_link_options(jimmy_pocketpal_jni PRIVATE
    -Wl,--gc-sections
    -Wl,-Bsymbolic)

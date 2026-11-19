# PocketPal native engine migration for Jimmy

Status: native core and Jimmy-owned JNI adapter both compile successfully for ARM64 in GitHub Actions. They are not yet assembled into an APK or runtime-tested on the Exynos device.

## Decision

Use PocketPal's native inference core as the target engine, but keep Jimmy's Android/Kotlin/Jetpack Compose application. Do not transplant PocketPal's React Native application or require a React Native runtime.

PocketPal's current stack is materially ahead of Jimmy's existing Android wrapper in inference features:

- Native Jinja chat-template rendering and OpenAI-compatible message parsing.
- Structured tool-call parsing from model output, including incremental partial results.
- JSON-schema / grammar-constrained generation.
- Multimodal image and audio processing through MTMD.
- Explicit sampler configuration and completion lifecycle.
- Context-state checkpoints, prefix reuse, cancellation and parallel request slots.
- Speculative decoding support (MTP) and multiple ARM CPU build variants.
- Optional Qualcomm OpenCL / Hexagon backends.

The current Jimmy wrapper exposes a much smaller plain-text JNI interface: load model, set a plain system prompt, submit a plain text prompt and receive token strings. Its current llama.cpp pin is 3de52ac38818978d6da8f386eda43a856e53714a (2026-11-04). It does not expose PocketPal's structured completion/tool-call API to Kotlin.

## Source and licensing

- PocketPal app: https://github.com/a-ghorbani/pocketpal-ai
- PocketPal's llama.rn fork: https://github.com/a-ghorbani/llama.rn
- Upstream llama.rn: https://github.com/mybigday/llama.rn
- The inspected llama.rn source declares the MIT license. Preserve its license and notices when vendoring or adapting source.
- Pin the exact llama.rn commit used by the build. Do not build from a moving main ref.

The fork's native core is implemented in C/C++ under cpp/ and uses a vendored llama.cpp tree. The React Native JSI bridge is a separate layer. Jimmy should reuse/adapt the native core and write a small JNI/Kotlin host adapter, rather than importing JSI, fbjni, ReactAndroid, or PocketPal's UI.

## Android target constraints

Jimmy remains an Android-only Kotlin application with its existing package ID, storage, settings, chat UI, Gemini provider and runtime configuration.

The target phone is Samsung Galaxy S23 FE Exynos / ARM64. Therefore:

- Build and test the ARM64 CPU backend first.
- Do not assume Qualcomm Adreno OpenCL or Hexagon HTP/NPU acceleration is available on this Exynos device.
- Keep Qualcomm-specific variants optional and disabled unless device capability detection confirms support.
- Preserve the existing Qwen GGUF model path and model lifecycle semantics.
- Keep model loading, inference, cancellation and release serialized to prevent native context races and memory leaks.

## Target architecture

1. Native core: pinned PocketPal llama.rn C/C++ inference core, including llama.cpp, ggml, common chat/Jinja, parsers, sampling, MTMD and the relevant CPU implementation.
2. JNI host adapter: a Jimmy-owned C++ JNI layer. It must not depend on React Native or JSI.
3. Kotlin engine API: expose model load/unload, generation, cancellation, context settings, system/chat messages and structured completion events.
4. Agent tool dispatch: consume native tool_calls events, validate names and JSON arguments against the local allow-list, execute tools sequentially with a hard call limit, and send tool results back as tool messages.
5. Coordinator: preserve local/cloud routing and Gemini fallback policy. Local tool calls must never be sent to Gemini unless an explicit runtime policy permits it.
6. UI: keep Compose UI independent from the native implementation. The existing plain-text engine interface can remain as a compatibility adapter during migration.

## Required Kotlin/native contract

The JNI boundary should expose structured events, not a JSON string embedded in ordinary assistant prose:

- TextDelta(text)
- ReasoningDelta(text) (internal only; never display unless a future explicit setting enables it)
- ToolCallDelta(index, id, nameFragment, argumentsFragment)
- ToolCallsComplete(calls)
- GenerationFinished(metadata)
- GenerationFailed(error)

The Kotlin layer must assemble fragmented tool-call arguments safely, validate the final JSON, and dispatch only registered tools. Cancellation must interrupt native generation promptly. Native exceptions must become typed Kotlin errors; they must not leave the engine marked ready while its context is invalid.

## Migration sequence

### Stage A — source/build integration
- Pin the exact llama.rn fork commit and retain its MIT notices.
- Add a separate native-core checkout in GitHub Actions.
- Build a CPU-only ARM64 native library in CI without changing the current engine.
- Add a small native smoke-test target for model metadata and context initialization.
- Keep the existing llama.cpp module as rollback fallback.

### Stage B — JNI/Kotlin adapter
- Implement native model lifecycle, chat-template formatting, streaming callbacks and cancellation.
- Add structured tool-call and JSON-schema support.
- Add tests for callback ordering, fragmented arguments, cancellation, repeated load/unload and failure recovery.

### Stage C — agent integration
- Make AgentCoordinator prefer the new structured local engine when it is available.
- Retain the text-envelope runner only as a temporary fallback for the old engine.
- Route native tool calls through the existing allow-listed AgentTalentRegistry.
- Ensure tool results are returned to the model as structured tool messages, not concatenated into an ambiguous prompt.

### Stage D — feature enablement
- Add image/audio projector loading and multimodal message handling.
- Add model-specific sampler/context settings and memory-aware defaults.
- Evaluate context checkpointing and speculative decoding only after correctness and memory tests.
- Enable any hardware backend only after verified device capability detection.

### Stage E — cutover
- Compare output correctness, token throughput, memory use, cancellation latency and repeated session stability in GitHub CI and later on the device.
- Do not remove the existing engine until the new engine passes the same regression suite and device validation.
- Do not change the application ID or storage layout as part of the engine migration.

## Current interim state

The consolidated branch now contains:
- A pinned checkout of PocketPal llama.rn at `ae0e55b7dacdb06d37843273204a3e7ff294a6a2`.
- A standalone JNI bridge with model load/release, Jinja chat formatting, structured tool-call results, snapshot callback and cancellation.
- A Kotlin `PocketPalNativeBridge` and `PocketPalStructuredAgentRunner`.
- JNI multimodal groundwork: projector load/release functions and OpenAI-style `image_url` / `input_audio` message normalization to PocketPal MTMD media markers and local media paths. The Compose Vision flow is not switched to this path yet.
- `PocketPalLocalProvider` selected by `AgentCoordinator.forContext()` as the primary local text provider.
- A Compose model lifecycle that keeps the legacy llama.cpp engine unloaded for ordinary text generation and only loads it for the existing Vision path. This avoids loading the same Qwen GGUF into two engines simultaneously. The next cutover is to load SmolVLM and its mmproj through the PocketPal MTMD API, route image requests through the shared local provider, then remove the legacy Vision engine only after CI and device validation.
- The legacy JSON-envelope runner and legacy local text provider have been removed from the active coordinator path.

GitHub Actions run 44406380286 successfully built and verified both `librnllama.so` and `libjimmy_pocketpal_jni.so` as ARM64/AArch64 shared libraries. The artifact is `pocketpal-rnllama-arm64-cpu`. SHA-256: `librnllama.so` = `798e3e022174aeed0bdac768db30e253398e67fc7b1fec716c8dd06167efa6a7`; `libjimmy_pocketpal_jni.so` = `b575e86269f03c68a89a366d9d4da7a4140f294e2b7957bf6ea1423941172040`. This is a native-library CI build, not an APK build.

The unit-test-only workflow run 44406966524 also completed successfully after the process-wide provider and coordinator lifecycle changes.

The Kotlin provider and UI cutover have not yet been validated on the physical Exynos device. The final APK build remains deferred until all additions and changes are complete.

# Native dependency moved to GitHub Actions

The 479 MB local llama.cpp checkout was removed after its Android modifications and rollback archives were transferred to the public branch `snapshot/device-worktree-20261109`.

Cloud builds fetch upstream `ggml-org/llama.cpp` at commit `3de52ac38818978d6da8f386eda43a856e53714a`, apply `patches/llama.cpp/android-engine.patch`, install `VisionInferenceEngine.kt`, and compile the Android native libraries through GitHub Actions.

To restore the source tree for a local build:

```bash
git clone https://github.com/ggml-org/llama.cpp third_party/llama.cpp
git -C third_party/llama.cpp checkout 3de52ac38818978d6da8f386eda43a856e53714a
curl -L 'https://raw.githubusercontent.com/mikhaildezprofi-glitch/mobile-ai-agent/snapshot/device-worktree-20261109/patches/llama.cpp/android-engine.patch' -o /tmp/android-engine.patch
git -C third_party/llama.cpp apply /tmp/android-engine.patch
mkdir -p third_party/llama.cpp/examples/llama.android/lib/src/main/java/com/arm/aichat
curl -L 'https://raw.githubusercontent.com/mikhaildezprofi-glitch/mobile-ai-agent/snapshot/device-worktree-20261109/patches/llama.cpp/VisionInferenceEngine.kt' -o third_party/llama.cpp/examples/llama.android/lib/src/main/java/com/arm/aichat/VisionInferenceEngine.kt
```

The local Gradle build is intentionally not self-contained until this dependency is restored. Use the cloud Android build workflow for normal development.

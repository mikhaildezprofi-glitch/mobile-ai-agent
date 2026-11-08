# Device Worktree Snapshot

Created: 2026-11-09

This branch snapshots the current Samsung device worktree source and automation files. It preserves the current source state without changing `main`.

## Included
- Current Android app source and manifest/build configuration.
- Current README and build/report script.
- Cloud and agent-related Kotlin source additions.

## Excluded intentionally
- Generated build output and Gradle/Kotlin caches.
- Local model weights (`models/` and the separate Qwen GGUF).
- Runtime status files and local.properties.
- Rollback `.bak` files and the native-library rollback folder; those remain local pending private cloud backup.
- The local `third_party/llama.cpp` checkout. It was a clean checkout at `3de52ac38818978d6da8f386eda43a856e53714a`, the same pinned revision fetched by `.github/workflows/android-build.yml` from `ggml-org/llama.cpp`. The cloud workflow fetches this dependency itself, so the phone does not need to retain a duplicate 479 MB source tree for cloud builds.

## Restore local native build dependency if needed

```bash
git clone https://github.com/ggml-org/llama.cpp third_party/llama.cpp
git -C third_party/llama.cpp checkout 3de52ac38818978d6da8f386eda43a856e53714a
```

The retained installer APK is byte-identical to the APK formerly in `app/build/outputs/apk/debug/` and `/Download/MOBILE_AI_AGENT.apk`. The generated `app/build/` directory and duplicate APK were removed; source files remain intact.

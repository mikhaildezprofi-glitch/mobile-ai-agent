# Device Worktree Snapshot

Created: 2026-11-09

This branch snapshots the current Samsung device worktree source and automation files. It preserves the current parent-project source state without changing `main`.

## Included
- Current Android app source and manifest/build configuration.
- Current README and build/report script.
- Current agent, document, image/video, foreground-service and UI source additions.

## Excluded intentionally
- Generated build output and Gradle/Kotlin caches.
- Local model weights (`models/` and the separate Qwen GGUF).
- Runtime status files and local.properties.
- Rollback `.bak` files and the native-library rollback folder; these remain on the device pending a private/cloud backup.
- `third_party/llama.cpp` is not yet included in this snapshot. The phone checkout is based on upstream commit `3de52ac38818978d6da8f386eda43a856e53714a`, but it contains five meaningful modified Android engine files and six untracked files, including vision support and prebuilt-library rollback copies. It must not be deleted until those changes are archived and the cloud build is taught to apply the required native patch.

## Build dependency

`.github/workflows/android-build.yml` checks out `ggml-org/llama.cpp` at `3de52ac38818978d6da8f386eda43a856e53714a`. The cloud workflow currently fetches upstream source, but this snapshot does not yet transfer the phone's local native-engine modifications. Cloud build parity is therefore not yet established.

The retained installer APK is byte-identical to the APK formerly in `app/build/outputs/apk/debug/` and `/Download/MOBILE_AI_AGENT.apk`. The generated `app/build/` directory and one duplicate APK were removed; source files remain intact.

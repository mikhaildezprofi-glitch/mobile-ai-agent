# Device Worktree Snapshot

Created: 2026-11-09

This branch snapshots the current Samsung device worktree and transfers the native Android engine changes and rollback archives into GitHub. `main` remains unchanged.

## Included
- Current Android app source, manifest and Gradle configuration.
- Current agent, document, image/video, foreground-service and UI source additions.
- `patches/llama.cpp/android-engine.patch`, the new `VisionInferenceEngine.kt`, The OpenMP runtime is emitted by CMake; the workflow does not add a duplicate copy.
- Recovery archives for the local native-library backups and source rollback `.bak` files.
- GitHub Actions now applies the native patch and can build this snapshot branch on push.

## Still local by design
- Model weights (`models/` and the separate Qwen GGUF), because they are needed for on-device inference and are large.
- The full `third_party/llama.cpp` checkout has now been removed from the phone after its five meaningful Android engine changes, vision interface, and rollback files were transferred into this branch. The phone retains only `third_party/README.md` with restore instructions. This shifts native source compilation to GitHub Actions; restore the pinned upstream source plus patch only if a local build is needed.
- `local.properties`, runtime status files and local caches.

## Build dependency

`.github/workflows/android-build.yml` checks out `ggml-org/llama.cpp` at `3de52ac38818978d6da8f386eda43a856e53714a`, applies `patches/llama.cpp/android-engine.patch`, installs the vision interface and `libomp.so`, then builds the APK on GitHub Actions. The workflow filters push/PR events to source and build files, and stores APK artifacts for 90 days. The snapshot branch is included in the workflow's push trigger.

The retained installer APK is byte-identical to the APK formerly in `app/build/outputs/apk/debug/` and `/Download/MOBILE_AI_AGENT.apk`. Generated `app/build/`, the 479 MB local llama.cpp checkout, Gradle/Kotlin caches, duplicate APKs, and local rollback copies were removed after cloud archival. Model weights remain local for offline inference.

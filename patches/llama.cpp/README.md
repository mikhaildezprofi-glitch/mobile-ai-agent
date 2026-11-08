# Android native engine patch

`android-engine.patch` contains the five device-tested changes to the pinned upstream llama.cpp revision `3de52ac38818978d6da8f386eda43a856e53714a`:

- multimodal/MTMD CMake integration for Android ARM64;
- JNI vision-projector loading and image-prompt entry points;
- native diagnostic capture exposed to Kotlin;
- sampler and token-position fixes used by the device build.

`VisionInferenceEngine.kt` adds the optional Kotlin capability interface. The upstream CMake build produces `libomp.so` as part of the native output; do not copy another `libomp.so` into `jniLibs`, or Android Gradle Plugin will report a duplicate native library.

The GitHub Actions workflow applies this patch to a fresh checkout of the pinned upstream revision. It deliberately keeps the upstream Android Gradle file's CMake build configuration; the phone's local Gradle file had disabled CMake because it used locally built prebuilt libraries.

The patch was generated with Android file-mode changes normalized and reverse-checked against the device's current modified checkout. Cloud compilation and device runtime validation are still required before merging.

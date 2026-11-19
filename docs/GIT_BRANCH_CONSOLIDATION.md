# Git branch consolidation

Canonical development branch: `integration/agent-consolidated-20261119`

Base: `fix/gemini-handoff-clean-20261112`, which already contained the task planner, task executor registry, SQLite journal and resumable plans. The consolidated branch is now the only active integration target.

## Consolidated work

- Task planner, task executor registry, SQLite task journal, resumable plans and Gemini routing.
- PocketPal llama.rn native core, pinned to `ae0e55b7dacdb06d37843273204a3e7ff294a6a2`.
- Jimmy-owned CMake target and JNI bridge for ARM64 CPU inference, Jinja chat formatting, structured tool calls, snapshots and cancellation.
- Kotlin `PocketPalNativeBridge`, the injectable `PocketPalGenerationEngine` contract, `PocketPalStructuredAgentRunner` and process-wide `PocketPalLocalProvider`.
- `AgentCoordinator` now routes local text requests and local plan steps through PocketPal. Gemini remains a separate cloud provider.
- The Compose model lifecycle does not load Qwen into the old llama.cpp engine. The old engine is retained only for the current Vision/MTMD path.
- Local tools are allow-listed: `calculate` and `datetime`; maximum five sequential calls per request. Structured-runner tests cover tool-result continuation, multiple call IDs and the hard execution limit.
- The old text JSON-envelope runner was deleted. There is one structured tool-calling path.
- Chat history is passed into PocketPal prompts and native snapshots are converted into incremental text for the existing chat UI.
- A separate unit-test workflow runs `:app:testDebugUnitTest`; it does not assemble or sign an APK.
- The native workflow builds only ARM64 .so libraries. The JNI bridge uses an explicit UTF-8 to UTF-16 conversion and is independent of deprecated `codecvt` support.

## Superseded pull requests

- PR #3: installed Gemini app handoff. Closed as duplicate; `InstalledGeminiBridge`, package visibility and settings UI are already present in the inherited source.
- PR #4: task planning and persistence. Closed as superseded; the task subsystem is the base of this branch.
- PR #5: PocketPal local talents. Closed as superseded; its useful implementation has been replaced by the native structured tool path.

## Related branch history

The following branches are historical source snapshots or intermediate implementations, not active development targets:

- `feature/cloud-first-hybrid-20261111`: ancestor of the consolidated source; its cloud routing, privacy, runtime configuration and UI work are inherited.
- `feature/vision-model-manager-20261111`: its verified SmolVLM download and installed-assets detection are present in the consolidated code. Its feature-branch APK trigger was intentionally not carried over.
- `feature/installed-gemini-clean-20261112`: duplicate installed-Gemini implementation; the same bridge is already in the inherited source.
- `feature/gemini-handoff-gitlab-20261112`, `sync/gemini-handoff-buildfix-20261112` and `sync/gemini-handoff-gitlab-20261112`: duplicate GitLab/GitHub sync snapshots. Their root version catalog and installed-Gemini source are present in the consolidated tree.
- `fix/pocketpal-talents-20261119` and `fix/pocketpal-talents-main-20261119`: intermediate text-envelope talent runners. Their calculator/date-time talents and allow-list were retained; the envelope runner was intentionally not copied because the structured PocketPal path supersedes it.
- `feat/pocketpal-native-core-android-20261119`: intermediate native-core build branch; its native source, pin and workflow are integrated here. Its successful native build and the current native build both verified ARM64 shared libraries.
- `fix/gemini-handoff-clean-20261112`: preserved as the historical base of the consolidated branch.

Other branches such as `agent-queue`, `feature/hourly-device-watchdog` and `cloud/quality-gates-20261109` have separate automation/queue purposes and were not blindly merged into the Android application. No history was force-deleted. GitHub connector permissions available in this session do not expose a branch-delete operation, so superseded refs remain for audit/rollback; do not continue development on them.

## Build policy during development

- Do not run the APK build workflow during feature development.
- Use the unit-test-only workflow for JVM regression checks.
- The PocketPal ARM64 native-core workflow builds only native shared libraries, never an APK. Run `44403357955` successfully produced `librnllama.so` and `libjimmy_pocketpal_jni.so`, verified as ELF64/AArch64.
- Unit-test run `44406966524` passed after the process-wide provider change; run `44407449547` passed the subsequent UI/provider changes. Structured tool-runner regression tests are being checked in run `44458814893`.
- The final APK workflow should run once after the remaining additions, integration and checks are complete.

# Git branch consolidation

Canonical development branch: `integration/agent-consolidated-20261119`

Base: `fix/gemini-handoff-clean-20261112` (16 commits ahead of `main` at consolidation time).

## Consolidated work

- The task planner, task executor registry, SQLite task journal, resumable plans, coordinator changes and current Compose screen are inherited from the Gemini handoff branch.
- PocketPal's local talent registry and bounded tool runner are integrated into the same `AgentCoordinator`. The existing planner and task persistence remain in place; the tool runner is used for each ordinary generation and each plan step.
- The local calculator and date/time talents are registered once in `AgentToolRegistry`.
- The JVM test classpath uses a real `org.json` implementation; production Android JSON behavior is unchanged.
- PocketPal native-core migration notes and the isolated ARM64 native-core workflow are included.
- A separate unit-test-only workflow runs `:app:testDebugUnitTest` and does not assemble or sign an APK.

## Superseded pull requests

- PR #3 (installed Gemini app handoff): closed as duplicate. Its bridge, manifest package visibility and settings UI are already present on `main` and inherited by this branch.
- PR #4 (task planning and persistence): closed as superseded. Its changes are the base of this branch.
- PR #5 (PocketPal local talents): closed as superseded. Its implementation is consolidated into this branch and integrated with the task planner.

## Related branch history

- `fix/pocketpal-talents-20261119` contains the PocketPal talents on top of the task-planner branch.
- `fix/pocketpal-talents-main-20261119` contains the same talent adaptation on top of the older `main`.
- `feat/pocketpal-native-core-android-20261119` adds the native-core migration documentation and native-only workflow to the main-based talent branch.
- `feature/installed-gemini-clean-20261112` is an older version of the installed-Gemini work now present in `main`.
- `feature/gemini-handoff-gitlab-20261112` and `sync/gemini-handoff-buildfix-20261112` point to the same commit.

Historical refs are intentionally retained for audit and rollback. They are not active development targets. The consolidated branch is the only branch to receive further integration work.

## Build policy during development

- Do not run the APK build workflow during feature development.
- Use the unit-test-only workflow for JVM regression checks.
- The PocketPal ARM64 native-core workflow builds only native `.so` files (never an APK). It successfully compiled `librnllama.so` and `libjimmy_pocketpal_jni.so` as ELF64/AArch64 and uploaded the `pocketpal-rnllama-arm64-cpu` artifact.
- Create the final PR and run the APK build only after native JNI/Kotlin integration and the remaining additions are complete.

# GitLab → GitHub → GitLab → phone build flow

## Source of truth

GitLab owns application source, agent tasks and Merge Requests. A GitLab source commit is pushed to a GitHub branch under sync/. GitHub Actions builds only that synchronized commit.

The hourly GitHub workflow also polls the public GitLab main branch, mirrors it into GitHub branch sync/gitlab-source, and builds the mirrored source. This is a fallback for cases where an immediate push bridge is unavailable.

## APK build and return

1. GitHub Actions builds the Android debug APK from the synchronized source.
2. It creates build metadata containing the full GitLab source SHA, GitHub run ID, versionCode, versionName, APK size and SHA-256.
3. It uploads a normal Actions artifact and publishes the APK, metadata and SHA-256 as a GitHub Release asset.
4. GitLab CI waits for the release matching the exact CI_COMMIT_SHA, downloads it and verifies the metadata and checksum.
5. GitLab stores the verified APK as its own job artifact. This is the handoff to device testing.

## Device installation

The APK from GitHub Actions uses the runner's debug signing key. Before updating the installed package, re-sign it with the existing local Android debug keystore, verify the certificate fingerprint against the installed package, and install with adb install -r. Never uninstall or clear app data during an upgrade.

## Evidence required

- GitLab source commit SHA.
- GitHub workflow run URL and conclusion.
- APK SHA-256 and byte size.
- GitLab APK-sync job result and artifact presence.
- Device package versionCode/versionName after installation.
- JNI library load, model load, projector load, text inference and image inference logs.

A green build is not proof of runtime inference. Keep CI and device results separate.

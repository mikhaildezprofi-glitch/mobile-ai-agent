# GitLab AI agent rollout plan

## Objective

Move all primary source development, builds, task coordination and agent execution to GitLab. The Android phone remains a device-validation target and optional Termux queue endpoint, not the cloud build machine.

## Phase 0: establish GitLab as the source of truth

- Review migration MR !6.
- Verify the serial task queue validator.
- Verify the GitLab Android debug APK build and artifact.
- Keep GitHub as a separate legacy copy only; do not depend on GitHub Actions or Copilot for new work.

## Phase 1: activate the free coding executor

- Add masked CI variable GEMINI_API_KEY using an eligible Google AI Studio Free API key.
- Add masked CI variable GITLAB_AGENT_TOKEN using a GitLab Personal Access Token (PAT) with api and write_repository scopes. GitLab.com project access tokens require Premium, so use a PAT on Free. Set an expiry date and never share the token in chat.
- Keep credentials out of repository files, artifacts and logs.
- Start one manual gitlab-ai-agent job from the protected default branch.
- Verify the actual Aider worker job, dedicated branch and Draft Merge Request before marking the executor active.

## Phase 2: first controlled task

Run audit-repo-source-sync first. It compares the public GitHub source tree with the GitLab checkout, records exact paths and SHAs, and prepares a safe source-sync report. It must not copy model weights, APKs, signing material, backups or secrets, and must not change main directly.

## Phase 3: specialist onboarding

Use the portable role definitions under agent-control/roles/:
1. Coordinator: plan and sequence tasks.
2. Research: repository/source audit.
3. Cloud Build: reproducible GitLab Android builds.
4. QA: regression and acceptance checks.
5. Security: secrets, permissions and dependency review.
6. Native Runtime: JNI/NDK/OpenMP/ggml and llama.cpp.
7. Android: Kotlin, Compose and device integration.

Only one task may be queued or under review at a time. Every implementation task gets a narrow editable-path allowlist and a Draft MR. Run the required tests before advancing to the next task.

## Phase 4: hourly monitoring

Create one GitLab pipeline schedule on the default branch with cron 0 * * * * in UTC. The read-only project-hourly-monitor job checks recent pipelines, Merge Requests, the coding task queue, actual AI worker jobs, and the existing Termux/ADB device heartbeat branch. It reports state but does not dispatch tasks or merge code.

## Safety gates

- No direct main writes and no automatic merges.
- No destructive commands or automatic device actions.
- CI jobs, profile files and queue entries are not proof of active AI sessions.
- Free Gemini API requests may be used to improve Google services and reviewed by humans. Never send secrets, personal data, signing keys, model files or private logs.
- Stop on failed checks, missing permissions, API quota exhaustion or an unavailable executor.
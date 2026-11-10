# MOBILE AI AGENT: GitLab agent team

GitLab is the only primary development and agent orchestration platform for this project. GitHub Copilot-specific profiles and GitHub Actions workflows are retired from the GitLab source branch.

## Active role definitions

Portable specialist instructions live under agent-control/roles/:

- coordinator.md: serial task planning and handoffs
- android.md: Kotlin, Compose, lifecycle, permissions and device integration
- native-runtime.md: llama.cpp, GGUF, JNI, NDK, OpenMP and ggml
- cloud-build.md: GitLab CI/CD, Gradle, SDK/NDK and APK artifacts
- qa.md: tests, regressions and acceptance evidence
- security.md: secrets, permissions, supply chain and task boundaries
- research.md: repository audits and evidence-based reports

## Execution model

Aider is the coding executor running inside GitLab CI. Gemini API Free is the initial model provider. The queue is agent-control/queue.json. The worker accepts one queued task, restricts writable paths, pushes a dedicated branch and opens a Draft Merge Request. It never writes to main or merges its own work.

## What counts as an active agent

A role file is not an active agent. A pipeline is not an active agent. An agent is counted only when a real gitlab-ai-agent worker job is running or has completed and its branch and Draft MR can be verified.

## Safety

One task at a time. Required checks before advancing. Human review and merge only. No secrets, model weights, signing keys, personal data or private logs are sent to the free model API.
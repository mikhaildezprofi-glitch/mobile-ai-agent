# GitLab as the primary MOBILE AI AGENT platform

## Decision

The canonical project is GitLab: https://gitlab.com/lenives777/mobile-ai-agent

GitLab owns the source tree, task queue, CI/CD, build artifacts, monitoring, agent roles, and Merge Requests. GitHub is no longer required for day-to-day development or agent orchestration. Its repository remains a separate legacy copy until the source audit and migration review are complete.

## Source baseline and migration warning

At the time this plan was prepared:
- GitLab `main` contained 37 tree entries and did not contain a root `.gitlab-ci.yml`.
- GitLab branch `agent-team/bootstrap-20261109` contained 47 tree entries, including the Android application sources, Gradle configuration, agent profile definitions, and build workflow.
- The Android Gradle settings reference `third_party/llama.cpp/examples/llama.android/lib`; the existing build workflow checks out llama.cpp at commit `3de52ac38818978d6da8f386eda43a856e53714a`. The native dependency is therefore treated as a pinned external checkout, not copied as a large vendored tree.
- The migration branch must be reviewed and tested before it becomes the default branch. No automatic merge is permitted.

## GitLab-native responsibilities

- Repository and branch management: GitLab.
- Build/test: GitLab CI/CD, including Android debug APK.
- Queue: `agent-control/queue.json`.
- Agent role prompts: `agent-control/roles/*.md`.
- Coding executor: Aider running in a GitLab CI job.
- Initial model: Gemini 3.8 Flash through Gemini API Free, subject to free-tier quota and privacy limitations.
- Monitoring: GitLab scheduled pipeline and `agent-control/monitor.py`.
- Review: Draft Merge Requests, human approval, no agent self-merge.

## Copilot replacement

The Copilot custom profiles are not active GitLab agents. Their responsibilities are represented as portable role prompts under `agent-control/roles/`. Aider is the actual file-editing executor; Gemini is the model provider. The worker receives one queued task and only the task's allowlisted writable paths.

GitLab Duo Agent Platform is not selected as the free replacement because its generally available agent features on the Free plan require purchased GitLab Credits.

## Free-tier constraints

GitLab.com Free includes 400 compute minutes per month for hosted runners. Android builds are expensive compared with small validation jobs, so run APK builds only on merge requests and the default branch, auto-cancel obsolete MR builds, and keep hourly monitoring to one lightweight job. The hourly worker checks for queued tasks before installing Git or Aider, so an empty queue is a low-cost no-op. Gemini 3.8 Flash is configured through the Gemini API Free tier; model quotas and availability can vary. No paid billing is enabled.

Google's unpaid Gemini API terms permit submitted prompts and responses to be used to improve Google services and reviewed by humans. Do not submit secrets, private data, signing material, model weights, personal data, or private logs.

## Activation gates

1. Review this migration branch and its source tree.
2. Verify the GitLab Android build on a Merge Request pipeline.
3. Add masked/protected `GEMINI_API_KEY` and `GITLAB_AGENT_TOKEN` variables. `GITLAB_AGENT_TOKEN` must be a GitLab Personal Access Token with `api` and `write_repository` scopes; GitLab.com project access tokens are not available on the Free tier.
4. Confirm one real Aider worker run creates a branch and Draft MR.
5. Create the hourly GitLab pipeline schedule.
6. Only after those checks, mark the executor active and unblock the first queue task.

## Safety

No direct writes to `main`, no automatic merges, no destructive operations, no model-file copies, and no credentials in Git. A green CI pipeline is not proof of an AI agent session. A role prompt is not a running agent.

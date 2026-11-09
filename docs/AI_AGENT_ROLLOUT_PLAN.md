# Cloud AI agent rollout plan

## Objective

Move repository analysis, coding, builds, and quality checks to cloud services. Keep the Samsung phone as a device-validation target and optional Termux queue endpoint, not as the primary build machine.

## Current verified baseline

- Six specialist/coordinator profile files are present in `.github/agents/` on branch `agent-team/bootstrap-20261109`.
- `AGENTS.md` defines branch, data-protection, test-evidence, and PR rules.
- The branch is isolated from `main`; no application source, native runtime, Gradle files, model weights, APKs, or backups are changed.
- `device-watchdog.yml` runs hourly from the default branch and monitors the phone heartbeat and phone-dependent queue blockers. It does not launch coding agents.
- `cloud-bridge.yml` validates queue events and accepts a small set of local-only task types. It does not execute arbitrary code changes or start an AI coding agent.
- `gemini-ai-triage.yml` performs sanitized diagnostics only when `GEMINI_API_KEY` is configured. It explicitly does not authorize or execute commands.
- No persistent cloud coding-agent session or active agent PR has been verified yet.

## Phase 0: merge the agent profiles

Owner: repository maintainer.

1. Open a draft PR from `agent-team/bootstrap-20261109` into `main`.
2. Review the six profiles and repository instructions.
3. Run the repository's required CI checks.
4. Merge only after human review.
5. Open GitHub Copilot's Agents page and confirm that the repository and custom profiles appear.

Gate: profile files exist on the default branch and GitHub exposes the custom profiles. A branch-only profile is not yet active for the repository's default Copilot agent experience.

## Phase 1: enable one cloud coding executor

Owner: repository maintainer, then Copilot coding agent.

1. Verify that the GitHub account has an eligible paid Copilot plan with cloud agent access.
2. In repository settings, verify that Copilot coding agent is enabled and allowed to access this repository.
3. Create one small issue: "Bootstrap cloud agent workflow and document execution boundaries".
4. Assign that issue to Copilot coding agent from GitHub's issue UI.
5. Require a dedicated branch and draft PR. Do not grant merge authority.
6. Confirm a real agent session, commits, and PR before recording the executor as active.

Gate: at least one real cloud agent session and draft PR are visible in GitHub. If account plan, repository policy, or UI controls prevent assignment, stop and record the blocker.

## Phase 2: pilot the coordinator

Use the Agent Coordinator profile to split one narrowly scoped cloud-infrastructure task into dependency-ordered steps. The coordinator may delegate analysis to specialist profiles when the Copilot runtime supports it, but must not claim independent agent sessions unless GitHub shows them.

Pilot acceptance:
- one dedicated branch and draft PR;
- exact changed-file list;
- lint and unit-test results;
- APK build result, if relevant;
- independent QA review;
- security review for workflow or permission changes;
- rollback instructions.

## Phase 3: onboard specialists one at a time

1. Cloud Build Engineer: stabilize cloud lint -> unit test -> APK build -> checksum/artifact flow.
2. QA Engineer: add regression tests and verify artifacts from the current commit.
3. AI Runtime Engineer: investigate native library loading and model initialization without changing model assets.
4. Android Engineer: address Kotlin/Compose issues only after runtime and CI baselines are stable.
5. Security Auditor: review permissions, secrets, dependencies, and queue input boundaries before any automation expands.

Each specialist starts with a separate issue and branch. Avoid simultaneous edits to shared files. Dependent work is sequential.

## Phase 4: controlled queue orchestration

Only after the pilot succeeds:

- define a versioned task schema with task ID, scope, acceptance criteria, allowed paths, dependencies, and expected checks;
- validate every queue payload before dispatch;
- allow only explicitly approved task types;
- use GitHub-hosted execution for code work;
- use the phone queue only for actions that truly require the Android device;
- stop the chain on a failed check, permission error, or missing agent;
- never automatically merge PRs or run destructive device commands;
- record task state, commit SHA, PR URL, workflow run, and test evidence.

Do not convert the existing cloud bridge into an unrestricted shell runner. Keep its current narrow validation role unless a separately reviewed design replaces it.

## Phase 5: monitoring and operational handoff

- Keep the existing hourly device watchdog for phone availability.
- Add agent activity monitoring only when a supported GitHub API/event source is available and tested.
- Report active sessions, queued tasks, blocked tasks, failed quality gates, and stale PRs separately.
- Do not label an hourly device heartbeat as an AI-agent health check.
- Keep the phone idle during cloud builds; use it for installation, camera, microphone, thermal, and runtime validation.

## Stop conditions

Stop dispatching when any of these occurs:
- no eligible cloud agent or permission is available;
- an agent attempts to edit protected paths outside its issue scope;
- a quality gate fails;
- a task requests secrets, destructive cleanup, model deletion, or direct main-branch writes;
- evidence cannot establish whether the change was tested.

## Roles of supporting tools

Superpowers is a development-methodology skill set for planning, worktree isolation, test-driven implementation, review, and verification. It is not itself a persistent cloud coding executor. GitHub Actions is CI/monitoring automation, not an AI coding agent. Gemini triage is diagnostic automation, not a code-writing agent.

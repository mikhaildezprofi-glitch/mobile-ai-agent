# MOBILE AI AGENT: cloud AI agent team

## Current roles

See [AI_AGENT_ROLLOUT_PLAN.md](AI_AGENT_ROLLOUT_PLAN.md) for the staged activation plan and the currently verified execution boundaries.

The repository contains reusable GitHub Copilot custom-agent profiles:

| Profile | Responsibility |
|---|---|
| Agent Coordinator | Breaks work into dependent tasks, assigns specialist profiles, and tracks verified handoffs |
| Android Engineer | Kotlin, Compose, Android lifecycle and permissions |
| AI Runtime Engineer | llama.cpp, GGUF, JNI/NDK and native runtime |
| Cloud Build Engineer | GitHub Actions, build pipeline and artifacts |
| QA Engineer | Tests, regression checks and evidence |
| Security Auditor | Permissions, secrets, workflows and data safety |

Profiles are stored in `.github/agents/*.agent.md` and become available in supported GitHub Copilot agent surfaces after the files are merged into the repository's default branch and the account/repository has coding-agent access enabled.

## Safe execution protocol

The Agent Coordinator is the entry point for decomposing a larger request. It may delegate independent analysis to specialist profiles, but shared-file edits and dependent build/test stages remain sequential. It must stop when an agent, permission, or service is unavailable rather than fabricate completion.


1. Create a focused GitHub issue with acceptance criteria.
2. Assign one coding agent to one implementation task.
3. Select a custom profile when starting the task, if the GitHub UI offers it.
4. Require a dedicated branch and draft PR.
5. Run the repository quality gates.
6. Request independent QA and security review.
7. A human reviews and merges. Agents must not merge their own work.

## Important limitations

- A custom profile is a role definition, not a running agent or an automatic worker.
- The hourly device watchdog is monitoring only; it does not dispatch coding agents.
- The Gemini triage workflow is diagnostics-only and requires the repository secret `GEMINI_API_KEY`; it does not edit code.
- The existing queue bridge validates events and does not itself execute an AI coding agent.
- Copilot, Claude, or Codex cloud-agent availability depends on the account plan and repository policy.
- Do not claim that an agent is active until GitHub shows an active session/task or a draft PR from that agent.

# GitHub Code Agent integration

## Implemented in the integration branch

### Credentials
- A separate `GitHubTokenStore` encrypts a fine-grained personal access token with AES-256/GCM.
- The encryption key is generated and retained by Android Keystore. Only ciphertext and the GCM IV are stored in a dedicated private preferences file.
- GitHub credentials are isolated from Gemini credentials.
- Settings UI masks the token, clears the input after saving, supports deletion, and verifies access to the configured repository.

### Restricted GitHub API
- `GitHubApiClient` is restricted to `mikhaildezprofi-glitch/mobile-ai-agent` and the fixed GitHub API host.
- It creates only `code-agent/*` working branches, commits bounded UTF-8 project files through the Git Database API, and uses the expected branch SHA to reject stale changes.
- Git reference validation rejects traversal, malformed segments, hidden path segments, and `.lock` suffixes.
- File-path validation blocks traversal, `.git`, generated directories, local properties, model weights, APKs, environment files, and common signing-key/credential files.
- The client can dispatch only `.github/workflows/agent-unit-tests.yml`, list runs/jobs, compare a working branch with its base, and retrieve job logs.
- Job-log downloads use GitHub's short-lived HTTPS redirect without forwarding the PAT to the redirect host. ZIP entry count, per-entry size, total archive size, and rendered log text are bounded.
- The HTTP connection factory and token-reader interface are injectable for deterministic API unit tests without live GitHub requests.

### Code Agent project UI
- Project dialog supports creating an isolated GitHub working branch.
- A local preview reads the selected private workspace project and lists every file before upload. It also shows removal candidates from a per-branch manifest of files previously synchronized by Jimmy.
- Preview rejects unsafe paths, unreadable/binary files, more than 100 files, and total content above 5 MiB.
- Upload requires a separate explicit confirmation action. Deletions are opt-in (unchecked by default), displayed as an exact path list, and require a separate checkbox confirmation.
- The deletion manifest is initialized only after the first successful upload to that working branch. Files inherited from the base branch are never considered managed and cannot be deleted by this feature. Unconfirmed removals remain in the manifest and reappear in the next preview.
- The Git tree commit API accepts explicit deletion paths only after path validation, duplicate/overlap checks, and the same optimistic branch-SHA check as file updates.
- Successful upload creates one commit in the selected `code-agent/*` branch and dispatches the test-only workflow.
- UI can refresh workflow status and retrieve job summaries/failure logs.
- Remote operations never write to `main`.
- The active working branch, base SHA, and last workflow run ID are stored in app-private preferences. After restart, the user can regenerate the local file preview before committing. Pending file contents are deliberately not persisted.

### CI workflow and verified tests
- The dedicated unit-test workflow runs `:app:testDebugUnitTest` only, with `contents: read`.
- It does not build an APK, upload a release, or receive the user's PAT.
- CI execution of project Gradle scripts has no repository write token or application secrets.
- Run #155 completed successfully on 2026-11-19 for commit `d9a424a02ecaa4b9bab066c017c8c5366d626b01`. All workflow steps, including `Run unit tests only`, completed successfully.
- The API-level unit tests cover mocked HTTP responses, authorization failures, optimistic concurrency conflicts, branch/path validation, explicit deletion tree entries, and bounded ZIP log parsing.
- The test HTTP connection mock explicitly supports `PATCH`, matching the GitHub reference-update request used after a commit.

## Fine-grained token permissions

Create a token scoped only to `mikhaildezprofi-glitch/mobile-ai-agent` with:

- **Contents: Read and write** — repository content, branch and commit operations.
- **Actions: Read and write** — dispatch the unit-test workflow and inspect runs/jobs.

GitHub grants Metadata read access automatically. Do not grant Administration, Secrets, or organization-wide permissions.

## Safety boundaries

- Never paste the PAT into ChatGPT, source files, Git commits, issue comments, or workflow inputs.
- No GitHub token is stored in the repository or passed to Actions.
- Code Agent writes only to `code-agent/*`; it cannot target `main`.
- Intermediate validation uses the test-only workflow, never the APK build workflow.
- The GitHub client does not execute arbitrary shell commands or project-provided scripts.

## Remaining verification

1. Device-side end-to-end verification is **not yet performed**. It requires the user to configure a fine-grained token in the app and run the flow against a disposable `code-agent/*` branch.
2. Per the current project workflow, no phone, Termux, or Desktop Commander testing is being performed in this development phase. The device-side check remains deferred until explicitly resumed.
3. After that check, update this document with the observed result. Do not claim end-to-end verification based on unit tests alone.

## Build policy

No APK was built during this stage. APK assembly remains deferred until all planned development changes are complete and the user explicitly authorizes the final build.

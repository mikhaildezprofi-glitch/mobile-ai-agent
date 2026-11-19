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
- Job-log downloads use GitHub's short-lived HTTPS redirect without forwarding the PAT to the redirect host. ZIP entries and total log size are bounded.

### Code Agent project UI
- Project dialog now supports creating an isolated GitHub working branch.
- A local preview reads the selected private workspace project and lists every file before upload.
- Preview rejects unsafe paths, unreadable/binary files, more than 100 files, and total content above 5 MiB.
- Upload requires a separate explicit confirmation action.
- Successful upload creates one commit in the selected `code-agent/*` branch and dispatches the test-only workflow.
- UI can refresh workflow status and retrieve job summaries/failure logs.
- The remote operation never writes to `main`.
- The active working branch, base SHA, and last workflow run ID are stored in app-private preferences. After restart, the user can regenerate the local file preview before committing. Pending file contents are deliberately not persisted.

### CI workflow
- The dedicated unit-test workflow runs `:app:testDebugUnitTest` only, with `contents: read`.
- It does not build an APK, upload a release, or receive the user's PAT.
- CI execution of project Gradle scripts has no repository write token or application secrets.

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

## Remaining work in this stage

1. Run and verify the unit-test workflow on the integration branch after the latest UI/API changes; fix any failures.
2. Add API-level tests for mocked HTTP requests, optimistic concurrency conflicts, and ZIP log extraction.
3. Add remote deletion preview/confirmation, so a full project sync can mirror removals safely.
4. Perform an end-to-end run with a user-configured fine-grained token and a disposable test branch.
5. Update this document with the verified end-to-end result.

## Build policy

No APK was built during this stage. APK assembly remains deferred until all planned development changes are complete and the user explicitly authorizes the final build.

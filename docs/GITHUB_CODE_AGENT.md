# GitHub Code Agent integration

## Implemented in the integration branch

- A separate `GitHubTokenStore` encrypts a fine-grained personal access token with AES-256/GCM.
- The encryption key is generated and retained by Android Keystore. Only ciphertext and the GCM IV are stored in a dedicated private preferences file.
- GitHub credentials are isolated from Gemini credentials. Clearing the GitHub token does not clear Gemini settings.
- The settings UI masks the token, clears the input after saving, supports deleting it, and can verify access to the configured repository.
- `GitHubApiClient` is restricted to the configured Jimmy repository and fixed GitHub API host.
- It creates only `code-agent/*` working branches, commits bounded UTF-8 project files through the Git Database API, and uses the expected branch SHA to reject stale changes.
- File-path validation blocks traversal, `.git`, generated directories, local properties, model weights, APKs, and common signing-key files.
- The client can dispatch only `.github/workflows/agent-unit-tests.yml`, list runs/jobs, compare a working branch with its base, and retrieve job logs.
- Job-log downloads use GitHub's short-lived HTTPS redirect without forwarding the PAT to the redirect host. ZIP entries and total log size are bounded.
- The dedicated unit-test workflow runs `:app:testDebugUnitTest` only, with `contents: read`; it does not build an APK.

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

## Remaining work before stage 1 is complete

1. Connect the Code Agent's project workspace to the GitHub client.
2. Add local change preview and explicit confirmation before creating a branch or pushing files.
3. Add UI for creating/selecting the working branch, sending a commit, dispatching tests, and viewing status/logs.
4. Add automated tests for GitHub API request construction, stale-branch handling, API errors, and log archive parsing.
5. Run the unit-test workflow on the integration branch and fix all failures.
6. Verify the complete user flow and update this document with the final tested behavior.

# GitHub Code Agent integration

## Current implementation

- GitHub fine-grained PAT is stored separately from Gemini credentials.
- The token is encrypted with AES-256/GCM; the encryption key is generated and retained by Android Keystore.
- Only ciphertext and the GCM IV are stored in a dedicated private SharedPreferences file.
- The settings field masks the token. After saving, the input is cleared and the token is never read back into the UI.
- Removing the token clears only the GitHub-specific preferences and its dedicated Keystore alias.
- Token validation rejects empty/short values, internal whitespace, and control characters.
- The application does not print the token or include it in status messages.

## Required fine-grained token permissions

For the target repository only:

- **Contents: Read and write** — read repository metadata/content and create branches/commits.
- **Actions: Read and write** — dispatch the dedicated unit-test workflow and inspect runs/jobs.

Metadata read access is provided automatically by GitHub for fine-grained tokens. Do not grant Administration, Workflows, Secrets, or organization-wide access.

## Safety boundaries

- Never paste the PAT into ChatGPT, source files, Git commits, issue comments, or workflow inputs.
- The APK build workflow is not used for intermediate Code Agent validation.
- The dedicated `agent-unit-tests.yml` workflow runs `:app:testDebugUnitTest` only and has `contents: read`.
- Code Agent changes must target a separate `code-agent/*` branch. The `main` branch is not a target.
- No arbitrary shell commands or untrusted project scripts are executed by this integration.

## Remaining work in stage 1

1. GitHub REST client with authenticated repository checks.
2. Isolated working-branch creation and safe Git Data API commits.
3. Dispatch of the unit-test-only workflow and retrieval of run/job logs.
4. Local diff preview and explicit user confirmation before pushing changes.
5. End-to-end tests and documentation of permission/error handling.

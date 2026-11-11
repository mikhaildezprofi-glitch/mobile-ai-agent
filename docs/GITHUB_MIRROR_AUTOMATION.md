# GitLab canonical source and GitHub build mirror

## Ownership

- GitLab `lenives777/mobile-ai-agent` is the canonical repository for source changes, branches, reviews, and merge requests.
- GitHub `mikhaildezprofi-glitch/mobile-ai-agent` is a downstream mirror used for GitHub Actions builds, versioned APK releases, and artifact pickup.
- Do not develop directly on GitHub or merge GitHub-only source changes back without first reviewing them against GitLab.

## Automated synchronization

`.github/workflows/gitlab-source-sync.yml`:

- Runs after a push to GitHub `main`, hourly, or by manual dispatch.
- Fetches the public GitLab `main` branch as the source of truth.
- Updates GitHub `main` to the exact GitLab commit. If the GitHub ref differs, its previous tip is preserved in an `archive/github-main-before-sync-*` branch before the update.
- Updates GitHub `sync/current` to the same source commit.
- Checks for an existing release or an active/completed build for that exact SHA before dispatching `android-build.yml`.
- Uses the repository-scoped `GITHUB_TOKEN`; no personal access token or secret is required.

The hourly poll is the fallback for GitLab commits that do not themselves generate a GitHub event. A push to GitHub `main` also starts the sync immediately.

## APK build and verification

`.github/workflows/android-build.yml` builds the checked-out `sync/*` source commit, uploads the APK and metadata, and publishes an immutable release for both push and workflow-dispatch runs.

Release tags use:

`build-<source-sha-prefix>-<github-run-id>`

The release contains:

- `app-debug.apk`
- `app-debug.apk.sha256`
- `build-metadata.json`

The metadata records the exact source commit, GitHub run ID, app version, APK size, and SHA-256 digest.

## Safety rules

- Do not force-push GitHub `main` manually unless performing a controlled GitLab-to-GitHub synchronization.
- Never merge a GitHub PR as an independent source change.
- Keep archive branches until the corresponding GitLab source and APK have been verified.
- The sync workflow intentionally mirrors only GitLab `main` plus the derived GitHub `sync/current` build ref. It does not mirror every feature branch or delete unrelated GitHub archive branches.
- A completed failed build is not automatically retried every hour. Fix the cause or explicitly dispatch a new build to avoid wasting Actions minutes.

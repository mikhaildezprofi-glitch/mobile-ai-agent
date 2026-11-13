#!/usr/bin/env python3
"""Fetch and verify the GitHub Actions APK release for an exact GitLab commit."""
import argparse
import hashlib
import json
import pathlib
import time
import urllib.error
import urllib.request

REPO = "mikhaildezprofi-glitch/mobile-ai-agent"
API = "https://api.github.com/repos/" + REPO

def request(url):
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "mobile-ai-agent-gitlab-apk-sync"
    })
    with urllib.request.urlopen(req, timeout=45) as response:
        return response.read()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--commit", required=True)
    parser.add_argument("--timeout", type=int, default=1800)
    args = parser.parse_args()
    commit = args.commit.lower()
    if len(commit) != 40 or any(c not in "0123456789abcdef" for c in commit):
        raise SystemExit("Expected a full 40-character Git commit SHA")
    prefix = "build-" + commit[:12] + "-"
    deadline = time.time() + args.timeout
    release = None
    last_error = None

    while time.time() < deadline:
        try:
            releases = json.loads(request(API + "/releases?per_page=100"))
            release = next((r for r in releases if r.get("tag_name", "").startswith(prefix) and not r.get("draft")), None)
            if release:
                break
            print("GitHub release for " + commit[:12] + " not published yet; waiting 60 seconds.", flush=True)
        except (urllib.error.URLError, urllib.error.HTTPError, json.JSONDecodeError) as exc:
            last_error = str(exc)
            print("GitHub release lookup temporarily unavailable: " + last_error + "; retrying in 60 seconds.", flush=True)
        time.sleep(60)

    if not release:
        raise SystemExit("No GitHub release for source commit " + commit + "; last error: " + str(last_error))

    assets = {a["name"]: a["browser_download_url"] for a in release.get("assets", [])}
    required = {"app-debug.apk", "app-debug.apk.sha256", "build-metadata.json"}
    if not required.issubset(assets):
        raise SystemExit("GitHub release is missing one or more required build assets")
    out = pathlib.Path("artifacts/github")
    out.mkdir(parents=True, exist_ok=True)

    for name in sorted(required):
        (out / name).write_bytes(request(assets[name]))

    metadata = json.loads((out / "build-metadata.json").read_text())
    if metadata.get("source_commit", "").lower() != commit:
        raise SystemExit("Release source_commit does not match the GitLab pipeline commit")
    digest = hashlib.sha256((out / "app-debug.apk").read_bytes()).hexdigest()
    expected = metadata.get("apk_sha256", "").lower()
    sidecar = (out / "app-debug.apk.sha256").read_text().split()[0].lower()
    if digest != expected or digest != sidecar:
        raise SystemExit("APK SHA-256 verification failed")
    if not metadata.get("version_code") or not metadata.get("version_name"):
        raise SystemExit("APK version metadata is incomplete")
    print(json.dumps({
        "source_commit": commit,
        "github_release": release.get("html_url"),
        "github_tag": release.get("tag_name"),
        "github_workflow_run_id": metadata.get("github_workflow_run_id"),
        "version_code": metadata.get("version_code"),
        "version_name": metadata.get("version_name"),
        "apk_size": metadata.get("apk_size"),
        "apk_sha256": digest,
        "verified": True
    }, indent=2))

if __name__ == "__main__":
    main()

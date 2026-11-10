#!/usr/bin/env python3
"""Run one allowlisted Aider task and open a Draft GitLab MR."""
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

def run(command, **kwargs):
    print("+", command[0], "(arguments hidden)")
    subprocess.run(command, check=True, **kwargs)

def gitlab_api_post(url, token, values):
    body = urllib.parse.urlencode(values).encode()
    request = urllib.request.Request(
        url, data=body, method="POST",
        headers={"PRIVATE-TOKEN": token, "Content-Type": "application/x-www-form-urlencoded"}
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[:500]
        raise RuntimeError(f"GitLab API returned HTTP {exc.code}: {detail}") from exc

def main():
    api_key = os.environ.get("GEMINI_API_KEY")
    token = os.environ.get("GITLAB_AGENT_TOKEN")
    required = ["CI_API_V4_URL", "CI_PROJECT_ID", "CI_PROJECT_PATH", "CI_SERVER_HOST"]
    if not api_key or not token or any(not os.environ.get(key) for key in required):
        raise SystemExit("Missing required masked CI variables; no task was started")
    prepared = json.loads(subprocess.check_output(
        [sys.executable, "agent-control/prepare_task.py"], text=True
    ))
    task_id = prepared["task_id"]
    branch = prepared["branch"]
    allowed = prepared["allowed_paths"]
    prompt_path = prepared["prompt_path"]

    if subprocess.check_output(["git", "status", "--porcelain"], text=True).strip():
        raise SystemExit("Working tree is not clean; refusing to run agent")

    run(["git", "config", "user.name", "GitLab AI Agent"])
    run(["git", "config", "user.email", "gitlab-ai-agent@noreply.gitlab.com"])
    run(["git", "checkout", "-b", branch])

    env = os.environ.copy()
    env["GEMINI_API_KEY"] = api_key
    command = [
        "aider", "--model", "gemini/gemini-3.6-flash",
        "--yes", "--no-auto-commits", "--map-tokens", "0", "--message-file", prompt_path,
        *allowed
    ]
    run(command, env=env)

    changed = set(subprocess.check_output(["git", "diff", "--name-only"], text=True).splitlines())
    untracked = set(subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], text=True).splitlines())
    changed |= untracked
    outside = sorted(changed - set(allowed))
    if outside:
        raise SystemExit("Agent changed paths outside its allowlist: " + ", ".join(outside))
    if not changed:
        print("Agent produced no file changes; no branch or MR will be published.")
        return

    # The worker, not the model, records that this task now has a review branch.
    queue_path = __import__("pathlib").Path("agent-control/queue.json")
    queue_data = json.loads(queue_path.read_text(encoding="utf-8"))
    for item in queue_data["tasks"]:
        if item["id"] == task_id:
            item["status"] = "in_review"
            item["agent_branch"] = branch
            break
    queue_path.write_text(json.dumps(queue_data, ensure_ascii=False, indent=2) + "\\n", encoding="utf-8")
    changed.add("agent-control/queue.json")
    run([sys.executable, "agent-control/validate_queue.py", "agent-control/queue.json"])
    run(["git", "diff", "--check"])
    run(["git", "add", "--", *sorted(changed)])
    run(["git", "commit", "-m", f"agent: {task_id}"])

    auth = base64.b64encode(("oauth2:" + token).encode()).decode()
    extra = "AUTHORIZATION: basic " + auth
    repo_url = f"https://{os.environ['CI_SERVER_HOST']}/{os.environ['CI_PROJECT_PATH']}.git"
    run(["git", "-c", f"http.extraheader={extra}", "push", repo_url, branch])

    api_url = f"{os.environ['CI_API_V4_URL']}/projects/{os.environ['CI_PROJECT_ID']}/merge_requests"
    mr = gitlab_api_post(api_url, token, {
        "source_branch": branch,
        "target_branch": "main",
        "title": f"Draft: {task_id}",
        "description": (
            "## Automated agent result\n"
            f"- Task: `{task_id}`\n"
            f"- Role: `{prepared['role']}`\n"
            f"- Changed paths: {', '.join(sorted(changed))}\n"
            "- Model: Gemini API Free via Aider\n"
            "- This is a Draft MR. No automatic merge is permitted.\n"
            "- Review the diff and run the required project checks before merging."
        ),
        "remove_source_branch": "false"
    })
    print("Draft Merge Request created:", mr.get("web_url", "URL unavailable"))

if __name__ == "__main__":
    main()

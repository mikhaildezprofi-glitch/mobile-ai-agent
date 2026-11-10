#!/usr/bin/env python3
"""Read-only GitLab project health report for scheduled CI."""
import json
import os
import pathlib
import urllib.request

PROJECT_ID = os.environ.get("CI_PROJECT_ID", "88757234")
BASE = f"https://gitlab.com/api/v4/projects/{PROJECT_ID}"

def get(path):
    request = urllib.request.Request(
        BASE + path,
        headers={"Accept": "application/json", "User-Agent": "mobile-ai-agent-monitor"}
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)

def main():
    lines = ["# MOBILE AI AGENT hourly report", ""]
    pipelines = []
    try:
        pipelines = get("/pipelines?per_page=8&order_by=id&sort=desc")
        lines += ["## Recent GitLab CI pipelines"]
        for item in pipelines:
            lines.append(
                f"- [{item['id']}]({item['web_url']}) · `{item['ref']}` · "
                f"**{item['status']}** · {item['created_at']}"
            )
        if not pipelines:
            lines.append("- No pipelines found.")
    except Exception as exc:
        lines += ["## GitLab CI", f"- ERROR: {type(exc).__name__}: {exc}"]

    agent_jobs = []
    for pipeline in pipelines[:8]:
        try:
            jobs = get(f"/pipelines/{pipeline['id']}/jobs?per_page=100")
            for job in jobs:
                if job.get("name") == "gitlab-ai-agent":
                    agent_jobs.append({
                        "pipeline": pipeline["id"],
                        "ref": pipeline.get("ref"),
                        "status": job.get("status"),
                        "url": job.get("web_url")
                    })
        except Exception as exc:
            lines.append(f"- Could not inspect jobs for pipeline {pipeline.get('id')}: {type(exc).__name__}")

    try:
        mrs = get("/merge_requests?state=opened&per_page=30&order_by=updated_at&sort=desc")
        lines += ["", "## Open Merge Requests"]
        for mr in mrs:
            author = mr.get("author", {}).get("username", "unknown")
            branch = mr.get("source_branch", "")
            if branch.startswith("agent/"):
                label = "agent branch; verify worker job"
            elif "agent" in author.lower() or "bot" in author.lower():
                label = "possible bot-authored MR"
            else:
                label = "human-authored or unverified"
            lines.append(
                f"- [!{mr['iid']} {mr['title']}]({mr['web_url']}) · "
                f"`{mr['detailed_merge_status']}` · `{branch}` · {label}"
            )
        if not mrs:
            lines.append("- No open MRs.")
    except Exception as exc:
        lines += ["", "## Merge Requests", f"- ERROR: {type(exc).__name__}: {exc}"]

    lines += ["", "## AI worker jobs"]
    if agent_jobs:
        for job in agent_jobs:
            lines.append(
                f"- Pipeline {job['pipeline']} · `{job['ref']}` · "
                f"**{job['status']}** · {job['url']}"
            )
    else:
        lines.append("- No `gitlab-ai-agent` job found in the latest eight pipelines.")
        lines.append("- Role files, queue entries, and ordinary CI jobs are not counted as active agents.")

    try:
        queue = json.loads(
            pathlib.Path("agent-control/queue.json").read_text(encoding="utf-8")
        )
        tasks = queue.get("tasks", [])
        counts = {}
        for task in tasks:
            status = task.get("status", "unknown")
            counts[status] = counts.get(status, 0) + 1
        lines += [
            "",
            "## Task queue",
            f"- Total: {len(tasks)}",
            f"- Status counts: {counts or 'empty'}"
        ]
        for task in tasks:
            lines.append(
                f"- `{task.get('id')}` · {task.get('role')} · "
                f"**{task.get('status')}** · {task.get('title')}"
            )
    except Exception as exc:
        lines += ["", "## Task queue", f"- ERROR: {type(exc).__name__}: {exc}"]

    lines += [
        "",
        "## Verification rule",
        "- A running agent requires a `gitlab-ai-agent` worker job with status `running`.",
        "- A completed agent run requires a successful worker job and a matching agent branch/Draft MR.",
        "- The report is read-only; it does not dispatch tasks, edit files, or merge MRs."
    ]
    report = "\n".join(lines) + "\n"
    pathlib.Path("agent-control/monitor-report.md").write_text(report, encoding="utf-8")
    print(report)

if __name__ == "__main__":
    main()

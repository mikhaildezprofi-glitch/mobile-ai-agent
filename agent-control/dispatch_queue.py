#!/usr/bin/env python3
"""Advance the serial agent queue only after an MR is merged with passing CI."""
import base64
import json
import os
import urllib.parse
import urllib.request

API = os.environ.get("CI_API_V4_URL", "https://gitlab.com/api/v4")
PROJECT = os.environ.get("CI_PROJECT_ID", "88757234")
TOKEN = os.environ.get("GITLAB_AGENT_TOKEN")
BRANCH = os.environ.get("AGENT_CONTROL_BRANCH", "sync/github-app-source-20261111")
SCHEDULE_DESCRIPTION = "MOBILE AI AGENT serial queue dispatcher"

def api(method, path, values=None):
    if not TOKEN:
        raise RuntimeError("GITLAB_AGENT_TOKEN is required")
    url = API + "/projects/" + urllib.parse.quote(str(PROJECT), safe="") + path
    data = urllib.parse.urlencode(values).encode() if values is not None else None
    request = urllib.request.Request(url, data=data, method=method, headers={
        "PRIVATE-TOKEN": TOKEN, "Accept": "application/json",
        "Content-Type": "application/x-www-form-urlencoded"
    })
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            body = response.read()
            return json.loads(body) if body else {}
    except Exception as exc:
        raise RuntimeError(f"GitLab API {method} failed: {type(exc).__name__}") from exc

def ensure_hourly_schedule():
    schedules = api("GET", "/pipeline_schedules?per_page=100")
    for item in schedules:
        if item.get("description") == SCHEDULE_DESCRIPTION and item.get("ref") == BRANCH:
            if not item.get("active", False):
                api("PUT", f"/pipeline_schedules/{item['id']}", {"active": "true"})
            print(f"Hourly queue schedule exists: {item['id']}")
            return
    created = api("POST", "/pipeline_schedules", {
        "description": SCHEDULE_DESCRIPTION,
        "ref": BRANCH,
        "cron": "0 * * * *",
        "cron_timezone": "UTC",
        "active": "true"
    })
    print(f"Created hourly queue schedule: {created.get('id', 'unknown')}")

def read_queue():
    file_path = urllib.parse.quote("agent-control/queue.json", safe="")
    ref = urllib.parse.quote(BRANCH, safe="")
    meta = api("GET", f"/repository/files/{file_path}?ref={ref}")
    content = base64.b64decode(meta["content"]).decode("utf-8")
    return json.loads(content), meta.get("last_commit_id")

def save_queue(queue, last_commit_id):
    file_path = urllib.parse.quote("agent-control/queue.json", safe="")
    api("PUT", f"/repository/files/{file_path}", {
        "branch": BRANCH,
        "content": json.dumps(queue, ensure_ascii=False, indent=2) + "\n",
        "commit_message": "ci(agent): advance serial task queue",
        "last_commit_id": last_commit_id or ""
    })

def find_task_mr(task):
    expected_branch = task.get("agent_branch") or ""
    results = api("GET", "/merge_requests?" + urllib.parse.urlencode({
        "state": "all", "scope": "all", "search": task["id"], "per_page": 100
    }))
    matches = []
    for mr in results:
        source = mr.get("source_branch", "")
        if (expected_branch and source == expected_branch) or (
            not expected_branch and source.startswith("agent/" + task["id"] + "-")
        ):
            matches.append(mr)
    if len(matches) > 1:
        raise RuntimeError(f"Multiple MRs found for {task['id']}; queue advancement stopped")
    return matches[0] if matches else None

def passing_pipeline_for_mr(mr):
    pipelines = api("GET", f"/merge_requests/{mr['iid']}/pipelines?per_page=30")
    source_sha = mr.get("sha")
    for pipeline in pipelines:
        if pipeline.get("sha") == source_sha and pipeline.get("status") == "success":
            return pipeline
    return None

def main():
    if os.environ.get("CI_COMMIT_REF_NAME") and os.environ["CI_COMMIT_REF_NAME"] != BRANCH:
        raise SystemExit("Dispatcher may run only on the protected control branch")
    ensure_hourly_schedule()
    queue, last_commit_id = read_queue()
    tasks = queue.get("tasks", [])
    active = [t for t in tasks if t.get("status") in {"queued", "in_review"}]
    if len(active) > 1:
        raise SystemExit("Serial queue invariant violated; no task was dispatched")

    changed = False
    if active:
        task = active[0]
        mr = find_task_mr(task)
        if mr:
            if mr.get("state") == "opened":
                if task.get("status") != "in_review" or task.get("agent_branch") != mr.get("source_branch"):
                    task["status"] = "in_review"
                    task["agent_branch"] = mr["source_branch"]
                    task["merge_request_iid"] = mr["iid"]
                    changed = True
                print(f"{task['id']}: waiting for human review and merge (MR !{mr['iid']})")
            elif mr.get("state") == "merged":
                pipeline = passing_pipeline_for_mr(mr)
                if not pipeline:
                    print(f"{task['id']}: merged, but no successful pipeline for source SHA; queue paused")
                else:
                    task["status"] = "completed"
                    task["merge_request_iid"] = mr["iid"]
                    task["merged_commit_sha"] = mr.get("merge_commit_sha")
                    task["validated_source_sha"] = mr.get("sha")
                    task["validated_pipeline_id"] = pipeline.get("id")
                    task["validated_pipeline_status"] = "success"
                    changed = True
                    next_task = next((t for t in tasks if t.get("status") == "blocked"), None)
                    if next_task:
                        next_task["status"] = "queued"
                        changed = True
                        print(f"{task['id']} completed; queued next task {next_task['id']}")
                    else:
                        print(f"{task['id']} completed; queue is empty")
            else:
                print(f"{task['id']}: MR is {mr.get('state')}, queue remains paused")
        elif task.get("status") == "in_review":
            print(f"{task['id']}: expected MR not found; queue paused")
        else:
            print(f"{task['id']}: queued; worker will start after dispatch stage")
    else:
        print("No active queue task; no task was auto-unblocked")

    if changed:
        save_queue(queue, last_commit_id)
        print("Queue state committed to protected control branch")
    with open("agent-control/queue.json", "w", encoding="utf-8") as handle:
        json.dump(queue, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("Queue dispatch check completed")

if __name__ == "__main__":
    main()

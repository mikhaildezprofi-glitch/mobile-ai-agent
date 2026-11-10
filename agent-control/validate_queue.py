#!/usr/bin/env python3
"""Validate the restrictive MOBILE AI AGENT task queue."""
import json
import pathlib
import re
import sys

ALLOWED_ROLES = {"coordinator", "android", "native-runtime", "cloud-build", "qa", "security", "research"}
ALLOWED_STATUS = {"queued", "blocked", "in_review", "completed", "failed"}
PROTECTED_PREFIXES = (
    ".git/", "app/src/main/assets/models/", "app/src/main/jniLibs/",
    "third_party/", "keystore/", "signing/"
)
REQUIRED = {"id", "title", "role", "status", "base_branch", "allowed_paths", "acceptance_criteria"}

def fail(message):
    print(f"QUEUE ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)

def main():
    path = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "agent-control/queue.json")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(str(exc))
    if data.get("schema_version") != 1:
        fail("unsupported schema_version")
    policy = data.get("policy", {})
    required_policy = {
        "max_parallel_tasks": 1,
        "require_tests_before_next_task": True,
        "require_merge_request": True,
        "allow_main_writes": False,
        "allow_destructive_actions": False,
    }
    for key, expected in required_policy.items():
        if policy.get(key) != expected:
            fail(f"unsafe policy value: {key}")
    tasks = data.get("tasks")
    if not isinstance(tasks, list):
        fail("tasks must be an array")
    seen = set()
    active = 0
    for task in tasks:
        if not isinstance(task, dict):
            fail("each task must be an object")
        missing = REQUIRED - set(task)
        if missing:
            fail("task missing fields: " + ", ".join(sorted(missing)))
        task_id = task["id"]
        if not isinstance(task_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,47}", task_id):
            fail(f"invalid task id: {task_id!r}")
        if task_id in seen:
            fail(f"duplicate task id: {task_id}")
        seen.add(task_id)
        if task["role"] not in ALLOWED_ROLES or task["status"] not in ALLOWED_STATUS:
            fail(f"unsupported role or status in {task_id}")
        if task["base_branch"] != "main":
            fail(f"{task_id}: tasks must be based on main")
        if task["status"] in {"queued", "in_review"}:
            active += 1
        paths = task["allowed_paths"]
        if not isinstance(paths, list) or not paths:
            fail(f"{task_id}: allowed_paths must be a non-empty array")
        for item in paths:
            if not isinstance(item, str) or item.startswith("/") or ".." in pathlib.PurePosixPath(item).parts:
                fail(f"{task_id}: unsafe path {item!r}")
            if any(item.startswith(prefix) for prefix in PROTECTED_PREFIXES):
                fail(f"{task_id}: protected path {item}")
        if not isinstance(task["acceptance_criteria"], list) or not task["acceptance_criteria"]:
            fail(f"{task_id}: acceptance_criteria must be a non-empty array")
    if active > 1:
        fail("serial policy violation: more than one queued/in_review task")
    print(f"QUEUE OK: {len(tasks)} task(s); {active} active; serial execution policy verified")

if __name__ == "__main__":
    main()

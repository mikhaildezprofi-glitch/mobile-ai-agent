#!/data/data/com.termux/files/usr/bin/python
import fcntl, json, os, re, subprocess, sys, time
from datetime import datetime, timezone
from pathlib import Path

HOME = Path.home()
PROJECT = Path("/storage/emulated/0/Download/MOBILE_AI_AGENT")
QUEUE = HOME / "mobile-ai-agent-queue"
QUEUE_DIR = QUEUE / "agent-queue"
COMMANDS = QUEUE_DIR / "commands"
RESULTS = QUEUE_DIR / "results"
LOG = HOME / "mobile_ai_cloud_worker.log"
HEARTBEAT_FILE = QUEUE_DIR / "device-heartbeat.json"
HEARTBEAT_STATE = HOME / ".mobile_ai_heartbeat_epoch"
HEARTBEAT_INTERVAL_SECONDS = 900
ALLOWED = {"collect_project_status", "static_audit"}

def log(message):
    stamp = datetime.now().astimezone().isoformat(timespec="seconds")
    with LOG.open("a", encoding="utf-8") as f:
        f.write(f"[{stamp}] {message}\n")

def git(*args, check=True):
    return subprocess.run(["git", "-C", str(QUEUE), *args], text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          check=check, timeout=45).stdout.strip()

def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        return {"read_error": str(exc)}

def run_check(command):
    p = subprocess.run(command, cwd=PROJECT, text=True, stdout=subprocess.PIPE,
                        stderr=subprocess.STDOUT, timeout=20)
    return {"exit_code": p.returncode, "output": p.stdout[-5000:]}

def run_quiet(command, timeout=8):
    try:
        p = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, timeout=timeout)
        return p.returncode, p.stdout.strip()
    except Exception as exc:
        return 127, type(exc).__name__

def sync_queue():
    """Synchronize without discarding locally committed results during outages."""
    git("fetch", "origin", "agent-queue")
    dirty = git("status", "--porcelain")
    if dirty:
        log("QUEUE sync deferred: local worktree contains uncommitted changes; preserving them")
        return
    counts = git("rev-list", "--left-right", "--count", "HEAD...origin/agent-queue")
    ahead, behind = (int(value) for value in counts.split())
    if ahead == 0 and behind == 0:
        return
    if ahead == 0:
        git("merge", "--ff-only", "origin/agent-queue")
    elif behind == 0:
        git("push", "origin", "HEAD:agent-queue")
    else:
        try:
            git("rebase", "origin/agent-queue")
            git("push", "origin", "HEAD:agent-queue")
        except Exception:
            git("rebase", "--abort", check=False)
            raise


def heartbeat_due():
    try:
        last = float(HEARTBEAT_STATE.read_text(encoding="utf-8").strip())
    except Exception:
        last = 0
    return time.time() - last >= HEARTBEAT_INTERVAL_SECONDS

def publish_heartbeat():
    if not heartbeat_due():
        return

    wifi_code, wifi_output = run_quiet(["termux-wifi-connectioninfo"])
    try:
        wifi_info = json.loads(wifi_output) if wifi_code == 0 else {}
    except Exception:
        wifi_info = {}
    wifi_connected = bool(wifi_info.get("ip")) and wifi_info.get("supplicant_state") == "COMPLETED"
    interface = "wifi" if wifi_connected else "cellular_or_unknown"

    # Wireless ADB is intentionally isolated on port 5038 inside Ubuntu PRoot.
    # The default Termux ADB server on 5037 is unrelated and must not be mistaken for it.
    adb_code, adb_output = run_quiet([
        "proot-distro", "login", "ubuntu", "--",
        "/root/alt-adb/platform-tools/adb", "-P", "5038", "devices", "-l"
    ], timeout=12)
    adb_state = "unavailable"
    adb_connected = False
    if adb_code == 0:
        states = [line.split()[1] for line in adb_output.splitlines()[1:]
                  if len(line.split()) >= 2]
        if "device" in states:
            adb_state = "device"
            adb_connected = True
        elif "offline" in states:
            adb_state = "offline"
        else:
            adb_state = "no-device"

    monitor_code, _ = run_quiet(["pgrep", "-f", "[m]obile_ai_monitor.sh"])
    heartbeat = {
        "schema_version": 1,
        "checked_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "termux_worker_running": True,
        "monitor_running": monitor_code == 0,
        "wifi_connected": wifi_connected,
        "network_interface": interface,
        "adb_connected": adb_connected,
        "adb_state": adb_state,
        "project_accessible": PROJECT.is_dir(),
        "executor": "termux-cloud-worker"
    }
    HEARTBEAT_FILE.write_text(
        json.dumps(heartbeat, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8"
    )
    git("add", str(HEARTBEAT_FILE.relative_to(QUEUE)))
    git("-c", "user.name=MOBILE AI Termux Worker",
        "-c", "user.email=termux-worker@users.noreply.github.com",
        "commit", "-m", "Update Android device heartbeat")
    # Rate-limit heartbeat commits even while the network is temporarily offline.
    # The unpushed commit is retained and sync_queue() retries it next cycle.
    HEARTBEAT_STATE.write_text(str(time.time()), encoding="utf-8")

    try:
        git("push", "origin", "HEAD:agent-queue")
    except Exception:
        git("fetch", "origin", "agent-queue")
        try:
            git("rebase", "origin/agent-queue")
        except Exception:
            git("rebase", "--abort", check=False)
            raise
        git("push", "origin", "HEAD:agent-queue")

    log("HEARTBEAT pushed: wifi=%s interface=%s adb=%s monitor=%s" % (
        wifi_connected, interface, adb_state, monitor_code == 0
    ))

def execute(task):
    action = task.get("action")
    result = {
        "task_id": task["task_id"],
        "action": action,
        "status": "completed",
        "executed_at": datetime.now(timezone.utc).isoformat(),
        "executor": "Termux local allowlist worker",
    }
    if action == "collect_project_status":
        status = read_json(PROJECT / "monitor_status.json")
        live = read_json(PROJECT / "monitor_live.json")
        apk = PROJECT / "app/build/outputs/apk/debug/app-debug.apk"
        result.update({
            "build_status": status,
            "live_status": live,
            "apk": {"exists": apk.is_file(), "bytes": apk.stat().st_size if apk.is_file() else 0},
            "git_branch": git("branch", "--show-current"),
            "tracked_changes": git("status", "--short").splitlines()[:100],
            "recent_monitor_log": (HOME / "mobile_ai_monitor.log").read_text(encoding="utf-8", errors="replace")[-6000:]
                if (HOME / "mobile_ai_monitor.log").exists() else "",
        })
    elif action == "static_audit":
        checks = {
            "monitor_shell_syntax": run_check(["bash", "-n", str(HOME / "mobile_ai_monitor.sh")]),
            "cloud_queue_shell_syntax": run_check(["bash", "-n", str(HOME / "mobile_ai_queue_cloud_event.sh")]),
            "build_wrapper_syntax": run_check(["bash", "-n", str(PROJECT / "build_and_report.sh")]),
            "git_diff_check": run_check(["git", "diff", "--check"]),
        }
        source = PROJECT / "app/src/main/java/com/mikhaildezprofi/mobileaiagent/ui/MainScreen.kt"
        if source.exists():
            lines = source.read_text(encoding="utf-8", errors="replace").splitlines()
            matches = [(i + 1, line.strip()) for i, line in enumerate(lines)
                       if "visionReady" in line or "loadVisionProjector" in line or "LaunchedEffect(engine" in line]
            checks["vision_readiness_references"] = matches[:60]
        result["checks"] = checks
        result["all_checks_passed"] = all(
            value.get("exit_code", 0) == 0 for value in checks.values() if isinstance(value, dict)
        )
    else:
        raise ValueError("Action is not allowlisted")
    return result

def main():
    QUEUE.mkdir(parents=True, exist_ok=True)
    if not (QUEUE / ".git").exists():
        raise RuntimeError("Queue worktree is not initialized")
    subprocess.run(["git", "config", "--global", "--add", "safe.directory", str(QUEUE)],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    sync_queue()
    COMMANDS.mkdir(parents=True, exist_ok=True)
    RESULTS.mkdir(parents=True, exist_ok=True)

    try:
        publish_heartbeat()
    except Exception as exc:
        log(f"HEARTBEAT ERROR: {type(exc).__name__}: {exc}")

    handled = 0
    for path in sorted(COMMANDS.glob("*.json")):
        if handled >= 3:
            break
        try:
            task = json.loads(path.read_text(encoding="utf-8"))
            task_id = task.get("task_id", "")
            if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", task_id):
                log(f"REJECT {path.name}: invalid task_id")
                continue
            if path.stem != task_id:
                log(f"REJECT {path.name}: filename/task_id mismatch")
                continue
            if task.get("action") not in ALLOWED:
                log(f"REJECT {task_id}: unsupported action {task.get('action')!r}")
                continue
            output = RESULTS / f"{task_id}.json"
            if output.exists():
                continue
            log(f"START {task_id} action={task['action']}")
            try:
                payload = execute(task)
            except Exception as exc:
                payload = {"task_id": task_id, "action": task.get("action"),
                           "status": "error", "error": f"{type(exc).__name__}: {exc}",
                           "executed_at": datetime.now(timezone.utc).isoformat()}
            output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            git("add", str(output.relative_to(QUEUE)))
            git("-c", "user.name=MOBILE AI Termux Worker",
                "-c", "user.email=termux-worker@users.noreply.github.com",
                "commit", "-m", f"Return local result {task_id}")
            try:
                git("push", "origin", "HEAD:agent-queue")
            except Exception:
                git("fetch", "origin", "agent-queue")
                git("rebase", "origin/agent-queue")
                git("push", "origin", "HEAD:agent-queue")
            log(f"DONE {task_id}: result committed and pushed")
            handled += 1
        except Exception as exc:
            log(f"ERROR {path.name}: {type(exc).__name__}: {exc}")
    if handled:
        log(f"Processed {handled} command(s)")

if __name__ == "__main__":
    try:
        # Serialize queue mutations with the monitor's event publisher.
        with (HOME / ".mobile_ai_queue.lock").open("a", encoding="utf-8") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            main()
    except Exception as exc:
        log(f"WORKER ERROR: {type(exc).__name__}: {exc}")
        sys.exit(1)

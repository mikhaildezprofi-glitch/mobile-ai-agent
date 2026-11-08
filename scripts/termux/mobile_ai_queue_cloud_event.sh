#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PROJECT="/storage/emulated/0/Download/MOBILE_AI_AGENT"
QUEUE="$HOME/mobile-ai-agent-queue"
STATUS="$PROJECT/monitor_status.json"
STATE="${1:-UNKNOWN}"
LOG="$HOME/mobile_ai_monitor.log"

if [ ! -e "$QUEUE/.git" ]; then
  git -C "$PROJECT" worktree add "$QUEUE" agent-queue
fi

git -C "$QUEUE" fetch origin agent-queue
git -C "$QUEUE" reset --hard origin/agent-queue

EVENT_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -p "$QUEUE/agent-queue"
python3 - "$STATUS" "$QUEUE/agent-queue/event-$EVENT_ID.json" "$STATE" "$EVENT_ID" <<'PY'
import json, sys
source, target, state, event_id = sys.argv[1:]
try:
    with open(source, encoding="utf-8") as f:
        raw = json.load(f)
except Exception:
    raw = {}
allowed = ("version_code", "version_name", "exit_code", "result", "completed_at",
           "installed_at", "launch", "fatal_exception", "thermal_status",
           "battery_percent", "battery_temperature_c", "next_step")
payload = {
    "task_id": event_id,
    "action": "repository_health_check",
    "project": "MOBILE_AI_AGENT",
    "device_state": state,
    "created_by": "termux-monitor",
}
for key in allowed:
    if key in raw:
        payload[key] = raw[key]
with open(target, "w", encoding="utf-8") as f:
    json.dump(payload, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY

git -C "$QUEUE" add "agent-queue/event-$EVENT_ID.json"
git -C "$QUEUE" -c user.name="MOBILE AI Termux Monitor" -c user.email="termux-monitor@users.noreply.github.com" commit -m "Queue device event $STATE"
git -C "$QUEUE" push origin HEAD:agent-queue
echo "[$(date '+%F %T')] Cloud event queued: $STATE ($EVENT_ID)" >> "$LOG"

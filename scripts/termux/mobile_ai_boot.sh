#!/data/data/com.termux/files/usr/bin/bash
# Termux:Boot supervisor for the MOBILE AI local monitor and cloud queue worker.
set -u
export HOME="/data/data/com.termux/files/home"
export PREFIX="/data/data/com.termux/files/usr"
export PATH="$PREFIX/bin:$PREFIX/bin/applets:/system/bin"
PROJECT="/storage/emulated/0/Download/MOBILE_AI_AGENT"
QUEUE="$HOME/mobile-ai-agent-queue"
LOG="$HOME/mobile_ai_boot.log"
mkdir -p "$HOME/.termux/boot"
sleep 45

while true; do
  if [ -d "$PROJECT/.git" ]; then
    cp "$PROJECT/scripts/termux/mobile_ai_cloud_worker.py" "$HOME/mobile_ai_cloud_worker.py" 2>/dev/null || true
    cp "$PROJECT/scripts/termux/mobile_ai_cloud_worker.sh" "$HOME/mobile_ai_cloud_worker.sh" 2>/dev/null || true
    cp "$PROJECT/scripts/termux/mobile_ai_monitor.sh" "$HOME/mobile_ai_monitor.sh" 2>/dev/null || true
    cp "$PROJECT/scripts/termux/mobile_ai_queue_cloud_event.py" "$HOME/mobile_ai_queue_cloud_event.py" 2>/dev/null || true
    cp "$PROJECT/scripts/termux/mobile_ai_queue_cloud_event.sh" "$HOME/mobile_ai_queue_cloud_event.sh" 2>/dev/null || true
    chmod 700 "$HOME"/mobile_ai_*.sh "$HOME"/mobile_ai_*.py 2>/dev/null || true

    if [ ! -e "$QUEUE/.git" ]; then
      git -C "$PROJECT" worktree prune >> "$LOG" 2>&1 || true
      git -C "$PROJECT" worktree add --force "$QUEUE" agent-queue >> "$LOG" 2>&1 || true
    fi

    if [ -e "$QUEUE/.git" ] && ! pgrep -f '[m]obile_ai_monitor.sh' >/dev/null 2>&1; then
      echo "[$(date -Iseconds)] Starting MOBILE AI monitor" >> "$LOG"
      nohup "$HOME/mobile_ai_monitor.sh" >> "$HOME/mobile_ai_monitor.log" 2>&1 </dev/null &
    fi
  else
    echo "[$(date -Iseconds)] Project folder unavailable: $PROJECT" >> "$LOG"
  fi
  sleep 60
done

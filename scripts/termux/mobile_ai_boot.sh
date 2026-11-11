#!/data/data/com.termux/files/usr/bin/bash
# Termux:Boot supervisor for the local MOBILE AI device monitor.
set -u
export HOME="/data/data/com.termux/files/home"
export PREFIX="/data/data/com.termux/files/usr"
export PATH="$PREFIX/bin:$PREFIX/bin/applets:/system/bin"
PROJECT="/storage/emulated/0/Download/MOBILE_AI_AGENT"
LOG="$HOME/mobile_ai_boot.log"

sleep 45
while true; do
  if [ -d "$PROJECT/.git" ]; then
    cp "$PROJECT/scripts/termux/mobile_ai_monitor.sh" "$HOME/mobile_ai_monitor.sh" 2>/dev/null || true
    chmod 700 "$HOME/mobile_ai_monitor.sh" 2>/dev/null || true

    if ! pgrep -f '[m]obile_ai_monitor.sh' >/dev/null 2>&1; then
      echo "[$(date -Iseconds)] Starting MOBILE AI device monitor" >> "$LOG"
      nohup "$HOME/mobile_ai_monitor.sh" >> "$HOME/mobile_ai_monitor.log" 2>&1 </dev/null &
    fi
  else
    echo "[$(date -Iseconds)] Project folder unavailable: $PROJECT" >> "$LOG"
  fi
  sleep 60
done

#!/data/data/com.termux/files/usr/bin/bash
set -u
WORKER="$HOME/mobile_ai_cloud_worker.py"
LOG="$HOME/mobile_ai_cloud_worker.log"
while true; do
  /data/data/com.termux/files/usr/bin/python "$WORKER" >> "$LOG" 2>&1 || true
  sleep 30
done

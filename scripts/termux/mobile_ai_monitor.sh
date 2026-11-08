#!/data/data/com.termux/files/usr/bin/bash
set -u
PROJECT="/storage/emulated/0/Download/MOBILE_AI_AGENT"
STATUS="$PROJECT/monitor_status.json"
LOG="$HOME/mobile_ai_monitor.log"
CLOUD_QUEUE="$HOME/mobile_ai_queue_cloud_event.sh"
WORKER="$HOME/mobile_ai_cloud_worker.sh"
LAST_STATE=""
LAST_ADB_STATE=""
LAST_WIFI_STATE=""
echo "[$(date '+%F %T')] Monitor v4 started" >> "$LOG"

while true; do
  NOW="$(date -Iseconds)"

  # Keep the local queue worker alive while Termux itself is running.
  if ! pgrep -f '[m]obile_ai_cloud_worker.sh' >/dev/null 2>&1; then
    nohup "$WORKER" >> "$HOME/mobile_ai_cloud_worker.log" 2>&1 </dev/null &
    echo "[$NOW] Cloud worker restarted by monitor" >> "$LOG"
  fi

  BUILD="$(pgrep -af 'GradleMain.*assembleDebug|proot.*assembleDebug' | grep -v 'mobile_ai_monitor' | grep -v 'monitor_status' || true)"
  BATTERY="$(termux-battery-status 2>/dev/null)"
  TEMP="$(printf '%s' "$BATTERY" | sed -n 's/.*"temperature":[[:space:]]*\([0-9.]*\).*/\1/p' | head -1)"
  THERMAL="$(adb shell dumpsys thermalservice 2>/dev/null | grep -m1 -E 'Thermal Status|mStatus' | tr '\n' ' ' || true)"
  MEM="$(awk '/MemAvailable:/ {printf "%.0f", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)"
  LOAD="$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null || echo n/a)"

  WIFI_INFO="$(termux-wifi-connectioninfo 2>/dev/null || echo '{}')"
  IFACE="cellular_or_unknown"
  if printf '%s' "$WIFI_INFO" | grep -q '"ip"[[:space:]]*:[[:space:]]*"[^"]\+"' && printf '%s' "$WIFI_INFO" | grep -q '"supplicant_state"[[:space:]]*:[[:space:]]*"COMPLETED"'; then
    WIFI_STATE="CONNECTED"
    IFACE="wifi"
  else
    WIFI_STATE="DISCONNECTED"
  fi

  ADB_OUTPUT="$(adb devices 2>/dev/null || true)"
  if printf '%s\n' "$ADB_OUTPUT" | awk 'NR>1 && $2=="device" {found=1} END {exit !found}'; then
    ADB_STATE="CONNECTED"
  elif printf '%s\n' "$ADB_OUTPUT" | awk 'NR>1 && $2=="offline" {found=1} END {exit !found}'; then
    ADB_STATE="OFFLINE"
  else
    ADB_STATE="DISCONNECTED"
  fi

  if [ -n "$BUILD" ]; then STATE="BUILD_RUNNING"; CONTENT="Gradle выполняется. RAM: $MEM MB. Батарея: $TEMP C."
  elif [ -f "$STATUS" ]; then STATE="$(grep -o '"state"[[:space:]]*:[[:space:]]*"[^"]*"' "$STATUS" | head -1 | cut -d'"' -f4)"; CONTENT="Состояние: $STATE. RAM: $MEM MB. Батарея: $TEMP C."
  else STATE="IDLE"; CONTENT="Ожидание. RAM: $MEM MB. Батарея: $TEMP C."; fi

  printf '{"project":"MOBILE_AI_AGENT","state":"%s","checked_at":"%s","free_ram_mb":%s,"battery_temperature_c":"%s","thermal":"%s","load":"%s","wifi_connected":%s,"network_interface":"%s","adb_state":"%s"}\n' \
    "$STATE" "$NOW" "$MEM" "$TEMP" "$(printf '%s' "$THERMAL" | tr '"' "'")" "$LOAD" \
    "$([ "$WIFI_STATE" = CONNECTED ] && echo true || echo false)" "$IFACE" "$ADB_STATE" > "$PROJECT/monitor_live.json"

  if [ "$STATE" != "$LAST_STATE" ]; then
    echo "[$NOW] STATE=$STATE $CONTENT" >> "$LOG"
    case "$STATE" in
      BUILD_SUCCESS)
        termux-notification --id 7738 --title "MOBILE AI: сборка успешна" --content "$CONTENT Cloud-отчёт отправляется." --priority high --sound
        "$CLOUD_QUEUE" "$STATE" >> "$LOG" 2>&1 || echo "[$NOW] Cloud queue failed for $STATE" >> "$LOG"
        ;;
      BUILD_FAILED)
        termux-notification --id 7738 --title "MOBILE AI: ошибка сборки" --content "$CONTENT Cloud-отчёт отправляется." --priority high --sound
        "$CLOUD_QUEUE" "$STATE" >> "$LOG" 2>&1 || echo "[$NOW] Cloud queue failed for $STATE" >> "$LOG"
        ;;
      INSTALLED_LAUNCHED_NO_FATAL)
        termux-notification --id 7738 --title "MOBILE AI: проверка установки завершена" --content "$CONTENT Cloud-отчёт отправляется." --priority high
        "$CLOUD_QUEUE" "$STATE" >> "$LOG" 2>&1 || echo "[$NOW] Cloud queue failed for $STATE" >> "$LOG"
        ;;
      BUILD_RUNNING)
        termux-notification --id 7738 --title "MOBILE AI: сборка выполняется" --content "$CONTENT" --ongoing
        ;;
    esac
    LAST_STATE="$STATE"
  fi

  if [ -n "$LAST_ADB_STATE" ] && [ "$ADB_STATE" != "$LAST_ADB_STATE" ]; then
    if [ "$ADB_STATE" = CONNECTED ]; then
      termux-notification --id 7740 --title "MOBILE AI: ADB восстановлен" --content "Беспроводная отладка снова доступна." --priority high
    else
      termux-notification --id 7740 --title "MOBILE AI: ADB недоступен" --content "Проверьте беспроводную отладку. Облачные задачи продолжат работу." --priority high --sound
    fi
    echo "[$NOW] ADB state changed: $LAST_ADB_STATE -> $ADB_STATE" >> "$LOG"
  fi
  LAST_ADB_STATE="$ADB_STATE"

  if [ -n "$LAST_WIFI_STATE" ] && [ "$WIFI_STATE" != "$LAST_WIFI_STATE" ]; then
    echo "[$NOW] Wi-Fi state changed: $LAST_WIFI_STATE -> $WIFI_STATE (route=$IFACE)" >> "$LOG"
  fi
  LAST_WIFI_STATE="$WIFI_STATE"

  sleep 15
done

#!/data/data/com.termux/files/usr/bin/bash
set +e
PROJECT="/storage/emulated/0/Download/MOBILE_AI_AGENT"
STATUS="$PROJECT/monitor_status.json"
APK="$PROJECT/app/build/outputs/apk/debug/app-debug.apk"
cd "$PROJECT" || exit 90
printf '{"project":"MOBILE_AI_AGENT","state":"BUILD_RUNNING","started_at":"%s"}\n' "$(date -Iseconds)" > "$STATUS"
proot-distro login ubuntu -- bash -lc 'cd /storage/emulated/0/Download/MOBILE_AI_AGENT && /opt/gradle-8.13/bin/gradle --no-daemon assembleDebug'
RC=$?
if [ "$RC" -eq 0 ] && [ -f "$APK" ]; then
  SIZE=$(stat -c '%s' "$APK")
  printf '{"project":"MOBILE_AI_AGENT","state":"BUILD_SUCCESS","result":"BUILD SUCCESSFUL","exit_code":0,"apk":"%s","apk_bytes":%s,"completed_at":"%s","next_step":"Install and verify APK"}\n' "$APK" "$SIZE" "$(date -Iseconds)" > "$STATUS"
  termux-notification --id 7738 --title "MOBILE AI: сборка успешна" --content "BUILD SUCCESSFUL. APK: $SIZE байт. Вернись в чат и напиши Продолжай." --priority high --sound
else
  printf '{"project":"MOBILE_AI_AGENT","state":"BUILD_FAILED","result":"BUILD FAILED","exit_code":%s,"completed_at":"%s","next_step":"Read Gradle error log and fix"}\n' "$RC" "$(date -Iseconds)" > "$STATUS"
  termux-notification --id 7738 --title "MOBILE AI: ошибка сборки" --content "Gradle exit code: $RC. Вернись в чат и напиши Продолжай." --priority high --sound
fi
exit "$RC"

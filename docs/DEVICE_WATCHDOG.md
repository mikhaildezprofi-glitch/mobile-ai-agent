# MOBILE AI Agent: phone availability and cloud queue

## Operating model

- GitHub Actions runs the device watchdog hourly (at minute 17).
- The Termux worker publishes a small heartbeat to `agent-queue/device-heartbeat.json` every 15 minutes.
- The cloud watchdog checks pending commands and the latest heartbeat. A heartbeat older than 75 minutes is treated as a missing Termux worker.
- The heartbeat reports Termux worker, local monitor, Wi-Fi, and ADB state. It does not include account tokens, file contents, or arbitrary logs.
- Wi-Fi is not mandatory when mobile data keeps Termux online. A task is blocked by Wi-Fi only when its queue JSON explicitly sets `requires_wifi: true`.
- A task is blocked by ADB only when its queue JSON explicitly sets `requires_adb: true`.
- Pending queue commands require Termux. If Termux is unavailable, the cloud workflow opens one assigned GitHub issue and mentions the repository owner. It closes that issue after the required channel recovers.
- GitHub Actions work that does not require the phone is independent of phone availability.

## Termux processes

- `mobile_ai_monitor.sh` checks local state every 15 seconds and restarts the queue worker if it exits.
- `mobile_ai_cloud_worker.sh` polls the GitHub queue every 30 seconds.
- `mobile_ai_cloud_worker.py` handles the existing allowlisted local actions and publishes the heartbeat every 15 minutes.
- The monitor uses Termux:API `termux-wifi-connectioninfo` to determine Wi-Fi state and `adb devices` for ADB state.

## Notifications

The cloud watchdog creates or updates a single issue when a pending task needs an unavailable phone channel. The issue is assigned to the repository owner and mentions them. Enable GitHub mobile push notifications and watch this repository to receive the alert on Android. If the phone is offline at the moment, GitHub delivers the notification after internet access returns.

## Queue task flags

Optional booleans in `agent-queue/commands/<task_id>.json`:

- `requires_adb: true` for tasks that need Android Debug Bridge.
- `requires_wifi: true` only for tasks that specifically need Wi-Fi. Normal Termux/Git operations may use mobile data.

All pending commands require the Termux queue worker by default.

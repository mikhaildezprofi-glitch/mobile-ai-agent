# Legacy Termux device worker

These scripts are preserved for optional phone-side device operations. They were stopped on 2026-11-09 to reduce background activity and battery use while development moves to GitHub Actions/cloud agents.

- `mobile_ai_cloud_worker.py` polls the `agent-queue` worktree and accepts only `collect_project_status` and `static_audit`. It does not edit app source or build APKs.
- `mobile_ai_cloud_worker.sh` runs the worker every 30 seconds.
- `mobile_ai_monitor.sh` samples battery, RAM, Wi-Fi and ADB every 15 seconds, sends Termux notifications, and automatically starts the worker if absent.
- `mobile_ai_queue_cloud_event.sh` writes device-state events to the `agent-queue` branch.

Do not configure these scripts for automatic startup by default. Start them only when a task explicitly requires phone telemetry or ADB. The monitor's auto-start behavior means starting the monitor also starts the worker.

The scripts assume the project remains at `/storage/emulated/0/Download/MOBILE_AI_AGENT` and the queue worktree at `$HOME/mobile-ai-agent-queue`.

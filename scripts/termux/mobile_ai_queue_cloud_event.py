#!/data/data/com.termux/files/usr/bin/python
import fcntl, json, os, subprocess, sys
from datetime import datetime, timezone
from pathlib import Path

HOME = Path.home()
PROJECT = Path('/storage/emulated/0/Download/MOBILE_AI_AGENT')
QUEUE = HOME / 'mobile-ai-agent-queue'

def git(*args):
    result = subprocess.run(['git', '-C', str(QUEUE), *args], text=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=45)
    if result.returncode:
        raise RuntimeError(result.stdout[-2000:])
    return result.stdout.strip()

def main():
    state = (sys.argv[1] if len(sys.argv) > 1 else 'UNKNOWN')[:80]
    live_path = PROJECT / 'monitor_live.json'
    status_path = PROJECT / 'monitor_status.json'
    live = {}
    status = {}
    try: live = json.loads(live_path.read_text(encoding='utf-8'))
    except Exception: pass
    try: status = json.loads(status_path.read_text(encoding='utf-8'))
    except Exception: pass
    now = datetime.now(timezone.utc)
    task_id = f"event-{now.strftime('%Y%m%dT%H%M%SZ')}-{os.getpid()}"
    next_step = {
        'BUILD_FAILED': 'Inspect the cloud build report and identify the first actionable compiler or native-linker error.',
        'BUILD_SUCCESS': 'Verify the APK artifact and retain the build report.',
        'INSTALLED_LAUNCHED_NO_FATAL': 'Review the launch diagnostics and confirm runtime initialization.',
        'BUILD_RUNNING': 'Leave compilation on GitHub Actions; do not start a competing local build.',
    }.get(state, 'Collect a fresh repository and device status report.')
    event = {
        'task_id': task_id,
        'action': 'repository_health_check',
        'project': 'MOBILE_AI_AGENT',
        'device_state': state,
        'version_code': status.get('version_code'),
        'version_name': status.get('version_name'),
        'launch': status.get('launch'),
        'fatal_exception': status.get('fatal_exception'),
        'thermal_status': str(live.get('thermal', 'unknown'))[:200],
        'battery_temperature_c': live.get('battery_temperature_c'),
        'next_step': next_step,
        'created_at': now.isoformat(timespec='seconds'),
    }
    event = {k: v for k, v in event.items() if v is not None}
    out = QUEUE / 'agent-queue' / f'{task_id}.json'
    out.write_text(json.dumps(event, ensure_ascii=False, indent=2) + '\\n', encoding='utf-8')
    git('add', str(out.relative_to(QUEUE)))
    git('-c', 'user.name=MOBILE AI Termux Monitor',
        '-c', 'user.email=termux-worker@users.noreply.github.com',
        'commit', '-m', f'Report device event {state}')
    try:
        git('push', 'origin', 'HEAD:agent-queue')
    except Exception:
        git('fetch', 'origin', 'agent-queue')
        try:
            git('rebase', 'origin/agent-queue')
        except Exception:
            git('rebase', '--abort')
            raise
        git('push', 'origin', 'HEAD:agent-queue')
    print(f'Queued sanitized cloud event {task_id} ({state})')

if __name__ == '__main__':
    lock_path = HOME / '.mobile_ai_queue.lock'
    with lock_path.open('a', encoding='utf-8') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        main()

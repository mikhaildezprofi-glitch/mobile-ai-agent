#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 "$ROOT/scripts/validate-runtime-config.py"
python3 -m json.tool "$ROOT/runtime-config/manifest.json" >/dev/null

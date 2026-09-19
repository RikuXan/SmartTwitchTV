#!/usr/bin/env bash
set -euo pipefail

repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
capture="$(date -u +%Y%m%dT%H%M%SZ)-$$"
destination="$repo/.tmp/logs/device/$capture"
mkdir -p "$destination"
docker exec stv-logcat adb pull \
    /sdcard/Android/data/com.fgl27.twitch/files/logs \
    "/logs/device/$capture/"
printf 'Saved diagnostics to %s/logs\n' "$destination"

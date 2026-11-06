#!/bin/bash
# Owner standing rule (2026-11-01, 5th report): auto-rotate ON is never a wanted state on the
# test phones. The app cannot write it (no WRITE_SETTINGS; zero Settings.System writes), so an
# ON state is always injected test input hitting the Quick Settings tile. Run this after ANY
# ad-hoc input session and at the end of every device session.
set -euo pipefail

ADB_BIN="${ADB_BIN:-$HOME/Library/Android/sdk/platform-tools/adb}"
DEV="${1:?usage: rotation-guard.sh <device-serial>}"

ROT_USER=$("$ADB_BIN" -s "$DEV" shell settings get system user_rotation | tr -d '\r')
case "$ROT_USER" in ''|null) ROT_USER=0 ;; esac

"$ADB_BIN" -s "$DEV" shell settings put system accelerometer_rotation 0
"$ADB_BIN" -s "$DEV" shell settings put system user_rotation "$ROT_USER"

ACC=$("$ADB_BIN" -s "$DEV" shell settings get system accelerometer_rotation | tr -d '\r')
echo "rotation guard: $DEV accelerometer_rotation=$ACC user_rotation=$ROT_USER"
[ "$ACC" = "0" ]

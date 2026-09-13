#!/bin/bash
# Slice 3 VPN PoC spike — on-device gate runner (plan §7 step 3 / §8.4).
# Gates: routes (10.14.14.1/32 + fd00:14:14::1/128), NXDOMAIN for a blocked domain,
# allowed domain resolves, HTTPS works, IPv6 sinkhole path, Private DNS detected,
# stop restores connectivity.
# Hard gate: must pass on the X6532 AND one more physical device before Slice 4.
#
# Prerequisites:
#   1. APK built (./gradlew :app:assembleDebug) — pass --build to do it here.
#   2. MyExodus whitelisted in XOS battery/app management (Hiber freezer), done once.
#   3. Device unlocked, screen on.
set -euo pipefail

export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
ADB="${ADB:-adb}"
APP="com.example.myexodus"
APP_APK="app/build/outputs/apk/debug/app-debug.apk"

if [[ "${1:-}" == "--build" ]]; then
  ./gradlew :app:assembleDebug -q
fi

echo "==> installing APK (unchanged APKs are skipped — XOS revokes grants on reinstall)"
local_md5=$(md5 -q "$APP_APK")
remote=$("$ADB" shell pm path "$APP" 2>/dev/null | head -1 | sed 's/^package://' | tr -d '\r')
if [ -n "$remote" ]; then
  remote_md5=$("$ADB" shell md5sum "$remote" 2>/dev/null | awk '{print $1}' | tr -d '\r')
  if [ -n "$remote_md5" ] && [ "$remote_md5" = "$local_md5" ]; then
    echo "    $APP unchanged — skipped"
  else
    "$ADB" install -r "$APP_APK" >/dev/null
    echo "    $APP reinstalled"
  fi
else
  "$ADB" install -r "$APP_APK" >/dev/null
  echo "    $APP installed"
fi

echo "==> launching spike activity (VPN consent dialog will appear)"
"$ADB" logcat -c
# force-stop first: the spike activity stays on top after its gates finish, and a
# second `am start` would deliver to that stale instance instead of launching fresh.
"$ADB" shell am force-stop "$APP"
"$ADB" shell am start -n "$APP/.vpn.VpnSpikeActivity" >/dev/null

echo "    === TAP ALLOW on the VPN consent dialog (and check 'always-on' is NOT checked) ==="
# logd can drop the early ACTIVE marker under load (XOS log-lag); a gate marker proves
# the service reached ACTIVE too, since gates only run after ACTIVE.
n=0
while [ "$n" -lt 45 ]; do
  if "$ADB" logcat -d | grep -qE "MARKER: vpn-state ACTIVE|MARKER: gate "; then break; fi
  if "$ADB" logcat -d | grep -q "MARKER: vpn-state FAILED"; then
    echo "FAILED: sinkhole start failed"
    "$ADB" logcat -d | grep -E "MyExodusVpn" || true
    exit 1
  fi
  n=$((n + 1))
  sleep 2
done
if [ "$n" -ge 45 ]; then
  echo "FAILED: VPN never became ACTIVE (consent not granted?)"
  "$ADB" logcat -d | grep -E "MyExodusVpn" || true
  exit 1
fi
echo "    VPN active — running gates (takes ~1 min)"

start_ts=$(date +%s)
deadline=$(( start_ts + 240 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  if "$ADB" logcat -d | grep -q "MARKER: spike-done"; then break; fi
  sleep 2
done

echo "==> gate log:"
"$ADB" logcat -d | grep -E "MyExodusVpn(Spike)?" | grep -E "MARKER: (gate|spike-done|vpn-state)" || true
res=$("$ADB" logcat -d | grep "MARKER: spike-done" | tail -1 || true)
if [ -z "$res" ]; then
  echo "FAILED: no result within 240s (timeout)"
  exit 1
fi
echo "==> $res"
echo "$res" | grep -q "ALL=PASS" && echo "PASS: VPN spike gates" || { echo "FAILED: VPN spike"; exit 1; }

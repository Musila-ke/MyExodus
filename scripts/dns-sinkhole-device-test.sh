#!/bin/bash
# Slice 3 VPN PoC spike — on-device gate runner (plan §7 step 3 / §8.4).
#
# App-side gates (VpnSpikeActivity, app uid): routes (192.0.2.1/32 + fd00:14:14::1/128),
# Private DNS detected, stop lifecycle.
# Traffic-path gates (here, shell uid): blocked domain → NXDOMAIN written into the TUN,
# allowed domain resolves through the relay, post-stop resolution restored.
#
# Why the split (S8/INV-9): the builder calls addDisallowedApplication(APPLICATION_ID), so
# the app's own sockets bypass its own TUN and can no longer probe the sinkhole from inside.
# The shell uid is not excluded, so it exercises the real client path: netd → TUN → service.
#
# Note: on some images the physical resolver also fails to resolve the probe domain, so the
# client's "unknown host" alone is not decisive — the service's own `nxdomain … written=true`
# line is what proves the sinkhole answered.
#
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
echo "    VPN active — running app-side gates, then the host traffic window"

# ---- traffic-path gates: host-driven from the shell uid (not excluded from the TUN) ----
# The app holds the VPN open until the host drops the handshake file (the shell cannot start
# a VpnService — BIND_VPN_SERVICE is app/system-only — and the app cannot probe its own TUN).
"$ADB" shell mkdir -p "/sdcard/Android/data/$APP/files" || true
"$ADB" shell rm -f "/sdcard/Android/data/$APP/files/spike-host-done" || true

echo "==> waiting for policy seed + blocklist snapshot"
n=0
while [ "$n" -lt 30 ]; do
  seeded=$("$ADB" logcat -d | grep -c "MARKER: policy-seeded success=true" || true)
  blocks=$("$ADB" logcat -d | grep -c "policy blocklist: [1-9]" || true)
  if [ "$seeded" -gt 0 ] && [ "$blocks" -gt 0 ]; then break; fi
  n=$((n + 1)); sleep 2
done
if [ "$n" -ge 30 ]; then
  echo "FAILED: policy never seeded / blocklist never loaded"
  "$ADB" logcat -d | grep -E "MyExodusVpn|policy" || true
  exit 1
fi

nx_before=$("$ADB" logcat -d | grep -c "nxdomain blocked.example.com" || true)
blocked_out=$("$ADB" shell "ping -c 1 -W 3 blocked.example.com" 2>&1 || true)
nx_after=$("$ADB" logcat -d | grep -c "nxdomain blocked.example.com" || true)
nx_line=$("$ADB" logcat -d | grep "nxdomain blocked.example.com" | tail -1 || true)
allowed_out=$("$ADB" shell "ping -c 1 -W 3 example.com" 2>&1 || true)
relay_count=$("$ADB" logcat -d | grep -cE "tcp synack|tcp fwd up->dev" || true)

# Hand the VPN back to the app: it stops, then reports spike-done.
"$ADB" shell touch "/sdcard/Android/data/$APP/files/spike-host-done" \
  || echo "    WARN: handshake write failed — the spike closes its window on its deadline"

start_ts=$(date +%s)
deadline=$(( start_ts + 240 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  if "$ADB" logcat -d | grep -q "MARKER: spike-done"; then break; fi
  sleep 2
done

echo "==> gate log:"
"$ADB" logcat -d | grep -E "MyExodusVpn(Spike)?" | grep -E "MARKER: (gate|spike-done|vpn-state|traffic-window)" || true
res=$("$ADB" logcat -d | grep "MARKER: spike-done" | tail -1 || true)
if [ -z "$res" ]; then
  echo "FAILED: no result within 240s (timeout)"
  exit 1
fi
app_pass=false
if echo "$res" | grep -q "ALL=PASS"; then app_pass=true; fi
echo "==> app-side: $res"

post_out=$("$ADB" shell "ping -c 1 -W 3 example.com" 2>&1 || true)

echo "==> traffic-path gates:"
t_pass=true
if echo "$blocked_out" | grep -q "unknown host" \
   && [ "$nx_after" -gt "$nx_before" ] \
   && echo "$nx_line" | grep -q "written=true"; then
  echo "    PASS dns_blocked: $nx_line"
else
  echo "    FAIL dns_blocked: client='$(echo "$blocked_out" | head -1)' nxdomain_delta=$((nx_after - nx_before))"
  t_pass=false
fi
if echo "$allowed_out" | grep -qE "\([0-9.]+\)"; then
  echo "    PASS dns_allowed: $(echo "$allowed_out" | head -1)"
else
  echo "    FAIL dns_allowed: $(echo "$allowed_out" | head -1)"
  t_pass=false
fi
echo "    INFO relay flows seen: $relay_count (tcp synack / fwd up->dev — informational)"
if echo "$post_out" | grep -qE "\([0-9.]+\)"; then
  echo "    PASS stop_restores: post-stop resolution ok"
else
  echo "    FAIL stop_restores: $(echo "$post_out" | head -1)"
  t_pass=false
fi

if [ "$app_pass" = true ] && [ "$t_pass" = true ]; then
  echo "PASS: VPN spike (app-side + traffic-path gates)"
else
  echo "FAILED: VPN spike — app-side=$app_pass traffic-path=$t_pass"
  exit 1
fi

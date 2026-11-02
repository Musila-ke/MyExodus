#!/bin/bash
# Enhanced Mode §12 on-device verification — Infinix X6532/XOS (plan: myexodus-enhanced-mode.implementation-plan.md §12)
#
# Prerequisites (user actions):
#   1. MyExodus whitelisted in XOS battery/app management (Hiber freezer), done once.
#   2. Device unlocked, screen on.
#
# What this does:
#   1. Installs app + androidTest APKs (assembleDebug/assembleDebugAndroidTest first if asked).
#   2. Enables BlockerAccessibilityService via secure settings and verifies it binds.
#   3. Freezer-survival cycle: am freeze -> sleep -> am unfreeze -> process-alive check.
#   4. Runs the suites via the debug DeviceVerificationActivity (in-process, bind survives).
#      Instrumentation was abandoned 2026-08-27: `am instrument` starts a fresh process that
#      never binds the accessibility service on XOS, and Context.startInstrumentation from an
#      app process is denied by an AOSP security check on user builds (caller must be
#      root/shell/system, or the OS build debuggable, or the process already instrumented).
#      The activity runs the same scenarios against the real bound service; the host reacts to
#      MARKER log lines with the shell-only actions (power key, dismiss-keyguard, touch
#      injection) and asserts the tapjacking result from the victim's logcat lines.
#   5. Prompts for a manual XOS split-screen gesture, then runs the multi-window suite.
#
# Notes learned on this device (2026-08-26/27):
#   - Never toggle accessibility_enabled back to 0: XOS's Hiber daemon clears the service list.
#   - The service binds only when enabled_accessibility_services is WRITTEN while the app
#     process is alive (normal launch). A fresh process with the setting already present does
#     NOT bind, and a process started by `am instrument` NEVER completes a bind — hence the
#     instrumentation-free verification activity.
#   - `am start --windowing-mode` is unsupported on this XOS build; split-screen is a manual
#     two-finger-swipe / recents gesture.
set -euo pipefail

export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
ADB="${ADB:-adb}"
APP="com.example.myexodus"
TEST_APK_PKG="$APP.test"
SVC="$APP/com.example.myexodus.blocking.BlockerAccessibilityService"
APP_APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

# Hygiene: nothing here writes rotation settings, but injected input can still flip the
# auto-rotate quick-settings tile mid-run (a stray notification shade + a center-screen
# tap — monkey proved shade pulls happen during these runs). The owner ruling is that
# auto-rotate ON is never a wanted state on this test phone, and a snapshot taken after an
# earlier flip would faithfully restore the flip — so force accelerometer_rotation OFF on
# exit; user_rotation still restores so the locked orientation does not jump.
ROT_USER=$("$ADB" shell settings get system user_rotation | tr -d '\r')
case "$ROT_USER" in ''|null) ROT_USER=0 ;; esac
restore_rotation() {
  "$ADB" shell settings put system accelerometer_rotation 0 || :
  "$ADB" shell settings put system user_rotation "$ROT_USER" || :
}
# INT/TERM get an explicit exit so the EXIT trap runs when a run is interrupted (Ctrl-C used
# to skip it, which is how a flip survived a session despite this guard existing).
trap restore_rotation EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ "${1:-}" == "--build" ]]; then
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -q
fi

echo "==> installing APKs (unchanged APKs are skipped — XOS revokes the accessibility grant on every reinstall)"
install_if_changed() {
  local apk="$1" pkg="$2"
  local local_md5 remote_md5 remote
  local_md5=$(md5 -q "$apk")
  remote=$("$ADB" shell pm path "$pkg" 2>/dev/null | head -1 | sed 's/^package://' | tr -d '\r')
  if [ -n "$remote" ]; then
    remote_md5=$("$ADB" shell md5sum "$remote" 2>/dev/null | awk '{print $1}' | tr -d '\r')
    if [ -n "$remote_md5" ] && [ "$remote_md5" = "$local_md5" ]; then
      echo "    $pkg unchanged — skipped"
      return 0
    fi
  fi
  "$ADB" install -r "$apk" >/dev/null
  echo "    $pkg reinstalled"
}
install_if_changed "$APP_APK" "$APP"
install_if_changed "$TEST_APK" "$TEST_APK_PKG"

echo "==> checking overlay permission (XOS rejects TYPE_ACCESSIBILITY_OVERLAY — the overlay is TYPE_APPLICATION_OVERLAY, which needs the SYSTEM_ALERT_WINDOW appop; shell grants are ignored on this build, the user grants 'display over other apps' in Settings)"
granted="$("$ADB" shell appops get "$APP" SYSTEM_ALERT_WINDOW | tr -d '\r')"
if ! echo "$granted" | grep -q "SYSTEM_ALERT_WINDOW: allow"; then
  echo "    NOT granted (state: ${granted:-unknown}) — opening the permission screen; toggle 'Display over other apps' ON (the suite continues automatically when granted)."
  "$ADB" shell am start -a android.settings.action.MANAGE_OVERLAY_PERMISSION -d "package:$APP" >/dev/null
  n=0
  while [ "$n" -lt 45 ]; do
    granted="$("$ADB" shell appops get "$APP" SYSTEM_ALERT_WINDOW | tr -d '\r')"
    if echo "$granted" | grep -q "SYSTEM_ALERT_WINDOW: allow"; then break; fi
    n=$((n + 1))
    sleep 2
  done
  granted="$("$ADB" shell appops get "$APP" SYSTEM_ALERT_WINDOW | tr -d '\r')"
  echo "$granted" | grep -q "SYSTEM_ALERT_WINDOW: allow" || { echo "FAILED: overlay permission still not granted (${granted:-unknown})"; exit 1; }
fi
echo "    granted"

echo "==> enabling accessibility service"
# Order matters on XOS: the service binds only when the setting CHANGES while the app
# process is alive (device-verified 2026-08-26). Writing the identical value is a no-op, so
# cycle dummy->real to guarantee a change event. A fresh process with the setting already
# present does not bind; keyguard dismissal while alive is a slow (~28s) secondary trigger.
# Two post-reinstall failure modes (both observed 2026-08-27): (1) XOS's cleanup clobbers
# the write within ~45s of a reinstall; (2) the service binds but event ROUTING stays dead
# (dumpsys shows Bound services, the app logs nothing) until a force-stop + settings-change
# cycle re-engages it — so readiness is probed by EVENT FLOW, not by the bind state.
DUMMY_SVC="$APP/com.example.myexodus.blocking.NonexistentService"
"$ADB" shell am force-stop "$APP"
"$ADB" shell am start -n "$APP/.MainActivity" >/dev/null
# A settings cycle landing within ~3s of process start no longer binds (device-observed
# 2026-09-19: cycle at +3s → Enabled but never Bound; identical cycle at +10s → bound in
# ~1s and delivering events). Let the process settle before the first cycle.
sleep 8
ready() {
  # Must see an event from a THIRD-PARTY app window: a degraded connection still delivers
  # our own app's and systemui's events but never application windows' (observed 2026-08-27:
  # probe passed on our own event, then the victim's WINDOW_STATE_CHANGED never arrived).
  "$ADB" logcat -c
  # HOME first: if Settings is already frontmost (it is, after every failed probe),
  # `am start` is a no-op with NO window transition → no WSC → the probe can never pass
  # (device-observed 2026-09-19, three consecutive script runs). HOME guarantees the
  # transition the probe is actually waiting for.
  "$ADB" shell input keyevent KEYCODE_HOME
  "$ADB" shell am start -n com.android.settings/.Settings >/dev/null 2>&1
  local n=0
  while [ "$n" -lt 8 ]; do
    if "$ADB" logcat -d | grep -q "MyExodusBlocker: event pkg=com.android.settings"; then return 0; fi
    n=$((n + 1))
    sleep 2
  done
  return 1
}
BOUND=""
for attempt in $(seq 1 8); do
  "$ADB" shell settings put secure accessibility_enabled 1
  "$ADB" shell settings put secure enabled_accessibility_services "$DUMMY_SVC"
  "$ADB" shell settings put secure enabled_accessibility_services "$SVC"
  "$ADB" shell input keyevent KEYCODE_WAKEUP
  "$ADB" shell wm dismiss-keyguard >/dev/null
  if ready; then BOUND=1; break; fi
  echo "    (attempt $attempt: no event flow yet — recycling service)"
  "$ADB" shell am force-stop "$APP"
  "$ADB" shell am start -n "$APP/.MainActivity" >/dev/null
  sleep 8
done
[ -n "$BOUND" ] || { echo "FAILED: accessibility service never delivered events"; exit 1; }
echo "    service bound and delivering events"

echo "==> clearing the §8.2 explicit-re-enable gate (ADR #156: this script's own force-stops are recorded exits, and an isolated force-stop is correctly a user stop — the FGS scan stays off until an explicit re-enable, which a script cannot broadcast to the exported=false receiver; the in-process suite invokes the same call the banner tap does)"
"$ADB" logcat -c
"$ADB" shell am start -n "$APP/.blocking.DeviceVerificationActivity" --es suite reenable >/dev/null
sleep 3
"$ADB" logcat -d | grep -q "RESULT: reenable=PASS" || { echo "FAILED: re-enable suite did not pass"; exit 1; }
echo "    re-enabled"

echo "==> freezer survival cycle (am freeze 10s)"
"$ADB" shell am freeze "$APP"
sleep 10
"$ADB" shell am unfreeze "$APP"
sleep 3
PID="$("$ADB" shell pidof "$APP" | tr -d '\r')"
[ -n "$PID" ] || { echo "FAILED: process died after am unfreeze"; exit 1; }
echo "    process alive after thaw (pid $PID)"

# Runs one suite in the already-bound app process via DeviceVerificationActivity (no
# instrumentation — see header). The host reacts to the activity's MARKER log lines with the
# shell-only actions and composes the tapjacking assertion from the victim's logcat lines.
# Results are read from logcat: "RESULT: <suite>=PASS|FAIL".
run_suite() {
  local suite="$1" timeout="${2:-360}"
  local lock_done="" wakeup_done="" unlock_done="" tap_done="" tapjacking=""
  "$ADB" logcat -c
  "$ADB" shell am start -n "$APP/.blocking.DeviceVerificationActivity" --es suite "$suite" >/dev/null
  echo "==> $suite (in-process, bind survives)"
  local start_ts deadline
  start_ts=$(date +%s)
  deadline=$(( start_ts + timeout ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if [ -z "$lock_done" ] && "$ADB" logcat -d | grep -q "MARKER: LOCK"; then
      lock_done=1
      "$ADB" shell input keyevent KEYCODE_POWER
      sleep 3
      if ! "$ADB" shell dumpsys window 2>/dev/null | grep -q "isKeyguardShowing=true"; then
        "$ADB" shell input keyevent KEYCODE_POWER
        echo "    reacted: power (device sleep, retried once)"
      else
        echo "    reacted: power (device locked)"
      fi
    fi
    if [ -z "$wakeup_done" ] && "$ADB" logcat -d | grep -q "MARKER: WAKEUP"; then
      wakeup_done=1
      "$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null
      sleep 2
      if ! "$ADB" shell dumpsys power 2>/dev/null | grep -q "mWakefulness=Awake"; then
        "$ADB" shell input keyevent KEYCODE_POWER
        sleep 2
        echo "    reacted: wakeup (screen was off, toggled on)"
      else
        echo "    reacted: wakeup (keyguard up, screen on)"
      fi
    fi
    if [ -z "$unlock_done" ] && "$ADB" logcat -d | grep -q "MARKER: UNLOCK"; then
      unlock_done=1
      # A secure keyguard (PIN/password) cannot be dismissed from shell by design; the
      # user unlocks manually and the harness detects the keyguard clearing. The app-side
      # wait is 120s; the poll below is 60 x 2s to match.
      echo "    === UNLOCK THE PHONE NOW (enter your PIN/password) — the harness cannot. ==="
      echo "    === The suite continues automatically once the keyguard clears. ==="
      local n=0
      while [ "$n" -lt 60 ]; do
        if ! "$ADB" shell dumpsys window 2>/dev/null | grep -q "isKeyguardShowing=true"; then
          echo "    reacted: device unlocked"
          break
        fi
        n=$((n + 1))
        sleep 2
      done
      if [ "$n" -ge 60 ]; then echo "    WARN: keyguard never cleared within 120s"; fi
    fi
    if [ -z "$tap_done" ] && "$ADB" logcat -d | grep -q "MARKER: TAP_READY"; then
      tap_done=1
      local bounds x y
      bounds=$("$ADB" logcat -d | grep "VictimBounds" | tail -1 || true)
      if [ -n "$bounds" ]; then
        x=$(( $(echo "$bounds" | sed -n 's/.*left=\([0-9]*\).*/\1/p') + $(echo "$bounds" | sed -n 's/.*width=\([0-9]*\).*/\1/p') / 2 ))
        y=$(( $(echo "$bounds" | sed -n 's/.*top=\([0-9]*\).*/\1/p') + $(echo "$bounds" | sed -n 's/.*height=\([0-9]*\).*/\1/p') / 2 ))
        "$ADB" shell input tap "$x" "$y"
        sleep 1
        # The victim is the androidTest APK, which has no Kotlin stdlib of its own (under
        # instrumentation it shares the target app's classloader; launched standalone it does
        # not), so a leaked touch crashes it at dispatchTouchEvent — `ClassNotFoundException:
        # kotlin.jvm.internal.Intrinsics` — BEFORE the VictimTouch log line runs. The crash's
        # `Process: <victim>` line therefore counts as a leak too; without it a leak reads as
        # PASS (device-caught 2026-09-13).
        local touches
        touches=$("$ADB" logcat -d | grep -cE "VictimTouch|Process: $TEST_APK_PKG," || true)
        if [ "$touches" -gt 0 ]; then tapjacking="tapjacking=FAIL ($touches touches leaked)"; else tapjacking="tapjacking=PASS"; fi
        echo "    reacted: tap at $x,$y — $tapjacking"
      else
        echo "    WARN: TAP_READY without VictimBounds — tapjacking unverifiable"
      fi
    fi
    if "$ADB" logcat -d | grep -q "RESULT: $suite="; then
      break
    fi
    sleep 2
  done
  local res
  res=$("$ADB" logcat -d | grep "RESULT: $suite=" | tail -1 || true)
  "$ADB" logcat -d | grep -E "MyExodusVerification: (life-safety|browser URL|cycle|latency ms|multi-window|RESULT|MARKER)|MyExodusVerification: service" || true
  echo "==> $suite: ${res:-NO RESULT (timeout)}${tapjacking:+ | $tapjacking}"
  if ! echo "${res:-}" | grep -q "=PASS" || [ "${tapjacking:-}" = "tapjacking=FAIL" ]; then
    echo "FAILED: $suite"
    return 1
  fi
  echo "PASS: $suite"
}

echo "==> functional suite (life-safety, tapjacking, browser URL)"
# Cold-start Chrome for the browser scenario: a live Chrome parked on a blocked URL
# foregrounds without re-firing content-changed events, so the URL scan never runs
# (observed 2026-08-27 — the scenario then times out on the missing overlay).
"$ADB" shell am force-stop com.android.chrome
run_suite "functional" 420

echo "==> latency suite (p50/p95/p99 vs §2 targets)"
run_suite "latency" 300

echo "==> multi-window suite — engage split-screen NOW"
echo "    (recents -> long-press an app card -> split, or two-finger swipe from the bottom edge)"
echo "    Split any two apps (e.g. Settings + Chrome). The suite starts automatically when"
echo "    split-screen is detected (or after 120s it runs anyway and self-skips if not engaged)."
n=0
while [ "$n" -lt 60 ]; do
  if "$ADB" shell dumpsys activity activities 2>/dev/null | grep -q "mMultiWindowMode=true"; then
    echo "    split-screen detected"
    break
  fi
  n=$((n + 1))
  sleep 2
done
run_suite "multiwindow" 180

echo "==> done. Record the latency numbers from the 'latency ms' logcat line above."

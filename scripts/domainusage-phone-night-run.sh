#!/bin/bash
# Overnight session on the X6532 — the phone half of AC-16 (plan §17) plus the legs the harness
# defects invalidated (report §5.1 items 12–13). Deferred 2026-09-21 by user ruling: it runs on a
# night the project owner schedules, not automatically. Nothing here is safe to start while the
# emulator PF batch is running on the same host (the Gradle build and the CPU legs would collide).
#
# PREP — the owner does steps 1–3, then starts this script:
#   1. Phone on USB, unlocked, adb authorized. Charge to 100%.
#   2. Enable wireless adb so the run survives unplugging: `adb tcpip 5555` while still on USB
#      (verified working on this build 2026-09-22 — the ro.debuggable=0 fear was wrong), then
#      `adb connect <phone-wifi-ip>:5555`. Re-do after any phone reboot: tcpip does not persist.
#   3. Screen timeout → Never; phone stays on Wi-Fi, undisturbed on a table (XOS kills backgrounded
#      apps under memory pressure and any PF leg the app dies in is invalid).
#   4. Start this script; unplug the phone when it says so. Run continues over wireless adb.
#
# Legs (what is left after 2026-09-22): PF-5 only — PF-3 and PF-4 both PASSed that day in a plugged
# block. PF-5 needs the cable out and self-skips while powered (including Qi: `Wireless powered`),
# so charge first, then run. `--runs 1` = 2 h (1 h × 2 arms) and needs only ~40% start charge.
cd /Users/musila/AndroidStudioProjects/MyExodus || exit 1
A=$HOME/Library/Android/sdk/platform-tools/adb
L=/tmp/du-night.log
say() { echo "$(date '+%H:%M:%S') $*" | tee -a "$L"; }

# the first attached non-emulator device: works for USB now and for the wireless serial after unplug
pick_serial() { "$A" devices | awk '$2=="device" && $1 !~ /^emulator-/ {print $1; exit}'; }

# Two concurrent runs against the same phone invalidate both (each seeds rules and cycles the
# tunnel), so wait for any in-flight batch — the emulator one and/or a phone session already live.
BUSY="pf-run-emu.sh|domainusage-pf-run.sh|domainusage-device-test.sh"
for i in $(seq 1 240); do
  pgrep -f "$BUSY" >/dev/null || break
  [[ $i == 1 ]] && say "waiting for an in-flight test batch to finish (host CPU and device must be quiet)"
  sleep 30
done
pgrep -f "$BUSY" >/dev/null && { say "a test batch is still running after 2 h — stopping"; exit 1; }

SERIAL=$(pick_serial)
[[ -n "$SERIAL" ]] || { say "no physical device attached — stopping"; exit 1; }
say "device=$SERIAL"
# The host LAN IP is DHCP and changes across reboots (it did on 2026-09-22) — pass this machine's
# current LAN address explicitly.
HOST="${DU_HOST:?usage: DU_HOST=<this machine's LAN IP> $0}"
curl -s -m 5 "http://$HOST:8080/hello" >/dev/null 2>&1 \
  || { say "host test server not answering on $HOST:8080 — stopping"; exit 1; }
say "host test server alive"

# Foreground keeper (adj=0 → XOS LMKD spares the app) + cached-process relief, both re-resolving the
# serial each iteration so they survive the USB→wireless swap. `am kill-all` drops only cached
# processes; the app stays up (device-tested 2026-09-21).
(
  n=0
  while ! grep -q "PF_RUN_EXIT=" "$L" 2>/dev/null; do
    S=$(pick_serial)
    if [[ -n "$S" ]]; then
      "$A" -s "$S" shell am start -n com.example.myexodus/.MainActivity >/dev/null 2>&1
      n=$((n+1))
      (( n % 5 == 0 )) && echo "$(date '+%H:%M:%S') keeper s=$S pid=$("$A" -s "$S" shell pidof com.example.myexodus 2>/dev/null | tr -d '\r')" >> /tmp/du-night-keeper.log
    fi
    sleep 60
  done
) &
(
  while ! grep -q "PF_RUN_EXIT=" "$L" 2>/dev/null; do
    S=$(pick_serial)
    [[ -n "$S" ]] && "$A" -s "$S" shell am kill-all >/dev/null 2>&1
    sleep 240
  done
) &
KEEPERS=$!

# What is left after 2026-09-22: I-11/I-13/I-17 and PF-1/2/3/4/6/7/8 all passed there, so re-running
# any of them only burns hours. PF-5 is the only leg that needs the cable out (drain is
# unmeasurable while powered) and it self-skips otherwise. `--runs 1` keeps the block at 2 h.
ONLY_PF="${DU_ONLY:-PF-5}"
RUNS_FLAG="${DU_RUNS:+--runs $DU_RUNS}"
say "PF re-run: $ONLY_PF $RUNS_FLAG — builds and installs the current tree (pf-run's own build step)"
if [[ ",$ONLY_PF," == *,PF-5,* ]]; then
  say "UNPLUG the phone now — PF-5 legs are skipped while charging; run continues over wireless adb"
  if [[ "$SERIAL" == *:* ]]; then
    # Already on wireless adb (phone was prepped before this script started): the 15-min wait
    # below exists only to catch the USB→wireless serial swap, so there is nothing to wait for.
    say "already on wireless adb — no unplug swap to wait for"
  else
    deadline=$((SECONDS + 900))
    while [[ $SECONDS -lt $deadline ]]; do
      s=$(pick_serial)
      if [[ -n "$s" && "$s" != "$SERIAL" ]]; then SERIAL="$s"; break; fi
      sleep 10
    done
  fi
fi
say "PF serial=$SERIAL"
bash scripts/domainusage-pf-run.sh --serial "$SERIAL" --host "$HOST" \
  --only "$ONLY_PF" $RUNS_FLAG >> "$L" 2>&1
echo "PF_RUN_EXIT=$?" >> "$L"

kill $KEEPERS 2>/dev/null
pkill -P $KEEPERS 2>/dev/null
say "night run done"

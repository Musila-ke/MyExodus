#!/bin/bash
# Day-log battery question: "is the VPN draining battery?" — start a sampler on the phone, use the
# phone normally all day, then stop and analyse. This is NOT the PF-5 gate: PF-5 is a controlled
# 1 h OFF arm vs 1 h ACCOUNT arm on an undisturbed, screen-pinned phone (scripts/domainusage-pf-run.sh).
# A free-use day has no controlled arm, so it can show a gross drain but cannot close PF-5.
#
#   bash scripts/battery-day-log.sh start    # push + launch (survives adb disconnect, Mac sleep)
#   bash scripts/battery-day-log.sh status   # rows so far + loop alive?
#   bash scripts/battery-day-log.sh stop     # kill loop, dump batterystats, pull both to /tmp
#
# SAMPLE=300 (5 min) by default; SAMPLE=60 bash ... start for finer resolution.
# VPN-on/off windows are read from the `vpn` column — toggle the tunnel by hand during the day so
# the analysis has both states, and note roughly when you toggled.
set -u
cd "$(dirname "$0")/.." || exit 1
A=$HOME/Library/Android/sdk/platform-tools/adb
DEV=/data/local/tmp/battery-day-log.sh
STATE=/tmp/battery-day-current.txt
pick() { "$A" devices | awk '$2=="device" && $1 !~ /^emulator-/ {print $1; exit}'; }
live() { "$A" -s "$1" shell "pgrep -f battery-day-log >/dev/null 2>&1 && echo yes || echo no" | tr -d '\r'; }

case "${1:-}" in
start)
  S=$(pick); [[ -n "$S" ]] || { echo "no physical device attached"; exit 1; }
  OUT=/sdcard/battery-day-$(date +%Y%m%d-%H%M).csv
  "$A" -s "$S" push scripts/battery-day-log-device.sh "$DEV" >/dev/null || exit 1
  "$A" -s "$S" shell "chmod 700 $DEV"
  "$A" -s "$S" shell "nohup sh $DEV $OUT >/dev/null 2>&1 </dev/null &"
  echo "$S $OUT" > "$STATE"
  sleep 12
  rows=$("$A" -s "$S" shell "wc -l < $OUT" | tr -d '\r')
  echo "sampler on $S → $OUT"
  echo "rows=$rows alive=$(live "$S")   (rows=2 right after start = header + first sample)"
  [[ "$rows" -ge 2 ]] || { echo "no row written — check: $A -s $S shell sh $DEV $OUT"; exit 1; }
  ;;
status)
  [[ -f "$STATE" ]] || { echo "no sampler started this boot (missing $STATE)"; exit 1; }
  read -r S OUT < "$STATE"
  [[ "$(pick)" == "$S" ]] || { S=$(pick); echo "note: reattached as $S"; }
  echo "device=$S out=$OUT alive=$(live "$S") rows=$("$A" -s "$S" shell "wc -l < $OUT" | tr -d '\r')"
  "$A" -s "$S" shell "tail -3 $OUT"
  ;;
stop)
  [[ -f "$STATE" ]] || { echo "no sampler started this boot"; exit 1; }
  read -r S OUT < "$STATE"
  S=$(pick)
  "$A" -s "$S" shell "pkill -f battery-day-log" 2>/dev/null
  stamp=$(basename "$OUT" .csv)
  DUMP="${OUT%.csv}-batterystats.txt"
  "$A" -s "$S" shell "dumpsys batterystats > $DUMP"
  DEST=/tmp/battery-day-$stamp
  mkdir -p "$DEST"
  "$A" -s "$S" pull "$OUT" "$DEST/" >/dev/null && "$A" -s "$S" pull "$DUMP" "$DEST/" >/dev/null
  rm -f "$STATE"
  echo "pulled to $DEST"
  ls -l "$DEST"
  ;;
*)
  sed -n '2,12p' "$0"
  ;;
esac

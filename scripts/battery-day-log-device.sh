#!/system/bin/sh
# On-device sampler for the day-log battery question ("is the VPN draining it?").
# Pushed to /data/local/tmp and launched detached by scripts/battery-day-log.sh — do not run by hand.
# One CSV row per SAMPLE seconds. Gaps in iso/epoch = the loop died (reboot or LMKD); uptime_s
# resets prove a reboot. app_ticks is the app's CPU jiffies summed over its processes — the
# VPN-path cost signal — and reads "-" when the app has no process at all: a killed app and an
# idle one would otherwise both print 0, and a kill is exactly what the analysis must see.
# vpn comes from the debug du-status probe; "?" means the debug receiver did not answer
# (release build or app dead).
SAMPLE=${SAMPLE:-300}
OUT=$1
PKG=com.example.myexodus
RCVR=$PKG/.domainusage.DomainUsageTestReceiver

[ -f "$OUT" ] || echo "iso,epoch,level,status,charge_uah,temp,ac,usb,wireless,wake,vpn,app_ticks,uptime_s" > "$OUT"

while :; do
  ts=$(date +%s); iso=$(date '+%F %T')
  b=$(dumpsys battery)
  cols=$(echo "$b" | awk '
    / level:/{l=$2} /^  status:/{s=$2} /Charge counter:/{c=$3} /temperature:/{t=$2}
    /AC powered:/{ac=$3} /USB powered:/{usb=$3} /Wireless powered:/{w=$3}
    END{printf "%s,%s,%s,%s,%s,%s,%s", l,s,c,t,ac,usb,w}')
  wake=$(dumpsys power | sed -n 's/.*mWakefulness=\([A-Za-z]*\).*/\1/p' | head -1)
  pids=$(pidof $PKG)
  if [ -n "$pids" ]; then
    ticks=$(for p in $pids; do cut -d')' -f2- /proc/$p/stat 2>/dev/null | cut -c2-; done \
      | awk '{t+=$12+$13} END{print t+0}')
  else
    ticks=-
  fi
  logcat -c 2>/dev/null
  am broadcast -n $RCVR -a du-status >/dev/null 2>&1
  vpn=$(logcat -d -s MyExodusVpn:I 2>/dev/null | sed -n 's/.* vpn=\([A-Z]*\) .*/\1/p' | tail -1)
  up=$(cut -d' ' -f1 /proc/uptime)
  echo "$iso,$ts,$cols,$wake,${vpn:-?},$ticks,$up" >> "$OUT"
  sleep "$SAMPLE"
done

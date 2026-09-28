#!/usr/bin/env bash
# Domain-usage metering §12.3 performance gates PF-1..PF-8 (plan S10, gates in plan §11).
#
# A different shape from the §12.2 matrix (domainusage-device-test.sh): every gate here is an
# OFF-vs-ACCOUNT A/B on the same device and workload, except PF-6 (JVM) and PF-7/PF-8 (the
# harness's own focused actions). The adb helpers are duplicated on purpose — the matrix script
# executes as a side effect of being sourced, so sharing them would run scenarios here too.
#
# Both legs run through the live tunnel (`addRoute 0.0.0.0/0`), so the ratio isolates the
# engine's metering work: OFF skips the hooks (DomainUsageEngine returns early), ACCOUNT runs
# them. Traffic is dialled from the device shell (uid 2000) — INV-9 excludes the app itself
# from its own TUN, so an in-app client would bypass the path under test (the S8 lesson).
#
# Usage: domainusage-pf-run.sh --serial <adb-serial> [--host <ip>] [--only PF-1,PF-7]
#                              [--runs <n>] [--minutes <n>] [--skip-build]
#
#   --host   the test server's address as this device reaches it (default 10.0.2.2: emulator)
#   --runs   median-of-N for the A/B gates (plan §11: median of 3 runs each)
set -uo pipefail

# `adb` is not on PATH in a non-interactive shell (`bash script.sh`, CI), and every `adbs` call
# below then becomes a silent no-op: the run fails in ways that read as product faults rather
# than a missing tool (device-caught 2026-09-21 — "VPN did not come up" reported against a live
# tunnel). The matrix script carries the same line for the same reason.
command -v adb >/dev/null 2>&1 || PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"

SERIAL="" HOST="" ONLY="" RUNS=3 SKIP_BUILD=0 SCRIPT_MIN=10 SOAK_MIN=30 BATT_MIN=60
PKG=com.example.myexodus
RCVR="$PKG/.domainusage.DomainUsageTestReceiver"
ACT="$PKG/.domainusage.DomainUsageTestActivity"
HERE="$(cd "$(dirname "$0")" && pwd)"
TMP=/tmp/domainusage-pf
DNS_TOOL="$HERE/domainusage-dns-probe.py"
HELLO_TOOL="$HERE/domainusage-hello-gen.py"
SERVER_LOG=/tmp/du-server.log
DNS_V4=192.0.2.53
BATT=0

PASSED=0 FAILED=0 SKIPPED=0
declare -a RESULTS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --host) HOST="$2"; shift 2 ;;
    --only) ONLY="$2"; shift 2 ;;
    --runs) RUNS="$2"; shift 2 ;;
    --minutes) SCRIPT_MIN="$2"; shift 2 ;;
    --soak-minutes) SOAK_MIN="$2"; shift 2 ;;
    --battery-minutes) BATT_MIN="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$SERIAL" ]] || { echo "--serial required (adb -s)"; exit 2; }
[[ -n "$HOST" ]] || HOST=$([[ "$SERIAL" == emulator-* ]] && echo 10.0.2.2 || echo "")

adbs() { adb -s "$SERIAL" "$@"; }
say() { printf '\n=== %s\n' "$*"; }
pass() { PASSED=$((PASSED + 1)); RESULTS+=("PASS $*"); printf 'PASS %s\n' "$*"; }
fail() { FAILED=$((FAILED + 1)); RESULTS+=("FAIL $*"); printf 'FAIL %s\n' "$*"; }
skip() { SKIPPED=$((SKIPPED + 1)); RESULTS+=("SKIP $*"); printf 'SKIP %s\n' "$*"; }
wanted() { [[ -z "$ONLY" || ",$ONLY," == *",$1,"* ]]; }

# §11 measures "median of 3 runs each" (line 633) — every A/B gate below collects RUNS
# observations per leg and compares the medians, never a single sample.
median() {
  printf '%s\n' "$@" | sort -n | awk '{v[NR]=$1} END{
    if (NR==0) { print ""; exit }
    print (NR%2) ? v[(NR+1)/2] : int((v[NR/2]+v[NR/2+1])/2)}'
}

# A leg that returned "" measured nothing (app killed, tunnel dropped — see ensure_live). Present
# in any A/B gate's samples it must invalidate the gate, not be averaged over: `sort -n` puts ""
# first, so a median of ["", 45000, 46000] would silently report 45000 as if it were measured.
has_empty() { local v; for v in "$@"; do [[ -z "$v" ]] && return 0; done; return 1; }

probe() { # probe <action> [extras] → the DUPROBE lines it produced
  adb -s "$SERIAL" logcat -c 2>/dev/null
  adbs shell am broadcast -n "$RCVR" "$@" >/dev/null 2>&1
  sleep 2
  # No `-t` window: logcat applies the count to the whole buffer before the `-s` tag filter, so a
  # window smaller than the tag's own output silently returns no counters (device-caught
  # 2026-09-21). The clear above already bounds this read to the probe's own output.
  local out i
  out=$(adbs logcat -d -s MyExodusVpn:I 2>/dev/null | grep -a DUPROBE | sed 's/.*DUPROBE //')
  # A loaded guest can log the DUPROBE line after the 2 s window; an empty read must not be
  # mistaken for a state (vpn_down accepted it as "the tunnel stopped").
  for i in 1 2 3; do
    [[ -n "$out" ]] && break
    sleep 2
    out=$(adbs logcat -d -s MyExodusVpn:I 2>/dev/null | grep -a DUPROBE | sed 's/.*DUPROBE //')
  done
  printf '%s\n' "$out"
}
take() { printf '%s\n' "$2" | grep -ao "$1=[^ ]*" | tail -1 | cut -d= -f2-; }

# §S1.3: the Engine resolves its mode once per service session (`start()`), and ACTION_START on a
# live tunnel is a no-op — so writing the DataStore override alone left every leg running in the
# previous mode and the OFF/ACCOUNT contrast vacuous (device-caught 2026-09-21 on Lean_API34: all
# six PF-1 legs reported mode=OFF with an empty ledger day; runs=[1 1 1]/[1 1 1]). set_mode now
# stops the tunnel; the leg's following ensure_live restores it and start() applies the override;
# check_mode() then proves the engine actually runs the requested mode.
EXPECT_MODE=""
MODE_CYCLE=0
vpn_down() { # stop the tunnel, wait until the state leaves ACTIVE/DEGRADED
  adbs shell am start -n "$ACT" --ez stop true >/dev/null 2>&1
  local i st
  for i in $(seq 1 10); do
    st=$(probe -a du-status | grep -a status= | tail -1)
    # non-empty required: an empty read is not evidence the tunnel stopped
    [[ -n "$st" && "$st" != *"vpn=ACTIVE"* && "$st" != *"vpn=DEGRADED"* ]] && return 0
    sleep 1
  done
  echo "  WARN: tunnel did not stop (still $st)" >&2
  return 1
}
set_mode() { EXPECT_MODE="$1"; probe -a du-mode --es mode "$1" >/dev/null; MODE_CYCLE=1; vpn_down || true; }
check_mode() { # the engine must run the leg's mode — and, for metering legs, be Active (rules
  # loaded, ledger attached). Dormant means the snapshot is empty, so an A/B leg would compare
  # two inert builds and PASS while measuring nothing (the vacuous-pass class of §5.1 item 12).
  [[ -z "$EXPECT_MODE" ]] && return 0
  local i st m s
  for i in $(seq 1 15); do
    st=$(probe -a du-status | grep -a status= | tail -1)
    m=$(take mode "$st"); s=$(take status "$st")
    if [[ "$m" == "$EXPECT_MODE" && ( "$EXPECT_MODE" == OFF || "$s" == Active ) ]]; then return 0; fi
    sleep 1
  done
  echo "  WARN: engine runs mode=$m status=$s, leg requested $EXPECT_MODE — leg invalid" >&2
  return 1
}
seed_rules() { # 3 enabled rules, the plan's A/B condition
  probe -a du-delall >/dev/null
  probe -a du-addrule --es pattern "a.$HOST.nip.io" --el limitSec 3600 >/dev/null
  probe -a du-addrule --es pattern "b.$HOST.nip.io" --el limitSec 3600 >/dev/null
  probe -a du-addrule --es pattern "c.$HOST.nip.io" --el limitSec 3600 >/dev/null
  # Read back what landed: without rules the engine stays Dormant and every A/B leg compares two
  # inert builds (2026-09-22 — a batch started legs against a ruleless store and reported a mode
  # mismatch instead of the real cause).
  local n; n=$(probe -a du-rules | grep -ac 'rule id=')
  if [[ "$n" == 3 ]]; then echo "  rules seeded: 3"; else
    echo "  WARN: seeded $n rules, expected 3 — aborting, legs would be vacuous" >&2; return 1; fi
}

pkg_pid() { adbs shell pidof "$PKG" 2>/dev/null | tr -d '\r'; }
proc_jiffies() { # <pid> → utime+stime of that process, in clock ticks; empty on any failure
  adbs shell "awk '{print \$14+\$15}' /proc/$1/stat 2>/dev/null" 2>/dev/null | tr -d '\r'
}
thread_count() {
  local pid; pid=$(pkg_pid)
  [[ -n "$pid" ]] || { echo ""; return; }
  adbs shell "awk '/^Threads:/{print \$2}' /proc/$pid/status 2>/dev/null" 2>/dev/null | tr -d '\r'
}
total_pss_kb() {
  adbs shell "dumpsys meminfo $PKG 2>/dev/null | awk '/^ *TOTAL PSS/{print \$3}'" 2>/dev/null | tr -d '\r' | tail -1
}
# PF-4's snapshots must read the *retired* state, not the churn round's tail and not a random
# phase of the cycle: each 120-flow round spawns per-connection threads and socket buffers that
# drain over tens of seconds (device-caught 2026-09-21: mid-round 153-165 threads vs 105-111
# between rounds; 5-s point samples read 108→118, min-of-4 read 107→113, break-on-two-equal read
# 103→109 — all phase luck). Residual wander is the app process's own Firebase/GMS gRPC-okhttp
# keep-alive threads retiring on minute-scale idle timers, not metering code (run-6 poller,
# name histograms). So the floor is sampled over a FIXED 3-min window at each end (12 samples
# 15 s apart, min of both metrics — min filters keep-alive bursts), and the gate reads
# end ≤ start + 5 (owner-approved 2026-09-21, plan §11 PF-4; a real per-flow leak over a leg's
# ≥10,000 churned flows shows as tens-to-hundreds of retained threads).
quiesced_snapshot() { # sets SETTLED_PSS (kB) + SETTLED_THR
  local _ p s pmin="" tmin=""
  for _ in $(seq 1 12); do
    sleep 15
    s=$(thread_count); p=$(total_pss_kb)
    [[ -n "$s" ]] && { [[ -z "$tmin" || "$s" -lt "$tmin" ]] && tmin="$s"; }
    [[ -n "$p" ]] && { [[ -z "$pmin" || "$p" -lt "$pmin" ]] && pmin="$p"; }
  done
  SETTLED_PSS="$pmin"; SETTLED_THR="$tmin"
}
battery_level() { adbs shell dumpsys battery 2>/dev/null | awk '/level:/{print $2}' | tr -d '\r' | tail -1; }
# PF-5's gate is a screen-ON drain; a sleeping panel both halves the drain and (on XOS) invites the
# freeze/reap path. The wake nudge + lifted timeout normally hold it awake, but if the panel sleeps
# anyway the leg is not a measurement — record it empty rather than score a fake low drain.
screen_awake() { adbs shell dumpsys power 2>/dev/null | grep -q 'mWakefulness=Awake'; }

vpn_up() { # every leg now cycles the tunnel (set_mode), so this restart is on the critical path:
  # a slow start under host load must not silently empty a leg's measurement (the devtest window
  # was widened for the same reason). Exits as soon as the state line lands — fast devices pay 0.
  adbs shell am start -n "$ACT" >/dev/null 2>&1
  local i; for i in $(seq 1 60); do
    local st; st=$(probe -a du-status | grep -a status= | tail -1)
    [[ "$st" == *"vpn=ACTIVE"* || "$st" == *"vpn=DEGRADED"* ]] && return 0
    sleep 2
  done
  return 1
}

# Device-caught 2026-09-21: XOS killed the app six times inside one PF-3 run (memory pressure:
# 65 MB free of 3721 MB, YouTube/Instagram/WhatsApp resident). A leg measured against a dead app
# is not a measurement. PF-3 scored 0 jiffies (median 0 → bound 0 → guaranteed FAIL), and worse,
# a dead app makes the device's traffic bypass the TUN entirely, so PF-1/PF-2/PF-4/PF-5 would
# have PASSED while measuring no VPN path at all. Every A/B leg restores the tunnel before it
# starts and asserts process identity after it ends; an invalid leg returns "" (never a number),
# and every gate refuses to compute from a sample set containing "".
leg_invalid() {
  local st; st=$(probe -a du-status | grep -a status= | tail -1)
  [[ -z "$(pkg_pid)" || ( "$st" != *"vpn=ACTIVE"* && "$st" != *"vpn=DEGRADED"* ) ]]
}
ensure_live() {
  if leg_invalid; then
    [[ "$MODE_CYCLE" == 1 ]] || echo "  WARN: app or tunnel down — restoring before the leg" >&2
    MODE_CYCLE=0
    vpn_up || true
    leg_invalid && return 1
  fi
  check_mode
}

# dns <name> <qtype> [server] — same primitive as the matrix's dns(), and duplicated for the
# same reason: a query to *any* address on port 53 is intercepted by the TUN, forwarded by the
# engine, and the shaped answer is what records the (ip → qname) observation. Only the default
# resolver is used here: the harvester legs need an observation, not a shaped answer.
dns() {
  local name="$1" qtype="$2" server="${3:-$DNS_V4}"
  python3 "$DNS_TOOL" build "$name" "$qtype" "$TMP/q.b64" >/dev/null
  adbs push "$TMP/q.b64" /data/local/tmp/du-pf-q.b64 >/dev/null
  adbs shell "base64 -d /data/local/tmp/du-pf-q.b64 | nc -u -w 3 -q 3 $server 53" >/dev/null 2>&1
}

# A port-80/8443 flow is attributed *only* by the DNS fallback (§6.4), and the A/AAAA record
# that seeds that fallback has to be learned while no rule is shaping the name to NODATA
# (device-caught 2026-09-21: I-3 accrued nothing when the observation post-dated the rule).
# One candidate, not three — two ruled candidates for one address is D-11's Ambiguous.
seed_observations() {
  dns "$1" A
}

# ---------------------------------------------------------------- workloads

sink_request() { # the POST that makes the test server read-and-discard (PF-1's sink)
  printf 'POST /sink HTTP/1.1\r\nHost: a.%s\r\nContent-Length: 1000000000\r\n\r\n' "$1" | base64
}

# sink_bench <host> <streams> <seconds> <label.b64> → Mbit/s carried, measured host-side.
# The device shell runs the whole thing itself: piping dd through two adb shells would make the
# measurement an adb-throughput test, not a VPN one.
sink_bench() {
  local host="$1" streams="$2" secs="$3" b64="$4" lines i
  # Nothing may be printed here: the caller captures this function's whole stdout, and a stray
  # line lands inside `$(pf1_leg ...)` and is then averaged into the median (device-caught
  # 2026-09-21 — a leaked cumulative total made PF-1 report 84 Gbps and pass).
  lines=$(grep -ac 'sink bytes=' "$SERVER_LOG" 2>/dev/null)
  adbs shell "for i in \$(seq 1 $streams); do ( { base64 -d $b64; dd if=/dev/zero bs=65536 count=100000 2>/dev/null; } | nc -w 5 $host 80 ) >/dev/null 2>&1 & done; sleep $secs; pkill -x nc; pkill -x dd; sleep 1" >/dev/null 2>&1
  # The server logs `sink bytes=` when each socket closes. Wait for this leg's own lines rather
  # than a fixed sleep: a straggler socket still closing when the next leg's window opens lands
  # its bytes in the wrong leg (device-caught 2026-09-21 — PF-1 runs varied 1..5 Mbps leg to leg).
  for i in $(seq 1 30); do
    [[ $(( $(grep -ac 'sink bytes=' "$SERVER_LOG" 2>/dev/null) - lines )) -ge "$streams" ]] && break
    sleep 1
  done
}

sink_bytes() { # total bytes the sink has discarded this server run
  awk -F'bytes=' '/sink bytes=/{s+=$2} END{print s+0}' "$SERVER_LOG" 2>/dev/null
}

ready_probe() { # <host> — the path must actually carry bytes before a timed leg starts. Since every
  # leg now cycles the tunnel (set_mode), a leg can begin in the seconds where the restarted service
  # reports ACTIVE but relays nothing: device-caught 2026-09-21 — the first PF-1 leg read 0 Mbps
  # while its neighbours on the same tunnel read normal, and a 0 in the OFF half silently widens the
  # gate. A completed request, not state. Two failed attempts return false → the caller reports
  # "no measurement".
  local host="$1" try i l0
  for try in 1 2; do
    l0=$(grep -ac 'sink bytes=' "$SERVER_LOG" 2>/dev/null)
    # POST /sink, not `GET /`: only /sink produces the accounting this guard reads. Readiness is
    # the new accounting LINE, not a byte delta — the server's header read swallows a small body
    # whole and logs `sink bytes=0`, and a plain GET never logs at all (both caught 2026-09-22 on
    # this guard's first live run, where it failed every leg it was meant to protect). The line
    # proves the request travelled the tunnel and was answered.
    adbs shell "printf 'POST /sink HTTP/1.1\r\nHost: a.$host\r\nContent-Length: 16\r\n\r\n0123456789abcdef' | nc -w 3 -q 1 $host 80" >/dev/null 2>&1
    for i in $(seq 1 10); do
      [[ $(( $(grep -ac 'sink bytes=' "$SERVER_LOG" 2>/dev/null) - l0 )) -gt 0 ]] && return 0
      sleep 1
    done
    echo "  WARN: warm-up carried 0 bytes (try $try) — cycling the tunnel" >&2
    set_mode "$EXPECT_MODE"; ensure_live || { echo "  WARN: tunnel not restored after re-cycle" >&2; }
  done
  return 1
}

browse_seconds() { # a scripted browsing loop: connection churn + streaming, for A/B CPU legs
  local secs="$1" host="$2" # separate statement: `end=$((SECONDS + secs))` in the same `local`
  local end=$((SECONDS + secs)) # expands before `secs` is assigned under `set -u` (caught by PF-3)
  while [[ $SECONDS -lt $end ]]; do
    adbs shell "printf 'GET /burst HTTP/1.1\r\nHost: a.$host.nip.io\r\n\r\n' | nc -w 3 -q 1 $host 80" >/dev/null 2>&1
    adbs shell "printf 'GET /stream HTTP/1.1\r\nHost: b.$host.nip.io\r\n\r\n'; sleep 2" 2>/dev/null \
      | adbs shell "nc -w 3 -q 1 $host 80" >/dev/null 2>&1
    adbs shell "head -c 20000 /dev/zero | nc -w 3 -q 1 $host 8443" >/dev/null 2>&1
  done
}

churn_flows() { # one round of N concurrent short-lived flows (PF-4's 100-flow churn)
  local n="$1" host="$2" i
  for i in $(seq 1 "$n"); do
    adbs shell "printf 'GET / HTTP/1.1\r\nHost: a.$host.nip.io\r\n\r\n' | timeout 5 nc -w 2 -q 1 $host 80" >/dev/null 2>&1 &
  done
  wait
}

# ---------------------------------------------------------------- PF-1..PF-8

pf1_leg() { # <host> <mode> → Mbit/s carried by 8 parallel TCP streams for 30 s; "" = not measured
  local host="$1" mode="$2" b0 b1 pid
  set_mode "$mode"
  ensure_live || { echo ""; return; }
  ready_probe "$host" || { echo ""; return; }
  pid=$(pkg_pid)
  b0=$(sink_bytes)
  sink_bench "$host" 8 30 /data/local/tmp/du-pf-sink.b64
  if [[ "$(pkg_pid)" != "$pid" ]]; then echo "  WARN: app died mid-leg" >&2; echo ""; return; fi
  b1=$(sink_bytes)
  mbps $(( b1 - b0 )) 30
}

pf1() { # 8 parallel TCP streams through the VPN for 30 s: ACCOUNT ≥ 90 % of OFF
  local host="$1" r offs=() accs=() off acc
  sink_request "$host" > "$TMP/sink.b64"
  adbs push "$TMP/sink.b64" /data/local/tmp/du-pf-sink.b64 >/dev/null
  for r in $(seq 1 "$RUNS"); do offs+=("$(pf1_leg "$host" OFF)"); done
  for r in $(seq 1 "$RUNS"); do accs+=("$(pf1_leg "$host" ACCOUNT)"); done
  if has_empty "${offs[@]}" "${accs[@]}"; then
    fail "PF-1 no measurement — leg(s) empty: app or tunnel down (runs=[${offs[*]}]/[${accs[*]}])"
    return
  fi
  off=$(median "${offs[@]}"); acc=$(median "${accs[@]}")
  local pct=$(( acc * 100 / (off > 0 ? off : 1) ))
  if [[ "${off:-0}" -gt 0 && "$pct" -ge 90 ]]; then
    pass "PF-1 OFF=${off}Mbps ACCOUNT=${acc}Mbps (${pct}%) 8x30s runs=[${offs[*]}]/[${accs[*]}]"
  else fail "PF-1 OFF=${off}Mbps ACCOUNT=${acc}Mbps (${pct}%) want>=90% runs=[${offs[*]}]/[${accs[*]}]"; fi
}

mbps() { python3 -c "print(int($1 * 8 / $2 / 1e6))"; }

pf2_leg() { # <host> <mode> → p95 accept→ClientHello across 200 TLS connects; "" = not measured
  local pid
  set_mode "$2"
  ensure_live || { echo ""; return; }
  pid=$(pkg_pid)
  : > "$SERVER_LOG"
  tls_burst "$1" 200
  if [[ "$(pkg_pid)" != "$pid" ]]; then echo "  WARN: app died mid-burst" >&2; echo ""; return; fi
  p95_ttfc
}

pf2() { # 200 sequential TLS connections: p95 time-to-first-ClientHello ≤ OFF + 25 ms
  local host="$1" r offs=() accs=() off acc
  python3 "$HELLO_TOOL" du-pf-a "$TMP/du-pf-a.b64" >/dev/null
  adbs push "$TMP/du-pf-a.b64" /data/local/tmp/du-pf-a.b64 >/dev/null
  for r in $(seq 1 "$RUNS"); do offs+=("$(pf2_leg "$host" OFF)"); done
  for r in $(seq 1 "$RUNS"); do accs+=("$(pf2_leg "$host" ACCOUNT)"); done
  if has_empty "${offs[@]}" "${accs[@]}"; then
    fail "PF-2 no measurement — leg(s) empty: app/tunnel down or burst logged no ClientHello (runs=[${offs[*]}]/[${accs[*]}])"
    return
  fi
  off=$(median "${offs[@]}"); acc=$(median "${accs[@]}")
  local bound=$(( ${off:-0} + 25 ))
  if [[ -n "$off" && -n "$acc" && "$acc" -le "$bound" ]]; then
    pass "PF-2 p95 TTFC OFF=${off}ms ACCOUNT=${acc}ms (bound ${bound}ms) runs=[${offs[*]}]/[${accs[*]}]"
  else fail "PF-2 p95 OFF='$off'ms ACCOUNT='$acc'ms bound ${bound}ms runs=[${offs[*]}]/[${accs[*]}]"; fi
}

# A bare `printf 'x' | nc host 443` never reaches the SNI callback — the server would have
# nothing to time. The burst has to carry real ClientHello bytes (same primitive as the matrix).
tls_burst() { # <host> <n> — n sequential ClientHellos to the SNI-routed TLS port
  local host="$1" n="$2" i
  for i in $(seq 1 "$n"); do
    adbs shell "base64 -d /data/local/tmp/du-pf-a.b64 | nc -w 2 -q 1 $host 443" >/dev/null 2>&1
  done
}

p95_ttfc() { # the server logs the SNI-callback delta from accept: `hello port=443 dt_ms=N sni=1`
  grep -ao 'hello port=443 dt_ms=[0-9]*' "$SERVER_LOG" | cut -d= -f3 | sort -n | \
    python3 -c "
import sys
v=[int(x) for x in sys.stdin if x.strip()]
print(v[min(len(v)-1, int(len(v)*0.95))] if v else '')"
}

pf3() { # scripted browsing: process CPU time ≤ 1.15 × OFF
  local host="$1" r offs=() accs=() off acc
  for r in $(seq 1 "$RUNS"); do offs+=("$(pf3_leg "$host" OFF)"); done
  for r in $(seq 1 "$RUNS"); do accs+=("$(pf3_leg "$host" ACCOUNT)"); done
  if has_empty "${offs[@]}" "${accs[@]}"; then
    fail "PF-3 no measurement — leg(s) empty: the app process did not survive the leg (runs=[${offs[*]}]/[${accs[*]}])"
    return
  fi
  off=$(median "${offs[@]}"); acc=$(median "${accs[@]}")
  local bound=$(( off * 115 / 100 ))
  if [[ "$off" -gt 0 && "$acc" -le "$bound" ]]; then
    pass "PF-3 CPU OFF=${off}j ACCOUNT=${acc}j (bound ${bound}j, ${SCRIPT_MIN}min x${RUNS})"
  else fail "PF-3 CPU OFF='$off'j ACCOUNT='$acc'j bound ${bound}j runs=[${offs[*]}]/[${accs[*]}]"; fi
}

# A restarted process would make the jiffies delta a difference between two different processes,
# so the PID itself is the liveness test: read before and after the leg, compared, never assumed.
# One death is retried (the tunnel comes back via vpn_up); a second returns "" so the gate reports
# "no measurement" instead of scoring a number that was never measured.
pf3_leg() { # <host> <mode> → CPU ticks over one leg; "" = app did not survive two attempts
  local host="$1" mode="$2" pid j0 j1 attempt
  for attempt in 1 2; do
    set_mode "$mode"
    ensure_live || { echo "  WARN: tunnel could not be restored (attempt $attempt)" >&2; continue; }
    ready_probe "$host" || { echo "  WARN: tunnel carries no bytes (attempt $attempt)" >&2; continue; }
    pid=$(pkg_pid); j0=$(proc_jiffies "$pid")
    browse_seconds $(( SCRIPT_MIN * 60 )) "$host"
    sleep 3
    if [[ -z "$j0" || "$(pkg_pid)" != "$pid" ]]; then
      echo "  WARN: app died or restarted mid-leg (attempt $attempt) — leg invalid" >&2
      vpn_up || true
      continue
    fi
    j1=$(proc_jiffies "$pid")
    [[ -n "$j1" ]] && { echo $(( j1 - j0 )); return; }
    echo "  WARN: /proc/$pid/stat unreadable after leg (attempt $attempt)" >&2
  done
  echo ""
}

pf4() { # soak: ≥100 concurrent flows churn, heap growth after warm-up ≤ 10 MB, threads flat
  local host="$1" r growths=() threads_ok=1 growth
  # §11 measures every gate under the `OFF` vs `ACCOUNT` A/B condition; the soak exercises the
  # metering path, so pin it to ACCOUNT explicitly rather than inheriting whatever mode the
  # preceding gate's last leg happened to leave (the implicit version could soak mode=OFF).
  set_mode ACCOUNT
  ensure_live || { fail "PF-4 tunnel/mode not live for the soak"; return; }
  for r in $(seq 1 "$RUNS"); do
    PF4_INVALID=0
    pf4_leg "$host"
    if [[ "$PF4_INVALID" == 1 ]]; then
      fail "PF-4 soak leg invalid — app or tunnel did not survive it (environment, not a memory result)"
      return
    fi
    growths+=("$PF4_GROWTH")
    # Plan §11 PF-4 (owner-approved 2026-09-21, tolerance re-ruled 2026-09-22 — report §5.1 item
    # 15): flat = end ≤ start + 10. The tolerance absorbs in-process SDK keep-alive thread wander;
    # a real per-flow leak over a leg's ≥10,000 churned flows surfaces as tens-to-hundreds of
    # retained threads, an order of magnitude above this bound. +5 was measured too tight on the
    # X6532: across the 2026-09-22 legs the deltas read +6/+2/−3 — one leg over by a single
    # thread, while the heap median was −1 MB.
    if [[ -n "$PF4_THR0" && -n "$PF4_THR1" ]] && (( PF4_THR1 <= PF4_THR0 + 10 )); then :; else threads_ok=0; fi
  done
  growth=$(median "${growths[@]}")
  if [[ "$growth" -le 10 && "$threads_ok" == 1 ]]; then
    pass "PF-4 growth=${growth}MB (≤10) runs=[${growths[*]}]MB threads=${PF4_THR0}→${PF4_THR1} over ${SOAK_MIN}min, 120-flow churn"
  else fail "PF-4 growth=${growth}MB runs=[${growths[*]}]MB threads ${PF4_THR0}→${PF4_THR1} (flat=$threads_ok)"; fi
}

PF4_GROWTH=0 PF4_THR0="" PF4_THR1="" PF4_INVALID=0
pf4_leg() { # one warm-up + soak; sets PF4_GROWTH (MB), the thread pair, PF4_INVALID on app death
  local host="$1" i pid0 pid1 pss0 pss1
  ensure_live || { PF4_INVALID=1; echo "  WARN: app or tunnel down at soak start" >&2; return; }
  pid0=$(pkg_pid)
  churn_flows 120 "$host"                 # warm-up round: JIT, pools, first DB flush
  quiesced_snapshot
  pss0="$SETTLED_PSS"; PF4_THR0="$SETTLED_THR"
  local rounds=$(( SOAK_MIN * 60 / 20 ))
  for i in $(seq 1 "$rounds"); do
    churn_flows 120 "$host"
    sleep 12
  done
  pid1=$(pkg_pid)
  # A dead app is the false-PASS shape of the same 2026-09-21 kills PF-3 hit: empty PSS pairs
  # collapse to 0 in `$(( ))`, so the leg would report "0 MB growth" for an app that no longer
  # exists. The APP DEATH is what this leg must detect, not the arithmetic.
  if [[ "$pid1" != "$pid0" ]]; then
    PF4_INVALID=1; echo "  WARN: app died or restarted during the soak" >&2; return
  fi
  quiesced_snapshot
  pss1="$SETTLED_PSS"; PF4_THR1="$SETTLED_THR"
  [[ -n "$pss0" && -n "$pss1" && -n "$PF4_THR0" && -n "$PF4_THR1" ]] \
    || { PF4_INVALID=1; echo "  WARN: PSS or thread count unreadable" >&2; return; }
  PF4_GROWTH=$(( (pss1 - pss0) / 1024 ))
  echo "  soak leg: heap ${pss0}→${pss1}kB threads ${PF4_THR0}→${PF4_THR1}"
}

pf5() { # 1-hour screen-on browsing: drain ≤ OFF + 2 percentage points
  local host="$1"
  # All three charging paths, not just cable: a Qi pad reports `Wireless powered: true` and the
  # cable-only pattern would let a charging device through — the drain number would then be real
  # arithmetic on a battery that is being filled, i.e. a silently invalid leg.
  local powered; powered=$(adbs shell dumpsys battery 2>/dev/null | awk '/USB powered|AC powered|Wireless powered/{print $3}' | tr -d '\r' | tr '\n' ' ')
  if [[ "$powered" == *true* ]]; then
    skip "PF-5 (device is charging: drain cannot be measured — unplug and re-run)"; return
  fi
  # Screen-on is part of the gate, and unplugged means `stayon` (charging-only) cannot hold it:
  # push the display timeout out for the run and nudge the panel awake every minute — a sleeping
  # screen both halves the drain and invites the freeze/reap path that killed the app six times on
  # 2026-09-21. The original timeout is restored (or deleted, returning to the OEM default) after.
  local saved_to; saved_to=$(adbs shell settings get system screen_off_timeout 2>/dev/null | tr -d '\r')
  adbs shell settings put system screen_off_timeout 2147483647 >/dev/null 2>&1
  adbs shell svc power stayon true >/dev/null 2>&1
  adbs shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  local nudge=""
  ( while true; do adbs shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; sleep 60; done ) & nudge=$!
  local r offs=() accs=() off acc
  for r in $(seq 1 "$RUNS"); do
    offs+=("$(pf5_leg "$host" OFF)"); accs+=("$(pf5_leg "$host" ACCOUNT)")
  done
  kill "$nudge" 2>/dev/null
  adbs shell svc power stayon false >/dev/null 2>&1
  if [[ -z "$saved_to" || "$saved_to" == "null" ]]; then
    adbs shell settings delete system screen_off_timeout >/dev/null 2>&1
  else
    adbs shell settings put system screen_off_timeout "$saved_to" >/dev/null 2>&1
  fi
  # The invalid check sits *after* the stayon/timeout cleanup on purpose: an early return above
  # would leave the screen pinned on (and the timeout lifted) for the rest of the day.
  if has_empty "${offs[@]}" "${accs[@]}"; then
    fail "PF-5 no measurement — leg(s) empty: screen slept or app/tunnel died mid-leg (runs=[${offs[*]}]/[${accs[*]}])"
    return
  fi
  off=$(median "${offs[@]}"); acc=$(median "${accs[@]}")
  if [[ "$acc" -le $(( off + 2 )) ]]; then
    pass "PF-5 drain OFF=${off}pp ACCOUNT=${acc}pp over ${BATT_MIN}min screen-on browsing"
  else fail "PF-5 drain OFF=${off}pp ACCOUNT=${acc}pp (want ≤ $(( off + 2 ))pp) runs=[${offs[*]}]/[${accs[*]}]"; fi
}

pf5_leg() { # <host> <mode> → percentage points drained in one leg; "" = app did not survive it
  local pid b0 b1
  set_mode "$2"
  ensure_live || { echo ""; return; }
  ready_probe "$1" || { echo ""; return; }
  screen_awake || { echo "  WARN: screen not awake at leg start" >&2; echo ""; return; }
  pid=$(pkg_pid)
  b0=$(battery_level); browse_seconds $(( BATT_MIN * 60 )) "$1"; b1=$(battery_level)
  screen_awake || { echo "  WARN: screen slept mid-leg — drain invalid" >&2; echo ""; return; }
  [[ "$(pkg_pid)" == "$pid" ]] || { echo "  WARN: app died mid-leg" >&2; echo ""; return; }
  echo $(( b0 - b1 ))
}

pf6() { # allocation guard — the JVM test IS the gate (plan §11's regression-guard wording)
  if ./gradlew :app:testDebugUnitTest --tests '*Allocation*' -q >/tmp/du-pf6.log 2>&1; then
    pass "PF-6 allocation test green (see JVM suite)"
  else fail "PF-6 allocation test failed — /tmp/du-pf6.log"; fi
}

PF7_BOUND_MS=150  # plan §11, re-calibrated 2026-09-21 with owner approval (was 50 ms, which was
# never calibrated against SQLCipher on the low-end device; measured cost ~7.5 ms fixed + ~0.31
# ms/row, and the superseded two-statement form measured 327/573 ms so the bound still catches it).

pf7() { # DB flush of 200 rows: p99 ≤ §11's bound on the db thread
  local out; out=$(probe -a du-flush --ei n 200 --ei iters 20 | grep -a 'flush p50')
  local p99; p99=$(printf '%s' "$out" | grep -ao 'p99=[0-9.]*' | cut -d= -f2)
  if [[ -n "$p99" ]] && python3 -c "import sys; sys.exit(0 if float('$p99')<=$PF7_BOUND_MS else 1)"; then
    pass "PF-7 $out (bound ${PF7_BOUND_MS}ms)"
  else fail "PF-7 $out want p99<=${PF7_BOUND_MS}ms"; fi
}

pf8() { # RST/ICMP construction incl. checksum: < 1 ms per packet on the low-end device
  local out; out=$(probe -a du-bench --ei n 2000 | grep -a 'bench rst')
  # Split the line at ` icmp ` first: both halves carry a p99, so a pattern anchored on the
  # label ("rst p99=") never matches — the label is followed by p50 (device-caught 2026-09-21:
  # a 0.044 ms result was read as empty and reported FAIL) — and a greedy `.*p99=` would take
  # the icmp number for both.
  local left="${out%% icmp *}" right="${out#* icmp }" r p
  r=$(printf '%s' "$left" | grep -ao 'p99=[0-9.]*' | tail -1 | cut -d= -f2)
  p=$(printf '%s' "$right" | grep -ao 'p99=[0-9.]*' | tail -1 | cut -d= -f2)
  if [[ -n "$r" && -n "$p" ]] && \
     python3 -c "import sys; sys.exit(0 if float('$r')<1 and float('$p')<1 else 1)"; then
    pass "PF-8 $out"
  else fail "PF-8 $out want rst/icmp p99 < 1ms"; fi
}

# ---------------------------------------------------------------- main

mkdir -p "$TMP"
if [[ "$SKIP_BUILD" == 0 ]]; then
  say "build + install"
  ./gradlew :app:assembleDebug -q || exit 1
  adbs install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null 2>&1 || { echo "install failed"; exit 1; }
fi
adbs shell cmd appops set "$PKG" ACTIVATE_VPN allow >/dev/null 2>&1
adbs shell settings put global hide_error_dialogs 1 >/dev/null 2>&1

if [[ -z "$HOST" ]]; then
  HOST=$(ifconfig en0 2>/dev/null | awk '/inet /{print $2; exit}')
fi
echo "device=$SERIAL host=$HOST runs=$RUNS"
say "VPN up + 3 rules"
vpn_up || { echo "VPN did not come up"; exit 1; }
seed_rules || exit 1
seed_observations "a.$HOST.nip.io"   # attribution for the port-80 legs (§6.4 DNS fallback)

for pf in PF-1 PF-2 PF-3 PF-4 PF-5 PF-6 PF-7 PF-8; do
  wanted "$pf" || continue
  fn=$(echo "$pf" | tr 'A-Z' 'a-z' | tr -d '-')
  declare -F "$fn" >/dev/null || { skip "$pf (not implemented)"; continue; }
  say "$pf"
  "$fn" "$HOST"
done

say "summary"
printf '%s\n' "${RESULTS[@]}"
echo "passed=$PASSED failed=$FAILED skipped=$SKIPPED"
[[ "$FAILED" == 0 ]]

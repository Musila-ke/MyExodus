#!/usr/bin/env bash
# Domain-usage metering §12.2/§12.3 device matrix (plan S10).
#
# Everything that must happen *inside* the metered tunnel runs from the device shell (uid
# 2000), never from the app: INV-9 excludes the app from its own TUN, so an in-app client
# would bypass the very path under test (the S8 lesson). The app side is driven through
# DomainUsageTestReceiver (state, rules, counters) and DomainUsageTestActivity (VPN start —
# uid 2000 is refused `am start-foreground-service`).
#
# Host leg: scripts/domainusage-test-server.py (TLS 443/8443 SNI-routed, HTTP 80 with
# /stream + /sink, TCP 8080, v6-only TLS 9443) and scripts/domainusage-dns-probe.py.
#
# Usage: domainusage-device-test.sh --serial <adb-serial> [--host-v4 <ip>] [--host-v6 <addr>]
#                                   [--topology shared|c-only|v6-only] [--only I-1,I-4,I-17]
#
# The topology flag names what this device can reach (they differ by network, §12.2 note):
#   shared  — one host IP carries a and b; c is on a second IP (emulator: 10.0.2.2)
#   v6-only — the host's ULA on the v6-only port 9443 (physical device on the same LAN)
set -uo pipefail

# `adb` is not on PATH in a non-interactive shell (`bash script.sh`, CI), and every `adbs` call
# below then becomes a silent no-op: the run fails in ways that read as product faults rather
# than a missing tool (device-caught 2026-09-21 — PF-8 reported "VPN did not come up" against a
# live tunnel). The PF script carries the same line for the same reason.
command -v adb >/dev/null 2>&1 || PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"

SERIAL="" HOST_V4="" HOST_V6="" HOST_C="" TOPOLOGY="shared" ONLY="" SKIP_BUILD=0
PKG=com.example.myexodus
RCVR="$PKG/.domainusage.DomainUsageTestReceiver"
ACT="$PKG/.domainusage.DomainUsageTestActivity"
DNS_V4=192.0.2.53
DNS_V6="fd00:14:14::53"
TMP=/tmp/domainusage-matrix
HERE="$(cd "$(dirname "$0")" && pwd)"
DNS_TOOL="$HERE/domainusage-dns-probe.py"
HELLO_TOOL="$HERE/domainusage-hello-gen.py"
SERVER_LOG=/tmp/du-server.log

PASSED=0 FAILED=0 SKIPPED=0 JUDGED=0
declare -a RESULTS=()

# ---------------------------------------------------------------- plumbing

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --host-v4) HOST_V4="$2"; shift 2 ;;
    --host-v6) HOST_V6="$2"; shift 2 ;;
    --host-c) HOST_C="$2"; shift 2 ;;
    --topology) TOPOLOGY="$2"; shift 2 ;;
    --only) ONLY="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n "$SERIAL" ]] || { echo "--serial required (adb -s)"; exit 2; }

adbs() { adb -s "$SERIAL" "$@"; }
say() { printf '\n=== %s\n' "$*"; }
pass() { PASSED=$((PASSED + 1)); RESULTS+=("PASS $*"); printf 'PASS %s\n' "$*"; }
fail() { FAILED=$((FAILED + 1)); RESULTS+=("FAIL $*"); printf 'FAIL %s\n' "$*"; }
skip() { SKIPPED=$((SKIPPED + 1)); RESULTS+=("SKIP $*"); printf 'SKIP %s\n' "$*"; }
# judge — the evidence is an image a human reads, not a counter: never self-reports PASS.
judge() { JUDGED=$((JUDGED + 1)); RESULTS+=("JUDGE $*"); printf 'JUDGE %s\n' "$*"; }

wanted() { [[ -z "$ONLY" || ",$ONLY," == *",$1,"* ]]; }

# probe <action> [--es k v ...] → prints the DUPROBE lines it produced
probe() {
  adb -s "$SERIAL" logcat -c 2>/dev/null
  adbs shell am broadcast -n "$RCVR" "$@" >/dev/null 2>&1
  sleep "${PROBE_WAIT:-2}"
  # No `-t` window: logcat applies the count to the *whole* buffer before the `-s` tag filter, so
  # a small window returns almost nothing on this tag and a probe silently reads no counters
  # (device-caught 2026-09-21 — `-t 200 -s MyExodusVpn:I` yielded 7 lines where its own tag had
  # 197). The buffer is cleared immediately above, so the tag stream is already exactly this
  # probe's output; the host-side grep/tail bound it instead.
  adbs logcat -d -s MyExodusVpn:I 2>/dev/null | grep -a DUPROBE | sed 's/.*DUPROBE //'
}

# take <field> <probe-output> → last occurrence of field=value
take() { printf '%s\n' "$2" | grep -ao "$1=[^ ]*" | tail -1 | cut -d= -f2-; }
counter() { take "$1" "$(probe -a du-counters)"; }
# usage_of <id> — current-revision usage in ms, 0 when the ledger is not loaded yet. The S7
# reload is asynchronous, so a reading taken straight after a restart legitimately sees the
# API's own `null`; passing that through poisoned I-8's arithmetic under `set -u`
# ("null: unbound variable" — device-caught 2026-09-21). A number or 0, never a word.
usage_of() {
  local v; v=$(take ms "$(probe -a du-usage -e id "$1" | grep -a "id=$1")")
  if [[ "$v" =~ ^[0-9]+$ ]]; then printf '%s' "$v"; else printf '0'; fi
}
rule_revision() { take revision "$(probe -a du-rules -e id "$1" | grep -a "id=$1")"; }

add_rule() { # pattern limitSec → echoes the rule id (one retry: the snapshot update is async,
  # so a save right after a delete can still see the previous rule and be rejected as duplicate)
  local id
  id=$(take id "$(probe -a du-addrule --es pattern "$1" --el limitSec "$2" | grep -a 'addrule result=Ok')")
  if [[ -z "$id" ]]; then
    sleep 2
    id=$(take id "$(probe -a du-addrule --es pattern "$1" --el limitSec "$2" | grep -a 'addrule result=Ok')")
  fi
  printf '%s\n' "$id"
}
set_limit() { probe -a du-setlimit --es id "$1" --el limitSec "$2" >/dev/null; }
del_rule() { probe -a du-delrule --es id "$1" >/dev/null; }

sleep_ms() { sleep "$(echo "$1 / 1000" | bc -l)"; }

# ---------------------------------------------------------------- traffic (device shell, metered)

# dns <name> <qtype> [v6] → parsed reply lines from the handler inside the TUN
# dns <name> <qtype> [server] — defaults to the advertised v4 resolver. The transport family
# follows the address, not the query: any destination on port 53 is intercepted by the TUN
# (`udp.dstPort == 53`), which is what makes I-20's hardcoded-resolver leg possible.
dns() {
  local name="$1" qtype="$2" server="${3:-$DNS_V4}" sock=4
  [[ "$server" == *:* ]] && sock=6
  python3 "$DNS_TOOL" build "$name" "$qtype" "$TMP/q.b64" >/dev/null
  adbs push "$TMP/q.b64" /data/local/tmp/du-q.b64 >/dev/null
  local out
  # -q: toybox nc's -w only bounds connect, and a UDP socket has nothing to close it — without
  # -q the shell waits for a peer that will never speak again, and the probe hangs forever.
  out=$(adbs shell "base64 -d /data/local/tmp/du-q.b64 | nc -$sock -u -w 3 -q 3 $server 53 | base64" 2>/dev/null | tr -d '\r')
  [[ -n "$out" ]] || { echo "no-reply"; return; }
  # r.b64 stays base64 — the tool decodes it (cmd builds a query, parse reads a captured reply).
  printf '%s' "$out" > "$TMP/r.b64"
  python3 "$DNS_TOOL" parse "$TMP/r.b64"
}

# Toybox nc needs -6 for a literal IPv6 peer; without it the connect silently fails on a
# v6-only address (device-verified 2026-09-21).
nc_fam() { [[ "$1" == *:* ]] && echo "-6" || echo ""; }

# tls <label> <ip> <port> → synthesized ClientHello for <label> to <ip>:<port>
tls() {
  python3 "$HELLO_TOOL" "$1" "$TMP/$1.b64" >/dev/null
  adbs push "$TMP/$1.b64" "/data/local/tmp/$1.b64" >/dev/null
  adbs shell "base64 -d /data/local/tmp/$1.b64 | nc $(nc_fam "$2") -w 3 -q 3 $2 $3" >/dev/null 2>&1
}

# stream <label> <ip> <seconds> [port] — holds one flow, 1 KB every 2 s, for <seconds>
stream() {
  local label="$1" ip="$2" secs="$3" port="${4:-80}"
  # -q 1: /stream never closes, so without it nc (and any `wait` on it) hangs after the sleep.
  adbs shell "(printf 'GET /stream HTTP/1.1\r\nHost: $label\r\n\r\n'; sleep $secs) | nc $(nc_fam "$ip") -w 3 -q 1 $ip $port" >/dev/null 2>&1
}

# stream_bg — same, backgrounded (parallel-flow scenarios)
stream_bg() { stream "$@" & }

# udp443 <ip> — one datagram to a QUIC port (I-11 family). Also under timeout(1): a QUIC
# port that answers nothing (the test server has no UDP listener) hangs the same way I-6 did.
udp443() { adbs shell "printf 'x' | timeout 3 nc $(nc_fam "$1") -u -w 1 -q 1 $1 443" >/dev/null 2>&1; }

# apphttp <host> <port> — the app's own request (I-17; must never touch flows_tcp_total)
apphttp() { probe -a du-apphttp --es host "$1" --ei port "$2" | grep -a apphttp; }

# ---------------------------------------------------------------- setup

# name_for_ip <label> <ip|addr> — nip.io/sslip.io encode the address in the label, so the device
# resolves the name to *that* address and the app's DNS observation is what binds the flow.
name_for_ip() {
  if [[ "$2" == *:* ]]; then echo "$1.$(echo "$2" | tr ':' '-').sslip.io"
  else echo "$1.$2.nip.io"; fi
}

ip_for() { # ip_for <label> — a and b share one address (I-1/I-2); c has its own (I-3)
  case "$1" in
    du-c) echo "$HOST_C" ;;
    *) if [[ "$TOPOLOGY" == v6-only ]]; then echo "$HOST_V6"; else echo "$HOST_V4"; fi ;;
  esac
}
names() { name_for_ip "$1" "$(ip_for "$1")"; }

IPA_NAME="" IPB_NAME="" IPC_NAME=""

setup() {
  mkdir -p "$TMP"
  if [[ "$SKIP_BUILD" == 0 ]]; then
    say "build + install"
    ./gradlew :app:assembleDebug -q || { echo "build failed"; exit 1; }
  fi
  adbs install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null 2>&1 || { echo "install failed"; exit 1; }
  # XOS revokes the grant on reinstall; the consent dialog needs a physical tap otherwise.
  adbs shell cmd appops set "$PKG" ACTIVATE_VPN allow >/dev/null 2>&1
  adbs shell settings put global hide_error_dialogs 1 >/dev/null 2>&1

  if [[ -z "$HOST_V4" ]]; then
    HOST_V4=$(ifconfig en0 | awk '/inet /{print $2; exit}')
  fi
  if [[ -z "$HOST_V6" ]]; then
    HOST_V6=$(ifconfig en0 | awk '/inet6 /{if ($2 !~ /^fe80/) {print $2; exit}}' | cut -d% -f1)
  fi
  # c needs an address of its own, or I-1/I-2's shared-IP ambiguity swallows I-3. The emulator
  # reaches the host's loopback as 10.0.2.2; on LAN the second address is the host's ULA, which
  # also exercises the v6 leg. If neither is reachable, --host-c pins one explicitly.
  if [[ -z "$HOST_C" ]]; then
    if [[ "$TOPOLOGY" == v6-only ]]; then HOST_C="$HOST_V6"
    elif [[ "$SERIAL" == emulator-* ]]; then HOST_C=10.0.2.2
    else HOST_C="$HOST_V6"; fi
  fi
  IPA_NAME=$(names du-a); IPB_NAME=$(names du-b); IPC_NAME=$(names du-c)
  adbs shell "cmd package compile -m speed -f $PKG" >/dev/null 2>&1
  echo "device=$SERIAL host_v4=$HOST_V4 host_v6=$HOST_V6 host_c=$HOST_C topology=$TOPOLOGY"
  echo "names: a=$IPA_NAME b=$IPB_NAME c=$IPC_NAME"
}

vpn_start_attempt() { # one `am start` and up to <n> seconds for the state line
  adbs shell am start -n "$ACT" >/dev/null 2>&1
  for _ in $(seq 1 "$1"); do
    adbs logcat -d -s MyExodusVpn:I 2>/dev/null | grep -aq "DUPROBE vpn-state=" && return 0
    sleep 1
  done
  return 1
}

vpn_start() {
  adb -s "$SERIAL" logcat -c 2>/dev/null
  # 240 s, not 90: a cold start on the API 37 emulator (slowest reference target, §3.1) spends
  # most of a minute before the tunnel is up, and a window that expires first reports a stopped
  # VPN the product never had (device-caught 2026-09-21). Two I-10 attempts died the same way on
  # the Lean_API34 emulator: with the host loaded, the tunnel needed ~2.5 min to come up and the
  # 90 s window expired first — the leg then read an empty ledger day and the clock guard refused
  # the no-op (no false pass, but no result either). The loop leaves as soon as the state line
  # lands, so the wider window costs nothing on a fast device.
  vpn_start_attempt 240 || vpn_start_attempt 120 ||
    # Second chance (device-caught 2026-09-21 on the X6532): one start out of a long matrix
    # landed while the previous instance was still winding down and produced no state line at
    # all — the run then measured a stopped tunnel. The second `am start` is harmless when the
    # first did start (the service ignores ACTION_START unless it is STOPPED) and settles the
    # race when it did not.
    say "VPN start: no state line after two attempts"
  adbs logcat -d -s MyExodusVpn:I 2>/dev/null | grep -a "DUPROBE vpn" | tail -2
}

vpn_stop() { adbs shell am start -n "$ACT" --ez stop true >/dev/null 2>&1; sleep 2; }

set_mode() { # <mode>: persist the engine-mode override (S1.3). The Engine reads it at start(),
  # so it takes effect on the next vpn_start — every leg gets one through fresh_vpn. Before this
  # existed the mode was whatever override an earlier run happened to persist, and every leg of a
  # run silently shared it (device-caught 2026-09-21: the PF harness's OFF and ACCOUNT legs both
  # ran OFF because nothing re-started the engine between legs).
  probe -a du-mode --es mode "$1" >/dev/null
}

rules_reset() { probe -a du-delall >/dev/null; }

# Between scenarios the rules are re-seeded, the tunnel is cycled and only then does the next
# scenario run. Both halves are load-bearing (device-caught 2026-09-21, matrix round 3):
#   - a stale DNS candidate from the previous scenario's probe makes the next scenario's flow
#     ambiguous on the shared address, so nothing is attributed and nothing accrues;
#   - `stop()` is the only seam that clears the observation store and flow registry (S-6);
#   - the engine status is NOT a liveness signal — an empty rule set reports a legitimate
#     Dormant (FR-12), so `vpn=` (the tunnel's own state) is what the harness reads.
# Scenarios that stop or revoke the VPN (I-15) are covered by the same cycle.
fresh_vpn() {
  rules_reset
  vpn_stop
  vpn_start
  local st; st=$(take vpn "$(probe -a du-status)")
  [[ "$st" == ACTIVE || "$st" == DEGRADED ]] || say "VPN not up after restart (vpn=$st)"
}

# ---------------------------------------------------------------- scenarios

# I-1 (§12.2): rule on a, browse b over the shared IP — a's usage must stay 0.
i1() {
  local a b ip id
  a="$IPA_NAME"; b="$IPB_NAME"; ip=$(ip_for du-a)
  id=$(add_rule "$a" 3600)
  [[ -n "$id" ]] || { fail "I-1 addrule"; return; }
  # Both names resolve to the shared address, so the port-80 flow is ambiguous by DNS
  # (§6.4) — which is exactly the case that must never be charged to a's rule.
  dns "$a" A >/dev/null
  dns "$b" A >/dev/null
  stream_bg "$b" "$ip" 20
  stream "$b" "$ip" 25
  wait
  local used; used=$(usage_of "$id")
  if [[ "${used:-x}" == 0 ]]; then pass "I-1 usage_a=0 (b browsed 45s on shared ip)"
  else fail "I-1 usage_a=$used want=0"; fi
  echo "$id" > "$TMP/i1-id"
}

# I-2: DNS resolved a and b, raw TCP to the shared IP on 8443 → ambiguous, allowed.
i2() {
  local a b ip
  a="$IPA_NAME"; b="$IPB_NAME"; ip=$(ip_for du-a)
  # The rule is what makes the two candidates disagree: one ruled, one not is a disagreement
  # (§4.6/D-11), while two unruled candidates are simply "no opinion" and never count.
  add_rule "$a" 3600 >/dev/null
  dns "$a" A >/dev/null; dns "$b" A >/dev/null
  local before after
  before=$(counter dns_fallback_ambiguous)
  adbs shell "printf 'x' | nc -w 3 $ip 8443" >/dev/null 2>&1
  sleep 2
  after=$(counter dns_fallback_ambiguous)
  if [[ "${after:-0}" -gt "${before:-0}" ]]; then pass "I-2 ambiguous=${before}→$after (allowed)"
  else fail "I-2 ambiguous counter did not move (${before}→$after)"; fi
}

# I-3: rule on c, DNS resolve c, TCP to 8080 — attributed by DNS to c's rule.
i3() {
  local c ip id qt=""
  c="$IPC_NAME"; ip=$(ip_for du-c)
  [[ "$ip" == *:* ]] && qt=AAAA                 # c's own address may be the host's ULA
  # Learn c's address *before* the rule exists. AAAA under a live rule is shaped to NODATA
  # (D-14), so the post-rule probe returns no answer and records no observation — the client
  # this leg models is one that already had the address, which is the only state in which a
  # v6 flow can be attributed at all (§6.4, same shape as I-13).
  # Query transport stays v4 even for a v6 answer: this LAN is ULA-only, so a forwarded query
  # sent to the v6 resolver never gets an upstream reply (device-observed 2026-09-21) — only
  # TUN-written shaped answers come back over v6. The observation keys the answer's address,
  # not the transport it arrived on.
  dns "$c" "${qt:-A}" >/dev/null
  id=$(add_rule "$c" 3600)
  [[ -n "$id" ]] || { fail "I-3 addrule"; return; }
  stream "$c" "$ip" 20 8080
  local used; used=$(usage_of "$id")
  if [[ "${used:-0}" -ge 15000 ]]; then pass "I-3 dns-attributed usage=${used}ms"
  else fail "I-3 usage=$used want>=15000"; fi
  echo "$id" > "$TMP/i3-id"
}

# I-4: 8 parallel streams to a for 20 s → ≈35 s (20 + 15 s tail), never 8×20.
i4() {
  local a ip id
  a="$IPA_NAME"; ip=$(ip_for du-a)
  id=$(add_rule "$a" 3600)
  [[ -n "$id" ]] || { fail "I-4 addrule"; return; }
  dns "$a" A >/dev/null   # port 80 carries no SNI: attribution is the DNS candidate (§6.4)
  for _ in 1 2 3 4 5 6 7 8; do stream_bg "$a" "$ip" 20; done
  wait
  sleep 16   # the T_IDLE tail: accrual stops 15 s after the last chunk
  local used; used=$(usage_of "$id")
  if [[ "${used:-0}" -ge 32000 && "${used:-0}" -le 38000 ]]; then pass "I-4 usage=${used}ms (~35s, not 160s)"
  else fail "I-4 usage=$used want 32000..38000"; fi
  echo "$id" > "$TMP/i4-id"
}

# I-5: one request then idle 60 s → ≤17 s.
i5() {
  local a ip id
  a="$IPA_NAME"; ip=$(ip_for du-a)
  id=$(add_rule "$a" 3600)
  [[ -n "$id" ]] || { fail "I-5 addrule"; return; }
  tls "$a" "$ip" 443
  sleep 60
  local used; used=$(usage_of "$id")
  if [[ "${used:-99999}" -le 17000 ]]; then pass "I-5 usage=${used}ms (<=17s)"
  else fail "I-5 usage=$used want<=17000"; fi
  echo "$id" > "$TMP/i5-id"
}

# I-6: 100-byte UDP every 5 s for 60 s → 0 (below MIN_ACTIVITY_BYTES, ICMP is not metered).
i6() {
  local a ip id
  a="$IPA_NAME"; ip=$(ip_for du-a)
  id=$(add_rule "$a" 3600)
  [[ -n "$id" ]] || { fail "I-6 addrule"; return; }
  # timeout(1) around the send, not just -w: toybox's -w bounds a TCP connect, and a UDP
  # socket whose peer never answers (port 9 has no listener, so nothing replies and nothing
  # resets) sits in nc's blocking read forever — `-q 1` alone left the first iteration hung
  # past the 10-minute mark (device-caught 2026-09-21). The pipe closes stdin; timeout ends
  # the wait regardless of what the network does.
  for _ in $(seq 1 12); do
    adbs shell "printf '%0100d' 0 | timeout 3 nc -u -w 1 -q 1 $ip 9" >/dev/null 2>&1
    sleep 5
  done
  local used; used=$(usage_of "$id")
  if [[ "${used:-x}" == 0 ]]; then pass "I-6 usage=0 after 60s of 100B datagrams"
  else fail "I-6 usage=$used want=0"; fi
  echo "$id" > "$TMP/i6-id"
}

# I-7: limit exhausted mid-stream → the held connection is reset, then a new lookup is NXDOMAIN.
i7() {
  local a ip id
  a="$IPA_NAME"; ip=$(ip_for du-a)
  id=$(add_rule "$a" 60)   # R-LIMIT-BOUNDS floor is 60 s (the plan's 30 s example predates it)
  [[ -n "$id" ]] || { fail "I-7 addrule"; return; }
  dns "$a" A >/dev/null
  # Sampled *before* the flow opens. Exhaustion cuts the held flow in place — a FlowRegistry
  # sweep, counted by `flow_resets_sweep`, not by `rst_injected` (which counts §6.10's built
  # RST packets) — so the original reading at t+63 was already post-reset and could never
  # move (device-caught 2026-09-21).
  local rst0; rst0=$(counter flow_resets_sweep)
  stream_bg "$a" "$ip" 100
  local stream_pid=$!
  sleep 63
  local rst1; rst1=$(counter flow_resets_sweep)
  local nx; nx=$(dns "$a" A | head -1)
  local nx6; nx6=$(dns "$a" AAAA | head -1)
  # The sweep should already have torn the connection down; this only reaps it if it did not.
  kill "$stream_pid" 2>/dev/null; wait "$stream_pid" 2>/dev/null
  local ok=1
  [[ "${rst1:-0}" -gt "${rst0:-0}" ]] || ok=0
  [[ "$nx" == *"rcode=3"* ]] || ok=0
  [[ "$nx6" == *"rcode=3"* ]] || ok=0
  if [[ "$ok" == 1 ]]; then pass "I-7 sweep=${rst0}→$rst1 nxdomain(A,AAAA)"
  else fail "I-7 sweep=${rst0}→$rst1 a='$nx' aaaa='$nx6'"; fi
  echo "$id" > "$TMP/i7-id"
}

# I-8: accrual then `am force-stop`, restart → restored total ≥ used − (persist interval + age).
i8() {
  local a ip id
  a="$IPA_NAME"; ip=$(ip_for du-a)
  id=$(add_rule "$a" 3600)
  [[ -n "$id" ]] || { fail "I-8 addrule"; return; }
  dns "$a" A >/dev/null
  stream "$a" "$ip" 25
  sleep 12                                   # one PERSIST_INTERVAL_MS (10 s) after the flow
  local before; before=$(usage_of "$id")
  local checkpoint_age=$((PERSIST_INTERVAL_MS_S + 5))
  adbs shell am force-stop "$PKG"
  sleep 3
  vpn_start >/dev/null
  # The reload is asynchronous: poll rather than assume one interval is enough, and stop as
  # soon as the rule reports a total (a 0 here would read as a failed restore, not a slow one).
  local after=0 i
  for i in $(seq 1 15); do
    after=$(usage_of "$id")
    [[ "${after:-0}" -gt 0 ]] && break
    sleep 2
  done
  local floor=$(( ${before:-0} - (checkpoint_age * 1000) ))
  if [[ "${after:-0}" -ge "$floor" && "${after:-0}" -gt 0 ]]; then
    pass "I-8 restored=${after}ms (in-memory $before, bound ${floor}ms)"
  else fail "I-8 restored=$after in-memory=$before bound=$floor"; fi
  echo "$id" > "$TMP/i8-id"
}
PERSIST_INTERVAL_MS_S=10

# I-9/I-10 move the wall clock, which needs root — the plan scopes both to the emulator, so a
# device that refuses `adb root` skips rather than reports a failure it cannot have caused.
need_root() {
  adbs root >/dev/null 2>&1
  sleep 1
  [[ "$(adbs shell id -u 2>/dev/null | tr -d '\r')" == 0 ]]
}

# The clock legs set the wall clock by hand; hand it back to NTP before anything else runs, or
# every later scenario measures against a device a day off. The emulator's NTP resync is not
# prompt, and a restore that only *asks* for auto_time leaves the device a day off for every
# later leg (device-caught 2026-09-21: I-10 ran while the device still read 09-20), so the
# device date is set from the host as well — auto_time stays on to correct the small drift.
clock_restore() {
  adbs shell settings put global auto_time 1 >/dev/null 2>&1
  local want; want=$(date +%m%d%H%M%Y.%S)
  adbs shell "date $want" >/dev/null 2>&1
  sleep 2
}

# Handing the clock to `date` needs the automatic sync off first: with auto_time on, the
# emulator's own time sync snaps the guest back and the jump silently never happens
# (device-caught 2026-09-21 — I-10's midnight never arrived and the leg blamed the product).
clock_set() {
  [[ -n "${1:-}" ]] || { echo "clock_set: empty date string — refusing to run a no-op"; return 1; }
  adbs shell settings put global auto_time 0 >/dev/null 2>&1
  adbs shell "date $1" >/dev/null 2>&1
}

# The ledger's effective day key, read from the product (§4.8) — the wall clock is precisely what
# these two legs make diverge from it. It is also monotonic within a run: `start()` raises the key
# to `max(today, persistedLastDayKey)` and only a forward move re-keys, so a device clock left a
# day ahead by an earlier run keeps the key forward even after the clock is set back
# (device-caught 2026-09-21 — the legs read the device date instead and asserted against a day the
# product had legitimately never opened).
ledger_day() { take day "$(probe -a du-status)"; }

# I-9 (emulator): wall clock back 1 day mid-use → effective day key unchanged, accrual continues.
i9() {
  need_root || { skip "I-9 (wall-clock leg — needs root; run on the emulator)"; return; }
  local a ip id
  a="$IPA_NAME"; ip=$(ip_for du-a)
  id=$(add_rule "$a" 3600)
  [[ -n "$id" ]] || { fail "I-9 addrule"; return; }
  dns "$a" A >/dev/null
  local day0; day0=$(ledger_day)
  stream_bg "$a" "$ip" 24
  sleep 6
  local used_pre; used_pre=$(usage_of "$id")
  local jump_before; jump_before=$(counter clock_jump)
  # Back one day from the *ledger's* day, never from the device's: on a device whose clock was
  # left ahead by an earlier run the two differ, and only the ledger's day is the invariant.
  # `-v` must precede `-f` on BSD date: after the `-f` operands it is swallowed and the output
  # format is ignored, so the clock silently never moves (device-caught 2026-09-21: jump=0→0).
  clock_set "$(date -j -v-1d -f "%Y-%m-%d %H:%M:%S" "$day0 12:00:00" "+%m%d%H%M%Y.%S")"
  sleep 20
  wait
  sleep 11                       # the row lands on the 10 s persist cycle, not on accrual
  local used_post; used_post=$(usage_of "$id")
  # Asserted against the day key the ledger opened on, never against the wall clock: the whole
  # point of the leg is that the two diverge after the jump (device-caught 2026-09-21 — the
  # earlier form read `du-rows` with no day, so it queried the *jumped* day and found the row
  # the product had correctly kept under the pre-jump one).
  local row; row=$(probe -a du-rows --es day "$day0" | grep -a "id=$id" | tail -1)
  local jump_after; jump_after=$(counter clock_jump)
  local day_after; day_after=$(ledger_day)
  local ok=1
  [[ "${used_post:-0}" -gt "${used_pre:-0}" ]] || ok=0
  [[ -n "$row" ]] || ok=0
  [[ "${jump_after:-0}" -gt "${jump_before:-0}" ]] || ok=0
  [[ "$day_after" == "$day0" ]] || ok=0
  if [[ "$ok" == 1 ]]; then
    pass "I-9 clock_jump=${jump_before}→$jump_after accrual ${used_pre}→${used_post} day=$day0 unchanged, row: $row"
  else fail "I-9 jump=${jump_before}→$jump_after accrual ${used_pre}→${used_post} day ${day0}→${day_after} row='$row'"; fi
  clock_restore
  echo "$id" > "$TMP/i9-id"
}

# I-10 (emulator): cross local midnight during use → split across two day keys at the boundary,
# and the new day starts unblocked.
# The clock is set to 23:58:30 and the rule's limit is 60 s, so the exhaust lands (~23:59:30)
# inside the old day; the pre-boundary flow is RST'd by that exhaustion, so the post-boundary
# half needs a fresh flow of its own — the split is a property of the ledger, not of a socket.
# Both halves are then observable: the old day's persisted row holds the pre-boundary time, the
# in-memory counter holds only the post-boundary time, and the DNS answer flips NXDOMAIN →
# answered across the boundary (fresh day, fresh counter — FR-12).
i10() {
  need_root || { skip "I-10 (midnight-rollover leg — needs root; run on the emulator)"; return; }
  local a ip id
  a="$IPA_NAME"; ip=$(ip_for du-a)
  # The boundary is crossed relative to the *ledger's* day (§4.8), so the leg holds on a device
  # whose clock or persisted key was left a day ahead by an earlier run.
  # The rule must exist before the day is read: with an empty rule set the engine is legitimately
  # Dormant (FR-12) and reports day=, which makes clock_set a refused no-op (device-caught
  # 2026-09-21, emulator re-run #5 on a freshly booted guest).
  id=$(add_rule "$a" 60)
  [[ -n "$id" ]] || { fail "I-10 addrule"; return; }
  local day_old; day_old=$(ledger_day)
  clock_set "$(date -j -f "%Y-%m-%d %H:%M:%S" "$day_old 23:58:30" "+%m%d%H%M%Y.%S")"
  dns "$a" A >/dev/null
  stream_bg "$a" "$ip" 90
  sleep 70                       # ~23:59:40: the 60 s limit is spent, midnight is 20 s away
  local pre; pre=$(dns "$a" A | head -1)
  sleep 25                       # ~00:00:05: the boundary is behind us
  local post; post=$(dns "$a" A | head -1)
  stream "$a" "$ip" 25           # the new day's own accrual, on a connection the old day never reset
  sleep 11                       # let the 10 s persist cycle write the outgoing day's finals
  wait
  local row; row=$(probe -a du-rows --es day "$day_old" | grep -a "id=$id" | tail -1)
  local old_ms; old_ms=$(take ms "$row")
  local new_ms; new_ms=$(usage_of "$id")
  local ok=1
  [[ "$pre" == *"rcode=3"* ]] || ok=0
  [[ "$post" == *"rcode=0"* ]] || ok=0
  [[ "${old_ms:-0}" -gt 0 ]] || ok=0
  [[ "${new_ms:-0}" -gt 0 ]] || ok=0
  if [[ "$ok" == 1 ]]; then
    pass "I-10 exhausted pre-midnight→NXDOMAIN, post-midnight $post; day=$day_old row: $row; new day ${new_ms}ms"
  else fail "I-10 pre='$pre' post='$post' day=$day_old row='$row' new_day=${new_ms}ms"; fi
  clock_restore
  echo "$id" > "$TMP/i10-id"
}

# I-11: exhausted rule → UDP/443 dropped with an ICMP nudge (the QUIC escape);
# I-11b: an unrelated IP's UDP/443 is never touched.
i11() {
  local a ip id other
  a="$IPA_NAME"; ip=$(ip_for du-a); other=$(ip_for du-c)
  id=$(add_rule "$a" 60)
  [[ -n "$id" ]] || { fail "I-11 addrule"; return; }
  dns "$a" A >/dev/null
  stream "$a" "$ip" 65          # exhaust the limit and end unambiguous
  sleep 2
  local udp0 icmp0 udp1 icmp1
  udp0=$(counter udp_dropped); icmp0=$(counter icmp_injected)
  udp443 "$ip"
  sleep 2
  udp1=$(counter udp_dropped); icmp1=$(counter icmp_injected)
  local other0 other1
  other0=$(counter udp_dropped)
  udp443 "$other"
  sleep 2
  other1=$(counter udp_dropped)
  local ok=1
  [[ "${udp1:-0}" -gt "${udp0:-0}" ]] || ok=0
  [[ "${icmp1:-0}" -gt "${icmp0:-0}" ]] || ok=0
  [[ "${other1:-0}" == "${other0:-0}" ]] || ok=0
  # AC-19 (plan §17 names I-11b as its ACCOUNT evidence): the same unrelated-IP guarantee with
  # the mode actually ACCOUNT — ENFORCE above proves the scoping, this proves the metering-only
  # mode never drops. The override applies at the next engine start, so the tunnel is cycled and
  # a rule re-seeded so "while another rule is exhausted" still holds (plan §12.2 wording).
  set_mode ACCOUNT
  fresh_vpn
  id=$(add_rule "$a" 60)
  dns "$a" A >/dev/null
  stream "$a" "$ip" 65
  sleep 2
  local acct acct_dns
  acct=$(take mode "$(probe -a du-status)")
  acct_dns=$(dns "$a" A | head -1)
  other0=$(counter udp_dropped)
  udp443 "$other"
  sleep 2
  other1=$(counter udp_dropped)
  [[ "$acct" == ACCOUNT ]] || ok=0
  [[ "$acct_dns" == *"rcode=0"* ]] || ok=0
  [[ "${other1:-0}" == "${other0:-0}" ]] || ok=0
  if [[ "$ok" == 1 ]]; then
    pass "I-11 udp_dropped=${udp0}→$udp1 icmp=${icmp0}→$icmp1; I-11b unrelated ip untouched ($other0, ACCOUNT mode=$acct exhausted-rule DNS $acct_dns)"
  else fail "I-11 udp=${udp0}→$udp1 icmp=${icmp0}→$icmp1 unrelated=${other0}→$other1"; fi
  echo "$id" > "$TMP/i11-id"
}

# I-12: Chrome to Cloudflare trace with a live rule → HTTPS/SVCB suppressed, no ECH.
i12() {
  local id
  id=$(add_rule www.cloudflare.com 3600)
  [[ -n "$id" ]] || { fail "I-12 addrule"; return; }
  local h0 h1 s0 s1
  h0=$(counter dns_nodata_https); s0=$(counter dns_nodata_svcb)
  adbs shell am start -a android.intent.action.VIEW -d https://www.cloudflare.com/cdn-cgi/trace >/dev/null 2>&1
  sleep 12
  h1=$(counter dns_nodata_https); s1=$(counter dns_nodata_svcb)
  adbs exec-out screencap -p > "$TMP/i12-chrome.png" 2>/dev/null
  if [[ "${h1:-0}" -gt "${h0:-0}" || "${s1:-0}" -gt "${s0:-0}" ]]; then
    pass "I-12 nodata https=${h0}→$h1 svcb=${s0}→$s1 (screenshot $TMP/i12-chrome.png)"
  else fail "I-12 no HTTPS/SVCB suppression (${h0}→$h1, ${s0}→$s1)"; fi
  echo "$id" > "$TMP/i12-id"
}

# I-12b (Android 17): a non-metered ECH connection is never disturbed. The platform must default
# to ECH for the leg to mean anything, so a pre-17 device skips: on the X6532 (SDK 34) Chrome
# still sends a plaintext SNI and the counter delta would read 0→0 and be judged as a product
# fault it is not (device-caught 2026-09-21 — the leg's ECH evidence came from the API 37 image).
i12b() {
  local sdk; sdk=$(adbs shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
  if [[ -z "$sdk" || "$sdk" -lt 37 ]]; then
    skip "I-12b (ECH-by-platform-default needs SDK>=37; this device is ${sdk:-unknown} — run on the API 37 image)"
    return
  fi
  local sni0; sni0=$(counter sni_ech)
  adbs shell am start -a android.intent.action.VIEW -d https://tls-ech.dev >/dev/null 2>&1
  sleep 15
  adbs exec-out screencap -p > "$TMP/i12b-chrome.png" 2>/dev/null
  local sni1; sni1=$(counter sni_ech)
  judge "I-12b chrome->tls-ech.dev sni_ech=${sni0}→$sni1 — read $TMP/i12b-chrome.png"
}

# I-13: a v6 host under a rule — its AAAA is suppressed, so a client that has not already
# learned the address cannot reach it at all; a literal connection to an address learned
# *before* the rule existed is reset once the rule is exhausted. I-13b: an unrelated v6 host
# still resolves (no global AAAA suppression).
# DNS legs run over the v4 resolver by design: this network is ULA-only, so the advertised v6
# resolver has no reachable upstream (device-observed 2026-09-21) — the shaped answers these
# legs assert are written from the TUN and are served over either family.
i13() {
  local a other ip id
  a=$(name_for_ip du-a "$HOST_V6"); other=$(name_for_ip du-b "$HOST_V6"); ip="$HOST_V6"
  # Learn a's address while no rule exists — the observation outlives the later suppression
  # (TTL-bounded), and it is the only way a v6 literal flow can be attributed at all (§6.4).
  dns "$a" AAAA >/dev/null
  id=$(add_rule "$a" 60)
  [[ -n "$id" ]] || { fail "I-13 addrule"; return; }
  local suppressed; suppressed=$(dns "$a" AAAA | head -1)
  stream "$a" "$ip" 65 8080
  sleep 2
  local rst0; rst0=$(counter rst_injected)
  adbs shell "printf 'x' | nc -6 -w 3 $ip 9443" >/dev/null 2>&1
  sleep 2
  local rst1; rst1=$(counter rst_injected)
  # Probed last: resolving the unrelated host would otherwise add a second candidate for the
  # same address and turn the literal flow above ambiguous (§4.6).
  local nodata; nodata=$(dns "$other" AAAA | head -1)
  local ok=1
  [[ "$suppressed" == *"an=0"* && "$suppressed" != *"no-reply"* ]] || ok=0
  [[ "$nodata" == *"an=1"* || "$nodata" == *"an=2"* ]] || ok=0
  [[ "${rst1:-0}" -gt "${rst0:-0}" ]] || ok=0
  # AC-19 (plan §17 names I-13b as its ACCOUNT evidence): with the mode actually ACCOUNT neither
  # the ruled host's AAAA nor the unrelated one is reshaped — ENFORCE above proves the scoping,
  # this proves the metering-only mode never shapes. Override applies at the next engine start,
  # so the tunnel is cycled first.
  set_mode ACCOUNT
  fresh_vpn
  id=$(add_rule "$a" 60)
  dns "$a" AAAA >/dev/null
  stream "$a" "$ip" 65 8080        # take the rule to its limit first: AC-19 covers the exhausted case
  sleep 2
  local acct acct_real acct_other
  acct=$(take mode "$(probe -a du-status)")
  acct_real=$(dns "$a" AAAA | head -1)
  acct_other=$(dns "$other" AAAA | head -1)   # plan I-13b wording: the unrelated host still works
  [[ "$acct" == ACCOUNT ]] || ok=0
  [[ "$acct_real" == *"an=1"* || "$acct_real" == *"an=2"* ]] || ok=0
  [[ "$acct_other" == *"an=1"* || "$acct_other" == *"an=2"* ]] || ok=0
  if [[ "$ok" == 1 ]]; then
    pass "I-13 aaaa(rule)=NODATA aaaa(unrelated) real + literal rst=${rst0}→$rst1 (I-13b; ACCOUNT mode=$acct aaaa(rule) real $acct_real unrelated real $acct_other)"
  else fail "I-13 suppressed='$suppressed' unrelated='$nodata' rst=${rst0}→$rst1 acct mode=$acct aaaa='$acct_real' acct other='$acct_other'"; fi
  echo "$id" > "$TMP/i13-id"
}

# I-14 / I-14b: strict Private DNS → Degraded(PRIVATE_DNS), clears when the network reverts.
i14() {
  adbs root >/dev/null 2>&1
  local before; before=$(counter tamper_private_dns)
  adbs shell settings put global private_dns_mode hostname >/dev/null 2>&1
  adbs shell settings put global private_dns_specifier one.one.one.one >/dev/null 2>&1
  sleep 6
  local st; st=$(take status "$(probe -a du-status)")
  local after; after=$(counter tamper_private_dns)
  adbs shell settings put global private_dns_mode off >/dev/null 2>&1
  sleep 6
  local st2; st2=$(take status "$(probe -a du-status)")
  local ok=1
  [[ "$st" == *PRIVATE_DNS* ]] || ok=0
  [[ "${after:-0}" -gt "${before:-0}" ]] || ok=0
  [[ "$st2" != *PRIVATE_DNS* ]] || ok=0
  if [[ "$ok" == 1 ]]; then pass "I-14 strict=$st tamper=${before}→$after; I-14b cleared=$st2"
  else fail "I-14 strict='$st' tamper=${before}→$after cleared='$st2'"; fi
}

# I-15: revoke path — counters + notification + a final flush; next start reloads totals.
i15() {
  local a ip id used persisted notes0 notes1
  a="$IPA_NAME"; ip=$(ip_for du-a)
  id=$(add_rule "$a" 3600)
  [[ -n "$id" ]] || { fail "I-15 addrule"; return; }
  # Port-80 flows are attributed by the DNS fallback alone (§6.4): with no prior observation
  # this leg accrues nothing and the flush then has nothing to persist, which reads exactly
  # like a flush bug (device-caught 2026-09-21 — in-memory 0, no row).
  dns "$a" A >/dev/null
  stream "$a" "$ip" 20
  sleep 12
  used=$(usage_of "$id")
  notes0=$(notif_count)
  probe -a du-revoke >/dev/null
  sleep 4
  persisted=$(probe -a du-rows | grep -a "id=$id" | tail -1)
  notes1=$(notif_count)
  if [[ -n "$persisted" && "${notes1:-0}" -gt "${notes0:-0}" ]]; then
    pass "I-15 revoke: notif ${notes0}→${notes1}, flush row: $persisted (in-memory ${used}ms)"
  else fail "I-15 notif ${notes0}→${notes1} persisted='$persisted' (in-memory $used)"; fi
  echo "$id" > "$TMP/i15-id"
}

# I-15 asserts the revoke notification is posted — an increase, not a fixed count: dumpsys
# also lists delivered history, so an absolute number is not "exactly once" evidence.
notif_count() {
  adbs shell "dumpsys notification --noredact 2>/dev/null | grep -ac com.example.myexodus" 2>/dev/null | tr -d '\r'
}

# I-16: network change during active flow → no crash, and flows opened *after* the change
# still accrue (the plan's "new flows accrue" — the pre-switch flow's own accrual proves less).
i16() {
  local a ip id
  a="$IPA_NAME"; ip=$(ip_for du-a)
  id=$(add_rule "$a" 3600)
  [[ -n "$id" ]] || { fail "I-16 addrule"; return; }
  dns "$a" A >/dev/null
  stream_bg "$a" "$ip" 30
  sleep 4
  adbs shell svc wifi disable >/dev/null 2>&1
  sleep 8
  local wifioff; wifioff=$(adbs shell settings get global wifi_on | tr -d '\r')
  adbs shell svc wifi enable >/dev/null 2>&1
  if [[ "$wifioff" != 0 ]]; then
    # Toggling Wi-Fi needs root on this build; without the toggle this scenario proves nothing.
    skip "I-16 (Wi-Fi toggle refused, wifi_on=$wifioff — root required)"
    wait
    return
  fi
  sleep 18                                       # the network (and the TUN's upstream) returns
  local used_pre; used_pre=$(usage_of "$id")
  dns "$a" A >/dev/null
  stream "$a" "$ip" 15
  sleep 16
  local used_post; used_post=$(usage_of "$id")
  local alive; alive=$(take status "$(probe -a du-status)")
  if [[ -n "$alive" && "${used_post:-0}" -gt "${used_pre:-0}" ]]; then
    pass "I-16 status=$alive usage ${used_pre}→${used_post}ms (new flow accrued after the Wi-Fi cycle)"
  else fail "I-16 status='$alive' usage ${used_pre}→${used_post}"; fi
  echo "$id" > "$TMP/i16-id"
}

# I-17: the app's own request must not enter its TUN (INV-9). The plan's assertion is
# `flows_tcp_total` unchanged, but that counter is global to the tunnel: on a physical device
# any other app's flow moves it, and a busy window reports a metering leak the product does not
# have (device-caught 2026-09-21 — the leg failed on +2 flows while the app's own request was
# separately measured to add exactly 0, five requests in a row). So the leg only asserts out of
# a *verified-quiet* baseline window: with the counter provably still without the app, any move
# during the request belongs to the request. Attempts are retried because the phone's other apps
# are not under the harness's control, and a device that never goes quiet skips rather than
# blames the product for them.
i17() {
  local attempt before after out
  for attempt in 1 2 3 4; do
    before=$(counter flows_tcp_total); sleep 5; after=$(counter flows_tcp_total)
    [[ "${after:-x}" == "${before:-x}" ]] || continue
    out=$(apphttp "$HOST_V4" 80)
    sleep 2
    after=$(counter flows_tcp_total)
    if [[ "${after:-0}" == "${before:-0}" && "$out" == *code=200* ]]; then
      pass "I-17 app request $out, flows_tcp_total unchanged ($before; quiet baseline, attempt $attempt)"
    else
      fail "I-17 flows ${before}→$after apphttp='$out' (quiet baseline, attempt $attempt)"
    fi
    return
  done
  skip "I-17 (device never held a quiet 5 s window — flows_tcp_total is global; re-run when idle)"
}

# I-18: everything exhausted → never-block/connectivity paths still resolve.
i18() {
  local a id
  a="$IPA_NAME"
  id=$(add_rule "$a" 60)
  dns "$a" A >/dev/null
  stream "$a" "$(ip_for du-a)" 65
  sleep 2
  local cc; cc=$(dns connectivitycheck.gstatic.com A | head -1)
  local ok=1
  [[ "$cc" == *"rcode=0"* && "$cc" == *"an="* ]] || ok=0
  [[ "$cc" != *"an=0"* ]] || ok=0
  if [[ "$ok" == 1 ]]; then pass "I-18 never-block resolves while exhausted: $cc"
  else fail "I-18 connectivity check: $cc"; fi
  echo "$id" > "$TMP/i18-id"
}

# I-19: raise the limit while exhausted → the site loads again within 6 s.
i19() {
  local a ip id nx0 nx used0 used1
  a="$IPA_NAME"; ip=$(ip_for du-a)
  id=$(add_rule "$a" 60)
  [[ -n "$id" ]] || { fail "I-19 addrule"; return; }
  # Self-contained by necessity: fresh_vpn() wipes the rule set between scenarios, so the
  # exhausted rule I-18 leaves behind is gone by the time this runs — reading its id from
  # $TMP/i18-id asked a deleted rule to be raised (device-caught 2026-09-21: the leg passed
  # its DNS check for the wrong reason, with no rule in place at all).
  dns "$a" A >/dev/null
  stream "$a" "$ip" 65            # exhaust the 60 s limit inside this leg
  sleep 2
  nx0=$(dns "$a" A | head -1)
  set_limit "$id" 3600
  sleep 6
  nx=$(dns "$a" A | head -1)
  used0=$(usage_of "$id")
  stream "$a" "$ip" 20
  used1=$(usage_of "$id")
  if [[ "$nx0" == *"rcode=3"* && "$nx" != *"rcode=3"* && "${used1:-0}" -gt "${used0:-0}" ]]; then
    pass "I-19 exhausted→NXDOMAIN, after raise $nx, usage ${used0}→$used1"
  else fail "I-19 before='$nx0' after='$nx' usage ${used0}→${used1}"; fi
  echo "$id" > "$TMP/i19-id"
}

# I-20: hardcoded 8.8.8.8-style UDP query for an exhausted host → NXDOMAIN from the handler.
i20() {
  local a id nx
  a="$IPA_NAME"
  id=$(add_rule "$a" 60)
  dns "$a" A >/dev/null
  stream "$a" "$(ip_for du-a)" 65
  sleep 2
  # The scenario's own shape: an app that ignores the advertised resolver and asks 8.8.8.8
  # directly must still be answered — the TUN intercepts every port-53 datagram, so the
  # handler sees this query exactly as it sees one to 192.0.2.53.
  local nx8; nx8=$(dns "$a" A 8.8.8.8 | head -1)
  nx=$(dns "$a" A | head -1)
  if [[ "$nx8" == *"rcode=3"* && "$nx" == *"rcode=3"* ]]; then
    pass "I-20 hardcoded-resolver NXDOMAIN: 8.8.8.8→'$nx8' advertised→'$nx'"
  else fail "I-20 8.8.8.8='$nx8' advertised='$nx' (want rcode=3 both)"; fi
  echo "$id" > "$TMP/i20-id"
}

# I-22: pattern edited mid-flow → the open flow keeps the old revision; the new one starts at 0.
i22() {
  local a b ip id
  a="$IPA_NAME"; b="$IPB_NAME"; ip=$(ip_for du-a)
  id=$(add_rule "$a" 3600)
  [[ -n "$id" ]] || { fail "I-22 addrule"; return; }
  dns "$a" A >/dev/null
  stream_bg "$a" "$ip" 40
  sleep 12
  local rev0 old_ms
  rev0=$(rule_revision "$id"); old_ms=$(usage_of "$id")
  probe -a du-editpattern --es id "$id" --es pattern "$b" >/dev/null
  sleep 3
  local rev1 new_ms
  rev1=$(rule_revision "$id"); new_ms=$(usage_of "$id")
  wait
  local old_rows
  old_rows=$(probe -a du-rows | grep -ac "id=$id revision=$rev0")
  if [[ "$rev1" != "$rev0" && "${new_ms:-1}" == 0 && "$old_rows" -ge 1 ]]; then
    pass "I-22 rev ${rev0}→$rev1 old(ms=$old_ms) kept accruing; new revision starts at 0"
  else fail "I-22 rev ${rev0}→$rev1 old=$old_ms new=$new_ms rows=$old_rows"; fi
}

# I-23: a save whose normalized pattern duplicates an existing rule is rejected.
i23() {
  local a id
  a="$IPA_NAME"
  id=$(add_rule "$a" 3600)
  [[ -n "$id" ]] || { fail "I-23 addrule"; return; }
  local dup; dup=$(probe -a du-addrule --es pattern "$a" --el limitSec 3600)
  if [[ "$dup" == *"result=DuplicatePattern"* ]]; then pass "I-23 duplicate rejected: $dup"
  else fail "I-23 expected DuplicatePattern, got '$dup'"; fi
  del_rule "$id"
}

# ---------------------------------------------------------------- main

setup
say "VPN start"
vpn_start
rules_reset

for id in I-1 I-2 I-3 I-4 I-5 I-6 I-7 I-8 I-9 I-10 I-11 I-12 I-12b I-13 I-14 I-15 I-16 I-17 I-18 I-19 I-20 I-22 I-23; do
  wanted "$id" || continue
  fn=$(echo "$id" | tr 'A-Z' 'a-z' | tr -d '-')
  if declare -F "$fn" >/dev/null; then
    say "$id"
    set_mode ENFORCE      # every leg starts in ENFORCE (the frozen matrix condition); the legs
                          # that claim ACCOUNT (I-11b, I-13b — AC-19) flip it and cycle themselves
    fresh_vpn
    "$fn"
  else
    skip "$id (no implementation for topology=$TOPOLOGY)"
  fi
done

# Not shell-testable: I-21/I-21b are Room migration cases, run over the real SQLCipher stack by
# ./gradlew :app:connectedDebugAndroidTest \
#   -Pandroid.testInstrumentationRunnerArguments.class=com.example.myexodus.data.db.AppRulesStoreMigrationInstrumentedTest
say "not in this script: I-21, I-21b (Room migration, instrumented test — see report)"

say "summary"
printf '%s\n' "${RESULTS[@]}"
echo "passed=$PASSED failed=$FAILED skipped=$SKIPPED judged=$JUDGED"
[[ "$FAILED" == 0 ]]

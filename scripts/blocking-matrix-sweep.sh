#!/usr/bin/env bash
# Device cross-product sweep for the blocking decision model (matrix Part C, 2026-10-20).
#
# Enumerates every valid combination of the finite rule axes on the device:
#   shape (5) x window state (inside/outside, only where a window exists)
#             x quota state (under/at/over, only where a limit exists)
#             x day scope (on/off) x expiry (none/future/past)
# = 6 + 12 + 18 + 36 + 12 = 84 cells.
#
# Each cell is seeded through RulesRepository (usage-seed, the same path as an editor save),
# Gmail is launched, and the verdict is read from the engine's own decision evidence —
# requiring a FRESH decision for the launch, so a persistent card's re-eviction (which
# decides nothing) can never masquerade as a Block. THREE evidence paths produce a verdict:
#   - `decide $PKG ... -> Block|Allow` (MyExodusBlocker: accessibility WSC path)
#   - `detected $PKG — launching blocker` (MyExodusBlockerFgs: the 1s foreground poll, whose
#     AppBlockScanner calls the same BlockDecisionEngine.decideApp — measured 22:20:46.628
#     on a cold Gmail start whose only WSC was the splash window, which the service defers
#     and drops, so no accessibility decision is ever logged for that launch).
#   - `observe $PKG ... -> Nms/Mms Block` (MyExodusUsage: the usage tick, which is the ONE
#     app quota path — both decideApp call sites pass usageTodayMs=null (blocking-core plan:
#     schedule/always-on/expiry decide; the daily limit is accounted by UsageAccountingEngine
#     over the tracker row). A quota-spent app outside its window therefore logs
#     `decide -> Allow` first and the wall rises from the tick's Block a beat later; an
#     Allow seen before the poll window closes is provisional, never a final verdict.
# A BLOCK verdict is accepted from any fresh line; an ALLOW verdict only from `decide`,
# since the poll and tick log nothing on Allow. The expected verdict is computed here, independently:
#   expiry past -> ALLOW (Rule 3 first)
#   day off     -> ALLOW (day scope gates the rule)
#   always-on   -> BLOCK
#   inside win  -> BLOCK (Rule 1)
#   quota >= lim-> BLOCK (Rule 2, applies outside the window too)
#   otherwise   -> ALLOW
#
# Every cell starts with a scene reset: a standing wall survives HOME and MainActivity (only
# its Close button dismisses it) and would re-evict the next launch with no decision. The
# reset parks on HOME first, then clicks that real Close through the verification suite —
# dismissing while the target is still foreground races the next poll, which re-raises the
# wall and contaminates every following cell (AVD, 2026-10-22).
# Precondition: dismiss Gmail's one-time "New in Gmail" onboarding ("GOT IT") on a fresh AVD.
# Left up, it relaunches after the reset's HOME and re-foregrounds the blocked package.
#
# Usage: scripts/blocking-matrix-sweep.sh [device-serial]
#        SKIP=33 scripts/blocking-matrix-sweep.sh [device-serial]   # resume at cell 33
# Writes a TSV to /tmp/blocking-sweep-<date>.tsv and prints a PASS/FAIL summary.
set -u
DEV="${1:-LAN-ADDRESS-REDACTED:5555}"
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
PKG=com.google.android.gm
ACT=com.google.android.gm/.ConversationListActivityGmail
TS=$(date +%Y%m%d-%H%M%S)
OUT="/tmp/blocking-sweep-$TS.tsv"
EVIDDIR="/tmp/blocking-sweep-$TS-cells"
mkdir -p "$EVIDDIR"
NOWMIN=$((10#$(date +%H) * 60 + 10#$(date +%M)))
# The inside window must still contain `now` for the whole run (~45 min), not just at start.
WIN_IN=$(printf '%d..%d' $(((NOWMIN - 60 + 1440) % 1440)) $(((NOWMIN + 120) % 1440)))
WIN_OUT=$(printf '%d..%d' $(((NOWMIN + 300) % 1440)) $(((NOWMIN + 420) % 1440)))
PASS=0; FAIL=0
SKIP=${SKIP:-0}   # first cell number to execute; earlier cells are skipped (resume after an abort)

# A locked phone makes `am start -W` wait forever (run 2026-10-20 22:54 wedged 5 h this way).
# Fail fast instead. Note that after HOME on API 37 the launch itself can be deferred: `-W`
# then never returns even with a connected device (2026-10-22, 17:24), so the harness starts
# the suite without `-W` and gates on the engine snapshot instead.
require_interactive() {
    local state
    state=$($ADB -s "$DEV" shell 'dumpsys power | grep -m1 mWakefulness=; dumpsys window | grep -m1 isKeyguardShowing' 2>/dev/null)
    case "$state" in
        *mWakefulness=Awake*isKeyguardShowing=false*) : ;;
        *) echo "ABORT at cell ${1:-start}: device asleep or locked — unlock it and resume with SKIP=$(( ${1:-1} ))" | tee -a "$OUT"; exit 3 ;;
    esac
}

restore_screen() { $ADB -s "$DEV" shell svc power stayon false >/dev/null 2>&1 || :; }
trap restore_screen EXIT INT TERM
$ADB -s "$DEV" shell svc power stayon true >/dev/null 2>&1 || :

# Today's weekday turned off, Mon-first — recomputed per cell: a run that crosses local
# midnight must flip the mask with the engine's day key, not keep yesterday's.
today_off_mask() {
    local idx=$(( $(date +%u) - 1 )) i mask=
    for i in 0 1 2 3 4 5 6; do
        if [ "$i" -eq "$idx" ]; then mask="${mask}0"; else mask="${mask}1"; fi
    done
    printf '%s' "$mask"
}

# One combined dump per poll, written to CELLLOG: four separate tag dumps cost ~2.4 s each
# on a full buffer and rotate the ring mid-cell; after the per-cell clear one combined
# dump is ~0.2 s and everything the verdict needs is read from the same snapshot.
CELLLOG=$(mktemp -t blocking-cell.XXXXXX)
dump_log() {
    $ADB -s "$DEV" logcat -d -t 4000 -s MyExodusBlocker:I MyExodusBlockerFgs:I \
        MyExodusUsage:I MyExodusRules:I > "$CELLLOG" 2>/dev/null || :
}

snapshot_version() {
    dump_log
    grep -o 'Authoritative snapshot v[0-9]*' "$CELLLOG" | tail -1 | grep -o '[0-9]*$'
}

decide_count() { grep -c "decide $PKG " "$CELLLOG" || :; }

decide_last() { grep "decide $PKG " "$CELLLOG" | tail -1 || :; }

block_count() { grep -c "block close pkg=$PKG" "$CELLLOG" || :; }

fgs_count() { grep -c "detected $PKG — launching blocker" "$CELLLOG" || :; }

usage_block_count() { grep -c "observe $PKG .* Block (" "$CELLLOG" || :; }

reset_scene() {
    # HOME FIRST, then close any persistent wall through its real listener (no-op when none).
    # Order matters (2026-10-22, AVD run): dismissing while the target is still foreground
    # lets the next poll re-raise the wall a beat later, and the now-standing wall contaminates
    # every following cell (observed as a sweep-long STALE-CARD chain starting at the first
    # usage-tick Block cell). With the target backgrounded first, nothing can re-raise it.
    # The buffer clear comes first, so the cell's log is bounded and a full-buffer dump
    # (2.4 s per tag) never slows the poll or rotates mid-cell.
    $ADB -s "$DEV" logcat -c >/dev/null 2>&1
    $ADB -s "$DEV" shell input keyevent 3 >/dev/null 2>&1
    sleep 2   # let the launcher transition finish; a start inside it gets deferred (2026-10-22)
    # No `-W`: on API 37 a start right after HOME was deferred and `am -W` never returned —
    # the run wedged 3.5 min on its first reset, and the device-side `timeout` left a zombie
    # while adbd kept the host shell open (AVD 2026-10-22, ~17:24). The suite needs no launch
    # ack: it runs on its own thread and finishes; the sleep below only has to outlast it.
    $ADB -s "$DEV" shell timeout 20 am start -n com.example.myexodus/.blocking.DeviceVerificationActivity \
        --es suite overlay-dismiss >/dev/null 2>&1
    sleep 3   # measured: the suite's click lands ~2.4 s after the start
}

wait_snapshot() { # prevVersion — the seed's policy bump is only in force once a newer snapshot lands
    local prev=$1 cur i
    # 60 s, not 12 s: after an ANR the app is killed and the next seed rides a cold start
    # (dex + verification + SQLCipher measured >12 s on the loaded AVD, 2026-10-22 17:29).
    for i in $(seq 1 120); do
        cur=$(snapshot_version)
        if [ -n "$cur" ] && [ "$cur" != "$prev" ]; then return 0; fi
        sleep 0.5
    done
    echo "  (snapshot did not advance past v$prev within 60s)" >&2
}
# Seed -> publish measured at ~2.0s; the wait above is mandatory, not a fixed sleep. The buffer
# clear sits in reset_scene, BEFORE the seed writes anything, so it can never wipe the version
# line this wait is looking for; counts stay deltas from a per-cell baseline regardless.

seed() { # wStart wEnd day expiryMs alwaysOn limitMs usedMs
    local args=(--es suite usage-seed --es pkg "$PKG" --ei limitMs "$6" --ei usedMs "$7"
        --ez quotaOnly "$( [ "$1" = none ] && echo true || echo false )"
        --ez alwaysOn "$5" --es days "$3")
    [ "$1" != none ] && args+=(--ei wStart "$1" --ei wEnd "$2")
    [ "$4" != 0 ] && args+=(--el expiresInMs "$4")
    local prev
    prev=$(snapshot_version)
    # No `-W` (same wedge as reset_scene); `wait_snapshot` is the real gate — it observes the
    # seeded policy in the engine's own snapshot rather than trusting a launch callback.
    $ADB -s "$DEV" shell timeout 20 am start -n com.example.myexodus/.blocking.DeviceVerificationActivity "${args[@]}" >/dev/null 2>&1
    wait_snapshot "$prev"
    sleep 1
}

run_cell() { # id shape window quota day expiry
    local id=$1 shape=$2 win=$3 quota=$4 day=$5 expiry=$6
    local idnum=${id//[!0-9]/}
    [ "$idnum" -lt "$SKIP" ] && return
    require_interactive "$idnum"
    local ws=none we=none limit=0 used=0 alwaysOn=false expMs=0 dayStr="1111111"
    local winState=none quotaState=none winInside=0 quotaSpent=0
    case "$shape" in
        alwaysOn) alwaysOn=true ;;
        window) [ "$win" = inside ] && { ws=${WIN_IN%..*}; we=${WIN_IN#*..}; winInside=1; } || { ws=${WIN_OUT%..*}; we=${WIN_OUT#*..}; } ;;
        quota) limit=60000 ;;
        windowQuota) limit=60000
            [ "$win" = inside ] && { ws=${WIN_IN%..*}; we=${WIN_IN#*..}; winInside=1; } || { ws=${WIN_OUT%..*}; we=${WIN_OUT#*..}; } ;;
        alwaysOnWindow) alwaysOn=true
            [ "$win" = inside ] && { ws=${WIN_IN%..*}; we=${WIN_IN#*..}; winInside=1; } || { ws=${WIN_OUT%..*}; we=${WIN_OUT#*..}; } ;;
    esac
    case "$shape" in
        window|windowQuota|alwaysOnWindow) [ "$win" = inside ] && winState=inside || winState=outside ;;
    esac
    case "$shape" in
        quota|windowQuota)
            case "$quota" in
                under) used=0 ;;
                at) used=60000; quotaSpent=1 ;;
                over) used=60001; quotaSpent=1 ;;
            esac
            quotaState=$quota ;;
    esac
    [ "$day" = off ] && dayStr=$(today_off_mask)
    case "$expiry" in
        future) expMs=120000 ;;
        past) expMs=-60000 ;;
    esac

    local expected=ALLOW
    if [ "$expiry" != past ] && [ "$day" = on ]; then
        if [ "$alwaysOn" = true ] || [ "$winInside" = 1 ] || [ "$quotaSpent" = 1 ]; then expected=BLOCK; fi
    fi

    reset_scene
    seed "$ws" "$we" "$dayStr" "$expMs" "$alwaysOn" "$limit" "$used"
    local dbase fbase ubase base blocks=0 observed=UNKNOWN evidence=none line i
    dump_log
    dbase=$(decide_count)
    fbase=$(fgs_count)
    ubase=$(usage_block_count)
    base=$(block_count)
    $ADB -s "$DEV" shell am start -n "$ACT" >/dev/null 2>&1
    # The verdict is the engine's fresh decision for THIS launch — the accessibility decide
    # line, the FGS poll's launch line, or the usage tick's Block (the only app-quota path).
    # An Allow is provisional: a quota wall can still rise from the tick, so the poll runs
    # its full window before concluding. A block close with no fresh line means a standing
    # scene re-evicted (harness contamination) — STALE-CARD, never a Block.
    for i in $(seq 1 22); do
        sleep 1
        dump_log
        if [ "$(usage_block_count)" -gt "$ubase" ]; then
            observed=BLOCK; evidence=usage; break
        fi
        if [ "$(decide_count)" -gt "$dbase" ]; then
            line=$(decide_last)
            evidence=decide
            case "$line" in
                *"-> Block") observed=BLOCK; break ;;
                *"-> Allow") observed=ALLOW ;;
            esac
        fi
        if [ "$(fgs_count)" -gt "$fbase" ]; then
            observed=BLOCK; evidence=fgs; break
        fi
        blocks=$(( $(block_count) - base ))
        if [ "$blocks" -gt 0 ]; then observed=STALE-CARD; break; fi
    done

    local verdict=PASS
    [ "$observed" = "$expected" ] || verdict=FAIL
    [ "$verdict" = PASS ] && PASS=$((PASS + 1)) || FAIL=$((FAIL + 1))
    printf '%s\t%s\twin=%s\tquota=%s\tday=%s\texpiry=%s\texpected=%s\tobserved=%s\tevidence=%s\tblocks=%s\t%s\n' \
        "$id" "$shape" "$winState" "$quotaState" "$day" "$expiry" "$expected" "$observed" "$evidence" "$blocks" "$verdict" >> "$OUT"
    echo "$id $shape win=$winState quota=$quotaState day=$day expiry=$expiry expected=$expected observed=$observed evidence=$evidence $verdict"
    dump_log
    cp "$CELLLOG" "$EVIDDIR/$id.log"

    # No reset here: the next cell opens with reset_scene, so an ALLOW cell's still-foreground
    # Gmail is backgrounded before any spent-quota seed can reach the usage tick.
}

echo "sweep start $(date '+%H:%M:%S') windowInside=$WIN_IN windowOutside=$WIN_OUT skip=$SKIP" | tee "$OUT"
require_interactive
# Warm-up: after an ANR the process is dead and the first seed would race a cold start.
# Launch the app once and wait for its first engine snapshot before any cell (2026-10-22).
$ADB -s "$DEV" shell am start -n com.example.myexodus/.MainActivity >/dev/null 2>&1
for i in $(seq 1 120); do
    [ -n "$(snapshot_version)" ] && break
    sleep 0.5
done
sleep 2
# Known-clean start: a wall left by a previous run re-evicts the first launch without a
# decision, which would taint the first cell.
reset_scene
ID=0
for day in on off; do
  for expiry in none future past; do
    ID=$((ID + 1)); run_cell "A$ID" alwaysOn none none "$day" "$expiry"
  done
  for win in inside outside; do
    for expiry in none future past; do
      ID=$((ID + 1)); run_cell "W$ID" window "$win" none "$day" "$expiry"
    done
    for quota in under at over; do
      for expiry in none future past; do
        ID=$((ID + 1)); run_cell "WQ$ID" windowQuota "$win" "$quota" "$day" "$expiry"
      done
    done
    for expiry in none future past; do
      ID=$((ID + 1)); run_cell "AW$ID" alwaysOnWindow "$win" none "$day" "$expiry"
    done
  done
  for quota in under at over; do
    for expiry in none future past; do
      ID=$((ID + 1)); run_cell "Q$ID" quota none "$quota" "$day" "$expiry"
    done
  done
done

$ADB -s "$DEV" shell timeout 20 am start -n com.example.myexodus/.blocking.DeviceVerificationActivity \
    --es suite usage-clear --es pkg "$PKG" --ez withRule true >/dev/null 2>&1
reset_scene   # leave no wall standing on the owner's phone
echo "sweep end $(date '+%H:%M:%S') pass=$PASS fail=$FAIL cells=$((PASS + FAIL))" | tee -a "$OUT"

#!/usr/bin/env bash
# Domain Usage Metering static checks (plan §8 S1.4, S1.6). Run by the Gradle `check` task.
#
# 1. Wall-clock confinement: `currentTimeMillis|Instant.now|LocalDate.now|LocalDateTime.now|
#    Clock.system` must match only SystemWallClock.kt inside */domainusage/* (INV-2).
# 2. PSL pinning: public_suffix_list.dat must hash to the sha256 recorded in its provenance file.
# 3. Hot-path confinement: inside `domainusage`, the Room DAO may be named only by the DAO
#    itself, `UsagePersistence` and the Engine's `start` wiring (INV-1: the packet/relay path
#    never touches Room). Any other reference — a tap, the ledger, a parser — fails CI.
# 4. Log hygiene: no interpolated `Log.` call inside `domainusage` production sources (INV-7/S-5:
#    logs carry counters only). Interpolation is the mechanism a hostname would leak through.
# 5. `allowBypass` must not appear anywhere under the scan root (INV-10/S-10).
# 6. The VPN service must call `addDisallowedApplication` (INV-9/S-10: own traffic stays out of
#    the TUN), so removing the line fails CI rather than a review someone may skip.
#
# Paths are overridable so the checks themselves can be tested (U-13); the plan's `git grep`
# is replaced by a filesystem grep — same semantics over the domainusage paths, but it also
# covers files not yet tracked by git.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCAN_ROOT="${DOMAINUSAGE_SCAN_ROOT:-$ROOT_DIR/app/src}"
PSL_ASSET="${PSL_ASSET_PATH:-$ROOT_DIR/app/src/main/assets/public_suffix_list.dat}"
PSL_PROVENANCE="${PSL_PROVENANCE_PATH:-$ROOT_DIR/app/src/main/assets/public_suffix_list.provenance}"

status=0

violations="$(grep -rnE "currentTimeMillis|Instant\.now|LocalDate\.now|LocalDateTime\.now|Clock\.system" "$SCAN_ROOT" 2>/dev/null \
  | grep "/domainusage/" | grep -v "SystemWallClock.kt" || true)"
if [ -n "$violations" ]; then
  echo "FAIL [S1.4] wall-clock call outside SystemWallClock.kt:" >&2
  echo "$violations" >&2
  status=1
fi

if [ ! -f "$PSL_ASSET" ] || [ ! -f "$PSL_PROVENANCE" ]; then
  echo "FAIL [S1.6] PSL asset or provenance file missing:" >&2
  echo "  $PSL_ASSET" >&2
  echo "  $PSL_PROVENANCE" >&2
  status=1
else
  expected="$(grep -E '^sha256=' "$PSL_PROVENANCE" | head -1 | cut -d= -f2)"
  if command -v shasum >/dev/null 2>&1; then
    actual="$(shasum -a 256 "$PSL_ASSET" | awk '{print $1}')"
  else
    actual="$(sha256sum "$PSL_ASSET" | awk '{print $1}')"
  fi
  if [ -z "$expected" ] || [ "$expected" != "$actual" ]; then
    echo "FAIL [S1.6] PSL checksum mismatch (provenance=$expected actual=$actual)" >&2
    status=1
  fi
fi

violations="$(grep -rn 'RuleUsageDao\|ruleUsageDao()' "$SCAN_ROOT/main" 2>/dev/null \
  | grep "/domainusage/" \
  | grep -v -e "/RuleUsageDao.kt" -e "/UsagePersistence.kt" -e "/DomainUsageEngine.kt" || true)"
if [ -n "$violations" ]; then
  echo "FAIL [INV-1] Room DAO reached outside the persistence path (the packet/relay path must not touch Room):" >&2
  echo "$violations" >&2
  status=1
fi

violations="$(grep -rn 'Log\.' "$SCAN_ROOT/main" 2>/dev/null | grep "/domainusage/" | grep '\$' || true)"
if [ -n "$violations" ]; then
  echo "FAIL [INV-7] interpolated Log call in domainusage production code (logs carry counters only):" >&2
  echo "$violations" >&2
  status=1
fi

violations="$(grep -rn 'allowBypass' "$SCAN_ROOT" 2>/dev/null || true)"
if [ -n "$violations" ]; then
  echo "FAIL [INV-10] allowBypass must not appear under $SCAN_ROOT:" >&2
  echo "$violations" >&2
  status=1
fi

vpn_service="$(find "$SCAN_ROOT" -path '*/vpn/DnsSinkholeVpnService.kt' 2>/dev/null | head -1)"
if [ -z "$vpn_service" ] || ! grep -q 'addDisallowedApplication' "$vpn_service"; then
  echo "FAIL [INV-9] DnsSinkholeVpnService must call addDisallowedApplication (own traffic stays out of the TUN):" >&2
  echo "  ${vpn_service:-no DnsSinkholeVpnService.kt under $SCAN_ROOT}" >&2
  status=1
fi

if [ "$status" -eq 0 ]; then
  echo "domainusage CI checks: OK"
fi
exit "$status"

#!/bin/bash
# Plan §13.4-6: produce the signed policy envelope the app fetches from
# /releases/latest/download/update-manifest.json.
#
#   scripts/update-manifest-sign.sh offer <apk> <versionCode> <versionName> <policySequence> <rolloutPercent> [notes-file]
#   scripts/update-manifest-sign.sh halt <policySequence>
#
# Environment:
#   RELEASE_REPOSITORY  owner/repo of the public releases-only repository (required)
#   CHANNEL             policy channel; must match the app (default: stable)
#   COHORT_SALT         32 lowercase hex; generated per release when unset
#   KEY_FILE            manifest private key (default: ~/.myexodus-update-key/update-manifest-key.pem)
#   STATE_FILE          publisher monotonic state (default: ~/.myexodus-update-key/publisher-state.json)
#   AAPT                aapt binary; auto-detected from ANDROID_HOME/ANDROID_SDK_ROOT when unset
#   OUT                 output path (default: update-manifest.json beside the APK / in $PWD for halt)
#
# The publisher refuses a non-monotonic policySequence or a versionCode downgrade (§13.4).
# The APK must be the exact signed artifact for the tag artifact-v<versionCode>; the URL is
# built from RELEASE_REPOSITORY and cannot be overridden.
set -euo pipefail

PACKAGE_NAME="com.example.myexodus"
MAX_APK_BYTES=$((250 * 1024 * 1024))
MAX_NOTES_CHARS=4000
MAX_VERSION_NAME_CHARS=64
KEY_FILE="${KEY_FILE:-$HOME/.myexodus-update-key/update-manifest-key.pem}"
STATE_FILE="${STATE_FILE:-$HOME/.myexodus-update-key/publisher-state.json}"
CHANNEL="${CHANNEL:-stable}"
RELEASE_REPOSITORY="${RELEASE_REPOSITORY:?set RELEASE_REPOSITORY to the releases-only owner/repo}"

MODE="${1:?usage: update-manifest-sign.sh offer <apk> <versionCode> <versionName> <sequence> <rolloutPercent> [notes-file] | halt <sequence>}"

apk_meta() {
  local apk="$1"
  local aapt="${AAPT:-}"
  if [ -z "$aapt" ]; then
    for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
      [ -n "$root" ] || continue
      aapt="$(ls -1 "$root"/build-tools/*/aapt 2>/dev/null | sort -V | tail -1)"
      [ -n "$aapt" ] && break
    done
  fi
  if [ -z "$aapt" ] || [ ! -x "$aapt" ]; then
    echo "aapt not found; set AAPT or ANDROID_HOME" >&2
    exit 1
  fi
  "$aapt" dump badging "$apk"
}

require_int() {
  local name="$1" value="$2"
  case "$value" in ''|*[!0-9]*) echo "$name must be a non-negative integer" >&2; exit 1 ;; esac
}

case "$MODE" in
  offer)
    APK="${2:?missing apk}"
    VERSION_CODE="${3:?missing versionCode}"
    VERSION_NAME="${4:?missing versionName}"
    SEQUENCE="${5:?missing policySequence}"
    ROLLOUT="${6:?missing rolloutPercent}"
    NOTES_FILE="${7:-}"
    require_int versionCode "$VERSION_CODE"
    require_int policySequence "$SEQUENCE"
    require_int rolloutPercent "$ROLLOUT"
    [ "$ROLLOUT" -le 100 ] || { echo "rolloutPercent must be 0..100" >&2; exit 1; }
    [ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }
    ;;
  halt)
    SEQUENCE="${2:?missing policySequence}"
    require_int policySequence "$SEQUENCE"
    ;;
  *)
    echo "unknown mode: $MODE (expected offer or halt)" >&2
    exit 1
    ;;
esac

[ -f "$KEY_FILE" ] || { echo "Signing key not found: $KEY_FILE" >&2; exit 1; }

COHORT_SALT="${COHORT_SALT:-$(openssl rand -hex 16)}"
printf '%s' "$COHORT_SALT" | grep -Eq '^[0-9a-f]{32}$' ||
  { echo "COHORT_SALT must be 32 lowercase hex characters" >&2; exit 1; }

if [ "$MODE" = "offer" ]; then
  ASSET="$(basename "$APK")"
  printf '%s' "$ASSET" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.apk$' ||
    { echo "APK filename must match [A-Za-z0-9][A-Za-z0-9._-]*.apk: $ASSET" >&2; exit 1; }
  printf '%s' "$VERSION_NAME" | grep -Eq "^[^[:cntrl:]]{1,$MAX_VERSION_NAME_CHARS}$" ||
    { echo "versionName must be 1..$MAX_VERSION_NAME_CHARS printable characters" >&2; exit 1; }

  BADGING="$(apk_meta "$APK")"
  ADB_PACKAGE="$(printf '%s\n' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
  ADB_VERSION_CODE="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p")"
  ADB_VERSION_NAME="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p")"
  MIN_SDK="$(printf '%s\n' "$BADGING" | sed -n "s/^sdkVersion:'\([0-9]*\)'.*/\1/p")"
  TARGET_SDK="$(printf '%s\n' "$BADGING" | sed -n "s/^targetSdkVersion:'\([0-9]*\)'.*/\1/p")"
  [ "$ADB_PACKAGE" = "$PACKAGE_NAME" ] ||
    { echo "APK package is $ADB_PACKAGE, expected $PACKAGE_NAME" >&2; exit 1; }
  [ "$ADB_VERSION_CODE" = "$VERSION_CODE" ] ||
    { echo "APK versionCode is $ADB_VERSION_CODE, argument says $VERSION_CODE" >&2; exit 1; }
  [ "$ADB_VERSION_NAME" = "$VERSION_NAME" ] ||
    { echo "APK versionName is $ADB_VERSION_NAME, argument says $VERSION_NAME" >&2; exit 1; }
  [ -n "$MIN_SDK" ] && [ -n "$TARGET_SDK" ] ||
    { echo "Could not read minSdk/targetSdk from the APK" >&2; exit 1; }

  SHA="$(shasum -a 256 "$APK" | awk '{print $1}')"
  SIZE="$(stat -f %z "$APK")"
  [ "$SIZE" -le "$MAX_APK_BYTES" ] ||
    { echo "APK exceeds the $MAX_APK_BYTES-byte client limit" >&2; exit 1; }
  APK_URL="https://github.com/$RELEASE_REPOSITORY/releases/download/artifact-v$VERSION_CODE/$ASSET"
  NOTES=""
  if [ -n "$NOTES_FILE" ]; then
    [ -f "$NOTES_FILE" ] || { echo "Notes file not found: $NOTES_FILE" >&2; exit 1; }
    NOTES="$(cat "$NOTES_FILE")"
  fi
  [ "${#NOTES}" -le "$MAX_NOTES_CHARS" ] ||
    { echo "Release notes exceed $MAX_NOTES_CHARS characters" >&2; exit 1; }
  OUT="${OUT:-$(dirname "$APK")/update-manifest.json}"
else
  ROLLOUT=0
  OUT="${OUT:-$PWD/update-manifest.json}"
fi

mkdir -p "$(dirname "$STATE_FILE")"
chmod 700 "$(dirname "$STATE_FILE")" 2>/dev/null || true

# §13.4: refuse replays and downgrades. The state file advances only after the envelope is
# written, so a failed run never burns a sequence.
STATE_FILE="$STATE_FILE" SEQUENCE="$SEQUENCE" MODE="$MODE" VERSION_CODE="${VERSION_CODE:-0}" python3 - <<'PY'
import json, os, sys
path = os.environ["STATE_FILE"]
try:
    state = json.load(open(path, encoding="utf-8"))
except FileNotFoundError:
    state = {}
sequence = int(os.environ["SEQUENCE"])
published = int(state.get("highestPolicySequence", 0))
if sequence <= published:
    sys.exit("policySequence %d is not above the published %d" % (sequence, published))
if os.environ["MODE"] == "offer":
    version = int(os.environ["VERSION_CODE"])
    published_version = int(state.get("highestVersionCode", 0))
    # §13.4 forbids a downgrade, not an equal version: rollout progression (5 to 25 to 100
    # percent) re-offers the same artifact with a higher policy sequence (§13.7).
    if version < published_version:
        sys.exit("versionCode %d is below the published %d" % (version, published_version))
PY

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

export PACKAGE_NAME CHANNEL MODE SEQUENCE COHORT_SALT ROLLOUT
export APK_URL="${APK_URL:-}" SHA="${SHA:-}" SIZE="${SIZE:-0}" VERSION_CODE="${VERSION_CODE:-0}"
export VERSION_NAME="${VERSION_NAME:-}" MIN_SDK="${MIN_SDK:-0}" TARGET_SDK="${TARGET_SDK:-0}"
export NOTES="${NOTES:-}"
python3 - "$WORK/payload.json" <<'PY'
import json, os, sys, time
notes = os.environ["NOTES"]
if any(ord(c) < 32 and c not in "\n\r\t" or ord(c) == 127 for c in notes):
    sys.exit("release notes contain control characters")
payload = {
    "schemaVersion": 1,
    "packageName": os.environ["PACKAGE_NAME"],
    "channel": os.environ["CHANNEL"],
    "policySequence": int(os.environ["SEQUENCE"]),
    "mode": os.environ["MODE"].upper(),
    "rolloutPercent": int(os.environ["ROLLOUT"]),
    "cohortSalt": os.environ["COHORT_SALT"],
    "issuedAtEpochSeconds": int(time.time()),
}
if os.environ["MODE"] == "offer":
    payload["release"] = {
        "versionCode": int(os.environ["VERSION_CODE"]),
        "versionName": os.environ["VERSION_NAME"],
        "minSdk": int(os.environ["MIN_SDK"]),
        "targetSdk": int(os.environ["TARGET_SDK"]),
        "apkUrl": os.environ["APK_URL"],
        "apkSizeBytes": int(os.environ["SIZE"]),
        "apkSha256Hex": os.environ["SHA"],
        "releaseNotes": notes,
    }
with open(sys.argv[1], "w", encoding="utf-8") as f:
    f.write(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
PY

openssl dgst -sha256 -sign "$KEY_FILE" -out "$WORK/signature.der" "$WORK/payload.json"
openssl ec -in "$KEY_FILE" -pubout -out "$WORK/public.pem" 2>/dev/null
openssl dgst -sha256 -verify "$WORK/public.pem" -signature "$WORK/signature.der" "$WORK/payload.json" >/dev/null ||
  { echo "signature self-check failed" >&2; exit 1; }

python3 - "$WORK/payload.json" "$WORK/signature.der" "$OUT" <<'PY'
import base64, json, sys
payload = open(sys.argv[1], "rb").read()
signature = open(sys.argv[2], "rb").read()
if len(payload) > 16 * 1024:
    sys.exit("payload exceeds the 16 KiB client limit")
envelope = {
    "format": "myexodus-update-envelope-v1",
    "keyId": "primary-v1",
    "payloadBase64": base64.b64encode(payload).decode("ascii"),
    "signatureBase64": base64.b64encode(signature).decode("ascii"),
}
raw = json.dumps(envelope, separators=(",", ":"))
if len(raw.encode("utf-8")) > 32 * 1024:
    sys.exit("envelope exceeds the 32 KiB client limit")
with open(sys.argv[3], "w", encoding="utf-8") as f:
    f.write(raw + "\n")
PY

STATE_FILE="$STATE_FILE" SEQUENCE="$SEQUENCE" MODE="$MODE" VERSION_CODE="${VERSION_CODE:-0}" python3 - <<'PY'
import json, os
path = os.environ["STATE_FILE"]
try:
    state = json.load(open(path, encoding="utf-8"))
except FileNotFoundError:
    state = {}
state["highestPolicySequence"] = int(os.environ["SEQUENCE"])
if os.environ["MODE"] == "offer":
    state["highestVersionCode"] = int(os.environ["VERSION_CODE"])
with open(path, "w", encoding="utf-8") as f:
    json.dump(state, f, indent=2)
    f.write("\n")
PY

echo "Wrote $OUT (mode=$MODE, sequence=$SEQUENCE)"
if [ "$MODE" = "offer" ]; then
  echo "  artifact: artifact-v$VERSION_CODE/$ASSET"
  echo "  version:  $VERSION_NAME ($VERSION_CODE), minSdk $MIN_SDK, targetSdk $TARGET_SDK"
  echo "  sha256:   $SHA"
  echo "  size:     $SIZE bytes"
  echo "  url:      $APK_URL"
  echo "  cohort:   salt=$COHORT_SALT rollout=$ROLLOUT%"
else
  echo "  cohort:   salt=$COHORT_SALT (HALT, rollout 0)"
fi
echo
echo "Publish (plan §13.5-6):"
echo "  1. artifact prerelease: gh release create artifact-v<versionCode> <apk> --prerelease"
echo "  2. policy release: gh release create policy-$(printf '%06d' "$SEQUENCE") $OUT --latest \\"
echo "       --notes 'Signed update policy sequence $SEQUENCE'"
echo "  3. verify: curl -fsSL https://github.com/$RELEASE_REPOSITORY/releases/latest/download/update-manifest.json"
echo "  4. client fixture: ./gradlew :app:testDebugUnitTest --tests 'com.example.myexodus.update.UpdatePolicyTest'"

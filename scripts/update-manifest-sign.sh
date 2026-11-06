#!/bin/bash
# ADR #171 update channel: produce update-manifest.json for a GitHub release.
#
#   scripts/update-manifest-sign.sh <apk> <versionCode> <versionName> [notes-file] [minSupportedVersionCode]
#
# Upload BOTH files to the release: the APK under its exact filename (the manifest names it)
# and update-manifest.json. The app reads the release marked "latest" (non-prerelease,
# non-draft) at /releases/latest/download/, so publish prereleases only for drafts.
#
# versionCode must exceed the installed one; the release APK must be signed with the same
# key as the installed app or the update is rejected before the installer ever sees it.
set -euo pipefail

APK="${1:?usage: update-manifest-sign.sh <apk> <versionCode> <versionName> [notes-file] [minSupportedVersionCode]}"
VERSION_CODE="${2:?missing versionCode}"
VERSION_NAME="${3:?missing versionName}"
NOTES_FILE="${4:-}"
MIN_SUPPORTED="${5:-0}"
KEY_FILE="${KEY_FILE:-$HOME/.myexodus-update-key/update-manifest-key.pem}"
OUT="${OUT:-$(dirname "$APK")/update-manifest.json}"

[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }
[ -f "$KEY_FILE" ] || { echo "Signing key not found: $KEY_FILE" >&2; exit 1; }
case "$VERSION_CODE" in ''|*[!0-9]*) echo "versionCode must be an integer" >&2; exit 1 ;; esac

ASSET="$(basename "$APK")"
if ! printf '%s' "$ASSET" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.apk$'; then
  echo "APK filename must match [A-Za-z0-9][A-Za-z0-9._-]*.apk: $ASSET" >&2
  exit 1
fi

SHA="$(shasum -a 256 "$APK" | awk '{print $1}')"
SIZE="$(stat -f %z "$APK")"
NOTES=""
if [ -n "$NOTES_FILE" ]; then
  [ -f "$NOTES_FILE" ] || { echo "Notes file not found: $NOTES_FILE" >&2; exit 1; }
  NOTES="$(cat "$NOTES_FILE")"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

APK="$APK" ASSET="$ASSET" VERSION_CODE="$VERSION_CODE" VERSION_NAME="$VERSION_NAME" \
SHA="$SHA" SIZE="$SIZE" MIN_SUPPORTED="$MIN_SUPPORTED" NOTES="$NOTES" \
python3 - "$WORK/payload.json" <<'PY'
import json, os, sys
payload = {
    "schema": 1,
    "versionCode": int(os.environ["VERSION_CODE"]),
    "versionName": os.environ["VERSION_NAME"],
    "apkAsset": os.environ["ASSET"],
    "apkSha256": os.environ["SHA"],
    "apkSizeBytes": int(os.environ["SIZE"]),
    "minSupportedVersionCode": int(os.environ["MIN_SUPPORTED"]),
    "notes": os.environ["NOTES"],
}
with open(sys.argv[1], "w", encoding="utf-8") as f:
    f.write(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
PY

openssl dgst -sha256 -sign "$KEY_FILE" -out "$WORK/signature.der" "$WORK/payload.json"

python3 - "$WORK/payload.json" "$WORK/signature.der" "$OUT" <<'PY'
import base64, json, sys
payload = open(sys.argv[1], "rb").read()
signature = open(sys.argv[2], "rb").read()
envelope = {
    "payload": base64.b64encode(payload).decode("ascii"),
    "signature": base64.b64encode(signature).decode("ascii"),
}
with open(sys.argv[3], "w", encoding="utf-8") as f:
    json.dump(envelope, f)
    f.write("\n")
PY

echo "Wrote $OUT"
echo "  asset:  $ASSET"
echo "  sha256: $SHA"
echo "  size:   $SIZE"
echo "  version: $VERSION_NAME ($VERSION_CODE)"
echo
echo "Next: upload $OUT and $APK to a GitHub release, then verify:"
echo "  curl -fsSL https://github.com/Musila-ke/MyExodus/releases/latest/download/update-manifest.json"

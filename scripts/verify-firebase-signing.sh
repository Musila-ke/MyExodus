#!/usr/bin/env bash
# Release gate (owner incident 2026-11-08): a release build whose signing certificate is
# missing from Firebase's Android API-key allow-list is rejected on-device at Firebase Auth
# ("Requests from this Android client application are blocked") — a total release blocker.
# Run before publishing any APK.
set -euo pipefail
cd "$(dirname "$0")/.."

SIGNING_PROPS="${MYEXODUS_SIGNING_PROPS:-$HOME/.myexodus-release-key/signing.properties}"
[ -f "$SIGNING_PROPS" ] || { echo "FAIL: $SIGNING_PROPS not found"; exit 1; }
prop() { grep "^$1=" "$SIGNING_PROPS" | head -1 | cut -d= -f2-; }
STORE=$(prop storeFile); ALIAS=$(prop keyAlias); STOREPASS=$(prop storePassword)

SHA1=$(keytool -list -v -keystore "$STORE" -alias "$ALIAS" -storepass "$STOREPASS" 2>/dev/null \
  | awk -F': ' '/SHA1:/{print $2}' | tr -d ':' | tr 'A-F' 'a-f')
[ -n "$SHA1" ] || { echo "FAIL: cannot read SHA-1 from $STORE"; exit 1; }

PROJECT=$(jq -r .project_info.project_id app/google-services.json)
PROJECT_NUMBER=$(jq -r .project_info.project_number app/google-services.json)
APP_ID=$(jq -r '.client[0].client_info.mobilesdk_app_id' app/google-services.json)
KEY=$(jq -r '.client[0].api_key[0].current_key' app/google-services.json)

TOKEN=$(curl -s -X POST https://www.googleapis.com/oauth2/v4/token \
  --data-urlencode "client_id=563584335869-fgrhgmd47bqnekij5i8b5pr03ho849e6.apps.googleusercontent.com" \
  --data-urlencode "client_secret=j9iVZfS8kkCEFUPaAeJV0sAi" \
  --data-urlencode "refresh_token=$(jq -r .tokens.refresh_token "$HOME/.config/configstore/firebase-tools.json")" \
  --data-urlencode "grant_type=refresh_token" | jq -r .access_token)
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || { echo "FAIL: cannot mint a Google token (firebase login?)"; exit 1; }

REGISTERED=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "https://firebase.googleapis.com/v1beta1/projects/$PROJECT/androidApps/$APP_ID/sha" \
  | jq -r '.certificates[]?.shaHash' | tr -d ':' | tr 'A-F' 'a-f' | grep -cx "$SHA1" || true)
[ "$REGISTERED" -gt 0 ] || {
  echo "FAIL: $SHA1 is not registered on Firebase app $APP_ID."
  echo "Fix: POST https://firebase.googleapis.com/v1beta1/projects/$PROJECT/androidApps/$APP_ID/sha"
  echo "     body {\"shaHash\":\"<colon SHA-1>\",\"certType\":\"SHA_1\"}"
  exit 1
}

KEY_UID=""
for uid in $(curl -s -H "Authorization: Bearer $TOKEN" \
    "https://apikeys.googleapis.com/v2/projects/$PROJECT_NUMBER/locations/global/keys" \
    | jq -r '.keys[]?.uid' 2>/dev/null); do
  if [ "$(curl -s -H "Authorization: Bearer $TOKEN" \
      "https://apikeys.googleapis.com/v2/projects/$PROJECT_NUMBER/locations/global/keys/$uid/keyString" \
      | jq -r .keyString)" = "$KEY" ]; then KEY_UID=$uid; break; fi
done
[ -n "$KEY_UID" ] || { echo "FAIL: API key from google-services.json not found in project $PROJECT (enable apikeys.googleapis.com?)"; exit 1; }

ALLOWED=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "https://apikeys.googleapis.com/v2/projects/$PROJECT_NUMBER/locations/global/keys/$KEY_UID" \
  | jq -r '.restrictions.androidKeyRestrictions.allowedApplications[]? | select(.packageName=="com.example.myexodus") | .sha1Fingerprint' \
  | grep -cx "$SHA1" || true)
[ "$ALLOWED" -gt 0 ] || {
  echo "FAIL: $SHA1 is not in the Android API key allow-list (the Auth enforcement point)."
  exit 1
}

echo "PASS: release SHA-1 $SHA1 registered on Firebase and allowed by the Android API key."

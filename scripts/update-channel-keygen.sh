#!/bin/bash
# ADR #171 update channel: create the manifest signing key that the app pins in
# UpdateConfig.MANIFEST_PUBLIC_KEY_BASE64. The private key never enters the repo; losing it
# strands every installed build on its current version (the pinned key cannot be rotated
# remotely), so back the file up somewhere the owner controls.
set -euo pipefail

KEY_DIR="${KEY_DIR:-$HOME/.myexodus-update-key}"
KEY_FILE="$KEY_DIR/update-manifest-key.pem"

mkdir -p "$KEY_DIR"
chmod 700 "$KEY_DIR"

if [ -f "$KEY_FILE" ]; then
  echo "Key already exists: $KEY_FILE (refusing to overwrite; delete it deliberately if rotating)" >&2
else
  openssl ecparam -name prime256v1 -genkey -noout -out "$KEY_FILE"
  chmod 600 "$KEY_FILE"
  echo "Created $KEY_FILE"
fi

echo
echo "SPKI public key (paste into UpdateConfig.MANIFEST_PUBLIC_KEY_BASE64):"
openssl ec -in "$KEY_FILE" -pubout -outform DER 2>/dev/null | base64

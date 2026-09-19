'use strict';
// Pairing-proof canonicalization + verification (plan §8 Step 3 / AD-02).
// Pure Node crypto — no firebase imports, so this module is unit-testable without the
// Functions SDK and without a Firestore emulator.

const crypto = require('node:crypto');

// Domain separation: the signed message is never a bare concatenation. Bump the domain
// string, not the field order, if the proof format ever changes.
const PROOF_DOMAIN = 'MyExodus-Pairing-Proof-v1';
const SUPPORTED_PROTOCOL_VERSIONS = [1];
const HASH_HEX_LENGTH = 64;

/**
 * The exact bytes both devices sign with their (biometric-gated) identity key:
 *   PROOF_DOMAIN \n protocolVersion \n ownUid \n partnerUid \n publicKeyHash \n transcriptHash
 * protocolVersion is inside the signed payload on purpose — the plan lists it as a proof
 * field, and an unsigned version field would allow a downgrade claim.
 */
function canonicalProofMessage({ ownUid, partnerUid, publicKeyHash, transcriptHash, protocolVersion }) {
  return [PROOF_DOMAIN, String(protocolVersion), ownUid, partnerUid, publicKeyHash, transcriptHash].join('\n');
}

function sha256Hex(bytes) {
  return crypto.createHash('sha256').update(bytes).digest('hex');
}

/** §10: sender key id = hex SHA-256 of the signing key's DER SPKI — same rule client-side. */
function publicKeyHashOf(spkiDerB64) {
  return sha256Hex(Buffer.from(spkiDerB64, 'base64'));
}

function isHashHex(value) {
  return typeof value === 'string' && value.length === HASH_HEX_LENGTH && /^[0-9a-f]+$/.test(value);
}

function isNonEmptyString(value) {
  return typeof value === 'string' && value.length > 0;
}

/**
 * Structural + cryptographic validation of one proof. Returns { ok: true } or
 * { ok: false, reason } — reasons are stable codes, never echoed content (plan §10:
 * no payload content in logs).
 */
function verifyProof(proof) {
  if (proof === null || typeof proof !== 'object') return { ok: false, reason: 'MALFORMED' };
  const { ownUid, partnerUid, publicKeyHash, transcriptHash, protocolVersion } = proof;
  if (!isNonEmptyString(ownUid) || !isNonEmptyString(partnerUid)) return { ok: false, reason: 'MALFORMED' };
  if (ownUid === partnerUid) return { ok: false, reason: 'SELF_PAIRING' };
  if (!isHashHex(publicKeyHash) || !isHashHex(transcriptHash)) return { ok: false, reason: 'MALFORMED' };
  if (!SUPPORTED_PROTOCOL_VERSIONS.includes(protocolVersion)) return { ok: false, reason: 'PROTOCOL_VERSION_UNSUPPORTED' };
  if (!isNonEmptyString(proof.signingPublicKey) || !isNonEmptyString(proof.signature)) {
    // The plan's proof field list omits the public key; signature verification is
    // impossible without it, so it travels with the proof and is bound by publicKeyHash.
    return { ok: false, reason: 'MALFORMED' };
  }
  if (publicKeyHashOf(proof.signingPublicKey) !== publicKeyHash) return { ok: false, reason: 'KEY_HASH_MISMATCH' };

  const message = Buffer.from(canonicalProofMessage(proof), 'utf8');
  let key;
  try {
    key = crypto.createPublicKey({
      key: Buffer.from(proof.signingPublicKey, 'base64'),
      format: 'der',
      type: 'spki',
    });
  } catch (e) {
    return { ok: false, reason: 'KEY_UNPARSEABLE' };
  }
  // DER-encoded ECDSA signature (JCA default) over SHA-256.
  const verified = crypto.verify('sha256', message, key, Buffer.from(proof.signature, 'base64'));
  return verified ? { ok: true } : { ok: false, reason: 'SIGNATURE_INVALID' };
}

module.exports = {
  PROOF_DOMAIN,
  SUPPORTED_PROTOCOL_VERSIONS,
  canonicalProofMessage,
  publicKeyHashOf,
  isHashHex,
  isNonEmptyString,
  verifyProof,
};

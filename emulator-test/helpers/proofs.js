'use strict';
// Proof builder for the §12.2 emulator suite: real ECDSA P-256 signing over the server's own
// canonical message (../functions/proof.js), so `verifyProof` runs for real — only the
// attestation *verifier* is stubbed at the factory seam, and only for the in-process leg
// (the emulator cannot mint Google-rooted chains; the HTTP leg runs the real verifier).
//
// The canonical byte layout itself is pinned by the client-mirror unit suites plus the
// device-verified pairings (ADR #96/#99), so signing via the server's own function here is
// deliberate: a duplicated literal would be a second source of truth that rots silently.

const crypto = require('node:crypto');
const { makePki } = require('../../functions/test/helpers/der-pki');
const { canonicalProofMessage, publicKeyHashOf } = require('../../functions/proof');

const RECORD_ID_ALPHABET = 'abcdefghijklmnopqrstuvwxyz0123456789';

/** uid-shaped ids: `pairIdOf` splits on '_', and real Firebase uids never contain one. */
function newUid(prefix = 'uid') {
  let suffix = '';
  const bytes = crypto.randomBytes(8);
  for (const b of bytes) suffix += RECORD_ID_ALPHABET[b % RECORD_ID_ALPHABET.length];
  return `${prefix}-${suffix}`;
}

/** §9: requestId is a Firestore doc id — pattern-valid, collision-free, no crypto needed. */
function newRequestId() {
  return `req-${crypto.randomBytes(12).toString('base64url')}`;
}

/** §8.3's transcriptHash role: 64 hex chars binding one attempt. Any digest works here. */
function transcriptOf(label) {
  return crypto.createHash('sha256').update(`transcript:${label}`).digest('hex');
}

/**
 * A signing identity without attestation evidence — enough for the handler-level leg
 * (attestation stubbed) and for the HTTP leg's proof-ordering cases.
 */
function makeIdentity(uid = newUid()) {
  const keys = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const spkiB64 = keys.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  return { uid, keys, signingPublicKey: spkiB64, publicKeyHash: publicKeyHashOf(spkiB64) };
}

/**
 * Identity carrying a full synthetic leaf→intermediate chain (der-pki), so the real
 * attestation verifier can be driven all the way to its pinned-roots check over the wire.
 */
function makeAttestedIdentity(uid = newUid()) {
  const challenge = crypto.randomBytes(32);
  const pki = makePki({ leafChallenge: challenge });
  const spkiB64 = pki.proof.signingPublicKey;
  return {
    uid,
    keys: pki.leafKeys,
    signingPublicKey: spkiB64,
    publicKeyHash: publicKeyHashOf(spkiB64),
    attestationChain: pki.proof.attestationChain,
    attestationChallenge: pki.proof.attestationChallenge,
  };
}

/**
 * The proof as the wire carries it: signed fields + key + signature + requestId, plus the
 * attestation evidence when the identity has any. `overrides` mutates signed fields
 * *after* signing — that is how the tamper cases are built.
 */
function makeProof(identity, { partnerUid, transcriptHash, protocolVersion = 1, requestId = newRequestId(), overrides = {} } = {}) {
  const fields = {
    ownUid: identity.uid,
    partnerUid,
    publicKeyHash: identity.publicKeyHash,
    transcriptHash,
    protocolVersion,
  };
  const signature = crypto
    .sign('sha256', Buffer.from(canonicalProofMessage(fields), 'utf8'), identity.keys.privateKey)
    .toString('base64');
  const proof = { ...fields, signingPublicKey: identity.signingPublicKey, signature, requestId };
  if (identity.attestationChain) {
    proof.attestationChain = identity.attestationChain;
    proof.attestationChallenge = identity.attestationChallenge;
  }
  return { ...proof, ...overrides };
}

function pairIdOf(uidA, uidB) {
  const [min, max] = uidA < uidB ? [uidA, uidB] : [uidB, uidA];
  return `${min}_${max}`;
}

module.exports = { newUid, newRequestId, transcriptOf, makeIdentity, makeAttestedIdentity, makeProof, pairIdOf };

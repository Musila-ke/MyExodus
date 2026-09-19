'use strict';
// Runnable check for the pairing-proof crypto boundary (plan §8 Step 3). No emulator
// needed: proof.js is pure node:crypto. Run with `npm test` in functions/.

const { test } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');

const {
  canonicalProofMessage,
  publicKeyHashOf,
  verifyProof,
} = require('../proof');

function makeKeypair() {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const spkiB64 = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  return { privateKey, spkiB64, hash: publicKeyHashOf(spkiB64) };
}

function signedProof(overrides = {}) {
  const key = makeKeypair();
  const base = {
    ownUid: 'uidA',
    partnerUid: 'uidB',
    publicKeyHash: key.hash,
    transcriptHash: 'a'.repeat(64),
    protocolVersion: 1,
    signingPublicKey: key.spkiB64,
  };
  const proof = { ...base, ...overrides };
  const message = Buffer.from(canonicalProofMessage(proof), 'utf8');
  proof.signature = crypto.sign('sha256', message, key.privateKey).toString('base64');
  return proof;
}

test('canonical message is domain-separated and binds protocolVersion', () => {
  const msg = canonicalProofMessage({
    ownUid: 'u1',
    partnerUid: 'u2',
    publicKeyHash: 'a'.repeat(64),
    transcriptHash: 'b'.repeat(64),
    protocolVersion: 1,
  });
  assert.strictEqual(
    msg,
    ['MyExodus-Pairing-Proof-v1', '1', 'u1', 'u2', 'a'.repeat(64), 'b'.repeat(64)].join('\n'),
  );
});

test('a correctly signed proof verifies', () => {
  assert.deepStrictEqual(verifyProof(signedProof()), { ok: true });
});

test('tampering with any signed field invalidates the signature', () => {
  const proof = signedProof();
  proof.transcriptHash = 'b'.repeat(64);
  assert.strictEqual(verifyProof(proof).reason, 'SIGNATURE_INVALID');

  const uidSwap = signedProof();
  [uidSwap.ownUid, uidSwap.partnerUid] = [uidSwap.partnerUid, uidSwap.ownUid];
  assert.strictEqual(verifyProof(uidSwap).reason, 'SIGNATURE_INVALID');
});

test('a signature from a different key is rejected', () => {
  const proof = signedProof();
  const other = makeKeypair();
  const message = Buffer.from(canonicalProofMessage(proof), 'utf8');
  proof.signature = crypto.sign('sha256', message, other.privateKey).toString('base64');
  assert.strictEqual(verifyProof(proof).reason, 'SIGNATURE_INVALID');
});

test('structural rejections use stable reason codes', () => {
  assert.strictEqual(verifyProof(null).reason, 'MALFORMED');
  assert.strictEqual(verifyProof({}).reason, 'MALFORMED');

  const self = signedProof({ partnerUid: 'uidA' });
  assert.strictEqual(verifyProof(self).reason, 'SELF_PAIRING');

  const badVersion = signedProof({ protocolVersion: 99 });
  assert.strictEqual(verifyProof(badVersion).reason, 'PROTOCOL_VERSION_UNSUPPORTED');

  const swappedKey = signedProof();
  swappedKey.publicKeyHash = 'c'.repeat(64);
  assert.strictEqual(verifyProof(swappedKey).reason, 'KEY_HASH_MISMATCH');
});

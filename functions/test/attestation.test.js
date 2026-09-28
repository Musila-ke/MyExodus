'use strict';
// Attestation verifier tests (plan §19.4 item 2 / §12.1). Two vector families:
//   1. Synthetic root->int->leaf PKI built at runtime (helpers/der-pki.js) covering the
//      KeyDescription checks, chain linkage, validity, and tampering.
//   2. The repo's openssl-generated PEM fixtures (copies of app/src/test/resources/attestation,
//      byte-identical) — the same vectors the Kotlin AttestationVerifierTest pins, so one
//      fixture set pins both implementations.

const { test } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const { verifyAttestation, validateChain, trustAnchors } = require('../attestation');
const { makePki, keyDescription, buildCert, newKey } = require('./helpers/der-pki');

const FIXTURES = path.join(__dirname, 'fixtures', 'attestation');
const loadCert = (name) => new crypto.X509Certificate(fs.readFileSync(path.join(FIXTURES, name)));

test('synthetic TEE chain verifies against its injected anchor', () => {
  const pki = makePki();
  const result = verifyAttestation(pki.proof, [pki.rootCert]);
  assert.deepStrictEqual(result, { ok: true, securityLevel: 1 });
});

test('strongbox security level is accepted', () => {
  const pki = makePki({ level: 2 });
  assert.strictEqual(verifyAttestation(pki.proof, [pki.rootCert]).ok, true);
});

test('software security level is rejected', () => {
  const pki = makePki({ level: 0 });
  assert.strictEqual(verifyAttestation(pki.proof, [pki.rootCert]).reason, 'KEY_SOFTWARE');
});

test('unknown security level is rejected', () => {
  const pki = makePki({ level: 3 });
  assert.strictEqual(verifyAttestation(pki.proof, [pki.rootCert]).reason, 'KEY_LEVEL_UNKNOWN');
});

test('challenge mismatch is rejected', () => {
  const pki = makePki();
  pki.proof.attestationChallenge = crypto.randomBytes(32).toString('base64');
  assert.strictEqual(verifyAttestation(pki.proof, [pki.rootCert]).reason, 'CHALLENGE_MISMATCH');
});

test('non-GENERATED origin is rejected', () => {
  const pki = makePki({ origin: 2 });
  assert.strictEqual(verifyAttestation(pki.proof, [pki.rootCert]).reason, 'ORIGIN_NOT_GENERATED');
});

test('purpose without SIGN is rejected', () => {
  const pki = makePki({ purposes: [0] });
  assert.strictEqual(verifyAttestation(pki.proof, [pki.rootCert]).reason, 'PURPOSE_MISSING_SIGN');
});

test('purpose decodes in both Keymaster and implicit encodings', () => {
  for (const purposeEncoding of ['explicit-set', 'implicit']) {
    const pki = makePki({ purposeEncoding });
    assert.strictEqual(verifyAttestation(pki.proof, [pki.rootCert]).ok, true, purposeEncoding);
  }
});

test('leaf key that is not the proof signing key is rejected', () => {
  const pki = makePki();
  pki.proof.signingPublicKey = newKey().publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  assert.strictEqual(verifyAttestation(pki.proof, [pki.rootCert]).reason, 'KEY_CHAIN_MISMATCH');
});

test('missing KeyDescription extension is rejected', () => {
  const pki = makePki({ leafKeyDescription: false });
  assert.strictEqual(verifyAttestation(pki.proof, [pki.rootCert]).reason, 'ATTESTATION_MISSING');
});

test('a chain to a root outside the anchors is rejected', () => {
  const pki = makePki();
  const foreign = makePki();
  assert.strictEqual(verifyAttestation(pki.proof, [foreign.rootCert]).reason, 'CHAIN_UNTRUSTED');
});

test('a non-CA intermediate is rejected', () => {
  const pki = makePki({ caIntermediate: false });
  assert.strictEqual(verifyAttestation(pki.proof, [pki.rootCert]).reason, 'CHAIN_UNTRUSTED');
});

test('an expired leaf is rejected', () => {
  const pki = makePki({
    leafNotBefore: new Date(Date.now() - 172800000),
    leafNotAfter: new Date(Date.now() - 86400000),
  });
  assert.strictEqual(verifyAttestation(pki.proof, [pki.rootCert]).reason, 'CHAIN_UNTRUSTED');
});

test('an expired anchor still validates (JDK-PKIX anchor exemption, Root 1 semantics)', () => {
  const rootKeys = newKey();
  const intKeys = newKey();
  const leafKeys = newKey();
  const expiredRoot = buildCert({
    subjectCn: 'Expired Root', issuerCn: 'Expired Root',
    publicKey: rootKeys.publicKey, signingKey: rootKeys.privateKey,
    notBefore: new Date(Date.now() - 63115200000), notAfter: new Date(Date.now() - 86400000), ca: true,
  });
  const int = buildCert({
    subjectCn: 'Issuer', issuerCn: 'Expired Root',
    publicKey: intKeys.publicKey, signingKey: rootKeys.privateKey,
    notBefore: new Date(Date.now() - 86400000), notAfter: new Date(Date.now() + 86400000), ca: true,
  });
  const challenge = crypto.randomBytes(32);
  const leaf = buildCert({
    subjectCn: 'Key', issuerCn: 'Issuer',
    publicKey: leafKeys.publicKey, signingKey: intKeys.privateKey,
    notBefore: new Date(Date.now() - 86400000), notAfter: new Date(Date.now() + 86400000),
    keyDescription: keyDescription({ challenge }),
  });
  const proof = {
    signingPublicKey: leafKeys.publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
    attestationChain: [leaf, int].map((d) => d.toString('base64')),
    attestationChallenge: challenge.toString('base64'),
  };
  const result = verifyAttestation(proof, [new crypto.X509Certificate(expiredRoot)]);
  assert.deepStrictEqual(result, { ok: true, securityLevel: 1 });
});

test('a tampered leaf fails chain verification', () => {
  const pki = makePki();
  const tampered = Buffer.from(pki.proof.attestationChain[0], 'base64');
  tampered[tampered.length - 1] ^= 0xff; // inside the signature BIT STRING
  pki.proof.attestationChain[0] = tampered.toString('base64');
  const reason = verifyAttestation(pki.proof, [pki.rootCert]).reason;
  assert.strictEqual(reason, 'CHAIN_UNTRUSTED');
});

test('structural garbage is rejected cleanly', () => {
  const pki = makePki();
  assert.strictEqual(verifyAttestation(null).reason, 'MALFORMED');
  assert.strictEqual(verifyAttestation({}).reason, 'MALFORMED');
  assert.strictEqual(verifyAttestation({ ...pki.proof, attestationChain: [] }).reason, 'MALFORMED');
  assert.strictEqual(
    verifyAttestation({ ...pki.proof, attestationChain: Array(5).fill(pki.proof.attestationChain[0]) }).reason,
    'MALFORMED',
  );
  assert.strictEqual(
    verifyAttestation({ ...pki.proof, attestationChain: ['not base64 !!'] }).reason,
    'MALFORMED',
  );
  assert.strictEqual(verifyAttestation({ ...pki.proof, attestationChallenge: '' }).reason, 'MALFORMED');
});

test('the two pinned anchors are the Google hardware attestation roots', () => {
  const anchors = trustAnchors();
  assert.strictEqual(anchors.length, 2);
  assert.deepStrictEqual(
    new Set(anchors.map((a) => a.serialNumber)),
    new Set(['E8FA196314D2FA18', '3505A0A2FDCC51E4']),
  );
});

test('the repo PEM fixtures validate identically to the Kotlin suite', () => {
  const leaf = loadCert('leaf.pem');
  const int = loadCert('int.pem');
  const root = loadCert('root.pem');
  const wrong = loadCert('wrong.pem');
  assert.strictEqual(validateChain([leaf, int, root], [root]), true);
  assert.strictEqual(validateChain([leaf, int, wrong], [root]), false);
});

test('validateChain rejects an empty chain', () => {
  assert.strictEqual(validateChain([], trustAnchors()), false);
});

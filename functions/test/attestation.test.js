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

const {
  verifyAttestation, validateChain, trustAnchors, fetchRevocationEntries,
  PROVISIONING_INFO_OID,
} = require('../attestation');
const {
  makePki, keyDescription, buildCert, newKey,
  applicationId, rootOfTrust, provisioningInfo,
} = require('./helpers/der-pki');

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
    verifyAttestation({ ...pki.proof, attestationChain: Array(9).fill(pki.proof.attestationChain[0]) }).reason,
    'MALFORMED',
  );
  assert.strictEqual(
    verifyAttestation({ ...pki.proof, attestationChain: ['not base64 !!'] }).reason,
    'MALFORMED',
  );
  assert.strictEqual(verifyAttestation({ ...pki.proof, attestationChallenge: '' }).reason, 'MALFORMED');
});

test('the two pinned anchors are the current official Google roots (RSA + RKP ECDSA)', () => {
  const anchors = trustAnchors();
  assert.strictEqual(anchors.length, 2);
  assert.deepStrictEqual(
    new Set(anchors.map((a) => a.serialNumber)),
    new Set(['F1C172A699EAF51D', '84A9D0297B0EB58AE7FF0E80DE760605']),
  );
  assert.ok(anchors.some((a) => a.subject.includes('f92009e853b6b045')));
  assert.ok(anchors.some((a) => a.subject.includes('Key Attestation CA1')));
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

// --- Phase 2 (§8.3.5/§10.4): application binding, verified boot, revocation, RKP selection ---

const PACKAGE = 'com.example.myexodus';
const SIGNING_DIGEST = Buffer.alloc(32, 7);

test('application binding accepts the expected package and certificate digest', () => {
  const pki = makePki({
    applicationIdDer: applicationId({ packageName: PACKAGE, signatureDigests: [SIGNING_DIGEST] }),
  });
  const options = {
    applicationId: { packageName: PACKAGE, signatureDigestSha256: SIGNING_DIGEST.toString('hex') },
  };
  assert.deepStrictEqual(verifyAttestation(pki.proof, [pki.rootCert], Date.now(), options),
    { ok: true, securityLevel: 1 });
});

test('a foreign package, foreign signing certificate, or absent application id is rejected', () => {
  const pki = makePki({
    applicationIdDer: applicationId({ packageName: PACKAGE, signatureDigests: [SIGNING_DIGEST] }),
  });
  const expected = { packageName: PACKAGE, signatureDigestSha256: SIGNING_DIGEST.toString('hex') };
  assert.strictEqual(
    verifyAttestation(pki.proof, [pki.rootCert], Date.now(),
      { applicationId: { ...expected, packageName: 'com.example.other' } }).reason,
    'APPLICATION_ID_MISMATCH',
  );
  assert.strictEqual(
    verifyAttestation(pki.proof, [pki.rootCert], Date.now(),
      { applicationId: { ...expected, signatureDigestSha256: Buffer.alloc(32, 9).toString('hex') } }).reason,
    'APPLICATION_ID_MISMATCH',
  );
  const bare = makePki();
  assert.strictEqual(
    verifyAttestation(bare.proof, [bare.rootCert], Date.now(), { applicationId: expected }).reason,
    'APPLICATION_ID_MISSING',
  );
});

test('verified boot is required only when asked, and only state Verified passes', () => {
  const verified = makePki({ rootOfTrustDer: rootOfTrust({ verifiedBootState: 0 }) });
  assert.deepStrictEqual(
    verifyAttestation(verified.proof, [verified.rootCert], Date.now(), { requireVerifiedBoot: true }),
    { ok: true, securityLevel: 1 },
  );
  const unlocked = makePki({ rootOfTrustDer: rootOfTrust({ verifiedBootState: 2, deviceLocked: false }) });
  assert.strictEqual(
    verifyAttestation(unlocked.proof, [unlocked.rootCert], Date.now(), { requireVerifiedBoot: true }).reason,
    'VERIFIED_BOOT_STATE_INVALID',
  );
  const absent = makePki();
  assert.strictEqual(
    verifyAttestation(absent.proof, [absent.rootCert], Date.now(), { requireVerifiedBoot: true }).reason,
    'ROOT_OF_TRUST_MISSING',
  );
  // The pairing path passes no options, so presence alone never changes its behavior.
  assert.strictEqual(verifyAttestation(unlocked.proof, [unlocked.rootCert]).ok, true);
});

test('revoked and suspended chain certificates are rejected; expired entries are ignored', () => {
  const pki = makePki();
  const leaf = new crypto.X509Certificate(Buffer.from(pki.proof.attestationChain[0], 'base64'));
  const serial = leaf.serialNumber.toLowerCase();
  const now = Date.now();
  assert.strictEqual(
    verifyAttestation(pki.proof, [pki.rootCert], now,
      { revocationEntries: { [serial]: { status: 'REVOKED', expires: null } } }).reason,
    'CERTIFICATE_REVOKED',
  );
  assert.strictEqual(
    verifyAttestation(pki.proof, [pki.rootCert], now,
      { revocationEntries: { [serial]: { status: 'SUSPENDED', expires: null } } }).reason,
    'CERTIFICATE_REVOKED',
  );
  assert.deepStrictEqual(
    verifyAttestation(pki.proof, [pki.rootCert], now,
      { revocationEntries: { [serial]: { status: 'REVOKED', expires: new Date(now - 1000).toISOString() } } }),
    { ok: true, securityLevel: 1 },
  );
});

test('revocation status list parses official entries and fails closed without a cache', async () => {
  const body = {
    entries: {
      ABC123: { status: 'REVOKED' },
      DEF456: { status: 'SUSPENDED', expires: '2020-01-01T00:00:00Z' },
      GHI789: { status: 'UNKNOWN_STATUS' },
    },
  };
  await assert.rejects(
    fetchRevocationEntries({
      useCache: false,
      fetchImpl: async () => { throw new Error('offline'); },
    }),
  );
  const entries = await fetchRevocationEntries({
    useCache: false,
    now: Date.now(),
    fetchImpl: async () => ({ ok: true, json: async () => body }),
  });
  assert.deepStrictEqual(entries.abc123, { status: 'REVOKED', expires: null });
  assert.deepStrictEqual(entries.def456, { status: 'SUSPENDED', expires: '2020-01-01T00:00:00Z' });
  assert.deepStrictEqual(entries.ghi789, { status: 'UNKNOWN_STATUS', expires: null });
});

test('RKP: the attestation extension nearest the root is selected, not the leaf', () => {
  const rootKeys = newKey();
  const keyKeys = newKey();
  const decoyKeys = newKey();
  const challenge = crypto.randomBytes(32);
  const now = Date.now();
  const root = buildCert({
    subjectCn: 'RKP Root', issuerCn: 'RKP Root',
    publicKey: rootKeys.publicKey, signingKey: rootKeys.privateKey,
    notBefore: new Date(now - 86400000), notAfter: new Date(now + 31536000000), ca: true,
    extraExtensions: [{ oid: PROVISIONING_INFO_OID, valueDer: provisioningInfo() }],
  });
  // The RKP-issued key certificate carries the genuine KeyDescription; the leaf below it
  // carries an attacker-appended one that must be ignored.
  const keyCert = buildCert({
    subjectCn: 'RKP Device Key', issuerCn: 'RKP Root',
    publicKey: keyKeys.publicKey, signingKey: rootKeys.privateKey,
    notBefore: new Date(now - 86400000), notAfter: new Date(now + 31536000000), ca: true,
    keyDescription: keyDescription({ challenge }),
  });
  const decoyLeaf = buildCert({
    subjectCn: 'Decoy', issuerCn: 'RKP Device Key',
    publicKey: decoyKeys.publicKey, signingKey: keyKeys.privateKey,
    notBefore: new Date(now - 86400000), notAfter: new Date(now + 31536000000),
    keyDescription: keyDescription({ challenge: crypto.randomBytes(32) }),
  });
  const rootCert = new crypto.X509Certificate(root);
  const proof = {
    signingPublicKey: keyKeys.publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
    attestationChain: [decoyLeaf, keyCert, root].map((der) => der.toString('base64')),
    attestationChallenge: challenge.toString('base64'),
  };
  assert.deepStrictEqual(verifyAttestation(proof, [rootCert]), { ok: true, securityLevel: 1 });
});

test('RKP: a provisioning-information extension in the wrong position is rejected', () => {
  const rootKeys = newKey();
  const keyKeys = newKey();
  const challenge = crypto.randomBytes(32);
  const now = Date.now();
  const root = buildCert({
    subjectCn: 'RKP Root', issuerCn: 'RKP Root',
    publicKey: rootKeys.publicKey, signingKey: rootKeys.privateKey,
    notBefore: new Date(now - 86400000), notAfter: new Date(now + 31536000000), ca: true,
  });
  // Provisioning info sits in the same certificate as the KeyDescription: the attestation
  // extension must instead be immediately toward the leaf of it.
  const keyCert = buildCert({
    subjectCn: 'RKP Device Key', issuerCn: 'RKP Root',
    publicKey: keyKeys.publicKey, signingKey: rootKeys.privateKey,
    notBefore: new Date(now - 86400000), notAfter: new Date(now + 31536000000),
    keyDescription: keyDescription({ challenge }),
    extraExtensions: [{ oid: PROVISIONING_INFO_OID, valueDer: provisioningInfo() }],
  });
  const proof = {
    signingPublicKey: keyKeys.publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
    attestationChain: [keyCert, root].map((der) => der.toString('base64')),
    attestationChallenge: challenge.toString('base64'),
  };
  assert.strictEqual(
    verifyAttestation(proof, [new crypto.X509Certificate(root)]).reason,
    'PROVISIONING_SEQUENCE_INVALID',
  );
});

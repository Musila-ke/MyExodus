'use strict';
// Test-only DER/X.509 builder: synthesizes the root -> intermediate -> leaf PKI and the
// Android KeyDescription extension so attestation.js can be exercised end-to-end without a
// real device chain (the Kotlin side does the same with BouncyCastle; this is the inverse of
// the parser in attestation.js, hand-rolled on node:crypto primitives).

const crypto = require('node:crypto');

function lenBytes(n) {
  if (n < 0x80) return Buffer.from([n]);
  const out = [];
  let v = n;
  while (v > 0) {
    out.unshift(v & 0xff);
    v >>= 8;
  }
  return Buffer.from([0x80 | out.length, ...out]);
}

function tlv(tagBytes, content) {
  return Buffer.concat([Buffer.from(tagBytes), lenBytes(content.length), content]);
}

const seq = (...parts) => tlv([0x30], Buffer.concat(parts));
const set = (...parts) => tlv([0x31], Buffer.concat(parts));
const octetString = (bytes) => tlv([0x04], Buffer.from(bytes));
const utf8 = (s) => tlv([0x0c], Buffer.from(s, 'utf8'));
const boolean = (v) => tlv([0x01], Buffer.from([v ? 0xff : 0x00]));
const enumerated = (n) => tlv([0x0a], Buffer.from([n]));

function int(n) {
  if (n === 0) return tlv([0x02], Buffer.from([0]));
  const out = [];
  let v = n;
  while (v > 0) {
    out.unshift(v & 0xff);
    v >>= 8;
  }
  if (out[0] & 0x80) out.unshift(0x00);
  return tlv([0x02], Buffer.from(out));
}

function oid(dotted) {
  const parts = dotted.split('.').map(Number);
  const out = [40 * parts[0] + parts[1]];
  for (const part of parts.slice(2)) {
    const groups = [part & 0x7f];
    let v = part >> 7;
    while (v > 0) {
      groups.unshift((v & 0x7f) | 0x80);
      v >>= 7;
    }
    out.push(...groups);
  }
  return tlv([0x06], Buffer.from(out));
}

function bitString(bytes) {
  return tlv([0x03], Buffer.concat([Buffer.from([0x00]), Buffer.from(bytes)]));
}

function utcTime(date) {
  const p = (n, w = 2) => String(n).padStart(w, '0');
  const s = `${p(date.getUTCFullYear() % 100)}${p(date.getUTCMonth() + 1)}${p(date.getUTCDate())}` +
    `${p(date.getUTCHours())}${p(date.getUTCMinutes())}${p(date.getUTCSeconds())}Z`;
  return tlv([0x17], Buffer.from(s, 'ascii'));
}

/** Context-class tag; tagNo >= 31 uses the high-tag-number form. */
function ctx(tagNo, constructed, content) {
  if (tagNo < 31) return tlv([(constructed ? 0xa0 : 0x80) | tagNo], content);
  const groups = [tagNo & 0x7f];
  let v = tagNo >> 7;
  while (v > 0) {
    groups.unshift((v & 0x7f) | 0x80);
    v >>= 7;
  }
  return tlv([0xbf, ...groups], content);
}

const nameOf = (cn) => seq(set(seq(oid('2.5.4.3'), utf8(cn))));

const SIG_ALG_ECDSA_SHA256 = seq(oid('1.2.840.10045.4.3.2'));

/** AttestationApplicationId DER: SEQUENCE { SET OF packageInfo, SET OF digest }. */
function applicationId({ packageName, version = 1, signatureDigests = [] }) {
  return seq(
    set(seq(octetString(Buffer.from(packageName, 'utf8')), int(version))),
    set(...signatureDigests.map((digest) => octetString(digest))),
  );
}

/** RootOfTrust DER: SEQUENCE { OCTET STRING key, BOOLEAN locked, ENUMERATED state }. */
function rootOfTrust({ verifiedBootKey = Buffer.alloc(32), deviceLocked = true, verifiedBootState = 0 }) {
  return seq(octetString(verifiedBootKey), boolean(deviceLocked), enumerated(verifiedBootState));
}

/**
 * Android KeyDescription. `purposeEncoding: 'explicit-set'` is the Keymaster wire shape
 * (caught on the X6532 2026-08-30); `'implicit'` is the plain single-value form.
 */
function keyDescription({ level = 1, challenge, origin = 0, purposes = [2], purposeEncoding = 'explicit-set',
  applicationIdDer = null, rootOfTrustDer = null }) {
  const purposeTag = purposeEncoding === 'explicit-set'
    ? ctx(1, true, set(...purposes.map(int)))
    : ctx(1, false, Buffer.from([purposes[0]]));
  const originTag = ctx(702, true, int(origin));
  const softwareFields = [purposeTag, originTag];
  // [709] EXPLICIT OCTET STRING wrapping the AttestationApplicationId DER.
  if (applicationIdDer) softwareFields.push(ctx(709, true, octetString(applicationIdDer)));
  // [704] EXPLICIT RootOfTrust SEQUENCE.
  if (rootOfTrustDer) softwareFields.push(ctx(704, true, rootOfTrustDer));
  return seq(
    int(3), // attestationVersion
    enumerated(level), // attestationSecurityLevel
    int(3), // keymasterVersion
    enumerated(1), // keymasterSecurityLevel
    octetString(challenge),
    octetString(Buffer.alloc(0)), // uniqueId
    seq(...softwareFields), // softwareEnforced
    seq(), // teeEnforced
  );
}

/**
 * Builds a DER certificate. When `keyDescription` is provided it is attached under the
 * Android attestation OID; `extraExtensions` = [{ oid, valueDer }] attaches arbitrary
 * extensions (RKP provisioning info, synthetic tests). `ca: true` adds basicConstraints.
 */
function buildCert({ subjectCn, issuerCn, publicKey, signingKey, notBefore, notAfter, ca = false,
  keyDescription: kd, extraExtensions = [] }) {
  const extensions = [];
  if (ca) {
    extensions.push(seq(oid('2.5.29.19'), boolean(true), octetString(seq(boolean(true)))));
  }
  if (kd) {
    extensions.push(seq(oid('1.3.6.1.4.1.11129.2.1.17'), octetString(kd)));
  }
  for (const extension of extraExtensions) {
    extensions.push(seq(oid(extension.oid), octetString(extension.valueDer)));
  }
  const tbsParts = [
    ctx(0, true, int(2)), // version v3
    int((Date.now() % 0x7fffff) + 1), // serialNumber
    SIG_ALG_ECDSA_SHA256,
    nameOf(issuerCn),
    seq(utcTime(notBefore), utcTime(notAfter)),
    nameOf(subjectCn),
    publicKey.export({ type: 'spki', format: 'der' }),
  ];
  if (extensions.length > 0) tbsParts.push(ctx(3, true, seq(...extensions)));
  const tbs = seq(...tbsParts);
  const signature = crypto.sign('sha256', tbs, signingKey);
  return tlv([0x30], Buffer.concat([tbs, SIG_ALG_ECDSA_SHA256, bitString(signature)]));
}

function newKey() {
  return crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
}

/** Provisioning information extension value (never parsed by the verifier, only detected). */
function provisioningInfo(challenge = crypto.randomBytes(32)) {
  return seq(octetString(challenge), int(Date.now()));
}

/**
 * Standard synthetic PKI: returns DER buffers + keys. Leaf carries a KeyDescription for
 * `challenge` unless `leafKeyDescription` is false. Chain = [leaf, int], anchor = root.
 */
function makePki({ leafChallenge = crypto.randomBytes(32), leafKeyDescription = true, level = 1,
  origin = 0, purposes = [2], purposeEncoding = 'explicit-set', caIntermediate = true,
  leafNotBefore = new Date(Date.now() - 86400000), leafNotAfter = new Date(Date.now() + 86400000),
  applicationIdDer = null, rootOfTrustDer = null, leafExtraExtensions = [],
  intExtraExtensions = [] } = {}) {
  const rootKeys = newKey();
  const intKeys = newKey();
  const leafKeys = newKey();
  const root = buildCert({
    subjectCn: 'Test Attestation Root', issuerCn: 'Test Attestation Root',
    publicKey: rootKeys.publicKey, signingKey: rootKeys.privateKey,
    notBefore: new Date(Date.now() - 86400000), notAfter: new Date(Date.now() + 31536000000), ca: true,
  });
  const int = buildCert({
    subjectCn: 'Test Device Issuer', issuerCn: 'Test Attestation Root',
    publicKey: intKeys.publicKey, signingKey: rootKeys.privateKey,
    notBefore: new Date(Date.now() - 86400000), notAfter: new Date(Date.now() + 31536000000),
    ca: caIntermediate, extraExtensions: intExtraExtensions,
  });
  const leaf = buildCert({
    subjectCn: 'Test Device Key', issuerCn: 'Test Device Issuer',
    publicKey: leafKeys.publicKey, signingKey: intKeys.privateKey,
    notBefore: leafNotBefore, notAfter: leafNotAfter,
    extraExtensions: leafExtraExtensions,
    keyDescription: leafKeyDescription
      ? keyDescription({
        level, challenge: leafChallenge, origin, purposes, purposeEncoding,
        applicationIdDer, rootOfTrustDer,
      })
      : null,
  });
  return {
    root, int, leaf,
    rootCert: new crypto.X509Certificate(root),
    leafKeys,
    proof: {
      signingPublicKey: leafKeys.publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
      attestationChain: [leaf, int].map((d) => d.toString('base64')),
      attestationChallenge: leafChallenge.toString('base64'),
    },
  };
}

module.exports = {
  makePki, keyDescription, buildCert, newKey,
  applicationId, rootOfTrust, provisioningInfo,
};

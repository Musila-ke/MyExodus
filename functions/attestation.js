'use strict';
// Server-side Android Key Attestation verification (pairing plan §19.4 item 2, owner directive
// 2026-09-28; upgraded for the partner event-delivery plan §8.3.5 / §10.4 on 2026-11-11).
// PKIX to the pinned Google attestation roots, KeyDescription (origin GENERATED, purpose SIGN,
// securityLevel >= TEE), challenge equality — plus the partner-delivery bindings:
//   - RKP-aware selection: the attestation extension used is the first occurrence found
//     walking from the trust anchor toward the leaf, never blindly the leaf. When a
//     provisioning-information extension (OID 1.3.6.1.4.1.11129.2.1.30) is present, the
//     attestation extension must be in the certificate immediately toward the leaf of it.
//     Later (leaf-ward) attacker-appended extensions are ignored.
//   - revocation status from the official status list,
//   - application binding (package + APK signing-certificate digest),
//   - verified-boot state.
//   - the attested key must equal the proof's `signingPublicKey` (the pairing path also binds
//     the attestation to the session through the transcript signature).
// Pure node:crypto — no firebase imports, so this module is unit-testable without the
// Functions SDK and without a Firestore emulator (same pattern as proof.js).

const crypto = require('node:crypto');

const KEY_DESCRIPTION_OID = '1.3.6.1.4.1.11129.2.1.17';
const PROVISIONING_INFO_OID = '1.3.6.1.4.1.11129.2.1.30';
const ORIGIN_GENERATED = 0;
const PURPOSE_SIGN = 2;

const SECURITY_LEVEL_SOFTWARE = 0;
const SECURITY_LEVEL_TEE = 1;
const SECURITY_LEVEL_STRONGBOX = 2;

// KeyDescription AuthorizationList tags (§8.3.5).
const APPLICATION_ID_TAG = 709;
const ROOT_OF_TRUST_TAG = 704;
const VERIFIED_BOOT_STATE_VERIFIED = 0;

// Android attestation chains are leaf -> [intermediates] -> root. RKP chains are longer than
// the classic root->intermediate->leaf shape, so the cap is 8. The cap bounds per-call
// parsing cost for a hostile caller.
const MAX_CHAIN_LENGTH = 8;

const REVOCATION_STATUS_URL = 'https://android.googleapis.com/attestation/status';
const REVOCATION_CACHE_MS = 60 * 60 * 1000;

// --- minimal DER reader (certificate walk + KeyDescription) ---

const TAG_BOOLEAN = 1;
const TAG_SEQUENCE = 16;
const TAG_SET = 17;
const TAG_INTEGER = 2;
const TAG_OCTET_STRING = 4;
const TAG_ENUMERATED = 10;
const TAG_OID = 6;
const CLASS_CONTEXT = 2;

/** Reads one TLV at `pos`; returns null on anything malformed. Bounds are explicit. */
function readTlv(buf, pos) {
  if (pos + 2 > buf.length) return null;
  const first = buf[pos];
  const cls = first >> 6;
  const constructed = (first & 0x20) !== 0;
  let tagNo = first & 0x1f;
  let i = pos + 1;
  if (tagNo === 0x1f) {
    tagNo = 0;
    let more = true;
    while (more) {
      if (i >= buf.length) return null;
      more = (buf[i] & 0x80) !== 0;
      tagNo = tagNo * 128 + (buf[i] & 0x7f);
      i += 1;
      if (tagNo > 0xfffff) return null;
    }
  }
  if (i >= buf.length) return null;
  let len = buf[i];
  i += 1;
  if (len & 0x80) {
    const n = len & 0x7f;
    if (n === 0 || n > 4) return null; // indefinite length or absurd
    if (i + n > buf.length) return null;
    len = 0;
    for (let j = 0; j < n; j++) len = len * 256 + buf[i + j];
    i += n;
  }
  if (i + len > buf.length) return null;
  return { cls, constructed, tagNo, pos, start: i, end: i + len };
}

/** Every TLV in [start, end), or null if the region doesn't parse exactly. */
function children(buf, start, end) {
  const out = [];
  let pos = start;
  while (pos < end) {
    const tlv = readTlv(buf, pos);
    if (!tlv || tlv.end > end) return null;
    out.push(tlv);
    pos = tlv.end;
  }
  return pos === end ? out : null;
}

/** Unsigned big-endian integer from a content region (DER INTEGERs here are small/positive). */
function intFromContent(buf, start, end) {
  let v = 0;
  for (let i = start; i < end; i++) v = v * 256 + buf[i];
  return v;
}

function oidToString(buf, start, end) {
  if (end <= start) return null;
  const first = buf[start];
  const parts = [Math.floor(first / 40), first % 40];
  let v = 0;
  for (let i = start + 1; i < end; i++) {
    v = v * 128 + (buf[i] & 0x7f);
    if ((buf[i] & 0x80) === 0) {
      parts.push(v);
      v = 0;
    }
  }
  return parts.join('.');
}

/**
 * Returns the extnValue contents of the first extension with `oid` in a certificate's DER,
 * or null. Walks TBSCertificate -> [3] extensions -> Extension.
 */
function extensionValueOf(certDer, oid) {
  const cert = readTlv(certDer, 0);
  if (!cert || cert.cls !== 0 || cert.tagNo !== TAG_SEQUENCE) return null;
  const certKids = children(certDer, cert.start, cert.end);
  if (!certKids || certKids.length < 1) return null;
  const tbs = certKids[0];
  if (tbs.cls !== 0 || tbs.tagNo !== TAG_SEQUENCE) return null;
  const tbsKids = children(certDer, tbs.start, tbs.end);
  if (!tbsKids) return null;

  const wrapped = tbsKids.find((k) => k.cls === CLASS_CONTEXT && k.tagNo === 3);
  if (!wrapped) return null;
  // [3] EXPLICIT SEQUENCE OF Extension; tolerate wrappers that inline the list directly.
  let extList = children(certDer, wrapped.start, wrapped.end);
  if (!extList || extList.length === 0) return null;
  if (extList.length === 1 && extList[0].tagNo === TAG_SEQUENCE && extList[0].cls === 0) {
    extList = children(certDer, extList[0].start, extList[0].end) || [];
  }

  for (const ext of extList) {
    const kids = children(certDer, ext.start, ext.end);
    if (!kids || kids.length < 2) continue;
    const id = kids[0];
    if (id.cls !== 0 || id.tagNo !== TAG_OID) continue;
    if (oidToString(certDer, id.start, id.end) !== oid) continue;
    const value = kids.find((k) => k.cls === 0 && k.tagNo === TAG_OCTET_STRING);
    return value ? certDer.subarray(value.start, value.end) : null;
  }
  return null;
}

/** First tagged integer with `tagNo` found in either AuthorizationList (software, tee). */
function taggedIntInLists(buf, lists, tagNo) {
  for (const list of lists) {
    if (!list || list.cls !== 0 || list.tagNo !== TAG_SEQUENCE) continue;
    const kids = children(buf, list.start, list.end);
    if (!kids) continue;
    for (const kid of kids) {
      if (kid.cls !== CLASS_CONTEXT || kid.tagNo !== tagNo) continue;
      if (kid.constructed) {
        // EXPLICIT: content wraps the value TLV.
        const inner = children(buf, kid.start, kid.end);
        if (inner && inner.length === 1) return intFromContent(buf, inner[0].start, inner[0].end);
      }
      // IMPLICIT: content is the raw value bytes.
      return intFromContent(buf, kid.start, kid.end);
    }
  }
  return null;
}

/**
 * Raw value of an EXPLICIT-or-IMPLICIT tagged field. For a wrapped OCTET STRING (the
 * application-id shape) returns the string's contents; for a wrapped constructed value
 * (rootOfTrust's SEQUENCE) returns the full inner TLV.
 */
function taggedRawInLists(buf, lists, tagNo) {
  for (const list of lists) {
    if (!list || list.cls !== 0 || list.tagNo !== TAG_SEQUENCE) continue;
    const kids = children(buf, list.start, list.end);
    if (!kids) continue;
    for (const kid of kids) {
      if (kid.cls !== CLASS_CONTEXT || kid.tagNo !== tagNo) continue;
      if (!kid.constructed) return buf.subarray(kid.start, kid.end);
      const inner = children(buf, kid.start, kid.end);
      if (!inner || inner.length !== 1) return null;
      const value = inner[0];
      if (value.cls === 0 && value.tagNo === TAG_OCTET_STRING) {
        return buf.subarray(value.start, value.end);
      }
      return buf.subarray(value.pos, value.end);
    }
  }
  return null;
}

/**
 * Purpose values (tag [1]) in either list. Keymaster emits `[1] EXPLICIT SET OF INTEGER`
 * (caught on the X6532 2026-08-30 — the encoding that crashed the first Kotlin parser), so
 * the content may be a SET/SEQUENCE of INTEGERs or a bare INTEGER; accept both.
 */
function purposesInLists(buf, lists) {
  const values = [];
  for (const list of lists) {
    if (!list || list.cls !== 0 || list.tagNo !== TAG_SEQUENCE) continue;
    const kids = children(buf, list.start, list.end);
    if (!kids) continue;
    for (const kid of kids) {
      if (kid.cls !== CLASS_CONTEXT || kid.tagNo !== 1) continue;
      if (!kid.constructed) {
        values.push(intFromContent(buf, kid.start, kid.end));
        continue;
      }
      const inner = children(buf, kid.start, kid.end);
      if (!inner) continue;
      for (const item of inner) {
        if (item.cls === 0 && (item.tagNo === TAG_SET || item.tagNo === TAG_SEQUENCE)) {
          const setKids = children(buf, item.start, item.end) || [];
          for (const v of setKids) {
            if (v.cls === 0 && v.tagNo === TAG_INTEGER) values.push(intFromContent(buf, v.start, v.end));
          }
        } else if (item.cls === 0 && item.tagNo === TAG_INTEGER) {
          values.push(intFromContent(buf, item.start, item.end));
        }
      }
    }
  }
  return values;
}

/** KeyDescription fields the verifier needs, or null when absent/malformed. */
function parseKeyDescription(der) {
  const seq = readTlv(der, 0);
  if (!seq || seq.cls !== 0 || seq.tagNo !== TAG_SEQUENCE) return null;
  const items = children(der, seq.start, seq.end);
  if (!items || items.length < 8) return null;
  const level = items[1];
  const challenge = items[4];
  const softwareList = items[6];
  const teeList = items[7];
  if (level.cls !== 0 || level.tagNo !== TAG_ENUMERATED) return null;
  if (challenge.cls !== 0 || challenge.tagNo !== TAG_OCTET_STRING) return null;
  const lists = [softwareList, teeList];
  return {
    securityLevel: intFromContent(der, level.start, level.end),
    challenge: der.subarray(challenge.start, challenge.end),
    origin: taggedIntInLists(der, lists, 702),
    purposes: purposesInLists(der, lists),
    applicationId: taggedRawInLists(der, lists, APPLICATION_ID_TAG),
    rootOfTrust: taggedRawInLists(der, lists, ROOT_OF_TRUST_TAG),
  };
}

/** AttestationApplicationId: SEQUENCE { SET OF packageInfo, SET OF digest }. */
function parseAttestationApplicationId(der) {
  const seq = readTlv(der, 0);
  if (!seq || seq.cls !== 0 || seq.tagNo !== TAG_SEQUENCE) return null;
  const items = children(der, seq.start, seq.end);
  if (!items || items.length < 2) return null;
  const packageSet = items[0];
  const digestSet = items[1];
  if (packageSet.cls !== 0 || packageSet.tagNo !== TAG_SET) return null;
  if (digestSet.cls !== 0 || digestSet.tagNo !== TAG_SET) return null;

  const packageNames = [];
  for (const info of children(der, packageSet.start, packageSet.end) || []) {
    if (info.cls !== 0 || info.tagNo !== TAG_SEQUENCE) continue;
    const fields = children(der, info.start, info.end);
    if (!fields || fields.length < 1) continue;
    const name = fields[0];
    if (name.cls !== 0 || name.tagNo !== TAG_OCTET_STRING) continue;
    packageNames.push(der.subarray(name.start, name.end).toString('utf8'));
  }
  const signatureDigests = [];
  for (const digest of children(der, digestSet.start, digestSet.end) || []) {
    if (digest.cls !== 0 || digest.tagNo !== TAG_OCTET_STRING) continue;
    signatureDigests.push(der.subarray(digest.start, digest.end).toString('hex'));
  }
  return { packageNames, signatureDigests };
}

/** RootOfTrust: SEQUENCE { OCTET STRING key, BOOLEAN locked, ENUMERATED state, ... }. */
function parseRootOfTrust(der) {
  const seq = readTlv(der, 0);
  if (!seq || seq.cls !== 0 || seq.tagNo !== TAG_SEQUENCE) return null;
  const items = children(der, seq.start, seq.end);
  if (!items || items.length < 3) return null;
  const locked = items[1];
  const state = items[2];
  if (locked.cls !== 0 || locked.tagNo !== TAG_BOOLEAN) return null;
  if (state.cls !== 0 || state.tagNo !== TAG_ENUMERATED) return null;
  return {
    deviceLocked: der[locked.start] !== 0,
    verifiedBootState: intFromContent(der, state.start, state.end),
  };
}

/**
 * Selects the KeyDescription nearest the trusted root (RKP rule, §8.3.5/§10.4). A
 * provisioning-information extension binds the attestation extension to the certificate
 * immediately toward the leaf of it; any other position is a forged/appended chain.
 */
function selectKeyDescription(chain) {
  let index = -1;
  let bytes = null;
  for (let i = chain.length - 1; i >= 0; i--) {
    const value = extensionValueOf(chain[i].raw, KEY_DESCRIPTION_OID);
    if (value) {
      index = i;
      bytes = value;
      break;
    }
  }
  if (index < 0) return { error: 'ATTESTATION_MISSING' };

  const provisioningIndices = [];
  for (let i = 0; i < chain.length; i++) {
    if (extensionValueOf(chain[i].raw, PROVISIONING_INFO_OID)) provisioningIndices.push(i);
  }
  if (provisioningIndices.length > 1) return { error: 'PROVISIONING_SEQUENCE_INVALID' };
  if (provisioningIndices.length === 1 && provisioningIndices[0] - 1 !== index) {
    return { error: 'PROVISIONING_SEQUENCE_INVALID' };
  }

  const desc = parseKeyDescription(bytes);
  if (!desc) return { error: 'ATTESTATION_MISSING' };
  return { index, desc };
}

// --- PKIX path validation (pinned anchors) ---

function withinValidity(cert, now) {
  const from = cert.validFromDate;
  const to = cert.validToDate;
  return from instanceof Date && to instanceof Date && now >= from.getTime() && now <= to.getTime();
}

// The factory root key is re-issued with extended validity; the expired re-issue shares both
// key and subject serial. Only this key gets the expired-top exemption.
const FACTORY_ROOT_SERIAL = 'f92009e853b6b045';

/**
 * Manual PKIX walk over the built-in X509Certificate primitives: signature linkage
 * (checkIssued + verify), issuer CA flag, and validity windows on every cert that is not
 * itself an anchor (JDK PKIX exempts anchors from expiry). `anchors` is a parameter so tests
 * can inject synthetic roots; the production default is the official Google pair.
 */
function validateChain(chain, anchors, now = Date.now()) {
  if (!Array.isArray(chain) || chain.length === 0) return false;
  for (let i = 0; i < chain.length - 1; i++) {
    if (!chain[i].checkIssued(chain[i + 1])) return false;
    if (!chain[i].verify(chain[i + 1].publicKey)) return false;
    if (!chain[i + 1].ca) return false;
    if (!withinValidity(chain[i], now)) return false;
  }
  const top = chain[chain.length - 1];
  if (anchors.some((a) => a.raw.equals(top.raw))) return true;
  const sameKeyFallback = anchors.some((a) => a.ca && top.checkIssued(a) && top.verify(a.publicKey));
  if (!sameKeyFallback) return false;
  if (withinValidity(top, now)) return true;
  return top.subject.toLowerCase().includes(FACTORY_ROOT_SERIAL);
}

// --- official Google Hardware Attestation roots (fetched from
// https://android.googleapis.com/attestation/root on 2026-11-11; the plan §8.3.5/§10.4
// requires both currently trusted roots, including the 2026 ECDSA root for RKP) ---

const GOOGLE_ATTESTATION_ROOT_PEMS = [
  // RSA root, serial F1C172A699EAF51D, subject f92009e853b6b045, valid 2022-03-20..2042-03-15.
  `-----BEGIN CERTIFICATE-----
MIIFHDCCAwSgAwIBAgIJAPHBcqaZ6vUdMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNV
BAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMjIwMzIwMTgwNzQ4WhcNNDIwMzE1MTgw
NzQ4WjAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0B
AQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdS
Sxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7
tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggj
nar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGq
C4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQ
oVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+O
JtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/Eg
sTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRi
igHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+M
RPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9E
aDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5Um
AGMCAwEAAaNjMGEwHQYDVR0OBBYEFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMB8GA1Ud
IwQYMBaAFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMA8GA1UdEwEB/wQFMAMBAf8wDgYD
VR0PAQH/BAQDAgIEMA0GCSqGSIb3DQEBCwUAA4ICAQB8cMqTllHc8U+qCrOlg3H7
174lmaCsbo/bJ0C17JEgMLb4kvrqsXZs01U3mB/qABg/1t5Pd5AORHARs1hhqGIC
W/nKMav574f9rZN4PC2ZlufGXb7sIdJpGiO9ctRhiLuYuly10JccUZGEHpHSYM2G
tkgYbZba6lsCPYAAP83cyDV+1aOkTf1RCp/lM0PKvmxYN10RYsK631jrleGdcdkx
oSK//mSQbgcWnmAEZrzHoF1/0gso1HZgIn0YLzVhLSA/iXCX4QT2h3J5z3znluKG
1nv8NQdxei2DIIhASWfu804CA96cQKTTlaae2fweqXjdN1/v2nqOhngNyz1361mF
mr4XmaKH/ItTwOe72NI9ZcwS1lVaCvsIkTDCEXdm9rCNPAY10iTunIHFXRh+7KPz
lHGewCq/8TOohBRn0/NNfh7uRslOSZ/xKbN9tMBtw37Z8d2vvnXq/YWdsm1+JLVw
n6yYD/yacNJBlwpddla8eaVMjsF6nBnIgQOf9zKSe06nSTqvgwUHosgOECZJZ1Eu
zbH4yswbt02tKtKEFhx+v+OTge/06V+jGsqTWLsfrOCNLuA8H++z+pUENmpqnnHo
vaI47gC+TNpkgYGkkBT6B/m/U01BuOBBTzhIlMEZq9qkDWuM2cA5kW5V3FJUcfHn
w1IdYIg2Wxg7yHcQZemFQg==
-----END CERTIFICATE-----`,
  // ECDSA root for Remote Key Provisioning, serial 84A9D0297B0EB58AE7FF0E80DE760605,
  // CN=Key Attestation CA1, valid 2025-07-17..2035-07-15.
  `-----BEGIN CERTIFICATE-----
MIICIjCCAaigAwIBAgIRAISp0Cl7DrWK5/8OgN52BgUwCgYIKoZIzj0EAwMwUjEc
MBoGA1UEAwwTS2V5IEF0dGVzdGF0aW9uIENBMTEQMA4GA1UECwwHQW5kcm9pZDET
MBEGA1UECgwKR29vZ2xlIExMQzELMAkGA1UEBhMCVVMwHhcNMjUwNzE3MjIzMjE4
WhcNMzUwNzE1MjIzMjE4WjBSMRwwGgYDVQQDDBNLZXkgQXR0ZXN0YXRpb24gQ0Ex
MRAwDgYDVQQLDAdBbmRyb2lkMRMwEQYDVQQKDApHb29nbGUgTExDMQswCQYDVQQG
EwJVUzB2MBAGByqGSM49AgEGBSuBBAAiA2IABCPaI3FO3z5bBQo8cuiEas4HjqCt
G/mLFfRT0MsIssPBEEU5Cfbt6sH5yOAxqEi5QagpU1yX4HwnGb7OtBYpDTB57uH5
Eczm34A5FNijV3s0/f0UPl7zbJcTx6xwqMIRq6NCMEAwDwYDVR0TAQH/BAUwAwEB
/zAOBgNVHQ8BAf8EBAMCAQYwHQYDVR0OBBYEFFIyuyz7RkOb3NaBqQ5lZuA0QepA
MAoGCCqGSM49BAMDA2gAMGUCMETfjPO/HwqReR2CS7p0ZWoD/LHs6hDi422opifH
EUaYLxwGlT9SLdjkVpz0UUOR5wIxAIoGyxGKRHVTpqpGRFiJtQEOOTp/+s1GcxeY
uR2zh/80lQyu9vAFCj6E4AXc+osmRg==
-----END CERTIFICATE-----`,
];

let cachedAnchors = null;

function trustAnchors() {
  if (!cachedAnchors) {
    cachedAnchors = GOOGLE_ATTESTATION_ROOT_PEMS.map((pem) => new crypto.X509Certificate(pem));
  }
  return cachedAnchors;
}

/**
 * Official revocation status list, cached for an hour. A network failure serves the last
 * cached list when one exists; with no cache the caller must fail closed (retry later).
 */
let revocationCache = null;

async function fetchRevocationEntries({ fetchImpl = globalThis.fetch, now = Date.now(), useCache = true } = {}) {
  if (useCache && revocationCache && now - revocationCache.fetchedAt < REVOCATION_CACHE_MS) {
    return revocationCache.entries;
  }
  try {
    const response = await fetchImpl(REVOCATION_STATUS_URL, {
      headers: { accept: 'application/json' },
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) throw new Error(`revocation status HTTP ${response.status}`);
    const body = await response.json();
    const entries = {};
    for (const [serial, entry] of Object.entries((body && body.entries) || {})) {
      if (!entry || typeof entry.status !== 'string') continue;
      entries[serial.toLowerCase()] = {
        status: entry.status,
        expires: typeof entry.expires === 'string' ? entry.expires : null,
      };
    }
    revocationCache = { entries, fetchedAt: now };
    return entries;
  } catch (error) {
    if (useCache && revocationCache) return revocationCache.entries;
    throw error;
  }
}

function isRevoked(entries, serial, now) {
  const entry = entries[serial.toLowerCase()];
  if (!entry) return false;
  if (entry.expires && Date.parse(entry.expires) <= now) return false;
  return entry.status === 'REVOKED' || entry.status === 'SUSPENDED';
}

/**
 * Verifies one proof's attestation evidence. Returns { ok: true, securityLevel } or
 * { ok: false, reason } — stable codes, never echoed payload content (plan §10).
 * `anchors` is a test seam; production callers omit it (pinned Google roots).
 * `options.applicationId` = { packageName, signatureDigestSha256 }; when given, the attested
 * application must match. `options.requireVerifiedBoot` demands a Verified boot state.
 * `options.revocationEntries` = lowercase-serial -> { status, expires }.
 */
function verifyAttestation(proof, anchors = trustAnchors(), now = Date.now(), options = {}) {
  if (proof === null || typeof proof !== 'object') return { ok: false, reason: 'MALFORMED' };
  const { attestationChain, attestationChallenge, signingPublicKey } = proof;
  if (!Array.isArray(attestationChain) || attestationChain.length === 0 ||
      attestationChain.length > MAX_CHAIN_LENGTH) {
    return { ok: false, reason: 'MALFORMED' };
  }
  if (typeof attestationChallenge !== 'string' || attestationChallenge.length === 0 ||
      typeof signingPublicKey !== 'string' || signingPublicKey.length === 0) {
    return { ok: false, reason: 'MALFORMED' };
  }

  const chain = [];
  for (const derB64 of attestationChain) {
    if (typeof derB64 !== 'string' || derB64.length === 0) return { ok: false, reason: 'MALFORMED' };
    try {
      chain.push(new crypto.X509Certificate(Buffer.from(derB64, 'base64')));
    } catch (e) {
      return { ok: false, reason: 'MALFORMED' };
    }
  }

  const selection = selectKeyDescription(chain);
  if (selection.error) return { ok: false, reason: selection.error };
  const { index, desc } = selection;
  if (desc.securityLevel === SECURITY_LEVEL_SOFTWARE) return { ok: false, reason: 'KEY_SOFTWARE' };
  if (desc.securityLevel !== SECURITY_LEVEL_TEE && desc.securityLevel !== SECURITY_LEVEL_STRONGBOX) {
    return { ok: false, reason: 'KEY_LEVEL_UNKNOWN' };
  }

  const expectedChallenge = Buffer.from(attestationChallenge, 'base64');
  if (!desc.challenge.equals(expectedChallenge)) return { ok: false, reason: 'CHALLENGE_MISMATCH' };
  if (desc.origin !== ORIGIN_GENERATED) return { ok: false, reason: 'ORIGIN_NOT_GENERATED' };
  if (!desc.purposes.includes(PURPOSE_SIGN)) return { ok: false, reason: 'PURPOSE_MISSING_SIGN' };

  if (options.applicationId) {
    if (!desc.applicationId) return { ok: false, reason: 'APPLICATION_ID_MISSING' };
    const application = parseAttestationApplicationId(desc.applicationId);
    if (!application) return { ok: false, reason: 'APPLICATION_ID_MISSING' };
    const expectedDigest = String(options.applicationId.signatureDigestSha256 || '').toLowerCase();
    if (!application.packageNames.includes(options.applicationId.packageName) ||
        !application.signatureDigests.includes(expectedDigest)) {
      return { ok: false, reason: 'APPLICATION_ID_MISMATCH' };
    }
  }

  if (options.requireVerifiedBoot) {
    const rootOfTrust = desc.rootOfTrust ? parseRootOfTrust(desc.rootOfTrust) : null;
    if (!rootOfTrust) return { ok: false, reason: 'ROOT_OF_TRUST_MISSING' };
    if (rootOfTrust.verifiedBootState !== VERIFIED_BOOT_STATE_VERIFIED) {
      return { ok: false, reason: 'VERIFIED_BOOT_STATE_INVALID' };
    }
  }

  // The attested key IS the proof's signing key.
  let attestedSpki;
  try {
    attestedSpki = chain[index].publicKey.export({ type: 'spki', format: 'der' });
  } catch (e) {
    return { ok: false, reason: 'MALFORMED' };
  }
  if (!attestedSpki.equals(Buffer.from(signingPublicKey, 'base64'))) {
    return { ok: false, reason: 'KEY_CHAIN_MISMATCH' };
  }

  if (options.revocationEntries) {
    for (const cert of chain) {
      if (isRevoked(options.revocationEntries, cert.serialNumber, now)) {
        return { ok: false, reason: 'CERTIFICATE_REVOKED' };
      }
    }
  }

  if (!validateChain(chain, anchors, now)) return { ok: false, reason: 'CHAIN_UNTRUSTED' };
  return { ok: true, securityLevel: desc.securityLevel };
}

module.exports = {
  KEY_DESCRIPTION_OID,
  PROVISIONING_INFO_OID,
  MAX_CHAIN_LENGTH,
  SECURITY_LEVEL_TEE,
  SECURITY_LEVEL_STRONGBOX,
  trustAnchors,
  validateChain,
  verifyAttestation,
  fetchRevocationEntries,
};

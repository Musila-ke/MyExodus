'use strict';
// Server-side Android Key Attestation verification (pairing plan §19.4 item 2, owner directive
// 2026-09-28 — overrides the build's earlier device-to-device-only ruling). Port of the client
// verifier (`AttestationVerifier.kt`): PKIX to the pinned Google attestation roots, KeyDescription
// (origin GENERATED, purpose SIGN, securityLevel >= TEE), challenge equality — plus two
// server-specific bindings the client gets structurally:
//   - the chain's LEAF KEY must equal the proof's `signingPublicKey` (the client uses
//     chain[0].publicKey as the key that must verify the transcript signature; here the
//     same identity is asserted byte-for-byte against the proof field), and
//   - because the attestation rides inside the registerPairing proof — a signature over the
//     session transcript (sessionId + both keys) — the attestation evidence is bound to the
//     pairing session through the proof signature. Stated precisely: a long-term signing key
//     is generated once at first launch (before any session exists), so no fresh server
//     challenge can be embedded in it; challenge equality proves the presented challenge
//     matches the cert's KeyDescription, and the session binding is the proof signature.
// Pure node:crypto — no firebase imports, so this module is unit-testable without the
// Functions SDK and without a Firestore emulator (same pattern as proof.js).

const crypto = require('node:crypto');

const KEY_DESCRIPTION_OID = '1.3.6.1.4.1.11129.2.1.17';
const ORIGIN_GENERATED = 0;
const PURPOSE_SIGN = 2;

const SECURITY_LEVEL_SOFTWARE = 0;
const SECURITY_LEVEL_TEE = 1;
const SECURITY_LEVEL_STRONGBOX = 2;

// Android key attestation chains are leaf -> [intermediates] -> root; 3 is the common shape.
// The cap bounds per-call parsing cost for a hostile caller.
const MAX_CHAIN_LENGTH = 4;

// --- minimal DER reader (certificate walk + KeyDescription) ---

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
  return { cls, constructed, tagNo, start: i, end: i + len };
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
 * Extracts the KeyDescription extension bytes from a certificate's DER: walks
 * TBSCertificate -> [3] extensions -> Extension whose extnID is the attestation OID, and
 * returns the extnValue OCTET STRING's contents (= the DER KeyDescription itself).
 */
function keyDescriptionBytesOf(certDer) {
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
    const oid = kids[0];
    if (oid.cls !== 0 || oid.tagNo !== TAG_OID) continue;
    if (oidToString(certDer, oid.start, oid.end) !== KEY_DESCRIPTION_OID) continue;
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
  return {
    securityLevel: intFromContent(der, level.start, level.end),
    challenge: der.subarray(challenge.start, challenge.end),
    origin: taggedIntInLists(der, [softwareList, teeList], 702),
    purposes: purposesInLists(der, [softwareList, teeList]),
  };
}

// --- PKIX path validation (pinned anchors) ---

function withinValidity(cert, now) {
  const from = cert.validFromDate;
  const to = cert.validToDate;
  return from instanceof Date && to instanceof Date && now >= from.getTime() && now <= to.getTime();
}

/**
 * Manual PKIX walk over the built-in X509Certificate primitives: signature linkage
 * (checkIssued + verify), issuer CA flag, and validity windows on every cert that is not
 * itself an anchor (JDK PKIX exempts anchors from expiry — Root 1 expired 2026-05-24 and
 * must keep validating). `anchors` is a parameter so tests can inject synthetic roots; the
 * production default is the pinned Google pair.
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
  if (!withinValidity(top, now)) return false;
  return anchors.some((a) => a.ca && top.checkIssued(a) && top.verify(a.publicKey));
}

// --- pinned Google Hardware Attestation roots (byte-identical to the client's pins;
// AttestationVerifierTest pins the same serials on the Kotlin side) ---

const GOOGLE_ATTESTATION_ROOT_PEMS = [
  `-----BEGIN CERTIFICATE-----
MIIFYDCCA0igAwIBAgIJAOj6GWMU0voYMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNVBAUTEGY5MjAw
OWU4NTNiNmIwNDUwHhcNMTYwNTI2MTYyODUyWhcNMjYwNTI0MTYyODUyWjAbMRkwFwYDVQQFExBm
OTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHs
K7Qui8xUFmOr75gvMsd/dTEDDJdSSxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfd
nJLmN0pTy/4lj4/7tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL
/ggjnar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGqC4FSYa04
T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQoVJYnFPlXTcHYvASLu+R
hhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+OJtvsBslHZvPBKCOdT0MS+tgSOIfga+z1
Z1g7+DVagf7quvmag8jfPioyKvxnK/EgsTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgp
Zrt3i5MIlCaY504LzSRiigHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6
tUXHI/+MRPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9EaDK8
Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5UmAGMCAwEAAaOBpjCB
ozAdBgNVHQ4EFgQUNmHhAHyIBQlRi0RsR/8aTMnqTxIwHwYDVR0jBBgwFoAUNmHhAHyIBQlRi0Rs
R/8aTMnqTxIwDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAYYwQAYDVR0fBDkwNzA1oDOg
MYYvaHR0cHM6Ly9hbmRyb2lkLmdvb2dsZWFwaXMuY29tL2F0dGVzdGF0aW9uL2NybC8wDQYJKoZI
hvcNAQELBQADggIBACDIw41L3KlXG0aMiS//cqrG+EShHUGo8HNsw30W1kJtjn6UBwRM6jnmiwfB
Pb8VA91chb2vssAtX2zbTvqBJ9+LBPGCdw/E53Rbf86qhxKaiAHOjpvAy5Y3m00mqC0w/Zwvju1t
wb4vhLaJ5NkUJYsUS7rmJKHHBnETLi8GFqiEsqTWpG/6ibYCv7rYDBJDcR9W62BW9jfIoBQcxUCU
JouMPH25lLNcDc1ssqvC2v7iUgI9LeoM1sNovqPmQUiG9rHli1vXxzCyaMTjwftkJLkf6724DFhu
Kug2jITV0QkXvaJWF4nUaHOTNA4uJU9WDvZLI1j83A+/xnAJUucIv/zGJ1AMH2boHqF8CY16LpsY
gBt6tKxxWH00XcyDCdW2KlBCeqbQPcsFmWyWugxdcekhYsAWyoSf818NUsZdBWBaR/OukXrNLfkQ
79IyZohZbvabO/X+MVT3rriAoKc8oE2Uws6DF+60PV7/WIPjNvXySdqspImSN78mflxDqwLqRBYk
A3I75qppLGG9rp7UCdRjxMl8ZDBld+7yvHVgt1cVzJx9xnyGCC23UaicMDSXYrB4I4WHXPGjxhZu
CuPBLTdOLU8YRvMYdEvYebWHMpvwGCF6bAx3JBpIeOQ1wDB5y0USicV3YgYGmi+NZfhA4URSh77Y
d6uuJOJENRaNVTzk
-----END CERTIFICATE-----`,
  `-----BEGIN CERTIFICATE-----
MIIFXzCCA0egAwIBAgIINQWgov3MUeQwDQYJKoZIhvcNAQELBQAwGzEZMBcGA1UEBRMQZTM1ZDM4
YzY4OTdkNDdlODAeFw0xODAzMjEwMzU1MDFaFw0yODAzMTgwMzU1MDFaMBsxGTAXBgNVBAUTEGUz
NWQzOGM2ODk3ZDQ3ZTgwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQCoQi070/6PH9BA
uJiBcTp8j5R2/Fj6kXFaSxsvUjJKRdi/FCOwUFBJfyhHiWJhga2iguIjAJuhZx5XlMj0pSY7buJi
sPqFknZhKdvfoi4C54j54D+XxCky1APVjD5uc203H+hrRlhh6x4/LTzSXWvb0YLjfOK07HvSddSR
CyKnPydI5bhyCb5QMtVKHzC4Axgx+BihwG1B4UQjOpZpXBHIFZ6EK/XBFeJX0tgrx0MCczkc1X0O
hNFKAYumCKcKyh4q2cGh7UwTRJIT4beIJVrOKDVwo3Fc50k3ICpOAc1zRGzRwupIKKGtW455KW1O
IyAtTJ4NqfIwrkQy5EJ9w/zDzjqFiDNdgTXaMtsz52jHUndfO3lzfvVdBjt2FWLRkWrFmd1tjQ6L
QQFPrSvUHo0XN5kIiOYdNlAyIwBZ51Zn/clU9he78UYAmXHdjfh04DXWgf+GGIFFdmg9tbiHwdqi
8yEcakKHL2TYNylmFJuKOkvLIS5iqEMu29OQyv+BJ1NNQtyn8o1D5f3K2a5qyoUcrL0Je2w28ByL
1JeFURO7wbzkXx9FWwH0stCs/dYq4JKJ8GxKN8aBOl/51atA1cdZAyI55B9ueAYwPevteORGDhEf
cKRaGiWJpSzIFLDmbL9uSpCAw5uqx5h1IRAX1OLi56EsVaFUedt8IqUaCkUcMQIDAQABo4GmMIGj
MB0GA1UdDgQWBBRTDkUbExXTrVzqjui8W7YUis0d6DAfBgNVHSMEGDAWgBRTDkUbExXTrVzqjui8
W7YUis0d6DAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwICBDBABgNVHR8EOTA3MDWgM6Ax
hi9odHRwczovL2FuZHJvaWQuZ29vZ2xlYXBpcy5jb20vYXR0ZXN0YXRpb24vY3JsLzANBgkqhkiG
9w0BAQsFAAOCAgEACHPElcXZEY6jItn3SnVn6gdOCvuFIrhB4J4fhxa6OlDCbxsylHHAbpyoOSJD
0R5dIBTRvz49ElfCpI9yp4l5tUOjWj2K3tMfQjhKN277jApdxbZ6MNac2u+Z3dwS+34YiiCJLVKh
VzWanXk/+9rmVnBzm7qvgan90j2SmH1oYIA3GowJL+1OWSjj6cBH1VrU7guVd7q+aCEQPhTesfbm
UkdUbM/TjUMyhf5SZ7WB8A28MnyYQcFttmGDsE1HZx4fTz8iXxjaph33alasmFUWGtG7KyzvbYUw
wUOmpPd32eT8ocx3B0Z3g+Vo2Cv2LzRRNp15FOf/Ag5HWloyIv3xrsBA2fa4xzOm3/t1Aq0ZViXL
LerKKmU/EJKo7t5gE0wtNebicNhUJaX2C+vShjfleBBwhs33a8b7dBFX/JMwU5+09N83WHRPZjCr
YuK3Uu4qANfJ+f1k+weqh6MpRxAZ6TT0OQt1XQz7rDAlq7AopfXRXI74OCNJf8d+2CPNVW8i5IHN
FjvyEz8ms1ETdw5n3uRbYIgYuCV2Pud3MCwaMTv2E0dLAqg8uJo0JYlhH7wbqidMm2ODRL5FaqWb
b2pfW0vbpCyGfyw0RQGTJAE7xVdvzfdun9HATDm6TbJFcejBdJImfwpZmDOBtDPIbsg5+QVE3lLF
6Licq13i5tIg5yQ=
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
 * Verifies one proof's attestation evidence. Returns { ok: true, securityLevel } or
 * { ok: false, reason } — stable codes, never echoed payload content (plan §10).
 * `anchors` is a test seam; production callers omit it (pinned Google roots).
 */
function verifyAttestation(proof, anchors = trustAnchors(), now = Date.now()) {
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

  const descBytes = keyDescriptionBytesOf(chain[0].raw);
  const desc = descBytes ? parseKeyDescription(descBytes) : null;
  if (!desc) return { ok: false, reason: 'ATTESTATION_MISSING' };
  if (desc.securityLevel === SECURITY_LEVEL_SOFTWARE) return { ok: false, reason: 'KEY_SOFTWARE' };
  if (desc.securityLevel !== SECURITY_LEVEL_TEE && desc.securityLevel !== SECURITY_LEVEL_STRONGBOX) {
    return { ok: false, reason: 'KEY_LEVEL_UNKNOWN' };
  }

  const expectedChallenge = Buffer.from(attestationChallenge, 'base64');
  if (!desc.challenge.equals(expectedChallenge)) return { ok: false, reason: 'CHALLENGE_MISMATCH' };
  if (desc.origin !== ORIGIN_GENERATED) return { ok: false, reason: 'ORIGIN_NOT_GENERATED' };
  if (!desc.purposes.includes(PURPOSE_SIGN)) return { ok: false, reason: 'PURPOSE_MISSING_SIGN' };

  // The attested key IS the pairing signing key — same identity the client enforces by
  // verifying the transcript signature under chain[0].publicKey.
  let leafSpki;
  try {
    leafSpki = chain[0].publicKey.export({ type: 'spki', format: 'der' });
  } catch (e) {
    return { ok: false, reason: 'MALFORMED' };
  }
  if (!leafSpki.equals(Buffer.from(signingPublicKey, 'base64'))) {
    return { ok: false, reason: 'KEY_CHAIN_MISMATCH' };
  }

  if (!validateChain(chain, anchors, now)) return { ok: false, reason: 'CHAIN_UNTRUSTED' };
  return { ok: true, securityLevel: desc.securityLevel };
}

module.exports = {
  KEY_DESCRIPTION_OID,
  MAX_CHAIN_LENGTH,
  SECURITY_LEVEL_TEE,
  SECURITY_LEVEL_STRONGBOX,
  trustAnchors,
  validateChain,
  verifyAttestation,
};

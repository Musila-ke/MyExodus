'use strict';
// §12.2 Cloud Function / integration suite for the shipped pairing surface — the real
// `registerPairing` over the Functions emulator's HTTP wire, plus in-process handler runs
// against a real Firestore emulator (plan §12.2, §19.3/§19.4 item 5; ADR #96/#98).
//
// §19.3 supersedes the five session callables (`createPairingSession`/`joinPairingSession`/
// `revealKeys`/`confirmSas`/`submitApproval`) with `registerPairing` + the rules-mediated
// token doc, so §12.2's scenarios are mapped onto the shipped surface, not copied:
//   - full happy-path flow            → both proofs in ⇒ `active` + epoch + activatedAt
//   - concurrent conflicting writes   → A‖B submissions converge on one valid record
//   - session-creation race (same UID) → concurrent distinct requestIds: one record, both cached
//   - idempotency, corrected ordering → replay after termination returns the committed result
//   - idempotency payload mismatch    → REQUEST_ID_REUSED
//   - expiry                         → the 5-minute pending window (record) + stale half-proof
//   - invitation-token reuse / security rules → live in ../rules-test (single-use gate,
//     function-only collections); deliberately not duplicated here
//   - rate limits                    → per-UID 10/h, and a mismatched peer stays unlocked
//   - stale unpair replay            → `pairingEpoch` monotonicity across a re-pair (AD-07)
//   - duplicate approval challenge   → no such callable on the shipped surface
//   - App Check / function-level authorization / malformed input / protocol version → below
//   - "unexpected extra fields must be rejected" → recorded reading: the handler never
//     crashes on them and `requestHash` binds them (a replay with different extras is
//     REQUEST_ID_REUSED). Strict unknown-field rejection would turn any additive client
//     field into a breaking change; robustness principle applies at this boundary.
//
// Two invocation levels against the same handler code:
//   1. HTTP → the real exported `registerPairing`: App Check presence, auth, and the real
//      attestation verifier run exactly as production does (the emulator's debug mode skips
//      token *verification*, never enforcement).
//   2. In-process `run({data, auth})` through the factory seam with a stubbed attestation
//      verifier — the emulator cannot mint Google-rooted chains (ADR #96), and the verifier
//      keeps its own unit suite + the real-device leg, so what is under test here is this
//      handler's ordering, transactions and idempotency against a real Firestore.

const { test, before, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const { initializeApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { _createRegisterPairing } = require('../functions/index.js');
const { requestHashOf, IDEMPOTENCY_RETENTION_MS, PAIRING_LIMIT_MAX } = require('../functions/pairing-guards.js');
const {
  newUid, transcriptOf, makeIdentity, makeAttestedIdentity, makeProof, pairIdOf,
} = require('./helpers/proofs.js');

// §9: the two-proof window. Deliberately not exported by index.js just to serve a test —
// the seeds below age past it, so a window change can only make this suite fail loudly.
const PAIRING_WINDOW_MS = 5 * 60 * 1000;

const PROJECT = process.env.GCLOUD_PROJECT || 'demo-myexodus';
const FIRESTORE_HOST = process.env.FIRESTORE_EMULATOR_HOST;
const HUB = process.env.FIREBASE_EMULATOR_HUB || '127.0.0.1:4400';

let db;
let functionsUrl;

before(async () => {
  assert.ok(FIRESTORE_HOST, 'FIRESTORE_EMULATOR_HOST is unset — run this suite via `npm test` (firebase emulators:exec)');
  initializeApp({ projectId: PROJECT });
  db = getFirestore();

  const hub = await (await fetch(`http://${HUB}/emulators`)).json();
  assert.ok(hub.functions && hub.functions.port, `Functions emulator not registered on the hub: ${JSON.stringify(hub)}`);
  functionsUrl = `http://${hub.functions.host}:${hub.functions.port}/${PROJECT}/us-central1/registerPairing`;
});

beforeEach(async () => {
  const res = await fetch(
    `http://${FIRESTORE_HOST}/emulator/v1/projects/${PROJECT}/databases/(default)/documents`,
    { method: 'DELETE' },
  );
  assert.ok(res.ok, `Firestore clear failed: ${res.status} ${await res.text()}`);
});

// ----------------------------------------------------------------- handler level

const pairingFn = _createRegisterPairing({ attestationVerifier: () => ({ ok: true, securityLevel: 1 }) });
const call = (data, uid) => pairingFn.run({ data, auth: { uid } });

async function expectRejection(promise, code, message) {
  try {
    await promise;
  } catch (e) {
    assert.equal(e.code, code, `expected ${code}, got ${e.code}: ${e.message}`);
    if (message !== undefined) assert.equal(e.message, message);
    return e;
  }
  assert.fail(`expected rejection ${code}, call resolved`);
}

const docAt = async (path) => {
  const snap = await db.doc(path).get();
  return snap.exists ? snap.data() : null;
};

test('both proofs land the pairing active, with the server-computed pairId', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const zoe = makeIdentity(newUid('uid-zoe'));
  const transcript = transcriptOf('session-happy');

  const first = await call(makeProof(ann, { partnerUid: zoe.uid, transcriptHash: transcript }), ann.uid);
  assert.equal(first.status, 'pending');

  const second = await call(makeProof(zoe, { partnerUid: ann.uid, transcriptHash: transcript }), zoe.uid);
  assert.equal(second.status, 'active');

  // AD-02: min_max is computed server-side, so the initiator being the string-max uid proves
  // the computation rather than the submission order.
  const expectedPairId = pairIdOf(ann.uid, zoe.uid);
  assert.equal(first.pairId, expectedPairId);
  assert.equal(second.pairId, expectedPairId);

  const doc = await docAt(`pairings/${expectedPairId}`);
  assert.equal(doc.status, 'active');
  assert.equal(doc.uidA, ann.uid);
  assert.equal(doc.uidB, zoe.uid);
  assert.deepEqual(doc.members, [ann.uid, zoe.uid]);
  assert.ok(doc.createdAt, 'createdAt is stamped');
  assert.ok(doc.activatedAt, 'activatedAt is stamped on activation');
  assert.deepEqual(Object.keys(doc.proofs).sort(), [ann.uid, zoe.uid].sort());
  assert.equal(doc.pairingEpoch, second.pairingEpoch);
});

test('concurrent submissions from both sides converge on one active record', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const zoe = makeIdentity(newUid('uid-zoe'));
  const transcript = transcriptOf('session-race');

  const results = await Promise.all([
    call(makeProof(ann, { partnerUid: zoe.uid, transcriptHash: transcript }), ann.uid),
    call(makeProof(zoe, { partnerUid: ann.uid, transcriptHash: transcript }), zoe.uid),
  ]);

  assert.deepEqual(results.map((r) => r.status).sort(), ['active', 'pending']);
  assert.equal(results[0].pairId, results[1].pairId);

  const doc = await docAt(`pairings/${results[0].pairId}`);
  assert.equal(doc.status, 'active');
  assert.equal(doc.pairingEpoch, 2);
  assert.deepEqual(Object.keys(doc.proofs).sort(), [ann.uid, zoe.uid].sort());
});

test('the same requestId submitted concurrently writes one record and replays one cached result', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const zoe = makeIdentity(newUid('uid-zoe'));
  const proof = makeProof(ann, { partnerUid: zoe.uid, transcriptHash: transcriptOf('session-double') });

  const results = await Promise.all([call(proof, ann.uid), call(proof, ann.uid)]);
  assert.deepEqual(results.map((r) => r.status), ['pending', 'pending']);

  const record = await docAt(`idempotency/${proof.requestId}`);
  assert.equal(record.functionName, 'registerPairing');
  assert.equal(record.requestHash, requestHashOf(proof));

  const replay = await call(proof, ann.uid);
  assert.deepEqual(replay, record.result, 'a later replay returns the cached result verbatim');
});

test('concurrent distinct requestIds from one uid keep exactly one pairing record', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const zoe = makeIdentity(newUid('uid-zoe'));
  const transcript = transcriptOf('session-supersede');

  const [r1, r2] = await Promise.all([
    call(makeProof(ann, { partnerUid: zoe.uid, transcriptHash: transcript }), ann.uid),
    call(makeProof(ann, { partnerUid: zoe.uid, transcriptHash: transcript }), ann.uid),
  ]);
  assert.equal(r1.status, 'pending');
  assert.equal(r2.status, 'pending');

  const doc = await docAt(`pairings/${r1.pairId}`);
  assert.deepEqual(Object.keys(doc.proofs), [ann.uid], 'no duplicated proof entries');
  assert.equal(doc.pairingEpoch, 2, 'serialized transactions advance the epoch monotonically');

  const records = await db.collection('idempotency').get();
  assert.equal(records.size, 2, 'each requestId caches its own result');
});

test('idempotency replay after termination returns the committed result and writes nothing', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const zoe = makeIdentity(newUid('uid-zoe'));
  const transcript = transcriptOf('session-replay');
  const zoeProof = makeProof(zoe, { partnerUid: ann.uid, transcriptHash: transcript });

  await call(makeProof(ann, { partnerUid: zoe.uid, transcriptHash: transcript }), ann.uid);
  const committed = await call(zoeProof, zoe.uid);
  assert.equal(committed.status, 'active');

  await db.doc(`pairings/${committed.pairId}`).update({ status: 'terminated' });

  const replayed = await call(zoeProof, zoe.uid);
  assert.deepEqual(replayed, committed, '§9 corrected ordering: cached result, not a re-execution');

  const doc = await docAt(`pairings/${committed.pairId}`);
  assert.equal(doc.status, 'terminated', 'the terminated record was not resurrected');
  assert.equal(doc.pairingEpoch, committed.pairingEpoch);
});

test('re-pairing after termination advances pairingEpoch (AD-07 replay bound)', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const zoe = makeIdentity(newUid('uid-zoe'));

  const t1 = transcriptOf('session-first');
  await call(makeProof(ann, { partnerUid: zoe.uid, transcriptHash: t1 }), ann.uid);
  const firstActive = await call(makeProof(zoe, { partnerUid: ann.uid, transcriptHash: t1 }), zoe.uid);
  await db.doc(`pairings/${firstActive.pairId}`).update({ status: 'terminated' });

  const t2 = transcriptOf('session-second');
  const pending = await call(makeProof(ann, { partnerUid: zoe.uid, transcriptHash: t2 }), ann.uid);
  const secondActive = await call(makeProof(zoe, { partnerUid: ann.uid, transcriptHash: t2 }), zoe.uid);

  assert.equal(pending.status, 'pending');
  assert.equal(secondActive.status, 'active');
  assert.ok(
    secondActive.pairingEpoch > firstActive.pairingEpoch,
    `epoch must strictly increase across a re-pair (${firstActive.pairingEpoch} → ${secondActive.pairingEpoch})`,
  );
});

test('a transcript mismatch is rejected and leaves the peer free to pair elsewhere', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const zoe = makeIdentity(newUid('uid-zoe'));
  const cy = makeIdentity(newUid('uid-cy'));

  await call(makeProof(ann, { partnerUid: zoe.uid, transcriptHash: transcriptOf('session-ann') }), ann.uid);
  await expectRejection(
    call(makeProof(zoe, { partnerUid: ann.uid, transcriptHash: transcriptOf('session-zoe') }), zoe.uid),
    'failed-precondition',
    'TRANSCRIPT_MISMATCH',
  );

  const t = transcriptOf('session-zoe-cy');
  await call(makeProof(zoe, { partnerUid: cy.uid, transcriptHash: t }), zoe.uid);
  const active = await call(makeProof(cy, { partnerUid: zoe.uid, transcriptHash: t }), cy.uid);
  assert.equal(active.status, 'active', 'the mismatch never locks the innocent peer out');
});

test('two sides presenting the same signing key are refused', async () => {
  const peerUid = newUid('uid-peer');
  const sharedKeys = makeIdentity(newUid('uid-shared'));
  // Same key material under a second uid — the SAME_KEY branch compares publicKeyHash
  // across the two proof entries, so both sides must sign with one device's identity key.
  const peerWithTheSameKeys = { ...sharedKeys, uid: peerUid };
  const transcript = transcriptOf('session-same-key');

  await call(makeProof(sharedKeys, { partnerUid: peerUid, transcriptHash: transcript }), sharedKeys.uid);
  await expectRejection(
    call(makeProof(peerWithTheSameKeys, { partnerUid: sharedKeys.uid, transcriptHash: transcript }), peerUid),
    'failed-precondition',
    'SAME_KEY',
  );
});

test('a half-proof older than the pairing window is dropped, not mismatched', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const zoe = makeIdentity(newUid('uid-zoe'));
  const pairId = pairIdOf(ann.uid, zoe.uid);

  await db.doc(`pairings/${pairId}`).set({
    uidA: ann.uid,
    uidB: zoe.uid,
    members: [ann.uid, zoe.uid],
    status: 'pending',
    pairingEpoch: 1,
    proofs: {
      [ann.uid]: {
        publicKeyHash: ann.publicKeyHash,
        transcriptHash: transcriptOf('session-abandoned'),
        protocolVersion: 1,
        at: Date.now() - (PAIRING_WINDOW_MS + 60000),
      },
    },
    pendingSince: Date.now(),
  });

  const result = await call(
    makeProof(zoe, { partnerUid: ann.uid, transcriptHash: transcriptOf('session-fresh') }),
    zoe.uid,
  );
  assert.equal(result.status, 'pending');

  const doc = await docAt(`pairings/${pairId}`);
  assert.deepEqual(Object.keys(doc.proofs), [zoe.uid], 'the stale half was dropped, no mismatch raised');
  assert.equal(doc.pairingEpoch, 2);
});

test('a pending record past its window restarts from a clean slate', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const zoe = makeIdentity(newUid('uid-zoe'));
  const pairId = pairIdOf(ann.uid, zoe.uid);

  await db.doc(`pairings/${pairId}`).set({
    uidA: ann.uid,
    uidB: zoe.uid,
    members: [ann.uid, zoe.uid],
    status: 'pending',
    pairingEpoch: 4,
    proofs: {
      [ann.uid]: {
        publicKeyHash: ann.publicKeyHash,
        transcriptHash: transcriptOf('session-expired'),
        protocolVersion: 1,
        at: Date.now(),
      },
    },
    pendingSince: Date.now() - (PAIRING_WINDOW_MS + 60000),
  });

  const result = await call(
    makeProof(zoe, { partnerUid: ann.uid, transcriptHash: transcriptOf('session-restart') }),
    zoe.uid,
  );
  assert.equal(result.status, 'pending');

  const doc = await docAt(`pairings/${pairId}`);
  assert.deepEqual(Object.keys(doc.proofs), [zoe.uid], 'the expired attempt was discarded wholesale');
  assert.equal(doc.pairingEpoch, 5);
  assert.ok(doc.pendingSince > Date.now() - 5000, 'the window restarted');
});

test('the per-UID rate limit admits N executions, rejects the next, and never counts a rejection', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const zoe = makeIdentity(newUid('uid-zoe'));

  for (let i = 0; i < PAIRING_LIMIT_MAX; i += 1) {
    const result = await call(
      makeProof(ann, { partnerUid: zoe.uid, transcriptHash: transcriptOf('session-rate') }),
      ann.uid,
    );
    assert.equal(result.status, 'pending', `execution ${i + 1} within budget`);
  }

  await expectRejection(
    call(makeProof(ann, { partnerUid: zoe.uid, transcriptHash: transcriptOf('session-rate') }), ann.uid),
    'resource-exhausted',
    'RATE_LIMITED',
  );

  const limit = await docAt(`rate-limits/pairing_${ann.uid}`);
  assert.equal(limit.window.count, PAIRING_LIMIT_MAX, 'the rejected attempt was not counted');

  const cy = makeIdentity(newUid('uid-cy'));
  const other = await call(
    makeProof(cy, { partnerUid: zoe.uid, transcriptHash: transcriptOf('session-other') }),
    cy.uid,
  );
  assert.equal(other.status, 'pending', 'a different uid has its own budget');
});

test('an idempotency key replayed with a different payload is refused', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const zoe = makeIdentity(newUid('uid-zoe'));
  const proof = makeProof(ann, { partnerUid: zoe.uid, transcriptHash: transcriptOf('session-reuse') });

  await call(proof, ann.uid);
  await expectRejection(
    call({ ...proof, partnerUid: newUid('uid-other') }, ann.uid),
    'invalid-argument',
    'REQUEST_ID_REUSED',
  );
});

test('an oversized request is refused before the requestId is even read', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const oversized = {
    ...makeProof(ann, { partnerUid: newUid('uid-zoe'), transcriptHash: transcriptOf('session-big') }),
    requestId: 'not a valid id',
    padding: 'A'.repeat(20 * 1024),
  };
  await expectRejection(call(oversized, ann.uid), 'invalid-argument', 'REQUEST_TOO_LARGE');
  assert.equal((await db.collection('idempotency').get()).size, 0, 'nothing was cached');
});

test('requestId shapes that would be unsafe as document ids are refused', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const partnerUid = newUid('uid-zoe');
  const base = makeProof(ann, { partnerUid, transcriptHash: transcriptOf('session-id') });

  for (const requestId of [undefined, '', 'short', 'a'.repeat(65), `req/../${newUid()}`, 'req id', 42, null]) {
    await expectRejection(call({ ...base, requestId }, ann.uid), 'invalid-argument', 'REQUEST_ID_INVALID');
  }
  await expectRejection(call(null, ann.uid), 'invalid-argument', 'REQUEST_ID_INVALID');
  await expectRejection(call('proof', ann.uid), 'invalid-argument', 'REQUEST_ID_INVALID');
});

test('identity is taken from the authenticated caller, never from the payload', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const zoe = makeIdentity(newUid('uid-zoe'));
  const impersonation = makeProof(zoe, { partnerUid: ann.uid, transcriptHash: transcriptOf('session-forge') });

  await expectRejection(call(impersonation, ann.uid), 'permission-denied', 'ownUid must be the authenticated user');
  assert.equal(await docAt(`pairings/${pairIdOf(ann.uid, zoe.uid)}`), null, 'no pairing document was written');
});

test('malformed and hostile proofs are refused with stable reason codes', async () => {
  const cases = [
    ['SELF_PAIRING', 'invalid-argument', (id) => makeProof(id, { partnerUid: id.uid, transcriptHash: transcriptOf('s') })],
    ['PROTOCOL_VERSION_UNSUPPORTED', 'invalid-argument', (id) => makeProof(id, { partnerUid: newUid(), transcriptHash: transcriptOf('s'), protocolVersion: 2 })],
    ['PROTOCOL_VERSION_UNSUPPORTED', 'invalid-argument', (id) => makeProof(id, { partnerUid: newUid(), transcriptHash: transcriptOf('s'), protocolVersion: '1' })],
    ['KEY_HASH_MISMATCH', 'invalid-argument', (id) => makeProof(id, { partnerUid: newUid(), transcriptHash: transcriptOf('s'), overrides: { publicKeyHash: 'f'.repeat(64) } })],
    ['SIGNATURE_INVALID', 'invalid-argument', (id) => makeProof(id, { partnerUid: newUid(), transcriptHash: transcriptOf('s'), overrides: { partnerUid: newUid() } })],
    ['MALFORMED', 'invalid-argument', (id) => makeProof(id, { partnerUid: newUid(), transcriptHash: transcriptOf('s'), overrides: { signature: '' } })],
    ['MALFORMED', 'invalid-argument', (id) => makeProof(id, { partnerUid: newUid(), transcriptHash: transcriptOf('s'), overrides: { signingPublicKey: '' } })],
    ['MALFORMED', 'invalid-argument', (id) => makeProof(id, { partnerUid: newUid(), transcriptHash: transcriptOf('s'), overrides: { transcriptHash: 'not-a-digest' } })],
    ['KEY_UNPARSEABLE', 'invalid-argument', (id) => {
      const garbage = Buffer.from('not a public key at all').toString('base64');
      const { publicKeyHashOf } = require('../functions/proof');
      return makeProof(id, {
        partnerUid: newUid(),
        transcriptHash: transcriptOf('s'),
        overrides: { signingPublicKey: garbage, publicKeyHash: publicKeyHashOf(garbage) },
      });
    }],
  ];

  for (const [reason, code, build] of cases) {
    const id = makeIdentity(newUid('uid-hostile'));
    await expectRejection(call(build(id), id.uid), code, reason);
  }
});

test('unknown extra fields are tolerated and bound by the request hash', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const zoe = makeIdentity(newUid('uid-zoe'));
  const transcript = transcriptOf('session-extras');
  const proof = makeProof(ann, { partnerUid: zoe.uid, transcriptHash: transcript });
  const withExtras = { ...proof, futureField: 'ignored-by-design' };

  const result = await call(withExtras, ann.uid);
  assert.equal(result.status, 'pending', 'an additive field never crashes the handler');

  await expectRejection(
    call({ ...withExtras, futureField: 'changed' }, ann.uid),
    'invalid-argument',
    'REQUEST_ID_REUSED',
  );
});

test('an attestation rejection precedes every pairing write', async () => {
  const strictFn = _createRegisterPairing({ attestationVerifier: () => ({ ok: false, reason: 'KEY_SOFTWARE' }) });
  const ann = makeIdentity(newUid('uid-ann'));
  const zoe = makeIdentity(newUid('uid-zoe'));
  const proof = makeProof(ann, { partnerUid: zoe.uid, transcriptHash: transcriptOf('session-software') });

  await expectRejection(strictFn.run({ data: proof, auth: { uid: ann.uid } }), 'invalid-argument', 'KEY_SOFTWARE');

  assert.equal(await docAt(`pairings/${pairIdOf(ann.uid, zoe.uid)}`), null, 'no pairing document');
  assert.equal(await docAt(`idempotency/${proof.requestId}`), null, 'no cached result for a rejected request');
});

test('the idempotency record carries the §10.12 retention window', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const proof = makeProof(ann, { partnerUid: newUid('uid-zoe'), transcriptHash: transcriptOf('session-retention') });
  await call(proof, ann.uid);

  const record = await docAt(`idempotency/${proof.requestId}`);
  const retentionMs = record.expiresAt.toMillis() - record.createdAt.toMillis();
  assert.ok(
    Math.abs(retentionMs - IDEMPOTENCY_RETENTION_MS) < 1000,
    `expiresAt - createdAt should be the 24h retention window, saw ${retentionMs}ms`,
  );
});

// -------------------------------------------------------------------- HTTP wire

const b64url = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');

/** Unsigned JWT: the emulator's debug mode skips verification but reads `sub` (see ADR #98). */
const mockToken = (payload) => `${b64url({ alg: 'none', typ: 'JWT' })}.${b64url(payload)}.`;
const idToken = (uid) => mockToken({ sub: uid, user_id: uid, aud: PROJECT, iss: `https://securetoken.google.com/${PROJECT}` });
const appCheckToken = () => mockToken({ sub: '1:000000000000:android:emulator', aud: PROJECT, iss: `https://firebaseappcheck.googleapis.com/${PROJECT}` });

async function invoke(data, { uid, auth = true, appCheck = true } = {}) {
  const headers = { 'content-type': 'application/json' };
  if (auth) headers.authorization = `Bearer ${idToken(uid)}`;
  if (appCheck) headers['X-Firebase-AppCheck'] = appCheckToken();
  const res = await fetch(functionsUrl, { method: 'POST', headers, body: JSON.stringify({ data }) });
  return res.json();
}

test('a call without App Check is rejected before any business logic runs', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const proof = makeProof(ann, { partnerUid: newUid('uid-zoe'), transcriptHash: transcriptOf('session-appcheck') });

  const body = await invoke(proof, { uid: ann.uid, appCheck: false });
  assert.equal(body.error.status, 'UNAUTHENTICATED');

  assert.equal(await docAt(`pairings/${pairIdOf(ann.uid, proof.partnerUid)}`), null);
  assert.equal(await docAt(`rate-limits/pairing_${ann.uid}`), null, 'the rate-limit transaction never ran');
});

test('a call without an auth token is rejected by the handler', async () => {
  const ann = makeIdentity(newUid('uid-ann'));
  const proof = makeProof(ann, { partnerUid: newUid('uid-zoe'), transcriptHash: transcriptOf('session-noauth') });

  const body = await invoke(proof, { uid: ann.uid, auth: false });
  assert.equal(body.error.status, 'UNAUTHENTICATED');
  assert.equal(body.error.message, 'anonymous sign-in required');
});

test('a tampered proof is refused over the wire, before attestation', async () => {
  const ann = makeAttestedIdentity(newUid('uid-ann'));
  const proof = makeProof(ann, {
    partnerUid: newUid('uid-zoe'),
    transcriptHash: transcriptOf('session-wire-tamper'),
    overrides: { partnerUid: newUid('uid-zoe') },
  });

  const body = await invoke(proof, { uid: ann.uid });
  assert.equal(body.error.message, 'SIGNATURE_INVALID', 'proof verification runs before the attestation verifier');
});

test('the deployed function verifies attestation against the pinned roots', async () => {
  const ann = makeAttestedIdentity(newUid('uid-ann'));
  const proof = makeProof(ann, { partnerUid: newUid('uid-zoe'), transcriptHash: transcriptOf('session-wire-chain') });

  const body = await invoke(proof, { uid: ann.uid });
  assert.equal(body.error.status, 'INVALID_ARGUMENT');
  assert.equal(body.error.message, 'CHAIN_UNTRUSTED', 'a self-signed chain never reaches a write');
  assert.equal(await docAt(`pairings/${pairIdOf(ann.uid, proof.partnerUid)}`), null);
});

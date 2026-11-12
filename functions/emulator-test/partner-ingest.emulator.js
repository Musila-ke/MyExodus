'use strict';
// `ingestPartnerEvents` suite (§8.4/§8.6/R08) — the real handler against the Firestore
// emulator. Named without `.test.js` on purpose: `npm test` stays emulator-free, so this
// file runs only via `npm run test:partner-ingest`.
//
// Events are signed with real P-256 keys over the protocol's JCS bytes, and the sender's
// registration plus every recipient key/pairing is seeded directly. What is under test is
// the ingress protocol: per-event result codes, the duplicate-digest rules, signature and
// binding enforcement, the no-write RECIPIENT_KEY_STALE contract, the R08 atomicity of
// ledger + mailbox + job (or the deferred fanout task), block coalescing, contact-at-most-
// once, and the per-installation budget.

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');

const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { HttpsError } = require('firebase-functions/v2/https');

const { createIngestPartnerEventsHandler } = require('../partner-ingest');
const { canonicalBytes, eventToWireValue } = require('../partner-events');
const { pairIdOf } = require('../pair-ids');

const PROJECT_ID = 'demo-myexodus-partner-ingest';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const INSTALL_A = '3f0f2a0e-7c33-4b7a-9e1f-2a1b3c4d5e6f';
const NOW = Date.UTC(2026, 10, 11, 12, 0, 0);

const alice = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const ALICE_SPKI = alice.publicKey.export({ type: 'spki', format: 'der' });
const ALICE_KEY_ID = crypto.createHash('sha256').update(ALICE_SPKI).digest('base64url');

let app;
let db;
let clockMs;
let handler;

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, 'partner-ingest-emulator-suite');
  db = getFirestore(app);
  handler = createIngestPartnerEventsHandler({ db, now: () => clockMs });
});

after(async () => {
  await deleteApp(app);
});

beforeEach(async () => {
  clockMs = NOW;
  for (const collection of [
    'partnerEvents', 'partnerMailboxes', 'partnerNotificationOutbox', 'partnerFanoutTasks',
    'partnerDeviceSigningRegistrations', 'partnerRecipientKeys', 'partnerDeviceLiveness',
    'pairings', 'rate-limits', 'deletionJobs',
  ]) {
    await db.recursiveDelete(db.collection(collection));
  }
  await db.doc(`partnerDeviceSigningRegistrations/${ALICE}:${INSTALL_A}`).set({
    ownerUid: ALICE,
    installationId: INSTALL_A,
    signingKeyId: ALICE_KEY_ID,
    signingSpkiDerBase64: ALICE_SPKI.toString('base64'),
    integrityTier: 'ACTIVE_UNATTESTED',
    registrationRevision: 1,
    status: 'ACTIVE',
  });
});

const call = (data, uid = ALICE) => handler({ auth: uid ? { uid } : undefined, data });
const ingest = (events, overrides = {}) => ({ installationId: INSTALL_A, events, ...overrides });
const request = (events) => call(ingest(events));

async function expectRejection(promise, code, message) {
  try {
    await promise;
    assert.fail(`expected HttpsError(${code})`);
  } catch (e) {
    assert.ok(e instanceof HttpsError, `expected HttpsError, got ${e && e.constructor && e.constructor.name}`);
    assert.strictEqual(e.code, code, `expected code ${code}, got ${e.code}: ${e.message}`);
    if (message) assert.strictEqual(e.message, message);
    return e;
  }
}

/** Seeds a recipient's ACTIVE key and its active pairing with the sender. */
async function seedRecipient(uid, { revision = 1, status = 'ACTIVE' } = {}) {
  const pair = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const spki = pair.publicKey.export({ type: 'spki', format: 'der' });
  const keyId = crypto.createHash('sha256').update(spki).digest('base64url');
  const installationId = `${uid}-install`;
  await db.doc(`partnerRecipientKeys/${keyId}`).set({
    ownerUid: uid,
    installationId,
    keyId,
    algorithm: 'ECDH-P256',
    spkiDerBase64: spki.toString('base64'),
    revision,
    status,
  });
  await db.doc(`pairings/${pairIdOf(ALICE, uid)}`).set({
    members: [ALICE, uid],
    status: 'active',
    uidA: ALICE,
    uidB: uid,
  });
  return { uid, keyId, installationId };
}

const envelope = (recipientEncryptionKeyId, payload = 'AAECAw') => ({
  recipientEncryptionKeyId,
  version: 1,
  encoding: 'existing-e2ee',
  payload,
});

function makeEvent(overrides = {}) {
  return {
    v: 1,
    eventId: crypto.randomUUID(),
    installationId: INSTALL_A,
    ownerUid: ALICE,
    sequence: 1,
    kind: 'CHECK_IN',
    deviceOccurredAtMs: NOW,
    metadata: {},
    ...overrides,
  };
}

const wire = (event, keyId = ALICE_KEY_ID) => ({
  keyId,
  event,
  signature: crypto.sign(
    'sha256', canonicalBytes(eventToWireValue(event)), alice.privateKey,
  ).toString('base64url'),
});

/** Multi-recipient check-in signed once. */
function eventFor(recipients, overrides = {}) {
  return wire(makeEvent({
    recipientEnvelopes: recipients.map((r) => envelope(r.keyId)),
    ...overrides,
  }));
}

const ledgerPath = (eventId) => `partnerEvents/${ALICE}/installations/${INSTALL_A}/events/${eventId}`;
const mailboxPath = (uid, eventId, installationId) =>
  `partnerMailboxes/${uid}/messages/${eventId}__${installationId}`;
const jobPath = (eventId, installationId) => `partnerNotificationOutbox/${eventId}__${installationId}`;
const ratePath = `rate-limits/partnerIngest_${ALICE}_${INSTALL_A}`;

const exists = async (path) => (await db.doc(path).get()).exists;

test('an unauthenticated caller is refused', async () => {
  await expectRejection(call(ingest([]), null), 'unauthenticated');
});

test('malformed batches are refused before any write', async () => {
  const bob = await seedRecipient(BOB);
  const w = eventFor([bob]);

  await expectRejection(request([]), 'invalid-argument', 'BATCH_INVALID');
  await expectRejection(call(null), 'invalid-argument', 'REQUEST_INVALID');
  await expectRejection(call({ events: [w] }), 'invalid-argument', 'INSTALLATION_ID_INVALID');
  await expectRejection(call(ingest([w], { installationId: 'not-a-uuid' })),
    'invalid-argument', 'INSTALLATION_ID_INVALID');
  await expectRejection(call({ installationId: INSTALL_A, events: [w], extra: 1 }),
    'invalid-argument', 'REQUEST_INVALID');
  await expectRejection(request(Array.from({ length: 21 }, (_, i) =>
    eventFor([bob], { eventId: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}` }))),
  'invalid-argument', 'TOO_LARGE');

  assert.strictEqual(await exists(ledgerPath(w.event.eventId)), false);
});

test('an accepted check-in writes the ledger, the mailbox and the notification job', async () => {
  const bob = await seedRecipient(BOB);
  const w = eventFor([bob]);
  const reply = await request([w]);

  assert.deepStrictEqual(reply.results, [
    { index: 0, code: 'ACCEPTED', eventId: w.event.eventId },
  ]);

  const ledger = (await db.doc(ledgerPath(w.event.eventId)).get()).data();
  assert.strictEqual(ledger.ownerUid, ALICE);
  assert.strictEqual(ledger.installationId, INSTALL_A);
  assert.strictEqual(ledger.kind, 'CHECK_IN');
  assert.strictEqual(ledger.signingKeyId, ALICE_KEY_ID);
  assert.strictEqual(ledger.signature, w.signature);
  assert.deepStrictEqual(ledger.recipientKeyIds, [bob.keyId]);
  assert.strictEqual(typeof ledger.digestSha256, 'string');
  assert.strictEqual(ledger.status, 'ACCEPTED');
  assert.strictEqual(ledger.receivedAt, NOW);

  const mailbox = (await db.doc(mailboxPath(BOB, w.event.eventId, bob.installationId)).get()).data();
  assert.strictEqual(mailbox.ownerUid, ALICE);
  assert.strictEqual(mailbox.eventId, w.event.eventId);
  assert.strictEqual(mailbox.recipientKeyId, bob.keyId);
  assert.strictEqual(mailbox.recipientKeyRevision, 1);
  assert.strictEqual(mailbox.payload, 'AAECAw');
  assert.strictEqual(mailbox.encoding, 'existing-e2ee');
  assert.strictEqual(mailbox.pairId, pairIdOf(ALICE, BOB));

  const job = (await db.doc(jobPath(w.event.eventId, bob.installationId)).get()).data();
  assert.strictEqual(job.recipientUid, BOB);
  assert.strictEqual(job.recipientInstallationId, bob.installationId);
  assert.strictEqual(job.groupId, null);
  assert.strictEqual(job.status, 'PENDING');
  assert.strictEqual(job.leaseUntil, null);

  const rate = (await db.doc(ratePath).get()).data();
  assert.deepStrictEqual(rate.window, { start: NOW, count: 1 });
});

test('block attempts coalesce under one server-owned group id per minute', async () => {
  const bob = await seedRecipient(BOB);
  const block = (eventId) => eventFor([bob], {
    eventId, kind: 'BLOCK_ATTEMPT', metadata: { category: 'BLOCKED_APP_ATTEMPT' },
  });
  const first = block(crypto.randomUUID());
  const second = block(crypto.randomUUID());
  await request([first, second]);

  const minute = Math.floor(NOW / 60000);
  const group = `blk:${BOB}:${bob.installationId}:${minute}`;
  assert.strictEqual(
    (await db.doc(jobPath(first.event.eventId, bob.installationId)).get()).data().groupId, group,
  );
  assert.strictEqual(
    (await db.doc(jobPath(second.event.eventId, bob.installationId)).get()).data().groupId, group,
  );

  clockMs += 60 * 1000;
  const third = block(crypto.randomUUID());
  await request([third]);
  assert.strictEqual(
    (await db.doc(jobPath(third.event.eventId, bob.installationId)).get()).data().groupId,
    `blk:${BOB}:${bob.installationId}:${minute + 1}`,
  );
});

test('an identical retry is ALREADY_ACCEPTED, even after key rotation', async () => {
  const bob = await seedRecipient(BOB);
  const w = eventFor([bob]);
  await request([w]);

  await db.doc(`partnerRecipientKeys/${bob.keyId}`).set(
    { status: 'REVOKED', revokedAt: NOW }, { merge: true },
  );
  const retry = await request([w]);
  assert.deepStrictEqual(retry.results, [
    { index: 0, code: 'ALREADY_ACCEPTED', eventId: w.event.eventId },
  ]);
  assert.strictEqual(await exists(mailboxPath(BOB, w.event.eventId, bob.installationId)), true);
});

test('a different digest under a committed event id is a security rejection', async () => {
  const bob = await seedRecipient(BOB);
  const original = eventFor([bob]);
  await request([original]);

  const tampered = wire({ ...original.event, sequence: 2 });
  const reply = await request([tampered]);
  assert.deepStrictEqual(reply.results, [{
    index: 0,
    code: 'PERMANENT_REJECTED',
    eventId: original.event.eventId,
    reason: 'EVENT_ID_CONFLICT',
  }]);
  const ledger = (await db.doc(ledgerPath(original.event.eventId)).get()).data();
  assert.strictEqual(ledger.sequence, 1);
});

test('signature and account/installation binding are enforced before any write', async () => {
  const bob = await seedRecipient(BOB);
  const good = makeEvent({ recipientEnvelopes: [envelope(bob.keyId)] });

  const badSignature = await request([{ ...wire(good), signature: 'A'.repeat(86) }]);
  assert.strictEqual(badSignature.results[0].code, 'PERMANENT_REJECTED');
  assert.strictEqual(badSignature.results[0].reason, 'SIGNATURE_INVALID');

  const unknownKey = await request([{ ...wire(good), keyId: 'A'.repeat(43) }]);
  assert.strictEqual(unknownKey.results[0].code, 'REENROLL_REQUIRED');

  await db.doc(`partnerDeviceSigningRegistrations/${ALICE}:${INSTALL_A}`).set(
    { status: 'REENROLL_REQUIRED' }, { merge: true },
  );
  assert.strictEqual((await request([wire(good)])).results[0].code, 'REENROLL_REQUIRED');
  await db.doc(`partnerDeviceSigningRegistrations/${ALICE}:${INSTALL_A}`).set(
    { status: 'ACTIVE' }, { merge: true },
  );

  const wrongOwner = wire({ ...good, ownerUid: BOB });
  assert.strictEqual((await request([wrongOwner])).results[0].reason, 'OWNER_UID_MISMATCH');

  const wrongInstall = wire({ ...good, installationId: crypto.randomUUID() });
  assert.strictEqual((await request([wrongInstall])).results[0].reason, 'INSTALLATION_MISMATCH');

  assert.strictEqual(await exists(ledgerPath(good.eventId)), false);
});

test('stale recipient keys reject with no writes and return the authorized keys', async () => {
  const bob = await seedRecipient(BOB);
  const staleEvent = wire(makeEvent({ recipientEnvelopes: [envelope(randomKeyId())] }));

  const reply = await request([staleEvent]);
  assert.strictEqual(reply.results[0].code, 'RECIPIENT_KEY_STALE');
  assert.deepStrictEqual(reply.results[0].currentRecipientKeys, [{
    keyId: bob.keyId, revision: 1, installationId: bob.installationId,
  }]);
  assert.strictEqual(await exists(ledgerPath(staleEvent.event.eventId)), false);

  await db.doc(`partnerRecipientKeys/${bob.keyId}`).set(
    { status: 'REVOKED', revokedAt: NOW }, { merge: true },
  );
  const rotated = await seedRecipient(BOB, { revision: 2 });
  const afterRotation = await request([wire(makeEvent({
    recipientEnvelopes: [envelope(bob.keyId)],
  }))]);
  assert.strictEqual(afterRotation.results[0].code, 'RECIPIENT_KEY_STALE');
  assert.deepStrictEqual(afterRotation.results[0].currentRecipientKeys, [{
    keyId: rotated.keyId, revision: 2, installationId: rotated.installationId,
  }]);
});

test('an unpaired recipient and a self-addressed envelope are stale', async () => {
  const aliceKey = await seedRecipient(ALICE);
  await db.doc(`pairings/${pairIdOf(ALICE, ALICE)}`).delete();
  await seedRecipient(BOB);
  const carol = await seedRecipient('carol-uid');
  await db.doc(`pairings/${pairIdOf(ALICE, 'carol-uid')}`).delete();

  const reply = await request([
    wire(makeEvent({ recipientEnvelopes: [envelope(carol.keyId)] })),
    wire(makeEvent({ recipientEnvelopes: [envelope(aliceKey.keyId)] })),
  ]);
  assert.deepStrictEqual(reply.results.map((r) => r.code),
    ['RECIPIENT_KEY_STALE', 'RECIPIENT_KEY_STALE']);
  assert.strictEqual(reply.results[0].currentRecipientKeys.length, 1);
});

test('a partially accepted batch reports per-event codes and commits only the valid events', async () => {
  const bob = await seedRecipient(BOB);
  const good = eventFor([bob]);
  const bad = { ...wire(makeEvent({ recipientEnvelopes: [envelope(bob.keyId)] })), signature: 'AAAA' };
  const alsoGood = eventFor([bob]);

  const reply = await request([good, bad, alsoGood]);
  assert.deepStrictEqual(reply.results.map((r) => r.code),
    ['ACCEPTED', 'PERMANENT_REJECTED', 'ACCEPTED']);
  assert.strictEqual(await exists(ledgerPath(good.event.eventId)), true);
  assert.strictEqual(await exists(ledgerPath(bad.event.eventId)), false);
  assert.strictEqual(await exists(ledgerPath(alsoGood.event.eventId)), true);
});

test('more than twenty recipients defers to a durable fanout task', async () => {
  const recipients = [];
  for (let i = 0; i < 21; i += 1) {
    recipients.push(await seedRecipient(`recipient-${String(i).padStart(2, '0')}`));
  }
  const w = eventFor(recipients);
  const reply = await request([w]);

  assert.deepStrictEqual(reply.results, [{
    index: 0, code: 'ACCEPTED', eventId: w.event.eventId, fanout: 'DEFERRED',
  }]);
  const task = (await db.doc(`partnerFanoutTasks/${w.event.eventId}`).get()).data();
  assert.strictEqual(task.status, 'PENDING');
  assert.strictEqual(task.ownerUid, ALICE);
  assert.strictEqual(task.recipients.length, 21);
  assert.strictEqual(task.recipients[0].recipientUid, recipients[0].uid);
  assert.strictEqual(task.recipients[0].pairId, pairIdOf(ALICE, recipients[0].uid));
  assert.strictEqual(await exists(ledgerPath(w.event.eventId)), true);
  assert.strictEqual(
    await exists(mailboxPath(recipients[0].uid, w.event.eventId, recipients[0].installationId)),
    false,
  );
});

test('contact is recorded at most once per accepted batch, only for a known liveness row', async () => {
  const bob = await seedRecipient(BOB);
  const livenessPath = `partnerDeviceLiveness/${INSTALL_A}`;

  const first = eventFor([bob]);
  await request([first]);
  assert.strictEqual(await exists(livenessPath), false);

  await db.doc(livenessPath).set({ ownerUid: ALICE, lastAcceptedContactAt: NOW - 5000 });
  clockMs += 1000;
  await request([eventFor([bob]), eventFor([bob])]);
  assert.strictEqual((await db.doc(livenessPath).get()).data().lastAcceptedContactAt, clockMs);

  const acceptedAt = clockMs;
  clockMs += 1000;
  await request([wire(makeEvent({ recipientEnvelopes: [envelope(randomKeyId())] }))]);
  assert.strictEqual((await db.doc(livenessPath).get()).data().lastAcceptedContactAt, acceptedAt);
});

test('the per-installation ingestion budget is charged per batch and stops further work', async () => {
  const bob = await seedRecipient(BOB);
  await request([eventFor([bob])]);
  assert.deepStrictEqual((await db.doc(ratePath).get()).data().window, { start: NOW, count: 1 });

  await db.doc(ratePath).set({ window: { start: NOW, count: 240 } });
  const blocked = eventFor([bob]);
  await expectRejection(request([blocked]), 'resource-exhausted', 'RATE_LIMITED');
  assert.strictEqual(await exists(ledgerPath(blocked.event.eventId)), false);

  await db.doc(ratePath).set({ window: { start: NOW - 60 * 60 * 1000, count: 240 } });
  assert.strictEqual((await request([eventFor([bob])])).results[0].code, 'ACCEPTED');
});

test('paused ingest returns retryable maintenance without writing, then accepts after resume', async () => {
  const bob = await seedRecipient(BOB);
  const w = eventFor([bob]);
  await db.doc('config/features').set({ delivery_ingest_v1_enabled: false });

  await expectRejection(request([w]), 'unavailable', 'MAINTENANCE');
  assert.strictEqual(await exists(ledgerPath(w.event.eventId)), false);
  assert.strictEqual(await exists(mailboxPath(BOB, w.event.eventId, bob.installationId)), false);
  assert.strictEqual(await exists(jobPath(w.event.eventId, bob.installationId)), false);
  assert.strictEqual(await exists(ratePath), false, 'a paused ingest charges no budget');

  await db.doc('config/features').delete();
  const reply = await request([w]);
  assert.strictEqual(reply.results[0].code, 'ACCEPTED');
});

test('an account being deleted cannot ingest', async () => {
  const bob = await seedRecipient(BOB);
  await db.doc(`deletionJobs/${ALICE}`).set({ status: 'QUEUED' });
  await expectRejection(request([eventFor([bob])]), 'permission-denied', 'ACCOUNT_DELETING');
});

function randomKeyId() {
  return crypto.createHash('sha256').update(crypto.randomBytes(16)).digest('base64url');
}

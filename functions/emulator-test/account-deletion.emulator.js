'use strict';
// Account deletion suite — the real handlers against the Firestore and Storage emulators
// with an in-memory Auth/Cloud Tasks seam (account plan §6.8 / §7 step 16).
// Run via `npm run test:deletion`.

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');

const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore, Timestamp } = require('firebase-admin/firestore');
const { getStorage } = require('firebase-admin/storage');
const { HttpsError } = require('firebase-functions/v2/https');

const {
  createRequestAccountDeletionHandler,
  createRequestGuestDataDeletionHandler,
  createProcessAccountDeletionHandler,
  createReconcileDeletionJobsHandler,
  createAuthDeletionBackstopHandler,
} = require('../account-deletion');
const {
  ACCOUNT_CONFIRMATION,
  GUEST_CONFIRMATION,
  PHASES,
} = require('../account-deletion-logic');

const PROJECT_ID = 'demo-myexodus-deletion';
const BUCKET = `${PROJECT_ID}.appspot.com`;
const USER = 'delUser1';
const PARTNER = 'partnerUser1';
const OTHER = 'otherUser1';
const PAIR_A = `${USER}_${PARTNER}`;
const PAIR_B = `${USER}_${OTHER}`;
const PAIR_C = `${PARTNER}_${OTHER}`;

let app;
let db;
let bucket;
let nowMs;

// --- seams ---------------------------------------------------------------------------------

function encodeToken(uid, { authTime, provider = 'google.com', verified = true }) {
  return `${uid}|${authTime}|${provider}|${verified}`;
}

function createFakeAuth(users) {
  const state = {
    users: new Set(users),
    disabled: new Set(),
    revoked: new Set(),
    deleted: [],
  };
  return {
    state,
    async verifyIdToken(raw) {
      const [uid, authTime, provider, verified] = String(raw).split('|');
      if (!state.users.has(uid)) throw new Error('auth/user-not-found');
      return {
        uid,
        auth_time: Number(authTime),
        email_verified: verified === 'true',
        firebase: { sign_in_provider: provider },
      };
    },
    async updateUser(uid, patch) {
      if (!state.users.has(uid)) {
        const e = new Error('no user');
        e.code = 'auth/user-not-found';
        throw e;
      }
      if (patch && patch.disabled) state.disabled.add(uid);
      return { uid };
    },
    async revokeRefreshTokens(uid) {
      if (!state.users.has(uid)) {
        const e = new Error('no user');
        e.code = 'auth/user-not-found';
        throw e;
      }
      state.revoked.add(uid);
    },
    async deleteUser(uid) {
      if (!state.users.has(uid)) {
        const e = new Error('no user');
        e.code = 'auth/user-not-found';
        throw e;
      }
      state.users.delete(uid);
      state.deleted.push(uid);
    },
  };
}

function createEnqueueSpy() {
  const calls = [];
  return {
    calls,
    async enqueue(uid) {
      calls.push(uid);
    },
  };
}

const auth = (uid, provider = 'google.com', extra = {}) => ({
  auth: { uid, token: { firebase: { sign_in_provider: provider }, ...extra } },
});

function request(uid, token, data, provider = 'google.com') {
  return {
    ...auth(uid, provider),
    rawRequest: { headers: token ? { authorization: `Bearer ${token}` } : {} },
    data,
  };
}

const nowSec = () => Math.floor(nowMs / 1000);

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

// --- world ---------------------------------------------------------------------------------

async function clearAll() {
  nowMs = Date.now();
  for (const collection of [
    'users', 'deletionJobs', 'mailboxes', 'relay-dispatch', 'backupReservations',
    'dms_install_state', 'dms_notification_outbox', 'guestTransitions',
    'guestTransitionLeases', 'pairing_tokens', 'rate-limits', 'eventRateLimits',
    'pairings', 'nudgePushQueue',
  ]) {
    await db.recursiveDelete(db.collection(collection));
  }
  const [files] = await bucket.getFiles({ prefix: 'backups/' });
  await Promise.all(files.map((file) => file.delete({ ignoreNotFound: true })));
}

async function seedWorld() {
  // Profiles and per-uid trees.
  await db.doc(`users/${USER}`).set({ status: 'ACTIVE', createdAt: Timestamp.now() });
  await db.doc(`users/${USER}/push_installations/inst1`).set({ provider: 'fcm', token: 'tok' });
  await db.doc(`users/${USER}/backupControl/current`).set({ backupId: 'b1', revision: 3 });
  await db.doc(`users/${USER}/backupManifests/b1`).set({ backupId: 'b1', revision: 3 });
  await db.doc(`users/${PARTNER}`).set({ status: 'ACTIVE' });

  // Pairs: A active with proofs/consent, B terminated, C partner-only.
  await db.doc(`pairings/${PAIR_A}`).set({
    uidA: USER, uidB: PARTNER, members: [USER, PARTNER], status: 'active', pairingEpoch: 1,
    proofs: {
      [USER]: { publicKeyHash: 'aaa', transcriptHash: 'ttt', protocolVersion: 2, at: nowMs },
      [PARTNER]: { publicKeyHash: 'bbb', transcriptHash: 'ttt', protocolVersion: 2, at: nowMs },
    },
    availabilityConsent: {
      [USER]: { enabled: true, updatedAtMs: nowMs },
      [PARTNER]: { enabled: false, updatedAtMs: nowMs },
    },
  });
  await db.doc(`pairings/${PAIR_B}`).set({
    uidA: USER, uidB: OTHER, members: [USER, OTHER], status: 'terminated', pairingEpoch: 1,
    proofs: { [USER]: { publicKeyHash: 'ccc' }, [OTHER]: { publicKeyHash: 'ddd' } },
  });
  await db.doc(`pairings/${PAIR_C}`).set({
    uidA: PARTNER, uidB: OTHER, members: [PARTNER, OTHER], status: 'active', pairingEpoch: 1,
    proofs: { [PARTNER]: { publicKeyHash: 'eee' }, [OTHER]: { publicKeyHash: 'fff' } },
  });

  // Nudges: both directions are the deleted user's private records; partner state stays.
  await db.doc(`pairings/${PAIR_A}/nudges/nudgeFromUser`).set({
    senderUid: USER, recipientUid: PARTNER, pairId: PAIR_A, expiresAt: Timestamp.now(),
  });
  await db.doc(`pairings/${PAIR_A}/nudges/nudgeToUser`).set({
    senderUid: PARTNER, recipientUid: USER, pairId: PAIR_A, expiresAt: Timestamp.now(),
  });
  await db.doc('nudgePushQueue/nudgeFromUser').set({ pairId: PAIR_A });
  await db.doc('nudgePushQueue/nudgeToUser').set({ pairId: PAIR_A });
  await db.doc(`pairings/${PAIR_A}/nudgeSettings/${USER}`).set({ blocked: true });
  await db.doc(`pairings/${PAIR_A}/nudgeSettings/${PARTNER}`).set({ blocked: false });
  await db.doc(`pairings/${PAIR_A}/nudgeState/${USER}`).set({ dayCount: 2 });
  await db.doc(`pairings/${PAIR_A}/nudgeState/${PARTNER}`).set({ dayCount: 3 });

  // Mailboxes both directions; relay records follow their message.
  await db.doc(`mailboxes/${USER}/messages/m1`).set({ senderUid: PARTNER, pairId: PAIR_A });
  await db.doc(`mailboxes/${USER}/messages/m1/acks/inst1`).set({ ackedAt: Timestamp.now() });
  await db.doc(`mailboxes/${PARTNER}/messages/mFromUser`).set({ senderUid: USER, pairId: PAIR_A });
  await db.doc('relay-dispatch/m1/installations/i1').set({ recipientUid: USER, status: 'DISPATCHED' });
  await db.doc('relay-dispatch/mFromUser/installations/i2').set({ recipientUid: PARTNER, status: 'DISPATCHED' });

  // Backups: ledger + object.
  await db.doc(`backupReservations/${USER}/reservations/r1`).set({ backupId: 'b1', expiresAt: Timestamp.now() });
  await db.doc(`backupReservations/${PARTNER}/reservations/p1`).set({ backupId: 'p1', expiresAt: Timestamp.now() });
  await bucket.file(`backups/${USER}/b1`).save('user-object');
  await bucket.file(`backups/${PARTNER}/p1`).save('partner-object');

  // Device availability + notification outbox.
  await db.doc(`dms_install_state/${USER}_inst1`).set({ uid: USER, next_deadline_at: Timestamp.now() });
  await db.doc(`dms_install_state/${PARTNER}_inst2`).set({ uid: PARTNER, next_deadline_at: Timestamp.now() });
  await db.doc('dms_notification_outbox/n1').set({ monitored_uid: USER, recipient_uid: PARTNER });
  await db.doc('dms_notification_outbox/n2').set({ monitored_uid: PARTNER, recipient_uid: USER });
  await db.doc('dms_notification_outbox/n3').set({ monitored_uid: PARTNER, recipient_uid: OTHER });

  // Guest transition records/lease + API tokens.
  await db.doc('guestTransitions/g1').set({ guestUid: USER, state: 'FINALIZED' });
  await db.doc('guestTransitions/g2').set({ guestUid: PARTNER, state: 'PREPARED', createdAt: Timestamp.now() });
  await db.doc(`guestTransitionLeases/${USER}`).set({ state: 'FINALIZED' });
  await db.doc('pairing_tokens/tok1').set({ creatorUid: USER, expiresAt: Timestamp.now() });
  await db.doc('pairing_tokens/tok2').set({ creatorUid: PARTNER, partnerUid: USER, expiresAt: Timestamp.now() });
  await db.doc('pairing_tokens/tok3').set({ creatorUid: PARTNER, expiresAt: Timestamp.now() });

  // Server counters.
  for (const doc of [
    `rate-limits/pairing_${USER}`,
    `rate-limits/invitation_${USER}`,
    `rate-limits/guest_transition_${USER}`,
    `eventRateLimits/user_${USER}`,
    `eventRateLimits/pair_${PAIR_A}`,
    `rate-limits/pairing_${PARTNER}`,
  ]) {
    await db.doc(doc).set({ window: 1 });
  }
}

let fakeAuth;
let enqueueSpy;

before(() => {
  app = initializeApp({ projectId: PROJECT_ID, storageBucket: BUCKET }, 'account-deletion-emulator-suite');
  db = getFirestore(app);
  bucket = getStorage(app).bucket(BUCKET);
});

after(async () => {
  await deleteApp(app);
});

beforeEach(async () => {
  await clearAll();
  fakeAuth = createFakeAuth([USER, PARTNER, OTHER]);
  enqueueSpy = createEnqueueSpy();
});

function handlers() {
  return {
    requestAccount: createRequestAccountDeletionHandler({
      db, authAdmin: fakeAuth, enqueueTask: enqueueSpy.enqueue, now: () => nowMs,
    }),
    requestGuest: createRequestGuestDataDeletionHandler({
      db, authAdmin: fakeAuth, enqueueTask: enqueueSpy.enqueue,
    }),
    process: createProcessAccountDeletionHandler({
      db, authAdmin: fakeAuth, bucket, now: () => nowMs,
    }),
    reconcile: createReconcileDeletionJobsHandler({
      db, enqueueTask: enqueueSpy.enqueue, now: () => nowMs,
    }),
    backstop: createAuthDeletionBackstopHandler({ db, enqueueTask: enqueueSpy.enqueue }),
  };
}

// --- requestAccountDeletion ----------------------------------------------------------------

test('requestAccountDeletion requires a recent, matching, verified raw token', async () => {
  await seedWorld();
  const { requestAccount } = handlers();
  const good = encodeToken(USER, { authTime: nowSec() });
  const data = { confirmation: ACCOUNT_CONFIRMATION };

  await expectRejection(requestAccount({ auth: null, data }), 'unauthenticated');
  await expectRejection(
    requestAccount(request(USER, null, data)),
    'unauthenticated', 'RAW_TOKEN_REQUIRED',
  );
  await expectRejection(
    requestAccount(request(USER, 'garbage', data)),
    'unauthenticated', 'TOKEN_INVALID',
  );
  await expectRejection(
    requestAccount(request(PARTNER, good, data)),
    'permission-denied', 'UID_MISMATCH',
  );
  await expectRejection(
    requestAccount(request(USER, encodeToken(USER, { authTime: nowSec() - 301 }), data)),
    'failed-precondition', 'REAUTH_REQUIRED',
  );
  await expectRejection(
    requestAccount(request(USER, encodeToken(USER, { authTime: nowSec(), provider: 'password', verified: false }), data)),
    'failed-precondition', 'EMAIL_NOT_VERIFIED',
  );
  await expectRejection(
    requestAccount(request(USER, good, { confirmation: 'DELETE' })),
    'invalid-argument', 'CONFIRMATION_MISMATCH',
  );
  await expectRejection(
    requestAccount(request(USER, good, { confirmation: ACCOUNT_CONFIRMATION, force: true })),
    'invalid-argument', 'INVALID_INPUT',
  );
  await expectRejection(
    requestAccount(request(USER, encodeToken(USER, { authTime: nowSec(), provider: 'anonymous' }), data)),
    'failed-precondition', 'PERSISTENT_ACCOUNT_REQUIRED',
  );

  assert.strictEqual((await db.doc(`deletionJobs/${USER}`).get()).exists, false);
  assert.strictEqual((await db.doc(`users/${USER}`).get()).get('status'), 'ACTIVE');
});

test('acceptance writes the tombstone, disables/revokes, and enqueues exactly one worker', async () => {
  await seedWorld();
  const { requestAccount } = handlers();
  const good = encodeToken(USER, { authTime: nowSec() });

  const result = await requestAccount(request(USER, good, { confirmation: ACCOUNT_CONFIRMATION }));
  assert.deepStrictEqual(result, { state: PHASES.QUEUED });

  const job = (await db.doc(`deletionJobs/${USER}`).get()).data();
  assert.strictEqual(job.kind, 'ACCOUNT_DELETE');
  assert.strictEqual(job.phase, PHASES.QUEUED);
  assert.strictEqual(job.uid, USER);
  assert.ok(job.operationId);
  assert.strictEqual((await db.doc(`users/${USER}`).get()).get('status'), 'PENDING_DELETION');
  assert.ok(fakeAuth.state.disabled.has(USER));
  assert.ok(fakeAuth.state.revoked.has(USER));
  assert.deepStrictEqual(enqueueSpy.calls, [USER]);

  // Replay: no second job, same operation id, still no error.
  const replay = await requestAccount(request(USER, good, { confirmation: ACCOUNT_CONFIRMATION }));
  assert.deepStrictEqual(replay, { state: PHASES.QUEUED });
  const after = (await db.doc(`deletionJobs/${USER}`).get()).data();
  assert.strictEqual(after.operationId, job.operationId);
});

test('a lost enqueue still accepts the durable job', async () => {
  await seedWorld();
  const failing = async () => { throw new Error('tasks unavailable'); };
  const handler = createRequestAccountDeletionHandler({
    db, authAdmin: fakeAuth, enqueueTask: failing, now: () => nowMs,
  });
  const result = await handler(request(USER, encodeToken(USER, { authTime: nowSec() }), {
    confirmation: ACCOUNT_CONFIRMATION,
  }));
  assert.deepStrictEqual(result, { state: PHASES.QUEUED });
  assert.ok((await db.doc(`deletionJobs/${USER}`).get()).exists);
  assert.strictEqual((await db.doc(`users/${USER}`).get()).get('status'), 'PENDING_DELETION');
});

test('guest deletion is anonymous-only, phrase-confirmed and profile-free', async () => {
  const { requestGuest } = handlers();
  const good = { confirmation: GUEST_CONFIRMATION };

  await expectRejection(
    requestGuest(request(PARTNER, null, good, 'google.com')),
    'permission-denied', 'ANONYMOUS_ONLY',
  );
  await expectRejection(
    requestGuest(request(PARTNER, null, { confirmation: ACCOUNT_CONFIRMATION }, 'anonymous')),
    'invalid-argument', 'CONFIRMATION_MISMATCH',
  );

  await db.doc(`users/${PARTNER}`).delete();
  const result = await requestGuest(request(PARTNER, null, { confirmation: GUEST_CONFIRMATION }, 'anonymous'));
  assert.deepStrictEqual(result, { state: PHASES.QUEUED });
  const job = (await db.doc(`deletionJobs/${PARTNER}`).get()).data();
  assert.strictEqual(job.kind, 'GUEST_DELETE');
  assert.ok(fakeAuth.state.disabled.has(PARTNER));
});

// --- processAccountDeletion ---------------------------------------------------------------

test('the worker terminates pairs, scrubs uid fields and leaves the partner intact', async () => {
  await seedWorld();
  const { requestAccount, process } = handlers();
  await requestAccount(request(USER, encodeToken(USER, { authTime: nowSec() }), {
    confirmation: ACCOUNT_CONFIRMATION,
  }));

  const result = await process({ data: { uid: USER } });
  assert.deepStrictEqual(result, { state: PHASES.COMPLETED });

  const pairA = (await db.doc(`pairings/${PAIR_A}`).get()).data();
  assert.strictEqual(pairA.status, 'terminated');
  assert.strictEqual(pairA.terminatedBy, USER);
  assert.strictEqual(pairA.terminationReason, 'ACCOUNT_DELETED');
  assert.strictEqual(pairA.proofs[USER], undefined);
  assert.ok(pairA.proofs[PARTNER]);
  assert.strictEqual(pairA.availabilityConsent[USER], undefined);
  assert.ok(pairA.availabilityConsent[PARTNER]);
  const event = (await db.doc(`pairings/${PAIR_A}/system_events/pair_terminated`).get()).data();
  assert.strictEqual(event.type, 'PAIR_TERMINATED');
  assert.strictEqual(event.actorUid, USER);

  // B was already terminated: status untouched, but the uid's proof is gone.
  const pairB = (await db.doc(`pairings/${PAIR_B}`).get()).data();
  assert.strictEqual(pairB.status, 'terminated');
  assert.strictEqual(pairB.proofs[USER], undefined);
  assert.ok(pairB.proofs[OTHER]);

  // Pair C never contained the uid: untouched, proofs intact.
  const pairC = (await db.doc(`pairings/${PAIR_C}`).get()).data();
  assert.strictEqual(pairC.status, 'active');
  assert.ok(pairC.proofs[PARTNER]);
  assert.ok(pairC.proofs[OTHER]);

  // The partner's records survive.
  assert.ok((await db.doc(`pairings/${PAIR_A}/nudgeSettings/${PARTNER}`).get()).exists);
  assert.ok((await db.doc(`pairings/${PAIR_A}/nudgeState/${PARTNER}`).get()).exists);
  assert.ok((await db.doc(`mailboxes/${PARTNER}/messages/mFromUser`).get()).exists);
  assert.ok((await db.doc('relay-dispatch/mFromUser/installations/i2').get()).exists);
  assert.ok((await db.doc(`backupReservations/${PARTNER}/reservations/p1`).get()).exists);
  assert.ok((await bucket.file(`backups/${PARTNER}/p1`).exists())[0]);
  assert.ok((await db.doc(`dms_install_state/${PARTNER}_inst2`).get()).exists);
  assert.ok((await db.doc('dms_notification_outbox/n3').get()).exists);
  assert.ok((await db.doc('guestTransitions/g2').get()).exists);
  assert.ok((await db.doc('pairing_tokens/tok3').get()).exists);
  assert.ok((await db.doc(`rate-limits/pairing_${PARTNER}`).get()).exists);

  // The uid's private world is gone.
  assert.strictEqual((await db.doc(`users/${USER}`).get()).exists, false);
  assert.strictEqual((await db.doc(`users/${USER}/push_installations/inst1`).get()).exists, false);
  assert.strictEqual((await db.doc(`users/${USER}/backupManifests/b1`).get()).exists, false);
  assert.strictEqual((await db.doc(`mailboxes/${USER}`).get()).exists, false);
  assert.strictEqual((await db.doc('relay-dispatch/m1').get()).exists, false);
  assert.strictEqual((await db.doc(`backupReservations/${USER}`).get()).exists, false);
  assert.strictEqual((await db.doc(`dms_install_state/${USER}_inst1`).get()).exists, false);
  assert.strictEqual((await db.doc('dms_notification_outbox/n1').get()).exists, false);
  assert.strictEqual((await db.doc('dms_notification_outbox/n2').get()).exists, false);
  assert.strictEqual((await db.doc('guestTransitions/g1').get()).exists, false);
  assert.strictEqual((await db.doc(`guestTransitionLeases/${USER}`).get()).exists, false);
  assert.strictEqual((await db.doc('pairing_tokens/tok1').get()).exists, false);
  assert.strictEqual((await db.doc('pairing_tokens/tok2').get()).exists, false);
  assert.strictEqual((await db.doc(`rate-limits/pairing_${USER}`).get()).exists, false);
  assert.strictEqual((await db.doc(`eventRateLimits/user_${USER}`).get()).exists, false);
  assert.strictEqual((await db.doc(`pairings/${PAIR_A}/nudges/nudgeFromUser`).get()).exists, false);
  assert.strictEqual((await db.doc(`pairings/${PAIR_A}/nudges/nudgeToUser`).get()).exists, false);
  assert.strictEqual((await db.doc('nudgePushQueue/nudgeFromUser').get()).exists, false);
  assert.strictEqual((await db.doc('nudgePushQueue/nudgeToUser').get()).exists, false);
  assert.strictEqual((await db.doc(`pairings/${PAIR_A}/nudgeSettings/${USER}`).get()).exists, false);
  assert.strictEqual((await db.doc(`pairings/${PAIR_A}/nudgeState/${USER}`).get()).exists, false);

  // Storage prefix emptied at the generation that was listed.
  const [files] = await bucket.getFiles({ prefix: `backups/${USER}/` });
  assert.strictEqual(files.length, 0);

  // Auth user gone; job completed with a completion timestamp.
  assert.ok(!fakeAuth.state.users.has(USER));
  const job = (await db.doc(`deletionJobs/${USER}`).get()).data();
  assert.strictEqual(job.phase, PHASES.COMPLETED);
  assert.ok(job.completedAt);
});

test('the worker is idempotent and treats a missing Auth user as completed', async () => {
  await seedWorld();
  const { requestAccount, process } = handlers();
  await requestAccount(request(USER, encodeToken(USER, { authTime: nowSec() }), {
    confirmation: ACCOUNT_CONFIRMATION,
  }));
  await process({ data: { uid: USER } });
  const second = await process({ data: { uid: USER } });
  assert.deepStrictEqual(second, { state: PHASES.COMPLETED });

  // The Auth user may already be gone (console deletion, earlier retry): not-found is success.
  await seedWorld();
  fakeAuth.state.users.delete(USER);
  await db.doc(`deletionJobs/${USER}`).set({ uid: USER, kind: 'ACCOUNT_DELETE', phase: 'QUEUED' });
  const third = await process({ data: { uid: USER } });
  assert.deepStrictEqual(third, { state: PHASES.COMPLETED });
});

test('the worker rejects a malformed payload and a job-less uid', async () => {
  await seedWorld();
  const { process } = handlers();
  await assert.rejects(process({ data: { uid: 'a/b' } }), /INVALID_TASK_PAYLOAD/);
  const none = await process({ data: { uid: 'noSuchUser' } });
  assert.deepStrictEqual(none, { state: 'NO_JOB' });
});

// --- reconcileDeletionJobs ----------------------------------------------------------------

test('reconciliation re-enqueues stale jobs, purges aged tombstones and keeps fresh ones', async () => {
  const old = Timestamp.fromMillis(nowMs - 60 * 60 * 1000);
  const { reconcile } = handlers();

  await db.doc(`deletionJobs/staleUser`).set({
    uid: 'staleUser', kind: 'ACCOUNT_DELETE', phase: PHASES.PAIRS, updatedAt: old,
  });
  await db.doc(`deletionJobs/agedTombstone`).set({
    uid: 'agedTombstone', kind: 'ACCOUNT_DELETE', phase: PHASES.COMPLETED,
    updatedAt: old,
    completedAt: Timestamp.fromMillis(nowMs - 31 * 24 * 60 * 60 * 1000),
  });
  await db.doc(`deletionJobs/freshTombstone`).set({
    uid: 'freshTombstone', kind: 'ACCOUNT_DELETE', phase: PHASES.COMPLETED,
    updatedAt: old, completedAt: Timestamp.fromMillis(nowMs - 60 * 1000),
  });
  await db.doc(`deletionJobs/freshJob`).set({
    uid: 'freshJob', kind: 'ACCOUNT_DELETE', phase: PHASES.PAIRS,
    updatedAt: Timestamp.fromMillis(nowMs - 1000),
  });
  // A stale unfinalized guest transition must be alerted, never deleted.
  await db.doc('guestTransitions/staleTransition').set({
    guestUid: 'someGuest', state: 'PREPARED',
    createdAt: Timestamp.fromMillis(nowMs - 48 * 60 * 60 * 1000),
  });

  await reconcile();

  assert.deepStrictEqual(enqueueSpy.calls, ['staleUser']);
  const stale = (await db.doc('deletionJobs/staleUser').get()).data();
  assert.strictEqual(stale.requeueCount, 1);
  assert.ok(stale.updatedAt.toMillis() >= nowMs - 1000);
  assert.strictEqual((await db.doc('deletionJobs/agedTombstone').get()).exists, false);
  assert.ok((await db.doc('deletionJobs/freshTombstone').get()).exists);
  assert.strictEqual((await db.doc('deletionJobs/freshJob').get()).get('requeueCount'), undefined);
  assert.ok((await db.doc('guestTransitions/staleTransition').get()).exists);
});

test('reconciliation stops requeueing at the cap', async () => {
  const { reconcile } = handlers();
  await db.doc(`deletionJobs/stuckUser`).set({
    uid: 'stuckUser', kind: 'ACCOUNT_DELETE', phase: PHASES.AUTH,
    updatedAt: Timestamp.fromMillis(nowMs - 60 * 60 * 1000), requeueCount: 12,
  });
  await reconcile();
  assert.deepStrictEqual(enqueueSpy.calls, []);
});

// --- out-of-band backstop -----------------------------------------------------------------

test('an Auth deletion outside the client flow creates a job and enqueues the worker', async () => {
  const { backstop } = handlers();
  const failing = async () => { throw new Error('tasks down'); };
  const handler = createAuthDeletionBackstopHandler({ db, enqueueTask: failing });

  await handler({ uid: 'consoleUser' });
  const job = (await db.doc('deletionJobs/consoleUser').get()).data();
  assert.strictEqual(job.kind, 'AUTH_DELETION_BACKSTOP');
  assert.strictEqual(job.phase, PHASES.QUEUED);

  await handler({ uid: 'consoleUser' });
  assert.strictEqual((await db.doc('deletionJobs/consoleUser').get()).exists, true);

  await db.doc('deletionJobs/doneUser').set({ phase: PHASES.COMPLETED });
  await backstop({ uid: 'doneUser' });
  assert.deepStrictEqual(enqueueSpy.calls, []);
});

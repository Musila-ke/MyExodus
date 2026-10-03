// Partner SOS (V1 Req 35) security-rules suite — plan §12.1 R-F1…R-F8.
// Same harness as firestore-rules.test.mjs: `npm test` from rules-test/ runs both files
// under the Firestore emulator. Nudge/queue/state/config documents are Admin-SDK-only
// writes, so seeding goes through withSecurityRulesDisabled — exactly what the callable
// would write — and every assertion exercises the client surface.
import { after, before, beforeEach, test } from 'node:test';
import { readFileSync } from 'node:fs';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  limit,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
} from 'firebase/firestore';

const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';
const PAIR = 'pair-1';
const NUDGE = 'nudge-1';
const DAY_MS = 24 * 60 * 60 * 1000;

let env;

// Plan §4.3 document shape; the rules read recipientUid / expiresAt / ackedAt only.
const nudgeDoc = (overrides = {}) => ({
  senderUid: ALICE,
  recipientUid: BOB,
  envelope: 'AAECAw==',
  envelopeVersion: 1,
  createdAt: new Date(),
  createdAtMs: Date.now(),
  expiresAt: new Date(Date.now() + 7 * DAY_MS),
  status: 'sent',
  suppressedAt: null,
  ackedAt: null,
  pushAttempts: 0,
  lastPushAtMs: null,
  targetDeviceCount: 1,
  sentDeviceCount: 1,
  lastPushErrorCode: null,
  terminalAt: null,
  ...overrides,
});

const nudgeRef = (ctx, nudgeId = NUDGE, pairId = PAIR) =>
  doc(ctx.firestore(), `pairings/${pairId}/nudges/${nudgeId}`);

const seedNudge = (overrides = {}, nudgeId = NUDGE, pairId = PAIR) =>
  env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(nudgeRef(ctx, nudgeId, pairId), nudgeDoc(overrides));
  });

before(async () => {
  // Own project namespace: `node --test` runs files in parallel processes, and each file's
  // beforeEach clearFirestore() would otherwise wipe the other file's seeds (caught when
  // this suite was added beside firestore-rules.test.mjs).
  env = await initializeTestEnvironment({
    projectId: 'demo-myexodus-sos',
    firestore: {
      rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `pairings/${PAIR}`), {
      members: [ALICE, BOB],
      status: 'active',
    });
  });
});

after(async () => {
  await env.cleanup();
});

// --- R-F1: sender cannot create/delete/update nudge documents ---

test('R-F1: a client cannot create, update, or delete a nudge', async () => {
  const alice = env.authenticatedContext(ALICE);
  await assertFails(setDoc(nudgeRef(alice, 'forged'), nudgeDoc()));

  await seedNudge();
  // The sender is a pair member, still not the recipient — reads and writes stay denied.
  await assertFails(updateDoc(nudgeRef(alice), { status: 'acked' }));
  await assertFails(deleteDoc(nudgeRef(alice)));
});

// --- R-F2: read requires recipient identity + active pair + unexpired ---

test('R-F2: the recipient reads their active, unexpired nudge', async () => {
  await seedNudge();
  const bob = env.authenticatedContext(BOB);
  await assertSucceeds(getDoc(nudgeRef(bob)));
});

test('R-F2: the sender (a pair member) cannot read the nudge', async () => {
  await seedNudge();
  const alice = env.authenticatedContext(ALICE);
  await assertFails(getDoc(nudgeRef(alice)));
});

test('R-F2: a non-member cannot read the nudge', async () => {
  await seedNudge();
  const carol = env.authenticatedContext(CAROL);
  await assertFails(getDoc(nudgeRef(carol)));
});

test('R-F2: a nudge addressed to someone else is not readable', async () => {
  await seedNudge({ recipientUid: CAROL });
  const bob = env.authenticatedContext(BOB);
  await assertFails(getDoc(nudgeRef(bob)));
});

test('R-F2: reads are denied once the pair is terminated', async () => {
  await seedNudge();
  await env.withSecurityRulesDisabled(async (ctx) => {
    await updateDoc(doc(ctx.firestore(), `pairings/${PAIR}`), { status: 'terminated' });
  });
  const bob = env.authenticatedContext(BOB);
  await assertFails(getDoc(nudgeRef(bob)));
});

test('R-F2: reads are denied after expiry', async () => {
  await seedNudge({ expiresAt: new Date(Date.now() - 60_000) });
  const bob = env.authenticatedContext(BOB);
  await assertFails(getDoc(nudgeRef(bob)));
});

test('R-F2: a nudge under a nonexistent pairing is denied (exists() guard)', async () => {
  await seedNudge({}, NUDGE, 'ghost-pair');
  const bob = env.authenticatedContext(BOB);
  await assertFails(getDoc(nudgeRef(bob, NUDGE, 'ghost-pair')));
});

test('R-F2: an unauthenticated reader is denied', async () => {
  await seedNudge();
  const anon = env.unauthenticatedContext();
  await assertFails(getDoc(nudgeRef(anon)));
});

// --- R-F2q: the recipient's Home LIST query (§6.9) against the read rule ---
// Plan §6.9's query is the exact shape NudgeInboxRepository will issue:
//   where recipientUid == uid, suppressedAt == null, ackedAt == null,
//   orderBy createdAt desc, limit 10.
// The list rule deliberately omits the expiresAt gate the get rule carries: the engine
// cannot evaluate a request.time comparison against a field the query does not constrain
// on a potential result set, and the plan puts expiry filtering on the client query
// (§6.9 / §17). These tests pin that split.

const nudgesCol = (ctx, pairId = PAIR) => collection(ctx.firestore(), `pairings/${pairId}/nudges`);

const inboxQuery = (ctx, uid) =>
  query(
    nudgesCol(ctx),
    where('recipientUid', '==', uid),
    where('suppressedAt', '==', null),
    where('ackedAt', '==', null),
    orderBy('createdAt', 'desc'),
    limit(10),
  );

test('R-F2q: the exact §6.9 recipient query succeeds', async () => {
  await seedNudge();
  await seedNudge({}, 'nudge-2');
  const bob = env.authenticatedContext(BOB);
  const snap = await assertSucceeds(getDocs(inboxQuery(bob, BOB)));
  // The emulator does not enforce composite indexes; the constraint here is only that the
  // rules engine admits the query shape — and that both seeded docs came back.
  if (snap.docs.length !== 2) throw new Error(`expected 2 docs, got ${snap.docs.length}`);
});

test('R-F2q: the list query returns a past-expiry TTL-lagging doc — the client must ignore it', async () => {
  // TTL deletion lags up to ~24h; the list rule cannot filter expiry (see above), so the
  // contract is: the query succeeds AND the expired doc is in the result set, and
  // NudgeInboxRepository drops it client-side (§6.9).
  await seedNudge({ expiresAt: new Date(Date.now() - 60_000) }, 'nudge-expired');
  await seedNudge({}, 'nudge-live');
  const bob = env.authenticatedContext(BOB);
  const snap = await assertSucceeds(getDocs(inboxQuery(bob, BOB)));
  if (snap.docs.length !== 2) throw new Error(`expected 2 docs (expired included), got ${snap.docs.length}`);
});

test('R-F2q: the get rule still refuses a past-expiry doc', async () => {
  await seedNudge({ expiresAt: new Date(Date.now() - 60_000) }, 'nudge-expired');
  const bob = env.authenticatedContext(BOB);
  await assertFails(getDoc(nudgeRef(bob, 'nudge-expired')));
});

test('R-F2q: a query aimed at someone else\'s nudges is denied', async () => {
  await seedNudge();
  // Rules are not filters in the other direction either: the engine admits a query only
  // when the constraints make the rule provable. Alice (the sender) filtering by her own
  // uid is provable — and returns zero docs, because the constraint excludes the actual
  // data. The leak attempt is filtering by the recipient's uid: unprovable → denied.
  const alice = env.authenticatedContext(ALICE);
  await assertFails(getDocs(inboxQuery(alice, BOB)));
  const own = await assertSucceeds(getDocs(inboxQuery(alice, ALICE)));
  if (own.docs.length !== 0) throw new Error(`expected constraint to exclude all docs, got ${own.docs.length}`);

  const carol = env.authenticatedContext(CAROL);
  await assertFails(getDocs(inboxQuery(carol, BOB)));
});

test('R-F2q: an unfiltered recipient query is not provable and fails', async () => {
  await seedNudge();
  const bob = env.authenticatedContext(BOB);
  await assertFails(getDocs(nudgesCol(bob)));
});

// --- R-F3: the recipient's ack mutates only ackedAt ---

test('R-F3: the recipient acknowledges with exactly ackedAt', async () => {
  await seedNudge();
  const bob = env.authenticatedContext(BOB);
  await assertSucceeds(updateDoc(nudgeRef(bob), { ackedAt: serverTimestamp() }));
});

test('R-F3: the ack cannot smuggle field changes', async () => {
  await seedNudge();
  const bob = env.authenticatedContext(BOB);
  await assertFails(updateDoc(nudgeRef(bob), { ackedAt: serverTimestamp(), status: 'acked' }));
  await assertFails(updateDoc(nudgeRef(bob), { status: 'acked' }));
  await assertFails(updateDoc(nudgeRef(bob), { ackedAt: serverTimestamp(), envelope: 'tampered' }));
});

test('R-F3: ackedAt must be the server timestamp, not a client clock', async () => {
  await seedNudge();
  const bob = env.authenticatedContext(BOB);
  await assertFails(updateDoc(nudgeRef(bob), { ackedAt: new Date() }));
});

test('R-F3: the sender cannot acknowledge for the recipient', async () => {
  await seedNudge();
  const alice = env.authenticatedContext(ALICE);
  await assertFails(updateDoc(nudgeRef(alice), { ackedAt: serverTimestamp() }));
});

// --- R-F4: acknowledgment is exactly-once ---

test('R-F4: a second acknowledgment mutation is rejected', async () => {
  await seedNudge();
  const bob = env.authenticatedContext(BOB);
  await assertSucceeds(updateDoc(nudgeRef(bob), { ackedAt: serverTimestamp() }));
  await assertFails(updateDoc(nudgeRef(bob), { ackedAt: serverTimestamp() }));
});

test('R-F4: a pre-acknowledged nudge (admin-seeded) cannot be re-acked', async () => {
  await seedNudge({ ackedAt: new Date() });
  const bob = env.authenticatedContext(BOB);
  await assertFails(updateDoc(nudgeRef(bob), { ackedAt: serverTimestamp() }));
});

// --- R-F5: queue / rate state / config are client-inaccessible ---

test('R-F5: clients cannot read or write the push queue, rate state, or config', async () => {
  const alice = env.authenticatedContext(ALICE);
  await assertFails(
    setDoc(doc(alice.firestore(), `nudgePushQueue/${NUDGE}`), { pairId: PAIR, createdAtMs: Date.now() }),
  );
  await assertFails(getDoc(doc(alice.firestore(), `nudgePushQueue/${NUDGE}`)));

  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `pairings/${PAIR}/nudgeState/${ALICE}`), {
      lastAcceptedAtMs: 1,
      lastNudgeId: NUDGE,
      dayKey: '2026-10-03',
      dayCount: 1,
    });
    await setDoc(doc(ctx.firestore(), 'config/features'), { partnerSosEnabled: false });
  });
  await assertFails(
    setDoc(doc(alice.firestore(), `pairings/${PAIR}/nudgeState/${ALICE}`), { dayCount: 0 }),
  );
  await assertFails(getDoc(doc(alice.firestore(), `pairings/${PAIR}/nudgeState/${ALICE}`)));
  await assertFails(getDoc(doc(alice.firestore(), 'config/features')));
  await assertFails(setDoc(doc(alice.firestore(), 'config/features'), { partnerSosEnabled: true }));
});

// --- R-F6: block-setting schema + ownership ---

const settingsRef = (ctx, uid, pairId = PAIR) =>
  doc(ctx.firestore(), `pairings/${pairId}/nudgeSettings/${uid}`);

test('R-F6: the owner reads and writes their own block setting on an active pair', async () => {
  const bob = env.authenticatedContext(BOB);
  await assertSucceeds(setDoc(settingsRef(bob, BOB), { blocked: true, updatedAt: serverTimestamp() }));
  await assertSucceeds(getDoc(settingsRef(bob, BOB)));
  await assertSucceeds(updateDoc(settingsRef(bob, BOB), { blocked: false, updatedAt: serverTimestamp() }));
});

test('R-F6: only the owner touches their setting document', async () => {
  const alice = env.authenticatedContext(ALICE);
  // Alice is a pair member; the uid-in-path ownership check still denies her.
  await assertFails(setDoc(settingsRef(alice, BOB), { blocked: true, updatedAt: serverTimestamp() }));
  await assertFails(getDoc(settingsRef(alice, BOB)));
});

test('R-F6: the setting schema is boolean blocked + server updatedAt, nothing else', async () => {
  const bob = env.authenticatedContext(BOB);
  await assertFails(setDoc(settingsRef(bob, BOB), { blocked: 'yes', updatedAt: serverTimestamp() }));
  await assertFails(setDoc(settingsRef(bob, BOB), { blocked: true }));
  await assertFails(setDoc(settingsRef(bob, BOB), { blocked: true, updatedAt: new Date() }));
  await assertFails(
    setDoc(settingsRef(bob, BOB), { blocked: true, updatedAt: serverTimestamp(), extra: 1 }),
  );
});

test('R-F6: a non-member cannot write a block setting even on an existing pair', async () => {
  const carol = env.authenticatedContext(CAROL);
  await assertFails(setDoc(settingsRef(carol, CAROL), { blocked: true, updatedAt: serverTimestamp() }));
});

test('R-F6: reading a never-written setting is admitted — absence is the unblocked default', async () => {
  const alice = env.authenticatedContext(ALICE);
  // Alice's own document was never written. The read rule names no resource field, so the
  // read is admitted; the client reads absence as blocked=false (Step 11 default).
  await assertSucceeds(getDoc(settingsRef(alice, ALICE)));
});

test('R-F6: the block setting is frozen once the pair is terminated', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await updateDoc(doc(ctx.firestore(), `pairings/${PAIR}`), { status: 'terminated' });
  });
  const bob = env.authenticatedContext(BOB);
  await assertFails(setDoc(settingsRef(bob, BOB), { blocked: true, updatedAt: serverTimestamp() }));
});

// --- R-F7: device registration owner/schema (SOS delta = the appVersion cap + pushEligible) ---

const installationDoc = (overrides = {}) => ({
  provider: 'fcm',
  token: 'tok-1',
  platform: 'android',
  appVersion: '1.0',
  updatedAt: serverTimestamp(),
  createdAt: serverTimestamp(),
  ...overrides,
});

test('R-F7: appVersion is capped at 32 characters', async () => {
  const bob = env.authenticatedContext(BOB);
  const ref = doc(bob.firestore(), `users/${BOB}/push_installations/dev1`);
  await assertSucceeds(setDoc(ref, installationDoc({ appVersion: '1'.repeat(32) })));
  await assertFails(setDoc(ref, installationDoc({ appVersion: '1'.repeat(33) })));
  await assertFails(setDoc(ref, installationDoc({ appVersion: 42 })));
});

test('R-F7: pushEligible is accepted as a bool or absent, rejected as anything else', async () => {
  const bob = env.authenticatedContext(BOB);
  const ref = doc(bob.firestore(), `users/${BOB}/push_installations/dev1`);
  // Absent (pre-SOS client) and both bool values pass.
  await assertSucceeds(setDoc(ref, installationDoc()));
  await assertSucceeds(setDoc(ref, installationDoc({ pushEligible: true })));
  await assertSucceeds(setDoc(ref, installationDoc({ pushEligible: false })));
  await assertFails(setDoc(ref, installationDoc({ pushEligible: 'yes' })));
  await assertFails(setDoc(ref, installationDoc({ pushEligible: 1 })));
});

// --- R-F8: pair membership gates every SOS surface ---

test('R-F8: membership is required on a *different* active pair too', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'pairings/pair-other'), { members: [CAROL, BOB], status: 'active' });
  });
  // Nudge under the other pair, addressed to Bob: Carol is a member but not the recipient.
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(
      doc(ctx.firestore(), 'pairings/pair-other/nudges/n1'),
      nudgeDoc({ senderUid: CAROL, recipientUid: BOB }),
    );
  });
  const carol = env.authenticatedContext(CAROL);
  await assertFails(getDoc(doc(carol.firestore(), 'pairings/pair-other/nudges/n1')));

  // Alice has no relation to pair-other at all.
  const alice = env.authenticatedContext(ALICE);
  await assertFails(getDoc(doc(alice.firestore(), 'pairings/pair-other/nudges/n1')));
  await assertFails(
    setDoc(doc(alice.firestore(), 'pairings/pair-other/nudgeSettings/' + ALICE), {
      blocked: true,
      updatedAt: serverTimestamp(),
    }),
  );
});

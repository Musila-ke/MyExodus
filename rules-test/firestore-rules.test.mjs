// Firestore security-rules emulator suite — plan §12 "Security rules (Firebase emulator)".
// The §13 CI gate: this suite must pass before every rules deployment (task #25 gate).
//
// Account plan Release 0 step 3 postures covered here:
// - mailbox (event_chain) and system_events: client writes denied, reads shape unchanged;
// - pairings: read-only for participants, lifecycle writes only through callables;
// - users profile root: owner read, never client-written;
// - pairing_tokens: read/delete by token possession only, mint/response callable-only;
// - clientCompatibility/current: public read, no write;
// - every server-owned account/backup collection: default deny.
//
// Plan §12 rows (1) and (2) — "paired GMS user with a valid App Check token can write" and
// "paired HMS/no-GMS user without an App Check token can still write (AD-03b)" — collapse to
// the SAME evaluation here: App Check enforcement is a Firestore service-level setting
// (monitor -> enforce, §13/AD-03), never a rules clause, so the emulator cannot and should
// not distinguish them. The no-GMS exemption is the absence of any App Check clause in
// firestore.rules.
import { after, before, beforeEach, test } from 'node:test';
import { readFileSync } from 'node:fs';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import { deleteDoc, doc, getDoc, serverTimestamp, setDoc, updateDoc } from 'firebase/firestore';

const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';
const PAIR = 'pair-1';

let env;

const mailboxMessage = (overrides = {}) => ({
  senderId: 'sender-key-id',
  senderUid: ALICE,
  pairId: PAIR,
  payload: 'AAECAw==',
  keyEpoch: 0,
  timestamp: new Date(),
  ...overrides,
});

const messageRef = (ctx, messageId) => doc(ctx.firestore(), `mailboxes/${BOB}/messages/${messageId}`);

// Events are appended only by the appendUserEvent callable (Admin SDK), so fixtures that
// need an existing message seed it past the rules.
const seedMessage = (messageId, overrides = {}) =>
  env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `mailboxes/${BOB}/messages/${messageId}`), mailboxMessage(overrides));
  });

const seedToken = (tokenId, expiresAt) =>
  env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `pairing_tokens/${tokenId}`), {
      creatorUid: ALICE,
      expiresAt,
      payload: { b64: 'initiator-material' },
    });
  });

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-myexodus',
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

// --- event_chain (mailbox): callable-only append ---

test('direct client mailbox create is denied for everyone, valid payload or not', async () => {
  const alice = env.authenticatedContext(ALICE);
  await assertFails(setDoc(messageRef(alice, 'm1'), mailboxMessage()));
  const carol = env.authenticatedContext(CAROL);
  await assertFails(setDoc(messageRef(carol, 'm1'), mailboxMessage({ senderUid: CAROL })));
});

test('mailbox reads keep recipient/author isolation', async () => {
  await seedMessage('m1');
  const alice = env.authenticatedContext(ALICE);
  const bob = env.authenticatedContext(BOB);
  const carol = env.authenticatedContext(CAROL);

  await assertSucceeds(getDoc(messageRef(bob, 'm1'))); // recipient
  await assertSucceeds(getDoc(messageRef(alice, 'm1'))); // author (relayStatus evidence)
  await assertFails(getDoc(messageRef(carol, 'm1')));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), `mailboxes/${BOB}/messages/m1`)));
});

test('mailbox messages are immutable and client-undeletable (AD-18b)', async () => {
  await seedMessage('m1');
  const bob = env.authenticatedContext(BOB);
  await assertFails(updateDoc(messageRef(bob, 'm1'), { payload: 'tampered' }));
  await assertFails(deleteDoc(messageRef(bob, 'm1')));
});

test('only the recipient acks a mailbox message', async () => {
  await seedMessage('m1');
  const bob = env.authenticatedContext(BOB);
  await assertSucceeds(
    setDoc(doc(bob.firestore(), `mailboxes/${BOB}/messages/m1/acks/dev1`), { ackedAt: serverTimestamp() }),
  );
  const carol = env.authenticatedContext(CAROL);
  await assertFails(
    setDoc(doc(carol.firestore(), `mailboxes/${BOB}/messages/m1/acks/dev2`), { ackedAt: serverTimestamp() }),
  );
});

// --- AD-13b: the request's author reads the acks proving their deletion was processed ---

test('message author can read the acks on their own message (REMOTE_SHRED_CONFIRMED evidence)', async () => {
  await seedMessage('m-del');
  const bob = env.authenticatedContext(BOB);
  await assertSucceeds(
    setDoc(doc(bob.firestore(), `mailboxes/${BOB}/messages/m-del/acks/dev1`), { ackedAt: serverTimestamp() }),
  );

  const alice = env.authenticatedContext(ALICE);
  await assertSucceeds(getDoc(doc(alice.firestore(), `mailboxes/${BOB}/messages/m-del/acks/dev1`)));
  // The recipient's own read is unchanged.
  await assertSucceeds(getDoc(doc(bob.firestore(), `mailboxes/${BOB}/messages/m-del/acks/dev1`)));
  // A non-author non-recipient stays denied — including on a cleaned-up parent message.
  const carol = env.authenticatedContext(CAROL);
  await assertFails(getDoc(doc(carol.firestore(), `mailboxes/${BOB}/messages/m-del/acks/dev1`)));
  await assertFails(getDoc(doc(carol.firestore(), `mailboxes/${BOB}/messages/gone/acks/dev1`)));
});

// --- pairings: participant read-only, lifecycle behind callables ---

test('pairings are member-readable only', async () => {
  await assertSucceeds(getDoc(doc(env.authenticatedContext(ALICE).firestore(), `pairings/${PAIR}`)));
  await assertFails(getDoc(doc(env.authenticatedContext(CAROL).firestore(), `pairings/${PAIR}`)));
});

test('direct pair lifecycle writes are denied, including termination and membership changes', async () => {
  const alice = env.authenticatedContext(ALICE);
  await assertFails(updateDoc(doc(alice.firestore(), `pairings/${PAIR}`), { status: 'terminated' }));
  await assertFails(updateDoc(doc(alice.firestore(), `pairings/${PAIR}`), { members: [ALICE] }));
  const carol = env.authenticatedContext(CAROL);
  await assertFails(updateDoc(doc(carol.firestore(), `pairings/${PAIR}`), { status: 'terminated' }));
  await assertFails(setDoc(doc(alice.firestore(), 'pairings/pair-2'), { members: [ALICE], status: 'active' }));
  await assertFails(deleteDoc(doc(alice.firestore(), `pairings/${PAIR}`)));
});

test('system_events are participant-readable and never client-writable', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `pairings/${PAIR}/system_events/pair_terminated`), {
      type: 'pair_terminated',
    });
  });
  await assertSucceeds(
    getDoc(doc(env.authenticatedContext(ALICE).firestore(), `pairings/${PAIR}/system_events/pair_terminated`)),
  );
  await assertFails(
    getDoc(doc(env.authenticatedContext(CAROL).firestore(), `pairings/${PAIR}/system_events/pair_terminated`)),
  );
  const alice = env.authenticatedContext(ALICE);
  await assertFails(
    setDoc(doc(alice.firestore(), `pairings/${PAIR}/system_events/forged`), { type: 'pair_terminated' }),
  );
  await assertFails(
    deleteDoc(doc(alice.firestore(), `pairings/${PAIR}/system_events/pair_terminated`)),
  );
});

// --- users profile root: owner read, server-managed writes ---

test('user profiles are owner-readable only and never client-writable', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `users/${ALICE}`), { status: 'ACTIVE', profileVersion: 1 });
  });
  await assertSucceeds(getDoc(doc(env.authenticatedContext(ALICE).firestore(), `users/${ALICE}`)));
  await assertFails(getDoc(doc(env.authenticatedContext(BOB).firestore(), `users/${ALICE}`)));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), `users/${ALICE}`)));

  const alice = env.authenticatedContext(ALICE);
  await assertFails(updateDoc(doc(alice.firestore(), `users/${ALICE}`), { status: 'PENDING_DELETION' }));
  await assertFails(deleteDoc(doc(alice.firestore(), `users/${ALICE}`)));
});

// --- clientCompatibility/current: the one public read ---

test('clientCompatibility/current is public read-only', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'clientCompatibility/current'), { minimumVersionCode: 2 });
  });
  const anonymous = env.unauthenticatedContext().firestore();
  await assertSucceeds(getDoc(doc(anonymous, 'clientCompatibility/current')));
  await assertFails(getDoc(doc(anonymous, 'clientCompatibility/other')));
  await assertFails(
    setDoc(doc(env.authenticatedContext(ALICE).firestore(), 'clientCompatibility/current'), {
      minimumVersionCode: 0,
    }),
  );
});

// --- push_installations: per-user private (unchanged; distinct from plan deviceTokens) ---

test("a user cannot read another user's push_installations", async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `users/${BOB}/push_installations/dev1`), {
      provider: 'fcm',
      token: 'tok',
      platform: 'android',
      appVersion: '1.0',
      updatedAt: serverTimestamp(),
      createdAt: serverTimestamp(),
    });
  });
  const alice = env.authenticatedContext(ALICE);
  await assertFails(getDoc(doc(alice.firestore(), `users/${BOB}/push_installations/dev1`)));

  const bob = env.authenticatedContext(BOB);
  await assertSucceeds(getDoc(doc(bob.firestore(), `users/${BOB}/push_installations/dev1`)));
});

test('push_installations: owner-only writes and the exact field allowlist', async () => {
  const alice = env.authenticatedContext(ALICE);
  const bob = env.authenticatedContext(BOB);
  const valid = () => ({
    provider: 'fcm',
    token: 'tok',
    platform: 'android',
    appVersion: '1.0',
    updatedAt: serverTimestamp(),
    createdAt: serverTimestamp(),
  });
  await assertFails(
    setDoc(doc(alice.firestore(), `users/${BOB}/push_installations/dev1`), valid()),
  );
  await assertFails(
    setDoc(doc(alice.firestore(), `users/${ALICE}/push_installations/dev1`), {
      ...valid(),
      uid: 'spoofed',
    }),
  );
  await assertSucceeds(
    setDoc(doc(alice.firestore(), `users/${ALICE}/push_installations/dev1`), valid()),
  );
  await assertFails(deleteDoc(doc(bob.firestore(), `users/${ALICE}/push_installations/dev1`)));
  await assertSucceeds(deleteDoc(doc(alice.firestore(), `users/${ALICE}/push_installations/dev1`)));
});

// --- server-owned collections: default deny ---

test('default deny: account/backup/lifecycle collections are closed to clients', async () => {
  const alice = env.authenticatedContext(ALICE);
  const fs = alice.firestore();
  const paths = [
    `users/${ALICE}/backupControl/current`,
    `users/${ALICE}/backupManifests/backup-1`,
    'guestTransitions/transition-1',
    `deletionJobs/${ALICE}`,
    'serverFeatureGates/pairCreation',
    'backupReservations/backup-1',
    `eventRateLimits/user_${ALICE}`,
    `pairings/${PAIR}/event_chain/event-1`,
    'pairs/pair-legacy',
  ];
  for (const path of paths) {
    await assertFails(setDoc(doc(fs, path), { probe: true }));
    await assertFails(getDoc(doc(fs, path)));
  }
});

test('default deny: no-auth clients cannot touch pairing, mailbox, token, profile or push records', async () => {
  const fs = env.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(fs, `pairings/${PAIR}`)));
  await assertFails(getDoc(doc(fs, `mailboxes/${BOB}/messages/from-alice`)));
  await assertFails(getDoc(doc(fs, `users/${BOB}`)));
  await assertFails(getDoc(doc(fs, `pairings/${PAIR}/system_events/pair_terminated`)));
  await assertFails(getDoc(doc(fs, `users/${BOB}/push_installations/dev1`)));
  await assertFails(getDoc(doc(fs, 'pairing_tokens/token-1')));
  await assertFails(
    setDoc(doc(fs, `mailboxes/${BOB}/messages/anon-write`), mailboxMessage()),
  );
});

// --- pairing_tokens: possession-gated transport, callable-only mutation ---

test('pairing tokens: reads/deletes are possession-gated; create/update are denied', async () => {
  const TOKEN = 'token-1';
  const expiresAt = new Date(Date.now() + 300_000);
  await seedToken(TOKEN, expiresAt);

  const alice = env.authenticatedContext(ALICE);
  await assertSucceeds(getDoc(doc(alice.firestore(), `pairing_tokens/${TOKEN}`)));
  await assertFails(
    getDoc(doc(env.unauthenticatedContext().firestore(), `pairing_tokens/${TOKEN}`)),
  );

  // Minting moved to createPairInvitation; the partner response moved to
  // respondToPairInvitation. Neither is a client write any more.
  await assertFails(
    setDoc(doc(alice.firestore(), 'pairing_tokens/token-spoof'), {
      creatorUid: ALICE,
      expiresAt,
      payload: { b64: 'initiator-material' },
    }),
  );
  const bob = env.authenticatedContext(BOB);
  await assertFails(
    updateDoc(doc(bob.firestore(), `pairing_tokens/${TOKEN}`), {
      partnerUid: BOB,
      partnerPayload: { b64: 'partner-material' },
      partnerSignature: 'sig',
    }),
  );

  // The holder removes the rendezvous document after registerPairing succeeds.
  await assertSucceeds(deleteDoc(doc(alice.firestore(), `pairing_tokens/${TOKEN}`)));
});

test('pairing tokens: expired tokens are unreadable', async () => {
  await seedToken('token-expired', new Date(Date.now() - 60_000));
  const alice = env.authenticatedContext(ALICE);
  await assertFails(getDoc(doc(alice.firestore(), 'pairing_tokens/token-expired')));
});

// Firestore security-rules emulator suite — plan §12 "Security rules (Firebase emulator)".
// The §13 CI gate: this suite must pass before every rules deployment (task #25 gate).
//
// Plan §12 rows (1) and (2) — "paired GMS user with a valid App Check token can write" and
// "paired HMS/no-GMS user without an App Check token can still write (AD-03b)" — collapse to
// the SAME evaluation here: App Check enforcement is a Firestore service-level setting
// (monitor -> enforce, §13/AD-03), never a rules clause, so the emulator cannot and should
// not distinguish them. Case 1 below covers both rows; the no-GMS exemption is the absence
// of any App Check clause in firestore.rules.
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
  timestamp: serverTimestamp(),
  ...overrides,
});

const messageRef = (ctx, messageId) => doc(ctx.firestore(), `mailboxes/${BOB}/messages/${messageId}`);

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

// --- §12 positive rows: paired user writes to the partner's mailbox (rows 1+2, see header) ---

test('paired user can write a schema-valid message to the partner mailbox', async () => {
  const alice = env.authenticatedContext(ALICE);
  await assertSucceeds(setDoc(messageRef(alice, 'm1'), mailboxMessage()));
});

// --- §12 negative rows: unpaired / unknown-pair writes ---

test('authenticated non-member cannot write to an arbitrary mailbox', async () => {
  const carol = env.authenticatedContext(CAROL);
  await assertFails(setDoc(messageRef(carol, 'm1'), mailboxMessage({ senderUid: CAROL })));
});

test('write referencing a nonexistent pairing is denied', async () => {
  const alice = env.authenticatedContext(ALICE);
  await assertFails(setDoc(messageRef(alice, 'm1'), mailboxMessage({ pairId: 'no-such-pair' })));
});

test('write under a non-active pairing is denied', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `pairings/${PAIR}`), { members: [ALICE, BOB], status: 'revoked' });
  });
  const alice = env.authenticatedContext(ALICE);
  await assertFails(setDoc(messageRef(alice, 'm1'), mailboxMessage()));
});

test('senderUid must be the writer', async () => {
  // Carol IS a member of this pair, isolating the senderUid clause from the membership one.
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'pairings/pair-carol'), { members: [CAROL, BOB], status: 'active' });
  });
  const carol = env.authenticatedContext(CAROL);
  await assertFails(setDoc(messageRef(carol, 'm1'), mailboxMessage({ pairId: 'pair-carol', senderUid: ALICE })));
  await assertSucceeds(setDoc(messageRef(carol, 'm2'), mailboxMessage({ pairId: 'pair-carol', senderUid: CAROL })));
});

// --- §12 negative row: push_installations are per-user private ---

test('a user cannot read another user\'s push_installations', async () => {
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

// --- §12 negative row: function-only collections ---

test('clients cannot write /pairings, /relay-dispatch, or /rate-limits', async () => {
  const alice = env.authenticatedContext(ALICE);
  await assertFails(setDoc(doc(alice.firestore(), 'pairings/pair-2'), { members: [ALICE], status: 'active' }));
  await assertFails(setDoc(doc(alice.firestore(), 'relay-dispatch/m1/installations/i1'), { status: 'PENDING' }));
  await assertFails(setDoc(doc(alice.firestore(), 'rate-limits/pair-1'), { pair: {} }));
});

// --- §12 negative row: schema strictness ---

test('extra fields, wrong types, and oversized payloads are rejected', async () => {
  const alice = env.authenticatedContext(ALICE);
  await assertFails(setDoc(messageRef(alice, 'm1'), mailboxMessage({ extra: 'nope' })));
  await assertFails(setDoc(messageRef(alice, 'm1'), mailboxMessage({ keyEpoch: '0' })));
  await assertFails(setDoc(messageRef(alice, 'm1'), mailboxMessage({ payload: 'A'.repeat(8193) })));
  await assertFails(setDoc(messageRef(alice, 'm1'), mailboxMessage({ timestamp: new Date() })));
});

// --- clauses the rules encode beyond the §12 list, pinned because they are security-critical ---

test('mailbox messages are immutable and client-undeletable (AD-18b)', async () => {
  const alice = env.authenticatedContext(ALICE);
  await assertSucceeds(setDoc(messageRef(alice, 'm1'), mailboxMessage()));

  const bob = env.authenticatedContext(BOB);
  await assertSucceeds(getDoc(messageRef(bob, 'm1')));
  await assertFails(updateDoc(messageRef(bob, 'm1'), { payload: 'tampered' }));
  await assertFails(deleteDoc(messageRef(bob, 'm1')));
});

test("the message's author can read its own message doc (Step 18 rejection marker)", async () => {
  const alice = env.authenticatedContext(ALICE);
  await assertSucceeds(setDoc(messageRef(alice, 'm1'), mailboxMessage()));

  // The relay's rejection marker lands on this document; the sender must be able to see it.
  await env.withSecurityRulesDisabled(async (ctx) => {
    await updateDoc(doc(ctx.firestore(), `mailboxes/${BOB}/messages/m1`), { relayStatus: 'RATE_LIMITED' });
  });
  await assertSucceeds(getDoc(messageRef(alice, 'm1')));

  // Carol is a member of another pair and still not the author: denial stands.
  const carol = env.authenticatedContext(CAROL);
  await assertFails(getDoc(messageRef(carol, 'm1')));
});

test('only the recipient acks a mailbox message', async () => {
  const alice = env.authenticatedContext(ALICE);
  await assertSucceeds(setDoc(messageRef(alice, 'm1'), mailboxMessage()));

  const bob = env.authenticatedContext(BOB);
  await assertSucceeds(
    setDoc(doc(bob.firestore(), `mailboxes/${BOB}/messages/m1/acks/dev1`), { ackedAt: serverTimestamp() }),
  );
  const carol = env.authenticatedContext(CAROL);
  await assertFails(
    setDoc(doc(carol.firestore(), `mailboxes/${BOB}/messages/m1/acks/dev2`), { ackedAt: serverTimestamp() }),
  );
});

test('pairings are member-readable only', async () => {
  const alice = env.authenticatedContext(ALICE);
  await assertSucceeds(getDoc(doc(alice.firestore(), `pairings/${PAIR}`)));

  const carol = env.authenticatedContext(CAROL);
  await assertFails(getDoc(doc(carol.firestore(), `pairings/${PAIR}`)));
});

// --- Step 13/14 unpair: revocation is a member-only, status-only, one-way update ---

test('pair member can terminate an active pairing; nothing can re-open it', async () => {
  const alice = env.authenticatedContext(ALICE);
  await assertSucceeds(updateDoc(doc(alice.firestore(), `pairings/${PAIR}`), { status: 'terminated' }));
  // One-way: even a member cannot flip it back (the v1 revocation-reopen gap stays closed).
  await assertFails(updateDoc(doc(alice.firestore(), `pairings/${PAIR}`), { status: 'active' }));
});

test('termination cannot smuggle other field changes', async () => {
  const alice = env.authenticatedContext(ALICE);
  await assertFails(updateDoc(doc(alice.firestore(), `pairings/${PAIR}`), { status: 'terminated', members: [ALICE] }));
  await assertFails(updateDoc(doc(alice.firestore(), `pairings/${PAIR}`), { members: [ALICE] }));
  // Only the exact terminal value, never a third state.
  await assertFails(updateDoc(doc(alice.firestore(), `pairings/${PAIR}`), { status: 'revoked' }));
});

test('non-member cannot terminate a pairing', async () => {
  const carol = env.authenticatedContext(CAROL);
  await assertFails(updateDoc(doc(carol.firestore(), `pairings/${PAIR}`), { status: 'terminated' }));
});

test('re-terminating an already terminated pairing is denied (mutual-unpair loser)', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `pairings/${PAIR}`), { members: [ALICE, BOB], status: 'terminated' });
  });
  const bob = env.authenticatedContext(BOB);
  await assertFails(updateDoc(doc(bob.firestore(), `pairings/${PAIR}`), { status: 'terminated' }));
});

// --- AD-13b: the request's author reads the acks proving their deletion was processed ---

test('message author can read the acks on their own message (REMOTE_SHRED_CONFIRMED evidence)', async () => {
  const alice = env.authenticatedContext(ALICE);
  await assertSucceeds(setDoc(messageRef(alice, 'm-del'), mailboxMessage()));

  const bob = env.authenticatedContext(BOB);
  await assertSucceeds(
    setDoc(doc(bob.firestore(), `mailboxes/${BOB}/messages/m-del/acks/dev1`), { ackedAt: serverTimestamp() }),
  );

  await assertSucceeds(getDoc(doc(alice.firestore(), `mailboxes/${BOB}/messages/m-del/acks/dev1`)));
  // The recipient's own read is unchanged.
  await assertSucceeds(getDoc(doc(bob.firestore(), `mailboxes/${BOB}/messages/m-del/acks/dev1`)));
  // A non-author non-recipient stays denied.
  const carol = env.authenticatedContext(CAROL);
  await assertFails(getDoc(doc(carol.firestore(), `mailboxes/${BOB}/messages/m-del/acks/dev1`)));
});

test('acks read by a non-author is denied on someone else\'s message', async () => {
  const alice = env.authenticatedContext(ALICE);
  await assertSucceeds(setDoc(messageRef(alice, 'm-del'), mailboxMessage()));

  const bob = env.authenticatedContext(BOB);
  await assertSucceeds(
    setDoc(doc(bob.firestore(), `mailboxes/${BOB}/messages/m-del/acks/dev1`), { ackedAt: serverTimestamp() }),
  );

  // Carol authored nothing; a non-existent parent message would also short-circuit here.
  const carol = env.authenticatedContext(CAROL);
  await assertFails(getDoc(doc(carol.firestore(), `mailboxes/${BOB}/messages/m-del/acks/dev1`)));
  await assertFails(getDoc(doc(carol.firestore(), `mailboxes/${BOB}/messages/gone/acks/dev1`)));
});

// --- pairing_tokens: the single-use update gate (Step 3 amendment) ---

test('pairing token: first partner response wins, second is denied', async () => {
  const TOKEN = 'token-1';
  const alice = env.authenticatedContext(ALICE);
  await assertSucceeds(
    setDoc(doc(alice.firestore(), `pairing_tokens/${TOKEN}`), {
      creatorUid: ALICE,
      expiresAt: new Date(Date.now() + 300_000),
      payload: { b64: 'initiator-material' },
    }),
  );

  const bob = env.authenticatedContext(BOB);
  await assertSucceeds(
    updateDoc(doc(bob.firestore(), `pairing_tokens/${TOKEN}`), {
      partnerUid: BOB,
      partnerPayload: { b64: 'partner-material' },
      partnerSignature: 'sig',
    }),
  );

  const carol = env.authenticatedContext(CAROL);
  await assertFails(
    updateDoc(doc(carol.firestore(), `pairing_tokens/${TOKEN}`), {
      partnerUid: CAROL,
      partnerPayload: { b64: 'third-party' },
      partnerSignature: 'sig',
    }),
  );
});

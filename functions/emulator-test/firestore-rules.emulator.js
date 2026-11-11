'use strict';
// Step 2 security gate: the mailbox rules are the server-side authorization boundary.
// These tests run against the real firestore.rules in the Firestore emulator.
//
// Account plan Release 0 step 3 changed the write side: appendUserEvent is the ONLY event
// write path, so direct client mailbox creates are denied outright. What remains here is
// the read boundary (recipient isolation, author relay marker), immutability, acks, and
// that pair lifecycle + DMS state stay server-only. The append-side gates (schema, pair and
// profile state, token buckets, kill switch) are covered by event-append.emulator.js.
//
// Run: npm run test:rules (functions/)

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require('@firebase/rules-unit-testing');
const {
  doc,
  getDoc,
  setDoc,
  collection,
  getDocs,
  query,
  where,
  serverTimestamp,
} = require('firebase/firestore');

const RULES = fs.readFileSync(path.join(__dirname, '..', '..', 'firestore.rules'), 'utf8');
const PROJECT_ID = 'demo-myexodus-step2';

let env;
let alice;
let bob;
let dave;
let erin;
let stranger;
let anonymous;

const mailbox = (uid, messageId) => `mailboxes/${uid}/messages/${messageId}`;

const message = (senderUid, pairId, overrides = {}) => ({
  senderId: 'sender-key-1',
  senderUid,
  pairId,
  payload: Buffer.from('opaque ciphertext').toString('base64'),
  keyEpoch: 0,
  timestamp: serverTimestamp(),
  ...overrides,
});

before(async () => {
  env = await initializeTestEnvironment({ projectId: PROJECT_ID, firestore: { rules: RULES } });
  alice = env.authenticatedContext('alice').firestore();
  bob = env.authenticatedContext('bob').firestore();
  dave = env.authenticatedContext('dave').firestore();
  erin = env.authenticatedContext('erin').firestore();
  stranger = env.authenticatedContext('stranger').firestore();
  anonymous = env.unauthenticatedContext().firestore();
});

after(async () => env.cleanup());

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const pairing = (id, members, status) => setDoc(doc(db, 'pairings', id), { members, status });
    await pairing('pair-alice-bob', ['alice', 'bob'], 'active');
    await pairing('pair-dave-erin', ['dave', 'erin'], 'active');
    await pairing('pair-frank-gina', ['frank', 'gina'], 'terminated');
    await setDoc(doc(db, mailbox('bob', 'from-alice')), {
      ...message('alice', 'pair-alice-bob'),
      timestamp: new Date(),
    });
    await setDoc(doc(db, mailbox('erin', 'from-dave')), {
      ...message('dave', 'pair-dave-erin'),
      timestamp: new Date(),
    });
    // Release 2 backup state: an active owner, a deleting owner, and an owner-less UID.
    await setDoc(doc(db, 'users/alice'), { status: 'ACTIVE', profileVersion: 1 });
    await setDoc(doc(db, 'users/deleting'), { status: 'PENDING_DELETION', profileVersion: 1 });
    for (const uid of ['alice', 'deleting']) {
      await setDoc(doc(db, `users/${uid}/backupControl/keyBundle`), { revision: 1, keyId: 'k1' });
      await setDoc(doc(db, `users/${uid}/backupControl/current`), { backupId: 'b1', revision: 1 });
      await setDoc(doc(db, `users/${uid}/backupManifests/b1`), { revision: 1, size: 1024 });
    }
    await setDoc(doc(db, 'backupReservations/b1'), { uid: 'alice', status: 'active' });
  });
});

test('the recipient can read its own mailbox', async () => {
  await assertSucceeds(getDoc(doc(bob, mailbox('bob', 'from-alice'))));
});

test('a partner from another relationship cannot read the mailbox document', async () => {
  await assertFails(getDoc(doc(erin, mailbox('bob', 'from-alice'))));
  await assertFails(getDoc(doc(stranger, mailbox('bob', 'from-alice'))));
});

test('a partner from another relationship cannot list the mailbox', async () => {
  await assertFails(getDocs(collection(erin, 'mailboxes/bob/messages')));
});

test('an unauthenticated client cannot read a mailbox', async () => {
  await assertFails(getDoc(doc(anonymous, mailbox('bob', 'from-alice'))));
});

test('the author can read the marker on its own sent message, not someone else’s', async () => {
  await assertSucceeds(getDoc(doc(alice, mailbox('bob', 'from-alice'))));
  await assertFails(getDoc(doc(alice, mailbox('erin', 'from-dave'))));
});

test('direct client mailbox create is denied — appendUserEvent is the only write path', async () => {
  // Valid-looking payload by an active pair member: still denied. The callable owns every
  // schema/state/token-bucket check now, so no client write variant is admissible.
  await assertFails(setDoc(doc(alice, mailbox('bob', 'queued-1')), message('alice', 'pair-alice-bob')));
  await assertFails(setDoc(doc(alice, mailbox('bob', 'spoof')), message('bob', 'pair-alice-bob')));
  await assertFails(
    setDoc(doc(alice, mailbox('bob', 'ghost-pair')), message('alice', 'pair-does-not-exist')),
  );
  await assertFails(
    setDoc(doc(stranger, mailbox('bob', 'not-a-member')), message('stranger', 'pair-alice-bob')),
  );
});

test('clients cannot delete or update a mailbox document', async () => {
  const { deleteDoc, updateDoc } = require('firebase/firestore');
  await assertFails(deleteDoc(doc(bob, mailbox('bob', 'from-alice'))));
  await assertFails(
    updateDoc(doc(bob, mailbox('bob', 'from-alice')), { relayStatus: 'RATE_LIMITED' }),
  );
});

test('only the recipient can acknowledge its own messages', async () => {
  const ack = (uid, messageId) => `mailboxes/${uid}/messages/${messageId}/acks/install-1`;
  await assertSucceeds(
    setDoc(doc(bob, ack('bob', 'from-alice')), { ackedAt: serverTimestamp() }),
  );
  await assertFails(
    setDoc(doc(erin, ack('bob', 'from-alice')), { ackedAt: serverTimestamp() }),
  );
});

test('the pairing document itself is not client-writable', async () => {
  const { updateDoc } = require('firebase/firestore');
  await assertFails(
    setDoc(doc(alice, 'pairings/pair-alice-bob'), { members: ['alice'], status: 'active' }),
  );
  await assertFails(
    updateDoc(doc(alice, 'pairings/pair-alice-bob'), { status: 'terminated' }),
  );
});

test('pairing invitations cannot be enumerated — the id is the capability', async () => {
  // 2026-11-05 security audit: `allow read` let any signed-in user run an expiresAt-
  // constrained list query and receive EVERY live invitation document id — which is also
  // the respondToPairInvitation capability. Listing is now denied; a get by id (the QR /
  // typed-code holder) still works.
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'pairing_tokens/live-token'), {
      creatorUid: 'alice',
      expiresAt: new Date(Date.now() + 60_000),
    });
    await setDoc(doc(db, 'pairing_tokens/expired-token'), {
      creatorUid: 'alice',
      expiresAt: new Date(Date.now() - 60_000),
    });
  });
  await assertSucceeds(getDoc(doc(stranger, 'pairing_tokens/live-token')));
  await assertFails(getDoc(doc(stranger, 'pairing_tokens/expired-token')));
  await assertFails(getDocs(collection(stranger, 'pairing_tokens')));
  await assertFails(getDocs(query(
    collection(stranger, 'pairing_tokens'),
    where('expiresAt', '>', new Date()),
  )));
  await assertFails(getDocs(collection(anonymous, 'pairing_tokens')));
});

test('backup control documents are owner-readable only while the account is active', async () => {
  const { deleteDoc, updateDoc } = require('firebase/firestore');
  const owner = env.authenticatedContext('alice').firestore();
  const deleting = env.authenticatedContext('deleting').firestore();
  const noProfile = env.authenticatedContext('stranger').firestore();

  for (const path of ['backupControl/keyBundle', 'backupControl/current', 'backupManifests/b1']) {
    await assertSucceeds(getDoc(doc(owner, `users/alice/${path}`)));
    // A pending-deletion account loses every backup read, including its own.
    await assertFails(getDoc(doc(deleting, `users/deleting/${path}`)));
    // Another authenticated UID can never read it, with or without a profile.
    await assertFails(getDoc(doc(noProfile, `users/alice/${path}`)));
    await assertFails(getDoc(doc(anonymous, `users/alice/${path}`)));
    // No client write variant is admissible — the backup callables are the only writers.
    await assertFails(setDoc(doc(owner, `users/alice/${path}`), { revision: 99 }));
    await assertFails(updateDoc(doc(owner, `users/alice/${path}`), { revision: 99 }));
    await assertFails(deleteDoc(doc(owner, `users/alice/${path}`)));
  }
});

test('upload reservations stay server-only', async () => {
  const { updateDoc } = require('firebase/firestore');
  await assertFails(getDoc(doc(alice, 'backupReservations/b1')));
  await assertFails(getDocs(collection(alice, 'backupReservations')));
  await assertFails(setDoc(doc(alice, 'backupReservations/b2'), { uid: 'alice' }));
  await assertFails(updateDoc(doc(alice, 'backupReservations/b1'), { status: 'expired' }));
});

test('dms_install_state is invisible and unwritable to every client, even its owner', async () => {
  const { deleteDoc, updateDoc } = require('firebase/firestore');
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'dms_install_state/alice_fid-1'), {
      uid: 'alice_body',
      install_id: 'fid-1',
      last_counter: 7,
      liveness_generation: 3,
    });
  });

  // The only reader/writer is the Admin SDK: submitHeartbeat writes it, the DMS deadline
  // processor reads it. A client reading its own row would leak a precise server timestamp
  // that §8.10 deliberately never exposes.
  await assertFails(getDoc(doc(alice, 'dms_install_state/alice_fid-1')));
  await assertFails(getDocs(collection(alice, 'dms_install_state')));
  await assertFails(
    setDoc(doc(alice, 'dms_install_state/alice_fid-1'), { last_counter: 99, liveness_generation: 99 }),
  );
  await assertFails(
    updateDoc(doc(alice, 'dms_install_state/alice_fid-1'), { last_counter: 99 }),
  );
  await assertFails(deleteDoc(doc(alice, 'dms_install_state/alice_fid-1')));
  await assertFails(
    setDoc(doc(anonymous, 'dms_install_state/alice_fid-1'), { last_counter: 99 }),
  );

  // And the owner's own tampering attempt changed nothing.
  await env.withSecurityRulesDisabled(async (ctx) => {
    const row = await getDoc(doc(ctx.firestore(), 'dms_install_state/alice_fid-1'));
    assert.strictEqual(row.data().last_counter, 7);
  });
});

// --- deletion tombstones (account plan §6.8.3 / §8.2) ------------------------------------

test('an accepted deletion denies every owner read and new push registration', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'deletionJobs/alice'), { uid: 'alice', phase: 'QUEUED' });
    await setDoc(doc(db, 'pairing_tokens/tok1'), {
      creatorUid: 'alice',
      expiresAt: new Date(Date.now() + 60_000),
    });
    await setDoc(doc(db, mailbox('alice', 'own')), {
      ...message('bob', 'pair-alice-bob'),
      timestamp: new Date(),
    });
  });

  // Profile, own mailbox, pair membership and pairing tokens are all closed to the owner.
  await assertFails(getDoc(doc(alice, 'users/alice')));
  await assertFails(getDoc(doc(alice, mailbox('alice', 'own'))));
  await assertFails(getDoc(doc(alice, 'pairings/pair-alice-bob')));
  await assertFails(getDoc(doc(alice, 'pairing_tokens/tok1')));

  // A device cannot re-register under a deleting account.
  await assertFails(
    setDoc(doc(alice, 'users/alice/push_installations/inst-9'), {
      provider: 'fcm',
      token: 'token-1',
      platform: 'android',
      appVersion: '1.0.0',
      updatedAt: serverTimestamp(),
    }),
  );

  // The partner still reads the pair — only the requester's own tombstone is checked.
  await assertSucceeds(getDoc(doc(bob, 'pairings/pair-alice-bob')));
});

test('only an ACTIVE profile is readable, even without a deletion job', async () => {
  // beforeEach seeds users/deleting with status PENDING_DELETION and no job record.
  const deleting = env.authenticatedContext('deleting').firestore();
  await assertFails(getDoc(doc(deleting, 'users/deleting')));
  await assertSucceeds(getDoc(doc(alice, 'users/alice')));
  await assertFails(getDoc(doc(bob, 'users/alice')));
});

// Partner event delivery v1 (partner event-delivery plan §7 Phase 1 / §10.10).
test('partner mailboxes are recipient-read-only', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'partnerMailboxes/bob/messages/evt-1'), {
      ownerUid: 'alice',
      eventId: 'evt-1',
      encoding: 'existing-e2ee',
      payload: 'opaque ciphertext',
    });
  });
  await assertSucceeds(getDoc(doc(bob, 'partnerMailboxes/bob/messages/evt-1')));
  await assertFails(getDoc(doc(alice, 'partnerMailboxes/bob/messages/evt-1')));
  await assertFails(getDoc(doc(anonymous, 'partnerMailboxes/bob/messages/evt-1')));
  await assertFails(getDocs(collection(bob, 'partnerMailboxes/alice/messages')));

  const { deleteDoc, updateDoc } = require('firebase/firestore');
  await assertFails(setDoc(doc(bob, 'partnerMailboxes/bob/messages/evt-2'), { payload: 'x' }));
  await assertFails(updateDoc(doc(bob, 'partnerMailboxes/bob/messages/evt-1'), { payload: 'y' }));
  await assertFails(deleteDoc(doc(bob, 'partnerMailboxes/bob/messages/evt-1')));
});

// Partner event delivery v1 (§8.6 Phase 5): registration kind is explicit and closed-set.
test('push registrations carry an optional FID or TOKEN registration kind', async () => {
  const base = {
    provider: 'fcm',
    token: 'token-1',
    platform: 'android',
    appVersion: '1.0.0',
    pushEligible: true,
    updatedAt: serverTimestamp(),
  };
  await assertSucceeds(
    setDoc(doc(alice, 'users/alice/push_installations/inst-token'), { ...base, registrationKind: 'TOKEN' }),
  );
  await assertSucceeds(
    setDoc(doc(alice, 'users/alice/push_installations/inst-fid'), { ...base, registrationKind: 'FID' }),
  );
  await assertSucceeds(
    setDoc(doc(alice, 'users/alice/push_installations/inst-legacy'), base),
  );
  await assertFails(
    setDoc(doc(alice, 'users/alice/push_installations/inst-bad'), { ...base, registrationKind: 'SOMETHING' }),
  );
  await assertFails(
    setDoc(doc(alice, 'users/alice/push_installations/inst-extra'), { ...base, registrationKind: 'TOKEN', extra: true }),
  );
});

test('partner delivery registry, ledger, job and liveness documents are server-only', async () => {
  const paths = [
    'partnerDeviceSigningRegistrations/alice:inst',
    'partnerDeviceChallenges/ch-1',
    'partnerRecipientKeys/key-1',
    'partnerEvents/alice/installations/inst/events/evt-1',
    'partnerNotificationOutbox/evt-1__inst',
    'partnerFanoutTasks/evt-1',
    'partnerDeviceLiveness/inst',
  ];
  for (const path of paths) {
    await assertFails(getDoc(doc(bob, path)));
    await assertFails(setDoc(doc(bob, path), { anything: true }));
  }
});

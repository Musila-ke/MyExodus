'use strict';
// Step 2 security gate: the mailbox rules are the server-side authorization boundary.
// These tests run against the real firestore.rules in the Firestore emulator and prove
// recipient isolation, create-only replay handling, the field allowlist, and that a
// revoked (terminated) relationship can no longer receive queued events.
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

test('a member of an active pair can write to the other member’s mailbox', async () => {
  await assertSucceeds(
    setDoc(doc(alice, mailbox('bob', 'queued-1')), message('alice', 'pair-alice-bob')),
  );
});

test('operator kill switch: an explicit false stops new sends and leaves stored data readable', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'config', 'features'), { partnerAccountabilityEnabled: false });
  });
  await assertFails(
    setDoc(doc(alice, mailbox('bob', 'switch-off')), message('alice', 'pair-alice-bob')),
  );
  // Rollback is not deletion: the pairing and an already-stored message survive and stay readable.
  await assertSucceeds(getDoc(doc(alice, 'pairings/pair-alice-bob')));
  await assertSucceeds(getDoc(doc(bob, mailbox('bob', 'from-alice'))));
});

test('operator kill switch: absent doc, absent field, or explicit true leaves sends enabled', async () => {
  await assertSucceeds(
    setDoc(doc(alice, mailbox('bob', 'no-config-doc')), message('alice', 'pair-alice-bob')),
  );
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'config', 'features'), { partnerSosEnabled: false });
  });
  await assertSucceeds(
    setDoc(doc(alice, mailbox('bob', 'field-absent')), message('alice', 'pair-alice-bob')),
  );
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'config', 'features'), { partnerAccountabilityEnabled: true });
  });
  await assertSucceeds(
    setDoc(doc(alice, mailbox('bob', 'explicit-true')), message('alice', 'pair-alice-bob')),
  );
});

test('senderUid cannot be spoofed', async () => {
  await assertFails(
    setDoc(doc(alice, mailbox('bob', 'spoof')), message('bob', 'pair-alice-bob')),
  );
});

test('a revoked relationship cannot queue new events', async () => {
  const frank = env.authenticatedContext('frank').firestore();
  await assertFails(
    setDoc(doc(frank, mailbox('gina', 'after-terminate')), message('frank', 'pair-frank-gina')),
  );
});

test('another relationship’s pairId cannot be used to post into a mailbox', async () => {
  await assertFails(
    setDoc(doc(dave, mailbox('bob', 'wrong-pair')), message('dave', 'pair-dave-erin')),
  );
});

test('a pairId the sender does not belong to is denied', async () => {
  await assertFails(
    setDoc(doc(stranger, mailbox('bob', 'not-a-member')), message('stranger', 'pair-alice-bob')),
  );
});

test('a mailbox document is create-only — replaying the message id is denied', async () => {
  await assertFails(
    setDoc(doc(alice, mailbox('bob', 'from-alice')), message('alice', 'pair-alice-bob')),
  );
});

test('the field allowlist rejects key material and unknown fields', async () => {
  await assertFails(
    setDoc(doc(alice, mailbox('bob', 'with-cek')), message('alice', 'pair-alice-bob', {
      cek: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
    })),
  );
  await assertFails(
    setDoc(doc(alice, mailbox('bob', 'with-plaintext')), message('alice', 'pair-alice-bob', {
      plaintext: 'blocked com.secret.app',
    })),
  );
});

test('oversized payloads are refused at the storage boundary', async () => {
  await assertFails(
    setDoc(
      doc(alice, mailbox('bob', 'oversized')),
      message('alice', 'pair-alice-bob', { payload: 'A'.repeat(8193) }),
    ),
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

test('an unknown pairing id cannot be used at all', async () => {
  await assertFails(
    setDoc(doc(alice, mailbox('bob', 'ghost-pair')), message('alice', 'pair-does-not-exist')),
  );
});

test('the pairing document itself is not client-writable', async () => {
  await assertFails(
    setDoc(doc(alice, 'pairings/pair-alice-bob'), { members: ['alice'], status: 'active' }),
  );
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

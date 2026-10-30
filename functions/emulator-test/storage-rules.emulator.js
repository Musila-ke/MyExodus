'use strict';
// Storage rules suite — the §8.6 boundary that only a live server-owned reservation can open.
// Runs against the real storage.rules and firestore.rules with both emulators
// (`npm run test:storage-rules`), including the cross-service Firestore lookup.

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require('@firebase/rules-unit-testing');

const PROJECT_ID = 'demo-myexodus-backup-rules';
const OWNER = 'backup-rules-owner';
const OTHER = 'backup-rules-other';
const MAX_BYTES = 21 * 1024 * 1024;

const FIRESTORE_RULES = fs.readFileSync(path.join(__dirname, '..', '..', 'firestore.rules'), 'utf8');
const STORAGE_RULES = fs.readFileSync(path.join(__dirname, '..', '..', 'storage.rules'), 'utf8');

let env;
let backupId;
let objectPath;
let reservationPath;

const binary = (size = 64) => new Uint8Array(size).fill(7);
const upload = (storage, bytes = binary(), metadata = {}) =>
  storage.ref(objectPath).put(bytes, { contentType: 'application/octet-stream', ...metadata });

async function seedReservation(expiresAt, overrides = {}) {
  await env.withSecurityRulesDisabled(async (context) => {
    await context.firestore().doc(reservationPath).set({
      uid: OWNER,
      backupId,
      objectPath,
      objectBytes: 64,
      expiresAt,
      ...overrides,
    });
  });
}

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: FIRESTORE_RULES },
    storage: { rules: STORAGE_RULES },
  });
});

after(async () => {
  await env.cleanup();
});

beforeEach(async () => {
  // A fresh backup id per test: the storage emulator's rule cache can otherwise report a
  // just-deleted object as still present for a moment, which would mask create-only checks.
  backupId = require('node:crypto').randomBytes(16).toString('hex');
  objectPath = `backups/${OWNER}/${backupId}`;
  reservationPath = `backupReservations/${OWNER}/reservations/${backupId}`;
  await env.clearFirestore();
  await env.clearStorage();
});

const future = () => new Date(Date.now() + 60 * 60 * 1000);
const past = () => new Date(Date.now() - 60 * 1000);
const owner = () => env.authenticatedContext(OWNER).storage();

test('an owner upload inside a live reservation is allowed and readable by that owner only', async () => {
  await seedReservation(future());
  await assertSucceeds(upload(owner()));
  await assertSucceeds(env.authenticatedContext(OWNER).storage().ref(objectPath).getMetadata());
  await assertFails(env.authenticatedContext(OTHER).storage().ref(objectPath).getMetadata());
});

test('an unreserved path is denied even for its own owner', async () => {
  await assertFails(upload(owner()));
  await assertFails(env.authenticatedContext(OWNER).storage().ref('backups/other/path').put(binary()));
  await assertFails(env.authenticatedContext(OWNER).storage().ref('elsewhere/file.bin').put(binary()));
});

test('an anonymous upload is denied', async () => {
  await seedReservation(future());
  await assertFails(env.unauthenticatedContext().storage().ref(objectPath).put(binary(), { contentType: 'application/octet-stream' }));
});

test('an owner cannot create at another owner path even when that reservation exists', async () => {
  await seedReservation(future());
  await assertFails(env.authenticatedContext(OTHER).storage().ref(objectPath).put(binary(), { contentType: 'application/octet-stream' }));
});

test('an expired reservation denies the upload and reads stay owner-only', async () => {
  await seedReservation(past());
  await assertFails(upload(owner()));
});

test('a non-binary content type is denied', async () => {
  await seedReservation(future());
  await assertFails(upload(owner(), binary(), { contentType: 'text/plain' }));
  await assertFails(upload(owner(), binary(), { contentType: 'image/png' }));
});

test('an object over 21 MiB is denied', async () => {
  await seedReservation(future());
  await assertFails(upload(owner(), new Uint8Array(MAX_BYTES + 1)));
});

test('an empty object is denied', async () => {
  await seedReservation(future());
  await assertFails(upload(owner(), new Uint8Array(0)));
});

test('the reservation path is write-once — overwrite and delete are denied', async () => {
  await seedReservation(future());
  await assertSucceeds(upload(owner()));
  await assertFails(upload(owner()));
  await assertFails(env.authenticatedContext(OWNER).storage().ref(objectPath).delete());
});

test('clients can never read or write the reservation document itself', async () => {
  await seedReservation(future());
  const firestore = env.authenticatedContext(OWNER).firestore();
  await assertFails(firestore.doc(reservationPath).get());
  await assertFails(firestore.doc(reservationPath).set({ uid: OWNER }));
  await assertFails(firestore.doc(`backupReservations/${OWNER}`).set({ attempts: [] }));
});

test('a reservation seeded for another backup id opens nothing', async () => {
  await seedReservation(future(), { backupId: 'b'.repeat(32) });
  await assertFails(upload(owner()));
  assert.ok(true);
});

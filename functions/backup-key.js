'use strict';
// Account plan §7 Release 2 step 12 / §6.4/§6.5 / §8.5 / §4.2: the ABK key-bundle callables.
//
//   initializeBackupKeyBundle  persistent, verified, active account only; creates the one
//                              current wrapped-ABK document at revision 1. A byte-identical
//                              replay (lost response, retried call) returns the stored bundle
//                              instead of failing, so setup is idempotent.
//   updateBackupKeyBundle      compare-and-set on the numeric revision; changes only what the
//                              client re-wrapped (passphrase wrapper or recovery wrapper),
//                              never the ABK identity, envelope version or KDF parameters.
//
// §6.5/§9: a stale revision is a hard conflict — the client must re-read, re-unlock and
// reconfirm, never overwrite a newer wrapper silently. No old bundle version is retained.
// Logging (§10.14): uids appear only as short hashes; wrapped blobs, salts and nonces are
// never logged.

const crypto = require('node:crypto');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { logger } = require('firebase-functions');

const { readActiveProfile } = require('./pair-lifecycle');
const {
  validateInitializeInput,
  validateUpdateInput,
  sameBundleCore,
  rotationChangedOnlyAllowedFields,
  publicBundle,
} = require('./backup-key-logic');

const REGION = 'us-central1';
const BUNDLE_PATH = (uid) => `users/${uid}/backupControl/keyBundle`;

function hashForLog(value) {
  return crypto.createHash('sha256').update(value).digest('hex').slice(0, 12);
}

function signInProviderOf(auth) {
  return (auth && auth.token && auth.token.firebase && auth.token.firebase.sign_in_provider) || undefined;
}

/**
 * §4.2: persistent account, no deletion job, profile ACTIVE, and — for email/password
 * identities — a verified address. A missing claim fails closed.
 */
async function requireBackupAccount(tx, db, auth) {
  if (signInProviderOf(auth) === 'anonymous') {
    throw new HttpsError('failed-precondition', 'PERSISTENT_ACCOUNT_REQUIRED');
  }
  if (signInProviderOf(auth) === 'password' && auth.token.email_verified !== true) {
    throw new HttpsError('failed-precondition', 'EMAIL_VERIFICATION_REQUIRED');
  }
  await readActiveProfile(tx, db, auth.uid);
}

function requireAuth(request) {
  if (!request.auth) throw new HttpsError('unauthenticated', 'sign-in required');
  return request.auth;
}

function createInitializeBackupKeyBundleHandler({ db }) {
  return async function initializeBackupKeyBundleHandler(request) {
    const auth = requireAuth(request);
    const validation = validateInitializeInput(request.data);
    if (!validation.ok) {
      logger.warn('initializeBackupKeyBundle rejected', { reason: validation.reason });
      throw new HttpsError('invalid-argument', validation.reason);
    }
    const bundleRef = db.doc(BUNDLE_PATH(auth.uid));

    return db.runTransaction(async (tx) => {
      await requireBackupAccount(tx, db, auth);
      const snap = await tx.get(bundleRef);
      if (snap.exists) {
        const current = snap.data();
        // Idempotent replay of the same creation: the wrappers are the full immutable
        // identity of this key bundle, so byte equality proves it is the same operation.
        if (sameBundleCore(current, request.data.bundle)) {
          return { ...publicBundle(current, current.revision), replayed: true };
        }
        logger.warn('initializeBackupKeyBundle rejected', {
          reason: 'ALREADY_INITIALIZED',
          uidHash: hashForLog(auth.uid),
        });
        throw new HttpsError('already-exists', 'ALREADY_INITIALIZED');
      }
      tx.set(bundleRef, {
        ...request.data.bundle,
        revision: 1,
        createdAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });
      logger.info('initializeBackupKeyBundle', { uidHash: hashForLog(auth.uid) });
      return { ...request.data.bundle, revision: 1, replayed: false };
    });
  };
}

function createUpdateBackupKeyBundleHandler({ db }) {
  return async function updateBackupKeyBundleHandler(request) {
    const auth = requireAuth(request);
    const validation = validateUpdateInput(request.data);
    if (!validation.ok) {
      logger.warn('updateBackupKeyBundle rejected', { reason: validation.reason });
      throw new HttpsError('invalid-argument', validation.reason);
    }
    const { expectedRevision, bundle } = request.data;
    const bundleRef = db.doc(BUNDLE_PATH(auth.uid));

    return db.runTransaction(async (tx) => {
      await requireBackupAccount(tx, db, auth);
      const snap = await tx.get(bundleRef);
      if (!snap.exists) throw new HttpsError('failed-precondition', 'NOT_INITIALIZED');
      const current = snap.data();
      // §6.5: compare-and-set. The client re-reads and re-unlocks before retrying.
      if (current.revision !== expectedRevision) {
        throw new HttpsError('aborted', 'REVISION_CONFLICT', {
          currentRevision: current.revision,
        });
      }
      // The ABK identity, envelope version and pinned KDF parameters never change here.
      if (!rotationChangedOnlyAllowedFields(current, bundle)) {
        throw new HttpsError('failed-precondition', 'BUNDLE_IDENTITY_CHANGED');
      }
      const next = {
        ...bundle,
        revision: current.revision + 1,
        createdAt: current.createdAt,
        updatedAt: FieldValue.serverTimestamp(),
      };
      tx.set(bundleRef, next);
      logger.info('updateBackupKeyBundle', {
        uidHash: hashForLog(auth.uid),
        revision: next.revision,
      });
      return { ...bundle, revision: next.revision, replayed: false };
    });
  };
}

function createInitializeBackupKeyBundle({ db } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 30 },
    createInitializeBackupKeyBundleHandler({ db: db || getFirestore() }),
  );
}

function createUpdateBackupKeyBundle({ db } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 30 },
    createUpdateBackupKeyBundleHandler({ db: db || getFirestore() }),
  );
}

module.exports = {
  createInitializeBackupKeyBundle,
  createUpdateBackupKeyBundle,
  createInitializeBackupKeyBundleHandler,
  createUpdateBackupKeyBundleHandler,
  requireBackupAccount,
  signInProviderOf,
};

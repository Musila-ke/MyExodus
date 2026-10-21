'use strict';
// AD-10: the per-installation dispatch record state machine, shared by the relay trigger
// (Step 5) and the Cloud Scheduler retry sweep (Step 6). One record per (messageId,
// installationId) at /relay-dispatch/{messageId}/installations/{installationId}. The
// decisions live in dispatch-logic.js (pure, node-tested); this file is the admin-SDK wiring.
//
// At-least-once semantics: a re-attempt that finds DISPATCHED does nothing; a PENDING or
// FAILED_RETRYABLE record may legitimately be dispatched again — that is the contract, not
// a bug. The record also carries recipientUid: the path has no recipientUid and the
// installation doc lives under /users/{uid}, so the sweep could not address it otherwise.

const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const { logger } = require('firebase-functions');

const { dispatchViaProvider, isTokenInvalid } = require('./dispatch');
const { createAttemptDispatch } = require('./dispatch-logic');

const db = getFirestore();

function recordRef(messageId, installationId) {
  return db.doc(`relay-dispatch/${messageId}/installations/${installationId}`);
}

const attemptDispatch = createAttemptDispatch({
  readRecord: async (recipientUid, messageId, installationId) => {
    const snap = await recordRef(messageId, installationId).get();
    return snap.exists ? snap.data() : null;
  },
  // lastAttemptAt = the time of this record write (attempt start, outcome, or dead-end) —
  // also the §14 T2 basis (mailbox createdAt → a DISPATCHED record's lastAttemptAt).
  writeRecord: async (recipientUid, messageId, installationId, patch) => {
    await recordRef(messageId, installationId)
      .set({ ...patch, lastAttemptAt: FieldValue.serverTimestamp() }, { merge: true });
  },
  // §7 Step 9: a provider-UNREGISTERED token is removed, not retained. The tombstone
  // (`stale`) keeps the install out of dispatch, but it is NOT evidence the app was
  // uninstalled — the next successful registration rewrites a fresh token and clears it.
  markInstallationStale: async (recipientUid, installationId) => {
    await db.doc(`users/${recipientUid}/push_installations/${installationId}`)
      .set({
        stale: true,
        staleAt: FieldValue.serverTimestamp(),
        token: FieldValue.delete(),
      }, { merge: true });
  },
  dispatch: dispatchViaProvider,
  isTokenInvalid,
  logger,
});

/** The sweep's dead-end: the installation row is gone or already stale, so retrying it
 *  can only burn attempts (AD-18c — a stale token never comes back). */
async function markPushInstallationGone(record) {
  await record.ref.set({ status: 'FAILED_TERMINAL', lastAttemptAt: FieldValue.serverTimestamp() }, { merge: true });
}

module.exports = { attemptDispatch, markPushInstallationGone };

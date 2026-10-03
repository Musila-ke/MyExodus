'use strict';
// Plan §8 Step 5 / AD-10: the mailbox relay. A mailbox document created by a sender's
// SyncWorker (doc id = the client-generated messageId) triggers: format+size validation
// (AD-19), a defense-in-depth pairing re-check (AD-02), the AD-23 rate limits, then one
// dispatch record per registered push installation (at-least-once provider dispatch;
// exactly-once client processing comes from the receiver's ProcessedEvents dedup, AD-10b).
//
// Retries are NOT this trigger's job: whole-event retry would re-run rate limiting and the
// pairing check redundantly and cannot express per-installation partial failure. The
// Cloud Scheduler sweep (Step 6, AD-10c) is the single retry source for dispatch.

const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const { onDocumentCreated } = require('firebase-functions/v2/firestore');
const { logger } = require('firebase-functions');

require('./fcm-transport'); // registers the 'fcm' transport (AD-04)
const { attemptDispatch } = require('./dispatch-record');
const {
  createRelayHandler,
  incrementWindows,
  pairingUsableForRelay,
  selectInstallations,
} = require('./relay-logic');

const REGION = 'us-central1';
const db = getFirestore();

/** AD-02 defense in depth: the relay never trusts the rules alone. `terminated` is accepted
 *  (members-bound) — see [pairingUsableForRelay] for why that is race-closing, not a hole. */
async function pairingActive(pairId, senderUid, recipientUid) {
  if (typeof pairId !== 'string' || typeof senderUid !== 'string') return false;
  const snap = await db.doc(`pairings/${pairId}`).get();
  if (!snap.exists) return false;
  return pairingUsableForRelay(snap.data(), senderUid, recipientUid);
}

/**
 * AD-23: fixed-window counters in `/rate-limits/{pairId}` — 10 events per pair per minute,
 * 200 messages per sender per day. One transaction so concurrent triggers cannot both pass
 * on the last slot. (The `senders` map is written whole: Firestore merge is shallow and a
 * nested map set would replace it.)
 *
 * AD-23's third threshold — 5 SOS events per pair per hour — was dropped by user ruling
 * 2026-09-18 (ADR #42): the mailbox contract has no event type and the payload is
 * ciphertext, so counting SOS here would require labeling the most sensitive event to the
 * untrusted relay. The volume windows bound any flood; SOS-specific pacing stays the
 * client-side cooldown (§8 Step 12b) as the accident control.
 */
async function checkAndIncrementRateLimit(pairId, senderUid, now) {
  const ref = db.doc(`rate-limits/${pairId}`);
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const decision = incrementWindows(snap.exists ? snap.data() : {}, senderUid, now);
    if (!decision.allowed) return false;
    tx.set(ref, { pair: decision.pair, senders: decision.senders, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    return true;
  });
}

async function markMailbox(recipientUid, messageId, pairId, relayStatus) {
  await db.doc(`mailboxes/${recipientUid}/messages/${messageId}`)
    .set({ relayStatus, relayedAt: FieldValue.serverTimestamp() }, { merge: true });
  // pairId in the log line is what §14's per-pair rate-limit rejection metric counts.
  logger.warn('mailbox rejected', { messageId, pairId, relayStatus });
}

async function listInstallations(recipientUid) {
  const snap = await db.collection(`users/${recipientUid}/push_installations`).get();
  return selectInstallations(snap.docs.map((d) => ({ id: d.id, data: d.data() })), Date.now());
}

exports.relayMailboxMessage = onDocumentCreated(
  {
    region: REGION,
    document: 'mailboxes/{recipientUid}/messages/{messageId}',
    retry: false, // Step 6 sweep owns retries (AD-10c)
    // AD-20a: placeholder bound until OQ-1 (load estimate) is answered.
    maxInstances: 10,
  },
  createRelayHandler({
    pairingActive,
    checkRateLimit: checkAndIncrementRateLimit,
    markMailbox,
    listInstallations,
    dispatch: attemptDispatch,
    logger,
  }),
);

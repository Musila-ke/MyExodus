'use strict';
// AD-18b / plan §12: the mailbox retention job. Mailbox documents are never deleted by the
// client (rules: `allow delete: if false`) — this scheduled function is the single deleter.
// A document goes when it has aged past the 7-day floor and every registered non-stale
// installation has acked it, or unconditionally at the 30-day cap (mailbox-retention.js).
//
// Plan §7 step 11: `cleanupIdempotencyRecords` — §10.12 retention for the pairing-path
// idempotency records (24h). Retention is cost hygiene only, never correctness: a record
// served after its expiry still holds the same committed result it was cached with
// (pairing-guards.js).

const { getFirestore, Timestamp } = require('firebase-admin/firestore');
const { onSchedule } = require('firebase-functions/v2/scheduler');
const { logger } = require('firebase-functions');

const { MIN_RETENTION_MS, shouldDeleteMailbox, unackedInstallationIds } = require('./mailbox-retention');

const REGION = 'us-central1';
const db = getFirestore();
// Per-run bound; a remainder just waits for the next daily run (over-retention is a cost
// nit, never data loss — deletion is the irreversible direction, keep it incremental).
const CLEANUP_BATCH = 500;

exports.cleanupMailboxes = onSchedule(
  {
    region: REGION,
    schedule: '23 3 * * *', // daily, off the top of the hour
    timeZone: 'UTC',
    maxInstances: 1,
    timeoutSeconds: 300,
  },
  async () => {
    const now = Date.now();
    const belowFloor = Timestamp.fromMillis(now - MIN_RETENTION_MS);
    const old = await db.collectionGroup('messages')
      .where('timestamp', '<', belowFloor)
      .orderBy('timestamp')
      .limit(CLEANUP_BATCH)
      .get();
    if (old.empty) return;

    let deleted = 0;
    for (const doc of old.docs) {
      const recipientUid = doc.ref.parent.parent.id;
      const ageMs = now - doc.get('timestamp').toMillis();
      if (!shouldDeleteMailbox(ageMs, await allAcked(doc, recipientUid))) continue;
      // recursiveDelete takes the acks subcollection with it (a plain doc delete would
      // orphan them under a vanished parent).
      await db.recursiveDelete(doc.ref);
      deleted += 1;
    }
    logger.info('mailbox cleanup', { scanned: old.size, deleted });
  },
);

exports.cleanupIdempotencyRecords = onSchedule(
  {
    region: REGION,
    schedule: '37 3 * * *', // daily, before the mailbox job and off the top of the hour
    timeZone: 'UTC',
    maxInstances: 1,
    timeoutSeconds: 300,
  },
  async () => {
    const expired = await db.collection('idempotency')
      .where('expiresAt', '<', Timestamp.now())
      .orderBy('expiresAt')
      .limit(CLEANUP_BATCH)
      .get();
    if (expired.empty) return;
    // A plain delete is right here: idempotency records have no subcollections.
    const batch = db.batch();
    expired.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
    logger.info('idempotency cleanup', { deleted: expired.size });
  },
);

// §8.8: event token-bucket documents are server-only and expire through scheduled cleanup.
// A day idle restores any bucket to its full burst capacity, so dropping the document is
// equivalent to refilling it — retention is cost hygiene, never correctness.
exports.cleanupEventRateLimits = onSchedule(
  {
    region: REGION,
    schedule: '53 3 * * *',
    timeZone: 'UTC',
    maxInstances: 1,
    timeoutSeconds: 300,
  },
  async () => {
    const stale = await db.collection('eventRateLimits')
      .where('updatedAt', '<', Timestamp.fromMillis(Date.now() - 24 * 60 * 60 * 1000))
      .orderBy('updatedAt')
      .limit(CLEANUP_BATCH)
      .get();
    if (stale.empty) return;
    const batch = db.batch();
    stale.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
    logger.info('eventRateLimits cleanup', { deleted: stale.size });
  },
);

async function allAcked(messageDoc, recipientUid) {
  const [instSnap, ackSnap] = await Promise.all([
    db.collection(`users/${recipientUid}/push_installations`).get(),
    messageDoc.ref.collection('acks').get(),
  ]);
  const acked = new Set(ackSnap.docs.map((d) => d.id));
  const unacked = unackedInstallationIds(instSnap.docs.map((d) => ({ id: d.id, ...d.data() })), acked);
  return unacked.length === 0;
}

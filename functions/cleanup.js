'use strict';
// AD-18b / plan §12: the mailbox retention job. Mailbox documents are never deleted by the
// client (rules: `allow delete: if false`) — this scheduled function is the single deleter.
// A document goes when it has aged past the 7-day floor and every registered non-stale
// installation has acked it, or unconditionally at the 30-day cap (mailbox-retention.js).

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

async function allAcked(messageDoc, recipientUid) {
  const [instSnap, ackSnap] = await Promise.all([
    db.collection(`users/${recipientUid}/push_installations`).get(),
    messageDoc.ref.collection('acks').get(),
  ]);
  const acked = new Set(ackSnap.docs.map((d) => d.id));
  const unacked = unackedInstallationIds(instSnap.docs.map((d) => ({ id: d.id, ...d.data() })), acked);
  return unacked.length === 0;
}

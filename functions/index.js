'use strict';
// Partner Pillar v2 Cloud Functions entry point (plan §8 Steps 3/5/6/18 — Step 6 and the
// Step 18 additions land in later commits; Step 3 (pairing registration) is defined here,
// Step 5 (the mailbox relay trigger) lives in ./relay.js and is re-exported below).
//
// Region invariant (AD-17a/b): the Firestore database and every function must be deployed to
// the same region. us-central1 is the agreed initial default; revisit per OQ-4 without ever
// separating the two.

const { initializeApp } = require('firebase-admin/app');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { logger } = require('firebase-functions');

const { verifyProof } = require('./proof');

initializeApp();
const db = getFirestore();

const REGION = 'us-central1';
const PAIRING_WINDOW_MS = 5 * 60 * 1000;

/**
 * AD-02: pairId = min(uidA, uidB) + "_" + max(uidA, uidB). Computed here — never trusted
 * from the client — so both devices address the same pairing document by construction.
 */
function pairIdOf(uidA, uidB) {
  const [min, max] = uidA < uidB ? [uidA, uidB] : [uidB, uidA];
  return `${min}_${max}`;
}

/**
 * §8 Step 3 / AD-02: the authoritative pairing record. Client writes are denied by rules;
 * this function is the only writer. Both devices submit a proof on enrollment completion;
 * the record flips pending → active only when both proofs are present within a 5-minute
 * window and their transcriptHash values match (the transcript binds the session id and
 * both agreement keys, so a matching transcriptHash means both devices saw the same SAS
 * transcript). Signature verification proves key possession; hardware attestation is
 * verified device-to-device at enrollment (unchanged, §3).
 *
 * Idempotent: re-submitting the same proof while active returns the current state, so a
 * client retry after a dropped response is safe.
 */
exports.registerPairing = onCall(
  {
    region: REGION,
    // Task #21 / AD-03: callable-level App Check enforcement. This is the money/abuse path
    // (writes the authoritative pairing record); the client SDK attaches the App Check token
    // automatically (PlayIntegrity in production, debug provider in instrumented tests).
    // Firestore-triggered background functions (relay.js) are NOT enforceable this way and
    // are not — AD-03's own reading.
    enforceAppCheck: true,
    // AD-20a: replaced with the load-derived value once OQ-1 (peak events/second) is
    // answered; until then this cap bounds cost and acts as the backpressure boundary.
    maxInstances: 10,
  },
  async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'anonymous sign-in required');

    const proof = request.data;
    const check = verifyProof(proof);
    if (!check.ok) {
      logger.warn('registerPairing rejected', { reason: check.reason });
      throw new HttpsError('invalid-argument', check.reason);
    }
    if (proof.ownUid !== auth.uid) {
      throw new HttpsError('permission-denied', 'ownUid must be the authenticated user');
    }

    const pairId = pairIdOf(proof.ownUid, proof.partnerUid);
    const ref = db.doc(`pairings/${pairId}`);
    const now = Date.now();
    const entry = {
      publicKeyHash: proof.publicKeyHash,
      transcriptHash: proof.transcriptHash,
      protocolVersion: proof.protocolVersion,
      at: now,
    };

    return db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      const existing = snap.exists ? snap.data() : null;

      if (existing && existing.status === 'active') {
        const sameProof = existing.proofs && existing.proofs[auth.uid] &&
          existing.proofs[auth.uid].transcriptHash === proof.transcriptHash;
        if (!sameProof) throw new HttpsError('failed-precondition', 'PAIRING_ACTIVE');
        return { pairId, status: 'active', pairingEpoch: existing.pairingEpoch };
      }

      // A terminated record means a re-pair: pairingEpoch increments (AD-07 replay bound).
      const priorEpoch = existing && typeof existing.pairingEpoch === 'number' ? existing.pairingEpoch : 0;

      // A pending record only survives its 5-minute window; past it the attempt restarts.
      const pendingFresh = existing && existing.status === 'pending' &&
        now - (existing.pendingSince || 0) <= PAIRING_WINDOW_MS;
      const proofs = pendingFresh ? { ...(existing.proofs || {}) } : {};
      proofs[auth.uid] = entry;

      const other = proofs[proof.partnerUid];
      let status = 'pending';
      if (other) {
        if (now - other.at > PAIRING_WINDOW_MS) {
          delete proofs[proof.partnerUid]; // stale half of a previous attempt
        } else if (other.transcriptHash !== proof.transcriptHash) {
          throw new HttpsError('failed-precondition', 'TRANSCRIPT_MISMATCH');
        } else if (other.publicKeyHash === proof.publicKeyHash) {
          throw new HttpsError('failed-precondition', 'SAME_KEY');
        } else {
          status = 'active';
        }
      }

      const [uidA, uidB] = proof.ownUid < proof.partnerUid
        ? [proof.ownUid, proof.partnerUid]
        : [proof.partnerUid, proof.ownUid];

      // A retrying device refreshes the window (its proof's `at` is refreshed too), so
      // a submission counts as "arriving" on every retry — the 5-minute rule then
      // measures from each device's last attempt. Terminated records keep the old
      // pendingSince for reference; it is inert once status is active.
      const pendingSince = status === 'pending' ? now : (existing && existing.pendingSince) || now;

      const doc = {
        uidA,
        uidB,
        members: [uidA, uidB],
        status,
        pairingEpoch: priorEpoch + 1,
        proofs,
        pendingSince,
      };
      if (!existing) doc.createdAt = FieldValue.serverTimestamp();
      if (status === 'active') doc.activatedAt = FieldValue.serverTimestamp();

      tx.set(ref, doc, { merge: false });
      logger.info('registerPairing', { pairId, status });
      return { pairId, status, pairingEpoch: doc.pairingEpoch };
    });
  },
);

// Exported for the function test suite once Step 20 wires the Functions emulator.
exports._pairIdOf = pairIdOf;

// §8 Step 5 / AD-10: mailbox-create trigger → validation, pairing re-check, rate limits,
// per-installation push dispatch. Re-exported so the deploy unit sees it at the root.
const { relayMailboxMessage } = require('./relay');
exports.relayMailboxMessage = relayMailboxMessage;

// §8 Step 6 / AD-10c: every-2-minutes dispatch retry sweep (the single retry source).
const { retryDispatchSweep } = require('./sweep');
exports.retryDispatchSweep = retryDispatchSweep;

// AD-18b: daily mailbox retention job — the only deleter of mailbox documents.
const { cleanupMailboxes } = require('./cleanup');
exports.cleanupMailboxes = cleanupMailboxes;

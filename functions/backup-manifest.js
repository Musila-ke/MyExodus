'use strict';
// Account plan §7 Release 2 step 14 / §6.4 items 11-14 / §8.6: the upload reservation and
// manifest commit callables.
//
//   beginBackup              persistent, verified, active account only; server feature gate;
//                            five reservations per rolling 24h, 105 MiB aggregate, at most one
//                            outstanding reservation (a new request supersedes an abandoned
//                            one, it never leaves two live paths). Binds the reservation to
//                            the exact base pair (or the exact pointer explicitly confirmed
//                            for replacement).
//   extendBackupReservation  keeps an accepted upload alive; never moves the seven-day
//                            ceiling and is deliberately not behind the new-work gate.
//   commitBackupManifest     streams the exact Storage object generation through SHA-256 and
//                            CRC32C, verifies path/size/content type, then commits the
//                            immutable manifest and the pointer in one transaction against
//                            the reservation's anchor. Client hashes are compared, never
//                            trusted, and the stored hash is the server-computed one.
//   beginBackupRestore       §6.6 item 6: pins one committed snapshot against retention
//                            while a device downloads it; one live pin per UID, one hour.
//   endBackupRestore         clears the pin; idempotent and harmless to lose (the pin expires).
//   cleanupBackupOrphans     daily: expired reservations older than the 24-hour grace lose
//                            their unreferenced object; idempotent and reference-checked.
//
// Logging (§10.14): uids appear only as short hashes; object bytes, hashes and manifests are
// never logged.

const crypto = require('node:crypto');
const { getFirestore, FieldValue, Timestamp } = require('firebase-admin/firestore');
const { getStorage } = require('firebase-admin/storage');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { onSchedule } = require('firebase-functions/v2/scheduler');
const { logger } = require('firebase-functions');

const { requireBackupAccount } = require('./backup-key');
const {
  MAX_OBJECT_BYTES,
  ORPHAN_GRACE_MS,
  RESERVATION_TTL_MS,
  RESERVATION_MAX_MS,
  RESTORE_GUARD_TTL_MS,
  HEARTBEAT_MIN_MS,
  PATHS,
  validateBeginInput,
  validateCommitInput,
  validateExtendInput,
  validateRestoreInput,
  restoreGuardActive,
  classifyBase,
  samePair,
  advanceReservationWindow,
  reservationExpired,
  extendedExpiry,
  manifestRevisionFor,
  retentionPlan,
  Crc32c,
} = require('./backup-manifest-logic');

const REGION = 'us-central1';
const GATE_DOC = 'serverFeatureGates/backupUpload';

function hashForLog(value) {
  return crypto.createHash('sha256').update(value).digest('hex').slice(0, 12);
}

function requireAuth(request) {
  if (!request.auth) throw new HttpsError('unauthenticated', 'sign-in required');
  return request.auth;
}

/** §8.2/§8.10: missing or disabled gate denies a NEW reservation or commit. */
async function requireGate(tx, db) {
  const snap = await tx.get(db.doc(GATE_DOC));
  if (!snap.exists || snap.get('enabled') !== true) {
    throw new HttpsError('failed-precondition', 'FEATURE_DISABLED');
  }
}

/** The pointer and its manifest are one durable fact; a disagreement fails closed. */
async function readCurrent(tx, db, uid) {
  const pointerSnap = await tx.get(db.doc(PATHS.pointer(uid)));
  if (!pointerSnap.exists) {
    return { revision: null, sha256: null, pointer: null };
  }
  const pointer = pointerSnap.data();
  const manifestSnap = await tx.get(db.doc(PATHS.manifest(uid, pointer.backupId)));
  if (!manifestSnap.exists
    || manifestSnap.get('revision') !== pointer.revision
    || manifestSnap.get('sha256') !== pointer.sha256) {
    throw new HttpsError('failed-precondition', 'BACKUP_CONFLICT');
  }
  return { revision: pointer.revision, sha256: pointer.sha256, pointer };
}

function expiresAtMsOf(record) {
  const value = record && record.expiresAt;
  return value && typeof value.toMillis === 'function' ? value.toMillis() : 0;
}

function sameReservationRequest(record, input) {
  return record.schemaVersion === input.schemaVersion
    && record.compressedBytes === input.compressedBytes
    && record.objectBytes === input.objectBytes
    && record.baseRevision === input.baseRevision
    && record.baseSha256 === input.baseSha256
    && record.replaceCurrent === input.replaceCurrent
    && record.replaceRevision === input.replaceRevision
    && record.replaceSha256 === input.replaceSha256;
}

function createBeginBackupHandler({ db, now = () => Date.now() }) {
  return async function beginBackupHandler(request) {
    const auth = requireAuth(request);
    const validation = validateBeginInput(request.data);
    if (!validation.ok) {
      logger.warn('beginBackup rejected', { reason: validation.reason });
      throw new HttpsError('invalid-argument', validation.reason);
    }
    const input = request.data;
    const nowMs = now();
    const uid = auth.uid;
    const reservationRef = db.doc(PATHS.reservation(uid, input.backupId));
    const ledgerRef = db.doc(PATHS.ledger(uid));

    return db.runTransaction(async (tx) => {
      await requireBackupAccount(tx, db, auth);
      await requireGate(tx, db);

      const reservationSnap = await tx.get(reservationRef);
      if (reservationSnap.exists) {
        const existing = reservationSnap.data();
        // A lost response retried with the same request reuses the reservation and never
        // charges a second attempt. A reused id with different content is a contract break.
        if (!sameReservationRequest(existing, input)) {
          throw new HttpsError('invalid-argument', 'BACKUP_INVALID_REQUEST');
        }
        if (!reservationExpired(existing, nowMs)) {
          return {
            backupId: input.backupId,
            objectPath: existing.objectPath,
            expiresAtMs: expiresAtMsOf(existing),
            replayed: true,
          };
        }
        tx.delete(reservationRef);
      }

      const current = await readCurrent(tx, db, uid);
      const decision = classifyBase({
        currentRevision: current.revision,
        currentSha256: current.sha256,
        baseRevision: input.baseRevision,
        baseSha256: input.baseSha256,
        replaceCurrent: input.replaceCurrent,
        replaceRevision: input.replaceRevision,
        replaceSha256: input.replaceSha256,
      });
      if (!decision.ok) {
        logger.warn('beginBackup rejected', { reason: decision.reason, uidHash: hashForLog(uid) });
        throw new HttpsError('failed-precondition', decision.reason);
      }

      const ledgerSnap = await tx.get(ledgerRef);
      const ledger = ledgerSnap.exists ? ledgerSnap.data() : { attempts: [] };
      const outstanding = ledger.outstanding;
      if (outstanding && outstanding.backupId
        && typeof outstanding.expiresAtMs === 'number'
        && outstanding.expiresAtMs > nowMs
        && outstanding.backupId !== input.backupId) {
        // §8.6 one outstanding reservation: an abandoned upload is superseded, so the UID
        // never ends up with two writable storage paths. Its attempt stays on the ledger.
        tx.delete(db.doc(PATHS.reservation(uid, outstanding.backupId)));
        logger.info('beginBackup superseded', { uidHash: hashForLog(uid) });
      }

      const window = advanceReservationWindow(ledger, nowMs, input.objectBytes);
      if (!window.allowed) {
        throw new HttpsError('resource-exhausted', 'BACKUP_RESERVATION_LIMIT', {
          retryAfterMs: window.retryAfterMs,
        });
      }

      const expiresAtMs = nowMs + RESERVATION_TTL_MS;
      tx.set(reservationRef, {
        uid,
        backupId: input.backupId,
        objectPath: PATHS.object(uid, input.backupId),
        schemaVersion: input.schemaVersion,
        compressedBytes: input.compressedBytes,
        objectBytes: input.objectBytes,
        baseRevision: input.baseRevision,
        baseSha256: input.baseSha256,
        replaceCurrent: input.replaceCurrent,
        replaceRevision: input.replaceRevision,
        replaceSha256: input.replaceSha256,
        status: 'ACTIVE',
        createdAt: FieldValue.serverTimestamp(),
        createdAtMs: nowMs,
        maxExpiresAtMs: nowMs + RESERVATION_MAX_MS,
        heartbeatAtMs: nowMs,
        expiresAt: Timestamp.fromMillis(expiresAtMs),
        expiresAtMs,
      });
      tx.set(ledgerRef, {
        attempts: window.attempts,
        outstanding: { backupId: input.backupId, expiresAtMs },
        updatedAt: FieldValue.serverTimestamp(),
      });
      logger.info('beginBackup', { uidHash: hashForLog(uid), mode: decision.mode });
      return {
        backupId: input.backupId,
        objectPath: PATHS.object(uid, input.backupId),
        expiresAtMs,
        replayed: false,
      };
    });
  };
}

function createExtendBackupReservationHandler({ db, now = () => Date.now() }) {
  return async function extendBackupReservationHandler(request) {
    const auth = requireAuth(request);
    const validation = validateExtendInput(request.data);
    if (!validation.ok) {
      logger.warn('extendBackupReservation rejected', { reason: validation.reason });
      throw new HttpsError('invalid-argument', validation.reason);
    }
    const { backupId } = request.data;
    const nowMs = now();
    const uid = auth.uid;
    const reservationRef = db.doc(PATHS.reservation(uid, backupId));
    const ledgerRef = db.doc(PATHS.ledger(uid));

    return db.runTransaction(async (tx) => {
      await requireBackupAccount(tx, db, auth);
      const snap = await tx.get(reservationRef);
      if (!snap.exists) {
        throw new HttpsError('failed-precondition', 'BACKUP_RESERVATION_EXPIRED');
      }
      const reservation = snap.data();
      if (reservationExpired(reservation, nowMs)) {
        throw new HttpsError('failed-precondition', 'BACKUP_RESERVATION_EXPIRED');
      }
      const createdAtMs = typeof reservation.createdAtMs === 'number' ? reservation.createdAtMs : nowMs;
      const maxExpiresAtMs = typeof reservation.maxExpiresAtMs === 'number'
        ? reservation.maxExpiresAtMs
        : createdAtMs + RESERVATION_MAX_MS;
      const heartbeatAtMs = typeof reservation.heartbeatAtMs === 'number'
        ? reservation.heartbeatAtMs
        : createdAtMs;
      if (nowMs - heartbeatAtMs < HEARTBEAT_MIN_MS) {
        // §8.6: at most one extension per 10-minute heartbeat window; a second call in the
        // same window is a no-op, never an error.
        return { backupId, expiresAtMs: expiresAtMsOf(reservation), extended: false };
      }
      const expiresAtMs = extendedExpiry(maxExpiresAtMs, nowMs);
      if (expiresAtMs <= nowMs) {
        throw new HttpsError('failed-precondition', 'BACKUP_RESERVATION_EXPIRED');
      }
      // Firestore transactions require every read before the first write.
      const ledgerSnap = await tx.get(ledgerRef);
      tx.update(reservationRef, {
        expiresAt: Timestamp.fromMillis(expiresAtMs),
        expiresAtMs,
        heartbeatAtMs: nowMs,
      });
      if (ledgerSnap.exists && ledgerSnap.get('outstanding.backupId') === backupId) {
        tx.update(ledgerRef, {
          outstanding: { backupId, expiresAtMs },
          updatedAt: FieldValue.serverTimestamp(),
        });
      }
      return { backupId, expiresAtMs, extended: true };
    });
  };
}

/** §6.6 item 6: one live pin per UID shields the snapshot a device is restoring. */
function createBeginBackupRestoreHandler({ db, now = () => Date.now() }) {
  return async function beginBackupRestoreHandler(request) {
    const auth = requireAuth(request);
    const validation = validateRestoreInput(request.data);
    if (!validation.ok) {
      logger.warn('beginBackupRestore rejected', { reason: validation.reason });
      throw new HttpsError('invalid-argument', validation.reason);
    }
    const { backupId } = request.data;
    const nowMs = now();
    const uid = auth.uid;
    const restoreRef = db.doc(PATHS.activeRestore(uid));
    const manifestRef = db.doc(PATHS.manifest(uid, backupId));

    return db.runTransaction(async (tx) => {
      await requireBackupAccount(tx, db, auth);
      const manifestSnap = await tx.get(manifestRef);
      if (!manifestSnap.exists) {
        throw new HttpsError('failed-precondition', 'NO_CLOUD_BACKUP');
      }
      const restoreSnap = await tx.get(restoreRef);
      if (restoreSnap.exists
        && restoreSnap.get('backupId') === backupId
        && restoreGuardActive(restoreSnap.data(), nowMs)) {
        return { backupId, expiresAtMs: restoreSnap.get('expiresAtMs'), replayed: true };
      }
      // A newer restore supersedes the previous pin; the UID never shields two objects.
      const expiresAtMs = nowMs + RESTORE_GUARD_TTL_MS;
      tx.set(restoreRef, {
        uid,
        backupId,
        objectPath: manifestSnap.get('objectPath'),
        startedAtMs: nowMs,
        expiresAtMs,
        expiresAt: Timestamp.fromMillis(expiresAtMs),
        updatedAt: FieldValue.serverTimestamp(),
      });
      logger.info('beginBackupRestore', { uidHash: hashForLog(uid) });
      return { backupId, expiresAtMs, replayed: false };
    });
  };
}

/**
 * §6.6 item 6: clearing the pin is best effort — a lost clear only pins the object until
 * the one-hour guard expires — so this handler never fails on unrelated account state.
 */
function createEndBackupRestoreHandler({ db }) {
  return async function endBackupRestoreHandler(request) {
    const auth = requireAuth(request);
    const validation = validateRestoreInput(request.data);
    if (!validation.ok) {
      logger.warn('endBackupRestore rejected', { reason: validation.reason });
      throw new HttpsError('invalid-argument', validation.reason);
    }
    const { backupId } = request.data;
    const restoreRef = db.doc(PATHS.activeRestore(auth.uid));
    const snap = await restoreRef.get();
    const cleared = snap.exists && snap.get('backupId') === backupId;
    if (cleared) await restoreRef.delete();
    return { backupId, cleared };
  };
}

/**
 * §8.6: the backend reads the reserved object generation itself. The client hash and any
 * custom metadata are claims; the stored manifest hash is computed here.
 */
async function verifyObject({ bucket, reservation, declaredSha256, declaredBytes }) {
  const file = bucket.file(reservation.objectPath);
  let metadata;
  try {
    [metadata] = await file.getMetadata();
  } catch (e) {
    if (e && (e.code === 404 || e.code === '404')) {
      throw new HttpsError('failed-precondition', 'BACKUP_OBJECT_MISSING');
    }
    throw e;
  }
  if (!metadata
    || metadata.contentType !== 'application/octet-stream'
    || String(metadata.size) !== String(reservation.objectBytes)
    || reservation.objectBytes !== declaredBytes
    || reservation.objectBytes > MAX_OBJECT_BYTES) {
    throw new HttpsError('failed-precondition', 'BACKUP_OBJECT_MISMATCH');
  }
  const generation = metadata.generation === undefined ? null : String(metadata.generation);
  if (!generation) throw new HttpsError('failed-precondition', 'BACKUP_OBJECT_MISMATCH');

  const hasher = crypto.createHash('sha256');
  const crc = new Crc32c();
  let seen = 0;
  await new Promise((resolve, reject) => {
    const stream = file.createReadStream();
    stream.on('data', (chunk) => {
      seen += chunk.length;
      if (seen > MAX_OBJECT_BYTES) {
        stream.destroy();
        reject(new HttpsError('failed-precondition', 'BACKUP_OBJECT_MISMATCH'));
        return;
      }
      hasher.update(chunk);
      crc.update(chunk);
    });
    stream.on('error', reject);
    stream.on('end', resolve);
  });

  const sha256 = hasher.digest('hex');
  if (seen !== reservation.objectBytes
    || sha256 !== declaredSha256
    || !metadata.crc32c
    || metadata.crc32c !== crc.digestBase64()) {
    throw new HttpsError('failed-precondition', 'BACKUP_OBJECT_MISMATCH');
  }
  return { sha256, generation };
}

function createCommitBackupManifestHandler({ db, bucket, now = () => Date.now() }) {
  if (!bucket) throw new Error('commitBackupManifest requires a storage bucket');
  return async function commitBackupManifestHandler(request) {
    const auth = requireAuth(request);
    const validation = validateCommitInput(request.data);
    if (!validation.ok) {
      logger.warn('commitBackupManifest rejected', { reason: validation.reason });
      throw new HttpsError('invalid-argument', validation.reason);
    }
    const { backupId, sha256: declaredSha256, objectBytes } = request.data;
    const uid = auth.uid;
    const reservationRef = db.doc(PATHS.reservation(uid, backupId));
    const manifestRef = db.doc(PATHS.manifest(uid, backupId));
    const pointerRef = db.doc(PATHS.pointer(uid));
    const ledgerRef = db.doc(PATHS.ledger(uid));
    const nowMs = now();

    // Replay of a commit whose response was lost: the manifest is the authoritative result.
    const replay = await db.runTransaction(async (tx) => {
      await requireBackupAccount(tx, db, auth);
      const reservationSnap = await tx.get(reservationRef);
      if (reservationSnap.exists) return null;
      const manifestSnap = await tx.get(manifestRef);
      if (!manifestSnap.exists) return null;
      if (manifestSnap.get('sha256') !== declaredSha256
        || manifestSnap.get('objectBytes') !== objectBytes) return null;
      return { revision: manifestSnap.get('revision'), sha256: manifestSnap.get('sha256'), replayed: true };
    });
    if (replay) return replay;

    const reservationRefData = await db.runTransaction(async (tx) => {
      await requireBackupAccount(tx, db, auth);
      await requireGate(tx, db);
      const snap = await tx.get(reservationRef);
      if (!snap.exists) throw new HttpsError('failed-precondition', 'BACKUP_RESERVATION_EXPIRED');
      const reservation = snap.data();
      if (reservationExpired(reservation, nowMs)) {
        throw new HttpsError('failed-precondition', 'BACKUP_RESERVATION_EXPIRED');
      }
      return reservation;
    });

    let verified;
    try {
      verified = await verifyObject({
        bucket,
        reservation: reservationRefData,
        declaredSha256,
        declaredBytes: objectBytes,
      });
    } catch (e) {
      // §14 "backup manifest hash failures": the metric anchors on this line.
      logger.warn('commitBackupManifest object rejected', {
        uidHash: hashForLog(uid),
        reason: e && e.message ? e.message : 'unknown',
      });
      throw e;
    }
    const { sha256, generation } = verified;

    return db.runTransaction(async (tx) => {
      await requireBackupAccount(tx, db, auth);
      await requireGate(tx, db);

      const reservationSnap = await tx.get(reservationRef);
      if (!reservationSnap.exists) {
        // A concurrent commit of the same object already completed.
        const manifestSnap = await tx.get(manifestRef);
        if (manifestSnap.exists && manifestSnap.get('sha256') === sha256) {
          return { revision: manifestSnap.get('revision'), sha256, replayed: true };
        }
        throw new HttpsError('aborted', 'BACKUP_CONFLICT');
      }
      const reservation = reservationSnap.data();
      if (reservationExpired(reservation, nowMs)) {
        throw new HttpsError('failed-precondition', 'BACKUP_RESERVATION_EXPIRED');
      }
      if (reservation.objectPath !== PATHS.object(uid, backupId)) {
        throw new HttpsError('failed-precondition', 'BACKUP_CONFLICT');
      }

      const current = await readCurrent(tx, db, uid);
      const anchor = reservation.replaceCurrent
        ? { revision: reservation.replaceRevision, sha256: reservation.replaceSha256 }
        : { revision: reservation.baseRevision, sha256: reservation.baseSha256 };
      const normalizedAnchor = anchor.revision === null ? null : anchor;
      if (!samePair(
        current.revision === null ? null : { revision: current.revision, sha256: current.sha256 },
        normalizedAnchor,
      )) {
        logger.warn('commitBackupManifest conflict', { uidHash: hashForLog(uid) });
        throw new HttpsError('aborted', 'BACKUP_CONFLICT');
      }
      // Firestore transactions require every read before the first write.
      const ledgerSnap = await tx.get(ledgerRef);

      const revision = manifestRevisionFor(current.revision);
      const manifest = {
        backupId,
        revision,
        sha256,
        objectPath: reservation.objectPath,
        generation,
        objectBytes: reservation.objectBytes,
        compressedBytes: reservation.compressedBytes,
        schemaVersion: reservation.schemaVersion,
        envelopeVersion: 1,
        baseRevision: reservation.baseRevision,
        baseSha256: reservation.baseSha256,
        createdAt: FieldValue.serverTimestamp(),
      };
      // `create` makes the manifest immutable even against a retry with different bytes.
      tx.create(manifestRef, manifest);
      tx.set(pointerRef, {
        backupId,
        revision,
        sha256,
        objectPath: reservation.objectPath,
        generation,
        objectBytes: reservation.objectBytes,
        schemaVersion: reservation.schemaVersion,
        updatedAt: FieldValue.serverTimestamp(),
      });
      tx.delete(reservationRef);
      if (ledgerSnap.exists && ledgerSnap.get('outstanding.backupId') === backupId) {
        tx.update(ledgerRef, {
          outstanding: null,
          updatedAt: FieldValue.serverTimestamp(),
        });
      }
      logger.info('commitBackupManifest', { uidHash: hashForLog(uid), revision });
      return { revision, sha256, replayed: false };
    }).then(async (result) => {
      await applyRetention({ db, bucket, uid, currentBackupId: backupId });
      return result;
    });
  };
}

/**
 * §8.6: retain the current pointer plus two prior successful snapshots. A snapshot is only
 * deleted when nothing references it: not the pointer, not a retained manifest, not a live
 * reservation, not a live restore pin (§6.6 item 6; the pin itself expires after an hour).
 */
async function applyRetention({ db, bucket, uid, currentBackupId, nowMs = Date.now() }) {
  try {
    const snap = await db.collection(PATHS.manifests(uid)).orderBy('revision', 'desc').limit(20).get();
    const manifests = snap.docs.map((doc) => ({ backupId: doc.id, ...doc.data() }));
    const doomed = retentionPlan(manifests, currentBackupId);
    if (doomed.length === 0) return;

    const restoreSnap = await db.doc(PATHS.activeRestore(uid)).get();
    const activeRestorePath = restoreSnap.exists && restoreGuardActive(restoreSnap.data(), nowMs)
      ? restoreSnap.get('objectPath')
      : null;
    for (const backupId of doomed) {
      const manifest = manifests.find((m) => m.backupId === backupId);
      if (!manifest || manifest.objectPath === activeRestorePath) continue;
      const reservationSnap = await db.doc(PATHS.reservation(uid, backupId)).get();
      if (reservationSnap.exists) continue;
      const file = bucket.file(manifest.objectPath);
      try {
        const [metadata] = await file.getMetadata();
        await file.delete({ ifGenerationMatch: String(metadata.generation), ignoreNotFound: true });
      } catch (e) {
        if (!e || (e.code !== 404 && e.code !== '404')) {
          logger.warn('backup retention object delete failed', { uidHash: hashForLog(uid) });
          continue;
        }
      }
      await db.doc(PATHS.manifest(uid, backupId)).delete();
    }
  } catch (e) {
    // Retention is cost hygiene; the commit already succeeded and stays truthful.
    logger.warn('backup retention failed', { uidHash: hashForLog(uid), reason: e && e.code });
  }
}

function createCleanupBackupOrphansHandler({ db, bucket, now = () => Date.now() }) {
  return async function cleanupBackupOrphansHandler() {
    const nowMs = now();
    const cutoff = Timestamp.fromMillis(nowMs - ORPHAN_GRACE_MS);
    const expired = await db.collectionGroup('reservations')
      .where('expiresAt', '<=', cutoff)
      .orderBy('expiresAt')
      .limit(200)
      .get();
    if (expired.empty) return;

    let cleaned = 0;
    for (const doc of expired.docs) {
      const uid = doc.ref.parent.parent.id;
      const backupId = doc.id;
      const manifestSnap = await db.doc(PATHS.manifest(uid, backupId)).get();
      if (!manifestSnap.exists) {
        const objectPath = typeof doc.get('objectPath') === 'string'
          ? doc.get('objectPath')
          : PATHS.object(uid, backupId);
        try {
          const file = bucket.file(objectPath);
          const [metadata] = await file.getMetadata();
          await file.delete({ ifGenerationMatch: String(metadata.generation), ignoreNotFound: true });
        } catch (e) {
          if (!e || (e.code !== 404 && e.code !== '404')) {
            logger.warn('backup orphan object delete failed', { uidHash: hashForLog(uid) });
            continue;
          }
        }
      }
      await doc.ref.delete();
      await db.runTransaction(async (tx) => {
        const ledgerRef = db.doc(PATHS.ledger(uid));
        const ledgerSnap = await tx.get(ledgerRef);
        if (ledgerSnap.exists && ledgerSnap.get('outstanding.backupId') === backupId) {
          tx.update(ledgerRef, { outstanding: null, updatedAt: FieldValue.serverTimestamp() });
        }
      });
      cleaned += 1;
    }
    logger.info('backup orphan cleanup', { scanned: expired.size, cleaned });
  };
}

function createBeginBackup({ db } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 30 },
    createBeginBackupHandler({ db: db || getFirestore() }),
  );
}

function createExtendBackupReservation({ db } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 30 },
    createExtendBackupReservationHandler({ db: db || getFirestore() }),
  );
}

function createBeginBackupRestore({ db } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 30 },
    createBeginBackupRestoreHandler({ db: db || getFirestore() }),
  );
}

function createEndBackupRestore({ db } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 30 },
    createEndBackupRestoreHandler({ db: db || getFirestore() }),
  );
}

function createCommitBackupManifest({ db, bucket } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 60, memory: '512MiB' },
    createCommitBackupManifestHandler({
      db: db || getFirestore(),
      bucket: bucket || getStorage().bucket(),
    }),
  );
}

function createCleanupBackupOrphans({ db, bucket } = {}) {
  return onSchedule(
    {
      region: REGION,
      schedule: '43 4 * * *',
      timeZone: 'UTC',
      maxInstances: 1,
      timeoutSeconds: 300,
    },
    createCleanupBackupOrphansHandler({
      db: db || getFirestore(),
      bucket: bucket || getStorage().bucket(),
    }),
  );
}

module.exports = {
  createBeginBackup,
  createExtendBackupReservation,
  createBeginBackupRestore,
  createEndBackupRestore,
  createCommitBackupManifest,
  createCleanupBackupOrphans,
  createBeginBackupHandler,
  createExtendBackupReservationHandler,
  createBeginBackupRestoreHandler,
  createEndBackupRestoreHandler,
  createCommitBackupManifestHandler,
  createCleanupBackupOrphansHandler,
  verifyObject,
  applyRetention,
};

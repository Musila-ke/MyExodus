'use strict';
// Account plan §7 Release 2 step 14 / §6.4 item 11-13 / §8.6: pure helpers for the upload
// reservation and manifest-commit callables — input shapes, the base-pair decision table,
// the rolling reservation window, the retention plan and CRC32C. Kept free of Firestore so
// `node --test` covers the rules without an emulator.

// §6.4 item 9: the envelope header (126) plus the payload GCM tag (16).
const ENVELOPE_OVERHEAD_BYTES = 142;
const MAX_COMPRESSED_BYTES = 20 * 1024 * 1024;
const MAX_OBJECT_BYTES = 21 * 1024 * 1024;
// §8.6: five reservations per UID per rolling 24 hours, 105 MiB aggregate per window.
const RESERVATION_WINDOW_MS = 24 * 60 * 60 * 1000;
const RESERVATION_LIMIT = 5;
const AGGREGATE_LIMIT_BYTES = 105 * 1024 * 1024;
// §8.6: a reservation expires after 30 minutes of inactivity, is extended at most once per
// 10-minute heartbeat, and never lives past seven days from creation.
const RESERVATION_INACTIVITY_MS = 30 * 60 * 1000;
const HEARTBEAT_MIN_MS = 10 * 60 * 1000;
const RESERVATION_MAX_MS = 7 * 24 * 60 * 60 * 1000;
const EXTENSION_HORIZON_MS = RESERVATION_INACTIVITY_MS;
// §8.6: orphan objects wait at least 24 hours after their reservation expires.
const ORPHAN_GRACE_MS = 24 * 60 * 60 * 1000;
// §6.6 item 6: a restore pin shields one object from retention while it downloads. The
// bound keeps a crashed restore from pinning forever; a 21 MiB download fits an hour many
// times over, and endBackupRestore clears the pin on every normal path.
const RESTORE_GUARD_TTL_MS = 60 * 60 * 1000;
// §8.6: the current pointer plus two prior successful snapshots.
const RETAINED_SNAPSHOTS = 3;

// The backup ID becomes a Storage path segment and a Firestore document id. The client
// sends 16 random bytes as lowercase hex; the charset makes path traversal impossible.
const BACKUP_ID_PATTERN = /^[0-9a-f]{32}$/;
const SHA256_PATTERN = /^[0-9a-f]{64}$/;

const RESERVATION_TTL_MS = RESERVATION_INACTIVITY_MS;

function isBackupId(value) {
  return typeof value === 'string' && BACKUP_ID_PATTERN.test(value);
}

function isSha256(value) {
  return typeof value === 'string' && SHA256_PATTERN.test(value);
}

function isUint(value, min, max) {
  return Number.isInteger(value) && value >= min && value <= max;
}

function isPairMember(value, validate) {
  return value === null || validate(value);
}

function hasExactKeys(data, keys) {
  if (data === null || typeof data !== 'object' || Array.isArray(data)) return false;
  const actual = Object.keys(data).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((k, i) => k === expected[i]);
}

/** The pair is either fully absent or fully present — a half pair is a malformed request. */
function validOptionalPair(revision, sha256) {
  if (revision === null && sha256 === null) return true;
  if (revision === null || sha256 === null) return false;
  return isUint(revision, 1, Number.MAX_SAFE_INTEGER) && isSha256(sha256);
}

function validateBeginInput(data) {
  if (!hasExactKeys(data, [
    'backupId', 'schemaVersion', 'compressedBytes', 'objectBytes',
    'baseRevision', 'baseSha256',
    'replaceCurrent', 'replaceRevision', 'replaceSha256',
  ])) return { ok: false, reason: 'INVALID_INPUT' };
  if (!isBackupId(data.backupId)) return { ok: false, reason: 'INVALID_BACKUP_ID' };
  if (!isUint(data.schemaVersion, 1, 0xffffffff)) return { ok: false, reason: 'INVALID_SCHEMA_VERSION' };
  if (!isUint(data.compressedBytes, 1, MAX_COMPRESSED_BYTES)) return { ok: false, reason: 'BACKUP_TOO_LARGE' };
  if (!isUint(data.objectBytes, 1, MAX_OBJECT_BYTES)) return { ok: false, reason: 'BACKUP_TOO_LARGE' };
  if (data.objectBytes !== data.compressedBytes + ENVELOPE_OVERHEAD_BYTES) {
    return { ok: false, reason: 'INVALID_OBJECT_SIZE' };
  }
  if (!validOptionalPair(data.baseRevision, data.baseSha256)) return { ok: false, reason: 'INVALID_BASE' };
  if (typeof data.replaceCurrent !== 'boolean') return { ok: false, reason: 'INVALID_REPLACE' };
  if (!validOptionalPair(data.replaceRevision, data.replaceSha256)) return { ok: false, reason: 'INVALID_REPLACE' };
  if (!data.replaceCurrent && (data.replaceRevision !== null || data.replaceSha256 !== null)) {
    return { ok: false, reason: 'INVALID_REPLACE' };
  }
  return { ok: true };
}

function validateCommitInput(data) {
  if (!hasExactKeys(data, ['backupId', 'sha256', 'objectBytes'])) return { ok: false, reason: 'INVALID_INPUT' };
  if (!isBackupId(data.backupId)) return { ok: false, reason: 'INVALID_BACKUP_ID' };
  if (!isSha256(data.sha256)) return { ok: false, reason: 'INVALID_SHA256' };
  if (!isUint(data.objectBytes, 1, MAX_OBJECT_BYTES)) return { ok: false, reason: 'BACKUP_TOO_LARGE' };
  return { ok: true };
}

function validateExtendInput(data) {
  if (!hasExactKeys(data, ['backupId'])) return { ok: false, reason: 'INVALID_INPUT' };
  if (!isBackupId(data.backupId)) return { ok: false, reason: 'INVALID_BACKUP_ID' };
  return { ok: true };
}

function validateRestoreInput(data) {
  return validateExtendInput(data);
}

/** §6.6 item 6: only a live pin exempts an object from retention. */
function restoreGuardActive(record, nowMs) {
  if (!record || typeof record.objectPath !== 'string') return false;
  const expires = typeof record.expiresAtMs === 'number' ? record.expiresAtMs : 0;
  return expires > nowMs;
}

function normalizePair(revision, sha256) {
  return revision === null ? null : { revision, sha256 };
}

/**
 * §8.6: the base the client believes exists must be exactly the server's current pointer.
 * Every divergence has its own code so the client can tell "refresh" from "investigate".
 */
function classifyBase({ currentRevision, currentSha256, baseRevision, baseSha256,
  replaceCurrent, replaceRevision, replaceSha256 }) {
  const current = normalizePair(currentRevision, currentSha256);
  const base = normalizePair(baseRevision, baseSha256);
  const replacement = normalizePair(replaceRevision, replaceSha256);

  if (replaceCurrent) {
    // §6.4 item 11: replacement is bound to the exact current revision shown to the user.
    if (!samePair(current, replacement)) return { ok: false, reason: 'BACKUP_CONFLICT' };
    return { ok: true, mode: 'REPLACE', anchor: current };
  }
  if (!current) {
    // A local base with no server pointer is a server-side rollback, not a first backup.
    if (!base) return { ok: true, mode: 'INITIAL', anchor: null };
    return { ok: false, reason: 'REMOTE_BACKUP_ROLLBACK' };
  }
  if (!base) return { ok: false, reason: 'BACKUP_BASE_REQUIRED' };
  if (base.revision > current.revision) return { ok: false, reason: 'REMOTE_BACKUP_ROLLBACK' };
  if (base.revision < current.revision) return { ok: false, reason: 'REMOTE_BACKUP_AHEAD' };
  if (base.sha256 !== current.sha256) return { ok: false, reason: 'REMOTE_BACKUP_EQUIVOCATION' };
  return { ok: true, mode: 'MATCH', anchor: current };
}

function samePair(a, b) {
  if (a === null || b === null) return a === b;
  return a.revision === b.revision && a.sha256 === b.sha256;
}

/**
 * §8.6: five reservations per rolling 24 hours and 105 MiB aggregate reserved bytes.
 * Entries are server-written timestamps; future values are discarded against a clock
 * rollback, matching the guest-transition window guard.
 */
function advanceReservationWindow(ledger, nowMs, objectBytes) {
  const attempts = ledger && Array.isArray(ledger.attempts)
    ? ledger.attempts.filter((entry) => entry && typeof entry.atMs === 'number'
      && entry.atMs <= nowMs && nowMs - entry.atMs < RESERVATION_WINDOW_MS
      && typeof entry.bytes === 'number' && entry.bytes >= 0)
    : [];
  if (attempts.length >= RESERVATION_LIMIT) {
    return {
      allowed: false,
      retryAfterMs: attempts[0].atMs + RESERVATION_WINDOW_MS - nowMs,
    };
  }
  const used = attempts.reduce((sum, entry) => sum + entry.bytes, 0);
  if (used + objectBytes > AGGREGATE_LIMIT_BYTES) {
    const retryAfterMs = attempts.length > 0
      ? attempts[0].atMs + RESERVATION_WINDOW_MS - nowMs
      : RESERVATION_WINDOW_MS;
    return { allowed: false, retryAfterMs };
  }
  return { allowed: true, attempts: [...attempts, { atMs: nowMs, bytes: objectBytes }] };
}

function reservationExpired(reservation, nowMs) {
  const expires = reservation && reservation.expiresAt;
  const ms = expires && typeof expires.toMillis === 'function' ? expires.toMillis() : 0;
  return ms <= nowMs;
}

/** §8.6: an extension is 30 minutes from now and never moves the seven-day ceiling. */
function extendedExpiry(maxExpiresAtMs, nowMs) {
  return Math.min(nowMs + EXTENSION_HORIZON_MS, maxExpiresAtMs);
}

function manifestRevisionFor(currentRevision) {
  return currentRevision === null || currentRevision === undefined ? 1 : currentRevision + 1;
}

/**
 * §8.6: retain the current pointer plus two prior commit revisions. Anything older is
 * eligible for deletion; the caller still checks live references and the 24-hour grace.
 */
function retentionPlan(manifests, currentBackupId) {
  const sorted = [...manifests].sort((a, b) => b.revision - a.revision);
  const keep = new Set(sorted.slice(0, RETAINED_SNAPSHOTS).map((m) => m.backupId));
  // The pointer's snapshot is never deleted even if a malformed manifest list predates it.
  if (currentBackupId) keep.add(currentBackupId);
  return sorted.filter((m) => !keep.has(m.backupId)).map((m) => m.backupId);
}

// CRC32C (Castagnoli), the checksum GCS publishes as `crc32c`. Table-driven so a 21 MiB
// object streams through one chunk at a time; it is a transport-corruption check, never a
// substitute for the SHA-256 or the GCM tag (§8.5).
const CRC32C_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i += 1) {
    let value = i;
    for (let bit = 0; bit < 8; bit += 1) {
      value = value & 1 ? (0x82f63b78 ^ (value >>> 1)) : (value >>> 1);
    }
    table[i] = value >>> 0;
  }
  return table;
})();

class Crc32c {
  constructor() {
    this.value = 0xffffffff;
  }

  update(buffer) {
    let value = this.value;
    for (let i = 0; i < buffer.length; i += 1) {
      value = CRC32C_TABLE[(value ^ buffer[i]) & 0xff] ^ (value >>> 8);
    }
    this.value = value >>> 0;
    return this;
  }

  digest() {
    return (this.value ^ 0xffffffff) >>> 0;
  }

  digestBase64() {
    const bytes = Buffer.alloc(4);
    bytes.writeUInt32BE(this.digest(), 0);
    return bytes.toString('base64');
  }
}

function crc32cBase64(buffer) {
  return new Crc32c().update(buffer).digestBase64();
}

const PATHS = Object.freeze({
  object: (uid, backupId) => `backups/${uid}/${backupId}`,
  reservation: (uid, backupId) => `backupReservations/${uid}/reservations/${backupId}`,
  ledger: (uid) => `backupReservations/${uid}`,
  pointer: (uid) => `users/${uid}/backupControl/current`,
  activeRestore: (uid) => `users/${uid}/backupControl/activeRestore`,
  manifest: (uid, backupId) => `users/${uid}/backupManifests/${backupId}`,
  manifests: (uid) => `users/${uid}/backupManifests`,
});

module.exports = {
  ENVELOPE_OVERHEAD_BYTES,
  MAX_COMPRESSED_BYTES,
  MAX_OBJECT_BYTES,
  RESERVATION_WINDOW_MS,
  RESERVATION_LIMIT,
  AGGREGATE_LIMIT_BYTES,
  RESERVATION_INACTIVITY_MS,
  HEARTBEAT_MIN_MS,
  RESERVATION_MAX_MS,
  RESERVATION_TTL_MS,
  ORPHAN_GRACE_MS,
  RESTORE_GUARD_TTL_MS,
  RETAINED_SNAPSHOTS,
  isBackupId,
  isSha256,
  hasExactKeys,
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
  crc32cBase64,
  PATHS,
};

'use strict';
// Account plan §8.6 / §6.4 items 11-13: the reservation and manifest-commit wire contract,
// the base-pair decision table, the rolling window, retention and CRC32C. No emulator.

const { test } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');

const {
  MAX_COMPRESSED_BYTES,
  MAX_OBJECT_BYTES,
  RESERVATION_LIMIT,
  AGGREGATE_LIMIT_BYTES,
  ENVELOPE_OVERHEAD_BYTES,
  validateBeginInput,
  validateCommitInput,
  validateExtendInput,
  validateRestoreInput,
  restoreGuardActive,
  classifyBase,
  advanceReservationWindow,
  reservationExpired,
  extendedExpiry,
  manifestRevisionFor,
  retentionPlan,
  crc32cBase64,
  Crc32c,
  PATHS,
  RESERVATION_MAX_MS,
  RESERVATION_TTL_MS,
  RESTORE_GUARD_TTL_MS,
} = require('../backup-manifest-logic');

const sha = (fill = 'a') => fill.repeat(64);
const backupId = (fill = 'b') => fill.repeat(32);

function beginInput(overrides = {}) {
  return {
    backupId: backupId(),
    schemaVersion: 20,
    compressedBytes: 1000,
    objectBytes: 1000 + ENVELOPE_OVERHEAD_BYTES,
    baseRevision: null,
    baseSha256: null,
    replaceCurrent: false,
    replaceRevision: null,
    replaceSha256: null,
    ...overrides,
  };
}

test('a canonical begin request passes and every unknown key is rejected', () => {
  assert.deepStrictEqual(validateBeginInput(beginInput()), { ok: true });
  assert.strictEqual(validateBeginInput({ ...beginInput(), extra: 1 }).reason, 'INVALID_INPUT');
  assert.strictEqual(validateBeginInput(null).reason, 'INVALID_INPUT');
});

test('the backup id is 16 bytes of lowercase hex — path segments cannot escape', () => {
  for (const id of ['../etc/passwd', 'A'.repeat(32), 'b'.repeat(31), 'b'.repeat(33), '', 42]) {
    assert.strictEqual(validateBeginInput(beginInput({ backupId: id })).reason, 'INVALID_BACKUP_ID');
  }
  assert.strictEqual(validateBeginInput(beginInput({ backupId: 'c'.repeat(32) })).ok, true);
});

test('size caps are enforced on both sides of the envelope relation', () => {
  assert.strictEqual(
    validateBeginInput(beginInput({
      compressedBytes: MAX_COMPRESSED_BYTES + 1,
      objectBytes: MAX_COMPRESSED_BYTES + 1 + ENVELOPE_OVERHEAD_BYTES,
    })).reason,
    'BACKUP_TOO_LARGE',
  );
  assert.strictEqual(
    validateBeginInput(beginInput({
      compressedBytes: 1000,
      objectBytes: 1000 + ENVELOPE_OVERHEAD_BYTES + 1,
    })).reason,
    'INVALID_OBJECT_SIZE',
  );
  assert.strictEqual(
    validateBeginInput(beginInput({ compressedBytes: 0, objectBytes: ENVELOPE_OVERHEAD_BYTES })).reason,
    'BACKUP_TOO_LARGE',
  );
  assert.strictEqual(
    validateBeginInput(beginInput({
      compressedBytes: MAX_COMPRESSED_BYTES,
      objectBytes: MAX_COMPRESSED_BYTES + ENVELOPE_OVERHEAD_BYTES,
    })).ok,
    true,
  );
  assert.strictEqual(
    validateBeginInput(beginInput({
      compressedBytes: 1000,
      objectBytes: MAX_OBJECT_BYTES + 1,
    })).reason,
    'BACKUP_TOO_LARGE',
  );
});

test('base and replacement pairs are all-or-nothing', () => {
  assert.strictEqual(
    validateBeginInput(beginInput({ baseRevision: 2 })).reason,
    'INVALID_BASE',
  );
  assert.strictEqual(
    validateBeginInput(beginInput({ baseSha256: sha() })).reason,
    'INVALID_BASE',
  );
  assert.strictEqual(
    validateBeginInput(beginInput({ baseRevision: 0, baseSha256: sha() })).reason,
    'INVALID_BASE',
  );
  assert.strictEqual(
    validateBeginInput(beginInput({ replaceRevision: 2 })).reason,
    'INVALID_REPLACE',
  );
  assert.strictEqual(
    validateBeginInput(beginInput({ replaceCurrent: 'yes' })).reason,
    'INVALID_REPLACE',
  );
  assert.strictEqual(
    validateBeginInput(beginInput({
      replaceCurrent: true,
      replaceRevision: 2,
      replaceSha256: sha(),
    })).ok,
    true,
  );
});

test('commit and extend accept only the exact minimal shape', () => {
  assert.deepStrictEqual(
    validateCommitInput({ backupId: backupId(), sha256: sha(), objectBytes: 1142 }),
    { ok: true },
  );
  assert.strictEqual(
    validateCommitInput({ backupId: backupId(), sha256: sha().toUpperCase(), objectBytes: 1142 }).reason,
    'INVALID_SHA256',
  );
  assert.strictEqual(
    validateCommitInput({ backupId: backupId(), sha256: sha(), objectBytes: 1142, extra: 1 }).reason,
    'INVALID_INPUT',
  );
  assert.deepStrictEqual(validateExtendInput({ backupId: backupId() }), { ok: true });
  assert.strictEqual(validateExtendInput({}).reason, 'INVALID_INPUT');
});

test('the base decision table names every divergence', () => {
  const current = (revision, sha256) => ({ currentRevision: revision, currentSha256: sha256 });
  const base = (revision, sha256) => ({
    baseRevision: revision, baseSha256: sha256,
    replaceCurrent: false, replaceRevision: null, replaceSha256: null,
  });

  assert.deepStrictEqual(
    classifyBase({ ...current(null, null), ...base(null, null) }),
    { ok: true, mode: 'INITIAL', anchor: null },
  );
  assert.strictEqual(classifyBase({ ...current(null, null), ...base(1, sha()) }).reason, 'REMOTE_BACKUP_ROLLBACK');
  assert.strictEqual(classifyBase({ ...current(2, sha()), ...base(null, null) }).reason, 'BACKUP_BASE_REQUIRED');
  assert.strictEqual(classifyBase({ ...current(2, sha()), ...base(3, sha()) }).reason, 'REMOTE_BACKUP_ROLLBACK');
  assert.strictEqual(classifyBase({ ...current(2, sha()), ...base(1, sha()) }).reason, 'REMOTE_BACKUP_AHEAD');
  assert.strictEqual(
    classifyBase({ ...current(2, sha()), ...base(2, sha('c')) }).reason,
    'REMOTE_BACKUP_EQUIVOCATION',
  );
  assert.deepStrictEqual(
    classifyBase({ ...current(2, sha()), ...base(2, sha()) }),
    { ok: true, mode: 'MATCH', anchor: { revision: 2, sha256: sha() } },
  );
});

test('replacement is bound to the exact revision and hash shown to the user', () => {
  const replacement = (revision, sha256) => ({
    currentRevision: 3, currentSha256: sha(),
    baseRevision: 1, baseSha256: sha('b'),
    replaceCurrent: true, replaceRevision: revision, replaceSha256: sha256,
  });
  assert.strictEqual(classifyBase(replacement(2, sha())).reason, 'BACKUP_CONFLICT');
  assert.strictEqual(classifyBase(replacement(3, sha('c'))).reason, 'BACKUP_CONFLICT');
  assert.strictEqual(classifyBase(replacement(3, sha())).ok, true);
  // Replacement of an absent pointer is allowed only while it is still absent.
  assert.strictEqual(classifyBase({
    currentRevision: 1, currentSha256: sha(),
    baseRevision: null, baseSha256: null,
    replaceCurrent: true, replaceRevision: null, replaceSha256: null,
  }).reason, 'BACKUP_CONFLICT');
});

test('the rolling window caps attempts and aggregate bytes with a retry delay', () => {
  const now = 1_000_000_000;
  let ledger = null;
  for (let i = 0; i < RESERVATION_LIMIT; i += 1) {
    const window = advanceReservationWindow(ledger, now + i, 1);
    assert.strictEqual(window.allowed, true);
    ledger = { attempts: window.attempts };
  }
  const refused = advanceReservationWindow(ledger, now + RESERVATION_LIMIT, 1);
  assert.strictEqual(refused.allowed, false);
  assert.strictEqual(refused.retryAfterMs, 24 * 60 * 60 * 1000 - RESERVATION_LIMIT);

  const big = advanceReservationWindow(
    { attempts: [{ atMs: now, bytes: AGGREGATE_LIMIT_BYTES - 5 }] },
    now + 10,
    10,
  );
  assert.strictEqual(big.allowed, false);

  // Entries outside the window and future-dated entries do not count.
  const stale = advanceReservationWindow(
    { attempts: [{ atMs: now - 24 * 60 * 60 * 1000 - 1, bytes: 10 }] },
    now,
    10,
  );
  assert.strictEqual(stale.allowed, true);
  const future = advanceReservationWindow(
    { attempts: [{ atMs: now + 1, bytes: 10 }] },
    now,
    10,
  );
  assert.strictEqual(future.allowed, true);
});

test('expiry, extension and revision rules', () => {
  const now = 10_000_000;
  const at = (ms) => ({ toMillis: () => ms });
  assert.strictEqual(reservationExpired({ expiresAt: at(now) }, now), true);
  assert.strictEqual(reservationExpired({ expiresAt: at(now + 1) }, now), false);
  assert.strictEqual(reservationExpired({}, now), true);

  assert.strictEqual(extendedExpiry(now + 1000, now), now + 1000);
  assert.strictEqual(extendedExpiry(now + RESERVATION_MAX_MS, now), now + 30 * 60 * 1000);
  assert.strictEqual(RESERVATION_TTL_MS, 30 * 60 * 1000);
  assert.strictEqual(manifestRevisionFor(null), 1);
  assert.strictEqual(manifestRevisionFor(4), 5);
});

test('retention keeps the current pointer and the two most recent prior snapshots', () => {
  const manifests = [5, 4, 3, 2, 1].map((revision) => ({
    backupId: String(revision).padStart(32, 'a'),
    revision,
  }));
  const plan = retentionPlan(manifests, manifests[0].backupId);
  assert.deepStrictEqual(plan, [
    String(2).padStart(32, 'a'),
    String(1).padStart(32, 'a'),
  ]);
  assert.deepStrictEqual(retentionPlan(manifests.slice(0, 2), manifests[0].backupId), []);
});

test('CRC32C matches the standard known-answer vectors', () => {
  assert.strictEqual(new Crc32c().digest(), 0);
  assert.strictEqual(new Crc32c().update(Buffer.from('123456789')).digest(), 0xe3069283);
  const chunked = new Crc32c()
    .update(Buffer.from('1234'))
    .update(Buffer.from('56789'));
  assert.strictEqual(chunked.digest(), 0xe3069283);
  assert.strictEqual(
    crc32cBase64(Buffer.from('123456789')),
    Buffer.from([0xe3, 0x06, 0x92, 0x83]).toString('base64'),
  );
  const random = crypto.randomBytes(65536);
  const once = new Crc32c().update(random).digest();
  const parts = new Crc32c();
  for (let i = 0; i < random.length; i += 1000) parts.update(random.subarray(i, i + 1000));
  assert.strictEqual(parts.digest(), once);
});

test('object paths are the only place a backup id and uid meet', () => {
  assert.strictEqual(PATHS.object('uid-1', backupId()), `backups/uid-1/${backupId()}`);
  assert.strictEqual(
    PATHS.reservation('uid-1', backupId()),
    `backupReservations/uid-1/reservations/${backupId()}`,
  );
  assert.strictEqual(PATHS.manifest('uid-1', backupId()), `users/uid-1/backupManifests/${backupId()}`);
  assert.strictEqual(PATHS.activeRestore('uid-1'), 'users/uid-1/backupControl/activeRestore');
});

test('the restore pin accepts exactly one valid backup id', () => {
  assert.deepStrictEqual(validateRestoreInput({ backupId: backupId() }), { ok: true });
  assert.strictEqual(validateRestoreInput({ backupId: backupId(), extra: 1 }).reason, 'INVALID_INPUT');
  assert.strictEqual(validateRestoreInput(null).reason, 'INVALID_INPUT');
  assert.strictEqual(validateRestoreInput({ backupId: '../x' }).reason, 'INVALID_BACKUP_ID');
});

test('only a live restore pin exempts an object from retention', () => {
  const nowMs = 1_800_000_000_000;
  const live = {
    backupId: backupId(),
    objectPath: PATHS.object('uid-1', backupId()),
    expiresAtMs: nowMs + RESTORE_GUARD_TTL_MS,
  };
  assert.strictEqual(restoreGuardActive(live, nowMs), true);
  assert.strictEqual(restoreGuardActive({ ...live, expiresAtMs: nowMs }, nowMs), false);
  assert.strictEqual(restoreGuardActive({ ...live, expiresAtMs: nowMs - 1 }, nowMs), false);
  assert.strictEqual(restoreGuardActive({ ...live, expiresAtMs: undefined }, nowMs), false);
  assert.strictEqual(restoreGuardActive({ backupId: live.backupId }, nowMs), false);
  assert.strictEqual(restoreGuardActive(null, nowMs), false);
});

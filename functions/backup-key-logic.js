'use strict';
// Account plan §7 Release 2 step 12 / §8.5 / §4.2: pure helpers for the ABK key-bundle
// callables — the exact wire shape, base64/length validation, the pinned Argon2id parameter
// set and the compare-and-set rules. Kept free of Firestore so `node --test` covers the
// contract without an emulator. The server never sees the ABK, the passphrase or the
// recovery key: only wrapped blobs and metadata.

// §8.5 pins the only accepted Argon2id parameter set. A bundle carrying anything else is
// rejected before any future client allocates KDF memory (§9 invalid/unknown KDF parameters).
const ARGON2 = Object.freeze({ memoryKiB: 32768, iterations: 3, parallelism: 1 });
const BUNDLE_FORMAT_VERSION = 1;

const KEY_ID_BYTES = 16;
const SALT_BYTES = 16;
const NONCE_BYTES = 12;
const WRAP_BYTES = 48; // 32-byte ABK + 16-byte GCM tag

const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/** Exact field allowlist — an unknown field is a malformed request, not something to ignore. */
function hasExactKeys(data, keys) {
  if (!isPlainObject(data)) return false;
  const actual = Object.keys(data).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((k, i) => k === expected[i]);
}

/** Strict canonical base64 of an exact decoded length; accepts no whitespace or variants. */
function isBase64OfLength(value, bytes) {
  if (typeof value !== 'string' || value.length === 0 || value.length % 4 !== 0) return false;
  if (!BASE64.test(value)) return false;
  const decoded = Buffer.from(value, 'base64');
  return decoded.length === bytes && decoded.toString('base64') === value;
}

function isUint(value, max) {
  return Number.isInteger(value) && value >= 0 && value <= max;
}

function isWrapper(value) {
  return hasExactKeys(value, ['nonce', 'ciphertext']) &&
    isBase64OfLength(value.nonce, NONCE_BYTES) &&
    isBase64OfLength(value.ciphertext, WRAP_BYTES);
}

function isArgon2(value) {
  return hasExactKeys(value, ['memoryKiB', 'iterations', 'parallelism']) &&
    value.memoryKiB === ARGON2.memoryKiB &&
    value.iterations === ARGON2.iterations &&
    value.parallelism === ARGON2.parallelism;
}

const BUNDLE_KEYS = [
  'bundleFormatVersion',
  'keyId',
  'argon2',
  'passphraseSalt',
  'passphraseWrapper',
  'recoveryKeyId',
  'recoveryHkdfSalt',
  'recoveryWrapper',
];

/** The bundle body the client may submit. `revision` is server-assigned, never client-supplied. */
function validateBundleBody(bundle) {
  if (!hasExactKeys(bundle, BUNDLE_KEYS)) return { ok: false, reason: 'INVALID_BUNDLE' };
  if (bundle.bundleFormatVersion !== BUNDLE_FORMAT_VERSION) {
    return { ok: false, reason: 'UNSUPPORTED_BUNDLE_VERSION' };
  }
  if (!isBase64OfLength(bundle.keyId, KEY_ID_BYTES)) return { ok: false, reason: 'INVALID_KEY_ID' };
  if (!isArgon2(bundle.argon2)) return { ok: false, reason: 'UNSUPPORTED_KDF_PARAMETERS' };
  if (!isBase64OfLength(bundle.passphraseSalt, SALT_BYTES)) {
    return { ok: false, reason: 'INVALID_PASSPHRASE_SALT' };
  }
  if (!isWrapper(bundle.passphraseWrapper)) return { ok: false, reason: 'INVALID_PASSPHRASE_WRAPPER' };
  if (!isBase64OfLength(bundle.recoveryKeyId, KEY_ID_BYTES)) {
    return { ok: false, reason: 'INVALID_RECOVERY_KEY_ID' };
  }
  if (!isBase64OfLength(bundle.recoveryHkdfSalt, SALT_BYTES)) {
    return { ok: false, reason: 'INVALID_RECOVERY_SALT' };
  }
  if (!isWrapper(bundle.recoveryWrapper)) return { ok: false, reason: 'INVALID_RECOVERY_WRAPPER' };
  return { ok: true };
}

function validateInitializeInput(data) {
  if (!hasExactKeys(data, ['bundle'])) return { ok: false, reason: 'INVALID_INPUT' };
  return validateBundleBody(data.bundle);
}

function validateUpdateInput(data) {
  if (!hasExactKeys(data, ['expectedRevision', 'bundle'])) {
    return { ok: false, reason: 'INVALID_INPUT' };
  }
  if (!isUint(data.expectedRevision, 1_000_000) || data.expectedRevision < 1) {
    return { ok: false, reason: 'INVALID_REVISION' };
  }
  return validateBundleBody(data.bundle);
}

/** The immutable identity of the wrapped ABK: a "replayed" initialize must match it byte-for-byte. */
function sameBundleCore(a, b) {
  if (!isPlainObject(a) || !isPlainObject(b)) return false;
  return a.bundleFormatVersion === b.bundleFormatVersion &&
    a.keyId === b.keyId &&
    isArgon2(a.argon2) && isArgon2(b.argon2) &&
    a.argon2.memoryKiB === b.argon2.memoryKiB &&
    a.argon2.iterations === b.argon2.iterations &&
    a.argon2.parallelism === b.argon2.parallelism &&
    a.passphraseSalt === b.passphraseSalt &&
    a.passphraseWrapper?.nonce === b.passphraseWrapper?.nonce &&
    a.passphraseWrapper?.ciphertext === b.passphraseWrapper?.ciphertext &&
    a.recoveryKeyId === b.recoveryKeyId &&
    a.recoveryHkdfSalt === b.recoveryHkdfSalt &&
    a.recoveryWrapper?.nonce === b.recoveryWrapper?.nonce &&
    a.recoveryWrapper?.ciphertext === b.recoveryWrapper?.ciphertext;
}

/**
 * §6.5/§8.5: a rotation may only change the wrapper it targets. The ABK identity and the
 * envelope/parameter contract stay fixed; the passphrase wrapper survives a recovery-key
 * rotation and the recovery wrapper survives a passphrase change, so the client can always
 * unlock with the other secret until its own rotation commits.
 */
function rotationChangedOnlyAllowedFields(current, next) {
  if (current.bundleFormatVersion !== next.bundleFormatVersion) return false;
  if (current.keyId !== next.keyId) return false;
  if (!isArgon2(next.argon2)) return false;
  return true;
}

/** The server response shape: the stored bundle without server timestamps. */
function publicBundle(record, revision) {
  return {
    bundleFormatVersion: record.bundleFormatVersion,
    keyId: record.keyId,
    argon2: record.argon2,
    passphraseSalt: record.passphraseSalt,
    passphraseWrapper: record.passphraseWrapper,
    recoveryKeyId: record.recoveryKeyId,
    recoveryHkdfSalt: record.recoveryHkdfSalt,
    recoveryWrapper: record.recoveryWrapper,
    revision,
  };
}

module.exports = {
  ARGON2,
  BUNDLE_FORMAT_VERSION,
  KEY_ID_BYTES,
  SALT_BYTES,
  NONCE_BYTES,
  WRAP_BYTES,
  isBase64OfLength,
  isPlainObject,
  hasExactKeys,
  validateBundleBody,
  validateInitializeInput,
  validateUpdateInput,
  sameBundleCore,
  rotationChangedOnlyAllowedFields,
  publicBundle,
};

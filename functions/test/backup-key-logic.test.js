'use strict';
// Account plan §8.5 / §4.2: the wrapped-ABK bundle wire contract. No emulator.

const { test } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');

const {
  ARGON2,
  BUNDLE_FORMAT_VERSION,
  isBase64OfLength,
  validateBundleBody,
  validateInitializeInput,
  validateUpdateInput,
  sameBundleCore,
  rotationChangedOnlyAllowedFields,
  publicBundle,
} = require('../backup-key-logic');

const b64 = (bytes) => crypto.randomBytes(bytes).toString('base64');

function bundle(overrides = {}) {
  return {
    bundleFormatVersion: BUNDLE_FORMAT_VERSION,
    keyId: b64(16),
    argon2: { ...ARGON2 },
    passphraseSalt: b64(16),
    passphraseWrapper: { nonce: b64(12), ciphertext: b64(48) },
    recoveryKeyId: b64(16),
    recoveryHkdfSalt: b64(16),
    recoveryWrapper: { nonce: b64(12), ciphertext: b64(48) },
    ...overrides,
  };
}

test('a canonical bundle body passes', () => {
  assert.deepStrictEqual(validateBundleBody(bundle()), { ok: true });
});

test('base64 is canonical and length-checked — no whitespace, no variants, no truncation', () => {
  assert.strictEqual(isBase64OfLength(b64(16), 16), true);
  assert.strictEqual(isBase64OfLength(b64(15), 16), false);
  assert.strictEqual(isBase64OfLength(b64(16) + '\n', 16), false);
  assert.strictEqual(isBase64OfLength(b64(16).replace('=', ''), 16), false);
  assert.strictEqual(isBase64OfLength('!!!!', 3), false);
  assert.strictEqual(isBase64OfLength('', 0), false);
  assert.strictEqual(isBase64OfLength(42, 16), false);
});

test('the pinned Argon2id parameters are the only accepted set', () => {
  for (const argon2 of [
    { ...ARGON2, memoryKiB: 65536 },
    { ...ARGON2, iterations: 1 },
    { ...ARGON2, parallelism: 2 },
    { memoryKiB: ARGON2.memoryKiB, iterations: ARGON2.iterations },
  ]) {
    assert.strictEqual(validateBundleBody(bundle({ argon2 })).reason, 'UNSUPPORTED_KDF_PARAMETERS');
  }
});

test('unknown fields, wrong version and malformed wrappers are rejected', () => {
  const withExtra = { ...bundle(), extra: true };
  assert.strictEqual(validateBundleBody(withExtra).reason, 'INVALID_BUNDLE');
  assert.strictEqual(
    validateBundleBody(bundle({ bundleFormatVersion: 2 })).reason,
    'UNSUPPORTED_BUNDLE_VERSION',
  );
  assert.strictEqual(
    validateBundleBody(bundle({ keyId: b64(15) })).reason,
    'INVALID_KEY_ID',
  );
  assert.strictEqual(
    validateBundleBody(bundle({ passphraseWrapper: { nonce: b64(12), ciphertext: b64(47) } })).reason,
    'INVALID_PASSPHRASE_WRAPPER',
  );
  assert.strictEqual(
    validateBundleBody(bundle({ recoveryWrapper: { nonce: b64(12), ciphertext: b64(48), extra: 1 } })).reason,
    'INVALID_RECOVERY_WRAPPER',
  );
  assert.strictEqual(
    validateBundleBody(bundle({ recoveryHkdfSalt: b64(32) })).reason,
    'INVALID_RECOVERY_SALT',
  );
});

test('initialize takes exactly { bundle }', () => {
  assert.deepStrictEqual(validateInitializeInput({ bundle: bundle() }), { ok: true });
  assert.strictEqual(validateInitializeInput(bundle()).reason, 'INVALID_INPUT');
  assert.strictEqual(
    validateInitializeInput({ bundle: bundle(), revision: 1 }).reason,
    'INVALID_INPUT',
  );
});

test('update takes exactly { expectedRevision, bundle } and the revision is a positive integer', () => {
  assert.deepStrictEqual(
    validateUpdateInput({ expectedRevision: 3, bundle: bundle() }),
    { ok: true },
  );
  for (const expectedRevision of [0, -1, 1.5, '1', null]) {
    assert.strictEqual(
      validateUpdateInput({ expectedRevision, bundle: bundle() }).reason,
      'INVALID_REVISION',
    );
  }
  assert.strictEqual(
    validateUpdateInput({ bundle: bundle() }).reason,
    'INVALID_INPUT',
  );
});

test('replay identity compares every stored wrapper field', () => {
  const a = bundle();
  assert.strictEqual(sameBundleCore(a, { ...a }), true);
  assert.strictEqual(sameBundleCore(a, { ...a, keyId: b64(16) }), false);
  assert.strictEqual(
    sameBundleCore(a, { ...a, passphraseWrapper: { ...a.passphraseWrapper, nonce: b64(12) } }),
    false,
  );
  assert.strictEqual(
    sameBundleCore(a, { ...a, recoveryWrapper: { ...a.recoveryWrapper, ciphertext: b64(48) } }),
    false,
  );
  assert.strictEqual(sameBundleCore(a, { ...a, argon2: { ...ARGON2, iterations: 4 } }), false);
  assert.strictEqual(sameBundleCore(null, a), false);
});

test('a rotation may rewrap either secret but never change the ABK identity or contract', () => {
  const current = bundle();
  const rewrapped = {
    ...current,
    passphraseSalt: b64(16),
    passphraseWrapper: { nonce: b64(12), ciphertext: b64(48) },
  };
  assert.strictEqual(rotationChangedOnlyAllowedFields(current, rewrapped), true);

  const rotatedRecovery = {
    ...current,
    recoveryKeyId: b64(16),
    recoveryHkdfSalt: b64(16),
    recoveryWrapper: { nonce: b64(12), ciphertext: b64(48) },
  };
  assert.strictEqual(rotationChangedOnlyAllowedFields(current, rotatedRecovery), true);

  assert.strictEqual(
    rotationChangedOnlyAllowedFields(current, { ...current, keyId: b64(16) }),
    false,
  );
  assert.strictEqual(
    rotationChangedOnlyAllowedFields(current, { ...current, bundleFormatVersion: 2 }),
    false,
  );
  assert.strictEqual(
    rotationChangedOnlyAllowedFields(current, { ...current, argon2: { ...ARGON2, memoryKiB: 1 } }),
    false,
  );
});

test('the public bundle carries no server timestamps and reports the assigned revision', () => {
  const record = {
    ...bundle(),
    revision: 7,
    createdAt: 'server',
    updatedAt: 'server',
  };
  const out = publicBundle(record, 7);
  assert.deepStrictEqual(Object.keys(out).sort(), [
    'argon2',
    'bundleFormatVersion',
    'keyId',
    'passphraseSalt',
    'passphraseWrapper',
    'recoveryHkdfSalt',
    'recoveryKeyId',
    'recoveryWrapper',
    'revision',
  ]);
  assert.strictEqual(out.revision, 7);
});

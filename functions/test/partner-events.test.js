'use strict';
// Partner event delivery v1 protocol contract (plan §7 Phase 1, §8.2–§8.4). Pure logic —
// no emulator. The Kotlin half reads the same fixture:
// app/src/test/java/com/example/myexodus/accountability/delivery/.

const { test } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const P = require('../partner-events');

const fixture = JSON.parse(
  fs.readFileSync(path.join(__dirname, 'fixtures', 'partner-event-jcs-v1.json'), 'utf8'),
);
const clone = (value) => JSON.parse(JSON.stringify(value));
const eventNamed = (name) => fixture.events.find((vector) => vector.name === name).event;

test('golden events canonicalize and digest identically to the shared fixture', () => {
  for (const vector of fixture.events) {
    assert.strictEqual(P.canonicalJson(P.eventToWireValue(vector.event)), vector.canonical, vector.name);
    assert.strictEqual(P.digest(vector.event), vector.sha256, vector.name);
    assert.strictEqual(P.validateEvent(vector.event), null, vector.name);
  }
});

test('JCS golden vectors match the shared fixture', () => {
  for (const vector of fixture.jcs) {
    assert.strictEqual(P.canonicalJson(vector.value), vector.canonical, vector.name);
  }
});

test('JCS orders object keys by UTF-16 code units, not locale', () => {
  assert.strictEqual(P.canonicalJson({ aa: 1, a: 2, B: 3 }), '{"B":3,"a":2,"aa":1}');
});

test('JCS escapes exactly the RFC 8785 set and leaves Unicode literal', () => {
  assert.strictEqual(P.canonicalJson('\u0001\u000C\u007F'), '"\\u0001\\f\u007F"');
  assert.strictEqual(P.canonicalJson('é😀'), '"é😀"');
  assert.strictEqual(P.canonicalJson('"\\\b\n\r\t'), '"\\"\\\\\\b\\n\\r\\t"');
});

test('JCS rejects values the protocol never carries', () => {
  assert.throws(() => P.canonicalJson(1.5), /unsafe JCS integer/);
  assert.throws(() => P.canonicalJson(Number.MAX_SAFE_INTEGER + 1), /unsafe JCS integer/);
  assert.throws(() => P.canonicalJson(NaN), /unsafe JCS integer/);
  assert.throws(() => P.canonicalJson('\ud800'), /unpaired high surrogate/);
  assert.throws(() => P.canonicalJson('\udc00'), /unpaired low surrogate/);
  assert.throws(() => P.canonicalJson(undefined), /unsupported JCS value/);
});

test('each shared invalid vector is rejected with its documented issue', () => {
  for (const vector of fixture.invalidEvents) {
    assert.strictEqual(P.validateEvent(vector.event), vector.issue, vector.name);
  }
});

test('ownerUid is inside the signed bytes, so rebinding changes the digest', () => {
  const event = eventNamed('block-attempt-app-two-recipients');
  const rebound = { ...clone(event), ownerUid: 'anotherUid_456' };
  assert.strictEqual(P.validateEvent(rebound), null);
  assert.notStrictEqual(P.digest(event), P.digest(rebound));
});

test('submission validation rejects invalid key IDs, signatures and extra fields', () => {
  const event = eventNamed('check-in-empty-metadata');
  const goodKey = fixture.signature.keyId;
  const goodSig = fixture.signature.signatureBase64Url;
  assert.strictEqual(P.validateSubmission({ keyId: goodKey, event, signature: goodSig }), null);

  assert.strictEqual(
    P.validateSubmission({ keyId: goodKey.slice(0, 42), event, signature: goodSig }),
    P.ISSUE.SUBMISSION_INVALID,
  );
  assert.strictEqual(
    P.validateSubmission({ keyId: goodKey, event, signature: `${goodSig}=` }),
    P.ISSUE.SUBMISSION_INVALID,
  );
  assert.strictEqual(
    P.validateSubmission({ keyId: goodKey, event, signature: goodSig, path: 'other' }),
    P.ISSUE.SUBMISSION_INVALID,
  );
  assert.strictEqual(
    P.validateSubmission([{ keyId: goodKey, event, signature: goodSig }]),
    P.ISSUE.SUBMISSION_INVALID,
  );
});

test('event size cap is 16 KiB and batch caps are 20 events / 256 KiB', () => {
  assert.strictEqual(P.MAX_EVENT_BYTES, 16 * 1024);
  assert.strictEqual(P.MAX_BATCH_EVENTS, 20);
  assert.strictEqual(P.MAX_BATCH_BYTES, 256 * 1024);

  const oversized = clone(eventNamed('check-in-empty-metadata'));
  oversized.recipientEnvelopes[0].payload = 'A'.repeat(P.MAX_EVENT_BYTES);
  assert.strictEqual(P.validateEvent(oversized), P.ISSUE.TOO_LARGE);

  const chunk = clone(eventNamed('check-in-empty-metadata'));
  chunk.recipientEnvelopes[0].payload = 'A'.repeat(15_000);
  assert.strictEqual(P.validateEvent(chunk), null);
  const wires = Array.from({ length: P.MAX_BATCH_EVENTS }, () => ({
    keyId: fixture.signature.keyId, event: clone(chunk), signature: 'A',
  }));
  assert.strictEqual(P.validateBatch(wires), P.ISSUE.TOO_LARGE);
  assert.strictEqual(P.validateBatch([]), P.ISSUE.TOO_LARGE);
});

test('the cross-language P-256 signature verifies over JCS bytes and tampering fails', () => {
  const vector = fixture.signature;
  const event = eventNamed(vector.eventName);
  assert.ok(P.verifyEventSignature(event, vector.signatureBase64Url, vector.publicKeySpkiBase64Url));

  const tampered = { ...clone(event), sequence: event.sequence + 1 };
  assert.strictEqual(
    P.verifyEventSignature(tampered, vector.signatureBase64Url, vector.publicKeySpkiBase64Url),
    false,
  );

  const otherKey = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
    .publicKey.export({ type: 'spki', format: 'der' }).toString('base64url');
  assert.strictEqual(P.verifyEventSignature(event, vector.signatureBase64Url, otherKey), false);
  assert.strictEqual(P.verifyEventSignature(event, 'not-a-signature', vector.publicKeySpkiBase64Url), false);
});

test('result classification encodes the no-effect / immutable-after-acceptance contract', () => {
  assert.strictEqual(P.isAcknowledged(P.RESULT.ACCEPTED), true);
  assert.strictEqual(P.isAcknowledged(P.RESULT.ALREADY_ACCEPTED), true);
  for (const code of [P.RESULT.RECIPIENT_KEY_STALE, P.RESULT.RETRYABLE,
    P.RESULT.REENROLL_REQUIRED, P.RESULT.PERMANENT_REJECTED]) {
    assert.strictEqual(P.isAcknowledged(code), false, code);
  }

  // Ciphertext regeneration is legal only on the definitive pre-effect rejection.
  assert.strictEqual(P.allowsCiphertextRegeneration(P.RESULT.RECIPIENT_KEY_STALE), true);
  for (const code of [P.RESULT.RETRYABLE, P.RESULT.ACCEPTED, P.RESULT.ALREADY_ACCEPTED,
    P.RESULT.REENROLL_REQUIRED, P.RESULT.PERMANENT_REJECTED, 'UNKNOWN']) {
    assert.strictEqual(P.allowsCiphertextRegeneration(code), false, code);
  }
});

test('kind, result and issue vocabularies are the documented closed sets', () => {
  assert.deepStrictEqual(Object.values(P.KIND).sort(),
    ['BLOCK_ATTEMPT', 'CHECK_IN', 'RULE_CHANGE_ATTEMPT']);
  assert.deepStrictEqual([...P.RESULT_CODES].sort(), ['ACCEPTED', 'ALREADY_ACCEPTED',
    'PERMANENT_REJECTED', 'RECIPIENT_KEY_STALE', 'REENROLL_REQUIRED', 'RETRYABLE']);
  assert.strictEqual(P.ISSUE_CODES.length, 11);
  // Server-generated kinds must never be accepted on this wire.
  for (const forbidden of ['RULE_CHANGE_APPLIED', 'PARTNER_ADDED', 'PARTNER_REMOVED',
    'PARTNER_APPROVAL']) {
    assert.ok(!Object.values(P.KIND).includes(forbidden));
  }
});

test('every server-owned collection name is denied in firestore.rules', () => {
  const rules = fs.readFileSync(path.join(__dirname, '..', '..', 'firestore.rules'), 'utf8');
  for (const collection of Object.values(P.COLLECTIONS)) {
    assert.ok(rules.includes(`/${collection}/`), `${collection} missing from rules`);
  }
  assert.ok(
    /match \/partnerMailboxes\/\{recipientUid\}\/messages\/\{messageId\} \{\s*allow read: if request\.auth != null/.test(rules),
    'partnerMailboxes must allow recipient reads',
  );
});

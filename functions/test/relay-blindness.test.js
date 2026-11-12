'use strict';
// Step 2 security gate: prove the relay path is content-blind. The mailbox payload is
// client-encrypted under a per-pair CEK that never leaves the devices (CekDerivation is
// ECDH + HKDF on Android Keystore keys), so "the relay cannot decrypt" is enforced by the
// absence of key material on the server. These checks fail if that absence ever regresses:
// no decryption/derivation in the delivery modules, no key field in the rules allowlist,
// and no payload content in any observable relay effect.

const { test } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const { createRelayHandler } = require('../relay-logic');
const { INPUT_KEYS } = require('../event-append-logic');

const PLAINTEXT = 'blocked app com.secret.bank notification="code 1234" https://secret.example';
const DELIVERY_MODULES = [
  'relay.js',
  'relay-logic.js',
  'dispatch-record.js',
  'dispatch-logic.js',
  'dispatch.js',
  'sweep.js',
  'cleanup.js',
  'mailbox-retention.js',
  'fcm-transport.js',
];

/** AES-256-GCM ciphertext under a key the relay never receives — stands in for the client
 *  envelope (the relay treats the bytes as opaque base64; the layout is not its concern). */
function ciphertext(plaintext) {
  const key = crypto.randomBytes(32);
  const nonce = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, nonce);
  const body = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
  return Buffer.concat([nonce, body, cipher.getAuthTag()]);
}

function harness() {
  const effects = { marks: [], dispatched: [] };
  const deps = {
    pairingActive: async () => true,
    checkRateLimit: async () => true,
    markMailbox: async (recipientUid, messageId, pairId, relayStatus) => {
      effects.marks.push({ recipientUid, messageId, pairId, relayStatus });
    },
    listInstallations: async () => [{ id: 'install-1' }],
    dispatch: async (recipientUid, messageId, installation, wakeData) => {
      effects.dispatched.push({ recipientUid, messageId, installation, wakeData });
    },
    logger: { info() {}, warn() {}, error() {} },
  };
  return { handler: createRelayHandler(deps), effects };
}

const event = (payload) => ({
  params: { recipientUid: 'bob', messageId: 'message-1' },
  data: { data: () => ({ senderUid: 'alice', pairId: 'pair-1', payload }) },
});

test('no observable relay effect carries payload content or ciphertext', async () => {
  const envelope = ciphertext(PLAINTEXT);
  const payload = envelope.toString('base64');
  const { handler, effects } = harness();

  await handler(event(payload));

  assert.strictEqual(effects.marks.length, 0, 'accepted message marks nothing');
  assert.strictEqual(effects.dispatched.length, 1);
  assert.deepStrictEqual(
    effects.dispatched[0].wakeData,
    { v: '1', type: 'SYNC_AVAILABLE', messageId: 'message-1' },
    'push wake is the generic signal only',
  );
  const observable = JSON.stringify(effects);
  assert.ok(!observable.includes(PLAINTEXT), 'plaintext never reaches relay state');
  assert.ok(!observable.includes(payload), 'ciphertext is not copied into relay state');
});

test('rejection markers carry only stable status codes, never content', async () => {
  const { handler, effects } = harness();
  effects.marks.length = 0;

  await handler(event('not-base64!.'));
  assert.deepStrictEqual(effects.marks.map((m) => m.relayStatus), ['INVALID_FORMAT']);

  const oversized = Buffer.alloc(4097).toString('base64');
  effects.marks.length = 0;
  await handler(event(oversized));
  assert.deepStrictEqual(effects.marks.map((m) => m.relayStatus), ['OVERSIZED']);

  assert.ok(!JSON.stringify(effects).includes('not-base64'));
});

test('relay decisions depend on length and format, not on ciphertext bytes', async () => {
  const a = ciphertext('alpha').toString('base64');
  const b = ciphertext('completely different content').toString('base64');
  const run = async (payload) => {
    const { handler, effects } = harness();
    await handler(event(payload));
    return effects;
  };
  assert.deepStrictEqual(await run(a), await run(b));
});

test('delivery modules contain no decryption or key-derivation code', () => {
  const forbidden = /createDecipher|createDecipheriv|privateDecrypt|subtle\.decrypt|deriveKey|deriveBits|hkdf|ecdh|createECDH/i;
  for (const name of DELIVERY_MODULES) {
    const source = fs.readFileSync(path.join(__dirname, '..', name), 'utf8');
    assert.ok(!forbidden.test(source), `${name} must not decrypt or derive keys`);
  }
});

test('the event write callable accepts no key-material field', () => {
  assert.deepStrictEqual([...INPUT_KEYS].sort(), [
    'ciphertextB64', 'eventId', 'installationId', 'keyEpoch', 'pairId', 'senderId',
  ], 'keyEpoch is a counter; installationId is an opaque handle; no secret material');
});

test('direct client mailbox writes are denied in the rules', () => {
  const rules = fs.readFileSync(path.join(__dirname, '..', '..', 'firestore.rules'), 'utf8');
  const mailboxBlock = rules.slice(rules.indexOf('match /mailboxes/'));
  assert.match(
    mailboxBlock,
    /allow create, update, delete: if false;/,
    'appendUserEvent must be the only event write path',
  );
});

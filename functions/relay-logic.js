'use strict';
// Pure decision logic and orchestration for the mailbox relay (plan §8 Step 5). No admin
// SDK, no Firestore — every effect is an injected dependency, so both the decisions and the
// handler's ordering (rate limit BEFORE any installation lookup/dispatch, plan §12/§17) are
// directly testable (`npm test`). The SDK wiring lives in relay.js.

// Fixed-window math now lives in ./rate-window (shared with the pairing-path limits);
// re-exported below so this module's test suite keeps its import surface.
const { windowState } = require('./rate-window');

// AD-19: the payload is base64 envelope bytes; the byte limit is on the DECODED length
// (rules cannot express byte size — their size() counts map fields).
const MAX_PAYLOAD_BYTES = 4096;

// AD-23 fixed windows.
const PAIR_WINDOW_MS = 60 * 1000;
const PAIR_WINDOW_MAX = 10; // events per pair per minute
const SENDER_WINDOW_MS = 24 * 60 * 60 * 1000;
const SENDER_WINDOW_MAX = 200; // mailbox messages per sender per day

// AD-18c: installations expire after 90 days without a token refresh; at most 5 active.
const INSTALLATION_STALE_MS = 90 * 24 * 60 * 60 * 1000;
const MAX_INSTALLATIONS = 5;

// AD-10c: the sweep retries PENDING/FAILED_RETRYABLE records while attempts < 8; the 8th
// failed attempt is terminal.
const MAX_ATTEMPTS = 8;

/**
 * AD-02 re-check, race-tolerant (defect #18(a), 2026-10-03). Membership is the security
 * boundary; the status is a lifecycle fact, not a relay gate. The mailbox create rule
 * requires the pairing to be `active` at write time and pins members, so a document can
 * only sit under a `terminated` pairing when the terminate landed between the sender's
 * write and this trigger's evaluation — the unpair deletion-request race (seconds of
 * trigger latency), which is exactly the message that must still be delivered. No other
 * document can reach here for a terminated pair: writes are rules-denied the moment the
 * flip lands. Rejecting `terminated` silently dropped the request that tells the partner
 * to shred (device-caught 2026-10-01: request rejected 3.4 s after the terminate flip,
 * partner never erased).
 */
function pairingUsableForRelay(data, senderUid, recipientUid) {
  if (!data || typeof senderUid !== 'string' || typeof recipientUid !== 'string') return false;
  if (data.status !== 'active' && data.status !== 'terminated') return false;
  return Array.isArray(data.members) &&
    data.members.includes(senderUid) &&
    data.members.includes(recipientUid);
}

/** Strict standard base64 (padded); Buffer.from is too lenient to be a validator. */
function isValidBase64(s) {
  return typeof s === 'string' && s.length > 0 && s.length % 4 === 0 && /^[A-Za-z0-9+/]+={0,2}$/.test(s);
}

/** Returns null when the payload passes AD-19, else the rejection reason for the relayStatus marker. */
function validatePayload(payload) {
  if (!isValidBase64(payload)) return 'INVALID_FORMAT';
  if (Buffer.from(payload, 'base64').length > MAX_PAYLOAD_BYTES) return 'OVERSIZED';
  return null;
}

/**
 * AD-23 gate for one message: advances the pair + sender windows and returns
 * `{ allowed, pair, senders }` — the state to persist is returned only when allowed, so a
 * rejected message is never counted and never extends the window it was rejected by. The
 * caller writes the result inside the same Firestore transaction that read `data`, which is
 * what makes the counter race-free.
 */
function incrementWindows(data, senderUid, now) {
  const pair = windowState(data.pair, now, PAIR_WINDOW_MS);
  const senders = data.senders && typeof data.senders === 'object' ? { ...data.senders } : {};
  const sender = windowState(senders[senderUid], now, SENDER_WINDOW_MS);

  if (pair.count >= PAIR_WINDOW_MAX || sender.count >= SENDER_WINDOW_MAX) {
    return { allowed: false };
  }

  pair.count += 1;
  sender.count += 1;
  senders[senderUid] = sender;
  return { allowed: true, pair, senders };
}

/**
 * The relay trigger's body (plan §8 Step 5 / AD-10), with every effect injected so the
 * rejection ordering is provable by call counts (plan §12: "rate limit rejection occurs
 * before any installation lookup or dispatch attempt"). Order: payload validation (AD-19) →
 * pairing re-check (AD-02) → AD-23 rate limit → installation lookup + dispatch.
 *
 * `deps`: { pairingActive(pairId, senderUid, recipientUid) → Promise<boolean>,
 *           checkRateLimit(pairId, senderUid, now) → Promise<boolean>,
 *           markMailbox(recipientUid, messageId, pairId, relayStatus) → Promise<void>,
 *           listInstallations(recipientUid) → Promise<Array>,
 *           dispatch(recipientUid, messageId, installation, wakeData) → Promise<void>,
 *           logger }
 *
 * `pairId` rides markMailbox so the rejection log line carries it — §14's "rate-limit
 * rejection count per pair" is a log-based metric on that field (Step 19).
 */
function createRelayHandler(deps) {
  return async (event) => {
    const { recipientUid, messageId } = event.params;
    const snapshot = event.data;
    if (!snapshot) return; // deleted before the trigger ran
    const { senderUid, pairId, payload } = snapshot.data();

    const invalid = validatePayload(payload);
    if (invalid) return deps.markMailbox(recipientUid, messageId, pairId, invalid);

    if (!(await deps.pairingActive(pairId, senderUid, recipientUid))) {
      return deps.markMailbox(recipientUid, messageId, pairId, 'PAIRING_INVALID');
    }

    if (!(await deps.checkRateLimit(pairId, senderUid, Date.now()))) {
      return deps.markMailbox(recipientUid, messageId, pairId, 'RATE_LIMITED');
    }

    const installations = await deps.listInstallations(recipientUid);
    if (installations.length === 0) {
      // AD-05: no push installation is an expected state (no-provider device) — the event
      // still arrives via foreground/periodic sync; nothing to dispatch, nothing to retry.
      deps.logger.info('no push installations', { recipientUid, messageId });
      return;
    }

    const wakeData = { messageId, type: 'domain-event' }; // AD-11: generic wake signal only
    await Promise.all(installations.map((inst) => deps.dispatch(recipientUid, messageId, inst, wakeData)));
  };
}

/** AD-18c server-side selection: non-stale, newest-refresh first, capped at 5. */
function selectInstallations(docs, now) {
  return docs
    .map((d) => ({ id: d.id, ...(d.data || {}) }))
    .filter((inst) => inst.stale !== true)
    .filter((inst) => !isInstallationStale(inst, now))
    .sort((a, b) => refreshMillis(b) - refreshMillis(a))
    .slice(0, MAX_INSTALLATIONS);
}

function isInstallationStale(inst, now) {
  const t = refreshMillis(inst);
  return t > 0 && now - t > INSTALLATION_STALE_MS;
}

function refreshMillis(inst) {
  const t = inst.updatedAt;
  if (!t) return 0;
  if (typeof t.toMillis === 'function') return t.toMillis();
  return typeof t === 'number' ? t : 0;
}

module.exports = {
  MAX_PAYLOAD_BYTES,
  PAIR_WINDOW_MS,
  PAIR_WINDOW_MAX,
  SENDER_WINDOW_MS,
  SENDER_WINDOW_MAX,
  INSTALLATION_STALE_MS,
  MAX_INSTALLATIONS,
  MAX_ATTEMPTS,
  isValidBase64,
  validatePayload,
  windowState,
  incrementWindows,
  createRelayHandler,
  pairingUsableForRelay,
  selectInstallations,
  isInstallationStale,
};

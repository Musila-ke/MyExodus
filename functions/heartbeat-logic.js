'use strict';
// Device-availability heartbeat decisions (partner event-delivery plan §7 Step 7 /
// §8.10-§8.11). Pure — no Firebase import — so the emulator and unit suites can drive every
// branch directly.
//
// Contract: an authenticated install submits a strictly increasing counter. The server's only
// durable facts are the last accepted counter, the server receipt time, the liveness
// generation, and the next partner-notification deadline. Nothing about monitoring state ever
// travels here (§8.10: no permission bitmask, category, or plaintext monitoring status).
//
// The namespace is (uid, install_id): a device whose anonymous identity rotates re-enrolls
// under a fresh uid and therefore a fresh document, so a lost client counter can never lock
// an install out (§9 "Counter resets or stale heartbeat replay"). A reinstall that loses its
// Firebase installation id gets a new document by construction.

const DMS_PARTNER_PUSH_MS = 6 * 60 * 60 * 1000;

// §10.13: server-side abuse control. The client's own 15-minute window is not a control — a
// modified client can call as fast as it likes, and every accepted beat writes state.
const MIN_ACCEPT_INTERVAL_MS = 30 * 1000;

const COUNTER_MAX = Number.MAX_SAFE_INTEGER;

/** `(uid, installId)` namespaced document id. UIDs are alphanumeric and installation ids use
 *  `[A-Za-z0-9_-]`, so `_` cannot collide the two halves. */
function stateDocId(uid, installId) {
  return `${uid}_${installId}`;
}

/** FID shape (22 base64url chars in practice) with generous bounds; anything else is junk. */
function isValidInstallId(value) {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{8,128}$/.test(value);
}

function isValidCounter(value) {
  return Number.isInteger(value) && value > 0 && value <= COUNTER_MAX;
}

/**
 * One heartbeat's decision over the prior state row.
 *
 * @param previous `{last_counter, last_received_at_ms, liveness_generation}` or null.
 * @param counter this request's counter.
 * @param nowMs server receipt time.
 * @returns {{ outcome: 'ACCEPTED'|'STALE'|'RATE_LIMITED', serverCounter: number,
 *            generation: number, lastReceivedAtMs: number|null, nextDeadlineAtMs: number|null }}
 */
function decideHeartbeat(previous, counter, nowMs) {
  if (!previous) {
    return {
      outcome: 'ACCEPTED',
      serverCounter: counter,
      generation: 1,
      lastReceivedAtMs: nowMs,
      nextDeadlineAtMs: nowMs + DMS_PARTNER_PUSH_MS,
    };
  }
  const lastCounter = Number(previous.last_counter) || 0;
  const generation = Number(previous.liveness_generation) || 0;
  const lastReceivedAtMs = Number(previous.last_received_at_ms) || 0;
  if (counter <= lastCounter) {
    // A replay or a client that lost its counter. It is a normal answer, not an error: the
    // client resyncs onto serverCounter (§7 Step 7 "stale server counter → resync"). It is
    // never liveness (§8.4 line 132: replayed requests must not update it).
    return {
      outcome: 'STALE',
      serverCounter: lastCounter,
      generation,
      lastReceivedAtMs: lastReceivedAtMs || null,
      nextDeadlineAtMs: null,
    };
  }
  if (nowMs - lastReceivedAtMs < MIN_ACCEPT_INTERVAL_MS) {
    // Valid and fresh, but faster than any honest client needs. Held as abuse control; the
    // previous receipt remains the liveness truth (nothing is lost — the first beat counted).
    return {
      outcome: 'RATE_LIMITED',
      serverCounter: lastCounter,
      generation,
      lastReceivedAtMs: lastReceivedAtMs || null,
      nextDeadlineAtMs: null,
    };
  }
  return {
    outcome: 'ACCEPTED',
    serverCounter: counter,
    generation: generation + 1,
    lastReceivedAtMs: nowMs,
    // A scheduler outage never rebases this (§8 Step 8); only an ingestion outage does, and
    // that compensation belongs to the deadline processor's read path.
    nextDeadlineAtMs: nowMs + DMS_PARTNER_PUSH_MS,
  };
}

module.exports = {
  DMS_PARTNER_PUSH_MS,
  MIN_ACCEPT_INTERVAL_MS,
  stateDocId,
  isValidInstallId,
  isValidCounter,
  decideHeartbeat,
};

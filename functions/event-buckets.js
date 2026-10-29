'use strict';
// Plan §8.8 event token buckets: two scopes (per UID, per pair), each metering two
// dimensions (events, bytes) on independent refill rates — a burst of tiny events must not
// exhaust the byte budget, nor a few large ones the event budget. Pure math, no Firestore,
// so `node --test` covers refill, clamping and throttle hints without an emulator.
//
// State is the persisted document shape minus storage types: { events, bytes, updatedAt }
// where updatedAt is epoch millis here and a Firestore Timestamp on disk (the handler
// converts; the cleanup job needs a comparable, indexable field).

const UID_BUCKET = {
  eventCapacity: 30,
  eventPerSecond: 2,
  byteCapacity: 1024 * 1024,
  bytePerMinute: 10 * 1024 * 1024,
};

const PAIR_BUCKET = {
  eventCapacity: 60,
  eventPerSecond: 4,
  byteCapacity: 2 * 1024 * 1024,
  bytePerMinute: 20 * 1024 * 1024,
};

// §8.8 bounded retry hint: a throttled caller never waits more than a minute, however far
// the bucket is from fitting the charge.
const MAX_RETRY_AFTER_MS = 60 * 1000;

function refilled(prev, config, nowMs) {
  const state = prev != null &&
    typeof prev.events === 'number' && typeof prev.bytes === 'number' &&
    typeof prev.updatedAt === 'number' ? prev : null;
  if (state == null) {
    return { events: config.eventCapacity, bytes: config.byteCapacity, updatedAt: nowMs };
  }
  // A rolled-back clock refills nothing and charges nothing — never a windfall.
  const elapsed = Math.max(0, nowMs - state.updatedAt);
  return {
    events: Math.min(config.eventCapacity, state.events + (elapsed * config.eventPerSecond) / 1000),
    bytes: Math.min(config.byteCapacity, state.bytes + (elapsed * config.bytePerMinute) / 60000),
    updatedAt: state.updatedAt,
  };
}

/**
 * Refill from the previous document state, then decide. `next` is the post-charge state for
 * an allowed charge; a rejection returns the refilled state but carries no charge, and §8.8
 * writes nothing on rejection, so callers must not persist `next` in that case.
 */
function decideCharge(prev, config, chargeBytes, nowMs) {
  const bucket = refilled(prev, config, nowMs);
  if (bucket.events >= 1 && bucket.bytes >= chargeBytes) {
    return {
      allowed: true,
      next: { events: bucket.events - 1, bytes: bucket.bytes - chargeBytes, updatedAt: nowMs },
    };
  }
  const waitEvents = bucket.events >= 1
    ? 0
    : ((1 - bucket.events) * 1000) / config.eventPerSecond;
  const waitBytes = bucket.bytes >= chargeBytes
    ? 0
    : ((chargeBytes - bucket.bytes) * 60000) / config.bytePerMinute;
  return {
    allowed: false,
    retryAfterMs: Math.min(MAX_RETRY_AFTER_MS, Math.max(1, Math.ceil(Math.max(waitEvents, waitBytes)))),
    next: { ...bucket, updatedAt: nowMs },
  };
}

module.exports = { UID_BUCKET, PAIR_BUCKET, MAX_RETRY_AFTER_MS, decideCharge };

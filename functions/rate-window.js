'use strict';
// Fixed-window counter math, shared by the two rate-limited paths: the mailbox relay's
// AD-23 windows (relay-logic.js — its first home, re-exported there for its test suite)
// and the §10.5 pairing-path window (pairing-guards.js).

/**
 * Fixed-window counter step. A missing window, an elapsed window, or a `start` in the
 * future (rollback/skew guard, AD-16 spirit) starts a fresh window; otherwise the window
 * is kept. Returns the new state — callers check the CURRENT count before incrementing.
 */
function windowState(prev, now, windowMs) {
  if (!prev || typeof prev.start !== 'number' || typeof prev.count !== 'number' ||
      now - prev.start >= windowMs || now < prev.start) {
    return { start: now, count: 0 };
  }
  return { start: prev.start, count: prev.count };
}

module.exports = { windowState };

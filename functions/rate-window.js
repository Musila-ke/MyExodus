'use strict';
// Fixed-window counter math, shared by the rate-limited paths: the mailbox relay's AD-23
// windows (relay-logic.js — its first home, re-exported there for its test suite), the
// §10.5 pairing-path window (pairing-guards.js), and the partner delivery registration and
// ingress callables. `consumeLimit` is the transactional gate: it reads the counter and
// writes the increment in the same transaction, which is what makes it race-free.

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

/**
 * Advances `path`'s fixed window by one. Returns false when the window is already at `max`
 * (the rejected attempt is not counted and does not extend the window it was rejected by).
 * The caller must run this inside a transaction; the read and the write are one unit.
 */
async function consumeLimit(db, path, max, windowMs, at) {
  const ref = db.doc(path);
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const window = windowState(snap.exists ? snap.get('window') : null, at, windowMs);
    if (window.count >= max) return false;
    tx.set(ref, { window: { start: window.start, count: window.count + 1 } }, { merge: false });
    return true;
  });
}

module.exports = { windowState, consumeLimit };

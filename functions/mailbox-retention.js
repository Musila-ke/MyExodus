'use strict';
// Pure AD-18b retention decisions for the mailbox cleanup function. No admin SDK, so
// `npm test` can run it without an initialized app (same split as relay-logic.js/relay.js).

const DAY_MS = 24 * 60 * 60 * 1000;
const MIN_RETENTION_MS = 7 * DAY_MS;
const MAX_RETENTION_MS = 30 * DAY_MS;

/**
 * AD-18b: delete a mailbox document when it has aged past the 7-day floor AND every
 * currently-registered installation has acked it — or unconditionally at the 30-day cap,
 * whichever comes first. `allAcked` is vacuously true for a recipient with no registered
 * non-stale installations; the 30-day cap bounds the rest.
 */
function shouldDeleteMailbox(ageMs, allAcked) {
  if (ageMs >= MAX_RETENTION_MS) return true;
  return ageMs >= MIN_RETENTION_MS && allAcked;
}

/**
 * The installations that still owe an ack: registered rows not marked stale (AD-18c
 * marks dead tokens stale; a stale row will never ack). Input shapes are plain objects so
 * this stays testable.
 */
function unackedInstallationIds(installations, ackedIds) {
  return installations
    .filter((inst) => inst.stale !== true && !ackedIds.has(inst.id))
    .map((inst) => inst.id);
}

module.exports = {
  DAY_MS,
  MIN_RETENTION_MS,
  MAX_RETENTION_MS,
  shouldDeleteMailbox,
  unackedInstallationIds,
};

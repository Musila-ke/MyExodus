'use strict';
// A composite index added by a deploy rejects every query that needs it with
// FAILED_PRECONDITION (gRPC code 9) until the build finishes. Scheduled functions must treat
// that as "not yet", not as a failure: throwing turns a self-resolving deploy window into
// 500s, error-rate alert pages, and incident emails (owner report 2026-10-21 — the
// dmsDeadlineWorker failed this way while dms_notification_outbox's index built). Skipping
// the pass loses nothing; the next tick re-runs the same bounded query.

const { logger } = require('firebase-functions');

function isIndexBuildInProgress(error) {
  if (!error) return false;
  const code = error.code !== undefined ? error.code : error.status;
  const precondition = code === 9 || code === 'FAILED_PRECONDITION';
  if (!precondition) return false;
  return /requires an index|index is currently building/i.test(String(error.message || ''));
}

/** Wraps a scheduled handler so an index-build window defers the pass instead of failing it. */
function deferWhileIndexBuilds(run, { log = logger } = {}) {
  return async (...args) => {
    try {
      return await run(...args);
    } catch (error) {
      if (!isIndexBuildInProgress(error)) throw error;
      log.warn('scheduled pass deferred: Firestore index still building', {
        error: String(error.message || error).slice(0, 200),
      });
      return { deferred: 'INDEX_BUILDING' };
    }
  };
}

module.exports = { isIndexBuildInProgress, deferWhileIndexBuilds };

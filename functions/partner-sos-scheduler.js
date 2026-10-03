'use strict';
// Partner SOS repair scheduler (plan §8.11/§6.6, Step 4): the every-5-minute sweep that
// re-drives due queue documents through the same `pushNudge` engine. The root queue query
// is the only recovery entry point — no nudge collection-group scans, ever (§8.11). The
// lease inside `pushNudge` is the correctness mechanism, so overlap with the creation
// trigger or a slow previous sweep is harmless; this module never deletes or mutates a
// queue document itself.

const { onSchedule } = require('firebase-functions/v2/scheduler');
const { logger } = require('firebase-functions');

const { hashForLog } = require('./partner-sos');

const REGION = 'us-central1';
const SWEEP_LIMIT = 50; // §8.11: limit 50 per sweep

/**
 * Test seam (house pattern, cf. `_createPushNudge`): the raw sweep with injected db/engine,
 * driven directly by `emulator-test/partner-sos-scheduler.emulator.js`.
 */
function createResumePendingNudgesHandler({ db, pushNudge, now = () => Date.now() }) {
  return async function resumePendingNudgesHandler() {
    const nowMs = now();
    const due = await db
      .collection('nudgePushQueue')
      .where('nextAttemptAtMs', '<=', nowMs)
      .orderBy('nextAttemptAtMs', 'asc')
      .limit(SWEEP_LIMIT)
      .get();

    let resumed = 0;
    let skipped = 0;
    let errored = 0;
    for (const doc of due.docs) {
      const pairId = doc.get('pairId');
      const attempt = typeof doc.get('attemptCount') === 'number' ? doc.get('attemptCount') : 0;
      const createdAtMs = doc.get('createdAtMs');
      const ageMs = typeof createdAtMs === 'number' ? nowMs - createdAtMs : null;
      if (typeof pairId !== 'string' || pairId.length === 0) {
        logger.error('nudge_resume', {
          nudgeHash: hashForLog(doc.id),
          attempt,
          ageMs,
          outcome: 'malformed_queue_document',
        });
        errored += 1;
        continue;
      }
      try {
        // One queue document per try/catch: a poison record must not stop the sweep, and
        // the next 5-minute tick retries whatever was left behind.
        const result = await pushNudge(pairId, doc.id);
        logger.info('nudge_resume', {
          nudgeHash: hashForLog(doc.id),
          attempt,
          ageMs,
          outcome: result.outcome,
        });
        if (result.outcome === 'not_owner' || result.outcome === 'missing' ||
            result.outcome === 'terminal_cleaned' || result.outcome === 'orphan_deleted') {
          skipped += 1;
        } else {
          resumed += 1;
        }
      } catch (err) {
        errored += 1;
        logger.error('nudge_resume', {
          nudgeHash: hashForLog(doc.id),
          attempt,
          ageMs,
          outcome: 'error',
          errorCode: err && err.code ? err.code : 'unknown',
        });
      }
    }

    // §14.1 fields on the sweep summary: counts only, no identifiers.
    logger.info('nudge_resume', {
      event: 'sweep_complete',
      due: due.size,
      resumed,
      skipped,
      errored,
    });
    return { due: due.size, resumed, skipped, errored };
  };
}

/** §8.11 function configuration: 5-minute schedule, maxInstances 1, low concurrency. */
function createResumePendingNudgesSchedule({ db, pushNudge }) {
  return onSchedule(
    {
      schedule: 'every 5 minutes',
      region: REGION,
      memory: '256MiB',
      timeoutSeconds: 60,
      maxInstances: 1,
      concurrency: 1,
      retryCount: 0,
    },
    createResumePendingNudgesHandler({ db, pushNudge }),
  );
}

module.exports = { createResumePendingNudgesHandler, createResumePendingNudgesSchedule };

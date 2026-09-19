'use strict';
// AD-10: pure decision logic for the per-installation dispatch record state machine, shared
// by the relay trigger (Step 5) and the Cloud Scheduler retry sweep (Step 6). Every effect is
// injected, so §12's Cloud-Function cases — a duplicate trigger still sends exactly one push
// per installation, installations fail independently, the 8th failed attempt is terminal —
// are provable by call counts and record patches in `npm test`. The SDK wiring lives in
// dispatch-record.js / sweep.js, mirroring the relay-logic.js / relay.js split.

const { UnknownProviderError } = require('./dispatch');
const { MAX_ATTEMPTS } = require('./relay-logic');

/** AD-11: the wake signal is generic; it never carries event content. */
function wakeData(messageId) {
  return { messageId, type: 'domain-event' };
}

/**
 * AD-10 at-least-once: a record already DISPATCHED is a no-op (that is what keeps a duplicate
 * trigger or a race with the sweep from double-sending); PENDING/FAILED_RETRYABLE may be
 * attempted again — the attempts counter is the bound.
 *
 * `deps`: { readRecord(recipientUid, messageId, installationId) → Promise<object|null>,
 *           writeRecord(recipientUid, messageId, installationId, patch) → Promise<void>,
 *           markInstallationStale(recipientUid, installationId) → Promise<void>,
 *           dispatch(installation, wakeData) → Promise<void>,
 *           isTokenInvalid(provider, err) → boolean,
 *           logger }
 *
 * Returns the outcome ('SKIPPED' | 'DISPATCHED' | 'FAILED_RETRYABLE' | 'FAILED_TERMINAL').
 */
function createAttemptDispatch(deps) {
  return async function attemptDispatch(recipientUid, messageId, installation, wake) {
    const existing = await deps.readRecord(recipientUid, messageId, installation.id);
    if (existing && existing.status === 'DISPATCHED') return 'SKIPPED';

    const attempts = existing ? (existing.attempts || 0) + 1 : 1;
    await deps.writeRecord(recipientUid, messageId, installation.id, {
      installationId: installation.id,
      recipientUid,
      provider: installation.provider,
      status: 'PENDING',
      attempts,
    });

    try {
      await deps.dispatch(installation, wake);
      await deps.writeRecord(recipientUid, messageId, installation.id, { status: 'DISPATCHED' });
      return 'DISPATCHED';
    } catch (err) {
      const tokenGone = deps.isTokenInvalid(installation.provider, err);
      const terminal = tokenGone || err instanceof UnknownProviderError || attempts >= MAX_ATTEMPTS;
      if (tokenGone) await deps.markInstallationStale(recipientUid, installation.id);
      await deps.writeRecord(recipientUid, messageId, installation.id, {
        status: terminal ? 'FAILED_TERMINAL' : 'FAILED_RETRYABLE',
      });
      deps.logger.warn('dispatch failed', {
        messageId,
        installationId: installation.id,
        provider: installation.provider,
        code: err && err.code,
        attempts,
        terminal,
      });
      return terminal ? 'FAILED_TERMINAL' : 'FAILED_RETRYABLE';
    }
  };
}

/**
 * §12: "One installation failing and another succeeding results in independent per-installation
 * states, with the Scheduler sweep correctly retrying only the failed one." The "only the
 * failed one" selection is the collection-group query in sweep.js (status in
 * [PENDING, FAILED_RETRYABLE] AND attempts < MAX); this pass processes exactly the records the
 * query returned: an installation row that is gone or stale is a dead end (terminal — AD-18c,
 * a stale token never comes back), anything else gets one real attempt.
 *
 * `deps`: { listRetryable() → Promise<Array<{ ref, recipientUid, messageId, installationId }>>,
 *           getInstallation(recipientUid, installationId) → Promise<object|null>,
 *           markGone(record) → Promise<void>,
 *           attempt(recipientUid, messageId, installation, wakeData) → Promise<outcome>,
 *           logger }
 */
function createSweepPass(deps) {
  return async function runSweep() {
    const stale = await deps.listRetryable();
    if (stale.length === 0) return { scanned: 0, redispatched: 0, terminal: 0 };

    let redispatched = 0;
    let terminal = 0;
    for (const record of stale) {
      const installation = await deps.getInstallation(record.recipientUid, record.installationId);
      if (!installation || installation.stale === true) {
        await deps.markGone(record);
        terminal += 1;
        continue;
      }
      await deps.attempt(record.recipientUid, record.messageId, installation, wakeData(record.messageId));
      redispatched += 1;
    }
    deps.logger.info('dispatch sweep', { scanned: stale.length, redispatched, terminal });
    return { scanned: stale.length, redispatched, terminal };
  };
}

module.exports = { createAttemptDispatch, createSweepPass, wakeData };

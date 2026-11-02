package com.example.myexodus.accountability.sync

import kotlinx.coroutines.withTimeoutOrNull

/** Bounded send leg (finding 2026-11-02): a prompt left up or a wedged pass used to keep
 *  `sending` true forever, with the sheet refusing every dismiss — a stuck modal. The message
 *  is journaled before any of these legs, so the timeout's honest verdict is QUEUED. */
internal const val MESSAGE_SEND_TIMEOUT_MS = 45_000L

/**
 * The composer's send outcome (Req 15), in the user's terms rather than the journal's:
 *
 *  - [SENT] — the message left this phone: this pass wrote it to the partner's mailbox (the
 *    push relay then wakes the partner's device).
 *  - [QUEUED] — saved on this phone and handed to the retry cadence. The honest reading when
 *    offline or when the auth window is cold — the journal-first contract guarantees it leaves
 *    on a later pass, so this is never presented as a failure.
 *  - [FAILED] — nothing was written; the composer says so and stays open.
 */
enum class MessageSendOutcome { SENT, QUEUED, FAILED }

/**
 * Derives the composer's outcome after a journal-first emit (owner UX ruling 2026-10-01: the
 * sender must know whether the message actually went, and the kick must not wait for the next
 * pass). Order matters:
 *
 *  1. nothing emitted -> [MessageSendOutcome.FAILED];
 *  2. offline -> [MessageSendOutcome.QUEUED] without running the pass (it cannot deliver);
 *  3. [authorize] — the cold-auth-window gate. A message is signed into the pair's hash chain
 *     (the same 900 s §8.1 window as every other partner event), so a cold window cannot be
 *     signed silently: the caller warms it with the user's prompt (device-journey finding
 *     2026-10-01: without this a message sat unlinked indefinitely, because nothing else in a
 *     normal session raises a prompt). A declined or impossible prompt is not a failure —
 *     the message is already journaled and rides the next warmed pass -> [QUEUED];
 *  4. otherwise run [runPass] (the send-kick: drain -> mailbox -> relay -> FCM) and ask
 *     [stillPending] whether anything for the pair is still queued. Only a cleared queue
 *     reports [MessageSendOutcome.SENT] — a failed pass or an unreadable queue stays
 *     [MessageSendOutcome.QUEUED], so a transport failure can never read as delivered.
 */
internal suspend fun messageSendOutcome(
    emitted: Boolean,
    online: Boolean,
    authorize: suspend () -> Boolean,
    runPass: suspend () -> Unit,
    stillPending: suspend () -> Boolean,
    timeoutMs: Long = MESSAGE_SEND_TIMEOUT_MS,
): MessageSendOutcome {
    if (!emitted) return MessageSendOutcome.FAILED
    if (!online) return MessageSendOutcome.QUEUED
    val authorized = withTimeoutOrNull(timeoutMs) {
        runCatching { authorize() }.getOrDefault(false)
    } ?: false
    if (!authorized) return MessageSendOutcome.QUEUED
    withTimeoutOrNull(timeoutMs) { runCatching { runPass() } }
    // A leg that timed out is not a cleared queue: only an explicit false says SENT.
    return if (withTimeoutOrNull(timeoutMs) { runCatching { stillPending() }.getOrDefault(true) } != false) {
        MessageSendOutcome.QUEUED
    } else {
        MessageSendOutcome.SENT
    }
}

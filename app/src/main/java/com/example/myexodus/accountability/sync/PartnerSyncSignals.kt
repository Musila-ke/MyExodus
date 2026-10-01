package com.example.myexodus.accountability.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Process-wide repaint signal for partner surfaces (owner UX ruling 2026-10-01: pages adjust
 * themselves — no manual check, no resume needed). [runPartnerSyncPass] ticks this after any
 * pass that pulled ≥1 partner event into local state, or that advanced an unpair (a peer's
 * deletion request shredded the pair, a journal row moved, the partner's ack confirmed) —
 * deletion passes pull nothing yet end exactly what these screens show. Screens holding
 * one-shot reads (the Partner dashboard) re-read on the tick. The Home pinned-message card
 * does not need this — it observes Room directly (`observePinnedMessages`).
 *
 * Buffer 1 + DROP_OLDEST: a tick while nothing collects (app backgrounded, pass run by the
 * worker) is dropped, never queued — the next compose or resume re-reads anyway, and a stale
 * replay must not trigger a spurious reload on first compose.
 */
object PartnerSyncSignals {
    val pulledEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
}

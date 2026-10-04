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

    /** Sender-side kick (owner mandate 2026-10-04): a local transition was journalled and the
     *  partner should hear about it now, not at the next 15-min pass.
     *  [com.example.myexodus.accountability.activity.PartnerActivityJournal] ticks this at its
     *  single write choke point; the app scope turns ticks into an immediate sync run. Same
     *  drop-when-nobody-collects contract as [pulledEvents] (a missed tick is healed by the
     *  pass a later transition — or the cadence — triggers). */
    val activityEmitted = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
}

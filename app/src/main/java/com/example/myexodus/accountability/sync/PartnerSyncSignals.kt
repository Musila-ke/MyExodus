package com.example.myexodus.accountability.sync

import com.example.myexodus.accountability.monitoring.StandbyBucket
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

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

    /** §4.7 App Standby explanation: emitted at most once per constrained transition in this
     *  process (the client enforces that), shown once as a non-intrusive snackbar. Never a
     *  permission request — the copy names where to look, it never claims a setting is required. */
    val standbyNotice = MutableSharedFlow<StandbyBucket>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** ADR #216: a pass stopped with partner-visible rows still queued because the §8.1
     *  signing window was cold. [runPartnerSyncPass] ticks this and the Activity retries the
     *  pass automatically once the window is warm (owner ruling 2026-11-05: never a prompt).
     *  Background runs tick with nobody collecting — dropped by design; the next foreground
     *  pass re-detects the same block and ticks again. */
    val signingNeeded = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Owner ruling 2026-11-01, tightened 2026-11-05: a rule or batch write must reach the
     *  partner on its own — no pending banner, no "Send now", no device-key prompt. Emitted
     *  from the rules write path (after the journal transaction commits); the Activity runs an
     *  automatic pass, which delivers while the signer is warm and otherwise leaves the rows
     *  journaled for the next warm pass. Same drop-when-nobody-collects contract as the rest
     *  of this object. */
    val ruleChangesJournaled = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Owner-approved A (2026-11-09): true while the last pass left partner-visible rows queued
     *  behind a cold §8.1 signing window. State rather than an event, so a partner page opened
     *  after the pass still tells the truth; any pass that gets through clears it. Automatic
     *  delivery still never prompts — the surface only explains the wait and offers Check now. */
    val signingDeferred = MutableStateFlow(false)

    /** Owner mandate 2026-11-09 ("a user can't have done an operation then the partner waits"):
     *  a partner-visible write caused by the subject's own tap requests the delivery pass at
     *  that moment, and that explicit path may raise the device-key prompt. Emitted from
     *  [com.example.myexodus.blocking.RulesRepository] only for user-act writes — the automatic
     *  expiry sweep stays on the never-prompting [ruleChangesJournaled] path (owner ruling
     *  2026-11-05). The Activity answers with the same heal Check now runs. */
    val deliveryRequested = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
}

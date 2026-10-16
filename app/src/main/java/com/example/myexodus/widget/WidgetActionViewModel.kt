package com.example.myexodus.widget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myexodus.R
import com.example.myexodus.accountability.sos.NudgeRepository
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.recovery.RecoverySessionRepository
import com.example.myexodus.ui.home.SosViewModel
import com.example.myexodus.ui.partner.PartnerDashboardSource
import com.example.myexodus.ui.partner.PartnerSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State behind the widget's hold-to-confirm surface ([WidgetActionActivity], widget v2.1 — owner
 * correction 2026-10-16). The hold gesture is the accident protection; this class is the
 * execution: "log an urge" writes the same terminal session row the Recovery screen's log sheet
 * writes ([RecoverySessionRepository.logOnly], entry [RecoverySession.ENTRY_WIDGET]), and
 * reach-out drives the same §8.15 sender machine Home uses.
 *
 * [sos] is this surface's own [SosViewModel] over the shared [NudgeRepository] — Home keeps its
 * own instance; §8.15's machine (UUID identity, retry-stable envelope, 5-second auto-returns) is
 * reused whole rather than re-implemented, so the surface can never drift from Home's semantics.
 *
 * Copy stays out of here (the [SosViewModel] pattern): this class exposes pure state, the screen
 * maps it to strings — except [urgeOutcomeRes], kept next to the states it names so the mapping
 * is unit-testable as a matrix.
 */
@HiltViewModel
class WidgetActionViewModel @Inject constructor(
    private val sessions: RecoverySessionRepository,
    private val nudgeRepository: NudgeRepository,
    private val partnerSource: PartnerDashboardSource,
    private val partnerSelection: PartnerSelection,
) : ViewModel() {

    val sos = SosViewModel(nudgeRepository)

    private val _urge = MutableStateFlow(UrgeOutcome.None)
    val urge: StateFlow<UrgeOutcome> = _urge.asStateFlow()

    private val _partner = MutableStateFlow<PartnerGate>(PartnerGate.Checking)
    val partner: StateFlow<PartnerGate> = _partner.asStateFlow()

    init {
        viewModelScope.launch {
            // The same §19.5 target rule the sender resolves at tap time (AppShell's FAB gate):
            // a null name means no live pair, and reach-out renders its honest empty state
            // instead of a hold that could only fail (user need: never a control that traps).
            val name = withContext(Dispatchers.IO) {
                runCatching { partnerSource.targetPartnerName(partnerSelection.selectedPairId) }
                    .getOrNull()
            }
            _partner.value = if (name == null) PartnerGate.Missing else PartnerGate.Ready(name)
        }
    }

    /**
     * A fresh open (or a second control tap through `onNewIntent`) starts from a clean slate —
     * the ring must be armed, not stuck on the previous session's result. SOS is deliberately not
     * reset: §8.15's machine keeps its retained envelope so the next hold is the retry.
     */
    fun reset() {
        _urge.value = UrgeOutcome.None
    }

    /** The urge-log execution: the exact path the Recovery screen's log sheet uses
     *  ([RecoverySessionRepository.logOnly] — a terminal-born, deliberately never-mirrored row),
     *  so the widget and the app can never log differently. The ring already gates this, but the
     *  VM is the last line: a double delivery must not write twice. */
    fun logUrge() {
        if (_urge.value == UrgeOutcome.InFlight) return
        _urge.value = UrgeOutcome.InFlight
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                sessions.logOnly(
                    intensity = null,
                    entry = RecoverySession.ENTRY_WIDGET,
                    zoneId = ZoneId.systemDefault().id,
                    nowEpochMs = System.currentTimeMillis(),
                )
            }
            _urge.value = UrgeOutcome.Saved
        }
    }

    /** The reach-out execution: the shared §8.15 path, default message (the surface has no
     *  composer — Home's dialog is where text is edited). */
    fun logReachOut(defaultMessage: String) {
        sos.onTap(defaultMessage)
    }

    sealed interface PartnerGate {
        /** The roster read is a fast local query; this state is one frame at most. */
        data object Checking : PartnerGate

        /** No live pair: the honest empty state, no hold control. */
        data object Missing : PartnerGate

        /** A live pair. [name] names the recipient when known; null renders the generic body. */
        data class Ready(val name: String?) : PartnerGate
    }

    /** FR-5 makes the write best-effort by contract (a store that is not open skips the write
     *  rather than failing the user), so there is no failure state: the only terminal outcome is
     *  [Saved]. */
    enum class UrgeOutcome { None, InFlight, Saved }
}

/**
 * The copy each urge outcome puts on the surface, in the disclosure pair the titles/bodies
 * already carry: the app's own vocabulary ("urge") only after the user opted into disclosure,
 * neutral wording while discreet (FR5 — the surface a hold opens must not print the word the
 * card just refused to show). Null = still interactive (no result text). Pure, so the full
 * outcome × disclosure matrix is unit-testable.
 */
internal fun urgeOutcomeRes(
    outcome: WidgetActionViewModel.UrgeOutcome,
    discreet: Boolean,
): Int? = when (outcome) {
    WidgetActionViewModel.UrgeOutcome.None,
    WidgetActionViewModel.UrgeOutcome.InFlight,
    -> null

    WidgetActionViewModel.UrgeOutcome.Saved ->
        if (discreet) R.string.widget_urge_saved_discreet else R.string.widget_urge_saved
}

/**
 * Whether the hold ring accepts a gesture in this outcome. Only the armed idle state does: the
 * write state closes holding by design, and Saved is a terminal state that closes itself. Pure,
 * and pinned by the same-domain test as the copy matrix.
 */
internal fun urgeHoldArmed(outcome: WidgetActionViewModel.UrgeOutcome): Boolean =
    outcome == WidgetActionViewModel.UrgeOutcome.None

/**
 * Whether an outcome closes the surface on its own: the saved state is self-evident, so the
 * surface dismisses and lets the user see the widget behind it.
 */
internal fun urgeAutoFinishes(outcome: WidgetActionViewModel.UrgeOutcome): Boolean =
    outcome == WidgetActionViewModel.UrgeOutcome.Saved

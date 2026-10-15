package com.example.myexodus.widget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myexodus.R
import com.example.myexodus.accountability.sos.NudgeRepository
import com.example.myexodus.streaks.HabitRepository
import com.example.myexodus.ui.home.SosViewModel
import com.example.myexodus.ui.partner.PartnerDashboardSource
import com.example.myexodus.ui.partner.PartnerSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State behind the widget's hold-to-confirm surface ([WidgetActionActivity], widget v2 — owner
 * spec 2026-10-15). The hold gesture is the accident protection; this class is the execution:
 * a relapse logs against the exact `habitId` the tapped pill displayed, and SOS drives the same
 * §8.15 sender machine Home uses.
 *
 * [sos] is this surface's own [SosViewModel] over the shared [NudgeRepository] — Home keeps its
 * own instance; §8.15's machine (UUID identity, retry-stable envelope, 5-second auto-returns) is
 * reused whole rather than re-implemented, so the surface can never drift from Home's semantics.
 *
 * Copy stays out of here (the [SosViewModel] pattern): this class exposes pure state, the screen
 * maps it to strings — except [relapseOutcomeRes], kept next to the states it names so the
 * mapping is unit-testable as a matrix.
 */
@HiltViewModel
class WidgetActionViewModel @Inject constructor(
    private val habits: HabitRepository,
    private val nudgeRepository: NudgeRepository,
    private val partnerSource: PartnerDashboardSource,
    private val partnerSelection: PartnerSelection,
) : ViewModel() {

    val sos = SosViewModel(nudgeRepository)

    private val _relapse = MutableStateFlow(RelapseOutcome.None)
    val relapse: StateFlow<RelapseOutcome> = _relapse.asStateFlow()

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
     * A fresh open (or a second pill tap through `onNewIntent`) starts from a clean slate — the
     * ring must be armed, not stuck on the previous session's result. SOS is deliberately not
     * reset: §8.15's machine keeps its retained envelope so the next hold is the retry.
     */
    fun reset() {
        _relapse.value = RelapseOutcome.None
    }

    fun logRelapse(habitId: String) {
        // The ring already gates this, but the VM is the last line: a double delivery of the
        // hold (or a tap racing the state flip) must not write twice.
        if (_relapse.value == RelapseOutcome.InFlight) return
        _relapse.value = RelapseOutcome.InFlight
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { habits.logRelapse(habitId) }
            _relapse.value = when (result) {
                is HabitRepository.RelapseResult.RolledOver -> RelapseOutcome.Logged
                // A write inside the idempotency window: the state the user asked for already
                // holds (the rollover just happened), so the honest report is success-adjacent,
                // not a failure.
                HabitRepository.RelapseResult.DuplicateIgnored -> RelapseOutcome.AlreadyLogged
                // The displayed track has no active streak left (ended or removed since the
                // widget rendered): the app is where that gets resolved.
                HabitRepository.RelapseResult.NoActiveStreak -> RelapseOutcome.Gone
                // Clock anomaly (unreachable from this surface: the timestamp is always "now")
                // and store-unavailable both mean "nothing was written, holding again is the
                // retry" — one honest state.
                HabitRepository.RelapseResult.RejectedBeforeStreakStart,
                HabitRepository.RelapseResult.StoreUnavailable,
                -> RelapseOutcome.Failed
            }
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

    enum class RelapseOutcome { None, InFlight, Logged, AlreadyLogged, Gone, Failed }
}

/**
 * The copy each relapse outcome puts on the surface. Null = still interactive (no result text).
 * Pure, so the full outcome matrix is unit-testable.
 */
internal fun relapseOutcomeRes(outcome: WidgetActionViewModel.RelapseOutcome): Int? = when (outcome) {
    WidgetActionViewModel.RelapseOutcome.None,
    WidgetActionViewModel.RelapseOutcome.InFlight,
    -> null

    WidgetActionViewModel.RelapseOutcome.Logged -> R.string.widget_relapse_logged
    WidgetActionViewModel.RelapseOutcome.AlreadyLogged -> R.string.widget_relapse_already_logged
    WidgetActionViewModel.RelapseOutcome.Gone -> R.string.widget_relapse_gone
    WidgetActionViewModel.RelapseOutcome.Failed -> R.string.widget_relapse_failed
}

/**
 * Whether the hold ring accepts a gesture in this outcome. Failed stays armed — the write never
 * happened, and the surface's own hint asks for the hold again; the other terminal states are
 * closed for holding by design (InFlight is a write, Logged/AlreadyLogged close themselves, Gone
 * points at the app). Pure, and pinned by the same-domain test as the copy matrix.
 */
internal fun relapseHoldArmed(outcome: WidgetActionViewModel.RelapseOutcome): Boolean =
    outcome == WidgetActionViewModel.RelapseOutcome.None ||
        outcome == WidgetActionViewModel.RelapseOutcome.Failed

/**
 * Whether an outcome closes the surface on its own: the two success-adjacent states are
 * self-evident (the widget behind the scrim flips to Day 1), so the surface dismisses and lets
 * the user see it. Terminal problems stay open — they carry copy the user must read first.
 */
internal fun relapseAutoFinishes(outcome: WidgetActionViewModel.RelapseOutcome): Boolean =
    outcome == WidgetActionViewModel.RelapseOutcome.Logged ||
        outcome == WidgetActionViewModel.RelapseOutcome.AlreadyLogged

package com.example.myexodus.ui.streaks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.streaks.HabitRepository
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.streaks.HabitSummary
import com.example.myexodus.streaks.MilestoneAlerter
import com.example.myexodus.streaks.WithdrawalSeverity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI-side holder of the habit state (plan §8 Step 5): collects nothing itself — the screens
 * consume [state] via `collectAsStateWithLifecycle` — and owns the two evaluation triggers,
 * [refresh] from `init` and from `MainActivity.onResume`. Nothing here runs because Compose
 * recomposed (Decision 9); this is the ViewModel boundary the plan names for exactly that.
 * [refresh] also rides the §14 catch-up sweep ([MilestoneAlerter.catchUpAll]) at the same
 * boundary: an alarm the platform suppressed or dropped is delivered at the next open instead
 * of never — for every habit, not just the displayed one (finding #22, device-caught
 * 2026-10-16), and each habit's evaluation re-arms its projected alarms.
 */
@HiltViewModel
class StreaksViewModel @Inject constructor(
    private val repository: HabitRepository,
    private val rulesRepository: RulesRepository,
    private val milestoneAlerter: MilestoneAlerter,
) : ViewModel() {

    /** Read-only snapshot flow from the repository; null until a habit is set up. */
    val state: StateFlow<HabitState?> = repository.state

    private val _resolved = MutableStateFlow(false)

    /** False until the first [refresh] completes — Home must not claim "no habit tracked"
     *  while the encrypted store is still opening (slow cold starts made that visible). */
    val resolved: StateFlow<Boolean> = _resolved.asStateFlow()

    private val _loadFailed = MutableStateFlow(false)

    /** True when the last [refresh] could not read the streak state (store refused to open, or
     *  the read threw): the Home milestone section's error state. Without it a failed read and
     *  an unfinished one are the same picture — a skeleton that never resolves. */
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    private val _habits = MutableStateFlow<List<HabitSummary>>(emptyList())

    /** All habits of the active profile (display-ordered) — the switcher's read model. */
    val habits: StateFlow<List<HabitSummary>> = _habits.asStateFlow()

    private val _addingHabit = MutableStateFlow(false)

    /** True while the setup form is open to add another habit (a habit already exists). */
    val addingHabit: StateFlow<Boolean> = _addingHabit.asStateFlow()

    private val _setupError = MutableStateFlow<SetupError?>(null)

    /** Setup-form failure (what/why/action, plan §9); null when there is nothing to show. The
     *  screen maps it to copy — resources stay out of the ViewModel (finding #20). */
    val setupError: StateFlow<SetupError?> = _setupError.asStateFlow()

    private val _onboardingInFlight = MutableStateFlow(false)

    /** §9 first line of duplicate-tap defense for the Start control, like
     *  [relapseInFlight]: `onboard` has no natural key to make its insert idempotent, so a
     *  second tap while the first write is out would create a duplicate habit and there is no
     *  delete path to undo it (finding #61). */
    val onboardingInFlight: StateFlow<Boolean> = _onboardingInFlight.asStateFlow()

    private val _relapseInFlight = MutableStateFlow(false)

    /** §9 first line of duplicate-tap defense: the control disables while a write is out. */
    val relapseInFlight: StateFlow<Boolean> = _relapseInFlight.asStateFlow()

    private val _relapseFeedback = MutableStateFlow<RelapseFeedback?>(null)

    /** Outcome of the last logged relapse; null before one is logged. The screen resolves it to
     *  copy (support line or error); the support text stays the test-pinned pure function. */
    val relapseFeedback: StateFlow<RelapseFeedback?> = _relapseFeedback.asStateFlow()

    init {
        refresh()
    }

    /** ViewModel-init and foreground trigger (plan §8 Step 5 / Decision 9). */
    fun refresh() {
        // Repository work on IO: the store open is a synchronous SQLCipher call that can block
        // seconds behind another opener's lock — on Dispatchers.Main that is an ANR from a
        // plain app resume (device-caught 2026-09-19, whole-class fix: every launch below).
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.refreshActive()
                // §14 sweep for every active habit (finding #22) — see the class KDoc.
                milestoneAlerter.catchUpAll()
                _habits.value = repository.habits()
                // The repository's own truth, not an unconditional true: a store-open failure
                // returns without a definite answer, and claiming resolved would show "no habit
                // tracked" over data that was merely unreachable.
                _resolved.value = repository.resolved.value
                _loadFailed.value = repository.loadFailed.value
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A read that threw (corrupt row, driver failure) is the same user-visible
                // state as a store that refused to open: keep unresolved, surface the error.
                _loadFailed.value = true
            }
        }
    }

    /** Multi-habit switch: the picked habit becomes the displayed one everywhere. */
    fun selectHabit(habitId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.selectHabit(habitId)
            _habits.value = repository.habits()
            _relapseFeedback.value = null
        }
    }

    /** Opens the setup form to add another habit (only reachable when one already exists). */
    fun beginAddHabit() {
        _setupError.value = null
        _addingHabit.value = true
    }

    fun cancelAddHabit() {
        _addingHabit.value = false
        _setupError.value = null
    }

    fun onboard(
        targetLabel: String,
        durationYears: Int,
        severity: WithdrawalSeverity,
        recoveryStartAt: Long,
    ) {
        if (_onboardingInFlight.value) return
        _onboardingInFlight.value = true
        _setupError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profileId = rulesRepository.ensureActiveProfile().userId
                when (val result = repository.onboard(
                    profileId = profileId,
                    targetIdentifier = targetLabel,
                    habitDurationYears = durationYears,
                    severity = severity,
                    recoveryStartAt = recoveryStartAt,
                )) {
                    is HabitRepository.OnboardingResult.Ready -> {
                        _relapseFeedback.value = null
                        _addingHabit.value = false
                        _habits.value = repository.habits()
                    }

                    is HabitRepository.OnboardingResult.Rejected -> {
                        _setupError.value = when (result.reason) {
                            HabitRepository.OnboardingResult.Rejected.Reason.START_IN_FUTURE ->
                                SetupError.START_IN_FUTURE

                            HabitRepository.OnboardingResult.Rejected.Reason.INVALID_DURATION ->
                                SetupError.INVALID_DURATION

                            HabitRepository.OnboardingResult.Rejected.Reason.IMPLAUSIBLE_CLOCK ->
                                SetupError.IMPLAUSIBLE_CLOCK

                            HabitRepository.OnboardingResult.Rejected.Reason.STORE_UNAVAILABLE ->
                                SetupError.STORE_UNAVAILABLE
                        }
                    }
                }
            } finally {
                _onboardingInFlight.value = false
            }
        }
    }

    /** Splash-card dismissal (§8 Step 4); the single combined card acknowledges all its
     *  days. The repository's DAO guard (`achieved_at IS NOT NULL`) makes each write safe. */
    fun acknowledgeMilestones(milestoneDays: List<Int>) {
        val habitId = state.value?.habitId ?: return
        viewModelScope.launch(Dispatchers.IO) { milestoneDays.forEach { repository.acknowledgeMilestone(habitId, it) } }
    }

    /** §6 relapse logging; the debounce flag is the first line of §9 duplicate-tap defense. */
    fun logRelapse() {
        val current = state.value ?: return
        if (_relapseInFlight.value) return
        _relapseInFlight.value = true
        _relapseFeedback.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Completed days, not the floored display count: "you showed up N days in a
                // row" must not claim a day the user had not finished.
                val endedDays = habitDays(current.currentStreakElapsedMs)
                val bestDays = habitDays(current.allTimeMaxElapsedMs)
                when (repository.logRelapse(current.habitId)) {
                    is HabitRepository.RelapseResult.RolledOver ->
                        _relapseFeedback.value = RelapseFeedback.Support(endedDays, bestDays)

                    HabitRepository.RelapseResult.RejectedBeforeStreakStart ->
                        _relapseFeedback.value = RelapseFeedback.RejectedBeforeStart

                    HabitRepository.RelapseResult.StoreUnavailable ->
                        _relapseFeedback.value = RelapseFeedback.StoreUnavailable

                    HabitRepository.RelapseResult.DuplicateIgnored,
                    HabitRepository.RelapseResult.NoActiveStreak,
                    -> Unit
                }
            } finally {
                _relapseInFlight.value = false
            }
        }
    }

    /** Setup-form failure the screen maps to copy (finding #20); resources stay out of VMs. */
    enum class SetupError { START_IN_FUTURE, INVALID_DURATION, IMPLAUSIBLE_CLOCK, STORE_UNAVAILABLE }

    /** The last relapse outcome, resolved to display copy by the screen (finding #20). */
    sealed interface RelapseFeedback {
        /** A rolled-over streak, with the numbers the support line needs — no error. */
        data class Support(val endedDays: Int, val bestDays: Int) : RelapseFeedback

        data object RejectedBeforeStart : RelapseFeedback

        data object StoreUnavailable : RelapseFeedback
    }
}

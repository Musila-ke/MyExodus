package com.example.myexodus.ui.streaks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.streaks.HabitRepository
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.streaks.HabitSummary
import com.example.myexodus.streaks.WithdrawalSeverity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI-side holder of the habit state (plan §8 Step 5): collects nothing itself — the screens
 * consume [state] via `collectAsStateWithLifecycle` — and owns the two evaluation triggers,
 * [refresh] from `init` and from `MainActivity.onResume`. Nothing here runs because Compose
 * recomposed (Decision 9); this is the ViewModel boundary the plan names for exactly that.
 */
@HiltViewModel
class StreaksViewModel @Inject constructor(
    private val repository: HabitRepository,
    private val rulesRepository: RulesRepository,
) : ViewModel() {

    /** Read-only snapshot flow from the repository; null until a habit is set up. */
    val state: StateFlow<HabitState?> = repository.state

    private val _resolved = MutableStateFlow(false)

    /** False until the first [refresh] completes — Home must not claim "no habit tracked"
     *  while the encrypted store is still opening (slow cold starts made that visible). */
    val resolved: StateFlow<Boolean> = _resolved.asStateFlow()

    private val _habits = MutableStateFlow<List<HabitSummary>>(emptyList())

    /** All habits of the active profile (display-ordered) — the switcher's read model. */
    val habits: StateFlow<List<HabitSummary>> = _habits.asStateFlow()

    private val _addingHabit = MutableStateFlow(false)

    /** True while the setup form is open to add another habit (a habit already exists). */
    val addingHabit: StateFlow<Boolean> = _addingHabit.asStateFlow()

    private val _setupError = MutableStateFlow<String?>(null)

    /** Setup-form error line (what/why/action copy); null when there is nothing to show. */
    val setupError: StateFlow<String?> = _setupError.asStateFlow()

    private val _relapseInFlight = MutableStateFlow(false)

    /** §9 first line of duplicate-tap defense: the control disables while a write is out. */
    val relapseInFlight: StateFlow<Boolean> = _relapseInFlight.asStateFlow()

    private val _relapseNote = MutableStateFlow<String?>(null)

    /** Supportive, non-punitive line after a logged relapse; null before one is logged. */
    val relapseNote: StateFlow<String?> = _relapseNote.asStateFlow()

    init {
        refresh()
    }

    /** ViewModel-init and foreground trigger (plan §8 Step 5 / Decision 9). */
    fun refresh() {
        viewModelScope.launch {
            repository.refreshActive()
            _habits.value = repository.habits()
            _resolved.value = true
        }
    }

    /** Multi-habit switch: the picked habit becomes the displayed one everywhere. */
    fun selectHabit(habitId: String) {
        viewModelScope.launch {
            repository.selectHabit(habitId)
            _habits.value = repository.habits()
            _relapseNote.value = null
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
        _setupError.value = null
        viewModelScope.launch {
            val profileId = rulesRepository.ensureActiveProfile().userId
            when (val result = repository.onboard(
                profileId = profileId,
                targetIdentifier = targetLabel,
                habitDurationYears = durationYears,
                severity = severity,
                recoveryStartAt = recoveryStartAt,
            )) {
                is HabitRepository.OnboardingResult.Ready -> {
                    _relapseNote.value = null
                    _addingHabit.value = false
                    _habits.value = repository.habits()
                }

                is HabitRepository.OnboardingResult.Rejected -> {
                    _setupError.value = when (result.reason) {
                        HabitRepository.OnboardingResult.Rejected.Reason.START_IN_FUTURE ->
                            "That start date is in the future, so the count can't begin there. " +
                                "Pick today or an earlier date and try again."

                        HabitRepository.OnboardingResult.Rejected.Reason.INVALID_DURATION ->
                            "Something in the setup didn't look right. Check your answers and try again."

                        HabitRepository.OnboardingResult.Rejected.Reason.IMPLAUSIBLE_CLOCK ->
                            "Your device's date looks off, so MyExodus can't start counting safely. " +
                                "Check your date and time settings, then try again."

                        HabitRepository.OnboardingResult.Rejected.Reason.STORE_UNAVAILABLE ->
                            "MyExodus can't reach its local database right now. Your data is safe — " +
                                "try again in a moment."
                    }
                }
            }
        }
    }

    /** Splash-card dismissal (§8 Step 4); the single combined card acknowledges all its
     *  days. The repository's DAO guard (`achieved_at IS NOT NULL`) makes each write safe. */
    fun acknowledgeMilestones(milestoneDays: List<Int>) {
        val habitId = state.value?.habitId ?: return
        viewModelScope.launch { milestoneDays.forEach { repository.acknowledgeMilestone(habitId, it) } }
    }

    /** §6 relapse logging; the debounce flag is the first line of §9 duplicate-tap defense. */
    fun logRelapse() {
        val current = state.value ?: return
        if (_relapseInFlight.value) return
        _relapseInFlight.value = true
        _relapseNote.value = null
        viewModelScope.launch {
            try {
                val endedDays = habitDays(current.currentStreakElapsedMs)
                val bestDays = habitDays(current.allTimeMaxElapsedMs)
                when (repository.logRelapse(current.habitId)) {
                    is HabitRepository.RelapseResult.RolledOver ->
                        _relapseNote.value = relapseSupportNote(endedDays, bestDays)

                    HabitRepository.RelapseResult.RejectedBeforeStreakStart ->
                        _relapseNote.value =
                            "That relapse time is earlier than this streak's start. " +
                                "Check your device's date and time settings, then try again."

                    HabitRepository.RelapseResult.StoreUnavailable ->
                        _relapseNote.value =
                            "MyExodus can't reach its local database right now, so nothing was logged. " +
                                "Try again in a moment."

                    HabitRepository.RelapseResult.DuplicateIgnored,
                    HabitRepository.RelapseResult.NoActiveStreak,
                    -> Unit
                }
            } finally {
                _relapseInFlight.value = false
            }
        }
    }
}

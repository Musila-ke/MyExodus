package com.example.myexodus.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myexodus.accountability.sos.NudgeSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SOS §8.19 (Step 11): the recipient block switch's state for the Settings row. A null state
 * means no row (no live partner, or the settings document could not be read — the next refresh
 * retries). The toggle flips optimistically so the switch answers the finger; a failed write
 * reverts it and raises the row's error line. `saving` disables the switch so two in-flight
 * writes cannot race.
 */
@HiltViewModel
class NudgeSettingsViewModel @Inject constructor(
    private val repository: NudgeSettingsRepository,
) : ViewModel() {

    data class UiState(
        val partnerName: String,
        val blocked: Boolean,
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
    )

    private val _state = MutableStateFlow<UiState?>(null)
    val state: StateFlow<UiState?> = _state.asStateFlow()

    private var current: NudgeSettingsRepository.BlockSettings? = null

    /** Settings-tab entry and every foreground resume (MainActivity), the same cadence the
     *  sender's flag refresh uses. */
    fun refresh() {
        viewModelScope.launch {
            val settings = repository.load()
            current = settings
            // Finding #19 (2026-10-16): the fresh read predates an in-flight write's commit, so
            // overwriting here would clear `saving` and re-enable the switch mid-write — reopening
            // the exact race `saving` exists to close (a resume can land inside the write's
            // network round trip). The write's own completion lands the committed value; the next
            // resume re-reads. Current-on-null is kept: the next write's target base stays fresh.
            if (_state.value?.saving == true) return@launch
            _state.value = settings?.let { UiState(it.partnerName, it.blocked) }
        }
    }

    fun setBlocked(blocked: Boolean) {
        val target = current ?: return
        val shown = _state.value ?: return
        if (shown.saving) return
        _state.value = shown.copy(blocked = blocked, saving = true, saveFailed = false)
        viewModelScope.launch {
            val committed = repository.setBlocked(target, blocked)
            if (committed) current = target.copy(blocked = blocked)
            _state.update {
                it?.copy(
                    blocked = if (committed) blocked else target.blocked,
                    saving = false,
                    saveFailed = !committed,
                )
            }
        }
    }
}

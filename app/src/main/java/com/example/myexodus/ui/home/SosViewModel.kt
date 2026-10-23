package com.example.myexodus.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myexodus.accountability.sos.NudgeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * SOS plan §8.15 (table A1, Step 8): Home's Reach out button state machine. Owns the one
 * UUIDv4 nudgeId per attempt, the retry-stable `(nudgeId, envelope)` pair behind
 * [SosSendState.CouldNotConfirm], and the 5-second auto-returns. Taps are ignored while a
 * send is in flight; nothing polls and nothing retries in the background — the next tap is
 * the retry (FR: no automatic background retry).
 */
@HiltViewModel
class SosViewModel @Inject constructor(
    private val repository: NudgeRepository,
) : ViewModel() {

    sealed interface SosSendState {
        data object Idle : SosSendState
        data object Sending : SosSendState

        /** Accepted durably (§9 FR-9); returns to [Idle] after 5 s. */
        data object Sent : SosSendState
        data class RateLimited(
            val reason: NudgeRepository.RateLimitReason,
            val retryAfterSeconds: Long,
        ) : SosSendState

        /** FR-3: no validated network at tap time — no backend call was made. */
        data object NotSentOffline : SosSendState

        /**
         * Transport-ambiguous (§8.15): the same `(nudgeId, envelope)` is retained and the
         * next tap replays it. The only state that does not auto-return.
         */
        data object CouldNotConfirm : SosSendState

        /** FR-22: feature disabled, or the server refused (auth, pair, input). */
        data object Unavailable : SosSendState
    }

    private val _state = MutableStateFlow<SosSendState>(SosSendState.Idle)
    val state: StateFlow<SosSendState> = _state.asStateFlow()

    /** The retry-stable identity of the in-flight logical send (§6.1 step 8); null when a
     *  fresh attempt would start from scratch. */
    private var pending: NudgeRepository.PendingNudge? = null
    private var sendJob: Job? = null
    private var returnJob: Job? = null

    /**
     * The single tap path (FAB rev: the dialog's Send — confirmation precedes this call).
     * [message] is the dialog's edited text and [targetPairId] the explicitly chosen recipient
     * (owner ruling 2026-10-23); null recipients keep the §19.5 default. A retry ignores both
     * and replays the retained envelope verbatim — its recipient is already sealed in it.
     */
    fun onTap(message: String? = null, targetPairId: String? = null) {
        val current = _state.value
        val retained = pending
        val allowed = current == SosSendState.Idle ||
            (current == SosSendState.CouldNotConfirm && retained != null)
        if (!allowed) return
        returnJob?.cancel()
        _state.value = SosSendState.Sending
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            val result = if (retained != null) {
                repository.resend(retained)
            } else {
                repository.sendNew(UUID.randomUUID().toString(), message, targetPairId)
            }
            apply(result)
        }
    }

    private fun apply(result: NudgeRepository.SendResult) {
        when (result) {
            NudgeRepository.SendResult.Accepted -> {
                pending = null
                showTemporarily(SosSendState.Sent)
            }
            is NudgeRepository.SendResult.RateLimited -> {
                pending = null
                showTemporarily(SosSendState.RateLimited(result.reason, result.retryAfterSeconds))
            }
            NudgeRepository.SendResult.Unavailable -> {
                pending = null
                showTemporarily(SosSendState.Unavailable)
            }
            // A fresh attempt retains nothing; a retry keeps its pending pair, so the next
            // tap still replays the same logical request once a network exists.
            NudgeRepository.SendResult.NotSentOffline -> showTemporarily(SosSendState.NotSentOffline)
            is NudgeRepository.SendResult.CouldNotConfirm -> {
                pending = result.pending
                _state.value = SosSendState.CouldNotConfirm
            }
        }
    }

    private fun showTemporarily(state: SosSendState) {
        _state.value = state
        returnJob?.cancel()
        returnJob = viewModelScope.launch {
            delay(RESULT_DISPLAY_MILLIS)
            _state.value = SosSendState.Idle
        }
    }

    companion object {
        /** §8.15: `Sent`/rate-limited/offline/unavailable each return to Idle after 5 s. */
        const val RESULT_DISPLAY_MILLIS = 5_000L

        /** §8.15 rate-limit copy: whole minutes, never "0 min" for a sub-minute wait. */
        fun cooldownMinutes(retryAfterSeconds: Long): Int =
            ((retryAfterSeconds + 59) / 60).toInt().coerceAtLeast(1)
    }
}

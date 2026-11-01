package com.example.myexodus.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myexodus.accountability.sos.NudgeInboxRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * SOS plan Step 9 (§8.16): the recipient card's lifecycle. The listener follows the Home
 * surface — [start] while Home is the visible tab, [stop] when it leaves — so the §6.9
 * query runs only where its card can render. A first attach can race session bootstrap
 * (`inbox()` then emits null and completes) and a listen can fail (permission denied, auth);
 * both self-heal, so while Home stays visible the flow is re-attached instead of waiting for
 * the next Home entry — a partner's reach-out must not sit invisible for the whole visit.
 */
@HiltViewModel
class NudgeInboxViewModel @Inject constructor(
    private val repository: NudgeInboxRepository,
) : ViewModel() {

    private val _card = MutableStateFlow<NudgeInboxRepository.NudgeCard?>(null)
    val card: StateFlow<NudgeInboxRepository.NudgeCard?> = _card.asStateFlow()

    private var listener: Job? = null

    fun start() {
        if (listener?.isActive == true) return
        listener = viewModelScope.launch {
            repeat(REATTACH_ATTEMPTS) { attempt ->
                repository.inbox()
                    .catch { error ->
                        Log.d(TAG, "inbox attempt ${attempt + 1}: listen error", error)
                        _card.value = null
                    }
                    .collect { _card.value = it }
                // Reached only when the flow ended (prerequisites missing or listen failed),
                // never while a listener is live; bounded so a broken path stops retrying.
                if (!isActive) return@launch
                Log.d(TAG, "inbox attempt ${attempt + 1}: no listener attached, retrying")
                delay(REATTACH_DELAY_MS)
            }
        }
    }

    fun stop() {
        listener?.cancel()
        listener = null
    }

    private companion object {
        const val TAG = "MyExodusNudgeInbox"
        const val REATTACH_ATTEMPTS = 20
        const val REATTACH_DELAY_MS = 3_000L
    }

    /** §6.9: the card's X acknowledges everything fetched (owner ruling 2026-11-01 — the
     *  reach-out is a one-way signal, so there is no reply action). The card clears through
     *  the listener when the batch lands — never optimistically. */
    fun acknowledge() {
        val current = _card.value ?: return
        viewModelScope.launch { repository.acknowledge(current) }
    }
}

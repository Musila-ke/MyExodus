package com.example.myexodus.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myexodus.accountability.sos.NudgeInboxRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * SOS plan Step 9 (§8.16): the recipient card's lifecycle. The listener follows the Home
 * surface — [start] while Home is the visible tab, [stop] when it leaves — so the §6.9
 * query runs only where its card can render. A failed listen (permission denied, auth)
 * degrades to no card; the next Home entry re-attaches and re-queries.
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
            repository.inbox()
                .catch { _card.value = null }
                .collect { _card.value = it }
        }
    }

    fun stop() {
        listener?.cancel()
        listener = null
    }

    /** Both card actions (§6.9): "I'm here" and X acknowledge everything fetched. The card
     *  clears through the listener when the batch lands — never optimistically. */
    fun acknowledge() {
        val current = _card.value ?: return
        viewModelScope.launch { repository.acknowledge(current) }
    }
}

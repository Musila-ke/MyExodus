package com.example.myexodus.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myexodus.insights.InsightsRepository
import com.example.myexodus.insights.InsightsSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Loads the Insights read model on demand — when the spoke opens, never at app start. The
 * store read runs on IO (SQLCipher open/reads can block), and only the latest pass may write
 * state: a second open while the first is still reading reuses it instead of racing.
 */
@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val repository: InsightsRepository,
) : ViewModel() {

    sealed interface State {
        /** Not requested yet — the screen shows its skeleton until [refresh] lands. */
        data object Idle : State

        data object Loading : State

        data class Ready(val snapshot: InsightsSnapshot) : State

        /** The encrypted store refused to open; the page states that instead of zeros. */
        data object Unavailable : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    fun refresh() {
        if (job?.isActive == true) return
        job = viewModelScope.launch(Dispatchers.IO) {
            _state.value = State.Loading
            val next = try {
                repository.load()?.let { State.Ready(it) } ?: State.Unavailable
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Unavailable
            }
            _state.value = next
        }
    }
}

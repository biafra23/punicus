package eth.serotine.ui.dapprequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.usecase.ObserveDappMessages
import eth.serotine.ui.connections.ConnectionsViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The requests to sign waiting, oldest first, as their routes name them (`topic/id`): what the app
 * opens screen D4 for as each arrives, and which it has opened in this run, so that one left
 * unanswered is not opened again (it stays in Connected dApps). Scoped to the host, as the navigation
 * it drives is; a configuration change keeps it.
 */
class DappRequestsWaitingViewModel(observeDappMessages: ObserveDappMessages) : ViewModel() {

    val waiting: StateFlow<List<String>> = observeDappMessages()
        .map { reviews -> reviews.map { ConnectionsViewModel.requestKey(it.request.id) } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val shown = mutableSetOf<String>()

    /** Whether the request [key] has been opened in this run. */
    fun wasOpened(key: String): Boolean = key in shown

    /** The request [key] is being opened. */
    fun opened(key: String) {
        shown += key
    }
}

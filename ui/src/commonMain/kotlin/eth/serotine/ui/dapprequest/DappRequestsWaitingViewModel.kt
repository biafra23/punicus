package eth.serotine.ui.dapprequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.usecase.DappSignContent
import eth.serotine.domain.usecase.ObserveDappMessages
import eth.serotine.ui.connections.ConnectionsViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A request waiting, by [key] (`topic/id`); a [transaction] opens screen D6, anything else D4. */
data class DappWaiting(val key: String, val transaction: Boolean)

/**
 * The requests waiting, oldest first, by their keys (`topic/id`) and kinds: what the app opens their
 * screen for as each arrives (D6 for a transaction, D4 otherwise), and which it has opened in this
 * run, so that one left unanswered is not opened again (it stays in Connected dApps). Scoped to the host, as the
 * navigation it drives is; a configuration change keeps it.
 */
class DappRequestsWaitingViewModel(observeDappMessages: ObserveDappMessages) : ViewModel() {

    val waiting: StateFlow<List<DappWaiting>> = observeDappMessages()
        .map { reviews ->
            reviews.map {
                DappWaiting(ConnectionsViewModel.requestKey(it.request.id), it.content is DappSignContent.Transaction)
            }
        }
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

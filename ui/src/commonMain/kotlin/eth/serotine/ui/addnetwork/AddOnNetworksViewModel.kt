package eth.serotine.ui.addnetwork

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.usecase.AddOnNetworks
import eth.serotine.domain.usecase.NetworksAdd
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.ui.components.chosenNetworks
import eth.serotine.ui.components.networkChoices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Add on another network dialog's ViewModel (docs/networks.md, N6): the networks the
 * externally owned [account]'s address is not on yet in this wallet, each to tick, a network
 * that is off to be turned on first ([turnOn]), and Add ([add]), which adds it on the networks
 * ticked through [AddOnNetworks]. Nothing is signed and no password is asked.
 */
class AddOnNetworksViewModel(
    private val account: AccountId,
    observeAccounts: ObserveAccounts,
    observeEnabledNetworks: ObserveEnabledNetworks,
    private val setNetworkEnabled: SetNetworkEnabled,
    private val addOnNetworks: AddOnNetworks,
) : ViewModel() {

    /** What the user ticked, how the last Add went, and whether one is running. */
    private data class Form(
        val chosen: Set<Network> = emptySet(),
        val adding: Boolean = false,
        val error: String? = null,
        val done: Boolean = false,
    )

    private val form = MutableStateFlow(Form())

    val uiState: StateFlow<AddOnNetworksUiState> =
        combine(observeAccounts(), observeEnabledNetworks(), form) { list, on, form ->
            if (form.done) return@combine AddOnNetworksUiState.Done
            val accounts = when (list) {
                is AccountList.Unreadable -> return@combine AddOnNetworksUiState.Unavailable(LIST_UNAVAILABLE)
                is AccountList.Loaded -> list.accounts
            }
            val own = accounts.firstOrNull { it.id == account } ?: return@combine AddOnNetworksUiState.Gone
            if (own.kind != AccountKind.EOA) return@combine AddOnNetworksUiState.Unavailable(NOT_AN_EOA)
            val present = accounts.filter { it.address == account.address }.map { it.network }.toSet()
            AddOnNetworksUiState.Choose(
                label = own.label,
                choices = networkChoices(
                    on.toSet(),
                    form.chosen,
                    Network.entries.filter { it !in present },
                    withLines = false,
                ),
                adding = form.adding,
                error = form.error,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            AddOnNetworksUiState.Loading,
        )

    /** Ticks the network of [chainId], or unticks it; a network that is off is turned on first. */
    fun toggle(chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        form.update { it.copy(chosen = if (network in it.chosen) it.chosen - network else it.chosen + network) }
    }

    /** Turns the network of [chainId] on, and ticks it: the way to choose one that is off. */
    fun turnOn(chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        viewModelScope.launch {
            if (setNetworkEnabled(network, on = true)) form.update { it.copy(chosen = it.chosen + network) }
        }
    }

    /** Adds the account on the networks ticked, once. */
    fun add() {
        val choose = uiState.value as? AddOnNetworksUiState.Choose ?: return
        if (!choose.canAdd) return
        val networks = choose.choices.chosenNetworks()
        form.update { it.copy(adding = true, error = null) }
        viewModelScope.launch {
            // The add first: an update's transform may run more than once, and must not add again.
            val result = addOnNetworks(account, networks)
            form.update { form ->
                when (result) {
                    is NetworksAdd.Added -> form.copy(adding = false, done = true)
                    is NetworksAdd.NotSaved -> form.copy(
                        adding = false,
                        chosen = form.chosen - result.networks.toSet(),
                        error = NOT_SAVED,
                    )
                    // The state says these itself, from the list.
                    NetworksAdd.NotFound, NetworksAdd.NotAnEoa -> form.copy(adding = false)
                }
            }
        }
    }

    private companion object {
        const val LIST_UNAVAILABLE = "Your account list cannot be read right now. Nothing was added."
        const val NOT_AN_EOA = "A Safe or another contract exists only on the network it was deployed on."
        const val NOT_SAVED = "The account could not be added: your account list cannot be saved right now."
    }
}

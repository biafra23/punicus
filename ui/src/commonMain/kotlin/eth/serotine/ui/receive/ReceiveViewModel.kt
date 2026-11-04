package eth.serotine.ui.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NetworkLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The Receive screen's ViewModel: the [account], to receive to on its network, which works whether
 * that network is on or not ([ObserveEnabledNetworks]): what is sent arrives either way.
 */
class ReceiveViewModel(
    account: AccountId,
    observeAccount: ObserveAccount,
    observeEnabledNetworks: ObserveEnabledNetworks,
) : ViewModel() {

    private val network = account.network

    val uiState: StateFlow<ReceiveUiState> = combine(observeAccount(account), observeEnabledNetworks()) { lookup, on ->
        when (lookup) {
            is AccountLookup.Found -> ReceiveUiState.Ready(
                label = lookup.account.label,
                address = Eip55.format(lookup.account.address),
                network = NetworkLabel.of(network),
                notice = when (lookup.account.kind) {
                    // A Safe is a contract: at the same address on another network there may
                    // be nothing, or someone else's.
                    AccountKind.SAFE -> "This Safe exists only on ${network.displayName}"
                    AccountKind.EOA -> "Only send ${network.displayName} assets to this address"
                },
                // A key's address is the same on every chain, but each is an account of its own.
                note = if (lookup.account.kind == AccountKind.EOA && !network.testnet) {
                    "The same address works on the other networks once the account is added there."
                } else {
                    null
                },
                networkOff = if (network in on) {
                    null
                } else {
                    "${network.displayName} is off in this wallet: what is sent here arrives, but is shown " +
                        "only once you turn it on."
                },
            )
            AccountLookup.NotInList -> ReceiveUiState.Gone
            AccountLookup.ListUnavailable ->
                ReceiveUiState.Unavailable("Your account list cannot be read right now. Serotine keeps trying.")
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        ReceiveUiState.Loading,
    )
}

package eth.serotine.ui.removeaccount

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.AccountRemoval
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.RemoveAccount
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.spokenList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The remove dialog's ViewModel: asks about the [account], saying whether its key goes with it or
 * stays for the same address held here on other networks ([ObserveAccounts]), then removes it.
 */
class RemoveAccountViewModel(
    private val account: AccountId,
    observeAccount: ObserveAccount,
    private val observeAccounts: ObserveAccounts,
    private val removeAccount: RemoveAccount,
) : ViewModel() {
    /** The account this screen is about: its address, on its network. */
    private val address: Address = account.address

    private val state = MutableStateFlow<RemoveAccountUiState>(RemoveAccountUiState.Loading)
    val uiState: StateFlow<RemoveAccountUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            state.value = when (val lookup = observeAccount(account).first()) {
                is AccountLookup.Found -> RemoveAccountUiState.Confirm(
                    label = lookup.account.label,
                    address = Eip55.format(lookup.account.address),
                    network = account.network.displayName,
                    keyed = lookup.account.custody == AccountCustody.InKeyStore,
                    keyStaysFor = keySharers().takeIf { lookup.account.custody == AccountCustody.InKeyStore },
                )
                AccountLookup.NotInList -> RemoveAccountUiState.Gone
                AccountLookup.ListUnavailable ->
                    RemoveAccountUiState.Notice("Your account list cannot be read right now. Nothing was removed.")
            }
        }
    }

    /**
     * The networks on which the same address is held here too, whose accounts keep its key
     * ("Ethereum and Gnosis Chain"), or null when there are none and the key goes.
     */
    private suspend fun keySharers(): String? {
        val list = observeAccounts().first() as? AccountList.Loaded ?: return null
        val others = list.accounts
            .filter { it.address == address && it.network != account.network }
            .filter { it.custody == AccountCustody.InKeyStore }
            .map { it.network }
            .distinct()
            .sorted()
            .map { it.displayName }
        return others.takeIf { it.isNotEmpty() }?.let(::spokenList)
    }

    /** Removes the account, once. */
    fun remove() {
        val confirm = state.value as? RemoveAccountUiState.Confirm ?: return
        if (confirm.removing) return
        state.value = confirm.copy(removing = true)
        viewModelScope.launch {
            state.value = when (removeAccount(account)) {
                AccountRemoval.Removed, AccountRemoval.RemovedKeyStays -> RemoveAccountUiState.Done
                AccountRemoval.NotFound -> RemoveAccountUiState.Gone
                AccountRemoval.RemovedKeyKept -> RemoveAccountUiState.Notice(
                    "${confirm.label} is removed, but its key could not be deleted from this device, so " +
                        "the wallet password stays the one you set. Importing its recovery phrase again " +
                        "brings the account back.",
                )
                AccountRemoval.NotSaved ->
                    RemoveAccountUiState.Notice("${confirm.label} could not be removed. Nothing has changed.")
            }
        }
    }
}

package eth.serotine.ui.renameaccount

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.AccountRename
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.RenameAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The rename dialog's ViewModel: the name of the account under [address], edited and saved. */
class RenameAccountViewModel(
    private val account: AccountId,
    observeAccount: ObserveAccount,
    private val renameAccount: RenameAccount,
) : ViewModel() {
    /** The account this screen is about: its address, on its network. */
    private val address: Address = account.address


    private val state = MutableStateFlow<RenameAccountUiState>(RenameAccountUiState.Loading)
    val uiState: StateFlow<RenameAccountUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            state.value = when (val lookup = observeAccount(account).first()) {
                is AccountLookup.Found -> RenameAccountUiState.Editing(lookup.account.label)
                AccountLookup.NotInList -> RenameAccountUiState.Gone
                AccountLookup.ListUnavailable -> RenameAccountUiState.Unavailable(LIST_UNAVAILABLE)
            }
        }
    }

    /** The user typed: the field shows [name], and the last error goes. */
    fun onNameChange(name: String) = state.update { current ->
        if (current is RenameAccountUiState.Editing && !current.saving) {
            current.copy(name = name, error = null)
        } else {
            current
        }
    }

    /** Saves the typed name; one save at a time. */
    fun save() {
        val editing = state.value as? RenameAccountUiState.Editing ?: return
        if (editing.saving) return
        state.value = editing.copy(saving = true, error = null)
        viewModelScope.launch {
            state.value = when (renameAccount(account, editing.name)) {
                AccountRename.Renamed -> RenameAccountUiState.Done
                AccountRename.NotFound -> RenameAccountUiState.Gone
                AccountRename.Blank -> editing.copy(error = "Enter a name")
                AccountRename.TooLong ->
                    editing.copy(error = "Use at most ${RenameAccount.MAX_LABEL_LENGTH} characters")
                AccountRename.NotSaved -> editing.copy(error = "The name could not be saved. Try again.")
            }
        }
    }

    private companion object {
        const val LIST_UNAVAILABLE = "Your account list cannot be read right now. Serotine keeps trying."
    }
}

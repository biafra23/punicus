package eth.serotine.ui.deriveaccount

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.usecase.AccountDerivation
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.DeriveAccount
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.STEPPED_DOWN_TEXT
import eth.serotine.ui.components.notEnoughMemoryText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * New account from the same phrase's ViewModel: reads the [account], an externally owned account
 * whose key is on this device, takes the wallet password, and adds the next account of its
 * phrase through [DeriveAccount]. The password is dropped once used and whenever the screen is
 * not shown ([onHidden]), as on the other screens that take it (CLAUDE.md, *Trust*).
 */
class DeriveAccountViewModel(
    private val account: AccountId,
    observeAccount: ObserveAccount,
    private val deriveAccount: DeriveAccount,
) : ViewModel() {

    private val state = MutableStateFlow<DeriveAccountUiState>(DeriveAccountUiState.Loading)
    val uiState: StateFlow<DeriveAccountUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            state.value = when (val lookup = observeAccount(account).first()) {
                is AccountLookup.Found -> {
                    val found = lookup.account
                    when {
                        found.kind != AccountKind.EOA -> DeriveAccountUiState.Unavailable(NOT_AN_EOA)
                        found.custody != AccountCustody.InKeyStore ->
                            DeriveAccountUiState.Unavailable(noKeyReason(found.label))
                        else -> DeriveAccountUiState.Ask(
                            label = found.label,
                            address = Eip55.format(found.address),
                            network = account.network.displayName,
                        )
                    }
                }
                AccountLookup.NotInList -> DeriveAccountUiState.Gone
                AccountLookup.ListUnavailable -> DeriveAccountUiState.Unavailable(LIST_UNREADABLE)
            }
        }
    }

    /** The password field changed. */
    fun onPasswordChange(text: String) {
        state.update { current ->
            if (current is DeriveAccountUiState.Ask && !current.deriving) {
                current.copy(password = WalletPassword(text))
            } else {
                current
            }
        }
    }

    /** The screen is no longer shown: the password typed goes. */
    fun onHidden() {
        state.update { current ->
            if (current is DeriveAccountUiState.Ask && !current.deriving) {
                current.copy(password = WalletPassword.EMPTY)
            } else {
                current
            }
        }
    }

    /** Adds the next account of the phrase, once. */
    fun derive() {
        val ask = state.value as? DeriveAccountUiState.Ask ?: return
        if (!ask.canDerive) return
        val password = ask.password
        // The password leaves the state as the derivation starts: it is held by this call alone.
        val asking = ask.copy(password = WalletPassword.EMPTY, deriving = true, error = null)
        val retry = asking.copy(deriving = false)
        val label = asking.label
        state.value = asking
        viewModelScope.launch {
            val derived = deriveAccount(account, password)
            state.value = when (derived) {
                is AccountDerivation.Added -> DeriveAccountUiState.Added(
                    label = derived.account.label,
                    address = Eip55.format(derived.account.address),
                    network = derived.account.network.displayName,
                    number = derived.index + 1,
                    note = STEPPED_DOWN_TEXT.takeIf { derived.steppedDown },
                )
                AccountDerivation.NotFound -> DeriveAccountUiState.Gone
                AccountDerivation.NoKey -> DeriveAccountUiState.Unavailable(noKeyReason(label))
                AccountDerivation.ListUnreadable -> DeriveAccountUiState.Unavailable(LIST_UNREADABLE)
                AccountDerivation.WrongPassword -> retry.copy(error = WRONG_PASSWORD)
                AccountDerivation.Unavailable -> retry.copy(error = cannotOpen(label))
                AccountDerivation.KeyNotSaved -> retry.copy(error = KEY_NOT_SAVED)
                is AccountDerivation.NotEnoughMemory ->
                    retry.copy(error = notEnoughMemoryText(derived.step, NOTHING_ADDED))
                AccountDerivation.ListNotSaved -> retry.copy(error = LIST_NOT_SAVED)
            }
        }
    }

    private companion object {
        const val NOTHING_ADDED = "Nothing was added."
        const val NOT_AN_EOA = "A Safe or another contract has no recovery phrase to add accounts from."
        const val LIST_UNREADABLE = "Your account list cannot be read right now. Nothing was added."
        const val WRONG_PASSWORD = "That is not the wallet password. $NOTHING_ADDED"
        const val KEY_NOT_SAVED = "The new account's key could not be stored on this device. $NOTHING_ADDED"
        const val LIST_NOT_SAVED = "The new account's key is stored, but your account list cannot be saved right " +
            "now, so it is not listed yet. Try again to add it."

        fun noKeyReason(label: String) = "The key of $label is not on this device, so no account of its recovery " +
            "phrase can be added from it. Import the phrase to hold its key here first."

        fun cannotOpen(label: String) =
            "The key store cannot open the key of $label on this device. $NOTHING_ADDED"
    }
}

package eth.serotine.ui.deriveaccount

import eth.serotine.domain.model.WalletPassword

/** Everything New account from the same phrase renders. */
sealed interface DeriveAccountUiState {

    /** The account has not been read yet. */
    data object Loading : DeriveAccountUiState

    /**
     * The wallet password asked, to add the next account of the phrase the account [label] at the
     * EIP-55 [address] on [network] comes from: [password] as typed, held only while the field is
     * shown; [deriving] while the account is added, and [error] why the last try added nothing.
     */
    data class Ask(
        val label: String,
        val address: String,
        val network: String,
        val password: WalletPassword = WalletPassword.EMPTY,
        val deriving: Boolean = false,
        val error: String? = null,
    ) : DeriveAccountUiState {
        /** Whether Add account can be done: a password typed, and nothing being added. */
        val canDerive: Boolean
            get() = !deriving && password.text.isNotEmpty()
    }

    /**
     * The new account is in the wallet: named [label], at the EIP-55 [address] on [network], account
     * [number] of its phrase (1 the first); [note] what else the user must know (a lighter password cost).
     */
    data class Added(
        val label: String,
        val address: String,
        val network: String,
        val number: Int,
        val note: String? = null,
    ) : DeriveAccountUiState

    /** No account can be added from this one, for the reason [message] gives, display-ready. */
    data class Unavailable(val message: String) : DeriveAccountUiState

    /** The account is not in the wallet (any more): the screen leaves. */
    data object Gone : DeriveAccountUiState
}

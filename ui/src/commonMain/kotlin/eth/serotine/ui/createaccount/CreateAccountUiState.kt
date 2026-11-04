package eth.serotine.ui.createaccount

import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.components.NO_NETWORK_TICKED
import eth.serotine.ui.components.NetworkChoice
import eth.serotine.ui.components.PasswordStep

/**
 * Everything Create account renders (docs/wallet-ux.md, screen 12): a seed UiState of the
 * mnemonic exception (CLAUDE.md, *Trust*), the backup words shown at creation. The states that
 * hold the [Mnemonic] and the [WalletPassword] exist only while the screen is shown: its
 * ViewModel replaces them with [Restart] as soon as it is not.
 */
sealed interface CreateAccountUiState {

    /** The key adapter is making the phrase. */
    data object Preparing : CreateAccountUiState

    /** The new [phrase], to be written down: shown on this screen alone, until it is stored. */
    data class Showing(val phrase: Mnemonic) : CreateAccountUiState {
        /** The words, numbered on screen in this order. */
        val words: List<String> = phrase.words
    }

    /**
     * Three of the words asked back: the 1-based [positions] and what is [typed] for each, a
     * [Mnemonic] too since it is a word of the phrase;
     * [wrong] once a check did not match what was written down.
     */
    data class Checking(
        val phrase: Mnemonic,
        val positions: List<Int>,
        val typed: List<Mnemonic>,
        val wrong: Boolean = false,
    ) : CreateAccountUiState {
        /** Every word is typed. */
        val canCheck: Boolean
            get() = typed.all { it.words.isNotEmpty() }
    }

    /**
     * The wallet password for the account's key: set and repeated on the first key ([step]
     * [PasswordStep.New]), the wallet's own on a later one; and the [networks] to add the account
     * to, none ticked until the user ticks one (docs/networks.md, N8). [creating] while the key is
     * stored; [error] and [passwordError] why the last attempt was refused.
     */
    data class Securing(
        val phrase: Mnemonic,
        val step: PasswordStep,
        val password: WalletPassword = WalletPassword.EMPTY,
        val repeat: WalletPassword = WalletPassword.EMPTY,
        val creating: Boolean = false,
        val error: String? = null,
        val passwordError: String? = null,
        val networks: List<NetworkChoice> = emptyList(),
    ) : CreateAccountUiState {

        /** What is wrong with the password, display-ready, or null. */
        val passwordHint: String?
            get() = passwordError ?: when {
                step !is PasswordStep.New -> null
                password.text.isNotEmpty() && !password.isLongEnough ->
                    "At least ${WalletPassword.MIN_LENGTH} characters"
                repeat.text.isNotEmpty() && repeat.text != password.text -> "The two passwords differ"
                else -> null
            }

        /** A usable password, while no attempt runs. */
        val passwordReady: Boolean
            get() = !creating && when (step) {
                PasswordStep.Checking, PasswordStep.Unavailable -> false
                is PasswordStep.New -> password.isLongEnough && repeat.text == password.text
                is PasswordStep.Existing -> password.text.isNotEmpty()
            }

        /**
         * Why Create cannot be done yet with a usable password: no network ticked, which Create,
         * greyed out, says on a tap (owner ruling, 2026-10-18, #55); null when one is.
         */
        val networkReason: String?
            get() = NO_NETWORK_TICKED.takeIf { networks.none { it.chosen && it.on } }

        /** A usable password and a network ticked, while no attempt runs. */
        val canCreate: Boolean
            get() = passwordReady && networkReason == null
    }

    /** The screen was left while the key was being stored: that finishes, without the words or the password. */
    data object Finishing : CreateAccountUiState

    /**
     * The account named [label] was created; the words and the password are gone. The screen
     * closes, unless there is a [note] to read first (its key protected at a lighter cost than
     * this device's own), which stays until the user is done.
     */
    data class Created(val label: String, val note: String? = null) : CreateAccountUiState

    /**
     * The phrase is gone, for the reason [message] gives (the screen was left; an attempt
     * failed): nothing of it is kept. Starting again makes a new one.
     */
    data class Restart(val message: String) : CreateAccountUiState

    /**
     * The screen was left while the key was being stored, and the key is stored but the account
     * list was not saved: the words are gone from the state, and importing the written-down
     * phrase finishes it. A new phrase would leave that key behind under the wallet password.
     */
    data object NotListed : CreateAccountUiState

    /** The key store does not work on this device: no account can be created. */
    data object Unavailable : CreateAccountUiState
}

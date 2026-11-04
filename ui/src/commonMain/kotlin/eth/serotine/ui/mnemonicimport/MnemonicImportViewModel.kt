package eth.serotine.ui.mnemonicimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ImportMnemonicAccount
import eth.serotine.domain.usecase.MnemonicImport
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.ui.components.PasswordStep
import eth.serotine.ui.components.STEPPED_DOWN_TEXT
import eth.serotine.ui.components.chosenNetworks
import eth.serotine.ui.components.networkChoices
import eth.serotine.ui.components.notEnoughMemoryText
import eth.serotine.ui.components.protectionText
import eth.serotine.ui.mnemonicimport.MnemonicImportUiState.Entering
import eth.serotine.ui.mnemonicimport.MnemonicImportUiState.Imported
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The seed-phrase screen's ViewModel: holds the words and the wallet password as they are
 * typed, with the networks to add the account to (docs/networks.md, N9; one that is off turned on
 * here first), and hands them to [ImportMnemonicAccount]. One of the ViewModels the [Mnemonic] and the
 * [WalletPassword] pass through (CLAUDE.md, *Trust*): it never logs, saves or caches them,
 * and drops them from its state as soon as the screen is not shown — hidden ([onHidden]) or
 * gone from the back stack ([onCleared]).
 */
class MnemonicImportViewModel(
    private val importMnemonicAccount: ImportMnemonicAccount,
    private val describeKeyStore: DescribeKeyStore,
    observeEnabledNetworks: ObserveEnabledNetworks,
    private val setNetworkEnabled: SetNetworkEnabled,
) : ViewModel() {

    /** The networks on in this wallet, as last read; null before, when no network is listed yet. */
    private var on: Set<Network>? = null

    /** The networks ticked: none until the user ticks one (N9). */
    private var chosen: Set<Network> = emptySet()

    /** The key store's last answer, kept for the blank state [onHidden] and [onCleared] leave. */
    private var step: PasswordStep = PasswordStep.Checking

    private val state = MutableStateFlow<MnemonicImportUiState>(Entering(Mnemonic.EMPTY))

    val uiState: StateFlow<MnemonicImportUiState> = state.asStateFlow()

    init {
        describe()
        viewModelScope.launch {
            observeEnabledNetworks().collect { networks ->
                on = networks.toSet()
                refreshNetworks()
            }
        }
    }

    /** Ticks the network of [chainId], or unticks it; ignored while an attempt runs. */
    fun onNetworkToggle(chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        if ((state.value as? Entering)?.importing != false) return
        chosen = if (network in chosen) chosen - network else chosen + network
        refreshNetworks()
    }

    /** Turns the network of [chainId] on, and ticks it: the way to choose one that is off. */
    fun onTurnOnNetwork(chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        viewModelScope.launch {
            if (setNetworkEnabled(network, on = true)) {
                chosen = chosen + network
                refreshNetworks()
            }
        }
    }

    /** The check boxes, once the networks on are read: none before, rather than every one shown off. */
    private fun choices() = on?.let { networkChoices(it, chosen) }.orEmpty()

    private fun refreshNetworks() {
        state.update { current ->
            if (current is Entering) current.copy(networks = choices()) else current
        }
    }

    /** The words field now reads [text]. Ignored while an attempt runs and after a success. */
    fun onPhraseChange(text: String) = edit { it.copy(phrase = Mnemonic(text)) }

    /** The password field now reads [text]. */
    fun onPasswordChange(text: String) = edit { it.copy(password = WalletPassword(text)) }

    /** The repeat field (shown while setting the password) now reads [text]. */
    fun onRepeatChange(text: String) = edit { it.copy(repeat = WalletPassword(text)) }

    private fun edit(change: (Entering) -> Entering) {
        state.update { current ->
            if (current is Entering && !current.importing) {
                change(current).copy(error = null, passwordError = null)
            } else {
                current
            }
        }
    }

    /**
     * Add the account the typed words control, its seed stored under the password. Ignored
     * unless [Entering.canImport]. An attempt is not abandoned when the screen is hidden:
     * the use case finishes it as one step, and its outcome lands in the state as it is
     * then, which holds no words once [onHidden] has run.
     */
    fun onImport() {
        val current = state.value as? Entering ?: return
        if (!current.canImport) return
        state.value = current.copy(importing = true, error = null, passwordError = null)
        viewModelScope.launch {
            val result = try {
                importMnemonicAccount(current.phrase, current.password, current.networks.chosenNetworks())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Every port on this path promises not to throw; this is the backstop for a
                // broken promise. The exception's text is not shown: nothing from the key
                // path reaches the screen.
                MnemonicImport.Unavailable
            }
            state.update { now -> if (now is Entering) now.copy(importing = false).after(result) else now }
            // Whatever happened, the key store may have changed: a password set, a key stored.
            if (result !is MnemonicImport.Added) describe()
        }
    }

    /**
     * The screen stopped being shown: the app went to the background or its window was
     * hidden. The seed UiState exists only while its screen is shown (CLAUDE.md, *Trust*), so
     * the words and the password go. An attempt still running goes on without them.
     */
    fun onHidden() {
        state.update { current ->
            if (current is Entering) {
                Entering(Mnemonic.EMPTY, step = step, importing = current.importing, networks = current.networks)
            } else {
                current
            }
        }
    }

    override fun onCleared() {
        // The screen is gone, and the words and the password go with it.
        state.value = Entering(Mnemonic.EMPTY, step = step, networks = choices())
    }

    private fun describe() {
        viewModelScope.launch {
            val description = try {
                describeKeyStore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KeyStoreDescription.Unavailable // the port promises not to throw; the backstop
            }
            step = when (description) {
                is KeyStoreDescription.Ready -> description.protectionText.let { protection ->
                    if (description.passwordSet) PasswordStep.Existing(protection) else PasswordStep.New(protection)
                }
                KeyStoreDescription.Unavailable -> PasswordStep.Unavailable
            }
            state.update { current -> if (current is Entering) current.copy(step = step) else current }
        }
    }

    private fun Entering.after(result: MnemonicImport): MnemonicImportUiState = when (result) {
        is MnemonicImport.Added -> Imported(result.account.label, STEPPED_DOWN_TEXT.takeIf { result.steppedDown })
        // A watched address whose key is stored now: the import did what was asked.
        is MnemonicImport.KeyAdded -> Imported(result.account.label, STEPPED_DOWN_TEXT.takeIf { result.steppedDown })
        is MnemonicImport.AlreadyAdded -> copy(
            error = "“${result.account.label}” is in the wallet already; its key is stored on this device now." +
                if (result.steppedDown) " $STEPPED_DOWN_TEXT" else "",
        )
        is MnemonicImport.Invalid -> copy(error = result.problem.message)
        MnemonicImport.WrongPassword -> copy(passwordError = "That is not the wallet password.")
        MnemonicImport.PasswordTooShort ->
            copy(passwordError = "A wallet password has at least ${WalletPassword.MIN_LENGTH} characters.")
        // After describe() said the key store works, a failure here is most likely passing
        // (memory for Argon2id, a Keystore hiccup): said so, not as a verdict on the device.
        MnemonicImport.Unavailable -> copy(
            error = "The key store failed. Nothing was added. Try again; if it keeps failing, this device " +
                "cannot store the key.",
        )
        MnemonicImport.KeyNotSaved -> copy(error = "The key could not be stored on this device. Nothing was added.")
        is MnemonicImport.NotEnoughMemory -> copy(error = notEnoughMemoryText(result.step, "Nothing was added."))
        MnemonicImport.ListNotSaved -> copy(
            error = "The key is stored, but the account list could not be saved. Add the same words again " +
                "to finish.",
        )
    }

    private val MnemonicProblem.message: String
        get() = when (this) {
            is MnemonicProblem.WordCount -> "A seed phrase has 12, 15, 18, 21 or 24 words, not $count."
            is MnemonicProblem.UnknownWord -> "Word $position is not in the BIP-39 English word list."
            MnemonicProblem.Checksum ->
                "These words are not a valid seed phrase. Check for a typo or two words in the wrong order."
        }
}

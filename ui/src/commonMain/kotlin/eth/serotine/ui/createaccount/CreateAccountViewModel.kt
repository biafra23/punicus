package eth.serotine.ui.createaccount

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ImportMnemonicAccount
import eth.serotine.domain.usecase.MnemonicImport
import eth.serotine.domain.usecase.NewRecoveryPhrase
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.ui.components.PasswordStep
import eth.serotine.ui.components.STEPPED_DOWN_TEXT
import eth.serotine.ui.components.chosenNetworks
import eth.serotine.ui.components.networkChoices
import eth.serotine.ui.components.notEnoughMemoryText
import eth.serotine.ui.components.protectionText
import eth.serotine.ui.createaccount.CreateAccountUiState.Checking
import eth.serotine.ui.createaccount.CreateAccountUiState.Securing
import eth.serotine.ui.createaccount.CreateAccountUiState.Showing
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Create account's ViewModel (docs/wallet-ux.md, screen 12): a new phrase from the key adapter
 * ([NewRecoveryPhrase]), shown on this screen alone; three of its words asked back; then the
 * wallet password and the networks to add it to (docs/networks.md, N8; one that is off is turned
 * on here first), and the account stored as an import of those words ([ImportMnemonicAccount]). One of the
 * ViewModels the [Mnemonic] and the [WalletPassword] pass through (CLAUDE.md, *Trust*): it never
 * logs, saves or caches them, and drops them as soon as the screen is not shown ([onHidden],
 * [onCleared]). [random] picks the words to check; which ones is no secret.
 */
class CreateAccountViewModel(
    private val newRecoveryPhrase: NewRecoveryPhrase,
    private val importMnemonicAccount: ImportMnemonicAccount,
    private val describeKeyStore: DescribeKeyStore,
    observeEnabledNetworks: ObserveEnabledNetworks,
    private val setNetworkEnabled: SetNetworkEnabled,
    private val random: Random = Random.Default,
) : ViewModel() {

    private val state = MutableStateFlow<CreateAccountUiState>(CreateAccountUiState.Preparing)

    /** The networks on in this wallet, as last read; null before, when no network is listed yet. */
    private var on: Set<Network>? = null

    /** The networks ticked: none until the user ticks one (N8). */
    private var chosen: Set<Network> = emptySet()

    /** Whether the screen is shown: a phrase made while it is not is not kept. */
    private var shown = true

    private var making: Job? = null

    val uiState: StateFlow<CreateAccountUiState> = state.asStateFlow()

    init {
        startOver()
        viewModelScope.launch {
            observeEnabledNetworks().collect { networks ->
                on = networks.toSet()
                refreshNetworks()
            }
        }
    }

    /** Ticks the network of [chainId], or unticks it. */
    fun onNetworkToggle(chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        if (state.value !is Securing || (state.value as Securing).creating) return
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
            if (current is Securing) current.copy(networks = choices()) else current
        }
    }

    /** A new phrase, the one before forgotten; ignored while one is being made. */
    fun startOver() {
        if (making?.isActive == true) return
        shown = true
        chosen = emptySet()
        state.value = CreateAccountUiState.Preparing
        making = viewModelScope.launch {
            val made = try {
                newRecoveryPhrase()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NewMnemonic.Unavailable // the port promises not to throw; the backstop
            }
            state.value = when (made) {
                // Made after the screen was left: not kept, so never shown later as if fresh.
                is NewMnemonic.Created ->
                    if (shown) Showing(made.mnemonic) else CreateAccountUiState.Restart(NEVER_SHOWN)
                NewMnemonic.Unavailable -> CreateAccountUiState.Unavailable
            }
        }
    }

    /** The phrase is written down: three of its words are asked back. */
    fun onWrittenDown() {
        val showing = state.value as? Showing ?: return
        val positions = (1..showing.words.size).shuffled(random).take(CHECKED_WORDS).sorted()
        state.value = Checking(showing.phrase, positions, List(CHECKED_WORDS) { Mnemonic.EMPTY })
    }

    /** Back from the check to the phrase, to look at it again. */
    fun onShowAgain() {
        val checking = state.value as? Checking ?: return
        state.value = Showing(checking.phrase)
    }

    /** The [index]-th checked word now reads [text]. */
    fun onCheckedWordChange(index: Int, text: String) {
        state.update { current ->
            if (current is Checking && index in current.typed.indices) {
                current.copy(typed = current.typed.toMutableList().also { it[index] = Mnemonic(text) }, wrong = false)
            } else {
                current
            }
        }
    }

    /** Compares the typed words with the phrase's; on a match, on to the password. */
    fun onCheck() {
        val checking = state.value as? Checking ?: return
        if (!checking.canCheck) return
        val words = checking.phrase.words
        val matches = checking.positions.zip(checking.typed).all { (position, typed) ->
            typed.words == listOf(words[position - 1])
        }
        if (!matches) {
            state.value = checking.copy(wrong = true)
            return
        }
        state.value = Securing(checking.phrase, PasswordStep.Checking, networks = choices())
        describe()
    }

    /** The password field now reads [text]. */
    fun onPasswordChange(text: String) = edit { it.copy(password = WalletPassword(text)) }

    /** The repeat field (shown while setting the password) now reads [text]. */
    fun onRepeatChange(text: String) = edit { it.copy(repeat = WalletPassword(text)) }

    private fun edit(change: (Securing) -> Securing) {
        state.update { current ->
            if (current is Securing && !current.creating) {
                change(current).copy(error = null, passwordError = null)
            } else {
                current
            }
        }
    }

    /**
     * Stores the account's key under the password and adds the account, as one step the use
     * case finishes even when the screen is left; its outcome lands in the state as it is then.
     */
    fun onCreate() {
        val securing = state.value as? Securing ?: return
        if (!securing.canCreate) return
        state.value = securing.copy(creating = true, error = null, passwordError = null)
        viewModelScope.launch {
            val result = try {
                importMnemonicAccount(securing.phrase, securing.password, securing.networks.chosenNetworks())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MnemonicImport.Unavailable // the port promises not to throw; the backstop
            }
            state.update { now -> if (now is Securing) now.after(result) else outcomeWithoutPhrase(result) }
            // As on the import screen: whatever failed, the key store may have changed (a password set).
            if (result !is MnemonicImport.Added && result !is MnemonicImport.KeyAdded) describe()
        }
    }

    /**
     * The screen stopped being shown. The phrase and the password exist only while it is
     * (CLAUDE.md, *Trust*), so they go, and with them this phrase: it was never stored, and a new
     * one is made if the user goes on. An attempt still running finishes without them.
     */
    fun onHidden() {
        shown = false
        state.update { current ->
            when (current) {
                is Showing, is Checking -> CreateAccountUiState.Restart(HIDDEN)
                // The use case holds what it needs: the state holds neither words nor password.
                is Securing ->
                    if (current.creating) CreateAccountUiState.Finishing else CreateAccountUiState.Restart(HIDDEN)
                else -> current
            }
        }
    }

    override fun onCleared() {
        // The screen is gone, and the words and the password go with it.
        state.value = CreateAccountUiState.Restart(HIDDEN)
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
            val step = when (description) {
                is KeyStoreDescription.Ready -> description.protectionText.let { protection ->
                    if (description.passwordSet) PasswordStep.Existing(protection) else PasswordStep.New(protection)
                }
                KeyStoreDescription.Unavailable -> PasswordStep.Unavailable
            }
            state.update { current -> if (current is Securing) current.copy(step = step) else current }
        }
    }

    private fun Securing.after(result: MnemonicImport): CreateAccountUiState = when (result) {
        is MnemonicImport.Added -> created(result.account.label, result.steppedDown)
        is MnemonicImport.KeyAdded -> created(result.account.label, result.steppedDown)
        // 128 bits of fresh entropy already in the wallet: only a broken generator does that.
        is MnemonicImport.AlreadyAdded -> CreateAccountUiState.Restart(
            "These words are an account the wallet has already. Start again for a new phrase.",
        )
        // The adapter made these words: they are valid unless something is very wrong.
        is MnemonicImport.Invalid -> CreateAccountUiState.Restart("The phrase could not be used. Start again.")
        MnemonicImport.WrongPassword -> copy(creating = false, passwordError = "That is not the wallet password.")
        MnemonicImport.PasswordTooShort -> copy(
            creating = false,
            passwordError = "A wallet password has at least ${WalletPassword.MIN_LENGTH} characters.",
        )
        MnemonicImport.Unavailable -> copy(
            creating = false,
            error = "The key store failed. Nothing was created. Try again; if it keeps failing, this device " +
                "cannot store the key.",
        )
        MnemonicImport.KeyNotSaved ->
            copy(creating = false, error = "The key could not be stored on this device. Nothing was created.")
        is MnemonicImport.NotEnoughMemory ->
            copy(creating = false, error = notEnoughMemoryText(result.step, "Nothing was created."))
        MnemonicImport.ListNotSaved -> copy(
            creating = false,
            error = "The key is stored, but the account list could not be saved. Try again to finish.",
        )
    }

    /** [label] created, with the note that its key was protected at a lighter cost when [steppedDown]. */
    private fun created(label: String, steppedDown: Boolean) =
        CreateAccountUiState.Created(label, STEPPED_DOWN_TEXT.takeIf { steppedDown })

    /** How an attempt that finished after the screen was left went, said without its phrase. */
    private fun outcomeWithoutPhrase(result: MnemonicImport): CreateAccountUiState = when (result) {
        is MnemonicImport.Added -> created(result.account.label, result.steppedDown)
        is MnemonicImport.KeyAdded -> created(result.account.label, result.steppedDown)
        MnemonicImport.ListNotSaved -> CreateAccountUiState.NotListed
        else -> CreateAccountUiState.Restart(
            "The account was not created, and the phrase is gone. Cross out what you wrote of it, then start again.",
        )
    }

    private companion object {
        const val CHECKED_WORDS = 3
        const val HIDDEN = "The phrase was cleared when the screen was left, before it was stored, and it will " +
            "never be used. Cross out every word of it you wrote down, then start again with a new phrase."

        // The screen was left while the phrase was being made: nothing was shown, nothing to cross out.
        const val NEVER_SHOWN = "The screen was left before a phrase was shown. Start again for a new one."
    }
}

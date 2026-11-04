package eth.serotine.ui.createsafe

import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.NetworkChip
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.UnlockStep

/**
 * Everything Create Safe renders (docs/wallet-ux.md, screens 13 to 15): the owners chosen, the
 * threshold under the recovery shapes, then the review of the deployment — the address computed
 * here with its threshold, the version, the owners, the account that pays and the verified quote
 * of what it pays — with the unlock that signs it. Every leaf is display-ready. While the review
 * is shown, [Review.unlock] holds the wallet password being typed, under the screen protections of
 * a screen that takes it, and the ViewModel drops it as soon as the screen is not shown.
 */
sealed interface CreateSafeUiState {

    /** The network's name ("Sepolia"). */
    val network: String

    /** The wallet's accounts have not been read yet. */
    data class Loading(override val network: String) : CreateSafeUiState

    /**
     * No Safe can be created here, for the reason [message] gives: no account's key is held here to
     * pay for the deployment, the account list cannot be read, or no Safe release is pinned. The
     * [networks] offer another network where that is so.
     */
    data class CannotCreate(
        override val network: String,
        val message: String,
        val networks: List<NetworkChip> = emptyList(),
    ) : CreateSafeUiState

    /**
     * Screen 13, 1/3: the Safe's [name] as typed ([nameError] why it cannot be one), and its owners:
     * the wallet's accounts ([accounts], each chosen or not) and the addresses typed and added
     * ([typed], chosen; each can be taken out again). [input] is the address or ENS name being
     * typed, [inputOwner] what it is once it parses as an address or resolves, and [inputNote] what
     * the field says under it — an error when [inputError], with [canRetryLookup] when Try again is
     * shown, greyed out with [retryLookupWaits] while asking by hand changes nothing (the light client
     * still syncing); [canAdd] once it can be added. [deviceKeyNote] says why this device's hardware key
     * is not offered. [summary] counts the owners chosen ("2 owners"), [error] says why they cannot
     * own a Safe, and [canContinue] when they can go on to the threshold. The [networks] come first:
     * the network the Safe is created on (docs/networks.md, N12).
     */
    data class Owners(
        override val network: String,
        val networks: List<NetworkChip> = emptyList(),
        val name: String,
        val accounts: List<OwnerChoice>,
        val typed: List<OwnerChoice>,
        val input: String,
        val summary: String,
        val deviceKeyNote: String,
        val nameError: String? = null,
        val inputOwner: OwnerChoice? = null,
        val inputNote: String? = null,
        val inputError: Boolean = false,
        val inputLabel: String = "Address or ENS name",
        val canRetryLookup: Boolean = false,
        val retryLookupWaits: String? = null,
        val canAdd: Boolean = false,
        val error: String? = null,
        val canContinue: Boolean = false,
    ) : CreateSafeUiState

    /**
     * Screen 14, 2/3: [threshold] of the [owners] ([ownerCount]), changed by the stepper while
     * [canDecrease] and [canIncrease]; [shape] what it means ("2 of 3 owners sign each
     * transaction"), [explanation] how it stands when an owner is lost, [rules] the recovery shapes
     * the wallet creates, and [refusal] why this one is not among them. [canContinue] when it is.
     */
    data class Threshold(
        override val network: String,
        val owners: List<OwnerItem>,
        val threshold: Int,
        val ownerCount: Int,
        val shape: String,
        val explanation: String?,
        val rules: List<String>,
        val canDecrease: Boolean,
        val canIncrease: Boolean,
        val refusal: String? = null,
        val canContinue: Boolean = false,
    ) : CreateSafeUiState

    /**
     * Screen 15, 3/3: the Safe named [name] at [address] (EIP-55, in full: computed here with
     * CREATE2), its [signers] badge ("2/3"), its [version] ("Safe 1.4.1") and [prediction], how the
     * address is known; its [owners] and [shape] ("2 of 3 owners sign each transaction"). [payer]
     * (EIP-55) of [payers], the accounts held here, sends the deployment at the [fee] level; [quote]
     * is its verified quote (null while it is first made, or when it was refused: [error] says why).
     * [unlock] is how it is signed. [quoting] while the quote is made, [signing] while it is signed
     * and broadcast, and [notSent] once it was signed but not broadcast: then the same signed bytes
     * are all that can be sent. Simple mode says no block (docs/simple-ui.md): [simplePrediction] and
     * [simpleError] are [prediction] and [error] in its words, null where they are the same.
     */
    data class Review(
        override val network: String,
        val name: String,
        val address: String,
        val signers: String,
        val version: String,
        val prediction: String,
        val owners: List<OwnerItem>,
        val shape: String,
        val payers: List<AccountChoice>,
        val payer: String,
        val fee: FeeLevel,
        val unlock: UnlockStep,
        val quote: DeploymentQuoteItem? = null,
        val error: String? = null,
        val quoting: Boolean = false,
        val signing: Boolean = false,
        val notSent: NotSentItem? = null,
        val simplePrediction: String? = null,
        val simpleError: String? = null,
    ) : CreateSafeUiState {

        /** A quote stands, something is typed, and nothing else runs or has run: the password may sign. */
        val canSign: Boolean
            get() = idle && quote != null && unlock.password.text.isNotEmpty()

        /** The biometric prompt may be offered now. */
        val canSignWithBiometrics: Boolean
            get() = idle && quote != null && unlock.biometrics

        /** No quote nor signature runs, and nothing was signed yet: the payer and the fee may change. */
        val idle: Boolean
            get() = !quoting && !signing && notSent == null
    }

    /** Broadcast: [hash] is the deployment's transaction, which the status screen follows. */
    data class Sent(override val network: String, val hash: String) : CreateSafeUiState
}

/**
 * One owner the Safe may get: its EIP-55 [address], its [label] (its name in the wallet, or the
 * ENS name it was resolved from; null: none), [note] what it is ("Key on this device"), and
 * whether it is [selected] as an owner. [simpleNote] is [note] with no block, for simple mode; null
 * where that is [note] itself.
 */
data class OwnerChoice(
    val address: String,
    val label: String?,
    val note: String,
    val selected: Boolean,
    val simpleNote: String? = null,
)

/**
 * One owner of the Safe as the threshold and the review list it: its EIP-55 [address], [label] and
 * [note]; [simpleNote] is [note] with no block, for simple mode, null where that is [note] itself.
 */
data class OwnerItem(val address: String, val label: String?, val note: String, val simpleNote: String? = null)

/**
 * The verified quote of a deployment: the most its fee can be ([maxFee], in the network's
 * currency) and what it is made of ([feeDetail]); the payer's [balance] before → after, with its
 * block; how the factory's call ran ([simulation]); and [pending], what the payer's earlier
 * transactions not mined yet may still take, if anything. [simpleSimulation] is [simulation] with
 * no block, for simple mode, which keeps the fee's make-up, the balance's block and the run's
 * behind *Details*.
 */
data class DeploymentQuoteItem(
    val maxFee: String,
    val feeDetail: String,
    val balance: BalanceChange,
    val simulation: String,
    val pending: String? = null,
    val simpleSimulation: String = simulation,
)

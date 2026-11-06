package eth.serotine.ui.handoff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PaymentLinks
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.AccountValue
import eth.serotine.domain.usecase.HANDOFF_WINDOW
import eth.serotine.domain.usecase.HandOffTransfer
import eth.serotine.domain.usecase.Handoff
import eth.serotine.domain.usecase.HandoffMatch
import eth.serotine.domain.usecase.HandoffOffer
import eth.serotine.domain.usecase.HandoffStart
import eth.serotine.domain.usecase.HandoffWatch
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.OfferHandoff
import eth.serotine.domain.usecase.RecipientLookup
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.WatchHandoff
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.AssetOption
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NO_WALLET
import eth.serotine.ui.components.RecipientState
import eth.serotine.ui.components.ResolvedAgain
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.TransferForm
import eth.serotine.ui.components.ZERO_ADDRESS
import eth.serotine.ui.components.addressFieldLabel
import eth.serotine.ui.components.amountHint
import eth.serotine.ui.components.assetOptions
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.components.exactAmount
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.recipientHint
import eth.serotine.ui.components.recipientItem
import eth.serotine.ui.components.recipientNote
import eth.serotine.ui.components.recipientStates
import eth.serotine.ui.components.recipientText
import eth.serotine.ui.components.redatedWhile
import eth.serotine.ui.components.resolveAgain
import eth.serotine.ui.components.retryLookupWaits
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.components.typedAmount
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Send from another wallet's ViewModel (issue #255; docs/wallet-ux.md, screen 7H): a transfer from
 * [account] on its network — an EOA whose key this device does not hold — composed as on Send (the
 * asset, [asset] the token asked for, null for the network's currency; the recipient typed or
 * picked and resolved by [resolveRecipient]; the amount in token units), reviewed, then handed to
 * another wallet app as a payment link ([handOffTransfer]) and followed on the chain
 * ([watchHandoff]) for [HANDOFF_WINDOW], as [timeSource] measures it.
 *
 * Serotine signs nothing and receives nothing back through the link: what the outcome says is
 * what verified blocks hold. A name is resolved again before the link is made, as Send resolves
 * one again before a quote: the link names the address the name has now. A link is made once: the
 * screen never offers to open the wallet a second time for the same transfer, which could pay
 * twice.
 */
class HandoffViewModel(
    private val account: AccountId,
    asset: Address?,
    observeAccounts: ObserveAccounts,
    observeAccountValue: ObserveAccountValue,
    private val resolveRecipient: ResolveRecipient,
    private val offerHandoff: OfferHandoff,
    private val handOffTransfer: HandOffTransfer,
    private val watchHandoff: WatchHandoff,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val now: () -> Long = ::epochSecondsNow,
) : ViewModel() {

    private val from: Address = account.address
    private val network: Network = account.network
    private val display = Eip55.format(from)

    /** What the compose step has: the token chosen (null: the network's currency), the recipient and amount typed. */
    private data class Inputs(
        val asset: Address?,
        val to: String = "",
        val amount: String = "",
        val lookupAttempt: Int = 0,
    )

    /** The account handed over from, or why nothing can be. */
    private sealed interface Sender {
        data class From(val account: Account) : Sender

        data class Cannot(val message: String) : Sender
    }

    private class Model(
        val sender: Sender,
        val accounts: List<Account>,
        val currency: DisplayCurrency,
        val options: List<AssetOption>,
        val selected: AssetOption?,
        val tokensRead: Boolean,
        val inputs: Inputs,
        val lookup: RecipientState,
    )

    /** A transfer the compose step can hand over, exactly as it has it. */
    private data class Request(
        val to: RecipientLookup.Found,
        val option: AssetOption,
        val amount: UInt256,
        val sender: SendFrom,
        val accounts: List<Account>,
    )

    private sealed interface Step {
        /** Composing; [checking] while the review is entered, [error] why the last attempt was not. */
        data class Composing(val checking: Boolean = false, val error: String? = null) : Step

        /** [request] under review; [opening] while it is handed over, [error] why it was not. */
        data class Reviewing(val request: Request, val opening: Boolean = false, val error: String? = null) : Step

        /** [handoff] handed over, and the latest of what the chain shows of it ([watch], null before the first). */
        data class Watching(val request: Request, val handoff: Handoff, val watch: HandoffWatch? = null) : Step

        /** The chain's answer about [handoff]: [watch] is a [HandoffWatch.Seen] or [HandoffWatch.NotSeen]. */
        data class Done(val request: Request, val watch: HandoffWatch) : Step
    }

    private val inputs = MutableStateFlow(Inputs(asset))
    private val step = MutableStateFlow<Step>(Step.Composing())
    private var job: Job? = null

    private val lookup: Flow<RecipientState> =
        recipientStates(inputs.map { it.to.trim() to it.lookupAttempt }, ::lookUp)

    private val model: StateFlow<Model?> =
        combine(observeAccounts(), observeAccountValue(account), inputs, lookup) { list, value, inputs, lookup ->
            modelOf(list, value, inputs, lookup)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            null,
        )

    val uiState: StateFlow<HandoffUiState> = combine(model, step) { model, step -> model to step }
        .redatedWhile { (model, step) ->
            step is Step.Composing && model?.options.orEmpty().any { it.provenAt != null }
        }
        .map { (model, step) -> render(model, step) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            HandoffUiState.Loading(network.displayName),
        )

    /** Chooses the asset: the token at [contract], or the network's currency (null). */
    fun onAssetChange(contract: String?) {
        val asset = contract?.let { Address.parse(it) ?: return }
        change { it.copy(asset = asset) }
    }

    /** The recipient field now reads [text]. */
    fun onToChange(text: String) = change { it.copy(to = text) }

    /** Hands over to the wallet's account at [address], its EIP-55 form. */
    fun onPickAccount(address: String) = change { it.copy(to = address) }

    /** Resolves the recipient again, after a lookup that could not be verified. */
    fun onRetryLookup() = change { it.copy(lookupAttempt = it.lookupAttempt + 1) }

    /** The amount field now reads [text], in the asset's units. */
    fun onAmountChange(text: String) = change { it.copy(amount = text) }

    /** Fills in the chosen asset's whole verified balance; the other wallet's fee then has to come from elsewhere. */
    fun onMax() {
        val option = model.value?.selected ?: return
        val balance = option.balance ?: return
        change { it.copy(amount = balance.formatUnits(option.decimals)) }
    }

    /** Shows the transfer as composed, for review: a name resolved again first. */
    fun review() {
        val current = step.value as? Step.Composing ?: return
        if (current.checking) return
        val request = model.value?.let(::requestOf) ?: return
        step.value = Step.Composing(checking = true)
        job = viewModelScope.launch {
            val next = when (val again = resolveAgain(request.to, ::lookUp)) {
                is ResolvedAgain.Same -> Step.Reviewing(request.copy(to = again.found))
                is ResolvedAgain.Changed -> {
                    inputs.update { it.copy(lookupAttempt = it.lookupAttempt + 1) }
                    Step.Composing(error = again.message)
                }
            }
            step.update { latest -> if (latest == Step.Composing(checking = true)) next else latest }
        }
    }

    /** Back from the review to the compose step; not while the link is being handed over. */
    fun edit() {
        val current = step.value as? Step.Reviewing ?: return
        if (current.opening) return
        job?.cancel()
        step.value = Step.Composing()
    }

    /**
     * Hands the reviewed transfer to another wallet app, once, and follows it on the chain. A name
     * is resolved again first, so that the link names the address the name has now.
     */
    fun open() {
        val current = step.value as? Step.Reviewing ?: return
        if (current.opening) return
        step.value = current.copy(opening = true, error = null)
        job = viewModelScope.launch {
            val request = when (val again = resolveAgain(current.request.to, ::lookUp)) {
                is ResolvedAgain.Same -> current.request.copy(to = again.found)
                is ResolvedAgain.Changed -> {
                    step.value = current.copy(opening = false, error = again.message)
                    return@launch
                }
            }
            val start = try {
                handOffTransfer(account, request.option.asset, request.to.address, request.amount)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HandoffStart.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
            }
            val handoff = (start as? HandoffStart.Opened)?.handoff
            if (handoff == null) {
                step.value = current.copy(request = request, opening = false, error = refusal(start))
                return@launch
            }
            step.value = Step.Watching(request, handoff)
            follow(request, handoff)
        }
    }

    private suspend fun follow(request: Request, handoff: Handoff) {
        val deadline = timeSource.markNow() + HANDOFF_WINDOW
        watchHandoff(handoff, deadline).collect { watch ->
            step.value = when (watch) {
                is HandoffWatch.Seen, is HandoffWatch.NotSeen -> Step.Done(request, watch)
                else -> Step.Watching(request, handoff, watch)
            }
        }
    }

    private fun change(edit: (Inputs) -> Inputs) {
        if (step.value !is Step.Composing) return
        job?.cancel()
        inputs.update(edit)
        step.value = Step.Composing()
    }

    private fun modelOf(list: AccountList, value: AccountValue, inputs: Inputs, lookup: RecipientState): Model {
        val accounts = (list as? AccountList.Loaded)?.accounts.orEmpty().filter { it.network == network }
        val sender = when (list) {
            is AccountList.Unreadable -> Sender.Cannot(LIST_UNAVAILABLE)
            is AccountList.Loaded -> {
                val account = accounts.firstOrNull { it.address == from }
                when {
                    account == null -> Sender.Cannot(NOT_IN_WALLET)
                    else -> when (offerHandoff(account)) {
                        HandoffOffer.Offered -> Sender.From(account)
                        HandoffOffer.NoWallet -> Sender.Cannot(NO_WALLET)
                        HandoffOffer.LeftOut -> Sender.Cannot(leftOut(account))
                    }
                }
            }
        }
        val options = assetOptions(value, inputs.asset, network)
        return Model(
            sender = sender,
            accounts = accounts,
            currency = value.currency,
            options = options,
            selected = options.firstOrNull { it.contract == inputs.asset },
            tokensRead = value.tokensRead,
            inputs = inputs,
            lookup = lookup,
        )
    }

    /** Why an account the action does not apply to has none: what a direct way to the screen is told. */
    private fun leftOut(account: Account): String = when {
        account.kind == AccountKind.SAFE -> SAFE
        account.custody == AccountCustody.InKeyStore -> KEY_HERE
        else -> NOT_ON_THIS_DEVICE
    }

    private fun render(model: Model?, step: Step): HandoffUiState {
        val name = network.displayName
        return when (step) {
            is Step.Reviewing -> review(step)
            is Step.Watching -> waiting(step)
            is Step.Done -> outcome(step.request, step.watch)
            is Step.Composing -> {
                model ?: return HandoffUiState.Loading(name)
                when (val sender = model.sender) {
                    is Sender.Cannot -> HandoffUiState.Cannot(name, sender.message)
                    is Sender.From -> HandoffUiState.Compose(form(model, SendFrom(sender.account.label, display), step))
                }
            }
        }
    }

    private fun form(model: Model, sender: SendFrom, step: Step.Composing): TransferForm {
        val inputs = model.inputs
        val at = now()
        val text = inputs.to.trim()
        val lookup = model.lookup.takeIf { it.text == text }
        val result = (lookup as? RecipientState.Done)?.result
        val (note, isError) = recipientNote(text, lookup)
        val selected = model.selected
        val amount = typedAmount(inputs.amount, selected)
        return TransferForm(
            network = network.displayName,
            toLabel = addressFieldLabel(network),
            toHint = recipientHint(network),
            from = sender,
            assets = model.options.map { it.choice(at) },
            asset = selected?.choice(at),
            to = inputs.to,
            accounts = model.accounts.filter { it.address != from }
                .map { AccountChoice(it.label, Eip55.format(it.address)) },
            amount = inputs.amount,
            assetNote = when {
                selected != null -> null
                !model.tokensRead -> "Checking this account's tokens…"
                else -> "The token asked for is not among this account's tokens: choose another asset."
            },
            recipient = (result as? RecipientLookup.Found)?.let { recipientItem(it, model.accounts, selected) },
            toNote = note,
            toError = isError,
            canRetryLookup = result is RecipientLookup.Unavailable,
            retryLookupWaits = retryLookupWaits(result),
            amountError = amount.error ?: overBalance(amount.units, selected),
            amountHint = amountHint(amount.units, selected, model.currency, network),
            canMax = selected?.balance != null && !step.checking,
            quoting = step.checking,
            error = step.error,
            canReview = !step.checking && requestOf(model) != null,
        )
    }

    /** Why [amount] cannot be handed over from [option]'s verified balance, or null when it can. */
    private fun overBalance(amount: UInt256?, option: AssetOption?): String? {
        val balance = option?.balance ?: return null
        if (amount == null || amount <= balance) return null
        return "This account holds ${exactAmount(balance, option.decimals, option.symbol)}, verified: less than that."
    }

    private fun review(step: Step.Reviewing): HandoffUiState.Review {
        val request = step.request
        val option = request.option
        val notes = listOfNotNull(
            "Serotine signs nothing. The wallet that opens signs and sends this transfer, and its fee is that " +
                "wallet's to set.",
            "In that wallet, choose “${request.sender.label}” (${shortAddress(display)}) before you confirm: it " +
                "sends from whichever of its accounts is chosen.",
            "That wallet sends the transfer through its own provider, which learns the transfer and this " +
                "device's IP address.",
            "Nothing comes back from that wallet. Serotine checks each new block for this transfer for " +
                "${HANDOFF_WINDOW.inWholeMinutes} minutes after the wallet opens.",
            "${network.displayName} is a test network: its ${network.nativeSymbol} has no value."
                .takeIf { network.testnet },
        )
        // MetaMask's parser turns 10^21 smallest units and more into an exponent, and refuses it, for the
        // network's currency's wei and a token's uint256 alike (#255's spike).
        val limit = when {
            request.amount < PaymentLinks.METAMASK_MAX_WEI_EXCLUSIVE -> null
            option.asset == TransferAsset.Native ->
                "MetaMask cannot open a payment link for 1,000 ${network.nativeSymbol} or more. Another wallet may."
            else -> "MetaMask cannot open a payment link for an amount this large of a token with " +
                "${option.decimals} decimals. Another wallet may."
        }
        return HandoffUiState.Review(
            network = network.displayName,
            from = request.sender,
            amount = exactAmount(request.amount, option.decimals, option.symbol),
            asset = option.choice(),
            recipient = recipientItem(request.to, request.accounts, option),
            notes = notes,
            limit = limit,
            opening = step.opening,
            error = step.error,
        )
    }

    private fun waiting(step: Step.Watching): HandoffUiState.Waiting {
        val request = step.request
        val watch = step.watch
        return HandoffUiState.Waiting(
            network = network.displayName,
            from = request.sender,
            summary = "${amountOf(request, request.amount)} to ${Eip55.format(request.to.address)}, from " +
                "“${request.sender.label}”, as handed over.",
            progress = when (watch) {
                null -> "Reading the newest verified block…"
                is HandoffWatch.Watching -> listOfNotNull(
                    "No block up to ${grouped(watch.readUpTo)} has it from this account yet. Serotine keeps " +
                        "looking for ${HANDOFF_WINDOW.inWholeMinutes} minutes after the handoff.",
                    watch.nearBlock?.let {
                        "Block ${grouped(it)} has a transfer like it, of another amount or from another account."
                    },
                ).joinToString(" ")
                is HandoffWatch.Found -> "Block ${grouped(watch.block)} has a transfer: reading its verified receipt…"
                is HandoffWatch.Seen, is HandoffWatch.NotSeen -> ""
            },
            problem = when (watch) {
                is HandoffWatch.Watching -> watch.unavailable?.let(::unreadable)
                is HandoffWatch.Found -> watch.unavailable?.let(::receiptUnreadable)
                else -> null
            },
        )
    }

    private fun outcome(request: Request, watch: HandoffWatch): HandoffUiState.Outcome {
        val name = network.displayName
        val recipient = Eip55.format(request.to.address)
        val asked = amountOf(request, request.amount)
        return when (watch) {
            is HandoffWatch.Seen -> {
                val block = grouped(watch.block)
                when (val match = watch.match) {
                    HandoffMatch.Exact -> if (watch.succeeded == null) {
                        HandoffUiState.Outcome(
                            name,
                            OutcomeKind.NOT_SEEN,
                            "Its receipt could not be checked",
                            "$asked from “${request.sender.label}” to $recipient is in block $block, but its " +
                                "receipt could not be verified in time, so whether it went through is not known. " +
                                "The account's balance shows it.",
                            hash = watch.hash,
                            block = block,
                        )
                    } else if (watch.succeeded == true) {
                        HandoffUiState.Outcome(
                            name,
                            OutcomeKind.SENT,
                            "Sent",
                            "$asked from “${request.sender.label}” to $recipient is in block $block, its receipt " +
                                "verified.",
                            hash = watch.hash,
                            block = block,
                        )
                    } else {
                        HandoffUiState.Outcome(
                            name,
                            OutcomeKind.FAILED,
                            "It failed",
                            "The transfer is in block $block, but it reverted: nothing was sent, and the other " +
                                "wallet paid its fee.",
                            hash = watch.hash,
                            block = block,
                        )
                    }
                    is HandoffMatch.OtherAmount -> HandoffUiState.Outcome(
                        name,
                        OutcomeKind.NOT_THE_ONE,
                        "Not what you handed over",
                        "A transfer of ${amountOf(request, match.amount)}, not $asked, from " +
                            "“${request.sender.label}” to $recipient is in block $block. " +
                            receiptSays(watch.succeeded),
                        hash = watch.hash,
                        block = block,
                    )
                    is HandoffMatch.OtherSender -> HandoffUiState.Outcome(
                        name,
                        OutcomeKind.NOT_THE_ONE,
                        "Not from this account",
                        "A transfer of $asked to $recipient is in block $block, from ${Eip55.format(match.sender)} " +
                            "and not from “${request.sender.label}”. It may come from another account of the other " +
                            "wallet, or be someone else's payment. It is not counted as this account's. " +
                            receiptSays(watch.succeeded),
                        hash = watch.hash,
                        block = block,
                    )
                }
            }
            is HandoffWatch.NotSeen -> HandoffUiState.Outcome(
                name,
                OutcomeKind.NOT_SEEN,
                "No matching transfer seen yet",
                "No block up to ${grouped(watch.readUpTo)} has $asked from “${request.sender.label}” to $recipient. " +
                    (watch.unavailable?.let { "The blocks after it could not be checked: ${unreadable(it)} " } ?: "") +
                    "If it is sent later, the account's balance shows it once a block has it.",
            )
            else -> error("not an outcome: $watch")
        }
    }

    /**
     * What the verified receipt of a transaction found says, in words that claim no more than it
     * proved: a block holding a transaction proves it exists, not that it moved anything.
     */
    private fun receiptSays(succeeded: Boolean?): String = when (succeeded) {
        true -> "Its verified receipt says it went through."
        false -> "Its verified receipt says it reverted: nothing was moved."
        null -> "Its receipt could not be verified in time, so whether it went through is not known."
    }

    private fun amountOf(request: Request, units: UInt256): String =
        exactAmount(units, request.option.decimals, request.option.symbol)

    private fun recipientItem(found: RecipientLookup.Found, accounts: List<Account>, asset: AssetOption?) =
        recipientItem(found, accounts) { address ->
            when (address) {
                from -> "The account you send from: only the fee is spent."
                asset?.contract -> "The token's own contract: nothing sent to it can ever be moved out."
                else -> null
            }
        }

    private fun requestOf(model: Model): Request? {
        val sender = (model.sender as? Sender.From)?.account ?: return null
        val lookup = model.lookup as? RecipientState.Done ?: return null
        if (lookup.text != model.inputs.to.trim()) return null
        val to = lookup.result as? RecipientLookup.Found ?: return null
        val option = model.selected ?: return null
        val amount = typedAmount(model.inputs.amount, option).units ?: return null
        if (overBalance(amount, option) != null) return null
        return Request(to, option, amount, SendFrom(sender.label, display), model.accounts)
    }

    private fun refusal(start: HandoffStart): String = when (start) {
        is HandoffStart.Opened -> error("an opened handoff is no refusal")
        HandoffStart.NoWallet -> NO_WALLET
        HandoffStart.NotOpened -> "The wallet app that opens payment links on this device would not be opened, so " +
            "nothing was handed over. Try another wallet app."
        HandoffStart.ToZeroAddress -> ZERO_ADDRESS
        HandoffStart.ToTokenContract ->
            "The token's own contract: nothing sent to it can ever be moved out, so it is refused."
        HandoffStart.NothingToSend -> "There is nothing to send: enter an amount above zero."
        is HandoffStart.Unavailable ->
            "Nothing was handed over: " + unreadable(start.reason).replaceFirstChar { it.lowercase() }
    }

    /** Why a verified block cannot be read now. */
    private fun unreadable(reason: ReadUnavailable): String = when (reason) {
        ReadUnavailable.NO_ENGINE -> "There is no light client on this device to check the transfer."
        ReadUnavailable.NETWORK_OFF -> "The network's light client is not running, so the transfer cannot be checked."
        ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so the transfer cannot be checked."
        ReadUnavailable.SYNCING -> "The light client is still syncing: the transfer is checked once it has caught up."
        ReadUnavailable.UNPROVEN -> "The newest block could not be verified this time: Serotine tries again."
        ReadUnavailable.REFUSED ->
            "The light client on this device cannot read blocks, so the transfer cannot be checked."
    }

    /** Why the receipt of the transaction found cannot be verified now. */
    private fun receiptUnreadable(reason: ReadUnavailable): String =
        "Its receipt cannot be verified now: " + unreadable(reason).replaceFirstChar { it.lowercase() }

    /** What [text] names, resolved; never throws. */
    private suspend fun lookUp(text: String): RecipientLookup = try {
        resolveRecipient(network, recipientText(text))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RecipientLookup.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
    }

    private companion object {
        const val LIST_UNAVAILABLE =
            "Your account list cannot be read right now, so nothing can be handed over. Serotine keeps trying."
        const val NOT_IN_WALLET = "This account is not in your wallet any more."
        const val SAFE = "A payment link cannot sign a Safe transaction: Send on the Safe's own screen proposes one."
        const val KEY_HERE = "This account's key is on this device: Send signs and sends from it here."
        const val NOT_ON_THIS_DEVICE = "This device does not hand payment links to other wallets."
    }
}

package eth.serotine.ui.createsafe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.DeploySafe
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.PlanSafe
import eth.serotine.domain.usecase.PlannedOwner
import eth.serotine.domain.usecase.QuoteSafeDeployment
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.RecipientLookup
import eth.serotine.domain.usecase.RenameAccount
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.SafeDeploymentQuote
import eth.serotine.domain.usecase.SafeDeploymentQuoting
import eth.serotine.domain.usecase.SafeDeploymentRefusal
import eth.serotine.domain.usecase.SafePlan
import eth.serotine.domain.usecase.SafePlanRefusal
import eth.serotine.domain.usecase.SafePlanning
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.domain.usecase.WarmFeeHistory
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NATIVE_DECIMALS
import eth.serotine.ui.components.NetworkChip
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.PROMPT_TIMED_OUT
import eth.serotine.ui.components.PROMPT_TOO_LATE
import eth.serotine.ui.components.QUOTE_MAX_AGE
import eth.serotine.ui.components.RecipientState
import eth.serotine.ui.components.ResolvedAgain
import eth.serotine.ui.components.STALE_QUOTE
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.addressFieldLabel
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.callRefusalText
import eth.serotine.ui.components.exactAmount
import eth.serotine.ui.components.feeDetail
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.notSentItem
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.promptDeadline
import eth.serotine.ui.components.recipientNote
import eth.serotine.ui.components.recipientStates
import eth.serotine.ui.components.recipientText
import eth.serotine.ui.components.resolveAgain
import eth.serotine.ui.components.retryLookupWaits
import eth.serotine.ui.components.simpleCallRefusalText
import eth.serotine.ui.components.unavailableText
import eth.serotine.ui.components.verifiedAt
import kotlin.concurrent.Volatile
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The ViewModel of Create Safe (docs/wallet-ux.md, screens 13 to 15): first the network it is
 * created on (docs/networks.md, N12; by default the first that is on and has an account whose key is
 * held here to pay), then the Safe's
 * name and owners — the wallet's accounts, and addresses or ENS names typed ([ResolveRecipient]) —
 * then its threshold, held to the recovery shapes [PlanSafe] allows, then its deployment planned
 * (the address computed here) and quoted from an account whose key is held here
 * ([QuoteSafeDeployment]: the factory's verified run must return that address) for review, and
 * signed and broadcast ([DeploySafe]); one signed but not broadcast is retried with its own bytes
 * ([RebroadcastTransfer]), never signed anew.
 *
 * What is signed is the quote under review, and a quote is never signed stale: it is made when the
 * review is entered, again on Refresh, on a change of the payer or the fee, when the screen is
 * shown again after it was not, and when it is older than [QUOTE_MAX_AGE] at the tap that would
 * sign it ([timeSource] tells). The plan stays what it was while the review is open, so its
 * address does not move under the user; back to the threshold drops it, and the next review plans
 * anew (another salt, another address).
 *
 * One of the ViewModels the [WalletPassword] passes through (CLAUDE.md, *Trust*): it holds it in
 * the review's state while the field is shown, hands it to the signer in an [Unlock], and never
 * logs, saves or caches it; it is dropped once used, when the screen is not shown ([onHidden]) and
 * when the screen is gone ([onCleared]).
 */
class CreateSafeViewModel(
    observeAccounts: ObserveAccounts,
    private val describeKeyStore: DescribeKeyStore,
    private val resolveRecipient: ResolveRecipient,
    private val planSafe: PlanSafe,
    private val quoteSafeDeployment: QuoteSafeDeployment,
    private val deploySafe: DeploySafe,
    private val rebroadcastTransfer: RebroadcastTransfer,
    private val warmFeeHistory: WarmFeeHistory,
    observeEnabledNetworks: ObserveEnabledNetworks,
    private val setNetworkEnabled: SetNetworkEnabled,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {

    /** The network the user picked; null until they pick one, the default standing until then. */
    private val picked = MutableStateFlow<Network?>(null)

    /**
     * The network the Safe is created on, as the model last found it: what the steps that do not read
     * the model (a name's lookup, the fee history, the sent screen) use. A review uses its plan's.
     */
    @Volatile
    private var network: Network = Network.entries.first()

    /** The network whose fee history was last asked for; null until the model first settles one. */
    private var warmed: Network? = null

    /** An owner typed and added: its [address], and the ENS [name] it was resolved from at [resolvedAt]. */
    private data class Typed(val address: Address, val name: String?, val resolvedAt: Long?)

    /**
     * What the first two steps have: the Safe's [name]; the wallet's accounts [selected] as owners
     * (null: the default, the first account whose key is held here) and the owners [typed]; the
     * owner [input] being typed and how often the user asked to resolve it again; the [threshold]
     * (null: the default for the owners chosen).
     */
    private data class Inputs(
        val name: String = "",
        val selected: Set<Address>? = null,
        val typed: List<Typed> = emptyList(),
        val input: String = "",
        val lookupAttempt: Int = 0,
        val threshold: Int? = null,
    )

    /** Everything the first two steps are derived from. */
    private class Model(
        val list: AccountList,
        val inputs: Inputs,
        val lookup: RecipientState,
        val network: Network,
        /** The networks on in this wallet. */
        val on: Set<Network>,
    ) {
        val accounts: List<Account> = (list as? AccountList.Loaded)?.accounts.orEmpty()

        /**
         * The wallet's externally owned accounts, on any network, one entry per address (the one
         * held in the key store where there is one): what can be chosen as an owner, since an
         * address is an owner on any chain (docs/networks.md, *Safes and swaps*).
         */
        val eoas: List<Account> = accounts.filter { it.kind == AccountKind.EOA }
            .groupBy { it.address }
            .map { (_, entries) -> entries.firstOrNull { it.custody == AccountCustody.InKeyStore } ?: entries.first() }

        /** Those whose key is held here on [network]: what can pay for the deployment there. */
        val held: List<Account> = accounts.filter {
            it.network == network && it.kind == AccountKind.EOA && it.custody == AccountCustody.InKeyStore
        }

        val selected: Set<Address> = inputs.selected ?: setOfNotNull(held.firstOrNull()?.address)

        private val chosen: List<Address> = eoas.filter { it.address in selected }.map { it.address }

        /** The owners typed and added, but for one that is a wallet's account chosen already. */
        val typed: List<Typed> = inputs.typed.filter { it.address !in chosen }

        /** The owners chosen, in order: the wallet's accounts as listed, then those typed as added. */
        val owners: List<Address> = chosen + typed.map { it.address }

        val threshold: Int = (inputs.threshold ?: defaultThreshold(owners.size)).coerceIn(1, maxOf(1, owners.size))
    }

    /**
     * A deployment signed but not broadcast: why ([broadcast]), and the [transfer] a retry sends;
     * [unverified] when a retry's rejection could not be checked against the receipt.
     */
    private data class NotSent(val broadcast: Broadcast, val transfer: SentTransfer, val unverified: Boolean = false) {
        /** Whether sending the same bytes again may change the verdict. */
        val canRetry: Boolean
            get() = unverified || broadcast is Broadcast.Unavailable || transfer.maybeSent
    }

    /** Where the flow stands. */
    private sealed interface Step {
        /** The owners, and [error], why the last review went back to them (a name that moved), if so. */
        data class Owners(val error: String? = null) : Step

        data object Threshold : Step

        /**
         * The review of [plan], with its [owners] as they were listed when it was planned ([named]:
         * those added by an ENS name, resolved again with every quote), sent from [payer] (one of
         * [payers], the accounts held here then) at [fee]: [quote], made at [quotedAt], or [error] why
         * there is none; [hidden] once the screen stopped being shown since it was made.
         */
        data class Reviewing(
            val plan: SafePlan,
            val owners: List<OwnerItem>,
            val named: List<Typed>,
            val payers: List<Account>,
            val payer: Address,
            val fee: FeeLevel,
            val unlock: UnlockStep,
            val quote: SafeDeploymentQuote? = null,
            val quotedAt: TimeMark? = null,
            val error: String? = null,
            val quoting: Boolean = false,
            val signing: Boolean = false,
            val notSent: NotSent? = null,
            val hidden: Boolean = false,
            /** [error] with no block, for simple mode; null where it is [error] itself. */
            val simpleError: String? = null,
        ) : Step {
            val idle: Boolean
                get() = !quoting && !signing && notSent == null
        }

        data class Sent(val hash: String) : Step
    }

    private val inputs = MutableStateFlow(Inputs())
    private val step = MutableStateFlow<Step>(Step.Owners())

    /** The quote, signature or broadcast in flight. */
    private var job: Job? = null

    /** Whether the screen is shown now ([onShown], [onHidden]). */
    private var shown = false

    /** The owner typed, resolved on the network chosen: an address at once, a name once the typing paused. */
    private val lookup: Flow<RecipientState> =
        recipientStates(inputs.map { it.input.trim() to it.lookupAttempt }) { text -> lookUp(network, text) }

    private val model: StateFlow<Model?> = combine(
        observeAccounts(),
        inputs,
        lookup,
        observeEnabledNetworks(),
        picked,
    ) { list, inputs, lookup, enabled, picked ->
        val on = enabled.toSet()
        val chosen = picked ?: defaultNetwork(list, on)
        network = chosen
        // The network settled, at the screen's opening or chosen anew: its fee history is asked for
        // now, ready by the review; once per network, not for the default it was before the accounts.
        if (chosen != warmed) {
            warmed = chosen
            warmFees()
        }
        Model(list, inputs, lookup, chosen, on)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        null,
    )

    /** The network whose light client the strip shows: the one the Safe is created on. */
    val stripNetwork: StateFlow<Network> = model.map { it?.network ?: network }
        // As the model is: followed while the screen is, never keeping the account list read past it.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), network)

    val uiState: StateFlow<CreateSafeUiState> = combine(model, step, ::render).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        CreateSafeUiState.Loading(network.displayName),
    )

    /**
     * Creates the Safe on the network of [chainId] (N12): on the owners step only, before anything
     * is planned. The owners chosen among the wallet's accounts start again from that network's.
     */
    fun onNetwork(chainId: Long) {
        val chosen = Network.ofChainId(chainId) ?: return
        // Before a review only: nothing is planned until then. The owners are looked at again.
        if (step.value !is Step.Owners && step.value !is Step.Threshold) return
        picked.value = chosen
        inputs.update { it.copy(selected = null, threshold = null) }
        step.value = Step.Owners()
    }

    /** Turns the network of [chainId] on, and chooses it: the way to create a Safe on one that is off. */
    fun onTurnOnNetwork(chainId: Long) {
        val chosen = Network.ofChainId(chainId) ?: return
        viewModelScope.launch {
            if (setNetworkEnabled(chosen, on = true)) onNetwork(chainId)
        }
    }

    /** The Safe's name now reads [text]. */
    fun onNameChange(text: String) = changeOwners { it.copy(name = text) }

    /** Chooses the wallet's account at [address] (EIP-55) as an owner, or no longer. */
    fun onToggleOwner(address: String) {
        val owner = Address.parse(address) ?: return
        val model = model.value ?: return
        if (model.eoas.none { it.address == owner }) return
        val selected = model.selected
        val next = if (owner in selected) selected - owner else selected + owner
        changeOwners { it.copy(selected = next, threshold = null) }
    }

    /** The owner field now reads [text]. */
    fun onInputChange(text: String) = changeOwners { it.copy(input = text) }

    /** Resolves the typed owner again, after a lookup that could not be verified. */
    fun onRetryLookup() = changeOwners { it.copy(lookupAttempt = it.lookupAttempt + 1) }

    /** Adds the owner typed, once it is an address: a wallet's account is chosen, any other one added. */
    fun onAddOwner() {
        val model = model.value ?: return
        val found = foundInput(model) ?: return
        if (found.address in model.owners) return
        changeOwners { current ->
            if (model.eoas.any { it.address == found.address }) {
                current.copy(selected = model.selected + found.address, input = "", threshold = null)
            } else {
                val typed = Typed(found.address, found.name, found.blockNumber)
                current.copy(typed = current.typed + typed, input = "", threshold = null)
            }
        }
    }

    /** Takes the typed owner at [address] (EIP-55) out again. */
    fun onRemoveTyped(address: String) {
        val owner = Address.parse(address) ?: return
        changeOwners { current ->
            current.copy(typed = current.typed.filterNot { it.address == owner }, threshold = null)
        }
    }

    /** The threshold now [threshold], within one and the number of owners. */
    fun onThresholdChange(threshold: Int) {
        if (step.value != Step.Threshold) return
        val owners = model.value?.owners?.size ?: return
        inputs.update { it.copy(threshold = threshold.coerceIn(1, maxOf(1, owners))) }
    }

    /** On from the owners to the threshold, and from the threshold to the review, when the step allows. */
    fun onContinue() {
        val model = model.value ?: return
        when (step.value) {
            is Step.Owners -> if (ownersRefusal(model) == null && nameError(model.inputs.name) == null) {
                step.value = Step.Threshold
            }
            Step.Threshold -> review(model)
            else -> Unit
        }
    }

    /**
     * Back one step: from the threshold to the owners; from the review to the threshold, its plan,
     * its quote and the password gone. Not while signing, nor after a broadcast no peer answered:
     * those bytes may be out there, and only a retry of them may send them again (the screen leaves
     * then instead).
     */
    fun back() {
        when (val current = step.value) {
            Step.Threshold -> step.value = Step.Owners()
            is Step.Reviewing -> {
                if (current.signing || current.notSent?.canRetry == true) return
                job?.cancel()
                step.value = Step.Threshold
            }
            else -> Unit
        }
    }

    /** Pays from the account at [address] (EIP-55) instead: quoted again. */
    fun onPayerChange(address: String) {
        val payer = Address.parse(address) ?: return
        val current = step.value as? Step.Reviewing ?: return
        if (current.signing || current.notSent != null || payer == current.payer) return
        if (current.payers.none { it.address == payer }) return
        step.value = current.copy(payer = payer, quote = null, quotedAt = null)
        requote(note = null)
    }

    /** Chooses the fee [level]: quoted again. */
    fun onFeeChange(level: FeeLevel) {
        val current = step.value as? Step.Reviewing ?: return
        if (current.signing || current.notSent != null || level == current.fee) return
        step.value = current.copy(fee = level, quote = null, quotedAt = null)
        requote(note = null)
    }

    /** Quotes the deployment again: fresh fees, nonce, balance and run. */
    fun refresh() {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle) return
        requote(note = null)
    }

    /** The password field now reads [text]. */
    fun onPasswordChange(text: String) = step.update { current ->
        if (current is Step.Reviewing && !current.signing && current.notSent == null) {
            current.copy(unlock = current.unlock.copy(password = WalletPassword(text), error = null))
        } else {
            current
        }
    }

    /** Signs the reviewed deployment with the typed wallet password, and broadcasts it. */
    fun sign() {
        val current = step.value as? Step.Reviewing ?: return
        val quote = current.quote ?: return
        val password = current.unlock.password
        if (!current.idle || password.text.isEmpty() || requoted(current)) return
        sign(current, quote, Unlock.WithPassword(password))
    }

    /** Signs the reviewed deployment with a fingerprint or face, and broadcasts it. */
    fun signWithBiometrics() {
        val current = step.value as? Step.Reviewing ?: return
        val quote = current.quote ?: return
        if (!current.idle || !current.unlock.biometrics || requoted(current)) return
        val plan = current.plan
        val payer = current.payers.firstOrNull { it.address == current.payer }?.label ?: Eip55.format(current.payer)
        // The address in full: the prompt is the last thing read before the key signs.
        val prompt = "Deploy a ${plan.threshold}-of-${plan.owners.size} Safe at ${Eip55.format(plan.address)}, " +
            "paid from $payer"
        // The prompt gets what is left of the quote's age: past it, nothing is signed, and it is made again.
        val quotedAt = current.quotedAt ?: return requote(note = STALE_QUOTE)
        val notAfter = promptDeadline(quotedAt) ?: return requote(note = PROMPT_TOO_LATE)
        sign(current, quote, Unlock.WithBiometrics(prompt, notAfter))
    }

    /** Broadcasts the deployment signed but not sent again, byte for byte. */
    fun retry() {
        val current = step.value as? Step.Reviewing ?: return
        val notSent = current.notSent ?: return
        if (current.signing || !notSent.canRetry) return
        step.value = current.copy(signing = true)
        job = viewModelScope.launch {
            val sending = safely { rebroadcastTransfer(notSent.transfer) }
            step.update { latest ->
                when {
                    latest !is Step.Reviewing -> latest
                    sending is TransferSending.Sent -> Step.Sent(sending.hash)
                    sending is TransferSending.NotSent ->
                        latest.copy(signing = false, notSent = NotSent(sending.broadcast, sending.transfer))
                    sending is TransferSending.RejectionUnverified -> latest.copy(
                        signing = false,
                        notSent = NotSent(sending.rejection, sending.transfer, unverified = true),
                    )
                    else -> latest.copy(signing = false)
                }
            }
        }
    }

    /**
     * The screen stopped being shown: the password goes (CLAUDE.md, *Trust*; rule 8), and the
     * review is quoted again when it is shown again ([onShown]). A signature already running
     * finishes, and its outcome is shown when it lands.
     */
    fun onHidden() {
        shown = false
        step.update { current ->
            if (current is Step.Reviewing) {
                current.copy(unlock = current.unlock.copy(password = WalletPassword.EMPTY), hidden = true)
            } else {
                current
            }
        }
    }

    /** The screen is shown (again): a review left while hidden is quoted anew. */
    fun onShown() {
        shown = true
        requoteIfHidden()
    }

    override fun onCleared() {
        // The screen is gone, and the password with it.
        onHidden()
    }

    private fun requoteIfHidden() {
        val current = step.value as? Step.Reviewing ?: return
        if (shown && current.hidden && current.idle) requote(note = null)
    }

    /** Whether [review]'s quote is too old to sign, and so is being made again instead. */
    private fun requoted(review: Step.Reviewing): Boolean {
        // Quoted before the screen was last hidden: never signed as it was.
        if (review.hidden) {
            requote(note = null)
            return true
        }
        val age = review.quotedAt?.elapsedNow() ?: return false
        if (age <= QUOTE_MAX_AGE) return false
        requote(note = STALE_QUOTE)
        return true
    }

    /** Plans the Safe of [model]'s owners and threshold, and quotes its deployment for review. */
    private fun review(model: Model) {
        val owners = model.owners
        val planning =
            planSafe(model.network, owners.map { PlannedOwner.Account(it) }, model.threshold, model.inputs.name)
        // A refusal is the threshold step's own to show; it is drawn there already.
        val plan = (planning as? SafePlanning.Planned)?.plan ?: return
        val payers = model.held
        // None held here any more: the screen says so already (render's CannotCreate, noPayerText), and
        // nothing is planned or quoted until one is.
        if (payers.isEmpty()) return
        // An owner pays when one is held here: the account the user picked first for it.
        val payer = owners.firstOrNull { owner -> payers.any { it.address == owner } } ?: payers.first().address
        val asked = Step.Reviewing(
            plan = plan,
            owners = owners.map { ownerItem(it, model) },
            named = model.typed.filter { it.name != null && it.address in owners },
            payers = payers,
            payer = payer,
            fee = FeeLevel.NORMAL,
            unlock = UnlockStep(),
            quoting = true,
        )
        step.value = asked
        job = viewModelScope.launch { quote(asked, note = null) }
        // Beside the quote, not before it: a change of payer or fee cancels a quote, never this.
        viewModelScope.launch {
            val biometrics = biometricsOffered()
            step.update { latest ->
                if (latest is Step.Reviewing && latest.plan == plan) {
                    latest.copy(unlock = latest.unlock.copy(biometrics = biometrics))
                } else {
                    latest
                }
            }
        }
    }

    /** Quotes the review's deployment (again), saying [note] under the unlock once the quote stands. */
    private fun requote(note: String?) {
        val current = step.value as? Step.Reviewing ?: return
        if (current.signing || current.notSent != null) return
        // A quote in flight is of another payer or fee, or as good as this one: for nothing now.
        job?.cancel()
        val cleared = current.unlock.copy(error = null)
        val asked = current.copy(quoting = true, hidden = false, error = null, simpleError = null, unlock = cleared)
        step.value = asked
        job = viewModelScope.launch { quote(asked, note) }
    }

    /**
     * [asked]'s deployment quoted, into the review when it is still the one asked for. An owner added
     * by its ENS name is resolved again first: one whose name moved, or cannot be verified again, is
     * said on the owners' step, and nothing is quoted for it (as Send resolves its recipient again).
     */
    private suspend fun quote(asked: Step.Reviewing, note: String?) {
        for (owner in asked.named) {
            val moved = movedName(owner, asked.plan.network) ?: continue
            step.update { latest ->
                if (latest is Step.Reviewing && latest.quoting && sameRequest(latest, asked)) {
                    Step.Owners(moved)
                } else {
                    latest
                }
            }
            return
        }
        // Its age runs from its first read, not from its answer: the figures are as old as that.
        val readFrom = timeSource.markNow()
        val quoting = try {
            quoteSafeDeployment(asked.plan, asked.payer, asked.fee)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The use case promises not to throw; the backstop.
            SafeDeploymentQuoting.Refused(SafeDeploymentRefusal.Unavailable(ReadUnavailable.UNPROVEN))
        }
        step.update { latest ->
            if (latest !is Step.Reviewing || !latest.quoting || !sameRequest(latest, asked)) return@update latest
            // The password typed meanwhile stays: it is the same review, hidden since or not.
            when (quoting) {
                is SafeDeploymentQuoting.Quoted -> latest.copy(
                    quote = quoting.quote,
                    quotedAt = readFrom,
                    quoting = false,
                    unlock = latest.unlock.copy(error = note),
                )
                is SafeDeploymentQuoting.Refused -> latest.copy(
                    quote = null,
                    quotedAt = null,
                    quoting = false,
                    error = refusalText(quoting.refusal),
                    simpleError = (quoting.refusal as? SafeDeploymentRefusal.NotQuoted)
                        ?.let { simpleCallRefusalText(it.refusal) },
                )
            }
        }
        // Hidden and shown again while it was made: it is from before, so it is made again.
        requoteIfHidden()
    }

    /**
     * Why [owner]'s name is no longer the address it was added as, resolved again on [network], the
     * plan's, or null when it still is.
     */
    private suspend fun movedName(owner: Typed, network: Network): String? {
        val name = owner.name ?: return null
        val found = RecipientLookup.Found(owner.address, name, owner.resolvedAt)
        val again = resolveAgain(found, { text -> lookUp(network, text) }) { _, unverifiable ->
            if (unverifiable) {
                "“$name” could not be resolved with verified calls again just now, so no Safe is deployed " +
                    "with it as an owner: review it again in a moment."
            } else {
                "“$name” no longer resolves to the address added as an owner, ${Eip55.format(owner.address)}: " +
                    "check the owner, then take it out and add it again."
            }
        }
        return (again as? ResolvedAgain.Changed)?.message
    }

    private fun sameRequest(a: Step.Reviewing, b: Step.Reviewing) =
        a.plan == b.plan && a.payer == b.payer && a.fee == b.fee

    private fun sign(review: Step.Reviewing, quote: SafeDeploymentQuote, unlock: Unlock) {
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        val unlocked = review.unlock.copy(password = WalletPassword.EMPTY, error = null)
        step.value = review.copy(signing = true, unlock = unlocked)
        job = viewModelScope.launch {
            val sending = safely { deploySafe(quote, unlock) }
            step.update { latest ->
                if (latest !is Step.Reviewing) return@update latest
                when (sending) {
                    is TransferSending.Sent -> Step.Sent(sending.hash)
                    is TransferSending.NotSigned -> latest.copy(
                        signing = false,
                        unlock = latest.unlock.copy(
                            error = notSignedText(sending.signing, DEPLOY_FAILED),
                            biometrics = latest.unlock.biometrics && biometricsStillOffered(sending.signing),
                        ),
                    )
                    is TransferSending.NotSent ->
                        latest.copy(signing = false, notSent = NotSent(sending.broadcast, sending.transfer))
                    is TransferSending.RejectionUnverified -> latest.copy(
                        signing = false,
                        notSent = NotSent(sending.rejection, sending.transfer, unverified = true),
                    )
                    null -> latest.copy(signing = false, unlock = latest.unlock.copy(error = DEPLOY_FAILED))
                }
            }
            // The prompt outlived the quote's age: nothing was signed, and the quote is made again.
            if ((sending as? TransferSending.NotSigned)?.signing == Signing.TimedOut) requote(note = PROMPT_TIMED_OUT)
            // Hidden while it ran and nothing was sent: the quote is from before, so it is made again.
            requoteIfHidden()
        }
    }

    /**
     * [send], to its end even when the screen is left meanwhile: a deployment signed is recorded and
     * broadcast as one step, which leaving cannot cut in half.
     */
    private suspend fun safely(send: suspend () -> TransferSending): TransferSending? =
        withContext(NonCancellable) {
            try {
                send()
            } catch (e: Exception) {
                null // the ports promise not to throw; the backstop
            }
        }

    /** Applies [edit] to the first two steps' inputs: only before the review. */
    private fun changeOwners(edit: (Inputs) -> Inputs) {
        val current = step.value
        if (current !is Step.Owners && current != Step.Threshold) return
        inputs.update(edit)
        // A change of the owners answers what the last review went back for.
        if (current is Step.Owners && current.error != null) step.value = Step.Owners()
    }

    /** The owner typed, once it is an address the lookup found for the text as it is now. */
    private fun foundInput(model: Model): RecipientLookup.Found? {
        val lookup = model.lookup as? RecipientState.Done ?: return null
        if (lookup.text != model.inputs.input.trim()) return null
        return lookup.result as? RecipientLookup.Found
    }

    private fun ownersRefusal(model: Model): SafePlanRefusal? =
        planSafe.ownersRefusal(model.network, model.owners.map { PlannedOwner.Account(it) })

    private fun render(model: Model?, step: Step): CreateSafeUiState {
        val network = network.displayName
        if (step is Step.Sent) return CreateSafeUiState.Sent(network, step.hash)
        // A review stands on its plan and its payers, whatever the list says meanwhile: a refresh or
        // the signature checks the chain again, and says what it finds.
        if (step is Step.Reviewing) return review(step)
        model ?: return CreateSafeUiState.Loading(network)
        val chosen = model.network.displayName
        when {
            model.list is AccountList.Unreadable -> return CreateSafeUiState.CannotCreate(chosen, LIST_UNAVAILABLE)
            model.network !in model.on ->
                return CreateSafeUiState.CannotCreate(chosen, offText(model.network), chips(model))
            !planSafe.hasRelease(model.network) ->
                return CreateSafeUiState.CannotCreate(chosen, NO_RELEASE, chips(model))
            model.held.isEmpty() -> return CreateSafeUiState.CannotCreate(chosen, noPayerText(model), chips(model))
        }
        return when (step) {
            is Step.Owners -> owners(model, step.error)
            else -> threshold(model)
        }
    }

    private fun owners(model: Model, error: String?): CreateSafeUiState.Owners {
        val inputs = model.inputs
        val text = inputs.input.trim()
        val lookup = model.lookup.takeIf { it.text == text }
        val found = foundInput(model)
        val (note, isError) = when {
            found != null && found.address in model.owners -> "Already one of the owners." to false
            (lookup as? RecipientState.Done)?.result == RecipientLookup.ZeroAddress ->
                "The zero address: nobody holds its key, so it cannot own a Safe." to true
            else -> recipientNote(text, lookup)
        }
        val owners = model.owners
        return CreateSafeUiState.Owners(
            network = model.network.displayName,
            networks = chips(model),
            name = inputs.name,
            accounts = model.eoas.map { account ->
                OwnerChoice(
                    address = Eip55.format(account.address),
                    label = account.label,
                    note = if (account.custody == AccountCustody.InKeyStore) KEY_HERE else WATCHED,
                    selected = account.address in model.selected,
                )
            },
            typed = model.typed.map { typed ->
                    OwnerChoice(
                        address = Eip55.format(typed.address),
                        label = typed.name ?: model.accounts.firstOrNull { it.address == typed.address }?.label,
                        note = typedNote(typed, model),
                        selected = true,
                        simpleNote = simpleTypedNote(typed, model),
                    )
                },
            input = inputs.input,
            summary = when (owners.size) {
                0 -> "No owner chosen yet"
                1 -> "1 owner"
                else -> "${owners.size} owners"
            },
            deviceKeyNote = DEVICE_KEY,
            nameError = nameError(inputs.name),
            inputOwner = found?.let {
                OwnerChoice(
                    address = Eip55.format(it.address),
                    label = it.name ?: model.accounts.firstOrNull { account -> account.address == it.address }?.label,
                    note = it.blockNumber?.let { block -> "Resolved at finalized block ${grouped(block)}" } ?: TYPED,
                    selected = it.address in owners,
                    simpleNote = it.blockNumber?.let { RESOLVED_ON_CHAIN },
                )
            },
            inputNote = note,
            inputError = isError,
            inputLabel = addressFieldLabel(model.network),
            canRetryLookup = (lookup as? RecipientState.Done)?.result is RecipientLookup.Unavailable,
            retryLookupWaits = retryLookupWaits((lookup as? RecipientState.Done)?.result),
            canAdd = found != null && found.address !in owners,
            error = error ?: if (owners.isEmpty()) null else ownersRefusal(model)?.let(::planRefusalText),
            canContinue = owners.isNotEmpty() && ownersRefusal(model) == null && nameError(inputs.name) == null,
        )
    }

    private fun threshold(model: Model): CreateSafeUiState.Threshold {
        val owners = model.owners
        val count = owners.size
        val threshold = model.threshold
        val planned = owners.map { PlannedOwner.Account(it) }
        val refusal = planSafe.ownersRefusal(model.network, planned) ?: planSafe.shapeRefusal(planned, threshold)
        return CreateSafeUiState.Threshold(
            network = model.network.displayName,
            owners = owners.map { ownerItem(it, model) },
            threshold = threshold,
            ownerCount = count,
            shape = shape(threshold, count),
            explanation = if (refusal == null) explanation(threshold, count) else null,
            rules = RULES,
            canDecrease = threshold > 1,
            canIncrease = threshold < count,
            refusal = refusal?.let(::planRefusalText),
            canContinue = refusal == null && nameError(model.inputs.name) == null,
        )
    }

    private fun review(step: Step.Reviewing): CreateSafeUiState.Review {
        val plan = step.plan
        val quote = step.quote
        val native = plan.network.nativeSymbol
        val payerLabel = step.payers.firstOrNull { it.address == step.payer }?.label
        val computed = "Computed here (CREATE2) from the factory, the singleton, the owners, the threshold and a salt."
        return CreateSafeUiState.Review(
            network = plan.network.displayName,
            name = plan.label.ifEmpty { "No name: it is listed as the next account number" },
            address = Eip55.format(plan.address),
            signers = "${plan.threshold}/${plan.owners.size}",
            version = "Safe ${plan.release.version}",
            simplePrediction = quote?.let {
                "$computed The factory's run, checked on chain, returns this address, which held no code when " +
                    "it was checked."
            },
            prediction = when {
                quote != null -> {
                    val ran = quote.call.simulationBlock?.let { "at block ${grouped(it)}" } ?: "at the head"
                    "$computed The factory's verified run $ran returns this address, which held no code at " +
                        "block ${grouped(quote.codelessAt)}."
                }
                step.quoting -> "$computed Checking it against the factory's verified run…"
                else -> "$computed The factory's verified run has not confirmed it, so nothing can be signed."
            },
            owners = step.owners,
            shape = shape(plan.threshold, plan.owners.size),
            payers = step.payers.map { AccountChoice(it.label, Eip55.format(it.address)) },
            payer = Eip55.format(step.payer),
            fee = step.fee,
            unlock = step.unlock,
            quote = quote?.let {
                val call = it.call
                val ran = call.simulationBlock?.let { block -> "at block ${grouped(block)}" } ?: "at the head"
                DeploymentQuoteItem(
                    maxFee = exactAmount(call.maxFee, NATIVE_DECIMALS, native),
                    feeDetail = feeDetail(call.level, call.transaction, call.feeBlock),
                    balance = BalanceChange(
                        what = "$native balance of ${payerLabel ?: "the account that pays"}",
                        before = exactAmount(call.nativeBefore, NATIVE_DECIMALS, native),
                        after = "at least " + exactAmount(call.nativeAfterAtLeast, NATIVE_DECIMALS, native),
                        verifiedAt = verifiedAt(call.balanceBlock),
                    ),
                    simulation = "Run as a verified call from this account $ran, the factory deploys the Safe at " +
                        "this address and sets it up with these owners and this threshold.",
                    simpleSimulation = "Checked on chain: the factory should deploy the Safe at this address and " +
                        "set it up with these owners and this threshold.",
                    pending = call.pendingCost.takeUnless { cost -> cost.isZero }?.let { cost ->
                        "Earlier transactions from this account, not mined yet, may still take up to " +
                            exactAmount(cost, NATIVE_DECIMALS, native) + ": the balance after counts it."
                    },
                )
            },
            error = step.error,
            simpleError = step.simpleError,
            quoting = step.quoting,
            signing = step.signing,
            notSent = step.notSent?.let {
                notSentItem(it.broadcast, it.unverified, what = "deployment", maybeSent = it.transfer.maybeSent)
            },
        )
    }

    private fun ownerItem(owner: Address, model: Model): OwnerItem {
        val account = model.accounts.firstOrNull { it.address == owner }
        val typed = model.inputs.typed.firstOrNull { it.address == owner }
        return OwnerItem(
            address = Eip55.format(owner),
            label = account?.label ?: typed?.name,
            note = when {
                account?.custody == AccountCustody.InKeyStore -> KEY_HERE
                account != null -> WATCHED
                typed != null -> typedNote(typed, model)
                else -> TYPED
            },
            simpleNote = typed?.takeIf { account == null }?.let { simpleTypedNote(it, model) },
        )
    }

    private fun typedNote(typed: Typed, model: Model): String = when {
        typed.name != null -> "Resolved from ${typed.name}" +
            (typed.resolvedAt?.let { " at finalized block ${grouped(it)}" } ?: "")
        model.accounts.any { it.address == typed.address } -> "In your wallet"
        else -> TYPED
    }

    /** [typedNote] in simple mode's words, with no block (docs/simple-ui.md); null where it names none. */
    private fun simpleTypedNote(typed: Typed, model: Model): String? =
        typed.takeIf { it.name != null && it.resolvedAt != null && typedNote(it, model) != TYPED }
            ?.let { "Resolved from ${it.name}, checked on chain" }

    private fun nameError(name: String): String? =
        if (name.trim().length > RenameAccount.MAX_LABEL_LENGTH) {
            "At most ${RenameAccount.MAX_LABEL_LENGTH} characters."
        } else {
            null
        }

    private fun shape(threshold: Int, owners: Int): String = when {
        owners == 0 -> "No owner chosen"
        owners == 1 -> "1 of 1: its one owner signs each transaction"
        else -> "$threshold of $owners owners sign each transaction"
    }

    private fun explanation(threshold: Int, owners: Int): String = when {
        owners == 1 -> "The owner's own backup is the Safe's: for an account of this wallet, its recovery phrase."
        threshold == 1 -> "Any one owner signs alone, and if an owner's key is lost, any other one can replace it."
        else -> {
            val spare = owners - threshold
            "With up to $spare ${if (spare == 1) "owner" else "owners"} lost, the others still reach the " +
                "threshold, and can replace the lost ones."
        }
    }

    private fun planRefusalText(refusal: SafePlanRefusal): String = when (refusal) {
        SafePlanRefusal.NoRelease -> NO_RELEASE
        SafePlanRefusal.NoOwners -> "Choose at least one owner."
        is SafePlanRefusal.TooManyOwners -> "At most ${refusal.max} owners."
        is SafePlanRefusal.ReservedAddress -> "${Eip55.format(refusal.address)} cannot own a Safe: the Safe " +
            "reserves it for its own lists."
        is SafePlanRefusal.DuplicateOwner -> "${Eip55.format(refusal.address)} is an owner twice."
        SafePlanRefusal.DeviceKeyAlone ->
            "A device's hardware key is never the only owner: it has no backup. Add a recovery owner."
        is SafePlanRefusal.ThresholdOutOfRange -> "The threshold is between 1 and the number of owners."
        is SafePlanRefusal.NeedsRecoveryModule -> "Not ${refusal.owners} of ${refusal.owners}: with one owner " +
            "lost, the others could never reach the threshold again, not even to replace the lost one. That " +
            "takes a recovery module beside it, which Serotine does not offer yet. Lower the threshold, or add " +
            "an owner."
        SafePlanRefusal.NameTooLong -> "At most ${RenameAccount.MAX_LABEL_LENGTH} characters."
    }

    private fun refusalText(refusal: SafeDeploymentRefusal): String = when (refusal) {
        is SafeDeploymentRefusal.AlreadyDeployed -> if (refusal.kind is AddressKind.Safe) {
            "A Safe exists at this address already, so a deployment there would fail. Go back and continue again " +
                "for another address."
        } else {
            "This address holds code already, so a deployment there would fail. Go back and continue again for " +
                "another address."
        }
        is SafeDeploymentRefusal.SameSafeSent ->
            "This wallet sent the deployment of a Safe with these owners and this threshold already, at " +
                "${Eip55.format(refusal.safe)}, and no block has it yet: it would be a second Safe of the same " +
                "shape. Wait for it, or change the owners or the threshold."
        SafeDeploymentRefusal.AlreadySent ->
            "This wallet sent this deployment already, and no block has it yet: sending it again would only pay " +
                "a fee to revert."
        is SafeDeploymentRefusal.AddressMismatch -> {
            val returned = refusal.returned?.let { "returns ${Eip55.format(it)}" } ?: "returns no address"
            "The factory's verified run $returned, not the address computed here: the Safe release Serotine " +
                "pins does not match the chain, so nothing is deployed."
        }
        SafeDeploymentRefusal.Syncing -> "The light client is not synced yet: try again in a moment."
        is SafeDeploymentRefusal.Unavailable -> unavailableText(refusal.reason)
        is SafeDeploymentRefusal.NotQuoted -> callRefusalText(refusal.refusal, network)
    }

    /**
     * The network a Safe is created on until the user picks one: the first that is on with an
     * account whose key is held here to pay, else the first that is on, else the first there is.
     */
    private fun defaultNetwork(list: AccountList, on: Set<Network>): Network {
        val accounts = (list as? AccountList.Loaded)?.accounts.orEmpty()
        val payable = Network.entries.filter { network ->
            network in on && accounts.any { it.network == network && it.isHeldEoa() }
        }
        return payable.firstOrNull() ?: Network.entries.firstOrNull { it in on } ?: Network.entries.first()
    }

    private fun Account.isHeldEoa() = kind == AccountKind.EOA && custody == AccountCustody.InKeyStore

    /** Each network as the first choice (N12), greyed out with the reason where a Safe cannot be created. */
    private fun chips(model: Model): List<NetworkChip> = Network.entries.map { network ->
        val reason = when {
            network !in model.on -> offText(network)
            !planSafe.hasRelease(network) -> NO_RELEASE
            model.accounts.none { it.network == network && it.isHeldEoa() } -> noPayerText(model, network)
            else -> null
        }
        NetworkChip(NetworkLabel.of(network), network == model.network, reason, off = network !in model.on)
    }

    private fun offText(network: Network) =
        "${network.displayName} is off in this wallet. Turn it on to create a Safe there."

    /**
     * Why no account can pay on [network]: none whose key is held here on it; and which would do,
     * the wallet's held accounts on other networks, added there from their menu.
     */
    private fun noPayerText(model: Model, network: Network = model.network): String {
        val elsewhere = model.accounts.filter { it.network != network && it.isHeldEoa() }
            .map { it.label }
            .distinct()
        val name = network.displayName
        val would = if (elsewhere.isEmpty()) {
            "Import or create an account on it first, and fund it."
        } else {
            "Add ${elsewhere.joinToString(" or ")} on $name (Add on another network, in its menu), or import or " +
                "create an account on it, and fund it."
        }
        return "Creating a Safe on $name needs an account whose key is on this device there, to pay for its " +
            "deployment. $would"
    }

    /** [text] resolved on [network]: the one chosen on the owners' step, the plan's on a review. */
    private suspend fun lookUp(network: Network, text: String): RecipientLookup = try {
        resolveRecipient(network, recipientText(text))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RecipientLookup.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
    }

    /**
     * Asks for the fee history once, now, and lets the answer go ([WarmFeeHistory]): the light
     * client builds it on a first request in seconds, so by the review it is ready. Never throws.
     */
    private fun warmFees() {
        viewModelScope.launch {
            try {
                warmFeeHistory(network)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing rests on it: the quote asks for the fees itself.
            }
        }
    }

    /** Whether the biometric prompt is on for this wallet ([DescribeKeyStore]); never throws. */
    private suspend fun biometricsOffered(): Boolean = try {
        biometricsOn(describeKeyStore())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false // the use case promises not to throw; the backstop: the password still works
    }

    private companion object {
        const val KEY_HERE = "Key on this device"
        const val WATCHED = "Watch-only: its key is elsewhere"
        const val TYPED = "Typed"

        /** A name's resolution in simple mode's words: no block (docs/simple-ui.md). */
        const val RESOLVED_ON_CHAIN = "Resolved, checked on chain"
        const val DEVICE_KEY =
            "This device's hardware key (Secure Enclave or StrongBox) as an owner comes later: Serotine does not " +
                "build its passkey signer yet."
        const val DEPLOY_FAILED =
            "Serotine could not finish this deployment. Check the paying account's balance before you deploy again."
        const val LIST_UNAVAILABLE =
            "Your account list cannot be read right now, so no Safe can be created. Serotine keeps trying."
        const val NO_RELEASE = "Serotine pins no Safe release to create a Safe from on this network."

        /** The recovery shapes the wallet creates (docs/keys-and-signing.md, *Backup and recovery*). */
        val RULES = listOf(
            "Each transaction of the Safe needs this many of its owners' signatures.",
            "A device's hardware key is never the only owner: it has no backup.",
            "Every owner signing (2 of 2, 3 of 3, …) takes a recovery module beside it, which Serotine does not " +
                "offer yet: with one owner lost, the others could never reach the threshold again.",
        )

        /** The threshold first offered for [owners]: one up to two owners, then a majority short of all of them. */
        fun defaultThreshold(owners: Int): Int = if (owners <= 2) 1 else owners / 2 + 1
    }
}

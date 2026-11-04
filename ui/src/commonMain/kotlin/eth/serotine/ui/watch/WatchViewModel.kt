package eth.serotine.ui.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReadUnavailable.SYNCING
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.usecase.AddressWatch
import eth.serotine.domain.usecase.HoldNetwork
import eth.serotine.domain.usecase.NetworkHold
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveAddressKind
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.RecipientLookup
import eth.serotine.domain.usecase.RenameAccount
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.WatchAddress
import eth.serotine.domain.usecase.control
import eth.serotine.domain.usecase.ownersHeld
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.RecipientState
import eth.serotine.ui.components.ResolvedAgain
import eth.serotine.ui.components.addressFieldLabel
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.recipientNote
import eth.serotine.ui.components.recipientStates
import eth.serotine.ui.components.recipientText
import eth.serotine.ui.components.resolveAgain
import eth.serotine.ui.components.retryLookupWaits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Watch address screen's ViewModel: reads the typed text as an address (EIP-55 checked at
 * the input) or an ENS name, resolved as Send's recipient is ([ResolveRecipient], against
 * finalized state), shows what the light client proves is at the address, and adds it through
 * [WatchAddress], which checks again before it adds. A name is resolved again at Add: what is
 * watched is the address it has then, and one that moved is said, not added (issue #71). Added
 * by a name and given none of the user's, the account's name follows that ENS name (issue #67).
 *
 * The address is checked on every network that is on ([ObserveEnabledNetworks]), each held
 * running while the screen is open ([HoldNetwork]): one card where one network has an answer; with
 * more, a row each, and an address with no code on all of them offered on all of them, or on those
 * ticked (docs/networks.md, N10 and N11). A name is resolved on every registry that is on, Ethereum's
 * and Sepolia's, each network's account at its own registry's address, and Gnosis Chain's at
 * Ethereum's, never a testnet's; each answer says which registry gave it (docs/networks.md, *Names*).
 */
class WatchViewModel(
    observeAddressKind: ObserveAddressKind,
    observeAccounts: ObserveAccounts,
    private val watchAddress: WatchAddress,
    private val resolveRecipient: ResolveRecipient,
    observeEnabledNetworks: ObserveEnabledNetworks,
    private val setNetworkEnabled: SetNetworkEnabled,
    /** Keeps each network that is on running while this screen is open, with no account on it yet. */
    private val holdNetwork: HoldNetwork,
) : ViewModel() {

    /** The networks on in this wallet, in `Network` order; null until read, so none is said to be off before. */
    private val on: StateFlow<List<Network>?> =
        observeEnabledNetworks().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** A hold on each network that is on, released when it goes off or the screen is gone ([onCleared]). */
    private val holds = mutableMapOf<Network, NetworkHold>()

    init {
        viewModelScope.launch {
            on.collect { read ->
                val networks = read ?: return@collect
                (holds.keys - networks.toSet()).forEach { holds.remove(it)?.release() }
                networks.filter { it !in holds }.forEach { holds[it] = holdNetwork(it) }
            }
        }
    }

    override fun onCleared() {
        holds.values.forEach { it.release() }
        holds.clear()
    }

    /** What is typed, and how often each registry was asked to resolve the name again ([lookupAttempts]). */
    private data class Typed(
        val address: String = "",
        val name: String = "",
        val lookupAttempts: Map<Network, Int> = emptyMap(),
    )

    private class Outcome(
        val adding: Boolean = false,
        val nameError: String? = null,
        val error: String? = null,
        val done: Boolean = false,
    )

    /** The networks ticked (null: those offered, until the user ticks one), and whether they are being chosen. */
    private data class Choice(val chosen: Set<Network>? = null, val choosing: Boolean = false)

    private val choice = MutableStateFlow(Choice())

    private val typed = MutableStateFlow(Typed())
    private val outcome = MutableStateFlow(Outcome())

    /**
     * The typed text resolved on each registry that is on ([registries]): an address at once, a name
     * once the typing paused, and anew when the registries change (one turned on from this screen),
     * so that one registry's answer is never taken for another network's. Each is asked again on its
     * own, so that an answer one registry gave stands while another is asked again.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val lookups: StateFlow<Map<Network, RecipientState>> = on.map { it?.let(::registries).orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { registries ->
            if (registries.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    registries.map { registry ->
                        recipientStates(typed.map { it.address.trim() to it.lookupAttempts[registry] }) { text ->
                            lookUp(registry, text)
                        }.map { registry to it }
                    },
                ) { it.toMap() }
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            emptyMap(),
        )

    /**
     * The network whose light client the strip or the sync line shows: of the registries a name is
     * resolved on, the first whose answer waits for its light client to sync, else the first; with
     * none, the first network that is on.
     */
    val stripNetwork: StateFlow<Network> = combine(on, lookups, typed) { read, lookups, typed ->
        val registries = read?.let(::registries).orEmpty()
        val text = typed.address.trim()
        registries.firstOrNull { registry ->
            val state = lookups[registry]?.takeIf { it.text == text } as? RecipientState.Done
            (state?.result as? RecipientLookup.Unavailable)?.reason == SYNCING
        } ?: registries.firstOrNull() ?: Network.entries.first()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), Network.entries.first())

    /**
     * The cards of what each network that is on would watch ([answerOn]), each read at its own
     * address: uiState shows them only for the text typed now. A network with no answer has no card.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val detection: Flow<List<Card>?> =
        combine(typed.map { it.address.trim() }.distinctUntilChanged(), lookups, on) { text, lookups, read ->
            read?.mapNotNull { network -> answerOn(network, text, lookups)?.let { network to it.second.address } }
        }
            .distinctUntilChanged()
            .flatMapLatest { found ->
                if (found.isNullOrEmpty()) {
                    flowOf(null)
                } else {
                    // The one list says whether an address is in the wallet already, and, for a Safe,
                    // which of its owners' keys are held here.
                    combine(
                        observeAccounts(),
                        combine(
                            found.map { (network, address) ->
                                observeAddressKind(network, address)
                                    .map<KindReading, KindReading?> { it }
                                    .onStart { emit(null) }
                            },
                        ) { it.toList() },
                    ) { list, readings ->
                        found.zip(readings) { (network, address), reading ->
                            Card(network, address, detectionOf(network, address, list, reading))
                        }
                    }
                }
            }

    /** [network]'s card: what is at [address] there. */
    private data class Card(val network: Network, val address: Address, val detection: Detection)

    val uiState: StateFlow<WatchUiState> = combine(
        combine(typed, choice, ::Pair),
        lookups,
        detection,
        outcome,
        on,
    ) { (typed, choice), lookups, cards, outcome, on ->
        val text = typed.address.trim()
        // The lookups of what is typed now; one of an earlier text is no answer about it.
        val results = lookups.filterValues { it.text == text }
            .mapNotNull { (registry, state) -> (state as? RecipientState.Done)?.let { registry to it.result } }
        val found = results.firstNotNullOfOrNull { it.second as? RecipientLookup.Found }
        val unavailable = results.mapNotNull { it.second as? RecipientLookup.Unavailable }
        val (note, isError) = addressNote(text, lookups, on.orEmpty())
        WatchUiState(
            address = typed.address,
            name = typed.name,
            addressError = note.takeIf { isError },
            addressNote = note.takeUnless { isError },
            simpleAddressNote = addressNote(text, lookups, on.orEmpty(), simple = true).first
                .takeIf { !isError && results.any { (it.second as? RecipientLookup.Found)?.blockNumber != null } },
            canRetryLookup = unavailable.isNotEmpty(),
            // Greyed out only while every answer missing waits for a light client still syncing.
            retryLookupWaits = unavailable.takeIf { it.isNotEmpty() && it.all { u -> u.reason == SYNCING } }
                ?.let { retryLookupWaits(it.first()) },
            // The name an empty field leaves the account with: the ENS name, when it is one to follow.
            nameDefault = found?.name?.takeIf { it.length <= RenameAccount.MAX_LABEL_LENGTH },
            adding = outcome.adding,
            nameError = outcome.nameError,
            error = outcome.error,
            done = outcome.done,
            noNetwork = NO_NETWORK.takeIf { on?.isEmpty() == true },
            // Names only where a network that is on resolves them; Gnosis Chain's alone takes addresses.
            addressLabel = on?.let(::registries)?.firstOrNull()?.let(::addressFieldLabel) ?: "Address or ENS name",
            addressHint = on?.let(::registries)?.firstOrNull()?.takeUnless { it.ensNames }?.let {
                "0x and 40 hexadecimal digits: names are not resolved on ${it.displayName} yet"
            } ?: "0x and 40 hexadecimal digits, or an ENS name like name.eth",
            offNetworks = on?.let { read -> Network.entries.filter { it !in read }.map(NetworkLabel::of) }.orEmpty(),
        ).withCards(
            // Only the cards of what is typed now: none while a new text is still being resolved,
            // nor those of the addresses before for a moment after the text changed.
            // Nor while a registry is still resolving it: one registry's card is not all there is to add.
            cards?.takeIf { cards ->
                lookups.values.all { it.text == text && it is RecipientState.Done } &&
                    cards.map { it.network to it.address } ==
                    on.orEmpty().mapNotNull { n -> answerOn(n, text, lookups)?.let { n to it.second.address } }
            },
            choice,
        )
    }.stateIn(
        viewModelScope,
        // The last state is kept (it holds what the user typed); on a return the card starts
        // again from "Checking", so no old verdict stands as current.
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        WatchUiState(),
    )

    /**
     * The cards of each network ([found]): the one network's as [WatchUiState.detection], or, with
     * more, a row each, with the all-networks question for an address with no code on every one.
     * Where the registries gave different addresses, no row is ticked until the user ticks it.
     */
    private fun WatchUiState.withCards(found: List<Card>?, choice: Choice): WatchUiState {
        if (found == null) return this
        if (found.size == 1) {
            val card = found.single()
            return copy(detection = card.detection, cardNetwork = NetworkLabel.of(card.network))
        }
        val cards = found.map { it.network to it.detection }
        // Where the registries gave different addresses, they are different accounts: no question for them.
        val oneAddress = found.map { it.address }.distinct().size == 1
        // A Safe exists only where it was deployed: where it is one, it is offered there alone (N11).
        val safeSomewhere = cards.any { (_, card) -> card is Detection.Safe }
        val offered = cards.filter { (_, card) -> if (safeSomewhere) card is Detection.Safe else card.addable }
            .map { it.first }
            .toSet()
        // Different accounts are each the user's choice: none ticked until the user ticks it.
        val chosen = choice.chosen ?: offered.takeIf { oneAddress }.orEmpty()
        val everyAccount = cards.isNotEmpty() && cards.all { (_, card) -> card is Detection.Account }
        // A delegated account (EIP-7702) has code: its delegation designation.
        val delegated = cards.any { (_, card) -> (card as? Detection.Account)?.delegate != null }
        return copy(
            networks = cards.map { (network, card) ->
                WatchNetwork(
                    NetworkLabel.of(network),
                    card,
                    offered = network in offered,
                    chosen = network in chosen && network in offered,
                )
            },
            question = if (everyAccount && oneAddress) {
                val what = if (delegated) "is an account a key controls" else "has no code"
                "This address $what. Watch it on ${networkNames(cards.map { it.first })}?"
            } else {
                null
            },
            choosing = choice.choosing,
        )
    }

    /** "Ethereum and Gnosis Chain", or "all three networks". */
    private fun networkNames(networks: List<Network>): String = when (networks.size) {
        3 -> "all three networks"
        else -> networks.map { it.displayName }.let { names ->
            if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString() + " and " + names.last()
        }
    }

    /** Turns the network of [chainId] on: the address is then checked there too. */
    fun onTurnOnNetwork(chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        viewModelScope.launch { setNetworkEnabled(network, on = true) }
    }

    /** Shows the networks as check boxes instead of the all-networks question (N10). */
    fun onChooseNetworks() {
        if (outcome.value.adding || outcome.value.done) return
        choice.update { it.copy(choosing = true) }
    }

    /** Ticks the network of [chainId], or unticks it. */
    fun onNetworkToggle(chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        if (outcome.value.adding || outcome.value.done) return
        // Until the user ticks one, the ticks are those shown: every network offered, or none.
        val shown = uiState.value.networks.filter { it.chosen }
            .mapNotNull { Network.ofChainId(it.network.chainId) }
        choice.update { current ->
            val chosen = current.chosen ?: shown.toSet()
            current.copy(chosen = if (network in chosen) chosen - network else chosen + network)
        }
    }

    /** The address field changed; ignored while an add runs, whose result is about what was typed then. */
    fun onAddressChange(text: String) {
        if (outcome.value.adding || outcome.value.done) return
        typed.update { it.copy(address = text) }
        choice.value = Choice()
        outcome.value = Outcome()
    }

    /** The name field changed; ignored while an add runs. */
    fun onNameChange(text: String) {
        if (outcome.value.adding || outcome.value.done) return
        typed.update { it.copy(name = text) }
        outcome.value = Outcome()
    }

    /**
     * Resolves the typed name again on each registry whose lookup could not be verified; an address
     * another registry gave stands meanwhile.
     */
    fun onRetryLookup() {
        if (outcome.value.adding || outcome.value.done) return
        val text = typed.value.address.trim()
        val unanswered = lookups.value.filterValues { state ->
            state.text == text && (state as? RecipientState.Done)?.result is RecipientLookup.Unavailable
        }.keys
        lookUpAgain(unanswered.ifEmpty { lookups.value.keys })
        outcome.value = Outcome()
    }

    /** Asks each of [registries] to resolve the typed text again. */
    private fun lookUpAgain(registries: Collection<Network>) = typed.update { current ->
        val attempts = current.lookupAttempts
        current.copy(lookupAttempts = attempts + registries.associateWith { (attempts[it] ?: 0) + 1 })
    }

    /**
     * Adds the address, once, on each network at its own registry's address: a name is resolved
     * again first, on each registry it came from, and [WatchAddress] proves the address's kind again.
     */
    fun add() {
        // outcome first: it is set synchronously below, while uiState follows it a step later,
        // so a second tap before that must still find the add running.
        val state = uiState.value
        if (outcome.value.adding || outcome.value.done || !state.canAdd) return
        // The networks to add it on: the one on; or all offered, as the question asks; or those ticked.
        val networks = when {
            // The network the card was read on, never one that is on by now instead.
            state.networks.isEmpty() -> listOfNotNull(state.cardNetwork?.let { Network.ofChainId(it.chainId) })
            state.question != null && !state.choosing -> state.networks.filter { it.offered }
                .mapNotNull { Network.ofChainId(it.network.chainId) }
            else -> state.networks.filter { it.offered && it.chosen }
                .mapNotNull { Network.ofChainId(it.network.chainId) }
        }
        if (networks.isEmpty()) return
        val text = typed.value.address.trim()
        val lookupsNow = lookups.value
        // Each network's answer as the card showed it: one without an answer now is not added.
        val answers = networks.map { network -> network to (answerOn(network, text, lookupsNow) ?: return) }
        outcome.value = Outcome(adding = true)
        viewModelScope.launch {
            // Each registry asked again once: what is watched is the address the name has there now.
            val again = mutableMapOf<Network, RecipientLookup.Found>()
            val targets = answers.map { (network, answer) ->
                val (registry, found) = answer
                val resolved = again[registry]
                    ?: when (val checked = resolveAgain(found, { lookUp(registry, it) }, ::nameChanged)) {
                        is ResolvedAgain.Same -> checked.found.also { again[registry] = it }
                        is ResolvedAgain.Changed -> {
                            // Resolved again for the card, which then shows what the name has now.
                            lookUpAgain(listOf(registry))
                            outcome.value = Outcome(error = checked.message)
                            return@launch
                        }
                    }
                network to resolved
            }
            outcome.value = watchOn(targets)
        }
    }

    /**
     * Watches each of [targets], a network and the address resolved for it, stopping at the first
     * that cannot be added, whose reason is said, with the networks it was added on before it.
     */
    private suspend fun watchOn(targets: List<Pair<Network, RecipientLookup.Found>>): Outcome {
        // One name on every network, as an import gives (docs/networks.md, N6 and N8): the user's,
        // the ENS name followed, or one "Account N" taken once.
        val name = watchAddress.sharedName(typed.value.name, targets.firstNotNullOfOrNull { it.second.name })
            ?: return Outcome(error = "The account could not be saved. Nothing was added.")
        val added = mutableListOf<Network>()
        for ((network, resolved) in targets) {
            // Where names are not resolved, WatchAddress makes the name only the account's label.
            val result = watchAddress(network, resolved.address, name, resolved.name)
            if (result is AddressWatch.Added) {
                added += network
                continue
            }
            if (result == AddressWatch.NameTooLong) {
                return Outcome(nameError = "Use at most ${RenameAccount.MAX_LABEL_LENGTH} characters")
            }
            val why = when (result) {
                is AddressWatch.AlreadyInWallet -> "Already in your wallet as “${result.account.label}”."
                AddressWatch.Contract -> CONTRACT
                is AddressWatch.NotVerified -> "The address cannot be checked right now."
                else -> "The account could not be saved."
            }
            return Outcome(
                error = if (added.isEmpty()) {
                    // In the wallet already says itself that nothing changed.
                    if (result is AddressWatch.AlreadyInWallet) why else "$why Nothing was added."
                } else {
                    "Added on ${added.joinToString { it.displayName }}, but not on ${network.displayName}: $why"
                },
            )
        }
        return Outcome(done = true)
    }

    private fun detectionOf(
        network: Network,
        address: Address,
        list: AccountList,
        reading: KindReading?,
    ): Detection {
        // In the wallet on this network: the same address on another network is another account.
        val accounts = (list as? AccountList.Loaded)?.accounts.orEmpty().filter { it.network == network }
        return detectionOf(address, list, accounts.firstOrNull { it.address == address }, reading, accounts)
    }

    private fun detectionOf(
        address: Address,
        list: AccountList,
        inWallet: Account?,
        reading: KindReading?,
        accounts: List<Account>,
    ): Detection = when {
        inWallet != null -> Detection.InWallet(inWallet.label)
        list is AccountList.Unreadable ->
            Detection.Unavailable("Your account list cannot be read right now, so nothing can be added.")
        reading == null -> Detection.Checking
        reading is KindReading.Syncing ->
            Detection.Unavailable("The light client is not synced yet. The address is checked as soon as it is.")
        reading is KindReading.Unavailable -> Detection.Unavailable(
            when (reading.reason) {
                ReadUnavailable.NO_ENGINE ->
                    "There is no light client on this device, so the address cannot be checked."
                ReadUnavailable.NETWORK_OFF ->
                    "The network's light client is not running, so the address cannot be checked."
                ReadUnavailable.STALE_ANCHOR ->
                    "The light client refuses to sync, so the address cannot be checked."
                // Not the code's fault alone: for a Safe, its code may be proven and its storage
                // (owners, threshold) not, and the reading does not tell the two apart.
                ReadUnavailable.SYNCING ->
                    "The light client is still syncing, so what is at this address cannot be checked yet."
                ReadUnavailable.UNPROVEN -> "What is at this address could not be verified this time: its code, or " +
                    "for a Safe its owners and threshold. Serotine keeps trying."
                ReadUnavailable.REFUSED -> "The light client on this device cannot check this address."
            },
        )
        else -> {
            val verified = reading as KindReading.Verified
            when (val kind = verified.kind) {
                AddressKind.Eoa ->
                    Detection.Account(Eip55.format(address), checkedAt(verified), null, CHECKED_ON_CHAIN)
                is AddressKind.DelegatedEoa -> Detection.Account(
                    Eip55.format(address),
                    checkedAt(verified),
                    Eip55.format(kind.delegate),
                    CHECKED_ON_CHAIN,
                )
                is AddressKind.Safe -> safeCard(address, kind.state, accounts, verified)
                AddressKind.Contract -> Detection.Contract(CONTRACT)
            }
        }
    }

    private fun checkedAt(reading: KindReading.Verified) = "Checked at block ${grouped(reading.blockNumber)}"

    private fun safeCard(address: Address, state: SafeState, accounts: List<Account>, verified: KindReading.Verified) =
        Detection.Safe(
            address = Eip55.format(address),
            title = "Safe ${state.version}" + if (state.l2) " (L2)" else "",
            signers = "${state.threshold} of ${state.owners.size} owners",
            owners = state.owners.map(Eip55::format),
            modules = state.modules.map(Eip55::format),
            guard = state.guard?.let(Eip55::format),
            checkedAt = checkedAt(verified),
            control = Control.of(state.control(state.ownersHeld(accounts).size)),
            moduleGuard = state.moduleGuard?.let(Eip55::format),
            threshold = "${state.threshold}/${state.owners.size}",
            simpleCheckedAt = CHECKED_ON_CHAIN,
        )

    /**
     * The registries a name is resolved on, in [on]: every network there that resolves names, or,
     * with none, the first network that is on, which takes an address and says it takes no name.
     */
    private fun registries(on: List<Network>): List<Network> =
        on.filter { it.ensNames }.ifEmpty { listOfNotNull(on.firstOrNull()) }

    /**
     * What [network] would watch for [text], from [lookups] of it, and the registry that gave it:
     * its own registry's answer where it resolves names; on Gnosis Chain Ethereum's, never a
     * testnet's, and for an address typed any registry's, all being the same (docs/networks.md,
     * *Names*). Null where none found an address for it.
     */
    private fun answerOn(
        network: Network,
        text: String,
        lookups: Map<Network, RecipientState>,
    ): Pair<Network, RecipientLookup.Found>? {
        fun found(registry: Network) = ((lookups[registry]?.takeIf { it.text == text } as? RecipientState.Done)
            ?.result as? RecipientLookup.Found)?.let { registry to it }
        if (network in lookups) return found(network)
        if (network.ensNames) return null
        lookups.keys.firstOrNull { it.ensNames && !it.testnet }?.let { return found(it) }
        return lookups.keys.firstNotNullOfOrNull { registry -> found(registry)?.takeIf { it.second.name == null } }
    }

    /**
     * What the address field says under [text], from [lookups] of it on each registry, and whether
     * that is an error: as Send's recipient field, but for the zero address, which nobody can hold,
     * and with the block a name was resolved at, or, in [simple] mode's words, none. A name says
     * which registry answered (docs/networks.md, *Names*): with two registries, each one's answer,
     * one still resolving among them; with one, its name on an address found or a name it has none
     * for or could not verify, where it is a testnet's or another network that is [on] takes its
     * answer (Gnosis Chain, Ethereum's). The same miss from every registry is said once.
     */
    private fun addressNote(
        text: String,
        lookups: Map<Network, RecipientState>,
        on: List<Network>,
        simple: Boolean = false,
    ): Pair<String?, Boolean> {
        val states = lookups.filterValues { it.text == text }
        if (states.isEmpty()) return recipientNote(text, null)
        val results = states.filterValues { it is RecipientState.Done }
            .mapValues { (it.value as RecipientState.Done).result }
        // Nothing answered yet, or an address still being checked: one note for it.
        if (results.isEmpty() || ('.' !in text && results.size < states.size)) {
            return recipientNote(text, states.values.first { it !is RecipientState.Done })
        }
        if (results.values.any { it == RecipientLookup.ZeroAddress }) return ZERO_ADDRESS to true
        val resolving = results.size < states.size
        val isError = !resolving && results.values.none { it is RecipientLookup.Found }
        val sameMiss = !resolving && results.size > 1 && results.values.distinct().size == 1 &&
            results.values.first() !is RecipientLookup.Found
        // An address typed, one registry asked, or every registry's same miss: one answer, said once.
        if ('.' !in text || states.size == 1 || sameMiss) {
            val (registry, result) = results.entries.first()
            val named = registry.takeIf {
                '.' in text && !sameMiss && it.ensNames && (it.testnet || on.any { n -> !n.ensNames })
            }
            val found = result as? RecipientLookup.Found
            val resolvedAt = found?.blockNumber
            // In full, whatever the card can say of it: the address the name points to is what is added.
            if (found != null && resolvedAt != null) return resolves(found, named, resolvedAt, simple) to false
            val (note, error) = recipientNote(text, states.getValue(registry))
            // The registry's own answer is named; a name that is not one is no registry's.
            val answered = result == RecipientLookup.NoAddress || result is RecipientLookup.Unavailable
            return (if (named != null && answered && note != null) onRegistry(named, note) else note) to error
        }
        return states.entries.joinToString("\n") { (registry, state) ->
            val result = results[registry]
            val found = result as? RecipientLookup.Found
            val block = found?.blockNumber
            val answer = when {
                found != null && block != null -> resolves(found, null, block, simple)
                result == RecipientLookup.NoAddress -> "no address"
                else -> recipientNote(text, state).first.orEmpty()
            }
            onRegistry(registry, answer)
        } to isError
    }

    /** "On Sepolia's test registry: no address": [answer] as [registry]'s. */
    private fun onRegistry(registry: Network, answer: String) =
        "On ${registryOf(registry)}: ${answer.replaceFirstChar { it.lowercase() }}"

    /** "Resolves to 0x…", on [registry]'s registry where it is named, verified at [block] or checked on chain. */
    private fun resolves(found: RecipientLookup.Found, registry: Network?, block: Long, simple: Boolean): String =
        "Resolves to ${Eip55.format(found.address)}" + registry?.let { " on ${registryOf(it)}" }.orEmpty() +
            if (simple) ", checked on chain" else ", verified at finalized block ${grouped(block)}"

    /** "Ethereum", "Sepolia's test registry": whose answer a line gives. */
    private fun registryOf(registry: Network): String =
        if (registry.testnet) "${registry.displayName}'s test registry" else registry.displayName

    private suspend fun lookUp(registry: Network, text: String): RecipientLookup = try {
        resolveRecipient(registry, recipientText(text))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RecipientLookup.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
    }

    /** Why [name]'s address is not added: it could not be verified again ([unverifiable]), or it moved. */
    private fun nameChanged(name: String, unverifiable: Boolean): String = if (unverifiable) {
        "“$name” could not be resolved with verified calls again just now. Nothing was added."
    } else {
        "“$name” no longer resolves to the address that was shown: check the address it has now. " +
            "Nothing was added."
    }

    private companion object {
        /** What a card says in simple mode in place of its block: what it shows was read from the chain and proven. */
        const val CHECKED_ON_CHAIN = "Checked on chain"
        const val ZERO_ADDRESS = "The zero address: nobody holds its key, so there is no account to watch."
        const val NO_NETWORK = "No network is on in this wallet, so the address cannot be checked. Turn one on in " +
            "Settings → Networks."
        const val CONTRACT =
            "This address holds a contract that is not a recognised Safe. Serotine watches accounts and Safes."
    }
}

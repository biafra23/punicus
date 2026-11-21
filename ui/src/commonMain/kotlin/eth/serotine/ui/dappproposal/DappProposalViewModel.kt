package eth.serotine.ui.dappproposal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppHost
import eth.serotine.domain.model.AppHostResult
import eth.serotine.domain.model.AppIsolation
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.DappApproval
import eth.serotine.domain.repository.DappConnections
import eth.serotine.domain.repository.DappProposal
import eth.serotine.domain.repository.DappProposalId
import eth.serotine.domain.repository.DappRefusal
import eth.serotine.domain.repository.MemoryStep
import eth.serotine.domain.usecase.AliasLinking
import eth.serotine.domain.usecase.ApproveDappProposal
import eth.serotine.domain.usecase.DappOffer
import eth.serotine.domain.usecase.dappOffer
import eth.serotine.domain.usecase.LinkMadePerAppAlias
import eth.serotine.domain.usecase.LinkPerAppAlias
import eth.serotine.domain.usecase.MakePerAppAddress
import eth.serotine.domain.usecase.ObserveDappConnections
import eth.serotine.domain.usecase.ObservePerAppChoice
import eth.serotine.domain.usecase.PerAppChoice
import eth.serotine.domain.usecase.PerAppEntry
import eth.serotine.domain.usecase.PerAppMaking
import eth.serotine.domain.usecase.missingRequired
import eth.serotine.domain.usecase.perAppNetworks
import eth.serotine.domain.usecase.typedHost
import eth.serotine.domain.usecase.RejectDappProposal
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.components.spokenList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A dApp's session proposal (docs/walletconnect.md, screen D3) as display-ready values: what the
 * dApp says of itself (its claim), the networks the connection would hold, what it asks to do, why it can
 * only be rejected when it can, and every account, those a dApp may not be offered greyed out with the
 * reason. [gone] once the proposal is no longer waiting; [done] once answered.
 */
data class DappProposalUiState(
    val loading: Boolean = true,
    val gone: Boolean = false,
    /** No longer waiting, and [notice] says why (it expired as Approve was tapped): Back leaves. */
    val ended: Boolean = false,
    val name: String = "",
    val url: String = "",
    val description: String = "",
    /**
     * The networks the connection would hold, each named with its kind of funds ("Sepolia, a test network"):
     * those the dApp asks for, of the kind of the accounts chosen once there are some.
     */
    val networks: List<String> = emptyList(),
    /** While the dApp asks for both kinds of funds and nothing is chosen: that the first account decides. */
    val networksNote: String? = null,
    val asks: List<String> = emptyList(),
    val refusal: String? = null,
    val accounts: List<AccountOption> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
    /** The reason a greyed-out item gives on a tap, while it is shown. */
    val explaining: Explanation? = null,
    val done: Boolean = false,
    /**
     * Why Approve waits for more accounts: the networks the dApp requires that no chosen account is on, said
     * ("This dApp requires an account on Gnosis Chain…"); null when each has one.
     */
    val missingRequired: String? = null,
    /**
     * The per-app address offered at the head of the accounts (issue #252; docs/walletconnect.md, *Per-app
     * addresses*): a new address for the dApp's site, ticked by default while the site has none and nothing it
     * remembers can be preselected; null once the site has its address on every network it would go on, which then
     * shows among the accounts.
     */
    val newAddress: NewAddressOption? = null,
    /** The phrases a new address can come from, while the user chooses one; null otherwise. */
    val phrases: List<PhraseOption>? = null,
    /**
     * The wallet password, typed while the new address is chosen: making it opens the phrase's envelope. It goes
     * once used, and whenever the screen is left.
     */
    val password: WalletPassword = WalletPassword.EMPTY,
    /**
     * For a site with no address of its own yet and no alias, the way to use another site's per-app address instead
     * (docs/walletconnect.md, *Aliases*): "No address for `<site>` yet" and "Use an address from another site…",
     * greyed out with its reason where there is none to use; null otherwise.
     */
    val aliasOffer: AliasOffer? = null,
    /** The other sites' per-app addresses, while the user chooses one to link this site to; null otherwise. */
    val aliasChoices: List<AliasChoice>? = null,
    /** "Type the old site's host": what is typed, while the list is open. */
    val aliasHost: String = "",
    /** The question asked before a site is linked to another's address, until answered; null otherwise. */
    val confirmingAlias: AliasQuestion? = null,
) {
    /** Approve is greyed out, still tappable, while it cannot be done (owner ruling, 2026-10-18, #55). */
    val approveUnavailable: Boolean
        get() = refusal != null || (accounts.none { it.chosen } && newAddress?.chosen != true) ||
            missingRequired != null || (newAddress?.chosen == true && password.text.isEmpty())
}

/**
 * The new per-app address for the dApp's site, as its row shows it: [title] ("New address for uniswap.org"), [where]
 * (the host and its site where they differ, and the networks it goes on), [chosen] or not, and [unavailable], why it
 * cannot be made, or null. [phrase] names the phrase it comes from when there are several, which [changeable] lets
 * the user change.
 */
data class NewAddressOption(
    val title: String,
    val where: String,
    val chosen: Boolean,
    val unavailable: String?,
    val phrase: String? = null,
    val changeable: Boolean = false,
)

/**
 * The way to another site's address for a site with none ([line], "No address for uniswap.app yet"), and why it is
 * greyed out ([unavailable]), or null.
 */
data class AliasOffer(val line: String, val unavailable: String?)

/**
 * Another site's per-app address the dApp's site may be linked to: its [site], the sites already linked to it
 * ([linked], "Also seen by …", or null), its short [address] and the [networks] it is held on; [key] names it.
 * [unavailable] is why it is greyed out (this dApp could be offered it on none of them), said on a tap, or null.
 */
data class AliasChoice(
    val key: String,
    val site: String,
    val linked: String?,
    val address: String,
    val networks: String = "",
    val unavailable: String? = null,
)

/**
 * What is asked before the site is linked: [title] and [text], which says the two sites can link the activity;
 * [needsPassword] when the other site's address is made again from the phrase first, under the wallet password;
 * [error], why "Use it" did not go through, said in the question, which stays open to try again.
 */
data class AliasQuestion(
    val title: String,
    val text: String,
    val needsPassword: Boolean = false,
    val error: String? = null,
)

/** A phrase a new address can come from, named by an account of it: [name] and [address]; [chosen] the one used. */
data class PhraseOption(val key: String, val name: String, val address: String, val chosen: Boolean)

/** One account on its [network], [chosen] or not; [unavailable] is why this dApp may not be offered it, or null. */
data class AccountOption(
    val key: String,
    val name: String,
    val address: String,
    val chosen: Boolean,
    val unavailable: String?,
    /** Its network, by name ("Gnosis Chain"): a dApp sees an account on its network alone. */
    val network: String = "",
    /** The site it is the per-app address of ("uniswap.org"), or null for an ordinary account (issue #252). */
    val perAppSite: String? = null,
    /** Whether the user linked the dApp's site to this per-app address of another site: an alias. */
    val linked: Boolean = false,
)

/** What a tap on a greyed-out item says. */
data class Explanation(val title: String, val reason: String)

/**
 * The proposal review's ViewModel: approves with the accounts chosen, or rejects. A new per-app address chosen is
 * made first ([MakePerAppAddress]), under the wallet password, and approved with the rest. What was approved for the
 * site last is preselected ([ObservePerAppChoice]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DappProposalViewModel(
    /** The proposal, as its route names it: `pairing/id`. */
    proposalKey: String,
    observeDappConnections: ObserveDappConnections,
    private val approveDappProposal: ApproveDappProposal,
    private val rejectDappProposal: RejectDappProposal,
    observePerAppChoice: ObservePerAppChoice,
    private val makePerAppAddress: MakePerAppAddress,
    private val linkPerAppAlias: LinkPerAppAlias,
    private val linkMadePerAppAlias: LinkMadePerAppAlias,
) : ViewModel() {

    private val id: DappProposalId? = proposalKey.split('/').takeIf { it.size == 2 }?.let { (pairing, number) ->
        number.toLongOrNull()?.let { DappProposalId(pairing, it) }
    }

    private data class Local(
        /** What the user chose, once they changed the choice; null for what is preselected. */
        val chosen: Set<String>? = null,
        /** The key of the account naming the phrase chosen for a new address; null for the default. */
        val phrase: String? = null,
        val choosingPhrase: Boolean = false,
        val choosingAlias: Boolean = false,
        /** The old site's host typed in the list, "Type the old site's host". */
        val aliasHost: String = "",
        /** Why the alias question's "Use it" did not go through, said in the question; null otherwise. */
        val aliasError: String? = null,
        /** Another site's address the user picked to link the dApp's site to, until the question is answered. */
        val pickedAlias: PickedAlias? = null,
        val password: WalletPassword = WalletPassword.EMPTY,
        val busy: Boolean = false,
        val notice: String? = null,
        val explaining: Explanation? = null,
        val done: Boolean = false,
    )

    /**
     * The [address] of [other]'s site the user picked, to link the dApp's [site] to: held with both as they were shown,
     * so the question and its answer never depend on a list read again meanwhile.
     */
    private data class PickedAlias(
        /** Its address, or null for one typed whose address is not held here: [making] makes it first. */
        val address: Address?,
        val site: AppHost,
        val other: AppHost,
        /** The rows of the address's accounts this dApp may be offered, chosen once the site is linked. */
        val keys: List<String> = emptyList(),
        /** The old site's address to make again from the phrase, under the password, before it is linked. */
        val making: NewAddress? = null,
        /** The phrase [making] comes from, named where there are several; null otherwise. */
        val phrase: String? = null,
        /** The site typed, when it is linked to [other]'s address: the question says why another name appears. */
        val via: AppHost? = null,
    )

    /** A new per-app address to make on Approve: from the account [from]'s phrase, for [host], on [networks]. */
    private data class NewAddress(val from: AccountId, val host: AppHost, val networks: List<Network>)

    /** What Approve acts on, as the state last shown has it: the keys chosen, and the new address chosen, if any. */
    private data class Plan(val chosen: Set<String>, val newAddress: NewAddress?)

    /**
     * The state shown and what Approve acts on, computed together so that the two never disagree, with the per-app
     * [choice] they were built from.
     */
    private class Shown(
        val state: DappProposalUiState,
        val plan: Plan,
        val choice: PerAppChoice? = null,
        val proposal: DappProposal? = null,
    )

    private val local = MutableStateFlow(Local())

    private val overviews = observeDappConnections()

    private val choices = overviews
        .map { overview -> (overview.connections as? DappConnections.On)?.proposals?.find { it.id == id }?.dapp?.url }
        .distinctUntilChanged()
        .flatMapLatest { url -> observePerAppChoice(url.orEmpty()) }

    private val shown: StateFlow<Shown> = combine(overviews, choices, local) { overview, perApp, local ->
        val proposal = (overview.connections as? DappConnections.On)?.proposals?.find { it.id == id }
            // No longer waiting: the screen leaves, unless an answer that did not take says why first.
            ?: return@combine Shown(
                if (local.notice != null && !local.done) {
                    DappProposalUiState(loading = false, ended = true, notice = local.notice)
                } else {
                    DappProposalUiState(loading = false, gone = true, done = local.done)
                },
                NO_PLAN,
            )
        val alias = perApp.alias
        val offered = overview.accounts.filter { it.offerTo(proposal, alias = alias) == DappOffer.OFFERED }
            .map { it.account.id }
        // Preselected until the user changes the choice: what was approved for the site last, or else the site's
        // per-app addresses, or the address it was linked to; and a new address while the site has neither
        // (docs/walletconnect.md, *Per-app addresses*, *Aliases*).
        val own = perApp.made + perApp.aliased
        val preselected = perApp.remembered.filter { it in offered }
            .ifEmpty { own.map { it.id }.filter { it in offered } }
            .map(::keyOf).toSet()
        // The new address is ticked only while nothing remembered can still be preselected: one declined once is not
        // offered ticked beside the account approved instead (owner's decisions, 2026-11-19, on #331 and #332). A
        // memory of what the site can no longer be offered (an alias removed since) is no such account.
        val fresh = own.isEmpty() && preselected.isEmpty() && proposal.refusal == null
        val wanted = local.chosen ?: if (fresh) preselected + NEW_KEY else preselected
        // What is chosen and still offered on its own: each account is judged beside the others chosen. One no
        // longer offered (its network turned off, say) counts for nothing, so it greys out no other.
        val ticked = offered.filter { keyOf(it) in wanted }
        val newOption = newAddressOption(proposal, perApp, local, ticked, NEW_KEY in wanted)
        val making = newOption?.second
        // The new address counts as chosen on its networks: it decides the kind of funds as an account would.
        val counted = ticked + making?.networks.orEmpty().map { AccountId(it, PLACEHOLDER) }
        // "Use an address from another site…" is named only where the screen offers it.
        val aliasOffer = aliasOffer(proposal, perApp)
        val aliasable = aliasOffer != null && aliasOffer.unavailable == null
        val options = overview.accounts.map { choice ->
            val offer = if (proposal.refusal != null) {
                choice.offer
            } else {
                choice.offerTo(proposal, counted - choice.account.id, alias)
            }
            AccountOption(
                key = keyOf(choice.account.id),
                name = choice.account.label,
                address = shortAddress(choice.account.address.hex),
                chosen = offer == DappOffer.OFFERED && keyOf(choice.account.id) in wanted,
                unavailable = offerLine(offer, choice.account.network, choice.account.perApp?.site?.name, aliasable),
                network = choice.account.network.displayName,
                perAppSite = choice.account.perApp?.site?.name,
                linked = choice.account in perApp.aliased,
            )
        }
        val chosenKeys = options.filter { it.chosen }.map { it.key }.toSet()
        val chosenIds = overview.accounts.map { it.account.id }.filter { keyOf(it) in chosenKeys } +
            making?.networks.orEmpty().map { AccountId(it, PLACEHOLDER) }
        val chosenKinds = chosenIds.map { it.network.testnet }.toSet()
        val held = Network.entries
            .filter { it in proposal.networks && (chosenKinds.isEmpty() || it.testnet in chosenKinds) }
        val state = DappProposalUiState(
            loading = false,
            name = proposal.dapp.name,
            url = proposal.dapp.url,
            description = proposal.dapp.description,
            networks = held.map(::networkLine),
            // Only while a choice can still decide: a refused proposal can only be rejected.
            networksNote = BOTH_KINDS.takeIf { proposal.refusal == null && held.map { it.testnet }.toSet().size > 1 },
            asks = proposal.methods.sortedBy { METHOD_ORDER.indexOf(it) }.map(::methodLine),
            refusal = proposal.refusal?.let(::refusalLine),
            accounts = options,
            missingRequired = missingLine(missingRequired(proposal, chosenIds)),
            busy = local.busy,
            notice = local.notice ?: if (overview.accountsUnreadable) ACCOUNTS_UNREADABLE else null,
            explaining = local.explaining,
            done = local.done,
            newAddress = newOption?.first,
            phrases = if (local.choosingPhrase) phraseOptions(perApp, local) else null,
            password = local.password,
            aliasOffer = aliasOffer,
            aliasChoices = if (local.choosingAlias) aliasChoices(proposal, perApp) else null,
            aliasHost = local.aliasHost,
            confirmingAlias = local.pickedAlias?.let(::aliasQuestion)?.copy(error = local.aliasError),
        )
        Shown(state, Plan(chosenKeys, making), perApp, proposal)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Shown(DappProposalUiState(), NO_PLAN))

    val uiState: StateFlow<DappProposalUiState> =
        shown.map { it.state }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DappProposalUiState())

    /**
     * The new address's row, and what Approve makes when it is chosen (null when it is not, or cannot be made); or
     * null when the site has its address on every network it would go on already, whether that network is on or not.
     */
    private fun newAddressOption(
        proposal: DappProposal,
        choice: PerAppChoice,
        local: Local,
        ticked: List<AccountId>,
        wanted: Boolean,
    ): Pair<NewAddressOption, NewAddress?>? {
        // Linked to another site's address: that address is this site's, and no new one is offered beside it.
        if (choice.aliased.isNotEmpty()) return null
        val networks = perAppNetworks(proposal, choice.enabled, ticked, choice.made)
        // Left out only when the site has its address on every network it would go on, on or off: one it lacks
        // that is off greys the row out with the way to turn it on (#55).
        val lacking = networks.ifEmpty { perAppNetworks(proposal, Network.entries.toSet(), ticked, choice.made) }
        if (lacking.isEmpty() && choice.made.isNotEmpty()) return null
        val site = choice.site
        val host = (choice.host as? AppHostResult.Host)?.host
        // The site's address on more networks comes from the phrase it was made from, whichever is chosen.
        val madeFrom = choice.made.firstNotNullOfOrNull { it.perApp?.phrase }
        val source = if (madeFrom != null) {
            choice.sources.firstOrNull { it.phrase == madeFrom }
        } else {
            sourceOf(choice, local)
        }
        val unavailable = when {
            proposal.refusal != null -> refusalLine(proposal.refusal!!)
            host == null || site == null -> hostLine(choice.host)
            source == null && madeFrom != null -> PHRASE_GONE
            source == null -> NO_PHRASE
            choice.collision != null -> collisionLine(choice.collision!!)
            networks.isEmpty() -> NO_NETWORK
            else -> null
        }
        val named = site?.name ?: "this dApp"
        val title = if (choice.made.isEmpty()) "New address for $named" else "$named's address on more networks"
        val hostPart = if (host != null && site != null && host != site) "${host.name}, site ${site.name}" else named
        val names = spokenList(networks.map { it.displayName })
        val where = if (networks.isEmpty()) hostPart else "$hostPart · On $names"
        val several = choice.sources.size > 1 && madeFrom == null
        val option = NewAddressOption(
            title = title,
            where = where,
            chosen = wanted && unavailable == null,
            unavailable = unavailable,
            phrase = source?.takeIf { several }?.let { "From the recovery phrase of ${choice.nameOf(it)}" },
            changeable = several && unavailable == null,
        )
        val making = if (option.chosen && host != null && source != null) {
            NewAddress(source.from.id, host, networks)
        } else {
            null
        }
        return option to making
    }

    /** The phrase the user chose for a new address, or the default: that of the first account in the list. */
    private fun sourceOf(choice: PerAppChoice, local: Local) =
        choice.sources.firstOrNull { keyOf(it.from.id) == local.phrase } ?: choice.sources.firstOrNull()

    private fun phraseOptions(choice: PerAppChoice, local: Local): List<PhraseOption> {
        val chosen = sourceOf(choice, local)
        return choice.sources.map {
            PhraseOption(keyOf(it.from.id), choice.nameOf(it), shortAddress(it.from.address.hex), it == chosen)
        }
    }

    /** The way to another site's address, for a site with no address of its own and no alias; null otherwise. */
    private fun aliasOffer(proposal: DappProposal, choice: PerAppChoice): AliasOffer? {
        val site = choice.site ?: return null
        if (proposal.refusal != null || choice.made.isNotEmpty() || choice.aliased.isNotEmpty()) return null
        // With a phrase held here, an old site's address can be typed and made again: none listed is no reason then.
        val none = choice.others.isEmpty() && choice.sources.isEmpty()
        return AliasOffer("No address for ${site.name} yet", NO_OTHER_SITE.takeIf { none })
    }

    /**
     * The other sites' addresses, each with its networks; one this dApp could be offered on none of them is greyed out,
     * a tap saying why (owner's decision, 2026-11-19, on PR #328).
     */
    private fun aliasChoices(proposal: DappProposal, choice: PerAppChoice): List<AliasChoice> =
        choice.others.map { entry -> aliasChoice(proposal, choice, entry) }

    private fun aliasChoice(proposal: DappProposal, choice: PerAppChoice, entry: PerAppEntry) =
        AliasChoice(
            key = entry.address.hex,
            site = entry.site.name,
            linked = entry.aliases.takeIf { it.isNotEmpty() }
                ?.let { sites -> "Also seen by ${sites.joinToString { it.name }}" },
            address = shortAddress(entry.address.hex),
            networks = entry.accounts.joinToString(", ") { it.network.displayName },
            unavailable = UNUSABLE_HERE.takeIf { usableOn(proposal, choice, entry).isEmpty() },
        )

    /** The accounts of [entry] this dApp could be offered, were the site linked to it. */
    private fun usableOn(proposal: DappProposal, choice: PerAppChoice, entry: PerAppEntry) =
        entry.accounts.filter { dappOffer(it, choice.enabled, proposal, alias = entry.address) == DappOffer.OFFERED }

    private fun aliasQuestion(picked: PickedAlias) = AliasQuestion(
        title = "Use ${picked.other.name}'s address?",
        text = (picked.via?.let { "${it.name} uses ${picked.other.name}'s address, linked by you. " } ?: "") +
            "${picked.site.name} will see the same address as ${picked.other.name}. The two sites can link your " +
            "activity." + when {
                picked.making == null -> ""
                picked.phrase != null -> " " + madeAgainFrom(picked.phrase)
                else -> " $MADE_AGAIN"
            },
        needsPassword = picked.making != null,
    )

    /** "Use an address from another site…" was tapped: the list opens, or, greyed out, it says why. */
    fun chooseAlias() {
        val offer = shown.value.state.aliasOffer ?: return
        val reason = offer.unavailable
        if (reason != null) {
            local.update { it.copy(explaining = Explanation("Use an address from another site", reason)) }
        } else {
            local.update { it.copy(choosingAlias = true, notice = null) }
        }
    }

    /**
     * The address [key] names was picked from the list as shown, or none ([key] null closes it): what it means is
     * asked, of the sites as they were shown.
     */
    fun pickAlias(key: String?) {
        val choice = shown.value.choice
        val proposal = shown.value.proposal
        val other = choice?.others?.firstOrNull { it.address.hex == key }
        val site = choice?.site
        val greyed = shown.value.state.aliasChoices?.firstOrNull { it.key == key }?.unavailable
        if (greyed != null) {
            local.update { it.copy(explaining = Explanation("Not usable for this dApp", greyed)) }
            return
        }
        local.update {
            it.copy(
                choosingAlias = false,
                aliasHost = "",
                pickedAlias = if (other != null && site != null && choice != null && proposal != null) {
                    val keys = usableOn(proposal, choice, other).map { account -> keyOf(account.id) }
                    PickedAlias(other.address, site, other.site, keys)
                } else {
                    null
                },
            )
        }
    }

    /** "Type the old site's host" changed. */
    fun onAliasHostChange(text: String) = local.update { it.copy(aliasHost = text) }

    /**
     * The old site's host typed was submitted (docs/walletconnect.md, *Aliases*, "Type the old site's host"): its
     * address, when held here, is picked as from the list; else it is to be made again from the phrase chosen, under
     * the password, which the question asks for. What names no other site is said, and nothing is picked.
     */
    fun typeAlias() {
        val choice = shown.value.choice ?: return
        val proposal = shown.value.proposal ?: return
        val site = choice.site ?: return
        val typed = typedHost(local.value.aliasHost)
        val host = (typed as? AppHostResult.Host)?.host
            ?: return explainTyped(if (typed == AppHostResult.NotAscii) NOT_ASCII else NOT_A_HOST)
        if (host.site == site) return explainTyped(SAME_SITE)
        // The address the old site saw: its own, or the one it was itself linked to.
        val own = choice.others.firstOrNull { it.site == host.site }
        val held = own ?: choice.others.firstOrNull { host.site in it.aliases }
        if (held != null) {
            local.update { it.copy(aliasHost = "") }
            pickAlias(held.address.hex)
            if (own == null) local.update { now -> now.copy(pickedAlias = now.pickedAlias?.copy(via = host.site)) }
            return
        }
        val source = sourceOf(choice, local.value) ?: return explainTyped(NO_PHRASE)
        // The networks a new address would go on, of the kind the accounts ticked decide, as its row says.
        val ticked = shown.value.plan.chosen.mapNotNull(::idOf)
        val networks = perAppNetworks(proposal, choice.enabled, ticked, emptyList())
        if (networks.isEmpty()) return explainTyped(NO_NETWORK)
        val taken = choice.others.map { it.site } + choice.made.mapNotNull { it.perApp?.site }
        AppIsolation.collision(host.site, taken)?.let { other -> return explainTyped(collisionOf(host.site, other)) }
        val phrase = choice.nameOf(source).takeIf { choice.sources.size > 1 }
        local.update {
            it.copy(
                choosingAlias = false,
                aliasHost = "",
                pickedAlias = PickedAlias(
                    address = null,
                    site = site,
                    other = host.site,
                    making = NewAddress(source.from.id, host, networks),
                    phrase = phrase,
                ),
            )
        }
    }

    private fun explainTyped(reason: String) =
        local.update { it.copy(explaining = Explanation("Type the old site's host", reason)) }

    /**
     * The question was answered: yes links the site to the address picked, which is then preselected; one typed whose
     * address is not held is made again first, under the password typed in the question, which stays open to try again
     * when that fails. One that is no longer another site's address here by then is refused by the link, and said
     * ([aliasLine]).
     */
    fun answerAlias(yes: Boolean) {
        val picked = local.value.pickedAlias ?: return
        // While the address is made, the question is not closed: what "Use it" started goes on.
        if (local.value.busy) return
        if (!yes) {
            // Only a question that asked for the password drops it: the new address's keeps its own.
            local.update {
                it.copy(
                    pickedAlias = null,
                    aliasError = null,
                    password = if (picked.making != null) WalletPassword.EMPTY else it.password,
                )
            }
            return
        }
        val password = local.value.password
        val making = picked.making
        if (making != null && password.text.isEmpty()) {
            local.update { it.copy(aliasError = PASSWORD_FOR_OLD_SITE) }
            return
        }
        // The password leaves the state as the work starts: it is held by this call alone.
        local.update { it.copy(busy = true, notice = null, aliasError = null, password = WalletPassword.EMPTY) }
        viewModelScope.launch {
            if (making == null) {
                val address = picked.address
                val linked = if (address == null) AliasLinking.NotPerApp else linkPerAppAlias(picked.site, address)
                val note = aliasLine(linked).takeIf { linked != AliasLinking.Linked }
                local.update { linkedState(it, linked, picked.keys, note) }
                return@launch
            }
            val outcome = linkMadePerAppAlias(picked.site, making.from, making.host, making.networks, password)
            val made = outcome.making
            if (made !is PerAppMaking.Made) {
                // Said in the question, which stays open to try again (with another password, say).
                local.update { it.copy(busy = false, aliasError = oldSiteLine(made, picked.other)) }
                return@launch
            }
            val linked = outcome.linking ?: AliasLinking.NotSaved
            val note = when {
                linked != AliasLinking.Linked -> madeNotLinkedLine(picked.other, linked)
                made.steppedDown -> oldSiteSteppedDown(picked.other)
                else -> null
            }
            local.update { linkedState(it, linked, made.accounts.map(::keyOf), note) }
        }
    }

    /**
     * The state once the link is answered: linked, the address's [keys] chosen (newer than what the site's memory
     * preselects), and [note] said where there is one; else what [note] says of it.
     */
    private fun linkedState(now: Local, linked: AliasLinking, keys: List<String>, note: String?) = if (
        linked == AliasLinking.Linked
    ) {
        now.copy(busy = false, pickedAlias = null, chosen = keys.toSet(), notice = note)
    } else {
        now.copy(busy = false, pickedAlias = null, notice = note)
    }

    /**
     * Chooses or unchooses the account [key], or the new address ([NEW_KEY]); one a dApp may not be offered, or a new
     * address that cannot be made, says why instead.
     */
    fun toggle(key: String) {
        val (state, plan) = shown.value.let { it.state to it.plan }
        // The choice as shown, preselection included, from here on the user's.
        val current = local.value.chosen ?: if (plan.newAddress != null) plan.chosen + NEW_KEY else plan.chosen
        val unavailable = if (key == NEW_KEY) {
            state.newAddress?.unavailable ?: if (state.newAddress == null) return else null
        } else {
            (state.accounts.find { it.key == key } ?: return).unavailable
        }
        // One chosen before it became unavailable can always be unchosen.
        if (key in current) {
            // Unticking the new address drops a typed password: nothing needs it any more.
            val password = if (key == NEW_KEY) WalletPassword.EMPTY else local.value.password
            local.update { it.copy(chosen = current - key, notice = null, password = password) }
            return
        }
        if (unavailable != null) {
            val title = if (key == NEW_KEY) "No new address" else "Not offered to this dApp"
            local.update { it.copy(explaining = Explanation(title, unavailable), chosen = current) }
            return
        }
        local.update { it.copy(chosen = current + key, notice = null) }
    }

    fun dismissExplanation() = local.update { it.copy(explaining = null) }

    /** Opens the list of phrases a new address can come from. */
    fun choosePhrase() {
        if (shown.value.state.newAddress?.changeable == true) local.update { it.copy(choosingPhrase = true) }
    }

    /** The new address comes from the phrase the account [key] names; null closes the list unchanged. */
    fun pickPhrase(key: String?) = local.update { it.copy(choosingPhrase = false, phrase = key ?: it.phrase) }

    /** The password field changed: what the alias question said of the last one goes. */
    fun onPasswordChange(text: String) {
        if (!local.value.busy) local.update { it.copy(password = WalletPassword(text), aliasError = null) }
    }

    /** The screen is no longer shown: the password typed goes. */
    fun onHidden() {
        if (!local.value.busy) local.update { it.copy(password = WalletPassword.EMPTY) }
    }

    fun approve() {
        val (state, plan) = shown.value.let { it.state to it.plan }
        val proposal = id ?: return
        if (state.busy) return
        state.refusal?.let { reason ->
            local.update { it.copy(explaining = Explanation("Cannot be approved", reason)) }
            return
        }
        val chosen = state.accounts.filter { it.chosen }.mapNotNull { idOf(it.key) }
        val making = plan.newAddress.takeIf { state.newAddress?.chosen == true }
        if (chosen.isEmpty() && making == null) {
            local.update { it.copy(explaining = Explanation("Choose an account", CHOOSE_ACCOUNT)) }
            return
        }
        state.missingRequired?.let { reason ->
            local.update { it.copy(explaining = Explanation("Choose an account", reason)) }
            return
        }
        val password = local.value.password
        if (making != null && password.text.isEmpty()) {
            local.update { it.copy(explaining = Explanation("Wallet password", PASSWORD_FIRST)) }
            return
        }
        // The password leaves the state as the work starts: it is held by this call alone.
        val asShown = if (making != null) plan.chosen + NEW_KEY else plan.chosen
        local.update { it.copy(busy = true, notice = null, password = WalletPassword.EMPTY, chosen = asShown) }
        viewModelScope.launch {
            var approving = chosen
            var steppedDown = false
            if (making != null) {
                when (val made = makePerAppAddress(making.from, making.host, making.networks, password)) {
                    is PerAppMaking.Made -> {
                        approving = chosen + made.accounts
                        steppedDown = made.steppedDown
                        // Made: from here on its accounts are chosen like any other, so a retry approves them.
                        val keys = made.accounts.map(::keyOf)
                        local.update { it.copy(chosen = (it.chosen.orEmpty() + keys) - NEW_KEY) }
                    }
                    else -> {
                        local.update { it.copy(busy = false, notice = makingLine(made)) }
                        return@launch
                    }
                }
            }
            val approval = approveDappProposal(proposal, approving)
            val approved = approval is DappApproval.Approved
            local.update {
                // A lighter seal is said before the screen leaves: Back leaves once it is read.
                val note = if (approved && steppedDown) STEPPED_DOWN else approvalLine(approval)
                it.copy(busy = false, done = approved && !steppedDown, notice = note)
            }
        }
    }

    fun reject() {
        val proposal = id ?: return
        if (uiState.value.busy) return
        local.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val rejected = rejectDappProposal(proposal)
            local.update { it.copy(busy = false, done = rejected, notice = if (rejected) null else NOT_SAVED) }
        }
    }

    companion object {
        const val NOT_SAVED = "That could not be saved. Nothing was changed."

        /** Why another site's address is greyed out in the list: this dApp could be offered it on no network. */
        const val UNUSABLE_HERE = "This address is on none of the networks this dApp asks for that are on, of the " +
            "kind of funds it requires. Linked, it would leave this site with no address it can use here."

        /** What the question adds when the old site's address is made again first. */
        const val MADE_AGAIN = "Its address is not on this device, so it is made again from your recovery phrase, " +
            "which takes the wallet password."

        /** Why Use it waits: the old site's address is made first, which takes the wallet password. */
        const val PASSWORD_FOR_OLD_SITE = "Type the wallet password: making the old site's address again takes it."

        /** What the question adds when the old site's address is made again from the phrase named [phrase]. */
        fun madeAgainFrom(phrase: String) = "Its address is not on this device, so it is made again from the " +
            "recovery phrase of $phrase, which takes the wallet password. To make it from another, change the new " +
            "address's phrase first."

        /** Why a typed old site's address cannot be made: its path is that of [other], another site's here. */
        fun collisionOf(site: AppHost, other: AppHost) = "${site.name}'s address would be the one made for " +
            "${other.name}, so it is already used for another app. Nothing was linked."

        /** What making the old site's [other] address gave, when it gave none: said of that site, not the dApp's. */
        fun oldSiteLine(made: PerAppMaking, other: AppHost): String = when (made) {
            is PerAppMaking.Made -> ""
            PerAppMaking.WrongPassword -> "That is not the wallet password. Nothing was made or linked."
            PerAppMaking.ListNotSaved -> "${other.name}'s address is stored, but your account list could not be " +
                "saved. Answer Use it again to add and link it."
            is PerAppMaking.Collision -> collisionOf(other, made.other)
            PerAppMaking.NoNetwork -> NO_NETWORK
            PerAppMaking.NoKey -> "The phrase's account has no key on this device any more. Nothing was made or " +
                "linked."
            PerAppMaking.Unavailable -> "The key store cannot open that recovery phrase on this device. Nothing was " +
                "made or linked."
            PerAppMaking.KeyNotSaved -> "${other.name}'s address could not be stored. Nothing was linked."
            is PerAppMaking.NotEnoughMemory -> makingLine(made) ?: NOT_SAVED
            PerAppMaking.ListUnreadable -> ACCOUNTS_UNREADABLE
        }

        /** What a link that failed once the old site's address was made says: the address is here, unlinked. */
        fun madeNotLinkedLine(other: AppHost, linking: AliasLinking): String =
            "${other.name}'s address is in your accounts now, but this site could not be linked to it" +
                (if (linking == AliasLinking.NotSaved) ": that could not be saved." else ".") +
                " Pick it from \"Use an address from another site…\" to try again."

        /** What a link says once made, when the old site's key was sealed at a lighter cost than this device's own. */
        fun oldSiteSteppedDown(other: AppHost) = "Linked. ${other.name}'s address's key was protected at a lighter " +
            "password cost than this device's own, because there was not enough free memory."

        /** What a typed host with characters outside ASCII says. */
        const val NOT_ASCII = "That name has characters outside ASCII, which Punicus does not map. Type it in its " +
            "xn-- form."

        /** What a typed host that names no site says. */
        const val NOT_A_HOST = "That is not a host or a site. Type the old site's, such as app.uniswap.org."

        /** What typing this dApp's own site says. */
        const val SAME_SITE = "That is this dApp's own site. Type the site it used before it moved."

        /** Why "Use an address from another site…" is greyed out: there is no other site's address here. */
        const val NO_OTHER_SITE = "No other site has a per-app address on this device, and there is no recovery " +
            "phrase here to make one again from. Import or create one under Accounts."

        /** What linking a site said, when it did not link it. */
        fun aliasLine(linking: AliasLinking): String = when (linking) {
            AliasLinking.Linked -> ""
            AliasLinking.NotPerApp -> "That address is no longer another site's per-app address here. Nothing " +
                "was linked."
            AliasLinking.HasOwn -> "This site has an address of its own by now, so it was not linked to another."
            AliasLinking.ListUnreadable -> ACCOUNTS_UNREADABLE
            AliasLinking.NotSaved -> NOT_SAVED
        }

        /** The new per-app address's row, as [toggle] names it. */
        const val NEW_KEY = "new"

        private val NO_PLAN = Plan(emptySet(), null)

        const val PHRASE_GONE = "This site's address came from a recovery phrase no longer held on this device, so " +
            "it cannot be added on more networks here."

        /** Stands for the new address on its networks while it is not made yet: only its network is ever read. */
        private val PLACEHOLDER = Address.of("0x" + "0".repeat(40))

        const val NO_PHRASE = "A per-app address needs a recovery phrase held on this device, and there is none: " +
            "only watched accounts or Safes. Import or create one under Accounts."

        const val NO_NETWORK = "None of the networks this dApp asks for, of the kind of funds chosen, is on. Turn " +
            "one on in Settings, under Networks."

        const val PASSWORD_FIRST = "Type the wallet password: making the new address takes it."

        const val STEPPED_DOWN = "Connected. The new address's key was protected at a lighter password cost than " +
            "this device's own, because there was not enough free memory."

        /** Why no address can be made for the host [host] states, when it has none. */
        fun hostLine(host: AppHostResult): String = when (host) {
            AppHostResult.NotAscii -> "This dApp's web address has characters outside ASCII, which Punicus does " +
                "not map, so no address can be made for it. Choose one of your accounts."
            AppHostResult.NotAWebAddress -> "This dApp states no web address, so there is no site to make an " +
                "address for. Choose one of your accounts."
            AppHostResult.NotAName, is AppHostResult.Host -> "This dApp's web address names no site an address can " +
                "be made for. Choose one of your accounts."
        }

        /** Why a site whose path is that of [other] gets no address of its own. */
        fun collisionLine(other: AppHost): String = "This site's address would be the one made for ${other.name}, " +
            "so it is already used for another app. Choose one of your accounts."

        /** What making the new address gave, when it gave none. */
        fun makingLine(made: PerAppMaking): String? = when (made) {
            is PerAppMaking.Made -> null
            PerAppMaking.NoNetwork -> NO_NETWORK
            PerAppMaking.NoKey -> "The account the address would come from has no key on this device any more. " +
                "Nothing was approved."
            PerAppMaking.WrongPassword -> "That is not the wallet password. Nothing was made or approved."
            PerAppMaking.Unavailable -> "The key store cannot open that recovery phrase on this device, so no " +
                "address was made. Nothing was approved."
            PerAppMaking.KeyNotSaved -> "The new address could not be stored. Nothing was approved."
            is PerAppMaking.NotEnoughMemory -> when (made.step) {
                MemoryStep.CHECK_PASSWORD -> "There is not enough free memory to check the password right now. " +
                    "Close other apps and try again."
                MemoryStep.PROTECT -> "There is not enough free memory to protect the new address's key right " +
                    "now. Close other apps and try again."
            }
            is PerAppMaking.Collision -> collisionLine(made.other)
            PerAppMaking.ListUnreadable -> ACCOUNTS_UNREADABLE
            PerAppMaking.ListNotSaved -> "The new address is stored, but your account list could not be saved. " +
                "Approve again to add it."
        }

        /**
         * An account as its row names it: its chain id and its address. The same address is an account
         * on each network it is held on, so the address alone would name two rows.
         */
        fun accountKey(chainId: Long, address: String): String = "$chainId:$address"

        private fun keyOf(id: AccountId): String = accountKey(id.network.chainId, id.address.hex)

        private fun idOf(key: String): AccountId? {
            val network = key.substringBefore(':').toLongOrNull()?.let(Network::ofChainId) ?: return null
            return AccountId(network, Address.parse(key.substringAfter(':')) ?: return null)
        }

        /** [network] by name, with its kind of funds: Sepolia keeps its testnet warning everywhere. */
        fun networkLine(network: Network): String =
            if (network.testnet) "${network.displayName}, a test network" else "${network.displayName}, real funds"

        /** What Approve waits for when [missing], the networks required with no chosen account, is not empty. */
        fun missingLine(missing: Set<Network>): String? {
            val names = Network.entries.filter { it in missing }.map { it.displayName }
            if (names.isEmpty()) return null
            val listed = when (names.size) {
                1 -> names.single()
                else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
            }
            return "This dApp requires an account on $listed. Choose one there to approve."
        }

        const val BOTH_KINDS = "A connection holds real funds or a test network, never both: the first account " +
            "you choose decides which."

        const val CHOOSE_ACCOUNT = "Choose at least one account for the dApp to see."
        const val ACCOUNTS_UNREADABLE = "Your accounts cannot be read right now, so none can be offered."

        private val METHOD_ORDER =
            listOf("personal_sign", "eth_signTypedData_v4", "eth_sendTransaction", "wallet_switchEthereumChain")

        fun methodLine(method: String): String = when (method) {
            "personal_sign" -> "Ask you to sign messages, such as signing in"
            "eth_signTypedData_v4" -> "Ask you to sign structured data, such as permits"
            "eth_sendTransaction" -> "Ask you to send transactions, each one reviewed here"
            "wallet_switchEthereumChain" -> "Ask to switch between the networks it is connected on"
            else -> method
        }

        fun refusalLine(refusal: DappRefusal): String = when (refusal) {
            DappRefusal.OTHER_CHAIN -> "This dApp requires a network Punicus does not connect dApps on. Only " +
                "Ethereum, Gnosis Chain and Sepolia are, so it can only be rejected."
            DappRefusal.MIXED_FUNDS -> "This dApp requires a test network and a network with real funds at once. " +
                "A connection holds one or the other, never both, so it can only be rejected."
            DappRefusal.NO_NETWORK -> "This dApp asks for none of the networks Punicus connects dApps on: " +
                "Ethereum, Gnosis Chain and Sepolia. It can only be rejected."
            DappRefusal.METHOD -> "This dApp requires a way of signing Punicus does not offer, such as " +
                "eth_sign, so it can only be rejected."
            DappRefusal.EVENT -> "This dApp requires notices Punicus does not send, so it can only be rejected."
            DappRefusal.NAMESPACE ->
                "This dApp requires a chain that is not an Ethereum one, so it can only be rejected."
        }

        /**
         * Why an account on [network] is not offered, for [offer]; null when it is. [site] is the site it is the
         * per-app address of, when it is one.
         */
        /**
         * Why an account is not offered, by [offer]; for another site's per-app address, the way to use it here anyway
         * where the screen offers one ([aliasable]).
         */
        fun offerLine(
            offer: DappOffer,
            network: Network,
            site: String? = null,
            aliasable: Boolean = false,
        ): String? = when (offer) {
            DappOffer.OFFERED -> null
            DappOffer.NOT_ASKED -> "This dApp did not ask for ${network.displayName}."
            DappOffer.OTHER_FUNDS -> if (network.testnet) {
                "${network.displayName} is a test network. A connection holding real funds cannot include it."
            } else {
                "${network.displayName} holds real funds. A connection on a test network cannot include it."
            }
            DappOffer.NETWORK_OFF -> "${network.displayName} is off. Turn it on in Settings, under Networks."
            DappOffer.NO_KEY -> "This account is watched: there is no key for it on this device."
            DappOffer.OTHER_SITE -> "This is the per-app address of ${site ?: "another site"}. It is offered to that " +
                "site alone, so that the two sites cannot link your activity." +
                if (aliasable) " To use it here anyway, choose \"Use an address from another site…\"." else ""
        }

        fun approvalLine(approval: DappApproval): String? = when (approval) {
            is DappApproval.Approved -> null
            DappApproval.NoSuchProposal, DappApproval.Expired ->
                "This request has expired. Connect again from the dApp."
            is DappApproval.Refused -> refusalLine(approval.why)
            DappApproval.InvalidAccounts -> "One of the accounts chosen cannot be offered to a dApp. Choose again."
            DappApproval.NotSaved -> NOT_SAVED
            DappApproval.Off -> "This build has dApp connections switched off."
        }
    }
}

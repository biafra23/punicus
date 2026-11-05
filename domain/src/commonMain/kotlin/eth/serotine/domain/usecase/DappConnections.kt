package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.DappApproval
import eth.serotine.domain.repository.DappConnections
import eth.serotine.domain.repository.DappPairing
import eth.serotine.domain.repository.DappProposal
import eth.serotine.domain.repository.DappProposalId
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.DappSessions
import eth.serotine.domain.repository.NetworkSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/**
 * Whether an account may be offered to a dApp, and why not when it may not (docs/walletconnect.md,
 * *Pairing and the session proposal*). An account that may not is shown greyed out, never hidden
 * (owner ruling, 2026-10-18, #55).
 */
enum class DappOffer {
    /** An account that can sign here, or a Safe, on a network that is on: it may be offered. */
    OFFERED,

    /** An account on a network the dApp did not ask for. */
    NOT_ASKED,

    /**
     * An account of the other kind of funds than the dApp requires, or than an account already chosen: a
     * session holds real funds or testnet, never both (owner ruling, 2026-11-05, on #22).
     */
    OTHER_FUNDS,

    /** An account on a network that is off: turning it on offers it. */
    NETWORK_OFF,

    /** An EOA watched with no key on this device: it can sign nothing here. */
    NO_KEY,
}

/** One of the wallet's accounts, and whether a dApp may be offered it, whatever the proposal asks. */
data class DappAccountChoice(val account: Account, val offer: DappOffer) {
    /**
     * Whether [account] may be offered to [proposal]'s dApp, with the accounts [chosen] so far besides it
     * (screen D3): as [dappOffer] answers, from the [offer] judged already.
     */
    fun offerTo(proposal: DappProposal, chosen: Collection<AccountId> = emptyList()): DappOffer =
        proposalOffer(account, proposal, chosen) ?: offer
}

/** Why [account] may not be offered to [proposal]'s dApp beside [chosen], for what it asks; null otherwise. */
private fun proposalOffer(account: Account, proposal: DappProposal, chosen: Collection<AccountId>): DappOffer? {
    val kinds = (proposal.requiredNetworks + chosen.map { it.network }).map { it.testnet }.toSet()
    return when {
        account.network !in proposal.networks -> DappOffer.NOT_ASKED
        kinds.any { it != account.network.testnet } -> DappOffer.OTHER_FUNDS
        else -> null
    }
}

/** The connections, and every account of the wallet with whether a dApp may be offered it. */
data class DappOverview(
    val connections: DappConnections,
    val accounts: List<DappAccountChoice>,
    /** The account list cannot be read: nothing can be offered. */
    val accountsUnreadable: Boolean = false,
)

/** Whether [account] may be offered to any dApp, with the networks [enabled]: whatever the proposal asks. */
fun dappOffer(account: Account, enabled: Set<Network>): DappOffer = when {
    account.network !in enabled -> DappOffer.NETWORK_OFF
    // A watched Safe is offered: its owners sign for it, here or elsewhere.
    account.kind == AccountKind.EOA && account.custody == AccountCustody.Watched -> DappOffer.NO_KEY
    else -> DappOffer.OFFERED
}

/**
 * Whether [account] may be offered to [proposal]'s dApp, with the networks [enabled] and the accounts
 * [chosen] so far (screen D3): on a network it asks for, of the kind of funds it requires and the
 * [chosen] ones are, and offered at all ([dappOffer]).
 */
fun dappOffer(
    account: Account,
    enabled: Set<Network>,
    proposal: DappProposal,
    chosen: Collection<AccountId> = emptyList(),
): DappOffer = proposalOffer(account, proposal, chosen) ?: dappOffer(account, enabled)

/** The dApp connections with the accounts a proposal may be approved with (screens D1 and D3). */
class ObserveDappConnections(
    private val sessions: DappSessions,
    private val accounts: AccountRepository,
    private val networks: NetworkSettings,
) {
    operator fun invoke(): Flow<DappOverview> =
        combine(sessions.observe(), accounts.observeAccounts(), networks.observe()) { connections, list, enabled ->
            when (list) {
                is AccountList.Loaded ->
                    DappOverview(connections, list.accounts.map { DappAccountChoice(it, dappOffer(it, enabled)) })
                is AccountList.Unreadable -> DappOverview(connections, emptyList(), accountsUnreadable = true)
            }
        }.distinctUntilChanged()
}

/** Pairs with the dApp of a `wc:` URI the user pasted (screen D2). */
class PairDapp(private val sessions: DappSessions) {
    suspend operator fun invoke(uri: String): DappPairing = sessions.pair(uri.trim())
}

/**
 * Approves a proposal with [accounts]: each must be one of the wallet's accounts the proposal's dApp may be
 * offered ([dappOffer]), on a network it asked for, all of one kind of funds with what it requires, so
 * that nothing a screen got wrong reaches the dApp ([DappApproval.InvalidAccounts]).
 */
class ApproveDappProposal(
    private val sessions: DappSessions,
    private val accounts: AccountRepository,
    private val networks: NetworkSettings,
) {
    suspend operator fun invoke(proposal: DappProposalId, chosen: List<AccountId>): DappApproval {
        val asked = (sessions.observe().first() as? DappConnections.On)?.proposals?.find { it.id == proposal }
            ?: return DappApproval.NoSuchProposal
        val list = accounts.observeAccounts().first() as? AccountList.Loaded ?: return DappApproval.InvalidAccounts
        val enabled = networks.observe().first()
        val wallet = list.accounts.associateBy { it.id }
        // Each against the others chosen: one of the other kind of funds than any of them is refused.
        val valid = chosen.isNotEmpty() && chosen.toSet().size == chosen.size && chosen.all { id ->
            val account = wallet[id] ?: return@all false
            dappOffer(account, enabled, asked, chosen - id) == DappOffer.OFFERED
        }
        if (!valid) return DappApproval.InvalidAccounts
        return sessions.approve(proposal, chosen)
    }
}

/** Rejects a proposal (screen D3). */
class RejectDappProposal(private val sessions: DappSessions) {
    suspend operator fun invoke(proposal: DappProposalId): Boolean = sessions.reject(proposal)
}

/** Ends a session (screen D1). */
class DisconnectDapp(private val sessions: DappSessions) {
    suspend operator fun invoke(session: DappSessionId): Boolean = sessions.disconnect(session)
}

/**
 * Keeps each session's accounts to those a dApp may still be offered ([dappOffer]), while it runs: one
 * removed from the wallet, whose key was removed, or whose network was turned off is taken out of
 * every session that sees it, the dApp told (`accountsChanged`), and a session left with none is
 * ended. A network counts as turned off only once the setting has answered it as on while this runs: a
 * setting that cannot be read is answered with the build's defaults ([NetworkSettings.observe]), and a
 * release build's default has Sepolia off, so a network off from the start ends no session on that word.
 * A defaults answer of "on" counts as one (a debug build's Sepolia, say), so a later read of "off" ends
 * the sessions then, which is the stored setting. Its requests are refused all the same. The host runs it
 * beside the relay connection.
 */
class FollowDappAccounts(
    private val sessions: DappSessions,
    private val accounts: AccountRepository,
    private val networks: NetworkSettings,
) {
    suspend operator fun invoke() {
        val seenOn = mutableSetOf<Network>()
        combine(sessions.observe(), accounts.observeAccounts(), networks.observe()) { connections, list, enabled ->
            Triple(connections, list, enabled)
        }
            .collect { (connections, list, enabled) ->
                val on = connections as? DappConnections.On ?: return@collect
                // An unreadable list is no news of a removal: nothing is changed on its word.
                val loaded = list as? AccountList.Loaded ?: return@collect
                // Nor is a damaged list set aside, which reads as one without its accounts.
                if (loaded.damagedListSetAside) return@collect
                seenOn += enabled
                // A network never read as on is not known to be off: it may be an unreadable setting.
                val judged = enabled + (Network.entries - seenOn)
                val held = loaded.accounts
                    .filter { dappOffer(it, judged) == DappOffer.OFFERED }
                    .map { it.id }
                    .toSet()
                for (session in on.sessions) {
                    val kept = session.accounts.filter { it in held }
                    when {
                        kept.size == session.accounts.size -> Unit
                        kept.isEmpty() -> sessions.disconnect(session.id)
                        else -> sessions.updateAccounts(session.id, kept)
                    }
                }
            }
    }
}

/** Extends a session to seven days from now (screen D1). */
class ExtendDappSession(private val sessions: DappSessions) {
    suspend operator fun invoke(session: DappSessionId): Boolean = sessions.extend(session)
}

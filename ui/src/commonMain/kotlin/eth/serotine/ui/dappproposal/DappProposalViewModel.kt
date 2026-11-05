package eth.serotine.ui.dappproposal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.DappApproval
import eth.serotine.domain.repository.DappConnections
import eth.serotine.domain.repository.DappProposalId
import eth.serotine.domain.repository.DappRefusal
import eth.serotine.domain.usecase.ApproveDappProposal
import eth.serotine.domain.usecase.DappOffer
import eth.serotine.domain.usecase.ObserveDappConnections
import eth.serotine.domain.usecase.RejectDappProposal
import eth.serotine.ui.components.shortAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
) {
    /** Approve is greyed out, still tappable, while it cannot be done (owner ruling, 2026-10-18, #55). */
    val approveUnavailable: Boolean get() = refusal != null || accounts.none { it.chosen }
}

/** One account on its [network], [chosen] or not; [unavailable] is why this dApp may not be offered it, or null. */
data class AccountOption(
    val key: String,
    val name: String,
    val address: String,
    val chosen: Boolean,
    val unavailable: String?,
    /** Its network, by name ("Gnosis Chain"): a dApp sees an account on its network alone. */
    val network: String = "",
)

/** What a tap on a greyed-out item says. */
data class Explanation(val title: String, val reason: String)

/** The proposal review's ViewModel: approves with the accounts chosen, or rejects. */
class DappProposalViewModel(
    /** The proposal, as its route names it: `pairing/id`. */
    proposalKey: String,
    observeDappConnections: ObserveDappConnections,
    private val approveDappProposal: ApproveDappProposal,
    private val rejectDappProposal: RejectDappProposal,
) : ViewModel() {

    private val id: DappProposalId? = proposalKey.split('/').takeIf { it.size == 2 }?.let { (pairing, number) ->
        number.toLongOrNull()?.let { DappProposalId(pairing, it) }
    }

    private data class Local(
        val chosen: Set<String> = emptySet(),
        val busy: Boolean = false,
        val notice: String? = null,
        val explaining: Explanation? = null,
        val done: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<DappProposalUiState> = combine(observeDappConnections(), local) { overview, local ->
        val proposal = (overview.connections as? DappConnections.On)?.proposals?.find { it.id == id }
            // No longer waiting: the screen leaves, unless an answer that did not take says why first.
            ?: return@combine if (local.notice != null && !local.done) {
                DappProposalUiState(loading = false, ended = true, notice = local.notice)
            } else {
                DappProposalUiState(loading = false, gone = true, done = local.done)
            }
        // What is chosen and still offered on its own: each account is judged beside the others chosen.
        val ticked = overview.accounts.filter { keyOf(it.account.id) in local.chosen }.map { it.account.id }
        val options = overview.accounts.map { choice ->
            val offer =
                if (proposal.refusal != null) choice.offer else choice.offerTo(proposal, ticked - choice.account.id)
            AccountOption(
                key = keyOf(choice.account.id),
                name = choice.account.label,
                address = shortAddress(choice.account.address.hex),
                chosen = offer == DappOffer.OFFERED && keyOf(choice.account.id) in local.chosen,
                unavailable = offerLine(offer, choice.account.network),
                network = choice.account.network.displayName,
            )
        }
        val chosenKeys = options.filter { it.chosen }.map { it.key }.toSet()
        val chosenKinds = overview.accounts.filter { keyOf(it.account.id) in chosenKeys }
            .map { it.account.network.testnet }.toSet()
        val held = Network.entries
            .filter { it in proposal.networks && (chosenKinds.isEmpty() || it.testnet in chosenKinds) }
        DappProposalUiState(
            loading = false,
            name = proposal.dapp.name,
            url = proposal.dapp.url,
            description = proposal.dapp.description,
            networks = held.map(::networkLine),
            networksNote = if (held.map { it.testnet }.toSet().size > 1) BOTH_KINDS else null,
            asks = proposal.methods.sortedBy { METHOD_ORDER.indexOf(it) }.map(::methodLine),
            refusal = proposal.refusal?.let(::refusalLine),
            accounts = options,
            busy = local.busy,
            notice = local.notice ?: if (overview.accountsUnreadable) ACCOUNTS_UNREADABLE else null,
            explaining = local.explaining,
            done = local.done,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DappProposalUiState())

    /** Chooses or unchooses the account [key]; one a dApp may not be offered says why instead. */
    fun toggle(key: String) {
        val option = uiState.value.accounts.find { it.key == key } ?: return
        if (option.unavailable != null) {
            local.update { it.copy(explaining = Explanation("Not offered to this dApp", option.unavailable)) }
            return
        }
        local.update { it.copy(chosen = if (key in it.chosen) it.chosen - key else it.chosen + key, notice = null) }
    }

    fun dismissExplanation() = local.update { it.copy(explaining = null) }

    fun approve() {
        val state = uiState.value
        val proposal = id ?: return
        if (state.busy) return
        state.refusal?.let { reason ->
            local.update { it.copy(explaining = Explanation("Cannot be approved", reason)) }
            return
        }
        val chosen = state.accounts.filter { it.chosen }.mapNotNull { idOf(it.key) }
        if (chosen.isEmpty()) {
            local.update { it.copy(explaining = Explanation("Choose an account", CHOOSE_ACCOUNT)) }
            return
        }
        local.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val approval = approveDappProposal(proposal, chosen)
            local.update {
                it.copy(busy = false, done = approval is DappApproval.Approved, notice = approvalLine(approval))
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
            DappRefusal.OTHER_CHAIN -> "This dApp requires a network Serotine does not connect dApps on. Only " +
                "Ethereum, Gnosis Chain and Sepolia are, so it can only be rejected."
            DappRefusal.MIXED_FUNDS -> "This dApp requires a test network and a network with real funds at once. " +
                "A connection holds one or the other, never both, so it can only be rejected."
            DappRefusal.NO_NETWORK -> "This dApp asks for none of the networks Serotine connects dApps on: " +
                "Ethereum, Gnosis Chain and Sepolia. It can only be rejected."
            DappRefusal.METHOD -> "This dApp requires a way of signing Serotine does not offer, such as " +
                "eth_sign, so it can only be rejected."
            DappRefusal.EVENT -> "This dApp requires notices Serotine does not send, so it can only be rejected."
            DappRefusal.NAMESPACE ->
                "This dApp requires a chain that is not an Ethereum one, so it can only be rejected."
        }

        /** Why an account on [network] is not offered, for [offer]; null when it is. */
        fun offerLine(offer: DappOffer, network: Network): String? = when (offer) {
            DappOffer.OFFERED -> null
            DappOffer.NOT_ASKED -> "This dApp did not ask for ${network.displayName}."
            DappOffer.OTHER_FUNDS -> if (network.testnet) {
                "${network.displayName} is a test network. A connection holding real funds cannot include it."
            } else {
                "${network.displayName} holds real funds. A connection on a test network cannot include it."
            }
            DappOffer.NETWORK_OFF -> "${network.displayName} is off. Turn it on in Settings, under Networks."
            DappOffer.NO_KEY -> "This account is watched: there is no key for it on this device."
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

package eth.serotine.ui.dappproposal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Address
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
 * dApp says of itself (its claim), the chain, what it asks to do, why it can only be rejected when
 * it can, and every account, those a dApp may not be offered greyed out with the reason. [gone] once
 * the proposal is no longer waiting; [done] once answered.
 */
data class DappProposalUiState(
    val loading: Boolean = true,
    val gone: Boolean = false,
    val name: String = "",
    val url: String = "",
    val description: String = "",
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

/** One account, [chosen] or not; [unavailable] is why a dApp may not be offered it, or null. */
data class AccountOption(
    val key: String,
    val name: String,
    val address: String,
    val chosen: Boolean,
    val unavailable: String?,
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
            ?: return@combine DappProposalUiState(loading = false, gone = true, done = local.done)
        DappProposalUiState(
            loading = false,
            name = proposal.dapp.name,
            url = proposal.dapp.url,
            description = proposal.dapp.description,
            asks = proposal.methods.sortedBy { METHOD_ORDER.indexOf(it) }.map(::methodLine),
            refusal = proposal.refusal?.let(::refusalLine),
            accounts = overview.accounts.map { choice ->
                AccountOption(
                    key = choice.account.address.hex,
                    name = choice.account.label,
                    address = shortAddress(choice.account.address.hex),
                    chosen = choice.offer == DappOffer.OFFERED && choice.account.address.hex in local.chosen,
                    unavailable = offerLine(choice.offer),
                )
            },
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
            local.update { it.copy(explaining = Explanation("Not offered to dApps", option.unavailable)) }
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
        val chosen = state.accounts.filter { it.chosen }.mapNotNull { Address.parse(it.key) }
        if (chosen.isEmpty()) {
            local.update { it.copy(explaining = Explanation("Choose an account", CHOOSE_ACCOUNT)) }
            return
        }
        local.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val approval = approveDappProposal(proposal, chosen)
            local.update { it.copy(busy = false, done = approval is DappApproval.Approved, notice = approvalLine(approval)) }
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
        const val CHOOSE_ACCOUNT = "Choose at least one account for the dApp to see."
        const val ACCOUNTS_UNREADABLE = "Your accounts cannot be read right now, so none can be offered."

        private val METHOD_ORDER = listOf("personal_sign", "eth_signTypedData_v4", "eth_sendTransaction", "wallet_switchEthereumChain")

        fun methodLine(method: String): String = when (method) {
            "personal_sign" -> "Ask you to sign messages, such as signing in"
            "eth_signTypedData_v4" -> "Ask you to sign structured data, such as permits"
            "eth_sendTransaction" -> "Ask you to send transactions, each one reviewed here"
            "wallet_switchEthereumChain" -> "Ask to stay on Sepolia"
            else -> method
        }

        fun refusalLine(refusal: DappRefusal): String = when (refusal) {
            DappRefusal.OTHER_CHAIN -> "This dApp requires a network other than Sepolia. dApp connections are on Sepolia " +
                "only for now, so it can only be rejected."
            DappRefusal.NO_SEPOLIA -> "This dApp does not ask for Sepolia. dApp connections are on Sepolia only for now, " +
                "so it can only be rejected."
            DappRefusal.METHOD -> "This dApp requires a way of signing Serotine does not offer, such as eth_sign, so it " +
                "can only be rejected."
            DappRefusal.EVENT -> "This dApp requires notices Serotine does not send, so it can only be rejected."
            DappRefusal.NAMESPACE -> "This dApp requires a chain that is not an Ethereum one, so it can only be rejected."
        }

        fun offerLine(offer: DappOffer): String? = when (offer) {
            DappOffer.OFFERED -> null
            DappOffer.NOT_SEPOLIA -> "dApp connections are on Sepolia only for now."
            DappOffer.SEPOLIA_OFF -> "Sepolia is off. Turn it on in Settings, under Networks."
            DappOffer.NO_KEY -> "This account is watched: there is no key for it on this device."
        }

        fun approvalLine(approval: DappApproval): String? = when (approval) {
            is DappApproval.Approved -> null
            DappApproval.NoSuchProposal, DappApproval.Expired -> "This request has expired. Connect again from the dApp."
            is DappApproval.Refused -> refusalLine(approval.why)
            DappApproval.InvalidAccounts -> "One of the accounts chosen cannot be offered to a dApp. Choose again."
            DappApproval.NotSaved -> NOT_SAVED
            DappApproval.Off -> "This build has dApp connections switched off."
        }
    }
}

package eth.serotine.domain.repository

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Network
import kotlinx.coroutines.flow.Flow

/** The CAIP-2 id of this network's chain, as WalletConnect names it: `eip155:` and its EIP-155 chain id. */
val Network.caip2: String get() = "eip155:$chainId"

/**
 * The network whose chain [chain] names, as CAIP-2 (`eip155:1`), or null for one Punicus has no dApp
 * sessions on (docs/walletconnect.md, ruling 9 as amended, 2026-11-05). Strict: the chain id in decimal,
 * with no sign or leading zero.
 */
fun dappNetworkOf(chain: String): Network? {
    val id = chain.removePrefix("eip155:").takeIf { it != chain } ?: return null
    if (id.isEmpty() || id.length > 18 || id.first() == '0' || !id.all { it in '0'..'9' }) return null
    return Network.ofChainId(id.toLong())
}

/**
 * A dApp's description of itself, as it states it when it connects (docs/walletconnect.md, ruling 7):
 * its claim, never verified, and shown as such. No icon: fetching one would tell a host of the dApp's
 * choosing that this device is connecting.
 */
data class DappMetadata(val name: String, val url: String, val description: String)

/** What names a session proposal: the pairing it came on and its id there (ids are unique per pairing only). */
data class DappProposalId(val pairing: String, val id: Long)

/** What names a session: its topic. */
data class DappSessionId(val topic: String)

/** Why a proposal can only be rejected (docs/walletconnect.md, *Pairing and the session proposal*). */
enum class DappRefusal {
    /** It requires a chain Punicus has no dApp sessions on: none but Ethereum, Gnosis Chain and Sepolia. */
    OTHER_CHAIN,

    /**
     * It requires real funds and testnet together: a session holds Ethereum and Gnosis Chain, or Sepolia
     * alone, never both (owner ruling, 2026-11-05, on #22).
     */
    MIXED_FUNDS,

    /** It asks for no chain Punicus has dApp sessions on at all. */
    NO_NETWORK,

    /** It requires a method outside the closed list (ruling 6), `eth_sign` among them. */
    METHOD,

    /** It requires an event a session here does not carry. */
    EVENT,

    /** It requires a namespace other than `eip155`. */
    NAMESPACE,
}

/** A dApp's proposal of a session, waiting for the user. */
data class DappProposal(
    val id: DappProposalId,
    val dapp: DappMetadata,
    /** The chains it requires and those it would also take, as CAIP-2 ids (`eip155:11155111`). */
    val requiredChains: Set<String>,
    val optionalChains: Set<String>,
    /** The methods a session with it would carry: what it asked for that is on the closed list. */
    val methods: Set<String>,
    /** Why it can only be rejected, or null when it can be approved. */
    val refusal: DappRefusal?,
    /** When the dApp stops waiting for an answer, in unix seconds. */
    val expiresAtSeconds: Long,
) {
    /** The networks it requires: one kind of funds, or it is refused ([DappRefusal.MIXED_FUNDS]). */
    val requiredNetworks: Set<Network> get() = requiredChains.mapNotNull(::dappNetworkOf).toSet()

    /**
     * The networks a session with it may hold, so the networks of the accounts it may be offered: those it
     * asks for, required or optional, that Punicus serves; of the kind of funds its required ones are, when
     * it requires any.
     */
    val networks: Set<Network>
        get() {
            val kind = requiredNetworks.map { it.testnet }.distinct().singleOrNull() ?: return askedNetworks
            return askedNetworks.filter { it.testnet == kind }.toSet()
        }

    /** Every network it asks for, required or optional, that Punicus serves, of either kind of funds. */
    val askedNetworks: Set<Network> get() = (requiredChains + optionalChains).mapNotNull(::dappNetworkOf).toSet()
}

/** A session with a dApp. */
data class DappSession(
    val id: DappSessionId,
    val dapp: DappMetadata,
    /** The accounts the dApp sees, each on its own network. */
    val accounts: List<AccountId>,
    val methods: List<String>,
    /** When it ends unless extended, in unix seconds. */
    val expiresAtSeconds: Long,
    /** Whether the dApp has confirmed the session. */
    val acknowledged: Boolean,
    /**
     * The networks the session holds, of one kind of funds: its requests may name these alone, and its
     * dApp may switch between them. Its accounts' networks, and those the dApp required.
     */
    val networks: Set<Network> = accounts.map { it.network }.toSet(),
    /**
     * The networks its dApp required: each keeps an account of the session, or the session ends, as WalletConnect
     * has a session cover every chain its proposal requires.
     */
    val requiredNetworks: Set<Network> = emptySet(),
)

/** Whether the relay connection is open: dApp messages arrive only while it is. */
enum class DappRelayLink { CONNECTED, CONNECTING, DISCONNECTED }

/** What [DappSessions.observe] says. */
sealed interface DappConnections {

    /** This build carries no WalletConnect project ID: dApp connections are switched off (ruling 2). */
    data object Off : DappConnections

    /** dApp connections are on: the relay's [link], the [proposals] waiting and the open [sessions]. */
    data class On(
        val link: DappRelayLink,
        val proposals: List<DappProposal>,
        val sessions: List<DappSession>,
    ) : DappConnections
}

/** What [DappSessions.pair] made of a `wc:` URI. */
sealed interface DappPairing {
    /** Paired: the dApp's proposal arrives on it. [waitingForRelay] while the relay cannot be reached yet. */
    data class Paired(val waitingForRelay: Boolean) : DappPairing

    /** Not a WalletConnect v2 pairing URI. */
    data object Unreadable : DappPairing

    /** A pairing URI of another version (v1's among them), which this build does not read. */
    data object UnsupportedVersion : DappPairing

    /** A pairing URI with [parameter], which this build cannot apply and so refuses (Myotis #314). */
    data class UnsupportedParameter(val parameter: String) : DappPairing

    /** Its expiry has passed. */
    data object Expired : DappPairing

    /** It names the methods the dApp will send, and a session proposal is not among them. */
    data object NoProposal : DappPairing

    /** Its topic is an open session's: refused, the session kept. */
    data object TopicInUse : DappPairing

    /** The state could not be saved: nothing was paired. */
    data object NotSaved : DappPairing

    /** dApp connections are switched off in this build. */
    data object Off : DappPairing
}

/** What [DappSessions.approve] did. */
sealed interface DappApproval {
    /** Approved; [delivered] false while the relay has not taken it yet: it is sent again, never lost. */
    data class Approved(val session: DappSessionId, val delivered: Boolean) : DappApproval

    /** No such proposal is waiting (answered, or gone). */
    data object NoSuchProposal : DappApproval

    /** The dApp has stopped waiting for an answer. */
    data object Expired : DappApproval

    /** The proposal asks for what no session here may have. */
    data class Refused(val why: DappRefusal) : DappApproval

    /** No account, or one a dApp may not be offered. */
    data object InvalidAccounts : DappApproval

    /** The state could not be saved: nothing was approved. */
    data object NotSaved : DappApproval

    /** dApp connections are switched off in this build. */
    data object Off : DappApproval
}

/**
 * dApp sessions over WalletConnect v2 (docs/walletconnect.md, *Direction 1*): pairing from a `wc:`
 * URI, the proposals a dApp makes, the sessions approved, with the closed list of methods (owner rulings,
 * 2026-11-03, #22), each on Ethereum and Gnosis Chain or on Sepolia alone (owner ruling, 2026-11-05, on
 * #22). What arrives this way is untrusted input: the adapter
 * checks it, and a proposal that asks for more than a session may have comes with its [DappRefusal].
 * Never throws.
 */
interface DappSessions {

    /** The connections, on each change. */
    fun observe(): Flow<DappConnections>

    /** Pairs with the dApp of [uri], a `wc:` URI as the dApp shows it. */
    suspend fun pair(uri: String): DappPairing

    /**
     * Approves [proposal] with the [accounts] the dApp is to see: on networks it asked for, of one kind of
     * funds, with at least one on each network it requires. The session holds their networks and those the
     * proposal requires.
     */
    suspend fun approve(proposal: DappProposalId, accounts: List<AccountId>): DappApproval

    /** Rejects [proposal], telling the dApp. True once saved. */
    suspend fun reject(proposal: DappProposalId): Boolean

    /** Ends [session], telling the dApp. True once saved. */
    suspend fun disconnect(session: DappSessionId): Boolean

    /** Extends [session] to seven days from now. True once saved. */
    suspend fun extend(session: DappSessionId): Boolean

    /**
     * Gives [session] the [accounts] instead, telling the dApp (`accountsChanged`), each on one of the
     * session's networks. True once saved.
     */
    suspend fun updateAccounts(session: DappSessionId, accounts: List<AccountId>): Boolean
}

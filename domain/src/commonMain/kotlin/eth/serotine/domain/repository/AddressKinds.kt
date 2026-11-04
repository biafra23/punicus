package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import kotlinx.coroutines.flow.Flow

/**
 * The port for what is at an address on chain — an account a key controls, one that has
 * delegated its code (EIP-7702), a Safe, or another contract — as the embedded light client
 * proves it from the address's code and storage. :data implements it over the Myotis engine
 * (`eth.serotine.data.engine`), each network's by its own light client; nothing else may answer
 * it (CLAUDE.md, *Trust*). What is at an address is per network: the same address may be a Safe
 * on one chain and hold no code on another.
 */
interface AddressKinds {
    /**
     * The kind of [address] on [network], re-emitted when the verified head moves or the light client's state changes;
     * consecutive equal readings are not repeated. A [KindReading.Verified] was proven at its block; when no proof is
     * possible the reading says why. A [network] no adapter serves here is refused, never answered from another network
     * ([ReadUnavailable.NO_ENGINE]). Never fails.
     */
    fun observeKind(network: Network, address: Address): Flow<KindReading>
}

/** What is at an address, as its verified code says. */
sealed interface AddressKind {

    /**
     * No code: an externally owned account, controlled by its key alone — or an address whose
     * contract is not deployed yet (a counterfactual Safe), which nothing on chain tells
     * apart until it is.
     */
    data object Eoa : AddressKind

    /**
     * An externally owned account whose code is an EIP-7702 delegation designator
     * (`0xef0100` ++ [delegate]): still controlled by its key, and also by whatever the
     * delegate's code lets act for it.
     */
    data class DelegatedEoa(val delegate: Address) : AddressKind

    /**
     * A Safe{Wallet} account (docs/wallet-ux.md, *Safes*): a pinned Safe proxy's code, whose
     * singleton is a pinned canonical Safe, with the [state] its storage holds.
     */
    data class Safe(val state: SafeState) : AddressKind

    /** Any other code: a contract, a Safe proxy of a singleton not pinned among them. */
    data object Contract : AddressKind
}

/**
 * A Safe's state, as its verified storage holds it: its singleton's [version] ("1.4.1") and
 * whether it is the L2 variant ([l2]), its [owners] in their on-chain order, the [threshold]
 * of their signatures a transaction needs, its [nonce], the enabled [modules] — each of which
 * can move the Safe's funds without the owners' signatures — its [guard], which checks every
 * transaction, and its [moduleGuard] (1.5.0 on), which checks the modules' (null: none).
 */
data class SafeState(
    val version: String,
    val l2: Boolean,
    val owners: List<Address>,
    val threshold: Int,
    val nonce: Long,
    val modules: List<Address>,
    val guard: Address?,
    val moduleGuard: Address? = null,
)

/** One answer of [AddressKinds.observeKind]. */
sealed interface KindReading {

    /** The light client has no verified head yet. */
    data object Syncing : KindReading

    /** The address holds [kind] at block [blockNumber], verified by the light client (a Safe's state too). */
    data class Verified(val kind: AddressKind, val blockNumber: Long) : KindReading

    /** No verified answer can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: ReadUnavailable) : KindReading
}

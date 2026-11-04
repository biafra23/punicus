package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Wei
import kotlinx.coroutines.flow.Flow

/**
 * The port for native-currency balances, as the embedded light client proves them. :data
 * implements it over the Myotis engine (`eth.serotine.data.engine`), each network's by its own
 * light client (CLAUDE.md, *Data sources*); nothing else may answer it — no RPC provider, no
 * indexer, no cached guess (CLAUDE.md, *Trust*). The one older answer it gives is the light
 * client's own last proof, labelled as such ([BalanceReading.LastKnown]).
 */
interface NativeBalances {
    /**
     * The balance of [address] on [network], re-emitted when the verified head moves or the light client's state
     * changes; consecutive equal readings are not repeated. A [BalanceReading.Verified] was proven against the sync
     * committee at its block; when no proof is possible the reading says why. A [network] no adapter serves here is
     * refused, never answered from another network ([ReadUnavailable.NO_ENGINE]). Never fails.
     */
    fun observeBalance(network: Network, address: Address): Flow<BalanceReading>
}

/** One answer of [NativeBalances.observeBalance]. */
sealed interface BalanceReading {

    /** The light client has no verified head yet, and no earlier proof of this balance. */
    data object Syncing : BalanceReading

    /**
     * The light client has no verified head it can read at (it is catching up, asleep, waiting
     * for a fresh head or for a peer serving proofs), and [wei] is the balance it last
     * verified, at block [blockNumber], whose verified head was produced at [asOf] (unix
     * seconds; null when unknown). It may have changed since: shown as such (owner's request,
     * 2026-10-19), and never a balance to act on — a quote or a signature treats it as
     * [Syncing].
     */
    data class LastKnown(val wei: Wei, val blockNumber: Long, val asOf: Long?) : BalanceReading

    /**
     * [wei] is the balance at block [blockNumber], verified by the light client at a verified
     * head produced at [provenAt] (unix seconds; null when unknown). In simple mode a proof may
     * stand across heads on a network without block access lists, for up to an hour
     * (docs/simple-ui.md): [provenAt] says how old it is.
     */
    data class Verified(val wei: Wei, val blockNumber: Long, val provenAt: Long? = null) : BalanceReading

    /** No verified balance can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: ReadUnavailable) : BalanceReading

}

/** Why a verified read — a balance, an address's kind, a token balance — has no answer right now. */
enum class ReadUnavailable {
    /**
     * This device has no running light client for the network asked about: the engine does not ship for its ABI or
     * platform, or it failed to start.
     */
    NO_ENGINE,

    /**
     * The light client refuses to sync: its best trust anchor is older than the
     * weak-subjectivity bound, and accepting that risk is the user's decision.
     */
    STALE_ANCHOR,

    /**
     * No verified answer could be had this time: the light client is synced but no peer
     * served the proof, or the engine's state could not be read at all.
     */
    UNPROVEN,

    /**
     * The light client is still syncing (starting, bootstrapping from its checkpoint, catching
     * up on sync committees): no head is verified yet, and one will be once it has caught up.
     * Asking again before then changes nothing (docs/simple-ui.md, *A read refused while syncing
     * says so*).
     */
    SYNCING,

    /**
     * The engine will not answer this read on this build, and asking again changes nothing
     * (the Myotis engine's REFUSED, say past a fork it cannot execute).
     */
    REFUSED,

    /**
     * No light client runs for the network asked about right now: it is turned off in this
     * wallet, or on with nothing needing it yet (docs/networks.md, *Networks on and off*).
     */
    NETWORK_OFF,
}

package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.repository.FinalizedBlock
import org.kotlincrypto.hash.sha3.Keccak256

/**
 * What the engine adapters need from a verified light client: a [snapshot] of its state,
 * which never blocks, and the proofs ([proveBalance], [proveCode]), which do (they fetch a
 * proof from a peer) and so run on the adapters' dispatcher, never a caller's thread.
 */
internal interface VerifiedChain {

    /** The engine's state right now (`ChainHandle.status()`). Never blocks. */
    fun snapshot(): EngineSnapshot

    /**
     * The balance of [address], proven against the light client's beacon-attested state;
     * null when the engine could not prove it. Blocks, bounded by the engine (about 90 s).
     */
    fun proveBalance(address: Address): ProvenBalance?

    /**
     * The code at [address] — none for an account without code or one that does not exist —
     * proven against the light client's beacon-attested state; null when the engine could
     * not prove it. Blocks, bounded by the engine.
     */
    fun proveCode(address: Address): ProvenCode?

    /**
     * [data] called on the contract at [to], from no sender, run against the light client's
     * beacon-attested state; null when the engine could not answer verified right now.
     * Blocks, bounded by the engine.
     */
    fun call(to: Address, data: ByteArray): CallOutcome?

    /**
     * Storage slot [slot] of [address] — or, given a [holder], the entry of the mapping at
     * [slot] under that key (`keccak256(holder ‖ slot)`) — proven against the account's proven
     * storage root and the beacon-attested state; null when the engine could not prove it.
     * Blocks, bounded by the engine.
     */
    fun proveSlot(address: Address, slot: Long, holder: Address? = null): ProvenSlot?

    /**
     * The storage word at the 32-byte [slot] of [address] (a slot no number names, such as a
     * hashed one), as a verified read at the head; null when it could not be read verified.
     * Blocks, bounded by the engine.
     */
    fun storageAt(address: Address, slot: ByteArray): String?

    /**
     * Block [number]'s header as the engine's verified `getBlockByNumber` answers it (its JSON block
     * object, without the transactions); null when it could not be read verified, and from an
     * engine that offers no such read. Blocks, bounded by the engine.
     */
    fun blockByNumber(number: Long): String? = null
}

/** A verified call's answer. */
internal sealed interface CallOutcome {

    /**
     * It ran and returned [hex] (lower-case, no `0x`), against the state of [blockNumber] (null:
     * not said), whose hash is [blockHash] when the engine proved it (lower-case `0x` hex).
     */
    data class Returned(val hex: String, val blockNumber: Long?, val blockHash: String? = null) : CallOutcome

    /** It ran and reverted: a verified answer too. */
    data class Reverted(val blockNumber: Long?, val blockHash: String? = null) : CallOutcome

    /** The engine will not answer it on this build, and asking again changes nothing. */
    data object Refused : CallOutcome
}

/**
 * A balance in decimal wei, proven at [blockNumber], the block the proof anchors to, whose hash
 * is [blockHash] when the engine proved it (lower-case `0x` hex).
 */
internal data class ProvenBalance(val wei: String, val blockNumber: Long, val blockHash: String? = null)

/**
 * A storage word as 64 lower-case hex digits ([valueHex]; all zero for an empty slot), shown as
 * proven at [blockNumber], and proven against [anchor]: two words are of one state when their
 * anchors are equal, whatever their block labels say (a label can be a peer's report).
 */
internal data class ProvenSlot(val valueHex: String, val blockNumber: Long, val anchor: ProofAnchor)

/**
 * Code as lower-case hex without `0x` (empty: none), shown as proven at [blockNumber], and
 * proven against [anchor] when the account proof matched an attested slot (null: a header-chain
 * walk, which leaves only the block label to compare).
 */
internal data class ProvenCode(val codeHex: String, val blockNumber: Long, val anchor: ProofAnchor? = null)

/**
 * What a proof was verified against, from the light client's own state: equal anchors are one
 * state, and only verified fields say which is older ([notBefore]), never a peer's label.
 */
internal sealed interface ProofAnchor {

    /** The proof's state root matched the attested beacon slot [slot]. */
    data class Attested(val slot: Long) : ProofAnchor

    /**
     * A header-chain walk proved the block [block] itself, whichever attested head it was walked
     * to: two walks of one block are one state, though the head moved between them. (The result
     * exposes no block hash, so the walked number is the identity.) A walk and an attested match
     * are never taken for one state, even of one block: the conservative side, a read again.
     */
    data class Walked(val block: Long) : ProofAnchor

    /**
     * Whether this anchor's state is not older than [other]'s, from verified fields alone: two
     * attested slots compared, two walked blocks compared; null when one was walked and the other
     * matched, which nothing verified orders.
     */
    fun notBefore(other: ProofAnchor): Boolean? = when {
        this is Attested && other is Attested -> slot >= other.slot
        this is Walked && other is Walked -> block >= other.block
        else -> null
    }
}

/** The Keccak-256 of [codeHex] (lower-case hex, no `0x`): its code hash, as lower-case hex. */
internal fun codeHash(codeHex: String): String = Keccak256().digest(codeHex.fromHex()).toHex()

/** Keccak-256 of no code: the code hash of every account without code. */
internal const val EMPTY_CODE_HASH = "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"

/**
 * The code of an account whose proof (verified at [blockNumber], against [anchor]) says it [exists] with the
 * code hash [codeHashHex]: none for an absent account or the empty hash; otherwise what
 * [fetchCode] returns, accepted only when it hashes to the proven value — the head may have
 * moved between the proof and the read, and code from another block must not carry this
 * one's label. Null, unproven, when the hash is missing or the code is not the proven code.
 */
internal fun provenCode(
    exists: Boolean,
    codeHashHex: String?,
    blockNumber: Long,
    anchor: ProofAnchor? = null,
    fetchCode: () -> ByteArray?,
): ProvenCode? {
    if (!exists) return ProvenCode("", blockNumber, anchor)
    val codeHash = codeHashHex?.removePrefix("0x")?.lowercase() ?: return null
    if (codeHash == EMPTY_CODE_HASH) return ProvenCode("", blockNumber, anchor)
    val code = fetchCode() ?: return null
    if (Keccak256().digest(code).toHex() != codeHash) return null
    return ProvenCode(code.toHex(), blockNumber, anchor)
}

/** The engine's state, or why there is none. */
internal sealed interface EngineSnapshot {

    /** The engine is still starting. */
    data object Booting : EngineSnapshot

    /** There is no engine on this device, or the host has shut down; this is final. */
    data object Down : EngineSnapshot

    /**
     * The network's light client failed to start. Not final: it is tried again when the network
     * is next asked to run (turned off and on, or needed again after idling), so a stream keeps
     * polling through it.
     */
    data object Failed : EngineSnapshot

    /**
     * The network's light client is stopped: the network is turned off in this wallet ([off]),
     * or on with nothing needing it. It starts again when asked: not final.
     */
    data class Stopped(val off: Boolean) : EngineSnapshot

    /**
     * The engine's report; see `io.myotis.api.StatusSnapshot` for each field. [upgrade] is
     * its upgrade advisory: an upgrade peers report that this build does not support, null
     * when none is (peer data, never verified).
     */
    data class Running(
        val phase: Phase,
        val paused: Boolean,
        val syncStartPeriod: Long,
        val syncCurrentPeriod: Long,
        val syncTargetPeriod: Long,
        val huntingLightClientServers: Boolean,
        val proofPeers: Int,
        val headBlock: Long,
        val headTimestamp: Long?,
        val headAgeMs: Long,
        val wsBoundPeriods: Long,
        val upgrade: NetworkUpgrade? = null,
        /** The beacon-finalized block's number (0: none yet). */
        val finalizedBlock: Long = 0,
        /**
         * The newest finalized block whose own header was read, with its timestamp ([FinalizedTimes]):
         * [finalizedBlock], or an older one until its header is had; null while none was.
         */
        val finalizedHeader: FinalizedBlock?,
    ) : EngineSnapshot {

        /**
         * The head reads can be verified at, or null when none can: synced, awake, with a
         * verified head younger than [FRESH_HEAD_MS], and a peer that serves state proofs.
         * The same gate Myotis's own readiness ladder applies (its `READY_HEAD_WARM_MS`).
         */
        val readableHead: Long?
            get() = headBlock.takeIf {
                phase == Phase.SYNCED && !paused && headAgeMs <= FRESH_HEAD_MS && proofPeers > 0 && it > 0
            }
    }

    /** `io.myotis.api.BeaconState`. */
    enum class Phase { STARTING, SYNCING, CATCHING_UP, SYNCED, STALE_ANCHOR }

    companion object {
        /** A verified head older than this is stale: reads wait for a fresh one. */
        const val FRESH_HEAD_MS = 45_000L
    }
}

package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.data.safe.SafeDeployments
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.kotlincrypto.hash.sha3.Keccak256

/**
 * [AddressKinds] over a verified light client: an address's code, proven by
 * [VerifiedChain.proveCode], read as no code (an EOA), an EIP-7702 delegation designator (an
 * EOA that has delegated), a Safe, or anything else (a contract). Polled, cached and refused
 * as [ProvenReads] describes.
 *
 * A Safe (docs/wallet-ux.md, *Safes*) is code whose hash is a pinned SafeProxy's ([safes]),
 * whose slot 0 names a pinned singleton, and whose storage holds a set-up Safe: its owners, the
 * linked list of the mapping at slot 2 walked from the sentinel `0x1`, as many as slot 3
 * counts, a threshold (slot 4) of at most that many, its nonce (slot 5), its modules (the list
 * at slot 1, whose sentinel every set-up Safe has written), its guard and, from 1.5.0, its
 * module guard. The lists are what the Safe's own getters return; an entry written outside
 * them (a setup delegatecall can) is not something reading them can rule out.
 *
 * Every numbered slot is a storage proof, and all of them must be of one state, no older than
 * the code's: one verified anchor ([ProofAnchor], the attested slot a proof matched; a block
 * label alone can be a peer's report) and one block, the one the reading is labelled with. That
 * rule has no exception, however often a read is made. Every proof that comes back null or throws
 * (a slow or lost peer), the code's first one included, is asked for again, up to [PROOF_TRIES]
 * times in all; a read the head moved under, or one a proof failed in after those tries, is made
 * again, for as long as [SAFE_READ_BUDGET] lasts from the first code proof ([timeSource] measures
 * it, checked between the engine's calls,
 * which each block and are not cut off; a cancelled read stops at its next call), a moment's
 * pause ([RETRY_PAUSE]) after a failed proof. When the
 * slots were of one state and only the code's anchor did not order before it (a walk against a
 * match, or none), the code is proven again too, so the next read can be of one state. The guards
 * are kept at hashed slots no storage proof names, so they are verified reads at the head, which
 * may be a head later than that label. Anything that does not match is a contract, never a Safe. A
 * Safe's reading stands for [SAFE_KEPT_FOR_HEADS] heads (a Safe takes a dozen proofs, and its
 * owners rarely change); any other kind's for one.
 *
 * A dozen proofs one after the other take long enough that the head often moves under them; the
 * root fix is the engine's, several slots in one proof at one block (biafra23/myotis#662), and
 * once it lands a Safe is read in one request. Until then, [debugLog] (debug builds only) says
 * which proof failed or straddled and how many reads a Safe took: public data, and no key is
 * anywhere near this path.
 */
class VerifiedAddressKinds internal constructor(
    private val served: Network,
    chain: VerifiedChain?,
    dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
    private val safes: SafeDeployments.Table,
    /** What a Safe's read budget ([SAFE_READ_BUDGET]) is measured with: a test's own clock, or the monotonic one. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** Where a Safe's read says what failed or straddled and how many reads it took; null: nowhere (release). */
    private val debugLog: ((String) -> Unit)? = null,
) : AddressKinds {

    private val reads = ProvenReads<Address, AddressKind>(
        chain,
        dispatcher,
        pollInterval,
        keptForHeads = { kind -> if (kind is AddressKind.Safe) SAFE_KEPT_FOR_HEADS else 1 },
        sharedForHeads = SAFE_KEPT_FOR_HEADS,
    ) { verified, address ->
        // One budget from the first proof on: the code's, asked for again like every other.
        val started = timeSource.markNow()
        val deadline = started + SAFE_READ_BUDGET
        val code = proof(deadline, { "${address.hex}: the code" }) { verified.proveCode(address) }
            ?: return@ProvenReads null
        when (val kind = kindOf(code.codeHex)) {
            AddressKind.Contract ->
                if (codeHash(code.codeHex) in safes.proxyCodeHashes) {
                    readSafe(verified, address, code, started, deadline)
                } else {
                    EngineReading.Proven(kind, code.blockNumber)
                }
            else -> EngineReading.Proven(kind, code.blockNumber)
        }
    }

    /**
     * The Safe at [safe], whose code is proven ([proven]), read again while a read fails, until
     * [SAFE_READ_BUDGET] runs out: at once when the head moved under it, after [RETRY_PAUSE] when a
     * proof failed (the engine may find another peer meanwhile). When the straddle was the code
     * against slots of one state, the code is proven again before the next read: proving the slots
     * alone again would meet the same code anchor every time. Code proven again that is no pinned
     * proxy's any more is read as what it is. Null: no read of one state could be had in time.
     */
    private suspend fun readSafe(
        chain: VerifiedChain,
        safe: Address,
        proven: ProvenCode,
        started: TimeMark,
        deadline: TimeMark,
    ): EngineReading<AddressKind>? {
        var code: ProvenCode? = proven
        var read = 1
        while (true) {
            val known = code
            val current = known ?: proof(deadline, { "Safe ${safe.hex}: the code" }) { chain.proveCode(safe) }
            val pause = if (current == null) {
                log { "Safe ${safe.hex}: read $read: the code, proven again, unproven" }
                true
            } else {
                if (known == null) {
                    val kind = kindOf(current.codeHex)
                    if (kind != AddressKind.Contract || codeHash(current.codeHex) !in safes.proxyCodeHashes) {
                        return EngineReading.Proven(kind, current.blockNumber)
                    }
                    code = current
                }
                val storage = SafeStorage(chain, safe, current, deadline)
                storage.read()?.let { reading ->
                    if (read > 1) log { "Safe ${safe.hex}: read in $read reads, ${started.elapsedNow()}" }
                    return reading
                }
                log { "Safe ${safe.hex}: read $read: ${storage.failure}" }
                if (storage.againstCode) code = null
                !storage.straddled
            }
            if (deadline.hasPassedNow()) {
                log { "Safe ${safe.hex}: not read after $read reads, ${started.elapsedNow()}: unproven" }
                return null
            }
            if (pause) delay(RETRY_PAUSE)
            read++
        }
    }

    /**
     * What [prove] proves, asked for again while it comes back null or throws, [PROOF_TRIES] times
     * in all, and never once [deadline] has passed; null: unproven. The caller's cancellation is
     * checked before each blocking call and after one that threw: a cancelled read ends there,
     * while anything else the engine throws (it crosses JNA: an Error too, and, as [ProvenReads]
     * has it, a `CancellationException` of the engine's own) is a failed proof. [what] names the
     * proof in the debug log.
     */
    private suspend fun <T : Any> proof(deadline: TimeMark, what: () -> String, prove: () -> T?): T? {
        for (attempt in 1..PROOF_TRIES) {
            if (deadline.hasPassedNow()) return null
            currentCoroutineContext().ensureActive()
            val proven = try {
                prove()
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                log { "${what()}: the engine threw ${e::class.simpleName}" }
                null
            }
            proven?.let { return it }
            if (attempt < PROOF_TRIES) log { "${what()} unproven, asked again (try ${attempt + 1} of $PROOF_TRIES)" }
        }
        return null
    }

    /** [message] to [debugLog], made only when there is one. */
    private inline fun log(message: () -> String) {
        debugLog?.invoke("Serotine: " + message())
    }

    /**
     * One read of the Safe storage at [safe]: every slot proven against one anchor, the verified
     * attested slot a proof matched (a block label alone can be a peer's report, so two slots
     * with equal labels and different anchors are of two states), shown with one block, and not
     * of a state before the [code]'s, as their anchors tell: attested slots compared, walked blocks
     * compared, and a walk against a match, which no verified field orders, a straddle.
     */
    private inner class SafeStorage(
        private val chain: VerifiedChain,
        private val safe: Address,
        private val code: ProvenCode,
        /** When the read gives up: a proof not yet asked for by then is not asked for. */
        private val deadline: TimeMark,
    ) {
        private val blocks = mutableSetOf<Long>()
        private val anchors = mutableSetOf<ProofAnchor>()

        /** Whether the slots read were proven at more than one block (or before the code): the head moved. */
        var straddled = false
            private set

        /**
         * Whether the straddle was the code's alone: the slots one state, which the code's anchor
         * does not order before (a walk against a match, none, or a later one).
         */
        var againstCode = false
            private set

        /** Why the read gave no reading, for the debug log: the proof that failed, or the straddle. */
        var failure = "no reading"
            private set

        /**
         * The word at [slot] (or under [key] in the mapping there), its proof asked for again while
         * it fails ([proof]); null: unproven.
         */
        private suspend fun word(slot: Long, key: Address? = null): String? {
            val name = "slot $slot" + key?.let { " under ${it.hex}" }.orEmpty()
            val proven = proof(deadline, { "Safe ${safe.hex}: $name" }) { chain.proveSlot(safe, slot, key) }
                ?: return unproven(name)
            blocks += proven.blockNumber
            anchors += proven.anchor
            return proven.valueHex
        }

        /** Null, with [failure] saying that [name]'s proof failed, or that the time ran out before it. */
        private fun unproven(name: String): Nothing? {
            failure = if (deadline.hasPassedNow()) "out of time at $name" else "$name unproven after $PROOF_TRIES tries"
            return null
        }

        suspend fun read(): EngineReading<AddressKind>? {
            val singleton = addressIn(word(SINGLETON_SLOT) ?: return null)?.let { safes.singletons[it] }
                ?: return proven(AddressKind.Contract)
            val owners = when (val walked = walk(OWNERS_SLOT, MAX_OWNERS)) {
                Walk.Unproven -> return null
                Walk.Broken -> return proven(AddressKind.Contract)
                is Walk.Entries -> walked.addresses
            }
            // A proven word that is no small number is no Safe's count.
            val ownerCount = number(word(OWNER_COUNT_SLOT) ?: return null) ?: return proven(AddressKind.Contract)
            val threshold = number(word(THRESHOLD_SLOT) ?: return null) ?: return proven(AddressKind.Contract)
            // Not set up, or not consistent: a contract, before any more is read.
            if (owners.isEmpty() || ownerCount != owners.size.toLong() || threshold !in 1..owners.size.toLong()) {
                return proven(AddressKind.Contract)
            }
            val nonce = number(word(NONCE_SLOT) ?: return null) ?: return proven(AddressKind.Contract)
            val modules = when (val walked = walk(MODULES_SLOT, MAX_MODULES)) {
                Walk.Unproven -> return null
                Walk.Broken -> return proven(AddressKind.Contract)
                is Walk.Entries -> walked.addresses
            }
            // Head reads: their slots are hashes no storage proof names. Only a version that
            // consults a module guard has one: a word in that slot of an older Safe is nothing.
            val guard = when (val read = guardAt(GUARD_SLOT)) {
                Guard.Unproven -> return null
                Guard.Broken -> return proven(AddressKind.Contract)
                is Guard.At -> read.address
            }
            val moduleGuard = if (!singleton.moduleGuard) {
                null
            } else {
                when (val read = guardAt(MODULE_GUARD_SLOT)) {
                    Guard.Unproven -> return null
                    Guard.Broken -> return proven(AddressKind.Contract)
                    is Guard.At -> read.address
                }
            }
            val state = SafeState(
                singleton.version,
                singleton.l2,
                owners,
                threshold.toInt(),
                nonce,
                modules,
                guard,
                moduleGuard,
            )
            return proven(AddressKind.Safe(state))
        }

        /** The guard kept at [slot]: an address, none, or a word that is no address. */
        private suspend fun guardAt(slot: ByteArray): Guard {
            val name = "guard slot 0x${slot.toHex().take(8)}…"
            val word = proof(deadline, { "Safe ${safe.hex}: $name" }) { chain.storageAt(safe, slot) }
                ?: return Guard.Unproven.also { unproven(name) }
            if (word.all { it == '0' }) return Guard.At(null)
            return addressIn(word)?.let { Guard.At(it) } ?: Guard.Broken
        }

        /**
         * [kind], labelled with the one block every slot is shown with, when every slot shares one
         * anchor and that state is not before the code's; null, and [straddled], otherwise.
         */
        private fun proven(kind: AddressKind): EngineReading<AddressKind>? {
            val anchor = anchors.singleOrNull()
            val block = blocks.singleOrNull()
            if (anchor == null || block == null || !notBeforeCode(anchor)) {
                straddled = true
                againstCode = anchor != null && block != null
                failure = if (againstCode) {
                    "straddled: the slots of one state ($anchor, block $block), the code's anchor ${code.anchor} " +
                        "not before it"
                } else {
                    "straddled: the slots at ${anchors.size} anchors $anchors, blocks ${blocks.sorted()}"
                }
                return null
            }
            return EngineReading.Proven(kind, block)
        }

        /**
         * Whether the state at [anchor] is not before the code's, as their verified anchors tell
         * ([ProofAnchor.notBefore]). No block label decides it: a code proof with no anchor, or a
         * walk against a match, which nothing verified orders, counts as a straddle, read again.
         */
        private fun notBeforeCode(anchor: ProofAnchor): Boolean = code.anchor?.let { anchor.notBefore(it) } == true

        /**
         * The linked list of the mapping at [slot] (owners, modules), walked from the sentinel
         * `0x1` back to it: no more than [max] entries, each an address. Setup writes both
         * sentinels, so an empty one is no list a Safe keeps.
         */
        private suspend fun walk(slot: Long, max: Int): Walk {
            val found = mutableListOf<Address>()
            var next = word(slot, SENTINEL) ?: return Walk.Unproven
            while (true) {
                if (next.all { it == '0' }) return Walk.Broken
                val entry = addressIn(next) ?: return Walk.Broken
                if (entry == SENTINEL) return Walk.Entries(found)
                if (entry in found || found.size == max) return Walk.Broken
                found += entry
                next = word(slot, entry) ?: return Walk.Unproven
            }
        }
    }

    /** What reading a guard gave: one or none ([At]), or that it could not be read, or that it is no address. */
    private sealed interface Guard {
        data class At(val address: Address?) : Guard
        data object Unproven : Guard
        data object Broken : Guard
    }

    /** What walking a list gave: its entries, or that a proof failed, or that the list is no list. */
    private sealed interface Walk {
        data class Entries(val addresses: List<Address>) : Walk
        data object Unproven : Walk
        data object Broken : Walk
    }

    override fun observeKind(network: Network, address: Address): Flow<KindReading> {
        if (network != served) return flowOf(KindReading.Unavailable(ReadUnavailable.NO_ENGINE))
        return reads.observe(address).map { reading ->
            when (reading) {
                EngineReading.Syncing, is EngineReading.LastKnown -> KindReading.Syncing
                is EngineReading.Proven -> KindReading.Verified(reading.value, reading.blockNumber)
                is EngineReading.Unavailable -> KindReading.Unavailable(reading.reason)
            }
        }
    }

    internal companion object {
        /** EIP-7702's delegation designator, `0xef0100`, which 20 bytes of address follow. */
        private const val DELEGATION_PREFIX = "ef0100"

        // The Safe storage layout of 1.3.0 to 1.5.0 (Singleton, ModuleManager, OwnerManager, Safe).
        private const val SINGLETON_SLOT = 0L
        private const val MODULES_SLOT = 1L
        private const val OWNERS_SLOT = 2L
        private const val OWNER_COUNT_SLOT = 3L
        private const val THRESHOLD_SLOT = 4L
        private const val NONCE_SLOT = 5L

        /** Where a Safe keeps its guard (1.3.0 on), and its module guard (1.5.0 on). */
        internal val GUARD_SLOT = Keccak256().digest("guard_manager.guard.address".encodeToByteArray())
        internal val MODULE_GUARD_SLOT = Keccak256().digest("module_manager.module_guard.address".encodeToByteArray())

        /** Heads a Safe's proof stands: about a minute of Sepolia blocks. */
        private const val SAFE_KEPT_FOR_HEADS = 5L

        /**
         * How long a Safe is read again while its reads fail: a couple of heads' worth, past which
         * the reading is unproven until the next poll; it runs from the first code proof. Kept well
         * inside a one-shot check's wait ([eth.serotine.domain.usecase.SAFE_CHECK_BUDGET], 45 s):
         * that wait must outlast this budget and one poll interval, or it would cancel the very read
         * it waits on and never see the next one (`SafeBudgetsTest` holds them to it).
         */
        internal val SAFE_READ_BUDGET = 20.seconds

        /** Times one proof of a read is asked for while it comes back null, the first included. */
        internal const val PROOF_TRIES = 3

        /** The pause before a Safe is read again after a proof failed: no tight loop on a peer that is gone. */
        internal val RETRY_PAUSE = 1.seconds

        /** The lists' sentinel, `0x…01`. */
        private val SENTINEL = Address.of("0x" + "0".repeat(39) + "1")

        /** Far more owners and modules than a Safe has: a list longer than this is no list read here. */
        private const val MAX_OWNERS = 100
        private const val MAX_MODULES = 50

        /** The address a storage word holds: its low 20 bytes, the rest zero; null when it is no address. */
        private fun addressIn(word: String): Address? = word
            .takeIf { it.length == WORD_HEX_DIGITS && it.take(ADDRESS_PADDING).all { c -> c == '0' } }
            ?.let { Address.of("0x" + it.drop(ADDRESS_PADDING)) }

        /** A storage word as a number, when it is a small one. */
        private fun number(word: String): Long? = Abi.uint256(word)?.toLongOrNull()

        private const val WORD_HEX_DIGITS = 64
        private const val ADDRESS_PADDING = 24

        /** What [codeHex] (lower-case hex, no `0x`) makes an address. */
        fun kindOf(codeHex: String): AddressKind = when {
            codeHex.isEmpty() -> AddressKind.Eoa
            codeHex.length == DELEGATION_PREFIX.length + 40 && codeHex.startsWith(DELEGATION_PREFIX) ->
                AddressKind.DelegatedEoa(Address.of("0x" + codeHex.drop(DELEGATION_PREFIX.length)))
            else -> AddressKind.Contract
        }
    }
}

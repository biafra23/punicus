package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.KnownNftCollection
import eth.serotine.domain.model.KnownNftKind
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NftRef
import eth.serotine.domain.repository.EnsNftReading
import eth.serotine.domain.repository.EnsNfts
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kotlincrypto.hash.sha3.Keccak256

/**
 * [EnsNfts] over the engine's verified calls at the head of [network], the one network its engine
 * follows (issue #246; owner rulings, 2026-11-05 and 2026-11-06). Every name it gives is checked by
 * hashing against the token's id, so a contract or a log can only confirm a name, never make one up;
 * a label that is not valid UTF-8, or holds a character that renders as nothing or moves text
 * ([Abi.visible]), is not given at all, so it cannot pass for another name. Nothing is read from
 * ENS's metadata service.
 *
 * A pinned collection's code ([KnownNftCollection.codeHash]) is proven once ([VerifiedChain.proveCode])
 * and the answer remembered while this adapter lives, as a Uniswap pin's is: deployed code does not
 * change. Asked only while the light client can verify reads at its head ([whyUnreadable]), on
 * [dispatcher]. A device without an engine ([chain] null) answers [ReadUnavailable.NO_ENGINE].
 */
class VerifiedEnsNfts internal constructor(
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
    private val network: Network,
) : EnsNfts {

    private val checking = Mutex()

    /** Whether each pinned collection's code was proven to be its pin; read and written under [checking]. */
    private val pinned = mutableMapOf<Address, Boolean>()

    override suspend fun name(
        network: Network,
        collection: KnownNftCollection,
        nft: NftRef,
        receiptLogs: List<ReceiptLog>,
    ): EnsNftReading {
        if (network != this.network) return EnsNftReading.Unavailable(ReadUnavailable.NO_ENGINE)
        collection.codeHash?.let { pin ->
            when (isPinned(collection.address, pin)) {
                false -> return EnsNftReading.NotPinned
                null -> return EnsNftReading.Unavailable(ReadUnavailable.UNPROVEN)
                true -> Unit
            }
        }
        val id = nft.tokenId.toBytes32()
        return chain.askVerified(dispatcher, EnsNftReading::Unavailable) { verified ->
            when (collection.kind) {
                KnownNftKind.ENS_V2_REGISTRY -> v2(verified, collection.address, id)
                KnownNftKind.ENS_NAME_WRAPPER -> wrapped(verified, collection.address, id)
                KnownNftKind.ENS_REGISTRAR -> registered(verified, collection.address, id, receiptLogs)
            }
        }
    }

    /** Whether [contract]'s proven code hashes to [pin]; null when it could not be proven right now. */
    private suspend fun isPinned(contract: Address, pin: String): Boolean? = checking.withLock {
        pinned[contract]?.let { return@withLock it }
        chain.askVerified<VerifiedChain, Boolean?>(dispatcher, { null }) { verified ->
            verified.proveCode(contract)?.let { it.codeHex.isNotEmpty() && codeHash(it.codeHex) == pin }
        }?.also { pinned[contract] = it }
    }

    /**
     * ENSv2's registry: the label its label store holds for [id], kept when its hash, the low 32 bits
     * (the version) aside, is [id]'s; the expiry, `getExpiry(id)`.
     */
    private fun v2(verified: VerifiedChain, registry: Address, id: ByteArray): EnsNftReading {
        val unversioned = id.copyOf().also { it.fill(0, WORD - VERSION_BYTES, WORD) }
        val labelHash = "0x" + unversioned.toHex()
        val store = when (val outcome = verified.call(registry, LABEL_STORE)) {
            is CallOutcome.Returned -> Abi.address(outcome.hex)
            is CallOutcome.Reverted -> null
            CallOutcome.Refused -> return EnsNftReading.Unavailable(ReadUnavailable.REFUSED)
            null -> return EnsNftReading.Unavailable(ReadUnavailable.UNPROVEN)
        }
        val label = store?.let { address ->
            when (val outcome = verified.call(address, GET_LABEL + id)) {
                is CallOutcome.Returned -> abiBytes(outcome.hex)
                is CallOutcome.Reverted -> null
                CallOutcome.Refused -> return EnsNftReading.Unavailable(ReadUnavailable.REFUSED)
                null -> return EnsNftReading.Unavailable(ReadUnavailable.UNPROVEN)
            }
        }?.takeIf { bytes ->
            keccak(bytes).also { it.fill(0, WORD - VERSION_BYTES, WORD) }.contentEquals(unversioned)
        }?.let { shownLabel(it) }
        val expiry = when (val outcome = verified.call(registry, GET_EXPIRY + id)) {
            is CallOutcome.Returned -> outcome
            is CallOutcome.Reverted -> null
            CallOutcome.Refused -> return EnsNftReading.Unavailable(ReadUnavailable.REFUSED)
            null -> return EnsNftReading.Unavailable(ReadUnavailable.UNPROVEN)
        }
        return EnsNftReading.Named(
            name = label?.let { "$it.eth" },
            labelHash = labelHash,
            expiry = expiry?.let { Abi.uint256(it.hex) }?.toLongOrNull(),
            wrapped = false,
            blockNumber = expiry?.blockNumber,
        )
    }

    /**
     * The NameWrapper: `names(id)`, the DNS-encoded name, kept when its namehash is [id]; the expiry,
     * the third word of `getData(id)`.
     */
    private fun wrapped(verified: VerifiedChain, wrapper: Address, id: ByteArray): EnsNftReading {
        val labels = when (val outcome = verified.call(wrapper, NAMES + id)) {
            is CallOutcome.Returned -> abiBytes(outcome.hex)?.let { dnsLabels(it) }
            is CallOutcome.Reverted -> null
            CallOutcome.Refused -> return EnsNftReading.Unavailable(ReadUnavailable.REFUSED)
            null -> return EnsNftReading.Unavailable(ReadUnavailable.UNPROVEN)
        }?.takeIf { namehash(it).contentEquals(id) }
        val data = when (val outcome = verified.call(wrapper, GET_DATA + id)) {
            is CallOutcome.Returned -> outcome
            is CallOutcome.Reverted -> null
            CallOutcome.Refused -> return EnsNftReading.Unavailable(ReadUnavailable.REFUSED)
            null -> return EnsNftReading.Unavailable(ReadUnavailable.UNPROVEN)
        }
        val shown = labels?.map { shownLabel(it) ?: return@map null }
        return EnsNftReading.Named(
            name = shown?.takeIf { parts -> parts.all { it != null } }?.joinToString("."),
            labelHash = "0x" + (labels?.firstOrNull()?.let { keccak(it) } ?: id).toHex(),
            expiry = data?.hex?.takeIf { it.length == 3 * WORD * 2 }?.let { Abi.uint256(it.substring(2 * WORD * 2)) }
                ?.toLongOrNull(),
            wrapped = true,
            blockNumber = data?.blockNumber,
        )
    }

    /**
     * The .eth registrar: the label from a log among [logs] whose topic 1 is [id] (the label's hash, as
     * the controller's `NameRegistered` indexes it) and whose data starts with a string hashing to it;
     * the expiry, `nameExpires(id)`.
     */
    private fun registered(
        verified: VerifiedChain,
        registrar: Address,
        id: ByteArray,
        logs: List<ReceiptLog>,
    ): EnsNftReading {
        val topic = "0x" + id.toHex()
        val label = logs.asSequence()
            .filter { it.topics.size >= 2 && it.topics[1] == topic }
            .mapNotNull { log -> abiBytes(log.data.removePrefix("0x"), allowTail = true) }
            .firstOrNull { keccak(it).contentEquals(id) }
            ?.let { shownLabel(it) }
        val expiry = when (val outcome = verified.call(registrar, NAME_EXPIRES + id)) {
            is CallOutcome.Returned -> outcome
            is CallOutcome.Reverted -> null
            CallOutcome.Refused -> return EnsNftReading.Unavailable(ReadUnavailable.REFUSED)
            null -> return EnsNftReading.Unavailable(ReadUnavailable.UNPROVEN)
        }
        return EnsNftReading.Named(
            name = label?.let { "$it.eth" },
            labelHash = topic,
            expiry = expiry?.let { Abi.uint256(it.hex) }?.toLongOrNull(),
            wrapped = false,
            blockNumber = expiry?.blockNumber,
        )
    }

    internal companion object {
        private const val WORD = 32

        /** The low bytes of an ENSv2 token id that are its version, not its label's hash. */
        private const val VERSION_BYTES = 4

        /** The longest label read, in bytes: longer than any name a row shows. */
        private const val MAX_LABEL_BYTES = 255

        private val LABEL_STORE = "9dbba19d".fromHex()
        private val GET_LABEL = "b21bf7fa".fromHex()
        private val GET_EXPIRY = "13c72608".fromHex()
        private val NAMES = "20c38e2b".fromHex()
        private val GET_DATA = "0178fe3f".fromHex()
        private val NAME_EXPIRES = "d6e4fa86".fromHex()

        fun keccak(bytes: ByteArray): ByteArray = Keccak256().digest(bytes)

        /** ENS's namehash of [labels], the leftmost first, each as its raw bytes. */
        fun namehash(labels: List<ByteArray>): ByteArray =
            labels.foldRight(ByteArray(WORD)) { label, node -> keccak(node + keccak(label)) }

        /**
         * The labels of a DNS-encoded name (each a length byte and that many bytes, ending with a zero
         * byte), or null when [bytes] is not exactly that, or a label is empty or holds a dot.
         */
        fun dnsLabels(bytes: ByteArray): List<ByteArray>? {
            val labels = mutableListOf<ByteArray>()
            var at = 0
            while (true) {
                if (at >= bytes.size) return null
                val length = bytes[at].toInt() and 0xff
                if (length == 0) return labels.takeIf { at == bytes.size - 1 && it.isNotEmpty() }
                if (at + 1 + length > bytes.size) return null
                val label = bytes.copyOfRange(at + 1, at + 1 + length)
                if ('.'.code.toByte() in label) return null
                labels += label
                at += 1 + length
            }
        }

        /**
         * The first dynamic `bytes` or `string` a call returned or a log holds, as [hex] (no `0x`): its
         * offset in the first word, then its length and bytes. Exactly that, unless [allowTail] (a log's
         * data, which goes on with other words). Null when out of shape or longer than [MAX_LABEL_BYTES].
         */
        fun abiBytes(hex: String, allowTail: Boolean = false): ByteArray? {
            if (hex.length % 2 != 0 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
            val bytes = hex.fromHex()
            if (bytes.size < 2 * WORD) return null
            val offset = small(bytes, 0) ?: return null
            if (offset % WORD != 0 || offset + WORD > bytes.size) return null
            val length = small(bytes, offset)?.takeIf { it <= MAX_LABEL_BYTES } ?: return null
            val end = offset + WORD + length
            val padded = offset + WORD + (length + WORD - 1) / WORD * WORD
            if (end > bytes.size || (!allowTail && padded != bytes.size)) return null
            return bytes.copyOfRange(offset + WORD, end)
        }

        /** The word at [at] as a small non-negative Int, or null when it is larger than any offset or length. */
        private fun small(bytes: ByteArray, at: Int): Int? {
            if (at + WORD > bytes.size) return null
            if ((at until at + WORD - 4).any { bytes[it] != 0.toByte() }) return null
            var value = 0L
            for (i in at + WORD - 4 until at + WORD) value = (value shl 8) or (bytes[i].toLong() and 0xff)
            return value.takeIf { it <= Int.MAX_VALUE }?.toInt()
        }

        /** [bytes] as a label a screen may show: valid UTF-8, non-empty, with nothing invisible in it; else null. */
        fun shownLabel(bytes: ByteArray): String? {
            if (bytes.isEmpty()) return null
            val text = bytes.decodeToString(throwOnInvalidSequence = false)
            if ('�' in text || Abi.visible(text) != text || '.' in text) return null
            return text
        }
    }
}

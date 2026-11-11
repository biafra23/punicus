@file:OptIn(ExperimentalForeignApi::class)

package eth.serotine.data.privacypools

import eth.serotine.data.privacypools.native.pp_lean_imt_proof
import eth.serotine.data.privacypools.native.pp_lean_imt_root
import eth.serotine.data.privacypools.native.pp_poseidon
import eth.serotine.data.privacypools.native.pp_prove
import eth.serotine.data.privacypools.native.pp_prover_free
import eth.serotine.data.privacypools.native.pp_prover_new
import kotlinx.cinterop.COpaque
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSLock
import platform.posix.memcpy
import platform.posix.memset

/**
 * The native Privacy Pools library on iOS (`native/privacy-pools`, its README; docs/privacy-pools.md,
 * Part 2e), through cinterop (`privacyPools.def`): the same calls as the JNA binding of Android and
 * the desktop, over the static library linked into the app, so it is always there.
 *
 * Field elements are 32 big-endian bytes below `p`. Every buffer that crosses the boundary and may
 * hold a secret is native memory this binding clears after the call (`memset`), so no copy of a
 * secret is left in one (the public `.zkey` and graph are freed uncleared); the caller's own arrays
 * are the caller's to zero (docs/keys-and-signing.md, *Privacy Pools note secrets*). Nothing here
 * logs an input.
 */
internal object PrivacyPoolsNative {
    /** A field element's width. */
    const val FIELD_BYTES = PoolAbi.FIELD_BYTES

    /** The deepest tree the circuits take. */
    const val MAX_DEPTH = PoolAbi.MAX_DEPTH

    /** A proof's width: eight base-field elements in the Solidity verifier's order. */
    const val PROOF_BYTES = PoolAbi.PROOF_BYTES

    /** `TREE_ALLOW_DUPLICATES`, the association set's tree flag; 0 refuses a repeated leaf. */
    const val TREE_ALLOW_DUPLICATES = PoolAbi.TREE_ALLOW_DUPLICATES
}

/** The typed calls over the library, as on the JVM: inputs copied into native memory, cleared after. */
internal class PrivacyPools {

    /** Poseidon of 1 to 3 field elements. */
    fun poseidon(inputs: List<ByteArray>): NativeResult<ByteArray> = withFields(inputs) { packed ->
        Buffer(PrivacyPoolsNative.FIELD_BYTES, secret = true).use { out ->
            answer(pp_poseidon(packed.pointer, inputs.size.convert(), out.pointer)) { out.read() }
        }
    }

    /** The root and depth of the Lean IMT of [leaves]; [allowDuplicates] for the association set. */
    fun leanImtRoot(leaves: List<ByteArray>, allowDuplicates: Boolean): NativeResult<TreeRoot> {
        if (leaves.isEmpty()) return NativeResult.Refused(NativeError.EMPTY_TREE)
        return withFields(leaves) { packed ->
            Buffer(PrivacyPoolsNative.FIELD_BYTES, secret = true).use { root ->
                Buffer(4, secret = true).use { depth ->
                    val status = pp_lean_imt_root(
                        packed.pointer, leaves.size.convert(), flags(allowDuplicates), root.pointer,
                        depth.pointer.reinterpret<UIntVar>(),
                    )
                    answer(status) {
                        TreeRoot(root.read(), depth.pointer.reinterpret<UIntVar>().pointed.value.toInt())
                    }
                }
            }
        }
    }

    /** The inclusion proof of the leaf at [index] in the Lean IMT of [leaves]. */
    fun leanImtProof(leaves: List<ByteArray>, index: Int, allowDuplicates: Boolean): NativeResult<InclusionProof> {
        if (leaves.isEmpty()) return NativeResult.Refused(NativeError.EMPTY_TREE)
        if (index < 0) return NativeResult.Refused(NativeError.NO_SUCH_LEAF)
        val width = PrivacyPoolsNative.FIELD_BYTES
        return withFields(leaves) { packed ->
            Buffer(width, secret = true).use { root ->
                Buffer(PrivacyPoolsNative.MAX_DEPTH * width, secret = true).use { siblings ->
                    Buffer(8, secret = true).use { path ->
                        val status = pp_lean_imt_proof(
                            packed.pointer, leaves.size.convert(), flags(allowDuplicates), index.convert(),
                            root.pointer, siblings.pointer, path.pointer.reinterpret<ULongVar>(),
                        )
                        answer(status) {
                            InclusionProof(
                                root.read(),
                                List(PrivacyPoolsNative.MAX_DEPTH) { siblings.read(it * width, width) },
                                path.pointer.reinterpret<ULongVar>().pointed.value.toLong(),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Loads [circuit] from its pinned `.zkey` and witness graph; the library refuses either if its
     * sha256 differs, and an empty one is refused here. Both are public: their native copies are
     * freed, not cleared.
     */
    fun prover(circuit: PoolCircuit, zkey: ByteArray, graph: ByteArray): NativeResult<PoolProver> {
        if (zkey.isEmpty() || graph.isEmpty()) return NativeResult.Refused(NativeError.BAD_ARTIFACT)
        return Buffer(zkey.size, secret = false).use { z ->
            z.write(zkey)
            Buffer(graph.size, secret = false).use { g ->
                g.write(graph)
                Buffer(4, secret = true).use { status ->
                    val code = status.pointer.reinterpret<IntVar>()
                    code.pointed.value = -1
                    val handle = pp_prover_new(
                        circuit.number.convert(), z.pointer, zkey.size.convert(), g.pointer, graph.size.convert(),
                        code,
                    )
                    val answered = code.pointed.value
                    if (handle != null && answered == 0) {
                        NativeResult.Ok(PoolProver(handle.reinterpret<COpaque>(), circuit))
                    } else {
                        handle?.let { pp_prover_free(it) }
                        NativeResult.Refused(NativeError.of(answered))
                    }
                }
            }
        }
    }

    private fun flags(allowDuplicates: Boolean): UInt =
        if (allowDuplicates) PrivacyPoolsNative.TREE_ALLOW_DUPLICATES.toUInt() else 0u
}

/**
 * A loaded circuit. [prove] and [close] are serialized: a close waits for the proof under way, and
 * a proof after it is refused. One owner holds it and closes it; one never closed keeps its parsed
 * proving key for the life of the process.
 */
internal class PoolProver(private var handle: COpaquePointer?, val circuit: PoolCircuit) : AutoCloseable {
    private val lock = NSLock()

    /**
     * Proves [circuit] from its packed inputs ([PoolCircuit.inputs] field elements in the order of
     * `src/ffi.rs`), which may hold the note's secrets: they are copied into native memory that is
     * cleared after the call. The caller zeroes [inputs] itself.
     */
    fun prove(inputs: ByteArray): NativeResult<Proved> = locked {
        val prover = handle ?: return@locked NativeResult.Refused(NativeError.NULL_POINTER)
        val width = PrivacyPoolsNative.FIELD_BYTES
        if (inputs.size != circuit.inputs * width) return@locked NativeResult.Refused(NativeError.BAD_LENGTH)
        Buffer(inputs.size, secret = true).use { packed ->
            packed.write(inputs)
            Buffer(PrivacyPoolsNative.PROOF_BYTES, secret = true).use { proof ->
                Buffer(circuit.publicSignals * width, secret = true).use { public ->
                    val status = pp_prove(
                        prover.reinterpret(), packed.pointer, circuit.inputs.convert(), proof.pointer,
                        public.pointer, circuit.publicSignals.convert(),
                    )
                    answer(status) {
                        Proved(proof.read(), List(circuit.publicSignals) { public.read(it * width, width) })
                    }
                }
            }
        }
    }

    override fun close() {
        locked {
            handle?.let { pp_prover_free(it.reinterpret()) }
            handle = null
        }
    }

    private inline fun <R> locked(block: () -> R): R {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/**
 * Native memory for one call, from the native heap: written from and read into Kotlin arrays, and
 * released by [use], which clears it first when it may hold a [secret].
 */
private class Buffer(private val size: Int, private val secret: Boolean) {
    /** The memory; at least one byte, so an empty input still has an address. */
    val pointer: CPointer<UByteVar> = nativeHeap.allocArray(maxOf(size, 1))

    /** Copies [bytes] in at [offset]. */
    fun write(bytes: ByteArray, offset: Int = 0) {
        if (bytes.isEmpty()) return
        bytes.usePinned { memcpy(pointer + offset, it.addressOf(0), bytes.size.convert()) }
    }

    /** Copies [count] bytes out from [offset]. */
    fun read(offset: Int = 0, count: Int = size): ByteArray = (pointer + offset)!!.readBytes(count)

    /** Runs [block] with this buffer, then clears it if it may hold a secret and frees it, whatever happens. */
    inline fun <R> use(block: (Buffer) -> R): R =
        try {
            block(this)
        } finally {
            if (secret) memset(pointer, 0, maxOf(size, 1).convert())
            nativeHeap.free(pointer)
        }
}

/** Packs [fields] (each 32 bytes) into one cleared native buffer for [block]; refuses a field of another width. */
private inline fun <T> withFields(fields: List<ByteArray>, block: (Buffer) -> NativeResult<T>): NativeResult<T> {
    val width = PrivacyPoolsNative.FIELD_BYTES
    if (fields.isEmpty() || fields.any { it.size != width }) return NativeResult.Refused(NativeError.BAD_LENGTH)
    return Buffer(fields.size * width, secret = true).use { packed ->
        fields.forEachIndexed { i, f -> packed.write(f, i * width) }
        block(packed)
    }
}

/** The value [read] makes on success (status 0), else the library's refusal. */
private inline fun <T> answer(status: Int, read: () -> T): NativeResult<T> =
    if (status == 0) NativeResult.Ok(read()) else NativeResult.Refused(NativeError.of(status))

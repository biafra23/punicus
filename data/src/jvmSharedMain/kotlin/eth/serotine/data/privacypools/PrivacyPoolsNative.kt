package eth.serotine.data.privacypools

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import eth.serotine.data.keys.Sodium.SizeT
import java.io.File
import java.nio.file.Files

/**
 * The native Privacy Pools library (`native/privacy-pools`, its README; docs/architecture.md,
 * *Native code*) through JNA: circomlib's Poseidon, zk-kit's Lean IMT, and Groth16 proofs of the
 * `withdraw` and `commitment` circuits, every proof verified by the library before it is returned.
 *
 * Field elements are 32 big-endian bytes below `p`. Every buffer that crosses the boundary and may
 * hold a secret is a native [Memory] this class clears after the call, so no copy of a secret is
 * left in one (the public `.zkey` and graph are freed uncleared); the
 * caller's own arrays are the caller's to zero (docs/keys-and-signing.md, *Privacy Pools note
 * secrets*). Nothing here logs an input.
 *
 * On the desktop the library is the classpath resource `:data`'s `buildPrivacyPoolsHost` staged under
 * JNA's prefix for this platform, and only that file: it is copied out and loaded by its absolute
 * path, never looked up by name on the loader's path, where another library of that name could be
 * handed the note's secrets. Where it is not built in, [library] is null and every call answers
 * [NativeError.UNAVAILABLE]. On Android it is the APK's own, which the linker's namespace limits a
 * bare name to.
 */
internal object PrivacyPoolsNative {

    /** The C ABI, `native/privacy-pools/src/ffi.rs`. Each call returns 0 or an error's number. */
    @Suppress("FunctionName") // the library's own names
    interface Library : com.sun.jna.Library {
        fun pp_poseidon(inputs: Pointer, count: SizeT, out: Pointer): Int

        fun pp_lean_imt_root(leaves: Pointer?, count: SizeT, flags: Int, outRoot: Pointer, outDepth: Pointer): Int

        fun pp_lean_imt_proof(
            leaves: Pointer?,
            count: SizeT,
            flags: Int,
            index: SizeT,
            outRoot: Pointer,
            outSiblings: Pointer,
            outIndex: Pointer,
        ): Int

        fun pp_prover_new(
            circuit: Int,
            zkey: Pointer,
            zkeyLen: SizeT,
            graph: Pointer,
            graphLen: SizeT,
            outStatus: Pointer,
        ): Pointer?

        fun pp_prover_free(prover: Pointer?)

        fun pp_prove(
            prover: Pointer,
            inputs: Pointer,
            count: SizeT,
            outProof: Pointer,
            outPublic: Pointer,
            publicCount: SizeT,
        ): Int
    }

    /** The library, or null when it is not built in here; loaded once. */
    val library: Library? by lazy {
        try {
            libraryPath()?.let { Native.load(it, Library::class.java) }
        } catch (e: Throwable) {
            System.err.println("Punicus: the Privacy Pools library cannot be loaded (${e::class.simpleName})")
            null
        }
    }

    /**
     * What to load: the bare name on Android; on the desktop, the absolute path of a private copy of
     * the built-in resource (a temporary file only its owner can write), or null when there is none.
     *
     * Only its owner can write it: on Linux and macOS because `Files.createTempFile` makes it so; on
     * Windows, which sets no ACL on it, because the temporary directory is the user's own (`%TEMP%`
     * under the profile), as it is by default. A `TEMP` pointed at a directory others can write
     * would weaken that.
     */
    private fun libraryPath(): String? {
        if (Platform.isAndroid()) return NAME
        val resource = "${Platform.RESOURCE_PREFIX}/${System.mapLibraryName(NAME)}"
        val stream = PrivacyPoolsNative::class.java.classLoader?.getResourceAsStream(resource) ?: return null
        val suffix = "." + resource.substringAfterLast('.')
        // Windows cannot delete a library a process has loaded, so deleteOnExit leaves each run's copy
        // behind there. The copies live in a directory of their own, in the user's own temporary
        // directory, and those older than an hour are removed first: never one another process has
        // just written and not loaded yet, and Windows refuses to delete one a process holds.
        val dir = if (Platform.isWindows()) {
            File(System.getProperty("java.io.tmpdir"), TEMP_PREFIX)
                .takeIf { d -> (d.isDirectory || d.mkdirs()) && d.isDirectory }
                ?.also { d -> removeStaleCopies(d, suffix, System.currentTimeMillis()) }
        } else {
            null
        }
        val file: File = (dir?.let { Files.createTempFile(it.toPath(), TEMP_PREFIX, suffix) }
            ?: Files.createTempFile(TEMP_PREFIX, suffix)).toFile()
        file.deleteOnExit()
        stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file.absolutePath
    }

    /**
     * Deletes the files in [dir] ending in [suffix] last written more than an hour before [now]: the
     * copies earlier runs left. A younger one, or one of another suffix, stays; so does one the
     * platform refuses to delete (on Windows, one a running process has loaded).
     */
    internal fun removeStaleCopies(dir: File, suffix: String, now: Long) {
        val stale = now - STALE_AFTER_MS
        dir.listFiles { f -> f.isFile && f.name.endsWith(suffix) && f.lastModified() < stale }
            ?.forEach { it.delete() }
    }

    private const val NAME = "privacy_pools"

    private const val TEMP_PREFIX = "punicus-privacy-pools"

    private const val STALE_AFTER_MS = 60 * 60 * 1000L

    /** A field element's width. */
    const val FIELD_BYTES = 32

    /** The deepest tree the circuits take. */
    const val MAX_DEPTH = 32

    /** A proof's width: eight base-field elements in the Solidity verifier's order. */
    const val PROOF_BYTES = 8 * 32

    /** `TREE_ALLOW_DUPLICATES`, the association set's tree flag; 0 refuses a repeated leaf. */
    const val TREE_ALLOW_DUPLICATES = 1
}

/** Why a call of the native library was refused: its `Error` numbers (`src/lib.rs`), and [UNAVAILABLE]. */
internal enum class NativeError(val code: Int) {
    UNAVAILABLE(-1),
    BAD_LENGTH(1),
    NOT_IN_FIELD(2),
    BAD_ARITY(3),
    ZERO_LEAF(4),
    DUPLICATE_LEAF(5),
    TREE_TOO_DEEP(6),
    NO_SUCH_LEAF(7),
    UNKNOWN_CIRCUIT(8),
    ARTIFACT_MISMATCH(9),
    BAD_ARTIFACT(10),
    WRONG_CIRCUIT(11),
    WITNESS(12),
    PROOF(13),
    PROOF_REJECTED(14),
    NULL_POINTER(15),
    PANIC(16),
    EMPTY_TREE(17),
    BAD_FLAGS(18),
    ;

    companion object {
        /** The error of [code]; an unknown number reads as [PANIC], since nothing it returned is to be trusted. */
        fun of(code: Int): NativeError = entries.firstOrNull { it.code == code } ?: PANIC
    }
}

/** A call's answer: [Ok] with its value, or [Refused] with why. */
internal sealed interface NativeResult<out T> {
    /** The call succeeded. */
    data class Ok<T>(val value: T) : NativeResult<T>

    /** The call was refused. */
    data class Refused(val error: NativeError) : NativeResult<Nothing>
}

/** A tree's root and depth. */
internal class TreeRoot(val root: ByteArray, val depth: Int)

/** An inclusion proof as the circuits take it: the root, 32 zero-padded siblings, the compressed index. */
internal class InclusionProof(val root: ByteArray, val siblings: List<ByteArray>, val index: Long)

/** A verified proof: its 256 bytes in the Solidity verifier's order, and the public signals, outputs first. */
internal class Proved(val proof: ByteArray, val publicSignals: List<ByteArray>)

/** The two circuits, by the library's numbers. */
internal enum class PoolCircuit(val number: Int, val inputs: Int, val publicSignals: Int) {
    /** `CommitmentHasher`, a ragequit's proof: value, label, nullifier, secret. */
    COMMITMENT(0, 4, 4),

    /** `Withdraw(32)`, a withdrawal's proof (`src/ffi.rs`, `WITHDRAW_INPUTS`, for the order). */
    WITHDRAW(1, 12 + 32 + 1 + 32 + 1, 8),
}

/**
 * The typed calls over [PrivacyPoolsNative.library]. Each copies its inputs into native memory,
 * calls, copies the answer out, and clears every native buffer it made, whatever the outcome.
 */
internal class PrivacyPools(private val library: PrivacyPoolsNative.Library? = PrivacyPoolsNative.library) {

    /** Poseidon of 1 to 3 field elements. */
    fun poseidon(inputs: List<ByteArray>): NativeResult<ByteArray> {
        val lib = library ?: return NativeResult.Refused(NativeError.UNAVAILABLE)
        return withFields(inputs) { packed ->
            Memory(PrivacyPoolsNative.FIELD_BYTES.toLong()).cleared { out ->
                answer(lib.pp_poseidon(packed, SizeT(inputs.size.toLong()), out)) {
                    out.getByteArray(0, PrivacyPoolsNative.FIELD_BYTES)
                }
            }
        }
    }

    /** The root and depth of the Lean IMT of [leaves]; [allowDuplicates] for the association set. */
    fun leanImtRoot(leaves: List<ByteArray>, allowDuplicates: Boolean): NativeResult<TreeRoot> {
        val lib = library ?: return NativeResult.Refused(NativeError.UNAVAILABLE)
        if (leaves.isEmpty()) return NativeResult.Refused(NativeError.EMPTY_TREE)
        return withFields(leaves) { packed ->
            Memory(PrivacyPoolsNative.FIELD_BYTES.toLong()).cleared { root ->
                Memory(4).cleared { depth ->
                    val count = SizeT(leaves.size.toLong())
                    val status = lib.pp_lean_imt_root(packed, count, flags(allowDuplicates), root, depth)
                    answer(status) {
                        TreeRoot(root.getByteArray(0, PrivacyPoolsNative.FIELD_BYTES), depth.getInt(0))
                    }
                }
            }
        }
    }

    /** The inclusion proof of the leaf at [index] in the Lean IMT of [leaves]. */
    fun leanImtProof(leaves: List<ByteArray>, index: Int, allowDuplicates: Boolean): NativeResult<InclusionProof> {
        val lib = library ?: return NativeResult.Refused(NativeError.UNAVAILABLE)
        if (leaves.isEmpty()) return NativeResult.Refused(NativeError.EMPTY_TREE)
        val width = PrivacyPoolsNative.FIELD_BYTES
        return withFields(leaves) { packed ->
            Memory(width.toLong()).cleared { root ->
                Memory((PrivacyPoolsNative.MAX_DEPTH * width).toLong()).cleared { siblings ->
                    Memory(8).cleared { path ->
                        val status = lib.pp_lean_imt_proof(
                            packed, SizeT(leaves.size.toLong()), flags(allowDuplicates), SizeT(index.toLong()),
                            root, siblings, path,
                        )
                        answer(status) {
                            InclusionProof(
                                root.getByteArray(0, width),
                                List(PrivacyPoolsNative.MAX_DEPTH) {
                                    siblings.getByteArray((it * width).toLong(), width)
                                },
                                path.getLong(0),
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
        val lib = library ?: return NativeResult.Refused(NativeError.UNAVAILABLE)
        if (zkey.isEmpty() || graph.isEmpty()) return NativeResult.Refused(NativeError.BAD_ARTIFACT)
        return Memory(zkey.size.toLong()).freed { z ->
            z.write(0, zkey, 0, zkey.size)
            Memory(graph.size.toLong()).freed { g ->
                g.write(0, graph, 0, graph.size)
                Memory(4).cleared { status ->
                    status.setInt(0, -1)
                    val handle = lib.pp_prover_new(
                        circuit.number, z, SizeT(zkey.size.toLong()), g, SizeT(graph.size.toLong()), status,
                    )
                    val code = status.getInt(0)
                    if (handle != null && code == 0) {
                        NativeResult.Ok(PoolProver(lib, handle, circuit))
                    } else {
                        handle?.let { lib.pp_prover_free(it) }
                        NativeResult.Refused(NativeError.of(code))
                    }
                }
            }
        }
    }

    private fun flags(allowDuplicates: Boolean): Int =
        if (allowDuplicates) PrivacyPoolsNative.TREE_ALLOW_DUPLICATES else 0
}

/**
 * A loaded circuit. [prove] and [close] are serialized: a close waits for the proof under way, and
 * a proof after it is refused. One owner holds it and closes it; one never closed keeps its parsed
 * proving key for the life of the process.
 */
internal class PoolProver(
    private val library: PrivacyPoolsNative.Library,
    private var handle: Pointer?,
    val circuit: PoolCircuit,
) : AutoCloseable {

    /**
     * Proves [circuit] from its packed inputs ([PoolCircuit.inputs] field elements in the order of
     * `src/ffi.rs`), which may hold the note's secrets: they are copied into native memory that is
     * cleared after the call. The caller zeroes [inputs] itself.
     */
    @Synchronized
    fun prove(inputs: ByteArray): NativeResult<Proved> {
        val prover = handle ?: return NativeResult.Refused(NativeError.NULL_POINTER)
        val width = PrivacyPoolsNative.FIELD_BYTES
        if (inputs.size != circuit.inputs * width) return NativeResult.Refused(NativeError.BAD_LENGTH)
        return Memory(inputs.size.toLong()).cleared { packed ->
            packed.write(0, inputs, 0, inputs.size)
            Memory(PrivacyPoolsNative.PROOF_BYTES.toLong()).cleared { proof ->
                Memory((circuit.publicSignals * width).toLong()).cleared { public ->
                    val status = library.pp_prove(
                        prover, packed, SizeT(circuit.inputs.toLong()), proof, public,
                        SizeT(circuit.publicSignals.toLong()),
                    )
                    answer(status) {
                        Proved(
                            proof.getByteArray(0, PrivacyPoolsNative.PROOF_BYTES),
                            List(circuit.publicSignals) { public.getByteArray((it * width).toLong(), width) },
                        )
                    }
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        handle?.let { library.pp_prover_free(it) }
        handle = null
    }
}

/** Runs [block] with [this] native buffer of public data, and frees it afterwards, whatever happens. */
private inline fun <R> Memory.freed(block: (Memory) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }

/** Runs [block] with [this] native buffer, and clears and frees it afterwards, whatever happens. */
private inline fun <R> Memory.cleared(block: (Memory) -> R): R =
    try {
        block(this)
    } finally {
        clear()
        close()
    }

/** Packs [fields] (each 32 bytes) into one native buffer for [block]; refuses a field of another width. */
private inline fun <T> withFields(fields: List<ByteArray>, block: (Memory) -> NativeResult<T>): NativeResult<T> {
    val width = PrivacyPoolsNative.FIELD_BYTES
    if (fields.isEmpty() || fields.any { it.size != width }) return NativeResult.Refused(NativeError.BAD_LENGTH)
    return Memory((fields.size * width).toLong()).cleared { packed ->
        fields.forEachIndexed { i, field -> packed.write((i * width).toLong(), field, 0, width) }
        block(packed)
    }
}

private inline fun <T> answer(status: Int, value: () -> T): NativeResult<T> =
    if (status == 0) NativeResult.Ok(value()) else NativeResult.Refused(NativeError.of(status))

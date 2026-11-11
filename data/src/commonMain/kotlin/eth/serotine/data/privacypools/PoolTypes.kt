package eth.serotine.data.privacypools

// What the native Privacy Pools library answers, shared by its two bindings: JNA's on Android and the
// desktop (jvmSharedMain, PrivacyPoolsNative), cinterop's on iOS (iosMain). Plain values, no native
// memory: every buffer that held an input is cleared by the binding before one of these is made.

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

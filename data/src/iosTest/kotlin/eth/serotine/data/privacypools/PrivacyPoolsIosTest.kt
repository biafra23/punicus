package eth.serotine.data.privacypools

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The iOS binding against the library's own reference vectors (native/privacy-pools/tests/vectors),
 * as the desktop's PrivacyPoolsNativeTest holds the JNA binding to them: every call, its buffers and
 * its refusals, here over the static library linked into the test binary, on the simulator.
 *
 * The files are read where the build left them ([PRIVACY_POOLS_DIR], which :data generates; the
 * simulator reads the Mac's files). The proving tests need the pinned proving keys, which only
 * `-PprivacyPoolsProving` fetches (CI passes it); without it they return at once, saying so, as
 * kotlin.test has no assumptions on Kotlin/Native.
 */
class PrivacyPoolsIosTest {
    private val pools = PrivacyPools()

    private fun proves(): Boolean =
        PRIVACY_POOLS_PROVING.also { if (!it) println("not run: needs -PprivacyPoolsProving") }

    private fun bytes(relative: String): ByteArray =
        SystemFileSystem.source(Path(PRIVACY_POOLS_DIR, relative)).buffered().use { it.readByteArray() }

    private fun vector(name: String): JsonElement =
        Json.parseToJsonElement(bytes("tests/vectors/$name").decodeToString())

    /** A decimal field element as 32 big-endian bytes. */
    private fun toField(decimal: String): ByteArray {
        val digits = IntArray(32)
        for (c in decimal) {
            require(c in '0'..'9') { "not a decimal: $decimal" }
            var carry = c - '0'
            for (i in 31 downTo 0) {
                val v = digits[i] * 10 + carry
                digits[i] = v and 0xff
                carry = v shr 8
            }
            require(carry == 0) { "wider than 256 bits: $decimal" }
        }
        return ByteArray(32) { digits[it].toByte() }
    }

    private fun toField(n: Long): ByteArray = toField(n.toString())

    private fun hex(fields: List<ByteArray>): List<String> =
        fields.map { f -> f.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } }

    private fun JsonObject.element(key: String): ByteArray = toField(getValue(key).jsonPrimitive.content)

    private fun JsonObject.elements(key: String): List<ByteArray> =
        getValue(key).jsonArray.map { toField(it.jsonPrimitive.content) }

    private fun <T> ok(result: NativeResult<T>): T = assertIs<NativeResult.Ok<T>>(result).value

    private fun refused(result: NativeResult<*>): NativeError = assertIs<NativeResult.Refused>(result).error

    private fun p1(n: Long): ByteArray = ok(pools.poseidon(listOf(toField(n))))

    @Test
    fun hashes_as_the_reference_does() {
        val input = vector("commitment.input.json").jsonObject
        val expected = vector("expected-outputs.json").jsonObject.getValue("commitment").jsonObject
        assertContentEquals(expected.element("nullifierHash"), ok(pools.poseidon(listOf(input.element("nullifier")))))
        val precommitment = ok(pools.poseidon(listOf(input.element("nullifier"), input.element("secret"))))
        val commitment = ok(pools.poseidon(listOf(input.element("value"), input.element("label"), precommitment)))
        assertContentEquals(expected.element("commitment"), commitment)
    }

    @Test
    fun refuses_bad_hash_inputs() {
        assertEquals(NativeError.BAD_LENGTH, refused(pools.poseidon(emptyList())))
        assertEquals(NativeError.BAD_ARITY, refused(pools.poseidon(List(4) { toField(1) })))
        assertEquals(NativeError.BAD_LENGTH, refused(pools.poseidon(listOf(ByteArray(31)))))
        assertEquals(NativeError.NOT_IN_FIELD, refused(pools.poseidon(listOf(ByteArray(32) { -1 }))))
    }

    private fun stateLeaves(): List<ByteArray> {
        val commitment = vector("expected-outputs.json").jsonObject.getValue("commitment").jsonObject
        return (1L..6L).map { p1(1000 + it) } + commitment.element("commitment") + p1(2000)
    }

    @Test
    fun builds_the_reference_state_tree() {
        val withdraw = vector("withdraw.input.json").jsonObject
        val root = ok(pools.leanImtRoot(stateLeaves(), allowDuplicates = false))
        assertContentEquals(withdraw.element("stateRoot"), root.root)
        assertEquals(3, root.depth)
        val proof = ok(pools.leanImtProof(stateLeaves(), 6, allowDuplicates = false))
        assertContentEquals(withdraw.element("stateRoot"), proof.root)
        assertEquals(hex(withdraw.elements("stateSiblings")), hex(proof.siblings))
        assertEquals(withdraw.getValue("stateIndex").jsonPrimitive.content.toLong(), proof.index)
    }

    @Test
    fun repeated_leaves_follow_the_flag() {
        val leaves = listOf(p1(1), p1(1))
        assertEquals(NativeError.DUPLICATE_LEAF, refused(pools.leanImtRoot(leaves, allowDuplicates = false)))
        ok(pools.leanImtRoot(leaves, allowDuplicates = true))
        assertEquals(NativeError.EMPTY_TREE, refused(pools.leanImtRoot(emptyList(), allowDuplicates = true)))
        assertEquals(NativeError.NO_SUCH_LEAF, refused(pools.leanImtProof(leaves, 2, allowDuplicates = true)))
    }

    private fun prover(circuit: PoolCircuit, name: String): PoolProver =
        ok(pools.prover(circuit, bytes("artifacts/$name.zkey"), bytes("graphs/$name.O1.graph.bin")))

    private fun publicSignals(name: String): List<ByteArray> =
        (vector(name) as JsonArray).map { toField(it.jsonPrimitive.content) }

    private fun pack(fields: List<ByteArray>): ByteArray = fields.fold(ByteArray(0)) { acc, f -> acc + f }

    @Test
    fun proves_the_commitment_circuit() {
        if (!proves()) return
        val input = vector("commitment.input.json").jsonObject
        prover(PoolCircuit.COMMITMENT, "commitment").use { prover ->
            val packed = pack(listOf("value", "label", "nullifier", "secret").map { input.element(it) })
            val proved = ok(prover.prove(packed))
            assertEquals(PrivacyPoolsNative.PROOF_BYTES, proved.proof.size)
            assertEquals(hex(publicSignals("commitment.public.json")), hex(proved.publicSignals))
        }
    }

    private fun withdrawInputs(): List<ByteArray> {
        val w = vector("withdraw.input.json").jsonObject
        val scalars = listOf(
            "withdrawnValue", "stateRoot", "stateTreeDepth", "ASPRoot", "ASPTreeDepth", "context", "label",
            "existingValue", "existingNullifier", "existingSecret", "newNullifier", "newSecret",
        ).map { w.element(it) }
        return scalars + w.elements("stateSiblings") + w.element("stateIndex") +
            w.elements("ASPSiblings") + w.element("ASPIndex")
    }

    @Test
    fun proves_the_withdraw_circuit_and_refuses_a_wrong_root() {
        if (!proves()) return
        prover(PoolCircuit.WITHDRAW, "withdraw").use { prover ->
            val inputs = withdrawInputs()
            val proved = ok(prover.prove(pack(inputs)))
            assertEquals(hex(publicSignals("withdraw.public.json")), hex(proved.publicSignals))
            val wrongRoot = inputs.toMutableList().also { it[1] = toField(12345) }
            assertEquals(NativeError.PROOF_REJECTED, refused(prover.prove(pack(wrongRoot))))
            assertEquals(NativeError.BAD_LENGTH, refused(prover.prove(ByteArray(32))))
        }
    }

    @Test
    fun refuses_an_artifact_that_is_not_pinned() {
        if (!proves()) return
        val zkey = bytes("artifacts/commitment.zkey").also { it[100] = (it[100] + 1).toByte() }
        val graph = bytes("graphs/commitment.O1.graph.bin")
        assertEquals(NativeError.ARTIFACT_MISMATCH, refused(pools.prover(PoolCircuit.COMMITMENT, zkey, graph)))
    }

    @Test
    fun a_closed_prover_refuses() {
        if (!proves()) return
        val prover = prover(PoolCircuit.COMMITMENT, "commitment")
        prover.close()
        prover.close()
        assertEquals(NativeError.NULL_POINTER, refused(prover.prove(ByteArray(4 * 32))))
    }
}

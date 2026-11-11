package eth.serotine.data.privacypools

import java.io.File
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue

/**
 * The binding against the native library's own reference vectors (native/privacy-pools/tests/vectors,
 * from Part 0's spike: inputs built as Kohaku builds them, public signals as snarkjs computed them).
 * The library is the one `buildPrivacyPoolsHost` builds for this host; the proving keys are those
 * `fetchPrivacyPoolsArtifacts` fetched and pinned. The library's own tests cover its cryptography;
 * these cover the mapping: every call, its buffers and its refusals.
 *
 * On a host the library is not built for (`serotine.privacyPoolsBuilt`), and for the proving tests
 * without `-PprivacyPoolsProving` (they need the network for the keys), a test is skipped.
 */
class PrivacyPoolsNativeTest {
    private val dir = File(assertNotNull(System.getProperty("serotine.privacyPoolsDir"), "run through Gradle"))
    private val pools = PrivacyPools()
    private val built = System.getProperty("serotine.privacyPoolsBuilt") == "true"
    private val proving = System.getProperty("serotine.privacyPoolsProving") == "true"

    private fun runs() = assumeTrue("no Privacy Pools library on this host", built)

    private fun proves() {
        runs()
        assumeTrue("needs -PprivacyPoolsProving", proving)
    }

    private fun hex(fields: List<ByteArray>): List<String> =
        fields.map { f -> f.joinToString("") { "%02x".format(it) } }

    private fun vector(name: String) = Json.parseToJsonElement(File(dir, "tests/vectors/$name").readText())

    private fun toField(decimal: String): ByteArray {
        val bytes = BigInteger(decimal).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - bytes.size) + bytes
    }

    private fun toField(n: Long): ByteArray = toField(n.toString())

    private fun JsonObject.element(key: String): ByteArray = toField(getValue(key).jsonPrimitive.content)

    private fun JsonObject.elements(key: String): List<ByteArray> =
        getValue(key).jsonArray.map { toField(it.jsonPrimitive.content) }

    private fun <T> ok(result: NativeResult<T>): T = assertIs<NativeResult.Ok<T>>(result).value

    private fun refused(result: NativeResult<*>): NativeError = assertIs<NativeResult.Refused>(result).error

    private fun p1(n: Long): ByteArray = ok(pools.poseidon(listOf(toField(n))))

    @Test
    fun the_library_is_built_in() {
        runs()
        assertNotNull(PrivacyPoolsNative.library, "buildPrivacyPoolsHost should have put it on the classpath")
    }

    @Test
    fun hashes_as_the_reference_does() {
        runs()
        val input = vector("commitment.input.json").jsonObject
        val expected = vector("expected-outputs.json").jsonObject.getValue("commitment").jsonObject
        assertContentEquals(expected.element("nullifierHash"), ok(pools.poseidon(listOf(input.element("nullifier")))))
        val precommitment = ok(pools.poseidon(listOf(input.element("nullifier"), input.element("secret"))))
        val commitment = ok(pools.poseidon(listOf(input.element("value"), input.element("label"), precommitment)))
        assertContentEquals(expected.element("commitment"), commitment)
    }

    @Test
    fun refuses_bad_hash_inputs() {
        runs()
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
        runs()
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
        runs()
        val leaves = listOf(p1(1), p1(1))
        assertEquals(NativeError.DUPLICATE_LEAF, refused(pools.leanImtRoot(leaves, allowDuplicates = false)))
        ok(pools.leanImtRoot(leaves, allowDuplicates = true))
        assertEquals(NativeError.EMPTY_TREE, refused(pools.leanImtRoot(emptyList(), allowDuplicates = true)))
        assertEquals(NativeError.NO_SUCH_LEAF, refused(pools.leanImtProof(leaves, 2, allowDuplicates = true)))
    }

    private fun prover(circuit: PoolCircuit, name: String): PoolProver {
        val zkey = File(dir, "artifacts/$name.zkey").readBytes()
        val graph = File(dir, "graphs/$name.O1.graph.bin").readBytes()
        return ok(pools.prover(circuit, zkey, graph))
    }

    private fun publicSignals(name: String): List<ByteArray> =
        (vector(name) as JsonArray).map { toField(it.jsonPrimitive.content) }

    private fun pack(fields: List<ByteArray>): ByteArray = fields.fold(ByteArray(0)) { acc, f -> acc + f }

    @Test
    fun proves_the_commitment_circuit() {
        proves()
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
        proves()
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
        proves()
        val zkey = File(dir, "artifacts/commitment.zkey").readBytes().also { it[100] = (it[100] + 1).toByte() }
        val graph = File(dir, "graphs/commitment.O1.graph.bin").readBytes()
        assertEquals(NativeError.ARTIFACT_MISMATCH, refused(pools.prover(PoolCircuit.COMMITMENT, zkey, graph)))
    }

    @Test
    fun a_closed_prover_refuses() {
        proves()
        val prover = prover(PoolCircuit.COMMITMENT, "commitment")
        prover.close()
        prover.close()
        assertEquals(NativeError.NULL_POINTER, refused(prover.prove(ByteArray(4 * 32))))
    }
}

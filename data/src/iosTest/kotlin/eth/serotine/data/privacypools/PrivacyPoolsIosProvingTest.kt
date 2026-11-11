package eth.serotine.data.privacypools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iOS binding's proofs against the reference vectors, on the simulator: both circuits proved
 * with snarkjs's public signals, a wrong root and an unpinned key refused, a closed prover refused.
 *
 * They need the circuits' pinned proving keys, which only `-PprivacyPoolsProving` fetches (CI passes
 * it). Without it, :data's iosSimulatorArm64Test leaves this class out by its filter, so a passed
 * test in the log is a proof that ran, as the desktop reports the same tests skipped.
 */
class PrivacyPoolsIosProvingTest {
    private val pools = PrivacyPools()

    private fun prover(circuit: PoolCircuit, name: String): PoolProver =
        ok(pools.prover(circuit, poolFile("artifacts/$name.zkey"), poolFile("graphs/$name.O1.graph.bin")))

    private fun publicSignals(name: String): List<ByteArray> =
        (poolVector(name) as JsonArray).map { toField(it.jsonPrimitive.content) }

    private fun pack(fields: List<ByteArray>): ByteArray = fields.fold(ByteArray(0)) { acc, f -> acc + f }

    @Test
    fun proves_the_commitment_circuit() {
        val input = poolVector("commitment.input.json").jsonObject
        prover(PoolCircuit.COMMITMENT, "commitment").use { prover ->
            val packed = pack(listOf("value", "label", "nullifier", "secret").map { input.element(it) })
            val proved = ok(prover.prove(packed))
            assertEquals(PrivacyPoolsNative.PROOF_BYTES, proved.proof.size)
            assertEquals(hex(publicSignals("commitment.public.json")), hex(proved.publicSignals))
        }
    }

    private fun withdrawInputs(): List<ByteArray> {
        val w = poolVector("withdraw.input.json").jsonObject
        val scalars = listOf(
            "withdrawnValue", "stateRoot", "stateTreeDepth", "ASPRoot", "ASPTreeDepth", "context", "label",
            "existingValue", "existingNullifier", "existingSecret", "newNullifier", "newSecret",
        ).map { w.element(it) }
        return scalars + w.elements("stateSiblings") + w.element("stateIndex") +
            w.elements("ASPSiblings") + w.element("ASPIndex")
    }

    @Test
    fun proves_the_withdraw_circuit_and_refuses_a_wrong_root() {
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
        val zkey = poolFile("artifacts/commitment.zkey").also { it[100] = (it[100] + 1).toByte() }
        val graph = poolFile("graphs/commitment.O1.graph.bin")
        assertEquals(NativeError.ARTIFACT_MISMATCH, refused(pools.prover(PoolCircuit.COMMITMENT, zkey, graph)))
    }

    @Test
    fun a_closed_prover_refuses() {
        val prover = prover(PoolCircuit.COMMITMENT, "commitment")
        prover.close()
        prover.close()
        assertEquals(NativeError.NULL_POINTER, refused(prover.prove(ByteArray(4 * 32))))
    }
}

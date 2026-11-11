package eth.serotine.data.privacypools

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The iOS binding against the library's own reference vectors (native/privacy-pools/tests/vectors),
 * as the desktop's PrivacyPoolsNativeTest holds the JNA binding to them: every call, its buffers and
 * its refusals, here over the static library linked into the test binary, on the simulator.
 *
 * The files are read where the build left them (PoolVectors.kt). The proving tests are
 * [PrivacyPoolsIosProvingTest].
 */
class PrivacyPoolsIosTest {
    private val pools = PrivacyPools()

    private fun p1(n: Long): ByteArray = ok(pools.poseidon(listOf(toField(n))))

    @Test
    fun hashes_as_the_reference_does() {
        val input = poolVector("commitment.input.json").jsonObject
        val expected = poolVector("expected-outputs.json").jsonObject.getValue("commitment").jsonObject
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
        val commitment = poolVector("expected-outputs.json").jsonObject.getValue("commitment").jsonObject
        return (1L..6L).map { p1(1000 + it) } + commitment.element("commitment") + p1(2000)
    }

    @Test
    fun builds_the_reference_state_tree() {
        val withdraw = poolVector("withdraw.input.json").jsonObject
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
}

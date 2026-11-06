package eth.serotine.data.engine

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.KeptSafe
import eth.serotine.domain.repository.SafeState
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString

/** The Safes last verified, kept for a lenient Safe draft (docs/lenient-mode.md, *Safe transactions in lenient mode*). */
@OptIn(ExperimentalCoroutinesApi::class)
class KeptSafesFileTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-kept-safes-${Random.nextLong().toULong()}")
    private val file = Path(directory, "kept-safes.json")
    private val safe = Address.of("0x" + "5a".repeat(20))
    private val alice = Address.of("0x" + "aa".repeat(20))
    private val bob = Address.of("0x" + "bb".repeat(20))
    private val state = SafeState("1.4.1", l2 = true, listOf(alice, bob), 2, 12, emptyList(), guard = null)

    private fun TestScope.kept(maxKept: Int = KeptSafesFile.MAX_KEPT) =
        KeptSafesFile(file.toString(), StandardTestDispatcher(testScheduler), maxKept)

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun aSafeProvenIsKeptAcrossARestartUnderItsNetworkOnly() = runTest {
        kept().proofs(Network.SEPOLIA).save(safe, EngineReading.LastKnown(AddressKind.Safe(state), 900, 1_800_000_000))

        val again = kept()
        assertEquals(KeptSafe(state, 900, 1_800_000_000), again.observe(AccountId(Network.SEPOLIA, safe)).first())
        assertNull(again.observe(AccountId(Network.ETHEREUM, safe)).first(), "the same address on another network is another Safe")
        assertEquals<EngineReading.LastKnown<AddressKind>?>(
            EngineReading.LastKnown(AddressKind.Safe(state), 900, 1_800_000_000),
            again.proofs(Network.SEPOLIA).load(safe),
        )
    }

    @Test
    fun anOlderProofNeverReplacesANewerOneAndTheSameStateIsWrittenAgainOnlyOnceOld() = runTest {
        val kept = kept()
        val proofs = kept.proofs(Network.SEPOLIA)
        proofs.save(safe, EngineReading.LastKnown(AddressKind.Safe(state), 900, 1_800_000_000))
        proofs.save(safe, EngineReading.LastKnown(AddressKind.Safe(state.copy(nonce = 11)), 899, 1_799_999_990))
        assertEquals(12, kept.observe(AccountId(Network.SEPOLIA, safe)).first()?.state?.nonce)

        // The same state a few blocks on: not written again.
        proofs.save(safe, EngineReading.LastKnown(AddressKind.Safe(state), 905, 1_800_000_060))
        assertEquals(900, kept().observe(AccountId(Network.SEPOLIA, safe)).first()?.blockNumber)
        // A changed one is, at once; so is the same one once its block is old.
        proofs.save(safe, EngineReading.LastKnown(AddressKind.Safe(state.copy(nonce = 13)), 906, 1_800_000_072))
        assertEquals(KeptSafe(state.copy(nonce = 13), 906, 1_800_000_072), kept().observe(AccountId(Network.SEPOLIA, safe)).first())
        val later = 906 + KeptSafesFile.REWRITE_AFTER_BLOCKS
        proofs.save(safe, EngineReading.LastKnown(AddressKind.Safe(state.copy(nonce = 13)), later, 1_800_000_400))
        assertEquals(later, kept().observe(AccountId(Network.SEPOLIA, safe)).first()?.blockNumber)
    }

    @Test
    fun anAddressProvenToBeNoSafeIsForgotten() = runTest {
        val proofs = kept().proofs(Network.SEPOLIA)
        proofs.save(safe, EngineReading.LastKnown(AddressKind.Safe(state), 900, 1_800_000_000))
        proofs.save(safe, EngineReading.LastKnown(AddressKind.Contract, 899, null))
        assertEquals(900, kept().observe(AccountId(Network.SEPOLIA, safe)).first()?.blockNumber, "an older proof forgets nothing")
        proofs.save(safe, EngineReading.LastKnown(AddressKind.Contract, 901, null))
        assertNull(kept().observe(AccountId(Network.SEPOLIA, safe)).first())
    }

    @Test
    fun theLeastRecentlyVerifiedSafeGoesFirst() = runTest {
        val kept = kept(maxKept = 2)
        val proofs = kept.proofs(Network.SEPOLIA)
        val others = listOf("01", "02", "03").map { Address.of("0x" + it.repeat(20)) }
        others.forEachIndexed { index, address ->
            proofs.save(address, EngineReading.LastKnown(AddressKind.Safe(state), 900L + index, null))
        }
        assertNull(kept.observe(AccountId(Network.SEPOLIA, others[0])).first())
        assertEquals(902, kept.observe(AccountId(Network.SEPOLIA, others[2])).first()?.blockNumber)
    }

    @Test
    fun anEntryNoSafeCanBeIsNotGivenBack() = runTest {
        SystemFileSystem.createDirectories(directory)
        val text = """{"version":1,"safes":[{"id":"11155111:${safe.hex}","version":"1.4.1","l2":false,""" +
            """"owners":["${alice.hex}"],"threshold":2,"nonce":3,"modules":[],"block":900}]}"""
        SystemFileSystem.sink(file).buffered().use { it.writeString(text) }
        assertNull(kept().observe(AccountId(Network.SEPOLIA, safe)).first(), "a threshold above its owners")
    }
}

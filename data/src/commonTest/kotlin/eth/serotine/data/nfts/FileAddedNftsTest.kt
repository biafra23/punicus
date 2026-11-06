package eth.serotine.data.nfts

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NftRef
import eth.serotine.domain.model.NftStandard
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AddedNftList
import eth.serotine.domain.repository.TokenChange
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString

/** [FileAddedNfts] against a file in the temporary directory (issue #246). */
@OptIn(ExperimentalCoroutinesApi::class)
class FileAddedNftsTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-nfts-${Random.nextLong().toULong()}")
    private val file = Path(directory, "nfts.json")
    private val alice = AccountId(Network.SEPOLIA, Address.of("0x" + "11".repeat(20)))
    private val bob = AccountId(Network.SEPOLIA, Address.of("0x" + "22".repeat(20)))
    private val punk = NftRef(Address.of("0x" + "a7".repeat(20)), UInt256.of(4021), NftStandard.ERC721)

    private fun TestScope.nfts() = FileAddedNfts(file.toString(), StandardTestDispatcher(testScheduler))

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun anAddedNftSurvivesARestartOnItsAccountAlone() = runTest {
        val first = nfts()
        assertEquals(TokenChange.Changed, first.add(alice, punk))
        assertEquals(TokenChange.AlreadyPresent, first.add(alice, punk))

        assertEquals(AddedNftList.Loaded(listOf(punk)), nfts().observe(alice).first())
        assertEquals(AddedNftList.Loaded(emptyList()), nfts().observe(bob).first())
        assertEquals(
            """{"version":1,"nfts":[{"chain":11155111,"account":"0x1111111111111111111111111111111111111111",""" +
                """"contract":"0xa7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7","id":"4021","standard":"ERC721"}]}""",
            SystemFileSystem.source(file).buffered().use { it.readString() },
        )
    }

    @Test
    fun aRemovedNftIsGone() = runTest {
        val nfts = nfts()
        assertEquals(TokenChange.NotFound, nfts.remove(alice, punk))
        nfts.add(alice, punk)
        assertEquals(TokenChange.Changed, nfts.remove(alice, punk))
        assertEquals(AddedNftList.Loaded(emptyList()), nfts().observe(alice).first())
    }
}

package eth.serotine.data.nfts

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.SeenEnsName
import eth.serotine.domain.repository.SeenEnsNameList
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
import kotlinx.io.writeString

/** [FileSeenEnsNames] against a file in the temporary directory (issue #274). */
@OptIn(ExperimentalCoroutinesApi::class)
class FileSeenEnsNamesTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-ens-names-${Random.nextLong().toULong()}")
    private val file = Path(directory, "ens-names.json")
    private val alice = AccountId(Network.SEPOLIA, Address.of("0x" + "11".repeat(20)))
    private val bob = AccountId(Network.SEPOLIA, Address.of("0x" + "22".repeat(20)))

    private fun TestScope.names() = FileSeenEnsNames(file.toString(), StandardTestDispatcher(testScheduler))

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun aNameSeenSurvivesARestartOnItsAccountAloneWithTheBlockFirstSeen() = runTest {
        val first = names()
        assertEquals(TokenChange.Changed, first.remember(alice, SeenEnsName("diskordia.eth", 100)))
        assertEquals(TokenChange.AlreadyPresent, first.remember(alice, SeenEnsName("diskordia.eth", 200)))
        assertEquals(TokenChange.Changed, first.remember(alice, SeenEnsName("mysafe.eth", null)))

        assertEquals(
            SeenEnsNameList.Loaded(listOf(SeenEnsName("diskordia.eth", 100), SeenEnsName("mysafe.eth", null))),
            names().observe(alice).first(),
        )
        assertEquals(SeenEnsNameList.Loaded(emptyList()), names().observe(bob).first())
        assertEquals(
            """{"version":1,"names":[{"chain":11155111,"account":"0x1111111111111111111111111111111111111111",""" +
                """"name":"diskordia.eth","block":100},{"chain":11155111,""" +
                """"account":"0x1111111111111111111111111111111111111111","name":"mysafe.eth"}]}""",
            SystemFileSystem.source(file).buffered().use { it.readString() },
        )
    }

    @Test
    fun aNewerFormatIsUnreadableAndNothingIsRemembered() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":2,"names":[]}""") }
        val names = names()

        assertEquals(SeenEnsNameList.Unreadable, names.observe(alice).first())
        assertEquals(TokenChange.NotSaved, names.remember(alice, SeenEnsName("diskordia.eth", 100)))
    }

    @Test
    fun aDamagedFileIsSetAsideAndTheEmptyListSaysSo() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":1,"names":[{"chain":1}]}""") }

        assertEquals(SeenEnsNameList.Loaded(emptyList(), setAside = true), names().observe(alice).first())
        assertEquals(1, SystemFileSystem.list(directory).count { it.name.startsWith("ens-names.json.damaged-") })
    }
}

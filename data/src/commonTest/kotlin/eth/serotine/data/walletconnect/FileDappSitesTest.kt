package eth.serotine.data.walletconnect

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppHost
import eth.serotine.domain.model.AppHostResult
import eth.serotine.domain.model.Network
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

/** [FileDappSites] against the [eth.serotine.domain.repository.DappSiteMemory] contract. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileDappSitesTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-sites-${Random.nextLong().toULong()}")
    private val file = Path(directory, "dapp-sites.json")
    private val uniswap = (AppHost.parse("uniswap.org") as AppHostResult.Host).host
    private val safe = (AppHost.parse("safe.global") as AppHostResult.Host).host
    private val perApp = AccountId(Network.ETHEREUM, Address.of("0x" + "11".repeat(20)))
    private val onGnosis = AccountId(Network.GNOSIS, Address.of("0x" + "11".repeat(20)))
    private val main = AccountId(Network.ETHEREUM, Address.of("0x" + "22".repeat(20)))

    private fun TestScope.sites() = FileDappSites(file.toString(), StandardTestDispatcher(testScheduler))

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    private fun write(text: String) {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString(text) }
    }

    private fun text(): String = SystemFileSystem.source(file).buffered().use { it.readString() }

    @Test
    fun theLastApprovalOfASiteIsRememberedAndSurvivesARestart() = runTest {
        val sites = sites()
        assertEquals(emptyMap(), sites.observe().first())
        assertTrue(sites.remember(uniswap, listOf(perApp, onGnosis)))
        assertTrue(sites.remember(safe, listOf(main)))
        assertTrue(sites.remember(uniswap, listOf(perApp, perApp)))

        assertEquals(mapOf(uniswap to listOf(perApp), safe to listOf(main)), sites().observe().first())
        assertEquals(
            """{"version":1,"sites":[{"site":"uniswap.org",""" +
                """"accounts":[{"chain":1,"address":"${perApp.address.hex}"}]},""" +
                """{"site":"safe.global","accounts":[{"chain":1,"address":"${main.address.hex}"}]}]}""",
            text(),
        )
    }

    @Test
    fun aFileThatCannotBeUsedIsNoMemory() = runTest {
        write("""{"version":2,"sites":[]}""")
        assertEquals(emptyMap(), sites().observe().first(), "a newer format")
        write("""{"version":1,"sites":[{"site":"uniswap.org","accounts":[{"chain":1,"address":"0x12"}]}]}""")
        assertEquals(emptyMap(), sites().observe().first(), "damaged, and set aside")
    }

    @Test
    fun anAccountOnANetworkThisVersionDoesNotKnowIsPassedOver() = runTest {
        write(
            """{"version":1,"sites":[{"site":"uniswap.org","accounts":[{"chain":424242,""" +
                """"address":"${main.address.hex}"},{"chain":1,"address":"${perApp.address.hex}"}]}]}""",
        )
        assertEquals(mapOf(uniswap to listOf(perApp)), sites().observe().first())
    }

    @Test
    fun aForgottenAccountLeavesEverySiteAndASiteLeftWithNoneGoes() = runTest {
        assertTrue(sites().forget(main), "remembered for none")
        assertFalse(SystemFileSystem.exists(file), "and nothing is written")
        val sites = sites()
        assertTrue(sites.remember(uniswap, listOf(perApp, main)))
        assertTrue(sites.remember(safe, listOf(main)))
        assertTrue(sites.forget(main))
        assertEquals(mapOf(uniswap to listOf(perApp)), sites().observe().first(), "across a restart")

        write("""{"version":2,"sites":[]}""")
        assertFalse(sites().forget(main), "a newer format: not saved")
        assertEquals("""{"version":2,"sites":[]}""", text(), "and left as it is")
    }
}

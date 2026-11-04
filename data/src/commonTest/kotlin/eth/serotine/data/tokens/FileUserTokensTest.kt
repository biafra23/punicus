package eth.serotine.data.tokens

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.TokenChange
import eth.serotine.domain.repository.TokenSettings
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

@OptIn(ExperimentalCoroutinesApi::class)
class FileUserTokensTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-tokens-${Random.nextLong().toULong()}")
    private val file = Path(directory, "tokens.json")
    private val eurc = Token(Address.of("0x08210f9170f89ab7658f0b5e3ff39b0e03c594d4"), "EURC", "EURC", 6, icon = null)
    private val usdc = Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238")

    private fun TestScope.tokens() = FileUserTokens(file.toString(), StandardTestDispatcher(testScheduler))

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun addedAndHiddenTokensSurviveARestart() = runTest {
        val first = tokens()
        assertEquals(TokenChange.Changed, first.add(Network.SEPOLIA, eurc))
        assertEquals(TokenChange.AlreadyPresent, first.add(Network.SEPOLIA, eurc))
        assertEquals(TokenChange.Changed, first.setHidden(Network.SEPOLIA, usdc, hidden = true))
        assertEquals(TokenChange.AlreadyPresent, first.setHidden(Network.SEPOLIA, usdc, hidden = true))

        assertEquals(TokenSettings.Loaded(listOf(eurc), setOf(usdc)), tokens().observe(Network.SEPOLIA).first())
        assertEquals(
            """{"version":2,"added":[{"address":"0x08210f9170f89ab7658f0b5e3ff39b0e03c594d4","symbol":"EURC",""" +
                """"name":"EURC","decimals":6,"chain":11155111}],""" +
                """"hiddenOn":[{"chain":11155111,"address":"0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"}]}""",
            SystemFileSystem.source(file).buffered().use { it.readString() },
        )
    }

    @Test
    fun aRemovedTokenLeavesNothingBehind() = runTest {
        val tokens = tokens()
        tokens.add(Network.SEPOLIA, eurc)
        tokens.setHidden(Network.SEPOLIA, eurc.address, hidden = true)

        assertEquals(TokenChange.Changed, tokens.remove(Network.SEPOLIA, eurc.address))
        assertEquals(TokenChange.NotFound, tokens.remove(Network.SEPOLIA, eurc.address))
        assertEquals(TokenSettings.Loaded(emptyList(), emptySet()), tokens().observe(Network.SEPOLIA).first())
    }

    @Test
    fun aNewerFormatIsUnreadableAndNothingChanges() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":3,"added":[],"hidden":[]}""") }
        val tokens = tokens()

        assertEquals(TokenSettings.Unreadable, tokens.observe(Network.SEPOLIA).first())
        assertEquals(TokenChange.NotSaved, tokens.add(Network.SEPOLIA, eurc))
    }

    @Test
    fun aDamagedFileIsSetAsideAndTheEmptySettingsSaySo() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":1,"added":[{"address":"x"}]}""") }
        val tokens = tokens()

        assertEquals(
            TokenSettings.Loaded(emptyList(), emptySet(), setAside = true),
            tokens.observe(Network.SEPOLIA).first(),
        )
        assertEquals(1, SystemFileSystem.list(directory).count { it.name.startsWith("tokens.json.damaged-") })
        // Still said after a change writes the new file.
        tokens.add(Network.SEPOLIA, eurc)
        assertEquals(
            TokenSettings.Loaded(listOf(eurc), emptySet(), setAside = true),
            tokens.observe(Network.SEPOLIA).first(),
        )
    }

    @Test
    fun aFileFromBeforeNetworksHoldsSepoliasTokens() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use {
            it.writeString(
                """{"version":1,"added":[{"address":"0x08210f9170f89ab7658f0b5e3ff39b0e03c594d4","symbol":"EURC",""" +
                    """"name":"EURC","decimals":6}],"hidden":["0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"]}""",
            )
        }

        assertEquals(TokenSettings.Loaded(listOf(eurc), setOf(usdc)), tokens().observe(Network.SEPOLIA).first())
    }
}

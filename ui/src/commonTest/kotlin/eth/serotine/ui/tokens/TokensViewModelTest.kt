package eth.serotine.ui.tokens

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenSettings
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.RemoveToken
import eth.serotine.domain.usecase.SetTokenHidden
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.ListCatalog
import eth.serotine.ui.MemoryUserTokens
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class TokensViewModelTest {

    private val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc")
    private val fake = Token(Address.of("0x" + "ee".repeat(20)), "USDC", "Not USDC", 6, null)
    private val userTokens = MemoryUserTokens()
    private val metadata = FakeMetadata()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): TokensViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = TokensViewModel(
            ObserveKnownTokens(ListCatalog(usdc), userTokens, metadata),
            SetTokenHidden(userTokens),
            RemoveToken(userTokens),
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun eachTokenSaysWhatItsCheckSaysAndAnImpostorIsMarked() = runTest {
        userTokens.add(Network.SEPOLIA, fake)
        val viewModel = viewModel()
        metadata.of(usdc.address).value = MetadataReading.Verified("USDC", "USDC", 6, 11_932_144)
        runCurrent()

        val (builtIn, added) = viewModel.uiState.value.sepolia
        assertEquals("Built in · checked against its contract at block 11,932,144", builtIn.status)
        assertEquals("Built in · checked against its contract", builtIn.simpleStatus, "no block")
        assertEquals("0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238", builtIn.address)
        assertEquals("Added: as its contract describes itself", added.status)
        assertEquals("Not the built-in USDC: another contract", added.warning)

        metadata.of(usdc.address).value = MetadataReading.Verified("USDC", "USDC", 18, 11_932_150)
        runCurrent()
        val contradicted = viewModel.uiState.value.sepolia.first()
        assertEquals("Not shown: its contract says USDC with 18 decimals", contradicted.status)
        assertEquals(true, contradicted.problem)
    }

    @Test
    fun aTokenIsHiddenShownAndAnAddedOneRemoved() = runTest {
        userTokens.add(Network.SEPOLIA, fake)
        val viewModel = viewModel()

        viewModel.setHidden(SEPOLIA, "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238", true)
        runCurrent()
        assertEquals(true, viewModel.uiState.value.sepolia.first().hidden)

        viewModel.remove(SEPOLIA, fake.address.hex)
        runCurrent()
        assertEquals(1, viewModel.uiState.value.sepolia.size)
    }

    @Test
    fun settingsThatCannotBeReadOrSavedAreSaid() = runTest {
        val viewModel = viewModel()
        userTokens.failSaves = true
        viewModel.setHidden(SEPOLIA, usdc.address.hex, true)
        runCurrent()
        assertEquals("The change could not be saved. Nothing has changed.", viewModel.uiState.value.notice)

        userTokens.settings.value = TokenSettings.Unreadable
        runCurrent()
        assertEquals(true, viewModel.uiState.value.notice?.startsWith("Your token settings cannot be read"))
        assertEquals(false, viewModel.uiState.value.canChange)
    }

    @Test
    fun settingsSetAsideAreSaidAndCanBeChanged() = runTest {
        userTokens.settings.value = TokenSettings.Loaded(emptyList(), emptySet(), setAside = true)
        val viewModel = viewModel()
        runCurrent()

        assertEquals(true, viewModel.uiState.value.notice?.startsWith("Your token settings were damaged"))
        assertEquals(true, viewModel.uiState.value.canChange)
    }

    @Test
    fun aRefusedCheckIsSaidToBeForGood() = runTest {
        val viewModel = viewModel()
        metadata.of(usdc.address).value = MetadataReading.Unavailable(ReadUnavailable.REFUSED)
        runCurrent()

        assertEquals(
            "Built in · its contract cannot be checked on this device",
            viewModel.uiState.value.sepolia[0].status,
        )
    }

    @Test
    fun eachNetworkIsASectionOfItsOwnAndAChangeIsMadeOnIt() = runTest {
        // An added token on Ethereum at the very address of Sepolia's built-in USDC: another token.
        userTokens.add(Network.ETHEREUM, fake.copy(address = usdc.address))
        val viewModel = viewModel()
        runCurrent()

        val sections = viewModel.uiState.value.sections
        assertEquals(listOf("Ethereum", "Sepolia"), sections.map { it.network.name }, "Ethereum's first")
        assertEquals(listOf(false), sections[0].items.map { it.builtIn })

        viewModel.setHidden(Network.ETHEREUM.chainId, usdc.address.hex, true)
        runCurrent()
        assertEquals(true, viewModel.uiState.value.sections[0].items.single().hidden, "hidden on Ethereum")
        assertEquals(false, viewModel.uiState.value.sepolia.single().hidden, "Sepolia's USDC untouched")
    }

    private companion object {
        val SEPOLIA = Network.SEPOLIA.chainId
    }
}

/** Sepolia's tokens, the section the tests above read. */
private val TokensUiState.sepolia: List<TokenItem>
    get() = sections.single { it.network.chainId == Network.SEPOLIA.chainId }.items

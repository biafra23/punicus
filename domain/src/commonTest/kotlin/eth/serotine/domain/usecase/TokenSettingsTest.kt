package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenChange
import eth.serotine.domain.repository.TokenSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** [ObserveKnownTokens], [AddToken], [SetTokenHidden] and [RemoveToken] over fake ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenSettingsTest {

    private val usdc = Token(Address.of("0x" + "1c".repeat(20)), "USDC", "USDC", 6, "usdc")
    private val link = Token(Address.of("0x" + "77".repeat(20)), "LINK", "ChainLink Token", 18, "link")
    private val eurc = Address.of("0x" + "08".repeat(20))
    private val userTokens = MemoryUserTokens()
    private val metadata = FakeMetadata()
    private val known = ObserveKnownTokens(ListCatalog(usdc, link), userTokens, metadata)

    @Test
    fun aBuiltInEntryAgreesOrIsNotShownAsItsContractSays() = runTest {
        metadata.of(usdc.address).value = MetadataReading.Verified("USDC", "USDC", 6, 9)
        metadata.of(link.address).value = MetadataReading.Verified("LINK", "ChainLink Token", 8, 9)

        val list = known(Network.SEPOLIA).first { list -> list.tokens.none { it.check == BuiltInCheck.Checking } }

        assertEquals(BuiltInCheck.Agrees(9), list.tokens[0].check)
        assertEquals(BuiltInCheck.Disagrees("LINK", 8), list.tokens[1].check)
        assertEquals(listOf(usdc), list.shown, "a contract that contradicts its entry hides it")
    }

    @Test
    fun anEntryThatCannotBeCheckedYetIsShownMeanwhile() = runTest {
        metadata.of(usdc.address).value = MetadataReading.Unavailable(ReadUnavailable.UNPROVEN)

        val list = known(Network.SEPOLIA).first { it.tokens[0].check is BuiltInCheck.Unavailable }

        assertTrue(usdc in list.shown)
    }

    @Test
    fun addedAndHiddenTokensJoinTheBuiltInOnes() = runTest {
        val fakeUsdc = Token(Address.of("0x" + "ee".repeat(20)), "usdc", "Not USDC", 6, null)
        userTokens.add(Network.SEPOLIA, fakeUsdc)
        userTokens.setHidden(Network.SEPOLIA, link.address, hidden = true)

        val list = known(Network.SEPOLIA).first()

        assertEquals(listOf(usdc, link, fakeUsdc), list.tokens.map { it.token })
        assertEquals(listOf(usdc, fakeUsdc), list.shown)
        assertTrue(list.tokens.last().sharesBuiltInSymbol, "an added token calling itself USDC is marked")
    }

    @Test
    fun unreadableSettingsAreSaid() = runTest {
        userTokens.settings.value = TokenSettings.Unreadable

        assertTrue(known(Network.SEPOLIA).first().settingsUnavailable)
    }

    @Test
    fun settingsSetAsideAreSaid() = runTest {
        userTokens.settings.value = TokenSettings.Loaded(emptyList(), emptySet(), setAside = true)

        assertTrue(known(Network.SEPOLIA).first().settingsSetAside)
    }

    @Test
    fun aSettingsChangeDoesNotCheckTheBuiltInEntriesAgain() = runTest {
        val seen = mutableListOf<KnownTokenList>()
        backgroundScope.launch { known(Network.SEPOLIA).collect { seen += it } }
        runCurrent()
        assertEquals(2, metadata.asked) // one check per built-in entry

        userTokens.setHidden(Network.SEPOLIA, link.address, hidden = true)
        runCurrent()

        assertEquals(2, metadata.asked)
        assertTrue(seen.last().tokens.single { it.token == link }.hidden)
    }

    @Test
    fun aTokenIsAddedAsItsContractDescribesItself() = runTest {
        metadata.of(eurc).value = MetadataReading.Verified("EURC", "EURC", 6, 9)

        val added = AddToken(userTokens, metadata, known)(Network.SEPOLIA, eurc)

        assertEquals(TokenAdd.Added(Token(eurc, "EURC", "EURC", 6, icon = null)), added)
        assertTrue(
            known(Network.SEPOLIA).first().tokens.any { it.token.address == eurc && it.origin == TokenOrigin.ADDED },
        )
    }

    @Test
    fun addingRefusesAKnownAddressANonTokenAndAnUnverifiedRead() = runTest {
        assertEquals(TokenAdd.AlreadyKnown(usdc), AddToken(userTokens, metadata, known)(Network.SEPOLIA, usdc.address))

        metadata.of(eurc).value = MetadataReading.NotAToken
        assertEquals(TokenAdd.NotAToken, AddToken(userTokens, metadata, known)(Network.SEPOLIA, eurc))

        metadata.of(eurc).value = MetadataReading.Syncing
        assertEquals(TokenAdd.NotVerified(null), AddToken(userTokens, metadata, known)(Network.SEPOLIA, eurc))

        metadata.of(eurc).value = MetadataReading.Unavailable(ReadUnavailable.REFUSED)
        assertEquals(
            TokenAdd.NotVerified(ReadUnavailable.REFUSED),
            AddToken(userTokens, metadata, known)(Network.SEPOLIA, eurc),
        )
    }

    @Test
    fun aDescriptionNoTokenCanHaveIsNotAToken() = runTest {
        metadata.of(eurc).value = MetadataReading.Verified("  ", "Blank", 6, 9)

        assertEquals(TokenAdd.NotAToken, AddToken(userTokens, metadata, known)(Network.SEPOLIA, eurc))
    }

    @Test
    fun aTokenIsHiddenShownAndRemoved() = runTest {
        metadata.of(eurc).value = MetadataReading.Verified("EURC", "EURC", 6, 9)
        AddToken(userTokens, metadata, known)(Network.SEPOLIA, eurc)

        assertEquals(TokenChange.Changed, SetTokenHidden(userTokens)(Network.SEPOLIA, eurc, hidden = true))
        assertEquals(TokenChange.AlreadyPresent, SetTokenHidden(userTokens)(Network.SEPOLIA, eurc, hidden = true))
        assertEquals(TokenChange.Changed, RemoveToken(userTokens)(Network.SEPOLIA, eurc))
        assertEquals(
            TokenChange.NotFound,
            RemoveToken(userTokens)(Network.SEPOLIA, usdc.address),
            "a built-in token is only hidden",
        )
        assertEquals(TokenSettings.Loaded(emptyList(), emptySet()), userTokens.settings.value)
    }
}

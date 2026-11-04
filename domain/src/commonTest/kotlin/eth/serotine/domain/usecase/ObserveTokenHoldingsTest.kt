package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.domain.repository.TokenSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveTokenHoldingsTest {

    private val holder = Address.of("0x" + "ab".repeat(20))
    private val usdc = Token(Address.of("0x" + "1c".repeat(20)), "USDC", "USDC", 6, "usdc")
    private val link = Token(Address.of("0x" + "77".repeat(20)), "LINK", "ChainLink Token", 18, "link")
    private val tokens = MutableStateFlow(listOf(usdc, link))
    private val catalog = object : TokenCatalog {
        override fun observeTokens(network: Network): Flow<List<Token>> = tokens
    }
    private val userTokens = MemoryUserTokens()
    private val metadata = FakeMetadata()
    private val known = ObserveKnownTokens(catalog, userTokens, metadata)
    private val asked = mutableListOf<Pair<Address, Address>>()
    private val balances = object : TokenBalances {
        override fun observeBalance(network: Network, holder: Address, token: Address): Flow<TokenBalanceReading> {
            asked += holder to token
            return flowOf(TokenBalanceReading.Verified(TokenUnits.of("5"), 9))
        }
    }

    @Test
    fun everyKnownTokenGetsARowStartingWithNoReadingYet() = runTest {
        val holdings = ObserveTokenHoldings(known, balances)(Network.SEPOLIA, holder).map { it.holdings }

        assertEquals(listOf(usdc, link), holdings.first().map { it.token })
        // combine may skip the in-between combinations: wait for where it settles.
        assertEquals(
            listOf(
                TokenHolding(usdc, TokenBalanceReading.Verified(TokenUnits.of("5"), 9)),
                TokenHolding(link, TokenBalanceReading.Verified(TokenUnits.of("5"), 9)),
            ),
            holdings.first { list -> list.all { it.reading != null } },
        )
        assertEquals(setOf(holder to usdc.address, holder to link.address), asked.toSet())
    }

    @Test
    fun noKnownTokensIsAnEmptyList() = runTest {
        tokens.value = emptyList()

        assertEquals(TokenHoldings(emptyList()), ObserveTokenHoldings(known, balances)(Network.SEPOLIA, holder).first())
    }

    @Test
    fun aHiddenTokenOrABuiltInOneItsContractContradictsGetsNoRow() = runTest {
        userTokens.setHidden(Network.SEPOLIA, usdc.address, hidden = true)
        metadata.of(link.address).value = MetadataReading.Verified("LINK", "ChainLink Token", 8, 9)

        val holdings = ObserveTokenHoldings(known, balances)(Network.SEPOLIA, holder).map { it.holdings }

        assertEquals(emptyList(), holdings.first { list -> list.none { it.token == link } })
    }

    @Test
    fun hidingATokenStopsOnlyItsOwnReading() = runTest {
        val seen = mutableListOf<List<TokenHolding>>()
        backgroundScope.launch { ObserveTokenHoldings(
            known,
            balances,
        )(Network.SEPOLIA, holder).collect { seen += it.holdings } }
        runCurrent()
        asked.clear()

        userTokens.setHidden(Network.SEPOLIA, usdc.address, hidden = true)
        runCurrent()

        assertEquals(listOf(TokenHolding(link, TokenBalanceReading.Verified(TokenUnits.of("5"), 9))), seen.last())
        assertEquals(emptyList(), asked, "the other token's reading goes on, not started again")
    }

    @Test
    fun anAddedTokenWithABuiltInSymbolIsMarkedAndUnreadableSettingsAreSaid() = runTest {
        val fake = Token(Address.of("0x" + "ee".repeat(20)), "usdc", "Not USDC", 6, null)
        userTokens.add(Network.SEPOLIA, fake)

        val marked = ObserveTokenHoldings(known, balances)(Network.SEPOLIA, holder).first { it.holdings.size == 3 }
        assertEquals(listOf(false, false, true), marked.holdings.map { it.sharesBuiltInSymbol })

        userTokens.settings.value = TokenSettings.Unreadable
        assertEquals(true, ObserveTokenHoldings(known, balances)(Network.SEPOLIA, holder).first().settingsUnavailable)
    }
}

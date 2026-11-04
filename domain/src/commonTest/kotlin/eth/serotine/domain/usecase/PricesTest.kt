package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.CurrencyChoice
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenBalanceReading
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [ObserveUsdPrice], the display currencies, [ObserveAccountValue] and [ObservePortfolio] over fake ports. */
class PricesTest {

    private val feeds = FakeFeeds(
        mapOf(ETH to PriceId.USD, LINK_ID to ETH, EUR to PriceId.USD, PriceId("USDC") to PriceId.USD),
    )
    private val setting = MemoryCurrencySetting()
    private val wallet = ListAccountRepository()
    private val currencies = ObserveDisplayCurrencies(feeds, setting, wallet)
    private val usd = ObserveUsdPrice(feeds)

    @Test
    fun aPoolsAverageOnThePathMarksThePriceAndAFeedPriceIsNoAverage() = runTest {
        feeds.answer(LINK_ID, 0.005, updatedAt = 1_100, ageSeconds = 0, pool = true)
        feeds.answer(ETH, 2_400.0, updatedAt = 900, ageSeconds = 300)

        assertEquals(
            Price.Priced(12.0, updatedAt = 900, ageSeconds = 300, stale = false, poolAverage = true),
            usd(Network.SEPOLIA, LINK_ID).first(),
        )
        assertEquals(false, (usd(Network.SEPOLIA, ETH).first() as Price.Priced).poolAverage)
    }

    @Test
    fun aPriceQuotedInEthGoesThroughEthUsdAndIsDatedByItsOldestFeed() = runTest {
        feeds.answer(LINK_ID, 0.005, updatedAt = 900, ageSeconds = 300)
        feeds.answer(ETH, 2_400.0, updatedAt = 1_100, ageSeconds = 100)

        assertEquals(
            Price.Priced(12.0, updatedAt = 900, ageSeconds = 300, stale = false),
            usd(Network.SEPOLIA, LINK_ID).first(),
        )
    }

    @Test
    fun oneFeedWithoutAPriceOrStaleMakesThePathSo() = runTest {
        feeds.answer(LINK_ID, 0.005)
        feeds.answer(ETH, 2_400.0, stale = true)
        assertTrue((usd(Network.SEPOLIA, LINK_ID).first() as Price.Priced).stale)

        feeds.of(ETH).value = FeedReading.NoPrice(NoPriceReason.DECIMALS_CHANGED)
        assertEquals(Price.NoPrice(NoPriceReason.DECIMALS_CHANGED), usd(Network.SEPOLIA, LINK_ID).first())

        feeds.of(ETH).value = FeedReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(Price.Unavailable(ReadUnavailable.UNPROVEN), usd(Network.SEPOLIA, LINK_ID).first())
        assertEquals(Price.NoPrice(NoPriceReason.NO_FEED), usd(Network.SEPOLIA, PriceId("UNI")).first())
    }

    @Test
    fun aLoopInTheTableIsNoPriceNotNoEnd() = runTest {
        val loop = ObserveUsdPrice(FakeFeeds(mapOf(ETH to LINK_ID, LINK_ID to ETH)))

        assertEquals(Price.NoPrice(NoPriceReason.NO_FEED), loop(Network.SEPOLIA, ETH).first())
    }

    @Test
    fun onlyCurrenciesWithAFeedAreOfferedAndAnotherCannotBeChosen() = runTest {
        val offered = currencies().first()
        assertEquals(listOf(DisplayCurrency.USD, DisplayCurrency.EUR, DisplayCurrency.ETH), offered.offered)
        assertEquals(DisplayCurrency.USD, offered.chosen)

        val set = SetDisplayCurrency(feeds, setting, wallet)
        assertFalse(set(DisplayCurrency.JPY), "JPY has no feed here")
        assertTrue(set(DisplayCurrency.EUR))
        assertEquals(DisplayCurrency.EUR, currencies().first().chosen)
    }

    @Test
    fun aStoredChoiceNoNetworkOffersAnyMoreIsUsd() = runTest {
        setting.choice.value = CurrencyChoice.Chosen(DisplayCurrency.GBP)

        assertEquals(DisplayCurrency.USD, currencies().first().chosen)
    }

    @Test
    fun aCurrencyIsOfferedWhenANetworkWithAccountsPricesItAndTheOthersAreNamed() = runTest {
        // Ethereum prices EUR and JPY, Sepolia EUR alone.
        val split = FakeFeeds(
            mapOf(EUR to PriceId.USD),
            byNetwork = mapOf(Network.ETHEREUM to mapOf(EUR to PriceId.USD, PriceId("JPY") to PriceId.USD)),
        )
        val both = ListAccountRepository(
            Account(Network.ETHEREUM, holder, AccountKind.EOA, "Main", AccountCustody.Watched),
            Account(Network.SEPOLIA, holder, AccountKind.EOA, "Test", AccountCustody.Watched),
        )
        val observe = ObserveDisplayCurrencies(split, setting, both)
        assertEquals(listOf(DisplayCurrency.USD, DisplayCurrency.EUR, DisplayCurrency.JPY), observe().first().offered)

        assertTrue(SetDisplayCurrency(split, setting, both)(DisplayCurrency.JPY), "Ethereum prices JPY")
        val chosen = observe().first()
        assertEquals(DisplayCurrency.JPY, chosen.chosen)
        assertEquals(setOf(Network.SEPOLIA), chosen.unpricedOn, "Sepolia has no JPY rate")
        // A figure of Sepolia's alone, a widget's, falls back to USD there.
        assertEquals(DisplayCurrency.USD, observe.on(Network.SEPOLIA).first())
        assertEquals(DisplayCurrency.JPY, observe.on(Network.ETHEREUM).first())

        // With the Ethereum account gone, no network with accounts prices JPY: USD again.
        both.accounts.value = both.accounts.value.filter { it.network == Network.SEPOLIA }
        assertEquals(DisplayCurrency.USD, observe().first().chosen)
        assertFalse(SetDisplayCurrency(split, setting, both)(DisplayCurrency.JPY))
    }

    // --- An account's value and the portfolio.

    private val holder = Address.of("0x" + "ab".repeat(20))
    private val usdc = Token(Address.of("0x" + "1c".repeat(20)), "USDC", "USDC", 6, "usdc", PriceId("USDC"))
    private val uni = Token(Address.of("0x" + "1f".repeat(20)), "UNI", "Uniswap", 18, "uni")
    private val native = FakeNativeBalances()
    private val tokenBalances = FakeTokenBalances()
    private val metadata = FakeMetadata()
    private val holdings = ObserveTokenHoldings(
        ObserveKnownTokens(ListCatalog(usdc, uni), MemoryUserTokens(), metadata),
        tokenBalances,
    )
    private val accountValue = ObserveAccountValue(
        ObserveNativeBalance(native),
        holdings,
        usd,
        currencies,
    )

    private fun pricesAndBalances() {
        feeds.answer(ETH, 2_000.0)
        feeds.answer(PriceId("USDC"), 1.0)
        feeds.answer(EUR, 1.25)
        native.of(holder).value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9) // 1.5 ETH
        tokenBalances.of(holder, usdc.address).value = TokenBalanceReading.Verified(TokenUnits.of("250000000"), 9)
        tokenBalances.of(holder, uni.address).value = TokenBalanceReading.Verified(TokenUnits.of("1"), 9)
    }

    @Test
    fun theTotalAddsThePricedHoldingsAndCountsTheOthers() = runTest {
        pricesAndBalances()

        val value = accountValue(holder.onSepolia).first { it.total.complete && it.tokens.size == 2 }

        assertEquals(Total(3_250.0, valued = 2, leftOut = 1), value.total) // UNI has no feed
        assertEquals(Valuation.NoPrice(NoPriceReason.NO_FEED), value.tokens[1].value)
    }

    @Test
    fun aHoldingValuedAtAPoolsAverageCountsInTheTotalAndIsCountedAsOne() = runTest {
        pricesAndBalances()
        feeds.answer(PriceId("USDC"), 1.0, pool = true)

        val value = accountValue(holder.onSepolia).first { it.total.complete && it.tokens.size == 2 }

        // Counted in (owner ruling, 2026-10-29, #205), and counted as a pool's average.
        assertEquals(Total(3_250.0, valued = 2, leftOut = 1, poolAveraged = 1), value.total)
        assertEquals(Valuation.Valued(250.0, ageSeconds = 60, stale = false, poolAverage = true), value.tokens[0].value)
        assertEquals(false, (value.nativeValue as Valuation.Valued).poolAverage)
    }

    @Test
    fun theTotalIsInTheChosenCurrencyAndAStalePriceIsLeftOut() = runTest {
        pricesAndBalances()
        setting.choose(DisplayCurrency.EUR)
        feeds.answer(PriceId("USDC"), 1.0, stale = true)

        val value = accountValue(holder.onSepolia).first { it.total.complete && it.tokens.size == 2 }

        assertEquals(DisplayCurrency.EUR, value.currency)
        assertEquals(Total(2_400.0, valued = 1, leftOut = 2), value.total) // 3000 USD / 1.25
    }

    @Test
    fun aBalanceOrPriceStillBeingReadMakesTheTotalIncomplete() = runTest {
        native.of(holder).value = BalanceReading.Verified(Wei.of("1"), 9)

        val first = accountValue(holder.onSepolia).first()
        assertFalse(first.tokensRead, "the token holdings are not read yet")
        assertFalse(first.total.complete)
    }

    @Test
    fun whatCannotBeVerifiedIsCountedAsSuchNotAsPendingOrUnpriced() = runTest {
        pricesAndBalances()
        native.of(holder).value = BalanceReading.Unavailable(ReadUnavailable.NO_ENGINE)
        feeds.of(PriceId("USDC")).value = FeedReading.Unavailable(ReadUnavailable.UNPROVEN)

        val value = accountValue(holder.onSepolia).first { it.tokensRead && it.tokens.all { t -> t.value != null } }

        assertEquals(Total(0.0, leftOut = 1, unverified = 2), value.total) // ETH and USDC; UNI has no feed
    }

    @Test
    fun aCurrencyWithoutARateRightNowIsSaidOnceNotBlamedOnEachAsset() = runTest {
        pricesAndBalances()
        setting.choose(DisplayCurrency.EUR)
        feeds.of(EUR).value = FeedReading.NoPrice(NoPriceReason.DECIMALS_CHANGED)

        val value = accountValue(holder.onSepolia).first { it.total.complete && it.tokens.size == 2 }

        assertEquals(Valuation.NoRate, value.nativeValue)
        assertEquals(Valuation.NoPrice(NoPriceReason.NO_FEED), value.tokens[1].value, "UNI has no feed either way")
        assertEquals(Total(0.0, leftOut = 1, noRate = true), value.total)
    }

    @Test
    fun etherInEtherIsItsOwnValueWhateverItsFeedSays() = runTest {
        pricesAndBalances()
        setting.choose(DisplayCurrency.ETH)
        feeds.answer(ETH, 2_000.0, ageSeconds = 7_200, stale = true)

        val value = accountValue(holder.onSepolia).first { it.nativeValue is Valuation.Valued }

        assertEquals(Valuation.Valued(1.5, ageSeconds = 0, stale = false), value.nativeValue)
    }

    @Test
    fun anAssetWhosePriceIsStillReadUnderAMissingRateHasNoRateNotNoPrice() = runTest {
        pricesAndBalances()
        setting.choose(DisplayCurrency.EUR)
        feeds.of(EUR).value = FeedReading.NoPrice(NoPriceReason.DECIMALS_CHANGED)
        feeds.of(PriceId("USDC")).value = FeedReading.Syncing

        val value = accountValue(holder.onSepolia).first { it.tokens.size == 2 && it.tokens[0].value != null }

        assertEquals(Valuation.NoRate, value.tokens[0].value)
        assertEquals(1, value.total.leftOut, "only UNI, which has no feed at all")
    }

    @Test
    fun verifiedBalancesWithAPriceStillBeingReadAreIncomplete() = runTest {
        pricesAndBalances()
        feeds.of(PriceId("USDC")).value = FeedReading.Syncing

        val value = accountValue(holder.onSepolia).first { it.tokens.size == 2 && it.tokens[0].value != null }

        assertEquals(Valuation.Checking, value.tokens[0].value)
        assertFalse(value.total.complete)
    }

    @Test
    fun aPortfolioStillValuedInThePreviousCurrencyIsIncomplete() = runTest {
        pricesAndBalances()
        val accounts = ListAccountRepository(
            Account(Network.SEPOLIA, holder, AccountKind.EOA, "Daily", AccountCustody.Watched),
        )
        val portfolio = Portfolio(
            AccountList.Loaded(accounts.accounts.value),
            mapOf(
                holder.onSepolia to accountValue(holder.onSepolia).first { it.total.complete && it.tokens.size == 2 },
            ),
            DisplayCurrency.EUR,
        )

        assertEquals(Total.PENDING, portfolio.total, "a USD value is not added to a EUR total")
    }

    @Test
    fun thePortfolioSumsItsAccountsInOneCurrency() = runTest {
        pricesAndBalances()
        metadata.of(usdc.address).value = MetadataReading.Verified("USDC", "USDC", 6, 9)
        val accounts = ListAccountRepository(
            Account(Network.SEPOLIA, holder, AccountKind.EOA, "Daily", AccountCustody.Watched),
        )
        val portfolio = ObservePortfolio(ObserveAccounts(accounts), accountValue, currencies)

        val read = portfolio().first { it.total.complete && it.values[holder.onSepolia]?.tokens?.size == 2 }

        assertEquals(Total(3_250.0, valued = 2, leftOut = 1), read.total)
    }
}

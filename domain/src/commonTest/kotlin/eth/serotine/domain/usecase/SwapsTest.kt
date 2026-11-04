package eth.serotine.domain.usecase

import eth.serotine.domain.model.AbiWords
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.FeeTier
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PoolKey
import eth.serotine.domain.model.QuoterV2
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.UniswapV3Deployment
import eth.serotine.domain.model.UniswapV3Factory
import eth.serotine.domain.model.UniswapV3Pool
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeQuote
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.repository.VenueReading
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SwapsTest {
    private val account = Address.of("0x" + "ac".repeat(20))
    private val usdcToken = Token(Address.of("0x" + "1c".repeat(20)), "USDC", "USD Coin", 6, icon = null)
    private val linkToken = Token(Address.of("0x" + "77".repeat(20)), "LINK", "ChainLink Token", 18, icon = null)
    private val usdc = TransferAsset.Erc20Token(usdcToken)
    private val link = TransferAsset.Erc20Token(linkToken)
    private val weth = Address.of("0x" + "ff".repeat(20))
    private val deployment = UniswapV3Deployment(
        factory = Address.of("0x" + "fa".repeat(20)),
        quoterV2 = Address.of("0x" + "0e".repeat(20)),
        swapRouter02 = Address.of("0x" + "50".repeat(20)),
        weth9 = weth,
    )
    private val router = deployment.swapRouter02
    private val hundredUsdc = UInt256.of(100_000_000)
    private val headTime = 1_792_419_000L

    private val chain = PoolChain()
    private val inputs = SwapInputs()
    private val native = FakeNativeBalances()
    private val tokens = FakeTokenBalances()
    private val sent = MemorySentTransfers()
    private val light = FakeLightClient()
    private val cowOrders = MemoryCowOrders()
    private val cowChain = FakeCowChain()
    private val quote = QuoteSwap(
        FindUniswapRoute(chain, inputs),
        inputs,
        native,
        tokens,
        sent,
        cowOrders,
        cowChain,
        light,
        Network.SEPOLIA,
        clock = { headTime },
    )

    init {
        native.of(account).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 120)
        tokens.of(account, usdcToken.address).value = TokenBalanceReading.Verified(TokenUnits.of("500000000"), 120)
        tokens.of(account, linkToken.address).value = TokenBalanceReading.Verified(TokenUnits.of("0"), 120)
        light.status.value = LightClientStatus.Synced(headBlock = 120, headTimestamp = headTime, finalized = null)
    }

    private fun word(value: UInt256) = value.toBytes32().toHex()
    private fun word(address: Address) = "00".repeat(12) + address.hex.drop(2)

    /** One pool of [tokenIn] and [tokenOut] at 0.3 %, quoting [out] for [amountIn]. */
    private fun pool(tokenIn: Address, tokenOut: Address, amountIn: UInt256, out: UInt256) {
        val key = PoolKey.of(tokenIn, tokenOut, FeeTier.MEDIUM)
        val address = Address.of("0x" + "30".repeat(20))
        chain.answers[deployment.factory to UniswapV3Factory.getPool(key).toHex()] = word(address)
        chain.answers[address to UniswapV3Pool.liquidity().toHex()] = word(UInt256.of(1_000))
        val route = SwapRoute.direct(tokenIn, tokenOut, FeeTier.MEDIUM)
        chain.answers[deployment.quoterV2 to QuoterV2.quote(route, amountIn).toHex()] =
            word(out) + "00".repeat(64) + word(UInt256.of(100_000))
    }

    /** The router's multicall returning [out] for the swap (and the unwrap's empty result when [unwrap]). */
    private fun returns(out: UInt256, unwrap: Boolean = false): String {
        val results = if (unwrap) listOf(out.toBytes32(), ByteArray(0)) else listOf(out.toBytes32())
        return AbiWords.withBytesArray(ByteArray(0), emptyList(), results).toHex()
    }

    private val amountOut = UInt256.of("4310000000000000000")

    private fun allow(amount: UInt256) {
        inputs.runs[usdcToken.address to Erc20.allowanceData(account, router).toHex()] = word(amount)
    }

    private suspend fun quoteUsdcForLink() =
        quote(account, usdc, link, hundredUsdc, SlippageTolerance.DEFAULT, FeeLevel.NORMAL)

    @Test
    fun aTokenSwapIsQuotedWithItsMinimumItsRunAndADeadlineFromTheHead() = runTest {
        pool(usdcToken.address, linkToken.address, hundredUsdc, amountOut)
        allow(hundredUsdc)
        inputs.routerReturns = returns(UInt256.of("4300000000000000000"))

        val quoted = assertIs<SwapQuoting.Quoted>(quoteUsdcForLink()).quote
        assertEquals(amountOut, quoted.quotedOut)
        // 4.31 less 0.5 %, rounded down.
        assertEquals(UInt256.of("4288450000000000000"), quoted.minimumOut)
        assertEquals(UInt256.of("4300000000000000000"), quoted.returnedOut)
        assertEquals(headTime + SWAP_DEADLINE_SECONDS, quoted.deadline)
        assertEquals(router, quoted.transaction.to)
        assertEquals(UInt256.ZERO, quoted.transaction.value)
        assertEquals(UInt256.of(400_000_000), quoted.sellAfter)
        assertEquals(UInt256.ZERO, quoted.buyBefore)
        assertEquals(SwapRoute.direct(usdcToken.address, linkToken.address, FeeTier.MEDIUM), quoted.route)
        assertEquals(null, quoted.priceImpact, "the pool's slot0 did not answer")
    }

    /** A CoW order of the account's selling [amount] of USDC, valid until [validTo]. */
    private fun cowOrder(amount: UInt256, validTo: Long): CowOrderRecord {
        val order = CowOrder(usdcToken.address, linkToken.address, account, amount, UInt256.ONE, validTo)
        return CowOrderRecord(
            account = account,
            network = Network.SEPOLIA,
            settlement = COW_SEPOLIA.settlement,
            order = order,
            uid = order.uid(Network.SEPOLIA.chainId, COW_SEPOLIA.settlement, account),
            signature = Signature("0".repeat(63) + "1", "0".repeat(63) + "1", 0),
            sell = usdc,
            buy = link,
            signedAt = validTo - 300,
            rangeStart = 90,
        )
    }

    @Test
    fun whatALiveCowOrderMayStillTakeIsNotTheSwapsToSell() = runTest {
        pool(usdcToken.address, linkToken.address, hundredUsdc, amountOut)
        allow(hundredUsdc)
        inputs.routerReturns = returns(UInt256.of("4300000000000000000"))
        // 500 USDC held, 450 of them by a live order: the 100 swapped would be one more than there is.
        cowOrders.orders.value = listOf(cowOrder(UInt256.of(450_000_000), validTo = headTime + 200))
        assertEquals(SwapQuoting.InsufficientToken, quoteUsdcForLink())

        cowOrders.orders.value = listOf(cowOrder(UInt256.of(300_000_000), validTo = headTime + 200))
        val quoted = assertIs<SwapQuoting.Quoted>(quoteUsdcForLink()).quote
        assertEquals(UInt256.of(300_000_000), quoted.heldByOrders)
        assertEquals(UInt256.of(100_000_000), quoted.sellAfter)
    }

    @Test
    fun anUnreadableRecordOfOrdersSellsNoToken() = runTest {
        cowOrders.unreadable = true
        assertEquals(SwapQuoting.CowOrdersUnreadable, quoteUsdcForLink())
    }

    @Test
    fun eachTokenBalanceCarriesTheBlockItWasVerifiedAt() = runTest {
        tokens.of(account, linkToken.address).value = TokenBalanceReading.Verified(TokenUnits.of("0"), 118)
        pool(usdcToken.address, linkToken.address, hundredUsdc, amountOut)
        allow(hundredUsdc)
        inputs.routerReturns = returns(UInt256.of("4300000000000000000"))

        val quoted = assertIs<SwapQuoting.Quoted>(quoteUsdcForLink()).quote
        assertEquals(120, quoted.tokenBalanceBlock)
        assertEquals(118, quoted.buyBalanceBlock)
    }

    @Test
    fun theQuoteCarriesItsPriceImpactAgainstThePoolsSpot() = runTest {
        pool(usdcToken.address, linkToken.address, hundredUsdc, amountOut)
        allow(hundredUsdc)
        inputs.routerReturns = returns(UInt256.of("4300000000000000000"))
        // 220 000² LINK units per USDC unit: 100 USDC is 4.84 LINK at the spot, 4.82548 less 0.3 %.
        val sqrt = UInt256.of(220_000) * UInt256.of("79228162514264337593543950336")
        chain.answers[Address.of("0x" + "30".repeat(20)) to UniswapV3Pool.slot0().toHex()] =
            word(sqrt) + "00".repeat(32 * 6)

        val quoted = assertIs<SwapQuoting.Quoted>(quoteUsdcForLink()).quote
        assertEquals(UInt256.of("4825480000000000000"), quoted.spotOut)
        assertEquals(1068, quoted.priceImpact, "4.31 against 4.82548")
    }

    @Test
    fun aLastKnownBalanceIsNeverQuotedOn() = runTest {
        pool(usdcToken.address, linkToken.address, hundredUsdc, amountOut)
        tokens.of(account, usdcToken.address).value =
            TokenBalanceReading.LastKnown(TokenUnits.of("500000000"), 110, asOf = null)
        assertEquals(SwapQuoting.Syncing, quoteUsdcForLink())
    }

    @Test
    fun anAllowanceThatFallsShortAsksForTheApprovalFirst() = runTest {
        pool(usdcToken.address, linkToken.address, hundredUsdc, amountOut)
        allow(UInt256.of(5))
        assertEquals(SwapQuoting.NeedsApproval(usdcToken, router, UInt256.of(5)), quoteUsdcForLink())
        assertEquals(0, inputs.estimates, "no swap is estimated before the approval")
    }

    @Test
    fun anApprovalSentAndNotMinedHoldsTheSwapBack() = runTest {
        pool(usdcToken.address, linkToken.address, hundredUsdc, amountOut)
        val approval = SentTransfer(
            network = Network.SEPOLIA,
            account = account,
            nonce = 7,
            signed = SignedTransfer(byteArrayOf(2, 7)),
            action = SentAction.TokenApproval(usdcToken, router, hundredUsdc),
            maxCost = UInt256.of(1_000),
            sentAt = headTime - 30,
        )
        sent.transfers.value = listOf(approval)
        assertEquals(SwapQuoting.ApprovalPending(approval.hash), quoteUsdcForLink())

        inputs.mined = 8 // a block took it
        allow(hundredUsdc)
        inputs.routerReturns = returns(amountOut)
        assertIs<SwapQuoting.Quoted>(quoteUsdcForLink())
    }

    @Test
    fun sellingEtherSendsTheAmountAsTheValueAndReadsNoAllowance() = runTest {
        val tenth = UInt256.of(100_000_000_000_000_000L)
        pool(weth, linkToken.address, tenth, amountOut)
        inputs.routerReturns = returns(amountOut)

        val quoted = assertIs<SwapQuoting.Quoted>(
            quote(account, TransferAsset.Native, link, tenth, SlippageTolerance.DEFAULT, FeeLevel.NORMAL),
        ).quote
        assertEquals(tenth, quoted.transaction.value)
        assertEquals(null, quoted.sellBefore)
        // No call of any token's allowance(owner, spender) was run: ETH sold needs none.
        val allowanceSelector = Erc20.allowanceData(account, router).toHex().take(8)
        assertTrue(inputs.simulated.isNotEmpty(), "the swap itself was run")
        assertTrue(inputs.simulated.none { (_, data) -> data.startsWith(allowanceSelector) })
    }

    @Test
    fun buyingEtherReadsTheUnwrapsResultToo() = runTest {
        val out = UInt256.of(30_000_000_000_000_000L)
        pool(usdcToken.address, weth, hundredUsdc, out)
        allow(hundredUsdc)
        inputs.routerReturns = returns(out, unwrap = true)
        val quoted = assertIs<SwapQuoting.Quoted>(
            quote(account, usdc, TransferAsset.Native, hundredUsdc, SlippageTolerance.DEFAULT, FeeLevel.NORMAL),
        ).quote
        assertEquals(out, quoted.returnedOut)
    }

    @Test
    fun aRunThatReturnsLessThanTheMinimumIsNoSwap() = runTest {
        pool(usdcToken.address, linkToken.address, hundredUsdc, amountOut)
        allow(hundredUsdc)
        inputs.routerReturns = returns(UInt256.of("4000000000000000000"))
        assertIs<SwapQuoting.Reverts>(quoteUsdcForLink())
    }

    @Test
    fun aQuoteTooSmallToKeepAMinimumIsNotSigned() = runTest {
        pool(usdcToken.address, linkToken.address, hundredUsdc, UInt256.ONE)
        allow(hundredUsdc)
        assertEquals(SwapQuoting.TooSmall, quoteUsdcForLink())
    }

    @Test
    fun noVerifiedHeadIsNoDeadline() = runTest {
        pool(usdcToken.address, linkToken.address, hundredUsdc, amountOut)
        allow(hundredUsdc)
        light.status.value = LightClientStatus.WaitingForHead(headAgeSeconds = 90)
        assertEquals(SwapQuoting.Syncing, quoteUsdcForLink())

        // A verified head whose time is not known names no figure: the swap was never run.
        light.status.value = LightClientStatus.Synced(headBlock = 120, headTimestamp = null, finalized = null)
        assertEquals(SwapQuoting.Unavailable(ReadUnavailable.UNPROVEN, figure = null), quoteUsdcForLink())
    }

    @Test
    fun whatAnEarlierSwapStillSellsIsNotThisOnesToSell() = runTest {
        pool(usdcToken.address, linkToken.address, hundredUsdc, amountOut)
        allow(hundredUsdc)
        inputs.routerReturns = returns(amountOut)
        sent.transfers.value = listOf(
            SentTransfer(
                network = Network.SEPOLIA,
                account = account,
                nonce = 7,
                signed = SignedTransfer(byteArrayOf(2, 8)),
                action = SentAction.Swap(router, usdc, UInt256.of(450_000_000), link, UInt256.ONE),
                maxCost = UInt256.of(1_000),
                sentAt = headTime - 30,
            ),
        )
        assertEquals(SwapQuoting.InsufficientToken, quoteUsdcForLink())
    }

    @Test
    fun noRouteIsSaidAsTheSwaps() = runTest {
        allow(hundredUsdc)
        assertEquals(SwapQuoting.NoRoute, quoteUsdcForLink())
        chain.venue = VenueReading.CodeMismatch(router)
        assertEquals(SwapQuoting.NoVenue(router), quoteUsdcForLink())
        assertEquals(
            SwapQuoting.NothingToSwap,
            quote(account, usdc, link, UInt256.ZERO, SlippageTolerance.DEFAULT, FeeLevel.NORMAL),
        )
    }

    @Test
    fun aSwapIsRunAgainBeforeItIsSignedAndRecordedAsTheSwapItIs() = runTest {
        pool(usdcToken.address, linkToken.address, hundredUsdc, amountOut)
        allow(hundredUsdc)
        inputs.routerReturns = returns(amountOut)
        val quoted = assertIs<SwapQuoting.Quoted>(quoteUsdcForLink()).quote
        val recovery = FakeRecovery()
        val signer = FakeSigner(recovery)
        val broadcaster = FakeBroadcaster()
        val send = SendSwap(inputs, signer, broadcaster, sent, clock = { headTime })
        val unlock = Unlock.WithPassword(WalletPassword("not a real password"))

        inputs.routerReturns = returns(UInt256.of("4000000000000000000"))
        assertIs<SwapSending.NoLongerMet>(send(quoted, unlock))
        assertTrue(signer.asked.isEmpty(), "nothing is signed")

        inputs.routerReturns = returns(amountOut)
        val done = assertIs<SwapSending.Done>(send(quoted, unlock))
        assertIs<TransferSending.Sent>(done.sending)
        assertEquals(
            SentAction.Swap(router, usdc, hundredUsdc, link, quoted.minimumOut),
            sent.transfers.value.single().action,
        )
    }

    @Test
    fun anApprovalIsForTheExactAmountAndOneCoveredNeedsNone() = runTest {
        val approve = QuoteTokenApproval(inputs, native, sent, Network.SEPOLIA, clock = { headTime })
        allow(UInt256.ZERO)
        val quoted = assertIs<ApprovalQuoting.Quoted>(
            approve(account, usdcToken, router, hundredUsdc, FeeLevel.NORMAL),
        ).quote
        assertEquals(hundredUsdc, quoted.amount)
        assertEquals(false, quoted.resetsFirst)
        assertEquals(usdcToken.address, quoted.transaction.to)
        assertEquals(Erc20.approveData(router, hundredUsdc).toHex(), quoted.transaction.data.toHex())

        val send = SendTokenApproval(FakeSigner(FakeRecovery()), FakeBroadcaster(), sent, clock = { headTime })
        assertIs<TransferSending.Sent>(send(quoted, Unlock.WithPassword(WalletPassword("made up"))))
        assertEquals(SentAction.TokenApproval(usdcToken, router, hundredUsdc), sent.transfers.value.single().action)

        inputs.mined = 8 // a block took it
        allow(hundredUsdc)
        assertEquals(
            ApprovalQuoting.AlreadyAllowed(hundredUsdc),
            approve(account, usdcToken, router, hundredUsdc, FeeLevel.NORMAL),
        )
    }

    @Test
    fun aTokenThatRefusesOneAllowanceForAnotherIsSetToZeroFirst() = runTest {
        val approve = QuoteTokenApproval(inputs, native, sent, Network.SEPOLIA, clock = { headTime })
        allow(UInt256.of(5))
        inputs.reverting += Erc20.approveData(router, hundredUsdc).toHex()

        val quoted = assertIs<ApprovalQuoting.Quoted>(
            approve(account, usdcToken, router, hundredUsdc, FeeLevel.NORMAL),
        ).quote
        assertEquals(true, quoted.resetsFirst)
        assertEquals(UInt256.ZERO, quoted.amount)
        assertEquals(Erc20.approveData(router, UInt256.ZERO).toHex(), quoted.transaction.data.toHex())
    }

    private fun record(nonce: Long, action: SentAction) = SentTransfer(
        network = Network.SEPOLIA,
        account = account,
        nonce = nonce,
        signed = SignedTransfer(byteArrayOf(2, nonce.toByte())),
        action = action,
        maxCost = UInt256.of(1_000),
        sentAt = headTime - 30,
    )

    @Test
    fun anEarlierSwapOfTheTokenNotMinedHoldsTheNextOneBack() = runTest {
        pool(usdcToken.address, linkToken.address, hundredUsdc, amountOut)
        allow(UInt256.of(200_000_000))
        val earlier = record(7, SentAction.Swap(router, usdc, UInt256.of(100_000_000), link, UInt256.ONE))
        sent.transfers.value = listOf(earlier)
        assertEquals(SwapQuoting.EarlierSwapPending(earlier.hash), quoteUsdcForLink())
        assertEquals(0, chain.quotes, "no route is quoted for a swap that waits")
    }

    @Test
    fun whatRefusesTheSwapAnywayIsSaidBeforeAnApprovalIsAskedFor() = runTest {
        // Too small to keep a minimum: said, though the allowance falls short too.
        pool(usdcToken.address, linkToken.address, hundredUsdc, UInt256.ONE)
        allow(UInt256.ZERO)
        assertEquals(SwapQuoting.TooSmall, quoteUsdcForLink())

        // 500 USDC held, 450 of it on its way out in a transfer not mined: 100 cannot be sold.
        pool(usdcToken.address, linkToken.address, hundredUsdc, amountOut)
        chain.quotes = 0
        sent.transfers.value = listOf(
            record(7, SentAction.Transfer(Address.of("0x" + "b0".repeat(20)), usdc, UInt256.of(450_000_000))),
        )
        assertEquals(SwapQuoting.InsufficientToken, quoteUsdcForLink())
        assertEquals(0, chain.quotes, "no route is quoted either")
    }

    @Test
    fun aMinedCountThatCannotBeReadIsSaidAsSuchNotAsAPendingApproval() = runTest {
        pool(usdcToken.address, linkToken.address, hundredUsdc, amountOut)
        sent.transfers.value = listOf(record(7, SentAction.TokenApproval(usdcToken, router, hundredUsdc)))
        inputs.minedUnreadable = true
        assertEquals(
            SwapQuoting.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.NONCE),
            quoteUsdcForLink(),
        )
    }

    @Test
    fun noApprovalIsSetUnderAnEarlierSwapOrBesideAnEarlierApproval() = runTest {
        val approve = QuoteTokenApproval(inputs, native, sent, Network.SEPOLIA, clock = { headTime })
        allow(UInt256.ZERO)
        val swap = record(7, SentAction.Swap(router, usdc, UInt256.of(100_000_000), link, UInt256.ONE))
        sent.transfers.value = listOf(swap)
        assertEquals(
            ApprovalQuoting.EarlierSwapPending(swap.hash),
            approve(account, usdcToken, router, hundredUsdc, FeeLevel.NORMAL),
        )
        val approval = record(7, SentAction.TokenApproval(usdcToken, router, hundredUsdc))
        sent.transfers.value = listOf(approval)
        assertEquals(
            ApprovalQuoting.ApprovalPending(approval.hash),
            approve(account, usdcToken, router, hundredUsdc, FeeLevel.NORMAL),
        )
    }

    /** Verified calls of the factory, its pools and QuoterV2 a test answers; the factory lists nothing else. */
    private inner class PoolChain : UniswapV3Chain {
        var venue: VenueReading = VenueReading.Verified(deployment)
        val answers = mutableMapOf<Pair<Address, String>, String>()
        var quotes = 0

        override suspend fun venue(network: Network): VenueReading = venue

        override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation {
            if (to == deployment.quoterV2) quotes++
            answers[to to data.toHex()]?.let { return Simulation.Succeeded(it, 120) }
            // A pair the test did not list: the factory has no pool for it.
            if (to == deployment.factory) return Simulation.Succeeded("00".repeat(32), 120)
            return Simulation.Reverted(120)
        }
    }

    /**
     * Transaction inputs: nonce 7, 10 gwei and 1 gwei tips, an estimate of 150 000; a run of the
     * router returns [routerReturns], one in [reverting] reverts, others answer [runs] or one word of 1.
     */
    private inner class SwapInputs : TransactionInputs {
        var mined = 7L
        var minedUnreadable = false
        var estimates = 0
        var routerReturns = ""
        val runs = mutableMapOf<Pair<Address, String>, String>()

        /** Every call run, as its contract and its data in hex. */
        val simulated = mutableListOf<Pair<Address, String>>()
        val reverting = mutableSetOf<String>()

        override suspend fun nonce(network: Network, account: Address): NonceReading = NonceReading.Read(
            maxOf(7, mined),
        )
        override suspend fun minedNonce(network: Network, account: Address): NonceReading = if (minedUnreadable) {
            NonceReading.Unavailable(ReadUnavailable.UNPROVEN)
        } else {
            NonceReading.Read(mined)
        }
        override suspend fun fees(network: Network): FeeReading = FeeReading.Quoted(
            FeeQuote(
                nextBaseFee = UInt256.of(10_000_000_000L),
                tips = FeeLevel.entries.associateWith { UInt256.of(1_000_000_000L) },
                blockNumber = 119,
            ),
        )

        override suspend fun estimateGas(
            network: Network,
            from: Address,
            to: Address,
            value: UInt256,
            data: ByteArray,
        ): GasEstimate {
            estimates++
            return if (data.toHex() in reverting) GasEstimate.Reverted("") else GasEstimate.Estimated(150_000)
        }

        override suspend fun simulate(
            network: Network,
            from: Address,
            to: Address,
            value: UInt256,
            data: ByteArray,
        ): Simulation {
            simulated += to to data.toHex()
            if (data.toHex() in reverting) return Simulation.Reverted(120)
            if (to == router) return Simulation.Succeeded(routerReturns, 120)
            return Simulation.Succeeded(runs[to to data.toHex()] ?: ("00".repeat(31) + "01"), 120)
        }
    }
}

package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.UniswapV3Deployment
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.model.Weth9
import eth.serotine.domain.model.WrapDirection
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.Outbox
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.repository.VenueReading
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WrapsTest {
    private val account = Address.of("0x" + "ac".repeat(20))
    private val wethToken = Token(Address.of("0x" + "ff".repeat(20)), "WETH", "Wrapped Ether", 18, icon = null)
    private val usdcToken = Token(Address.of("0x" + "1c".repeat(20)), "USDC", "USD Coin", 6, icon = null)
    private val weth = TransferAsset.Erc20Token(wethToken)
    private val usdc = TransferAsset.Erc20Token(usdcToken)
    private val deployment = UniswapV3Deployment(
        factory = Address.of("0x" + "fa".repeat(20)),
        quoterV2 = Address.of("0x" + "0e".repeat(20)),
        swapRouter02 = Address.of("0x" + "50".repeat(20)),
        weth9 = wethToken.address,
    )
    private val headTime = 1_792_419_000L
    private val tenth = UInt256.of("100000000000000000")

    private val uniswap = Venue()
    private val inputs = FakeTransactionInputs()
    private val native = FakeNativeBalances()
    private val tokens = FakeTokenBalances()
    private val sent = MemorySentTransfers()
    private val light = FakeLightClient()
    private val cowOrders = MemoryCowOrders()
    private val quote = QuoteWrap(
        uniswap,
        inputs,
        native,
        tokens,
        sent,
        cowOrders,
        FakeCowChain(),
        light,
        Network.SEPOLIA,
        outbox = Outbox.None,
        clock = { headTime },
    )

    init {
        native.of(account).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 120)
        tokens.of(account, wethToken.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("250000000000000000"), 118)
        light.status.value = LightClientStatus.Synced(headBlock = 120, headTimestamp = headTime, finalized = null)
    }

    @Test
    fun ethForTheProvenWeth9IsAWrapThroughItsDeposit() = runTest {
        val quoted = assertIs<WrapQuoting.Quoted>(quote(account, TransferAsset.Native, weth, tenth, FeeLevel.NORMAL))
            .quote
        assertEquals(WrapDirection.WRAP, quoted.direction)
        assertEquals(wethToken.address, quoted.transaction.to)
        assertEquals(tenth, quoted.transaction.value, "a deposit carries the ETH it wraps")
        assertEquals(Weth9.wrap(wethToken.address, tenth).data.toHex(), quoted.transaction.data.toHex())
        assertEquals(UInt256.of("350000000000000000"), quoted.wethAfter, "one for one")
        assertEquals(118, quoted.tokenBalanceBlock)
        // The value leaves the native balance along with the fee.
        assertEquals(UInt256.of("1000000000000000000") - tenth - quoted.maxFee, quoted.nativeAfter)
        assertEquals(SentAction.Wrap(wethToken, tenth), quoted.action)
    }

    @Test
    fun wethForEthIsAnUnwrapThroughItsWithdrawAndPaysTheEther() = runTest {
        val quoted = assertIs<WrapQuoting.Quoted>(quote(account, weth, TransferAsset.Native, tenth, FeeLevel.NORMAL))
            .quote
        assertEquals(WrapDirection.UNWRAP, quoted.direction)
        assertEquals(UInt256.ZERO, quoted.transaction.value)
        // withdraw(uint256): 2e1a7d4d, then the amount as a word.
        assertEquals("2e1a7d4d" + tenth.toBytes32().toHex(), quoted.transaction.data.toHex())
        assertEquals(UInt256.of("150000000000000000"), quoted.wethAfter)
        assertEquals(UInt256.of("1000000000000000000") - quoted.maxFee + tenth, quoted.nativeAfter)
        assertEquals(SentAction.Unwrap(wethToken, tenth), quoted.action)
    }

    @Test
    fun anyOtherPairIsNoWrapAndAsksNothingButTheVenue() = runTest {
        assertEquals(WrapQuoting.NotAWrap, quote(account, TransferAsset.Native, usdc, tenth, FeeLevel.NORMAL))
        assertEquals(WrapQuoting.NotAWrap, quote(account, usdc, weth, tenth, FeeLevel.NORMAL))
        assertEquals(
            WrapQuoting.NotAWrap,
            quote(account, TransferAsset.Native, TransferAsset.Native, tenth, FeeLevel.NORMAL),
        )
        assertTrue(inputs.simulated.isEmpty(), "nothing was run")
    }

    @Test
    fun noWrapGoesThroughAWeth9WhoseCodeIsNotProven() = runTest {
        uniswap.reading = VenueReading.CodeMismatch(wethToken.address)
        assertEquals(WrapQuoting.NotAWrap, quote(account, TransferAsset.Native, weth, tenth, FeeLevel.NORMAL))
        uniswap.reading = VenueReading.NotPinned
        assertEquals(WrapQuoting.NotAWrap, quote(account, TransferAsset.Native, weth, tenth, FeeLevel.NORMAL))
        assertTrue(inputs.simulated.isEmpty(), "nothing was run")
    }

    @Test
    fun aWrapNeedsTheEtherAndAnUnwrapTheWeth() = runTest {
        val tooMuch = UInt256.of("2000000000000000000")
        assertEquals(
            WrapQuoting.InsufficientFunds,
            quote(account, TransferAsset.Native, weth, tooMuch, FeeLevel.NORMAL),
        )
        assertEquals(
            WrapQuoting.InsufficientToken,
            quote(account, weth, TransferAsset.Native, UInt256.of("300000000000000000"), FeeLevel.NORMAL),
        )
        assertEquals(
            WrapQuoting.NothingToWrap,
            quote(account, weth, TransferAsset.Native, UInt256.ZERO, FeeLevel.NORMAL),
        )
    }

    @Test
    fun whatEarlierSendsAndLiveCowOrdersMayStillTakeIsNotTheUnwrapsToTake() = runTest {
        // 0.25 WETH held; an earlier unwrap of 0.1 not mined yet: 0.15 left.
        sent.transfers.value = listOf(record(7, SentAction.Unwrap(wethToken, tenth)))
        inputs.mined = 7
        assertEquals(
            WrapQuoting.InsufficientToken,
            quote(account, weth, TransferAsset.Native, UInt256.of("200000000000000000"), FeeLevel.NORMAL),
        )
        // And a live CoW order selling 0.1 of it: 0.05 left.
        cowOrders.orders.value = listOf(cowOrder(tenth, validTo = headTime + 200))
        val quoted = assertIs<WrapQuoting.Quoted>(
            quote(account, weth, TransferAsset.Native, UInt256.of("50000000000000000"), FeeLevel.NORMAL),
        ).quote
        assertEquals(tenth, quoted.pendingWeth)
        assertEquals(tenth, quoted.heldByOrders)
        assertEquals(UInt256.ZERO, quoted.wethAfter)

        cowOrders.unreadable = true
        assertEquals(
            WrapQuoting.CowOrdersUnreadable,
            quote(account, weth, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
    }

    @Test
    fun aRunThatRevertsOrReturnsSomethingIsNoWrap() = runTest {
        inputs.simulation = Simulation.Reverted(120)
        assertIs<WrapQuoting.Reverts>(quote(account, TransferAsset.Native, weth, tenth, FeeLevel.NORMAL))
        // WETH9 returns nothing: a contract that returns a word is not the one pinned.
        inputs.simulation = Simulation.Succeeded("00".repeat(31) + "01", 120)
        assertIs<WrapQuoting.Reverts>(quote(account, TransferAsset.Native, weth, tenth, FeeLevel.NORMAL))
    }

    @Test
    fun aQuotedWrapIsSignedAndRecordedAsTheWrapItIs() = runTest {
        val quoted = assertIs<WrapQuoting.Quoted>(quote(account, TransferAsset.Native, weth, tenth, FeeLevel.NORMAL))
            .quote
        val signer = FakeSigner(FakeRecovery())
        val send = SendWrap(signer, FakeBroadcaster(), sent, clock = { headTime })
        assertIs<TransferSending.Sent>(send(quoted, Unlock.WithPassword(WalletPassword("not a real password"))))
        assertEquals(SentAction.Wrap(wethToken, tenth), sent.transfers.value.single().action)
        assertEquals(quoted.transaction.signingDigest(), signer.asked.single().second)
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

    /** A CoW order of the account's selling [amount] of WETH, valid until [validTo]. */
    private fun cowOrder(amount: UInt256, validTo: Long): CowOrderRecord {
        val order = CowOrder(wethToken.address, usdcToken.address, account, amount, UInt256.ONE, validTo)
        return CowOrderRecord(
            account = account,
            network = Network.SEPOLIA,
            settlement = COW_SEPOLIA.settlement,
            order = order,
            uid = order.uid(Network.SEPOLIA.chainId, COW_SEPOLIA.settlement, account),
            signature = Signature("0".repeat(63) + "1", "0".repeat(63) + "1", 0),
            sell = weth,
            buy = usdc,
            signedAt = validTo - 300,
            rangeStart = 90,
        )
    }

    /** Uniswap's deployment as [reading] has it; no call is answered. */
    private inner class Venue : UniswapV3Chain {
        var reading: VenueReading = VenueReading.Verified(deployment)

        override suspend fun venue(network: Network): VenueReading = reading

        override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation =
            Simulation.Reverted(120)
    }
}

package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.GPv2Settlement
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeQuote
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.FinalizedBlock
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptLog
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.repository.prunable
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

class TransfersTest {

    private val alice = Address.of("0x" + "aa".repeat(20))
    private val bob = Address.of("0x" + "bb".repeat(20))
    private val usdc = Token(Address.of("0x" + "cc".repeat(20)), "USDC", "USD Coin", 6, icon = null)
    private val gwei = 1_000_000_000L

    private val inputs = FakeInputs()
    private var native: BalanceReading = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
    private var token: TokenBalanceReading = TokenBalanceReading.Verified(TokenUnits.of("5000000"), 100)
    private val balances = object : NativeBalances {
        override fun observeBalance(network: Network, address: Address): Flow<BalanceReading> = flowOf(native)
    }
    private val tokens = object : TokenBalances {
        override fun observeBalance(network: Network, holder: Address, token: Address): Flow<TokenBalanceReading> =
            flowOf(this@TransfersTest.token)
    }
    private val sent = FakeSentTransfers()
    private var now = 1_800_000_100L
    private val orders = MemoryCowOrders()
    private val light = FakeLightClient()
    private val cowChain = FakeCowChain()
    private val quoteTransfer =
        QuoteTransfer(inputs, balances, tokens, sent, orders, cowChain, light, Network.SEPOLIA) { now }

    @Test
    fun anAddressIsTakenAsIsAndANameIsResolved() = runTest {
        val names = object : NameResolver {
            override suspend fun resolve(network: Network, name: String): NameResolution =
                if (name == "bob.eth") NameResolution.Resolved("bob.eth", bob, 77) else NameResolution.NoAddress
        }
        val resolve = ResolveRecipient(names)

        assertEquals(RecipientLookup.Found(bob, null, null), resolve(Network.SEPOLIA, " ${bob.hex} "))
        assertEquals(RecipientLookup.Found(bob, "bob.eth", 77), resolve(Network.SEPOLIA, "bob.eth"))
        assertEquals(RecipientLookup.NoAddress, resolve(Network.SEPOLIA, "nobody.eth"))
        assertEquals(RecipientLookup.NotAnAddress, resolve(Network.SEPOLIA, "bob"))
        assertEquals(RecipientLookup.NotAnAddress, resolve(Network.SEPOLIA, "0x1234"))
    }

    @Test
    fun aTypedAddressMustCarryItsChecksumRightAndANameMayStartWith0x() = runTest {
        val asked = mutableListOf<String>()
        val names = object : NameResolver {
            override suspend fun resolve(network: Network, name: String): NameResolution {
                asked += name
                return if (name == "dark.eth") {
                    NameResolution.OffchainOnly
                } else {
                    NameResolution.Resolved(name, bob, 5)
                }
            }
        }
        val resolve = ResolveRecipient(names)
        val vector = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed" // EIP-55's own example

        assertEquals(RecipientLookup.Found(Address.of(vector), null, null), resolve(Network.SEPOLIA, vector))
        assertEquals(
            RecipientLookup.Found(Address.of(vector), null, null),
            resolve(Network.SEPOLIA, vector.lowercase()),
        )
        assertEquals(RecipientLookup.ChecksumMismatch, resolve(Network.SEPOLIA, vector.replace("aAeb", "aaEb")))
        assertEquals(RecipientLookup.Found(bob, "0xfoo.eth", 5), resolve(Network.SEPOLIA, "0xfoo.eth"))
        assertEquals(RecipientLookup.OffchainOnly, resolve(Network.SEPOLIA, "dark.eth"))
        assertEquals(listOf("0xfoo.eth", "dark.eth"), asked)

        // Gnosis Chain has no names: refused there, never asked of a resolver (docs/networks.md, *Names*).
        assertEquals(RecipientLookup.NoNamesOn(Network.GNOSIS), resolve(Network.GNOSIS, "0xfoo.eth"))
        assertEquals(RecipientLookup.Found(Address.of(vector), null, null), resolve(Network.GNOSIS, vector))
        assertEquals(listOf("0xfoo.eth", "dark.eth"), asked)
    }

    @Test
    fun aNativeTransferIsQuotedFromVerifiedInputs() = runTest {
        val amount = UInt256.of(100_000_000_000_000_000L) // 0.1 ETH

        val quoting = quoteTransfer(alice, bob, TransferAsset.Native, amount, FeeLevel.NORMAL)
        val quote = assertIs<TransferQuoting.Quoted>(quoting).quote

        val tx = quote.transaction
        assertEquals(Network.SEPOLIA.chainId, tx.chainId)
        assertEquals(7, tx.nonce)
        assertEquals(bob, tx.to)
        assertEquals(amount, tx.value)
        assertContentEquals(ByteArray(0), tx.data)
        assertEquals(21_000, tx.gasLimit)
        assertEquals(UInt256.of(2 * gwei), tx.maxPriorityFeePerGas)
        assertEquals(UInt256.of(2 * 10 * gwei + 2 * gwei), tx.maxFeePerGas, "twice the next base fee plus the tip")
        assertEquals(UInt256.of(21_000L * 22 * gwei), quote.maxFee)
        assertEquals(UInt256.of(1_000_000_000_000_000_000L) - amount - quote.maxFee, quote.nativeAfterAtLeast)
        assertEquals(listOf(Triple(alice, bob, amount)), inputs.simulated.map { Triple(it.first, it.second, it.third) })
    }

    @Test
    fun aTokenTransferCallsItsContract() = runTest {
        inputs.gas = 51_000
        val amount = UInt256.of(1_500_000) // 1.5 USDC

        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Erc20Token(usdc), amount, FeeLevel.FAST),
        ).quote

        assertEquals(usdc.address, quote.transaction.to)
        assertEquals(UInt256.ZERO, quote.transaction.value)
        assertContentEquals(Erc20.transferData(bob, amount), quote.transaction.data)
        assertEquals(UInt256.of(5_000_000), quote.tokenBefore)
        assertEquals(UInt256.of(3_500_000), quote.tokenAfter)
        assertEquals(100, quote.tokenBalanceBlock)
    }

    /** A CoW order of [account]'s selling [amount] of USDC, valid until [validTo]. */
    private fun cowOrder(account: Address, amount: Long, validTo: Long): CowOrderRecord {
        val order = CowOrder(usdc.address, CowOrder.NATIVE_BUY_TOKEN, account, UInt256.of(amount), UInt256.ONE, validTo)
        return CowOrderRecord(
            account = account,
            network = Network.SEPOLIA,
            settlement = COW_SEPOLIA.settlement,
            order = order,
            uid = order.uid(Network.SEPOLIA.chainId, COW_SEPOLIA.settlement, account),
            signature = Signature("0".repeat(63) + "1", "0".repeat(63) + "1", 0),
            sell = TransferAsset.Erc20Token(usdc),
            buy = TransferAsset.Native,
            signedAt = validTo - 300,
            rangeStart = 90,
        )
    }

    @Test
    fun whatALiveCowOrderMayStillTakeIsNotTheTransfersToMove() = runTest {
        orders.orders.value = listOf(cowOrder(alice, 4_000_000, validTo = now + 200))
        assertEquals(
            TransferQuoting.InsufficientToken,
            quoteTransfer(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.of(1_500_000), FeeLevel.FAST),
        )
        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.of(1_000_000), FeeLevel.FAST),
        ).quote
        assertEquals(UInt256.of(4_000_000), quote.heldByOrders)
        assertEquals(UInt256.ZERO, quote.tokenAfter)

        // Once a finalized block is past its validTo, it takes nothing more.
        light.status.value = LightClientStatus.Synced(130, now + 400, FinalizedBlock(120, now + 201))
        assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.of(1_500_000), FeeLevel.FAST),
        )
    }

    @Test
    fun anOrderFilledAtTheHeadIsNotCountedTwice() = runTest {
        val order = cowOrder(alice, 4_000_000, validTo = now + 200)
        orders.orders.value = listOf(order)
        // The settlement counts it filled at the head: the balance already shows the fill.
        cowChain.answers[COW_SEPOLIA.settlement to GPv2Settlement.filledAmount(order.uid).toHex()] =
            Simulation.Succeeded(UInt256.of(4_000_000).toBytes32().toHex(), 121)
        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.of(1_500_000), FeeLevel.FAST),
        ).quote
        assertEquals(UInt256.ZERO, quote.heldByOrders)
    }

    @Test
    fun anUnreadableRecordOfOrdersHoldsNothingBackFromATransfer() = runTest {
        orders.orders.value = listOf(cowOrder(alice, 4_000_000, validTo = now + 200))
        orders.unreadable = true
        assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.of(1_500_000), FeeLevel.FAST),
        )
    }

    @Test
    fun whatTheSenderCannotPayIsRefusedBeforeAnythingIsSigned() = runTest {
        assertEquals(
            TransferQuoting.InsufficientFunds,
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.of(2_000_000_000_000_000_000L), FeeLevel.SLOW),
        )
        assertEquals(
            TransferQuoting.InsufficientToken,
            quoteTransfer(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.of(6_000_000), FeeLevel.SLOW),
        )
        // The whole balance as the amount leaves nothing for the fee.
        assertEquals(
            TransferQuoting.InsufficientFunds,
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.of(1_000_000_000_000_000_000L), FeeLevel.SLOW),
        )
    }

    @Test
    fun theEnginesEstimateIsTheGasLimitEvenBelowThe21000OfOldForks() = runTest {
        // What the Rust engine estimated for a zero-value transfer on Sepolia's Amsterdam
        // (EIP-2780); whatever it answers is the limit, no floor added.
        inputs.gas = 17_433

        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        ).quote

        assertEquals(17_433, quote.transaction.gasLimit)
    }

    @Test
    fun aCheapRefusalAsksTheChainNothingMore() = runTest {
        assertEquals(
            TransferQuoting.InsufficientToken,
            quoteTransfer(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.of(6_000_000), FeeLevel.SLOW),
        )
        assertEquals(0, inputs.nonces + inputs.feeHistories, "no nonce, no fee history for a transfer refused before")
    }

    @Test
    fun anInfeasibleEstimateIsSaidAsTheEvmSaysIt() = runTest {
        inputs.estimate = GasEstimate.Infeasible("insufficient funds for transfer")
        assertEquals(
            TransferQuoting.InsufficientFunds,
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
        inputs.estimate = GasEstimate.Infeasible("max initcode size exceeded")
        assertEquals(
            TransferQuoting.Infeasible("max initcode size exceeded"),
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
    }

    @Test
    fun aRecipientThatCanNeverGiveItBackIsRefused() = runTest {
        val names = object : NameResolver {
            override suspend fun resolve(
                network: Network,
                name: String,
            ): NameResolution = NameResolution.Resolved(name, Address.ZERO, 77)
        }
        val resolve = ResolveRecipient(names)
        assertEquals(RecipientLookup.ZeroAddress, resolve(Network.SEPOLIA, "0x" + "00".repeat(20)))
        assertEquals(
            RecipientLookup.NoAddress,
            resolve(Network.SEPOLIA, "burn.eth"),
            "ENS's zero address is no address set",
        )

        assertEquals(
            TransferQuoting.ToZeroAddress,
            quoteTransfer(alice, Address.ZERO, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
        assertEquals(
            TransferQuoting.ToTokenContract,
            quoteTransfer(alice, usdc.address, TransferAsset.Erc20Token(usdc), UInt256.ONE, FeeLevel.NORMAL),
        )
        assertEquals(0, inputs.simulated.size + inputs.nonces + inputs.feeHistories, "nothing asked of the chain")
    }

    @Test
    fun anOwnTransferNotMinedYetKeepsItsNonceAndWhatItMayStillSpend() = runTest {
        val earlier = SentTransfer(
            network = Network.SEPOLIA,
            account = alice,
            nonce = 7,
            signed = SignedTransfer(byteArrayOf(2, 1)),
            action = SentAction.Transfer(bob, TransferAsset.Native, UInt256.of(100_000_000_000_000_000L)),
            maxCost = UInt256.of(900_000_000_000_000_000L), // 0.9 of the 1 ETH balance
            sentAt = 1_800_000_000,
        )
        sent.transfers.value = listOf(earlier, earlier.copy(account = bob, nonce = 40))
        // The engine forgot it (a restart): its pending count says 7, the nonce the earlier one has.

        assertEquals(
            TransferQuoting.InsufficientFunds,
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.of(200_000_000_000_000_000), FeeLevel.NORMAL),
            "0.9 may still go to the earlier transfer: 0.2 more does not fit",
        )
        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.of(1_000_000_000L), FeeLevel.NORMAL),
        ).quote
        assertEquals(8, quote.transaction.nonce, "after the earlier one, not on it")
        assertEquals(UInt256.of(900_000_000_000_000_000L), quote.pendingCost)

        // Mined since: the record forgets it, and the nonce follows the chain again.
        inputs.mined = 8
        quoteTransfer(alice, bob, TransferAsset.Native, UInt256.of(1_000_000_000L), FeeLevel.NORMAL)
        assertEquals(listOf(earlier.copy(account = bob, nonce = 40)), sent.transfers.value)
    }

    @Test
    fun anOwnTransferNoBlockTookInTimeHoldsItsNonceNoLonger() = runTest {
        val earlier = SentTransfer(
            Network.SEPOLIA,
            alice,
            7,
            SignedTransfer(byteArrayOf(2, 1)),
            SentAction.Transfer(bob, TransferAsset.Native, UInt256.ONE),
            UInt256.ONE,
            sentAt = now - RECORD_LIFETIME_SECONDS - 1,
        )
        sent.transfers.value = listOf(earlier)

        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        ).quote

        assertEquals(7, quote.transaction.nonce, "past its lifetime: its nonce is free, and one of two is mined")
        assertEquals(UInt256.ZERO, quote.pendingCost)
        assertEquals(listOf(earlier), sent.transfers.value, "its record stays, for its status, until 7 is mined")

        inputs.mined = 8
        quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL)
        assertEquals(emptyList(), sent.transfers.value)
    }

    @Test
    fun anEarlierTransactionThatMayHaveBeenSentHoldsTheNextOneBack() = runTest {
        val maybe = SentTransfer(
            Network.SEPOLIA,
            alice,
            7,
            SignedTransfer(byteArrayOf(2, 1)),
            SentAction.Transfer(bob, TransferAsset.Native, UInt256.ONE),
            UInt256.of(900_000_000_000_000_000L),
            sentAt = now - 60,
            maybeSent = true,
        )
        sent.transfers.value = listOf(maybe)

        assertEquals(
            TransferQuoting.EarlierMaybeSent(maybe.hash),
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
            "retried, never replaced: a new one could take nonce 8 and pay twice",
        )
        // Refused before any read but the mined count it needs started: the engine's block.
        assertEquals(0, inputs.simulated.size + inputs.nonces + inputs.feeHistories + inputs.estimates, "nothing asked")

        // Past its lifetime it holds nothing back, and neither its nonce nor its cost.
        sent.transfers.value = listOf(maybe.copy(sentAt = now - RECORD_LIFETIME_SECONDS - 1))
        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        ).quote
        assertEquals(7, quote.transaction.nonce)
        assertEquals(UInt256.ZERO, quote.pendingCost)

        // Mined: its nonce is used, and it holds nothing back either.
        sent.transfers.value = listOf(maybe)
        inputs.mined = 8
        assertIs<TransferQuoting.Quoted>(quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL))
        assertEquals(emptyList(), sent.transfers.value)
    }

    @Test
    fun anEarlyRefusalWaitsForNoReadItNoLongerNeeds() = runTest {
        // The fee history never comes; the estimate says the transfer reverts.
        inputs.feesGate = CompletableDeferred()
        inputs.estimate = GasEstimate.Reverted("")

        assertEquals(
            TransferQuoting.Reverts(null),
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
    }

    @Test
    fun theNonceIsNeverBelowTheMinedCount() = runTest {
        inputs.pending = 6 // read at an older head than the mined count
        inputs.mined = 8

        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        ).quote

        assertEquals(8, quote.transaction.nonce)
    }

    @Test
    fun whatAnOwnTokenTransferStillMovesIsNotAvailableToTheNext() = runTest {
        val earlier = SentTransfer(
            Network.SEPOLIA,
            alice,
            7,
            SignedTransfer(byteArrayOf(2, 1)),
            SentAction.Transfer(bob, TransferAsset.Erc20Token(usdc), amount = UInt256.of(4_000_000)),
            maxCost = UInt256.ONE,
            sentAt = now,
        )
        sent.transfers.value = listOf(earlier)
        val usdcAsset = TransferAsset.Erc20Token(usdc)

        assertEquals(
            TransferQuoting.InsufficientToken,
            quoteTransfer(alice, bob, usdcAsset, UInt256.of(2_000_000), FeeLevel.NORMAL),
            "5 USDC held, 4 still on their way: 2 more would revert once those are mined",
        )
        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, usdcAsset, UInt256.of(1_000_000), FeeLevel.NORMAL),
        ).quote
        assertEquals(UInt256.of(4_000_000), quote.pendingTokenAmount)
        assertEquals(UInt256.ZERO, quote.tokenAfter)
    }

    @Test
    fun aTransferIsRecordedOnceAPeerTookItAndNotBefore() = runTest {
        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        ).quote
        val signature = Signature("1".padStart(64, '0'), "2".padStart(64, '0'), recoveryId = 0)
        val signer = FakeSigner(Signing.Signed(signature))
        val unlock = Unlock.WithBiometrics("Send 1 wei")

        val rejected = FakeBroadcaster(Broadcast.Rejected("nonce too low"))
        SendTransfer(signer, rejected, sent) { 1_800_000_123 }(quote, unlock)
        assertEquals(emptyList(), sent.transfers.value, "rejected: it holds no nonce")

        // No peer answered: it may be out there all the same, so it is kept, holding nothing but
        // the next transaction back.
        SendTransfer(signer, FakeBroadcaster(Broadcast.Unavailable("no peer")), sent) { 1_800_000_120 }(quote, unlock)
        val maybe = sent.transfers.value.single()
        assertEquals(true, maybe.maybeSent)
        assertEquals(1_800_000_120, maybe.sentAt)
        assertEquals(
            TransferQuoting.EarlierMaybeSent(maybe.hash),
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )

        SendTransfer(signer, FakeBroadcaster(Broadcast.Sent("0x" + "00".repeat(32))), sent) { 1_800_000_123 }(
            quote,
            unlock,
        )

        val record = sent.transfers.value.single()
        assertEquals(false, record.maybeSent, "a peer took it")
        assertEquals(quote.transaction.nonce, record.nonce)
        assertEquals(SignedTransfer(quote.transaction.encodeSigned(signature)), record.signed)
        assertEquals(quote.maxFee + UInt256.ONE, record.maxCost)
        assertEquals(1_800_000_123, record.sentAt)
        assertEquals(SentAction.Transfer(bob, TransferAsset.Native, UInt256.ONE), record.action)
    }

    @Test
    fun aSentTransferIsSeenWithItsRecordAndItsReceipt() = runTest {
        val record = SentTransfer(
            Network.SEPOLIA,
            alice,
            7,
            SignedTransfer(byteArrayOf(2, 9)),
            SentAction.Transfer(bob, TransferAsset.Native, UInt256.ONE),
            UInt256.ONE,
            1,
        )
        sent.transfers.value = listOf(record)
        val receipts = object : ReceiptTracker {
            override fun observe(network: Network, hash: String): Flow<TransactionProgress> =
                flowOf(TransactionProgress.Included(100, succeeded = true))
        }

        val observe = ObserveSentTransfer(sent, receipts, MemoryTransactionHistory())

        assertEquals(
            SentTransferStatus(record, TransactionProgress.Included(100, succeeded = true)),
            observe(Network.SEPOLIA, record.hash).first(),
        )
        assertEquals(null, observe(Network.SEPOLIA, "0x" + "00".repeat(32)).first().transfer)
    }

    @Test
    fun aSwapThatWentThroughSaysWhatItPaidAndOneThatRevertedDoesNot() = runTest {
        val link = Token(Address.of("0x" + "77".repeat(20)), "LINK", "ChainLink Token", 18, icon = null)
        val swap = SentAction.Swap(
            Address.of("0x" + "50".repeat(20)),
            TransferAsset.Native,
            UInt256.ONE,
            TransferAsset.Erc20Token(link),
            UInt256.ONE,
        )
        val record = SentTransfer(Network.SEPOLIA, alice, 7, SignedTransfer(byteArrayOf(2, 9)), swap, UInt256.ONE, 1)
        sent.transfers.value = listOf(record)
        val paid = ReceiptLog(
            link.address,
            listOf(
                "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                "0x" + "00".repeat(12) + "30".repeat(20),
                "0x" + "00".repeat(12) + alice.hex.drop(2),
            ),
            "0x" + "00".repeat(31) + "2a",
        )
        var succeeded = true
        val receipts = object : ReceiptTracker {
            override fun observe(network: Network, hash: String): Flow<TransactionProgress> =
                flowOf(TransactionProgress.Finalized(100, succeeded, listOf(paid)))
        }

        // As in the app: the history holds the swap, finalized; its receipt still says what it paid.
        val history = MemoryTransactionHistory()
        history.record(record)
        history.settle(record.hash, SentOutcome.Finalized(100, succeeded = true))
        val observe = ObserveSentTransfer(sent, receipts, history)
        assertEquals(UInt256.of(42), observe(Network.SEPOLIA, record.hash).first().received)
        succeeded = false
        assertEquals(null, observe(Network.SEPOLIA, record.hash).first().received)
    }

    @Test
    fun aZeroAmountIsNothingToSend() = runTest {
        assertEquals(
            TransferQuoting.NothingToSend,
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ZERO, FeeLevel.NORMAL),
        )
        assertEquals(0, inputs.simulated.size, "nothing asked of the chain")
    }

    @Test
    fun anAbsurdFeeAnswerIsNoAnswerNotACrash() = runTest {
        // A base fee of 2 × 10^76: the cap fits in 256 bits, times the gas it does not.
        val huge = UInt256.parse("1" + "0".repeat(76))!!
        inputs.fees = FeeReading.Quoted(
            FeeQuote(huge + huge, FeeLevel.entries.associateWith { UInt256.ONE }, blockNumber = 99),
        )

        assertEquals(
            TransferQuoting.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.FEES),
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
    }

    @Test
    fun aTransferThatRevertsOrThatTheTokenRefusesStopsAtTheQuote() = runTest {
        inputs.simulation = Simulation.Reverted(100)
        assertEquals(
            TransferQuoting.Reverts(100),
            quoteTransfer(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.ONE, FeeLevel.NORMAL),
        )
        // transfer() returning false: refused as surely as a revert.
        inputs.simulation = Simulation.Succeeded("0".repeat(64), 100)
        assertEquals(
            TransferQuoting.Reverts(100),
            quoteTransfer(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.ONE, FeeLevel.NORMAL),
        )
        inputs.estimate = GasEstimate.Reverted("")
        assertEquals(
            TransferQuoting.Reverts(null),
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
    }

    @Test
    fun nothingIsQuotedOnLessThanVerifiedState() = runTest {
        native = BalanceReading.Syncing
        assertEquals(
            TransferQuoting.Syncing,
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
        // A balance last verified at an older head is shown, never quoted on: it may have changed.
        native = BalanceReading.LastKnown(Wei.of("1000000000000000000"), 99, asOf = 1_792_100_000)
        assertEquals(
            TransferQuoting.Syncing,
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
        native = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
        token = TokenBalanceReading.LastKnown(TokenUnits.of("5000000"), 99, asOf = null)
        assertEquals(
            TransferQuoting.Syncing,
            quoteTransfer(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.ONE, FeeLevel.NORMAL),
        )
        token = TokenBalanceReading.Verified(TokenUnits.of("5000000"), 100)
        native = BalanceReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(
            TransferQuoting.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.BALANCE),
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
        native = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
        inputs.fees = FeeReading.Unavailable(ReadUnavailable.STALE_ANCHOR)
        assertEquals(
            TransferQuoting.Unavailable(ReadUnavailable.STALE_ANCHOR, QuoteFigure.FEES),
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
        // Which figure failed is said, so that the screen can name it.
        inputs.fees = FeeReading.Quoted(
            FeeQuote(UInt256.of(10 * gwei), FeeLevel.entries.associateWith { UInt256.of(gwei) }, blockNumber = 99),
        )
        inputs.estimate = GasEstimate.Unavailable(ReadUnavailable.UNPROVEN, "no proof peer")
        assertEquals(
            TransferQuoting.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.GAS),
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
    }

    @Test
    fun whatIsSignedIsTheQuotedTransactionAndItIsBroadcastAsSigned() = runTest {
        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        ).quote
        val signature = Signature("1".padStart(64, '0'), "2".padStart(64, '0'), recoveryId = 1)
        val signer = FakeSigner(Signing.Signed(signature))
        val broadcaster = FakeBroadcaster(Broadcast.Sent("0x" + "00".repeat(32)))
        val unlock = Unlock.WithPassword(WalletPassword("correct horse battery"))

        val sending = SendTransfer(signer, broadcaster, sent) { 1_800_000_000 }
        val result = assertIs<TransferSending.Sent>(sending(quote, unlock))

        val asked = Triple<Address, Digest, Unlock>(alice, quote.transaction.signingDigest(), unlock)
        assertEquals(listOf(asked), signer.asked)
        val raw = quote.transaction.encodeSigned(signature)
        assertContentEquals(raw, broadcaster.sent.single())
        assertEquals(Eip1559Transaction.hashOf(raw), result.hash, "the raw bytes' own hash")
    }

    @Test
    fun aSignatureOrABroadcastThatIsRefusedIsSaidAndNothingElseHappens() = runTest {
        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        ).quote
        val broadcaster = FakeBroadcaster(Broadcast.Rejected("nonce too low"))
        val unlock = Unlock.WithBiometrics("Send 1 wei")

        assertEquals(
            TransferSending.NotSigned(Signing.Cancelled),
            SendTransfer(FakeSigner(Signing.Cancelled), broadcaster, sent)(quote, unlock),
        )
        assertEquals(0, broadcaster.sent.size, "nothing left the device")
        val signature = Signature("1".padStart(64, '0'), "2".padStart(64, '0'), recoveryId = 0)
        val notSent = assertIs<TransferSending.NotSent>(
            SendTransfer(FakeSigner(Signing.Signed(signature)), broadcaster, sent)(quote, unlock),
        )
        assertEquals(Broadcast.Rejected("nonce too low"), notSent.broadcast)
        assertEquals(SignedTransfer(quote.transaction.encodeSigned(signature)), notSent.signed)
        assertEquals(emptyList(), sent.transfers.value, "never sent: nothing recorded, no nonce held")
    }

    @Test
    fun aTransferNotSentIsRetriedWithItsOwnBytes() = runTest {
        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        ).quote
        val signature = Signature("1".padStart(64, '0'), "2".padStart(64, '0'), recoveryId = 0)
        val unavailable = FakeBroadcaster(Broadcast.Unavailable("no peer"))
        val sending = SendTransfer(FakeSigner(Signing.Signed(signature)), unavailable, sent)
        val notSent = assertIs<TransferSending.NotSent>(sending(quote, Unlock.WithBiometrics("Send 1 wei")))

        val again = FakeBroadcaster(Broadcast.Sent("0x" + "00".repeat(32)))
        val retry = RebroadcastTransfer(again, sent, receipts()) { 1_800_009_000 }
        val resent = assertIs<TransferSending.Sent>(retry(notSent.transfer))

        assertContentEquals(unavailable.sent.single(), again.sent.single(), "the very bytes, not a new quote")
        assertEquals(notSent.signed.hash, resent.hash)
        assertEquals(
            listOf(notSent.transfer.copy(sentAt = 1_800_009_000, maybeSent = false)),
            sent.transfers.value,
            "recorded once a peer took it, with that moment: its pool lifetime runs from there",
        )
    }

    @Test
    fun aRetryRejectedBecauseItsFirstBroadcastWasMinedIsSent() = runTest {
        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        ).quote
        val signature = Signature("1".padStart(64, '0'), "2".padStart(64, '0'), recoveryId = 0)
        val unavailable = FakeBroadcaster(Broadcast.Unavailable("no peer"))
        val notSent = assertIs<TransferSending.NotSent>(
            SendTransfer(FakeSigner(Signing.Signed(signature)), unavailable, sent) { now }(
                quote,
                Unlock.WithBiometrics("x"),
            ),
        )
        val rejection = Broadcast.Rejected("nonce too low: next nonce 8, tx nonce 7")
        val tooLow = FakeBroadcaster(rejection)

        assertEquals(listOf(notSent.transfer), sent.transfers.value, "maybe sent")

        // Its receipt cannot be verified right now: neither verdict is known, and the record stays.
        val unreadable = RebroadcastTransfer(
            tooLow,
            sent,
            receipts(TransactionProgress.Unavailable(ReadUnavailable.UNPROVEN)),
        )
        assertEquals(TransferSending.RejectionUnverified(rejection, notSent.transfer), unreadable(notSent.transfer))
        assertEquals(listOf(notSent.transfer), sent.transfers.value)

        // In no block at the head: turned away this time, and the record stays as it was. "Nonce too
        // low" may be a block past the light client's head; "already known", a pool that has it.
        val pending = RebroadcastTransfer(tooLow, sent, receipts(TransactionProgress.Pending))(notSent.transfer)
        assertEquals(TransferSending.NotSent(rejection, notSent.transfer), pending)
        assertEquals(listOf(notSent.transfer), sent.transfers.value, "still maybe sent: nothing new passes it")
        assertEquals(
            TransferQuoting.EarlierMaybeSent(notSent.transfer.hash),
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )

        // A peer took the first broadcast after all, and a block has it: sent, not "never minable".
        val mined = RebroadcastTransfer(tooLow, sent, receipts(TransactionProgress.Included(100, true))) { now }
        assertEquals(TransferSending.Sent(notSent.signed.hash), mined(notSent.transfer))
        assertEquals(listOf(notSent.transfer.copy(sentAt = now, maybeSent = false)), sent.transfers.value)
    }

    @Test
    fun aRetryNoPeerAnsweredStaysMaybeSentFromItsOwnBroadcast() = runTest {
        val maybe = SentTransfer(
            Network.SEPOLIA,
            alice,
            7,
            SignedTransfer(byteArrayOf(2, 1)),
            SentAction.Transfer(bob, TransferAsset.Native, UInt256.ONE),
            UInt256.ONE,
            sentAt = 1_800_000_000,
            maybeSent = true,
        )
        sent.transfers.value = listOf(maybe)

        val again = RebroadcastTransfer(FakeBroadcaster(Broadcast.Unavailable(null)), sent, receipts()) { now }
        val notSent = assertIs<TransferSending.NotSent>(again(maybe))

        assertEquals(maybe.copy(sentAt = now), notSent.transfer)
        assertEquals(listOf(maybe.copy(sentAt = now)), sent.transfers.value, "its lifetime runs from this broadcast")

        val refused = RebroadcastTransfer(FakeBroadcaster(Broadcast.Refused("not a transaction")), sent, receipts())
        assertIs<TransferSending.NotSent>(refused(maybe))
        assertEquals(listOf(maybe.copy(sentAt = now)), sent.transfers.value, "a refusal proves nothing of the first")
    }

    private fun receipts(progress: TransactionProgress = TransactionProgress.Pending) = object : ReceiptTracker {
        override fun observe(network: Network, hash: String): Flow<TransactionProgress> = flowOf(progress)
    }

    private inner class FakeInputs : TransactionInputs {
        var gas = 21_000L
        var estimate: GasEstimate? = null
        var simulation: Simulation = Simulation.Succeeded("", 100)
        var fees: FeeReading = FeeReading.Quoted(
            FeeQuote(
                nextBaseFee = UInt256.of(10 * gwei),
                tips = mapOf(
                    FeeLevel.SLOW to UInt256.of(1 * gwei),
                    FeeLevel.NORMAL to UInt256.of(2 * gwei),
                    FeeLevel.FAST to UInt256.of(3 * gwei),
                ),
                blockNumber = 99,
            ),
        )
        val simulated = mutableListOf<Triple<Address, Address, UInt256>>()
        var nonces = 0
        var feeHistories = 0
        var estimates = 0

        var mined = 7L
        var pending = 7L

        /** While set, the fee history waits for it. */
        var feesGate: CompletableDeferred<Unit>? = null

        override suspend fun nonce(network: Network, account: Address): NonceReading {
            nonces++
            return NonceReading.Read(pending)
        }

        override suspend fun minedNonce(network: Network, account: Address): NonceReading = NonceReading.Read(mined)

        override suspend fun fees(network: Network): FeeReading {
            feeHistories++
            feesGate?.await()
            return fees
        }

        override suspend fun estimateGas(
            network: Network,
            from: Address,
            to: Address,
            value: UInt256,
            data: ByteArray,
        ): GasEstimate {
            estimates++
            return estimate ?: GasEstimate.Estimated(gas)
        }

        override suspend fun simulate(
            network: Network,
            from: Address,
            to: Address,
            value: UInt256,
            data: ByteArray,
        ): Simulation {
            simulated += Triple(from, to, value)
            return simulation
        }
    }

    private class FakeSentTransfers : SentTransfers {
        val transfers = MutableStateFlow<List<SentTransfer>>(emptyList())

        override fun observe(): Flow<SentTransferList> = transfers.map { SentTransferList.Loaded(it) }

        override suspend fun record(transfer: SentTransfer): Boolean {
            transfers.value = transfers.value.filterNot { it.hash == transfer.hash } + transfer
            return true
        }


        override suspend fun prune(account: AccountId, minedCount: Long, unlistedBefore: Long): Boolean {
            transfers.value = transfers.value.filterNot { it.prunable(account, minedCount, unlistedBefore) }
            return true
        }
    }

    private class FakeSigner(private val answer: Signing) : Signer {
        val asked = mutableListOf<Triple<Address, Digest, Unlock>>()

        override suspend fun sign(account: Address, digest: Digest, unlock: Unlock): Signing {
            asked += Triple(account, digest, unlock)
            return answer
        }
    }

    private class FakeBroadcaster(private val answer: Broadcast) : Broadcaster {
        val sent = mutableListOf<ByteArray>()

        override suspend fun send(network: Network, raw: ByteArray): Broadcast {
            sent += raw
            return answer
        }
    }
}

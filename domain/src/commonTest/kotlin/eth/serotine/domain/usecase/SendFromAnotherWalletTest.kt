package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.BlockReading
import eth.serotine.domain.repository.BlockTransactions
import eth.serotine.domain.repository.ChainTransaction
import eth.serotine.domain.repository.HandoffAvailability
import eth.serotine.domain.repository.HandoffOpening
import eth.serotine.domain.repository.HeadReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.repository.WalletHandoff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class SendFromAnotherWalletTest {

    private val network = Network.SEPOLIA
    private val from = Address.of("0x1111111111111111111111111111111111111111")
    private val to = Address.of("0x2222222222222222222222222222222222222222")
    private val other = Address.of("0x3333333333333333333333333333333333333333")
    private val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc")
    private val account = AccountId(network, from)

    private fun account(kind: AccountKind, custody: AccountCustody) = Account(network, from, kind, "A", custody)

    private class Wallets(
        var availability: HandoffAvailability = HandoffAvailability.AVAILABLE,
        var opening: HandoffOpening = HandoffOpening.OPENED,
    ) : WalletHandoff {
        val opened = mutableListOf<String>()
        override fun availability() = availability
        override suspend fun open(link: String): HandoffOpening {
            opened += link
            return opening
        }
    }

    /** Blocks a test fills; [head] the newest verified one; [unreadable] blocks that cannot be read. */
    private class Blocks(var head: HeadReading = HeadReading.At(100)) : BlockTransactions {
        val bodies = mutableMapOf<Long, List<ChainTransaction>>()
        val unreadable = mutableSetOf<Long>()
        val read = mutableListOf<Long>()
        override suspend fun head(network: Network) = head
        override suspend fun transactions(network: Network, number: Long): BlockReading {
            read += number
            val top = (head as? HeadReading.At)?.number ?: return BlockReading.Unavailable(ReadUnavailable.SYNCING)
            if (number > top) return BlockReading.NotYet
            if (number in unreadable) return BlockReading.Unavailable(ReadUnavailable.UNPROVEN)
            return BlockReading.Read(bodies[number].orEmpty())
        }
    }

    private class Receipts(private val succeeded: Boolean = true) : ReceiptTracker {
        override fun observe(network: Network, hash: String): Flow<TransactionProgress> =
            flowOf(TransactionProgress.Pending, TransactionProgress.Included(105, succeeded))
    }

    private fun native(sender: Address, recipient: Address, wei: Long, hash: Char = 'a') =
        ChainTransaction("0x" + hash.toString().repeat(64), sender, recipient, UInt256.of(wei), "0x")

    private fun token(sender: Address, recipient: Address, units: Long, hash: Char = 'b') = ChainTransaction(
        "0x" + hash.toString().repeat(64),
        sender,
        usdc.address,
        UInt256.of(0L),
        "0x" + Erc20.transferData(recipient, UInt256.of(units)).toHex(),
    )

    private fun handoff(asset: TransferAsset = TransferAsset.Native, amount: Long = 1_000) =
        Handoff(account, asset, to, UInt256.of(amount), afterBlock = 100, link = "ethereum:")

    // OfferHandoff

    @Test
    fun aWatchedEOAIsOfferedItWhereAWalletTakesLinksGreyedOutWhereNoneDoes() {
        val wallets = Wallets()
        val offer = OfferHandoff(wallets)
        assertEquals(HandoffOffer.Offered, offer(account(AccountKind.EOA, AccountCustody.Watched)))
        wallets.availability = HandoffAvailability.NO_WALLET
        assertEquals(HandoffOffer.NoWallet, offer(account(AccountKind.EOA, AccountCustody.Watched)))
        wallets.availability = HandoffAvailability.NOT_ON_THIS_DEVICE
        assertEquals(HandoffOffer.LeftOut, offer(account(AccountKind.EOA, AccountCustody.Watched)))
    }

    @Test
    fun aSafeAndAnAccountWhoseKeyIsHeldHereAreLeftOut() {
        val offer = OfferHandoff(Wallets())
        assertEquals(HandoffOffer.LeftOut, offer(account(AccountKind.SAFE, AccountCustody.Watched)))
        assertEquals(HandoffOffer.LeftOut, offer(account(AccountKind.EOA, AccountCustody.InKeyStore)))
    }

    // HandOffTransfer

    @Test
    fun aTransferIsHandedOverAsItsLinkWatchedFromTheVerifiedHead() = runTest {
        val wallets = Wallets()
        val hand = HandOffTransfer(wallets, Blocks(HeadReading.At(4_242)))
        val start = hand(account, TransferAsset.Native, to, UInt256.of(5L))
        val opened = assertIs<HandoffStart.Opened>(start).handoff
        assertEquals(4_242, opened.afterBlock)
        assertEquals(listOf("ethereum:0x2222222222222222222222222222222222222222@11155111?value=5"), wallets.opened)
        assertEquals(wallets.opened.single(), opened.link)
    }

    @Test
    fun theZeroAddressTheTokensOwnContractAndNothingToSendAreRefusedBeforeAnythingOpens() = runTest {
        val wallets = Wallets()
        val hand = HandOffTransfer(wallets, Blocks())
        assertEquals(HandoffStart.ToZeroAddress, hand(account, TransferAsset.Native, Address.ZERO, UInt256.of(1L)))
        assertEquals(
            HandoffStart.ToTokenContract,
            hand(account, TransferAsset.Erc20Token(usdc), usdc.address, UInt256.of(1L)),
        )
        assertEquals(HandoffStart.NothingToSend, hand(account, TransferAsset.Native, to, UInt256.of(0L)))
        assertTrue(wallets.opened.isEmpty())
    }

    @Test
    fun nothingIsHandedOverWhileNoVerifiedBlockCanBeRead() = runTest {
        val wallets = Wallets()
        val start = HandOffTransfer(wallets, Blocks(HeadReading.Unavailable(ReadUnavailable.SYNCING)))(
            account,
            TransferAsset.Native,
            to,
            UInt256.of(1L),
        )
        assertEquals(HandoffStart.Unavailable(ReadUnavailable.SYNCING), start)
        assertTrue(wallets.opened.isEmpty())
    }

    @Test
    fun noAppTakingTheLinkIsSaid() = runTest {
        val start = HandOffTransfer(Wallets(opening = HandoffOpening.NO_WALLET), Blocks())(
            account,
            TransferAsset.Native,
            to,
            UInt256.of(1L),
        )
        assertEquals(HandoffStart.NoWallet, start)
        val refused = HandOffTransfer(Wallets(opening = HandoffOpening.REFUSED), Blocks())(
            account,
            TransferAsset.Native,
            to,
            UInt256.of(1L),
        )
        assertEquals(HandoffStart.NotOpened, refused, "a wallet that is there but not opened is not said to be missing")
    }

    // matchHandoff

    @Test
    fun aPaymentOfTheCurrencyMatchesBySenderRecipientAndValue() {
        val handoff = handoff()
        assertEquals(HandoffMatch.Exact, matchHandoff(handoff, native(from, to, 1_000)))
        assertEquals(HandoffMatch.OtherAmount(UInt256.of(999L)), matchHandoff(handoff, native(from, to, 999)))
        assertEquals(HandoffMatch.OtherSender(other), matchHandoff(handoff, native(other, to, 1_000)))
        assertNull(matchHandoff(handoff, native(other, to, 5)), "someone else's payment to the recipient")
        assertNull(matchHandoff(handoff, native(from, other, 1_000)), "to someone else")
        assertNull(matchHandoff(handoff, native(from, to, 1_000).copy(input = "0xdeadbeef")), "a call, not a payment")
    }

    @Test
    fun aTokenPaymentMatchesOnlyAnExactTransferCallOfThatToken() {
        val handoff = handoff(TransferAsset.Erc20Token(usdc))
        assertEquals(HandoffMatch.Exact, matchHandoff(handoff, token(from, to, 1_000)))
        assertEquals(HandoffMatch.OtherAmount(UInt256.of(10L)), matchHandoff(handoff, token(from, to, 10)))
        assertEquals(HandoffMatch.OtherSender(other), matchHandoff(handoff, token(other, to, 1_000)))
        assertNull(matchHandoff(handoff, token(from, other, 1_000)), "to someone else")
        assertNull(matchHandoff(handoff, token(from, to, 1_000).copy(to = other)), "another token")
        assertNull(matchHandoff(handoff, token(from, to, 1_000).copy(value = UInt256.of(1L))), "with ether on top")
        assertNull(matchHandoff(handoff, native(from, to, 1_000)), "the currency, not the token")
    }

    // WatchHandoff

    @Test
    fun theFirstBlockAboveTheHandoffWithTheTransferEndsTheWatchWithItsReceiptsVerdict() = runTest {
        val blocks = Blocks(HeadReading.At(103))
        blocks.bodies[102] = listOf(native(other, other, 1), native(from, to, 1_000, hash = 'c'))
        val deadline = testScheduler.timeSource.markNow().plus(10.minutes)
        val watch = WatchHandoff(blocks, Receipts(), pollInterval = 4.seconds)(handoff(), deadline)
        val seen = watch.toList()
        assertEquals(HandoffWatch.Watching(100), seen.first())
        assertEquals(
            HandoffWatch.Seen(HandoffMatch.Exact, "0x" + "c".repeat(64), 105, succeeded = true),
            seen.last(),
        )
        assertEquals(listOf(101L, 102L), blocks.read, "never the handoff's own block, and nothing past the find")
    }

    @Test
    fun withinOneBlockAnExactTransferWinsOverANearOne() = runTest {
        val blocks = Blocks(HeadReading.At(101))
        blocks.bodies[101] = listOf(native(other, to, 1_000, hash = 'd'), native(from, to, 1_000, hash = 'e'))
        val deadline = testScheduler.timeSource.markNow().plus(10.minutes)
        val last = WatchHandoff(blocks, Receipts())(handoff(), deadline).toList().last()
        assertEquals(HandoffWatch.Seen(HandoffMatch.Exact, "0x" + "e".repeat(64), 105, succeeded = true), last)
    }

    @Test
    fun aNearTransferIsKeptWhileTheAccountsOwnMayStillComeAndAnswersAtTheDeadline() = runTest {
        val blocks = Blocks(HeadReading.At(102))
        blocks.bodies[101] = listOf(native(other, to, 1_000, hash = 'd'))
        blocks.bodies[102] = listOf(native(from, to, 1_000, hash = 'e'))
        val deadline = testScheduler.timeSource.markNow().plus(10.minutes)
        val last = WatchHandoff(blocks, Receipts())(handoff(), deadline).toList().last()
        assertEquals(HandoffWatch.Seen(HandoffMatch.Exact, "0x" + "e".repeat(64), 105, succeeded = true), last)

        val alone = Blocks(HeadReading.At(101))
        alone.bodies[101] = listOf(native(other, to, 1_000, hash = 'd'))
        val start = testScheduler.timeSource.markNow()
        val updates = WatchHandoff(alone, Receipts())(handoff(), start.plus(1.minutes)).toList()
        assertTrue(HandoffWatch.Watching(101, nearBlock = 101) in updates, "said while it keeps looking")
        assertEquals(HandoffMatch.OtherSender(other), assertIs<HandoffWatch.Seen>(updates.last()).match)
        assertTrue(start.elapsedNow() >= 1.minutes, "not before the deadline")
    }

    @Test
    fun aReceiptThatCannotBeVerifiedEndsWithNoVerdictNeverAnException() = runTest {
        val blocks = Blocks(HeadReading.At(101))
        blocks.bodies[101] = listOf(native(from, to, 1_000))
        val gone = object : ReceiptTracker {
            override fun observe(network: Network, hash: String): Flow<TransactionProgress> =
                flowOf(TransactionProgress.Unavailable(ReadUnavailable.NO_ENGINE))
        }
        val deadline = testScheduler.timeSource.markNow().plus(10.minutes)
        val updates = WatchHandoff(blocks, gone)(handoff(), deadline).toList()
        assertTrue(updates.any { it is HandoffWatch.Found && it.unavailable == ReadUnavailable.NO_ENGINE })
        assertNull(assertIs<HandoffWatch.Seen>(updates.last()).succeeded)
    }

    @Test
    fun blocksThatStayUnreadableEndTheWatchAfterItsGraceWithWhy() = runTest {
        val blocks = Blocks(HeadReading.Unavailable(ReadUnavailable.NETWORK_OFF))
        val start = testScheduler.timeSource.markNow()
        val watch = WatchHandoff(blocks, Receipts(), pollInterval = 30.seconds, catchUp = 5.minutes)
        val last = watch(handoff(), start.plus(1.minutes)).toList().last()
        assertEquals(HandoffWatch.NotSeen(100, ReadUnavailable.NETWORK_OFF), last)
        assertTrue(start.elapsedNow() >= 6.minutes)
    }

    @Test
    fun aRevertedTransferIsSeenAsFailed() = runTest {
        val blocks = Blocks(HeadReading.At(101))
        blocks.bodies[101] = listOf(token(from, to, 1_000))
        val last = WatchHandoff(blocks, Receipts(succeeded = false))(
            handoff(TransferAsset.Erc20Token(usdc)),
            testScheduler.timeSource.markNow().plus(10.minutes),
        ).toList().last()
        assertEquals(false, assertIs<HandoffWatch.Seen>(last).succeeded)
    }

    @Test
    fun nothingSeenByTheDeadlineWithEveryBlockUpToTheHeadReadIsSaid() = runTest {
        val blocks = Blocks(HeadReading.At(100))
        val start = testScheduler.timeSource.markNow()
        val watch = WatchHandoff(blocks, Receipts(), pollInterval = 4.seconds)(handoff(), start.plus(1.minutes))
        val last = watch.toList().last()
        assertEquals(HandoffWatch.NotSeen(100), last)
        assertTrue(start.elapsedNow() >= 1.minutes)
    }

    @Test
    fun aBlockThatCannotBeReadIsSaidAndAskedForAgainNeverSkipped() = runTest {
        val blocks = Blocks(HeadReading.At(102))
        blocks.unreadable += 101
        blocks.bodies[102] = listOf(native(from, to, 1_000))
        val updates = mutableListOf<HandoffWatch>()
        val deadline = testScheduler.timeSource.markNow().plus(10.minutes)
        WatchHandoff(blocks, Receipts(), pollInterval = 4.seconds)(handoff(), deadline)
            .collect { update ->
                updates += update
                // Readable again after the first refusal was reported.
                if (update == HandoffWatch.Watching(100, ReadUnavailable.UNPROVEN)) blocks.unreadable.clear()
            }
        assertTrue(HandoffWatch.Watching(100, ReadUnavailable.UNPROVEN) in updates)
        assertIs<HandoffWatch.Seen>(updates.last())
        assertEquals(listOf(101L, 101L, 102L), blocks.read)
    }

    @Test
    fun aWatchBehindTheHeadIsNotEndedByTheDeadline() = runTest {
        val blocks = Blocks(HeadReading.At(103))
        blocks.unreadable += 101
        val start = testScheduler.timeSource.markNow()
        var polls = 0
        val updates = mutableListOf<HandoffWatch>()
        val watch = WatchHandoff(blocks, Receipts(), pollInterval = 30.seconds)(handoff(), start.plus(1.minutes))
        watch.collect { update ->
            updates += update
            if (update is HandoffWatch.Watching && update.unavailable != null && ++polls == 4) blocks.unreadable.clear()
        }
        // Past the deadline while block 101 could not be read: it was still read, and 102 and 103 after it.
        assertEquals(HandoffWatch.NotSeen(103), updates.last())
        assertTrue(start.elapsedNow() > 1.minutes)
    }
}

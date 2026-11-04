package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.CowTrade
import eth.serotine.domain.model.GPv2Settlement
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.FinalizedBlock
import eth.serotine.domain.repository.IndexedLog
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.LogCoverage
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CowOrderEndsTest {
    private val account = Address.of("0x" + "ac".repeat(20))
    private val usdc = Token(Address.of("0x" + "1c".repeat(20)), "USDC", "USD Coin", 6, icon = null)
    private val weth = Token(Address.of("0x" + "ff".repeat(20)), "WETH", "Wrapped Ether", 18, icon = null)
    private val settlement = COW_SEPOLIA.settlement
    private val validTo = 1_792_419_300L
    private val order = CowOrder(usdc.address, weth.address, account, UInt256.of(100_000_000), UInt256.of(5), validTo)
    private val uid = order.uid(Network.SEPOLIA.chainId, settlement, account)
    private val record = CowOrderRecord(
        account = account,
        network = Network.SEPOLIA,
        settlement = settlement,
        order = order,
        uid = uid,
        signature = Signature("0".repeat(63) + "1", "0".repeat(63) + "1", 0),
        sell = TransferAsset.Erc20Token(usdc),
        buy = TransferAsset.Erc20Token(weth),
        signedAt = validTo - 300,
        rangeStart = 100,
    )
    private val index = RecordingLogIndex()
    private val orders = MemoryCowOrders(record)
    private val light = FakeLightClient()
    private val wallet = ListAccountRepository(Account(Network.SEPOLIA, account, AccountKind.EOA, "Daily", AccountCustody.InKeyStore))
    private val prove = ProveCowOrderEnds(orders, index, light, wallet, Network.SEPOLIA) { validTo }

    private val ownerTopic = "0x" + "00".repeat(12) + account.hex.removePrefix("0x")
    private fun uidData(offset: String) = "0".repeat(62) + offset + "0".repeat(62) + "38" +
        uid.hex.removePrefix("0x") + "0".repeat(16)

    private fun trade(at: Long, from: Address = settlement, ofUid: String = uidData("c0")) = IndexedLog(
        log = ReceiptLog(
            address = from,
            topics = listOf(GPv2Settlement.TRADE_TOPIC, ownerTopic),
            data = "0x" + "00".repeat(12) + usdc.address.hex.drop(2) + "00".repeat(12) + weth.address.hex.drop(2) +
                UInt256.of(100_000_000).toBytes32().hex() + UInt256.of(6).toBytes32().hex() + "00".repeat(32) + ofUid,
        ),
        blockNumber = at,
        blockHash = "0x" + "bb".repeat(32),
        transactionHash = "0x" + "77".repeat(32),
        logIndex = 3,
    )

    private fun invalidation(at: Long) = IndexedLog(
        log = ReceiptLog(settlement, listOf(GPv2Settlement.ORDER_INVALIDATED_TOPIC, ownerTopic), "0x" + uidData("20")),
        blockNumber = at,
        blockHash = "0x" + "cc".repeat(32),
        transactionHash = "0x" + "88".repeat(32),
        logIndex = 0,
    )

    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun answer(vararg logs: IndexedLog) {
        index.answer = { LogsReading.Logs(logs.toList()) }
    }

    @Test
    fun theProofIsTheAccountsOwnLogsOverAFinalizedRange() = runTest {
        answer()
        prove.endOf(record, FinalizedBlock(140, validTo - 10))
        assertEquals(
            LogQuery(
                key = LogWatchKey.Mention(account),
                fromBlock = 100,
                toBlock = 140,
                topic0s = listOf(GPv2Settlement.TRADE_TOPIC, GPv2Settlement.ORDER_INVALIDATED_TOPIC),
                position = 1,
            ),
            index.queries.single(),
        )
    }

    @Test
    fun aTradeOfTheOrderProvesItFilled() = runTest {
        answer(trade(at = 120))
        assertEquals(
            CowOrderEnd.Filled(
                CowTrade(
                    account,
                    usdc.address,
                    weth.address,
                    UInt256.of(100_000_000),
                    UInt256.of(6),
                    UInt256.ZERO,
                    uid,
                ),
                block = 120,
                transactionHash = "0x" + "77".repeat(32),
            ),
            prove.endOf(record, FinalizedBlock(140, validTo - 10)),
        )
    }

    @Test
    fun anotherContractsOrAnotherOrdersTradeProvesNothing() = runTest {
        val elsewhere = Address.of("0x" + "99".repeat(20))
        val other = order.copy(buyAmount = UInt256.of(6)).uid(Network.SEPOLIA.chainId, settlement, account)
        val otherData = "0".repeat(62) + "c0" + "0".repeat(62) + "38" + other.hex.removePrefix("0x") + "0".repeat(16)
        answer(trade(at = 120, from = elsewhere), trade(at = 121, ofUid = otherData))
        assertNull(prove.endOf(record, FinalizedBlock(140, validTo - 10)))
    }

    @Test
    fun aCancellationOnChainProvesItNeverFilled() = runTest {
        answer(invalidation(at = 130))
        assertEquals(
            CowOrderEnd.Unfilled(endBlock = 130, cancelled = true),
            prove.endOf(record, FinalizedBlock(140, validTo - 10)),
        )
        // A fill before the cancellation is a fill.
        answer(trade(at = 125), invalidation(at = 130))
        assertTrue(prove.endOf(record, FinalizedBlock(140, validTo - 10)) is CowOrderEnd.Filled)
    }

    @Test
    fun noTradeUpToAFinalizedBlockPastValidToProvesItNeverFilled() = runTest {
        answer()
        assertNull(prove.endOf(record, FinalizedBlock(140, validTo)), "a finalized block still at validTo")
        assertEquals(
            CowOrderEnd.Unfilled(endBlock = 141, cancelled = false),
            prove.endOf(record, FinalizedBlock(141, validTo + 12)),
        )
    }

    @Test
    fun aRangeBelowTheEntrysStartCanNeverBeProvenButIsSaidSoOnlyOnceItCanFillNoMore() = runTest {
        index.answer = { LogsReading.BeforeStart(110) }
        assertNull(prove.endOf(record, FinalizedBlock(140, validTo)), "it may still fill: its tokens stay held back")
        assertEquals(CowOrderEnd.NotProvable, prove.endOf(record, FinalizedBlock(141, validTo + 12)))
    }

    @Test
    fun aSettlementLogThatDoesNotDecodeProvesNothing() = runTest {
        val garbled = trade(at = 120).let { it.copy(log = it.log.copy(data = it.log.data.dropLast(2))) }
        answer(garbled)
        assertNull(prove.endOf(record, FinalizedBlock(141, validTo + 12)), "it could hide this order's fill")
        // Another contract's log of any shape is no settlement's.
        val elsewhere = garbled.copy(log = garbled.log.copy(address = Address.of("0x" + "99".repeat(20))))
        answer(elsewhere)
        assertEquals(
            CowOrderEnd.Unfilled(endBlock = 141, cancelled = false),
            prove.endOf(record, FinalizedBlock(141, validTo + 12)),
        )
    }

    @Test
    fun aRangeNotCoveredYetIsTriedAgain() = runTest {
        index.answer = { LogsReading.NotCovered(100L..130L) }
        assertNull(prove.endOf(record, FinalizedBlock(140, validTo + 12)))
        index.answer = { LogsReading.NotWatched }
        assertNull(prove.endOf(record, FinalizedBlock(140, validTo + 12)), "not followed yet, as at every start")
        index.answer = { LogsReading.Unavailable(ReadUnavailable.UNPROVEN) }
        assertNull(prove.endOf(record, FinalizedBlock(140, validTo + 12)))
    }

    @Test
    fun anOrderOfAnAccountNoLongerInTheWalletCanNeverBeProven() = runTest {
        index.answer = { LogsReading.NotWatched }
        assertNull(prove.endOf(record, FinalizedBlock(140, validTo), removed = true), "it may still fill")
        assertEquals(CowOrderEnd.NotProvable, prove.endOf(record, FinalizedBlock(141, validTo + 12), removed = true))
        // Its entry still answering: proven as any.
        answer(trade(at = 120))
        val end = prove.endOf(record, FinalizedBlock(141, validTo + 12), removed = true)
        assertEquals(120, (end as CowOrderEnd.Filled).block)
    }

    @Test
    fun theWalletsAccountsSayWhichEntryIsGoneForGood() = runTest {
        index.answer = { LogsReading.NotWatched }
        val running = launch { prove() }
        light.status.value = LightClientStatus.Synced(150, validTo + 30, FinalizedBlock(141, validTo + 12))
        runCurrent()
        assertEquals(null, orders.orders.value.single().end, "the account is held: tried again")

        wallet.unreadable = true
        val other = Account(Network.SEPOLIA, Address.of("0x" + "bb".repeat(20)), AccountKind.EOA, "Other", AccountCustody.Watched)
        wallet.accounts.value = listOf(other)
        runCurrent()
        assertEquals(null, orders.orders.value.single().end, "an account list that cannot be read removes nothing")

        wallet.unreadable = false
        wallet.accounts.value = emptyList()
        runCurrent()
        assertEquals(CowOrderEnd.NotProvable, orders.orders.value.single().end, "removed: not provable")
        running.cancel()
    }

    @Test
    fun beforeItsFirstBlockIsFinalizedNothingIsAsked() = runTest {
        assertNull(prove.endOf(record, null))
        assertNull(prove.endOf(record, FinalizedBlock(99, validTo + 12)))
        assertTrue(index.queries.isEmpty())
    }

    @Test
    fun anEndProvenIsRecordedAndTheOrderAskedNoMore() = runTest {
        answer()
        val running = launch { prove() }
        light.status.value = LightClientStatus.Synced(150, validTo + 30, FinalizedBlock(141, validTo + 12))
        val covered = LogWatchKey.Mention(account) to LogCoverage(fromBlock = 50, covered = 50L..150L)
        index.coverage.value = LogCoverageReading.Read(mapOf(covered))
        runCurrent()
        assertEquals(CowOrderEnd.Unfilled(endBlock = 141, cancelled = false), orders.orders.value.single().end)
        val asked = index.queries.size
        light.status.value = LightClientStatus.Synced(160, validTo + 150, FinalizedBlock(150, validTo + 120))
        runCurrent()
        assertEquals(asked, index.queries.size, "an order whose end is proven is not asked again")
        running.cancel()
    }
}

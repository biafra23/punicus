package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.GPv2Settlement
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FinalizedBlock
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.Unlock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class CowCancellationsTest {
    private val account = Address.of("0x" + "ac".repeat(20))
    private val usdc = Token(Address.of("0x" + "1c".repeat(20)), "USDC", "USD Coin", 6, icon = null)
    private val weth = Token(Address.of("0x" + "ff".repeat(20)), "WETH", "Wrapped Ether", 18, icon = null)
    private val validTo = 1_792_419_300L
    private val order = CowOrder(usdc.address, weth.address, account, UInt256.of(100_000_000), UInt256.of(5), validTo)
    private val record = CowOrderRecord(
        account = account,
        network = Network.SEPOLIA,
        settlement = COW_SEPOLIA.settlement,
        order = order,
        uid = order.uid(Network.SEPOLIA.chainId, COW_SEPOLIA.settlement, account),
        signature = Signature("0".repeat(63) + "1", "0".repeat(63) + "1", 0),
        sell = TransferAsset.Erc20Token(usdc),
        buy = TransferAsset.Erc20Token(weth),
        signedAt = validTo - 300,
        rangeStart = 100,
    )
    private val inputs = FakeTransactionInputs()
    private val native = FakeNativeBalances()
    private val sent = MemorySentTransfers()
    private val light = FakeLightClient()
    private val cowChain = FakeCowChain()
    private val quote = QuoteCowCancellation(
        inputs,
        native,
        sent,
        cowChain,
        light,
        Network.SEPOLIA,
        clock = { validTo - 200 },
    )
    private val filledAmountCall = COW_SEPOLIA.settlement to GPv2Settlement.filledAmount(record.uid).toHex()

    init {
        native.of(account).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 120)
        light.status.value = LightClientStatus.Synced(120, validTo - 200, FinalizedBlock(100, validTo - 400))
        filled(UInt256.ZERO)
    }

    private fun filled(amount: UInt256) {
        cowChain.answers[filledAmountCall] = Simulation.Succeeded(amount.toBytes32().toHex(), 120)
    }

    @Test
    fun theCancellationIsTheOwnersInvalidateOrderToTheSettlement() = runTest {
        val quoted = assertIs<CowCancellationQuoting.Quoted>(quote(record, FeeLevel.NORMAL)).quote
        assertEquals(COW_SEPOLIA.settlement, quoted.call.transaction.to)
        assertEquals(UInt256.ZERO, quoted.call.transaction.value)
        assertContentEquals(GPv2Settlement.invalidateOrder(record.uid), quoted.call.transaction.data)
        assertEquals(account, quoted.call.from)
    }

    @Test
    fun anOrderThatCanFillNoMoreHasNothingToCancel() = runTest {
        assertEquals(
            CowCancellationQuoting.AlreadyEnded,
            quote(record.copy(end = CowOrderEnd.Unfilled(110, cancelled = false)), FeeLevel.NORMAL),
        )
        light.status.value = LightClientStatus.Synced(150, validTo + 40, FinalizedBlock(140, validTo + 1))
        assertEquals(CowCancellationQuoting.ValidityOver, quote(record, FeeLevel.NORMAL))
        // Past validTo at the head, before any finalized block is: no later block can fill it.
        light.status.value = LightClientStatus.Synced(150, validTo + 1, FinalizedBlock(140, validTo - 100))
        assertEquals(CowCancellationQuoting.ValidityOver, quote(record, FeeLevel.NORMAL))
    }

    @Test
    fun anOrderTheSettlementHoldsFilledOrCancelledAtTheHeadIsNotCancelledAgain() = runTest {
        filled(record.order.sellAmount)
        assertEquals(CowCancellationQuoting.AlreadyEnded, quote(record, FeeLevel.NORMAL))
        // A cancellation mined and not finalized yet: the maximum uint256.
        filled(UInt256.parse("115792089237316195423570985008687907853269984665640564039457584007913129639935")!!)
        assertEquals(CowCancellationQuoting.AlreadyEnded, quote(record, FeeLevel.NORMAL))
    }

    @Test
    fun aCancellationSentAndNotMinedIsNotSentTwice() = runTest {
        val pending = SentTransfer(
            network = Network.SEPOLIA,
            account = account,
            nonce = 7,
            signed = SignedTransfer(byteArrayOf(2, 7)),
            action = SentAction.CowCancellation(COW_SEPOLIA.settlement, record.uid),
            maxCost = UInt256.ONE,
            sentAt = validTo - 250,
        )
        sent.transfers.value = listOf(pending)
        assertEquals(CowCancellationQuoting.CancellationPending(pending.hash), quote(record, FeeLevel.NORMAL))
    }

    @Test
    fun aCancellationSentIsRecordedAsOne() = runTest {
        val quoted = assertIs<CowCancellationQuoting.Quoted>(quote(record, FeeLevel.NORMAL)).quote
        val send = SendCowCancellation(FakeSigner(FakeRecovery()), FakeBroadcaster(), sent, clock = { validTo - 190 })
        assertIs<TransferSending.Sent>(send(quoted, Unlock.WithPassword(WalletPassword("made up"))))
        assertEquals(
            SentAction.CowCancellation(COW_SEPOLIA.settlement, record.uid),
            sent.transfers.value.single().action,
        )
    }
}

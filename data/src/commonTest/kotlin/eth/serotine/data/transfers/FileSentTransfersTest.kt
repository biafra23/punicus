package eth.serotine.data.transfers

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.SafeListing
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString

@OptIn(ExperimentalCoroutinesApi::class)
class FileSentTransfersTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-sent-${Random.nextLong().toULong()}")
    private val file = Path(directory, "sent-transfers.json")
    private val alice = Address.of("0x" + "aa".repeat(20))
    private val bob = Address.of("0x" + "bb".repeat(20))
    private val usdc = Token(Address.of("0x" + "cc".repeat(20)), "USDC", "USD Coin", 6, icon = "usdc")

    private fun TestScope.record() = FileSentTransfers(file.toString(), StandardTestDispatcher(testScheduler))

    private fun transfer(nonce: Long, asset: TransferAsset = TransferAsset.Native, account: Address = alice) =
        SentTransfer(
            network = Network.SEPOLIA,
            account = account,
            nonce = nonce,
            signed = SignedTransfer(byteArrayOf(2, nonce.toByte(), -1)),
            action = SentAction.Transfer(bob, asset, UInt256.of(1_500_000)),
            maxCost = UInt256.of(21_000L * 30_000_000_000L),
            sentAt = 1_800_000_000 + nonce,
        )

    private suspend fun TestScope.stored() = (record().observe().first() as SentTransferList.Loaded).transfers

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun aRecordedTransferSurvivesARestartAsItWasSigned() = runTest {
        val native = transfer(7)
        val token = transfer(8, TransferAsset.Erc20Token(usdc))

        record().record(native)
        record().record(token)

        // The bundled icon's name comes back with the token, for its status to show.
        assertEquals(listOf(native, token), stored())
    }

    @Test
    fun theSameSignedTransferIsRecordedOnce() = runTest {
        record().record(transfer(7))
        record().record(transfer(7))

        assertEquals(1, stored().size)
    }

    @Test
    fun whatIsMinedIsForgottenForItsAccountAlone() = runTest {
        val store = record()
        listOf(transfer(6), transfer(7), transfer(8), transfer(9), transfer(3, account = bob)).forEach {
            store.record(it)
        }

        // However old one not mined is, it stays: past its lifetime it holds nothing, and its
        // status is still found.
        assertTrue(store.prune(AccountId(Network.SEPOLIA, alice), minedCount = 8, unlistedBefore = 0))

        assertEquals(listOf(transfer(8), transfer(9), transfer(3, account = bob)), stored())
    }

    @Test
    fun aTransactionMaybeSentIsRecordedAsSuchUntilAPeerTakesIt() = runTest {
        val maybe = transfer(7).copy(maybeSent = true)
        record().record(maybe)
        record().record(transfer(8))

        assertEquals(listOf(maybe, transfer(8)), stored())

        // A peer took it on a retry: the same hash, one record, sent now.
        record().record(transfer(7))
        assertEquals(listOf(transfer(8), transfer(7)), stored())
    }

    @Test
    fun aDeploymentWhoseSafeIsNotListedYetOutlivesItsNonceUntilItsOwnBound() = runTest {
        val store = record()
        val unlisted = transfer(6).copy(
            action = SentAction.SafeDeployment(Address.of("0x" + "5a".repeat(20)), listOf(alice), 1, "1.4.1", "Vault"),
        )
        val listed = transfer(7).copy(
            action = (unlisted.action as SentAction.SafeDeployment).copy(listed = SafeListing.LISTED),
        )
        store.record(unlisted)
        store.record(listed)

        // Both mined and old: the listed one goes; the unlisted one stays, its owners and name with it.
        assertTrue(store.prune(AccountId(Network.SEPOLIA, alice), minedCount = 8, unlistedBefore = 1_800_000_006))
        assertEquals(listOf(unlisted), stored())

        // Past its own bound, it goes too.
        assertTrue(store.prune(AccountId(Network.SEPOLIA, alice), minedCount = 8, unlistedBefore = 1_800_000_007))
        assertEquals(emptyList(), stored())
    }

    @Test
    fun aSafeTransactionsApprovalAndExecutionAreRecordedWithTheirRequest() = runTest {
        val safe = Address.of("0x" + "5a".repeat(20))
        // A made-up signature, of no key anyone holds.
        val signature = SafeSignature.Ecdsa(bob, Signature("1".padStart(64, '0'), "2".padStart(64, '0'), 1))
        val request = SigningRequest.of(
            Network.SEPOLIA,
            safe,
            SafeTx.call(bob, UInt256.of(5), ByteArray(0), nonce = 3),
            listOf(signature),
        )
        val approval = transfer(7).copy(action = SentAction.SafeApproval(request))
        val execution = transfer(8).copy(action = SentAction.SafeExecution(request))

        record().record(approval)
        record().record(execution)

        assertEquals(listOf(approval, execution), stored())
    }

    @Test
    fun aSafesDeploymentIsRecordedWithWhatItCreates() = runTest {
        val deployment = transfer(7).copy(
            action = SentAction.SafeDeployment(
                safe = Address.of("0x" + "5a".repeat(20)),
                owners = listOf(alice, bob),
                threshold = 1,
                version = "1.4.1",
                label = "Treasury",
            ),
        )
        val ended = transfer(8).copy(
            action = (deployment.action as SentAction.SafeDeployment).copy(listed = SafeListing.NO_SAFE),
        )

        record().record(deployment)
        record().record(ended)

        assertEquals(listOf(deployment, ended), stored())
    }

    @Test
    fun aTokenApprovalAndASwapAreRecordedWithWhatTheyMove() = runTest {
        val router = Address.of("0x" + "50".repeat(20))
        val link = Token(Address.of("0x" + "77".repeat(20)), "LINK", "ChainLink Token", 18, icon = "link")
        val approval = transfer(7).copy(action = SentAction.TokenApproval(usdc, router, UInt256.of(100_000_000)))
        val tokenSwap = transfer(8).copy(
            action = SentAction.Swap(
                router = router,
                sell = TransferAsset.Erc20Token(usdc),
                amountIn = UInt256.of(100_000_000),
                buy = TransferAsset.Erc20Token(link),
                minimumOut = UInt256.of("4288450000000000000"),
            ),
        )
        val etherSwap = transfer(9).copy(
            action = SentAction.Swap(
                router = router,
                sell = TransferAsset.Erc20Token(link),
                amountIn = UInt256.of("1000000000000000000"),
                buy = TransferAsset.Native,
                minimumOut = UInt256.of(1),
                weth = Address.of("0x" + "ff".repeat(20)),
            ),
        )

        record().record(approval)
        record().record(tokenSwap)
        record().record(etherSwap)

        assertEquals(listOf(approval, tokenSwap, etherSwap), stored())
    }

    @Test
    fun aCowOrdersCancellationIsRecordedWithItsSettlementAndUid() = runTest {
        val settlement = Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41")
        val uid = CowOrderUid.of("0x" + "ab".repeat(32) + alice.hex.removePrefix("0x") + "6ad9e7f4")
        val cancellation = transfer(7).copy(action = SentAction.CowCancellation(settlement, uid))

        record().record(cancellation)

        assertEquals(listOf(cancellation), stored())
        val text = SystemFileSystem.source(file).buffered().use { it.readString() }
        assertTrue(text.startsWith("""{"version":8,"""), "written as format 8: $text")
    }

    @Test
    fun aDappsTransactionIsRecordedWithItsRecipientValueAndDapp() = runTest {
        val call = transfer(7).copy(action = SentAction.DappCall(
                bob,
                UInt256.of(1_000),
                "Example dApp",
                eth.serotine.domain.repository.DappRequestId(eth.serotine.domain.repository.DappSessionId("ab".repeat(32)), 42),
            ),)

        record().record(call)

        assertEquals(listOf(call), stored())
        val text = SystemFileSystem.source(file).buffered().use { it.readString() }
        assertTrue(text.startsWith("""{"version":8,"""), "written as format 8: $text")
    }

    @Test
    fun aCancellationAndAnotherKindAtOnceIsPassedOver() = runTest {
        SystemFileSystem.createDirectories(directory)
        val uid = "0x" + "ab".repeat(32) + alice.hex.removePrefix("0x") + "6ad9e7f4"
        val cancellation = """"cancellation":{"settlement":"${bob.hex}","uid":"$uid"}"""
        val swap = """"swap":{"router":"${bob.hex}","amountIn":"1","minimumOut":"1"}"""
        val both = """{"account":"${alice.hex}","nonce":8,"raw":"0208ff",$cancellation,""" +
            """$swap,"maxCost":"1","sentAt":1800000008}"""
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":6,"transfers":[$both]}""") }

        assertEquals(emptyList(), stored())
    }

    @Test
    fun aSixthFormatRecordOfEitherBuildReadsAsItWasWritten() = runTest {
        SystemFileSystem.createDirectories(directory)
        val settlement = Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41")
        val uid = "0x" + "ab".repeat(32) + alice.hex.removePrefix("0x") + "6ad9e7f4"
        // Two builds took format 6 apart: main's for a CoW cancellation, with no chain (Sepolia, the
        // only network there was), and feature/networks's for each record's chain.
        val cancellation = """{"account":"${alice.hex}","nonce":7,"raw":"0207ff",""" +
            """"cancellation":{"settlement":"${settlement.hex}","uid":"$uid"},"maxCost":"1","sentAt":1800000007}"""
        val onChain = """{"account":"${alice.hex}","nonce":8,"raw":"0208ff","to":"${bob.hex}","amount":"1",""" +
            """"maxCost":"1","sentAt":1800000008,"chain":11155111}"""
        SystemFileSystem.sink(file).buffered().use {
            it.writeString("""{"version":6,"transfers":[$cancellation,$onChain]}""")
        }

        val read = stored()
        assertEquals(listOf(Network.SEPOLIA, Network.SEPOLIA), read.map { it.network })
        assertEquals(
            listOf(
                SentAction.CowCancellation(settlement, CowOrderUid.of(uid)),
                SentAction.Transfer(bob, TransferAsset.Native, UInt256.ONE),
            ),
            read.map { it.action },
        )
    }

    @Test
    fun aRecordOfTheFifthFormatReadsAsItWasWritten() = runTest {
        SystemFileSystem.createDirectories(directory)
        val v5 = """{"version":5,"transfers":[{"account":"${alice.hex}","nonce":7,"raw":"0207ff",""" +
            """"to":"${bob.hex}","amount":"1","maxCost":"1","sentAt":1800000007}]}"""
        SystemFileSystem.sink(file).buffered().use { it.writeString(v5) }

        assertEquals(listOf(SentAction.Transfer(bob, TransferAsset.Native, UInt256.ONE)), stored().map { it.action })
    }

    @Test
    fun anEntryOfTwoKindsAtOnceIsPassedOver() = runTest {
        SystemFileSystem.createDirectories(directory)
        val swap = """"swap":{"router":"${bob.hex}","amountIn":"1","minimumOut":"1"}"""
        val both = """{"account":"${alice.hex}","nonce":8,"raw":"0208ff","to":"${bob.hex}","amount":"1",""" +
            """$swap,"maxCost":"1","sentAt":1800000008}"""
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":5,"transfers":[$both]}""") }

        assertEquals(emptyList(), stored())
    }

    @Test
    fun aRecordOfTheSecondFormatReadsAsItWasWritten() = runTest {
        SystemFileSystem.createDirectories(directory)
        // What part 14 wrote, before a record could be a Safe's deployment.
        val v2 = """{"version":2,"transfers":[{"account":"${alice.hex}","nonce":7,"raw":"0207ff",""" +
            """"to":"${bob.hex}","token":null,"amount":"1500000","maxCost":"630000000000000","sentAt":1800000007}]}"""
        SystemFileSystem.sink(file).buffered().use { it.writeString(v2) }

        assertEquals(listOf(transfer(7)), stored())
    }

    @Test
    fun aRecordOfTheFirstFormatReadsAsItWasWritten() = runTest {
        SystemFileSystem.createDirectories(directory)
        // What part 11 wrote, before a record could be a Safe transaction's.
        val v1 = """{"version":1,"transfers":[{"account":"${alice.hex}","nonce":7,"raw":"0207ff",""" +
            """"to":"${bob.hex}","token":null,"amount":"1500000","maxCost":"630000000000000","sentAt":1800000007}]}"""
        SystemFileSystem.sink(file).buffered().use { it.writeString(v1) }

        assertEquals(listOf(transfer(7)), stored())
    }

    @Test
    fun anEntryThatDoesNotDecodeIsPassedOverAndTheRestRead() = runTest {
        SystemFileSystem.createDirectories(directory)
        val good = """{"account":"${alice.hex}","nonce":7,"raw":"0207ff","to":"${bob.hex}","amount":"1500000",""" +
            """"maxCost":"630000000000000","sentAt":1800000007}"""
        // Neither a transfer nor a Safe call; a field of the wrong type; an address out of range.
        val neither = """{"account":"${alice.hex}","nonce":8,"raw":"0208ff","maxCost":"1","sentAt":1800000008}"""
        val mistyped = """{"nonce":"x"}"""
        val badAddress = good.replace(alice.hex, "0x12")
        val text = """{"version":4,"transfers":[$neither,$good,$mistyped,$badAddress]}"""
        SystemFileSystem.sink(file).buffered().use { it.writeString(text) }

        assertEquals(listOf(transfer(7)), stored())
        // The file was set aside once, whole, with the entries passed over in it; what was read
        // took its place, so a later read sets nothing more aside and a write loses nothing.
        val aside = SystemFileSystem.list(directory).filter { ".damaged-" in it.name }
        assertEquals(1, aside.size)
        assertEquals(text, SystemFileSystem.source(aside.single()).buffered().use { it.readString() })
        assertEquals(listOf(transfer(7)), stored())
        record().record(transfer(9))
        assertEquals(listOf(transfer(7), transfer(9)), stored())
        assertEquals(1, SystemFileSystem.list(directory).count { ".damaged-" in it.name })
    }

    @Test
    fun aDamagedRecordIsSetAsideAndStartsEmpty() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":1,"transfers":{"nonce":"x"}}""") }

        assertEquals(emptyList(), stored())
    }
}

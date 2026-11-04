package eth.serotine.data.engine

import eth.serotine.data.engine.AddressActivity.Kind
import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.TokenBalanceReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class AddressActivityTest {

    private val a = Address.of("0x" + "aa".repeat(20))
    private val b = Address.of("0x" + "bb".repeat(20))

    /**
     * An engine follower whose report a test sets, recording what it is asked. Its `base` names
     * the block asked after, with [hashes]' hash for it (a test changes one to model a reorg).
     */
    internal class FakeActivity : ActivityChain {
        var report: String? = null

        /** A report for one `afterBlock` only, in place of [report] (two reads that differ). */
        val reportsAfter = mutableMapOf<Long, String>()
        var takes = true
        val pushes = mutableListOf<String>()
        val asked = mutableListOf<Long>()
        val hashes = mutableMapOf<Long, String>()

        /** Blocks the follower holds no hash for: their `base` hash is null. */
        val unheld = mutableSetOf<Long>()

        fun hashOf(block: Long) = hashes[block] ?: ("0x" + block.toString(16).padStart(64, '0'))

        override fun setActivityWatch(configJson: String): Boolean {
            pushes += configJson
            return takes
        }

        override fun addressActivity(afterBlock: Long): String? {
            asked += afterBlock
            val hash = if (afterBlock in unheld) "null" else "\"${hashOf(afterBlock)}\""
            return (reportsAfter[afterBlock] ?: report)?.replace(BASE, """{"block":$afterBlock,"hash":$hash}""")
        }

        companion object {
            const val BASE = "BASE"
        }
    }

    private fun report(
        to: Long,
        from: Long = to - 299,
        accountGaps: String = "[[$from,$to]]",
        logGaps: String = "[]",
        touched: String = "",
        watching: List<Address>,
    ) = """{"from":$from,"to":$to,"base":${FakeActivity.BASE},""" +
        """"gaps":{"account":$accountGaps,"log":$logGaps},"touched":[$touched],""" +
        """"watching":[${watching.joinToString(",") { "\"${it.hex}\"" }}]}"""

    private fun touch(address: Address, block: Long) =
        """{"address":"${address.hex}","block":$block,"blockHash":"0x${"11".repeat(32)}","via":["log"]}"""

    @Test
    fun anAddressNotWatchedYetIsWatchedAndNotVouchedFor() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = emptyList()) }
        val activity = AddressActivity(engine)

        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500))
        assertEquals(listOf("""{"watch":["${a.hex}"]}"""), engine.pushes)
    }

    @Test
    fun aCoveredUntouchedRangeVouchesForAProofThatNamesItsBlocksHash() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = listOf(a)) }
        val activity = AddressActivity(engine)

        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, null, 500), "a proof that names no hash")
        assertTrue(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500))
        assertTrue(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490).uppercase().replace("0X", "0x"), 500))
    }

    @Test
    fun aTouchAfterTheProofsBlockIsAChangeOneAtItIsInTheProof() {
        val engine = FakeActivity().apply {
            report = report(to = 500, touched = touch(a, 490) + "," + touch(b, 495), watching = listOf(a, b))
        }
        val activity = AddressActivity(engine)

        assertTrue(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500), "the proof already holds block 490's effect")
        assertFalse(activity.unchangedSince(b, Kind.LOG, 490, engine.hashOf(490), 500))
    }

    @Test
    fun aReorgThatReplacedTheProofsBlockIsAChangeThoughNothingTouchesTheAddress() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = listOf(a)) }
        val activity = AddressActivity(engine)
        val proven = engine.hashOf(490)
        assertTrue(activity.unchangedSince(a, Kind.LOG, 490, proven, 500))

        // Block 490 was replaced: the dropped one may have touched the address, and the report
        // lists only the canonical chain's touches. Its hash is what says so.
        engine.hashes[490] = "0x" + "ee".repeat(32)
        engine.report = report(to = 501, watching = listOf(a))
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, proven, 501))
    }

    @Test
    fun aProofFromASiblingOfTheCanonicalBlockIsNotVouchedFor() {
        // The proof ran against a block 490 the chain then left; the engine follows the one that
        // replaced it, and that one's hash is what its report names.
        val engine = FakeActivity().apply { report = report(to = 500, watching = listOf(a)) }
        val activity = AddressActivity(engine)
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, "0x" + "ee".repeat(32), 500))
    }

    @Test
    fun aProofBlockTheFollowerDoesNotHoldIsNotVouchedFor() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = listOf(a)) }
        val activity = AddressActivity(engine)
        engine.unheld += 490
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500))
    }

    @Test
    fun aReorgToALowerHeadIsReadAfreshNotAnsweredFromTheHigherHeadsReport() {
        // Chain X reached 501 with nothing touching the address: the proof at 490 stands.
        val engine = FakeActivity().apply { report = report(to = 501, watching = listOf(a)) }
        val activity = AddressActivity(engine)
        assertTrue(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 501))

        // A reorg above 490 to chain Y, whose head is 500 and whose block 500 names the address.
        engine.report = report(to = 500, touched = touch(a, 500), watching = listOf(a))
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500))
    }

    @Test
    fun aReorgThatKeepsTheHeadsNumberIsSeenAtTheNextPoll() {
        val clock = TestTimeSource()
        val engine = FakeActivity().apply { report = report(to = 500, watching = listOf(a)) }
        val activity = AddressActivity(engine, clock)
        assertTrue(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500))

        // Another block 500 replaces the first, and it names the address.
        engine.report = report(to = 500, touched = touch(a, 500), watching = listOf(a))
        clock += 4.seconds
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500))
    }

    @Test
    fun aVouchingReportThatNoLongerWatchesTheAddressIsNoVouching() {
        // The watch list's report still lists the address; the report after the proof's block,
        // read later (the address unwatched meanwhile, or the engine restarted), does not.
        val engine = FakeActivity().apply {
            report = report(to = 500, watching = listOf(a))
            reportsAfter[490] = report(to = 500, watching = emptyList())
        }
        assertFalse(AddressActivity(engine).unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500))
    }

    @Test
    fun severalFactsAreAnsweredFromOneReport() {
        val engine = FakeActivity().apply { report = report(to = 500, accountGaps = "[]", watching = listOf(a, b)) }
        val activity = AddressActivity(engine)
        val facts = listOf(a to Kind.LOG, b to Kind.ACCOUNT)
        assertTrue(activity.unchangedSince(facts, 490, engine.hashOf(490), 500))
        assertEquals(1, engine.asked.count { it == 490L }, "one read for both facts")

        // A touch of either fact's address is a change of the reading.
        engine.report = report(to = 501, accountGaps = "[]", touched = touch(b, 501), watching = listOf(a, b))
        assertFalse(activity.unchangedSince(facts, 490, engine.hashOf(490), 501))
    }

    @Test
    fun everyUnwatchedAddressOfAReadingIsWatchedAtOnce() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = emptyList()) }
        assertFalse(AddressActivity(engine).unchangedSince(listOf(a to Kind.LOG, b to Kind.ACCOUNT), 490, engine.hashOf(490), 500))
        assertEquals(listOf("""{"watch":["${a.hex}"]}""", """{"watch":["${b.hex}"]}"""), engine.pushes)
    }

    @Test
    fun anAddressNobodyAsksAboutIsUnwatchedAndARefusedPushWaits() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = emptyList()) }
        val activity = AddressActivity(engine)
        engine.takes = false
        activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500)
        activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 501)
        assertEquals(1, engine.pushes.size, "a refused push is not repeated at every poll")

        engine.takes = true
        engine.report = report(to = 600, watching = listOf(a, b))
        activity.unchangedSince(b, Kind.LOG, 590, engine.hashOf(590), 600)
        // a was last asked at head 501; at head 900 it has been idle for more than 300 heads.
        engine.report = report(to = 900, watching = listOf(a, b))
        activity.unchangedSince(b, Kind.LOG, 890, engine.hashOf(890), 900)
        assertTrue(engine.pushes.contains("""{"unwatch":["${a.hex}"]}"""), engine.pushes.toString())
    }

    @Test
    fun aReportWhoseNewestBlockIsAGapIsReadOnceMore() {
        val clock = TestTimeSource()
        val engine = FakeActivity().apply { report = report(to = 500, logGaps = "[[500,500]]", watching = listOf(a)) }
        val activity = AddressActivity(engine, clock)
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500))
        val reports = { engine.asked.count { it == 200L } }
        val checks = { engine.asked.count { it == 490L } } // the proof block's own report
        assertEquals(1, reports())
        assertEquals(1, checks())
        clock += 0.5.seconds // the same poll

        // The follower caught up meanwhile; within 2 s the cached report stands.
        engine.report = report(to = 500, watching = listOf(a))
        activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500)
        assertEquals(1, reports())
        clock += 3.seconds
        assertTrue(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500))
        assertEquals(2, reports())
        // Once more only, at one head; the proof block's own report at every poll.
        clock += 3.seconds
        activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500)
        assertEquals(2, reports())
        assertEquals(3, checks())
    }

    @Test
    fun accountCoverageIsKnownOnlyFromAReportThatHasSome() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = listOf(a)) }
        val activity = AddressActivity(engine)
        assertFalse(activity.hasAccountCoverage(500), "every block an account gap")
        engine.report = report(to = 501, accountGaps = "[]", watching = listOf(a))
        assertTrue(activity.hasAccountCoverage(501))
        assertFalse(AddressActivity(null).hasAccountCoverage(501), "no engine")
    }

    @Test
    fun aGapOfTheFactsKindAnywhereInTheRangeIsNoVouching() {
        val engine = FakeActivity().apply {
            report = report(to = 500, logGaps = "[[495,496]]", watching = listOf(a))
        }
        val activity = AddressActivity(engine)

        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500))
        assertTrue(activity.unchangedSince(a, Kind.LOG, 497, engine.hashOf(497), 500))
        // The account kind is a gap everywhere in this report.
        assertFalse(activity.unchangedSince(a, Kind.ACCOUNT, 497, engine.hashOf(497), 500))
    }

    @Test
    fun aProofOlderThanTheReportOrAHeadPastItIsNoVouching() {
        // Block 299 is below the follower's window: it holds no hash for it.
        val engine = FakeActivity().apply {
            report = report(to = 500, from = 300, watching = listOf(a))
            unheld += 299
        }
        val activity = AddressActivity(engine)

        assertFalse(activity.unchangedSince(a, Kind.LOG, 299, engine.hashOf(299), 500))
        // A head past the report asks for a new one; the same answer still stops at 500.
        assertFalse(activity.unchangedSince(a, Kind.LOG, 400, engine.hashOf(400), 501))
        assertFalse(activity.unchangedSince(a, Kind.LOG, -1, null, 500), "a block the engine did not name")
    }

    @Test
    fun oneReportServesEveryReadingAtItsHead() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = listOf(a, b)) }
        val activity = AddressActivity(engine)

        activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500)
        activity.unchangedSince(b, Kind.LOG, 490, engine.hashOf(490), 500)
        activity.unchangedSince(a, Kind.ACCOUNT, 490, engine.hashOf(490), 499)
        // Reports (after head - 300); the proof block's own hash is read apart, once per head.
        val reports = { engine.asked.filter { it != 490L } }
        assertEquals(listOf(200L), reports())

        engine.report = report(to = 501, watching = listOf(a, b))
        activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 501)
        assertEquals(listOf(200L, 201L), reports())
    }

    @Test
    fun anErrorOrNoAnswerIsNoVouching() {
        val engine = FakeActivity().apply { report = """{"error":"handle is paused"}""" }
        val activity = AddressActivity(engine)
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500))

        engine.report = null
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500))
        engine.report = "not json"
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500))
        assertFalse(AddressActivity(null).unchangedSince(a, Kind.LOG, 490, null, 500), "no engine")
    }

    @Test
    fun anEngineThatForgotTheListIsToldAgain() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = listOf(a)) }
        val activity = AddressActivity(engine)
        assertTrue(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 500))

        // A restarted engine: its next report lists nobody.
        engine.report = report(to = 501, watching = emptyList())
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, engine.hashOf(490), 501))
        assertEquals(listOf("""{"watch":["${a.hex}"]}"""), engine.pushes)
    }

    @Test
    fun aTokenBalanceVouchedForIsNotCalledAgainAndKeepsItsBlock() = runTest {
        val holder = a
        val usdc = Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238")
        val calldata = Abi.balanceOf(holder).toHex()
        val chain = FakeChain().apply { snapshot = running(head = 9) }
        val engine = FakeActivity().apply { report = report(to = 9, watching = listOf(holder)) }
        val balances = VerifiedTokenBalances(
            Network.SEPOLIA,
            chain,
            StandardTestDispatcher(testScheduler),
            4.seconds,
            activity = AddressActivity(engine),
            gated = setOf(usdc),
        )
        chain.calls[usdc to calldata] = CallOutcome.Returned("0".repeat(63) + "1", blockNumber = 9, blockHash = engine.hashOf(9))
        assertEquals(
            TokenBalanceReading.Verified(TokenUnits.of("1"), 9),
            balances.observeBalance(SEPOLIA, holder, usdc).first(),
        )

        // A new head, nothing touched the holder: the proof at block 9 stands, though a new call
        // would now answer 2.
        chain.snapshot = running(head = 10)
        chain.calls[usdc to calldata] = CallOutcome.Returned("0".repeat(63) + "2", blockNumber = 10, blockHash = engine.hashOf(10))
        engine.report = report(to = 10, watching = listOf(holder))
        assertEquals(
            TokenBalanceReading.Verified(TokenUnits.of("1"), 9),
            balances.observeBalance(SEPOLIA, holder, usdc).first(),
        )

        // A log at block 11 may name the holder: the balance is called again.
        chain.snapshot = running(head = 11)
        chain.calls[usdc to calldata] = CallOutcome.Returned("0".repeat(63) + "3", blockNumber = 11, blockHash = engine.hashOf(11))
        engine.report = report(to = 11, touched = touch(holder, 11), watching = listOf(holder))
        assertEquals(
            TokenBalanceReading.Verified(TokenUnits.of("3"), 11),
            balances.observeBalance(SEPOLIA, holder, usdc).first(),
        )
    }

    @Test
    fun aNativeBalanceStandsOnlyWhileItsProofsBlockIsCanonical() = runTest {
        val chain = FakeChain().apply { snapshot = running(head = 9) }
        val engine = FakeActivity().apply { report = report(to = 9, accountGaps = "[]", watching = listOf(a)) }
        val balances = VerifiedNativeBalances(
            Network.SEPOLIA,
            chain,
            StandardTestDispatcher(testScheduler),
            4.seconds,
            activity = AddressActivity(engine),
        )
        chain.proofs[a] = ProvenBalance("1", 9, engine.hashOf(9))
        assertEquals(BalanceReading.Verified(Wei.of("1"), 9), balances.observeBalance(SEPOLIA, a).first())

        // Head 10, nothing touched the account: the proof at block 9 stands.
        chain.snapshot = running(head = 10)
        chain.proofs[a] = ProvenBalance("2", 10, engine.hashOf(10))
        engine.report = report(to = 10, accountGaps = "[]", watching = listOf(a))
        assertEquals(BalanceReading.Verified(Wei.of("1"), 9), balances.observeBalance(SEPOLIA, a).first())

        // A reorg replaced block 9: proven again, though nothing touched the account.
        engine.hashes[9] = "0x" + "ee".repeat(32)
        chain.snapshot = running(head = 11)
        chain.proofs[a] = ProvenBalance("3", 11, engine.hashOf(11))
        engine.report = report(to = 11, accountGaps = "[]", watching = listOf(a))
        assertEquals(BalanceReading.Verified(Wei.of("3"), 11), balances.observeBalance(SEPOLIA, a).first())
    }

    @Test
    fun aTokenBalanceCalledAtASiblingIsCalledAgain() = runTest {
        val holder = a
        val usdc = Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238")
        val calldata = Abi.balanceOf(holder).toHex()
        val chain = FakeChain().apply { snapshot = running(head = 9) }
        val engine = FakeActivity().apply { report = report(to = 9, watching = listOf(holder)) }
        val balances = VerifiedTokenBalances(
            Network.SEPOLIA,
            chain,
            StandardTestDispatcher(testScheduler),
            4.seconds,
            activity = AddressActivity(engine),
            gated = setOf(usdc),
        )
        // The call ran at a block 9 the chain left; the engine now follows another block 9.
        val sibling = "0x" + "ee".repeat(32)
        chain.calls[usdc to calldata] = CallOutcome.Returned("0".repeat(63) + "1", blockNumber = 9, blockHash = sibling)
        assertEquals(
            TokenBalanceReading.Verified(TokenUnits.of("1"), 9),
            balances.observeBalance(SEPOLIA, holder, usdc).first(),
        )

        chain.snapshot = running(head = 10)
        chain.calls[usdc to calldata] = CallOutcome.Returned("0".repeat(63) + "2", blockNumber = 10, blockHash = engine.hashOf(10))
        engine.report = report(to = 10, watching = listOf(holder))
        assertEquals(
            TokenBalanceReading.Verified(TokenUnits.of("2"), 10),
            balances.observeBalance(SEPOLIA, holder, usdc).first(),
        )
    }

    @Test
    fun aTokenOutsideTheGatedSetIsCalledAtEveryHead() = runTest {
        val holder = a
        val custom = Address.of("0x" + "c0".repeat(20))
        val calldata = Abi.balanceOf(holder).toHex()
        val chain = FakeChain().apply { snapshot = running(head = 9) }
        val engine = FakeActivity().apply { report = report(to = 9, watching = listOf(holder)) }
        val balances = VerifiedTokenBalances(
            Network.SEPOLIA,
            chain,
            StandardTestDispatcher(testScheduler),
            4.seconds,
            activity = AddressActivity(engine),
            gated = emptySet(),
        )
        chain.calls[custom to calldata] = CallOutcome.Returned("0".repeat(63) + "1", blockNumber = 9, blockHash = engine.hashOf(9))
        balances.observeBalance(SEPOLIA, holder, custom).first()
        // A rebasing or interest-bearing token may change with no log naming the holder.
        chain.snapshot = running(head = 10)
        chain.calls[custom to calldata] = CallOutcome.Returned("0".repeat(63) + "2", blockNumber = 10, blockHash = engine.hashOf(10))
        engine.report = report(to = 10, watching = listOf(holder))
        assertEquals(
            TokenBalanceReading.Verified(TokenUnits.of("2"), 10),
            balances.observeBalance(SEPOLIA, holder, custom).first(),
        )
    }
}

/** The one network these readings are of. */
private val SEPOLIA = Network.SEPOLIA

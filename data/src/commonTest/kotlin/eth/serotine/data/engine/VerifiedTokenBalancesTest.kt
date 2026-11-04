package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenBalanceReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedTokenBalancesTest {

    private val holder = Address.of("0x" + "ab".repeat(20))
    private val usdc = Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238")
    private val chain = FakeChain().apply { snapshot = running(head = 9) }
    private val calldata = Abi.balanceOf(holder).toHex()

    private fun TestScope.balances() = VerifiedTokenBalances(
        Network.SEPOLIA,
        chain,
        StandardTestDispatcher(testScheduler),
        4.seconds,
    )

    @Test
    fun balanceOfIsTheSelectorAndThePaddedHolder() {
        assertEquals("70a08231" + "0".repeat(24) + "ab".repeat(20), calldata)
    }

    @Test
    fun aVerifiedWordIsTheBalanceLabelledWithItsBlock() = runTest {
        // 1,250 USDC: 1_250_000_000 units at six decimals.
        chain.calls[usdc to calldata] = CallOutcome.Returned("0".repeat(56) + "4a817c80", blockNumber = 9)

        assertEquals(
            TokenBalanceReading.Verified(TokenUnits.of("1250000000"), 9),
            balances().observeBalance(Network.SEPOLIA, holder, usdc).first(),
        )
    }

    @Test
    fun aBlockTheEngineDidNotNameIsNoBlock() = runTest {
        chain.calls[usdc to calldata] = CallOutcome.Returned("0".repeat(64), blockNumber = null)

        val reading = balances().observeBalance(Network.SEPOLIA, holder, usdc).first() as TokenBalanceReading.Verified
        assertEquals(TokenUnits.of("0"), reading.units)
        assertNull(reading.blockNumber)
    }

    @Test
    fun aRevertOrSomethingThatIsNoWordIsNotAToken() = runTest {
        chain.calls[usdc to calldata] = CallOutcome.Reverted(blockNumber = 9)
        assertEquals(TokenBalanceReading.NotAToken, balances().observeBalance(Network.SEPOLIA, holder, usdc).first())

        chain.calls[usdc to calldata] = CallOutcome.Returned("00", blockNumber = 9)
        assertEquals(TokenBalanceReading.NotAToken, balances().observeBalance(Network.SEPOLIA, holder, usdc).first())
    }

    @Test
    fun theLastBalanceIsLastKnownWhileTheHeadIsTooOld() = runTest {
        chain.snapshot = running(head = 9, headTimestamp = 1_792_100_000)
        chain.calls[usdc to calldata] = CallOutcome.Returned("0".repeat(56) + "4a817c80", blockNumber = 9)
        val balances = balances()
        balances.observeBalance(Network.SEPOLIA, holder, usdc).first()

        chain.snapshot = running(head = 9, headAgeMs = 60_000)
        assertEquals(
            TokenBalanceReading.LastKnown(TokenUnits.of("1250000000"), 9, asOf = 1_792_100_000),
            balances.observeBalance(Network.SEPOLIA, holder, usdc).first(),
        )
    }

    @Test
    fun anOlderNotATokenIsNotRepeatedWhileTheHeadIsTooOld() = runTest {
        chain.calls[usdc to calldata] = CallOutcome.Reverted(blockNumber = 9)
        val balances = balances()
        assertEquals(TokenBalanceReading.NotAToken, balances.observeBalance(Network.SEPOLIA, holder, usdc).first())

        chain.snapshot = running(head = 9, paused = true)
        assertEquals(TokenBalanceReading.Syncing, balances.observeBalance(Network.SEPOLIA, holder, usdc).first())
    }

    @Test
    fun noVerifiedAnswerIsUnproven() = runTest {
        assertEquals(
            TokenBalanceReading.Unavailable(ReadUnavailable.UNPROVEN),
            balances().observeBalance(Network.SEPOLIA, holder, usdc).first(),
        )
    }

    @Test
    fun wordsBecomeExactDecimals() {
        assertEquals("0", Abi.toDecimal(ByteArray(32)))
        assertEquals("255", Abi.toDecimal(byteArrayOf(-1)))
        // 2^256 - 1, the largest uint256.
        assertEquals(
            "115792089237316195423570985008687907853269984665640564039457584007913129639935",
            Abi.toDecimal(ByteArray(32) { -1 }),
        )
        assertNull(Abi.uint256("0x" + "0".repeat(62)), "no 0x, exactly one word")
        assertNull(Abi.uint256("0".repeat(128)), "two words are no amount")
    }

    @Test
    fun aRefusalIsSaidOnceAndNotAskedAgain() = runTest {
        chain.calls[usdc to calldata] = CallOutcome.Refused
        var asked = 0
        val counting = object : VerifiedChain by chain {
            override fun call(to: Address, data: ByteArray): CallOutcome? {
                asked++
                return chain.call(to, data)
            }
        }

        val readings = VerifiedTokenBalances(
            Network.SEPOLIA,
            counting,
            StandardTestDispatcher(testScheduler),
            4.seconds,
        )
            .observeBalance(Network.SEPOLIA, holder, usdc).toList()

        assertEquals(listOf<TokenBalanceReading>(TokenBalanceReading.Unavailable(ReadUnavailable.REFUSED)), readings)
        assertEquals(1, asked, "the flow ends: a permanent refusal is not asked again every tick")
    }

    @Test
    fun eachHolderAndTokenIsItsOwnReading() = runTest {
        val other = Address.of("0x" + "cd".repeat(20))
        val link = Address.of("0x779877a7b0d9e8603169ddbd7836e478b4624789")
        fun word(n: Int) = n.toString(16).padStart(64, '0')
        chain.calls[usdc to Abi.balanceOf(holder).toHex()] = CallOutcome.Returned(word(1), 9)
        chain.calls[usdc to Abi.balanceOf(other).toHex()] = CallOutcome.Returned(word(2), 9)
        chain.calls[link to Abi.balanceOf(holder).toHex()] = CallOutcome.Returned(word(3), 9)
        chain.calls[link to Abi.balanceOf(other).toHex()] = CallOutcome.Returned(word(4), 9)
        val balances = balances()

        val read = listOf(holder to usdc, other to usdc, holder to link, other to link).map { (who, token) ->
            (balances.observeBalance(Network.SEPOLIA, who, token).first() as TokenBalanceReading.Verified).units.decimal
        }
        assertEquals(listOf("1", "2", "3", "4"), read)
    }

    @Test
    fun aStateTheEngineCannotReportIsNotVerified() = runTest {
        chain.failure = IllegalStateException("the native library failed")

        assertEquals(
            TokenBalanceReading.Unavailable(ReadUnavailable.UNPROVEN),
            balances().observeBalance(Network.SEPOLIA, holder, usdc).first(),
        )
    }
}

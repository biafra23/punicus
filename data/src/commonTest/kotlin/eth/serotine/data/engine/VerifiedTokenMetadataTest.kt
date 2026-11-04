package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedTokenMetadataTest {

    private val token = Address.of("0x" + "08".repeat(20))
    private val chain = FakeChain().apply { snapshot = running(head = 9) }

    private fun TestScope.metadata() = VerifiedTokenMetadata(
        Network.SEPOLIA,
        chain,
        StandardTestDispatcher(testScheduler),
        4.seconds,
    )

    /** An ABI `string` answer: offset, length, the UTF-8 bytes padded to a word. */
    private fun string(text: String): String {
        val bytes = text.encodeToByteArray()
        val padded = bytes.copyOf((bytes.size + 31) / 32 * 32)
        return word(32) + word(bytes.size.toLong()) + padded.toHex()
    }

    private fun word(n: Long) = n.toString(16).padStart(64, '0')

    private fun answer(selector: ByteArray, outcome: CallOutcome) {
        chain.calls[token to selector.toHex()] = outcome
    }

    @Test
    fun aTokenDescribesItselfOnceAndTheAnswerIsKept() = runTest {
        answer(Abi.DECIMALS, CallOutcome.Returned(word(6), 9))
        answer(Abi.SYMBOL, CallOutcome.Returned(string("EURC"), 9))
        answer(Abi.NAME, CallOutcome.Returned(string("Euro Coin"), 9))
        val metadata = metadata()

        assertEquals(
            listOf<MetadataReading>(MetadataReading.Verified("EURC", "Euro Coin", 6, 9)),
            metadata.observe(Network.SEPOLIA, token).toList(),
        )
        chain.calls.clear() // asked again, the contract would not answer: the first answer is kept
        assertEquals(
            listOf<MetadataReading>(MetadataReading.Verified("EURC", "Euro Coin", 6, 9)),
            metadata.observe(Network.SEPOLIA, token).toList(),
        )
    }

    @Test
    fun anEarlyTokensBytes32SymbolAndAMissingNameAreRead() = runTest {
        answer(Abi.DECIMALS, CallOutcome.Returned(word(18), 9))
        answer(Abi.SYMBOL, CallOutcome.Returned("MKR".encodeToByteArray().copyOf(32).toHex(), 9))
        answer(Abi.NAME, CallOutcome.Reverted(9))

        assertEquals(
            listOf<MetadataReading>(MetadataReading.Verified("MKR", "MKR", 18, 9)),
            metadata().observe(Network.SEPOLIA, token).toList(),
        )
    }

    @Test
    fun whatIsNoTokensDescriptionIsNotAToken() = runTest {
        answer(Abi.DECIMALS, CallOutcome.Returned(word(78), 9)) // more decimals than an amount can show
        answer(Abi.SYMBOL, CallOutcome.Returned(string("BIG"), 9))
        answer(Abi.NAME, CallOutcome.Returned(string("Big"), 9))

        assertEquals(
            listOf<MetadataReading>(MetadataReading.NotAToken),
            metadata().observe(Network.SEPOLIA, token).toList(),
        )
    }

    @Test
    fun notATokenIsAskedAgainByTheNextSubscriber() = runTest {
        // Before the contract is deployed at the verified head: an empty answer.
        answer(Abi.DECIMALS, CallOutcome.Returned("", 9))
        answer(Abi.SYMBOL, CallOutcome.Returned("", 9))
        answer(Abi.NAME, CallOutcome.Returned("", 9))
        val metadata = metadata()
        assertEquals(
            listOf<MetadataReading>(MetadataReading.NotAToken),
            metadata.observe(Network.SEPOLIA, token).toList(),
        )

        chain.snapshot = running(head = 10)
        answer(Abi.DECIMALS, CallOutcome.Returned(word(6), 10))
        answer(Abi.SYMBOL, CallOutcome.Returned(string("EURC"), 10))
        answer(Abi.NAME, CallOutcome.Returned(string("Euro Coin"), 10))
        assertEquals(
            listOf<MetadataReading>(MetadataReading.Verified("EURC", "Euro Coin", 6, 10)),
            metadata.observe(Network.SEPOLIA, token).toList(),
        )
    }

    @Test
    fun aRefusalEndsTheReadingAndNoOtherCallIsMade() = runTest {
        answer(Abi.DECIMALS, CallOutcome.Refused)
        // symbol() and name() unanswered: unproven, were they asked.

        assertEquals(
            listOf<MetadataReading>(MetadataReading.Unavailable(ReadUnavailable.REFUSED)),
            metadata().observe(Network.SEPOLIA, token).toList(),
        )
    }

    @Test
    fun textIsAnAbiStringOrABytes32WordAndNothingElse() {
        assertEquals("EURC", Abi.text(string("EURC")))
        assertEquals("MKR", Abi.text("MKR".encodeToByteArray().copyOf(32).toHex()))
        assertEquals("A B", Abi.text(string("A\u0007\nB".replace("\n", " "))), "control characters are dropped")
        assertEquals("USDC", Abi.text(string("US\u200BDC")), "a zero-width space cannot hide in a symbol")
        assertEquals("abc", Abi.text(string("\u202Eabc\u2066\uFEFF\u2028")), "nor can a bidi override or a separator")
    }

    @Test
    fun whatRendersAsNothingIsDroppedWhateverItsCategory() {
        listOf(
            "US\u034FDC", // combining grapheme joiner (Mn)
            "USDC\uFE0F", // variation selector (Mn)
            "\u3164USDC\uFFA0", // Hangul fillers (Lo)
            "US\u2800DC", // blank Braille cell (So)
            "USDC\uDB40\uDC41", // U+E0041, a tag character beyond the BMP (Cf)
            "US\uD834\uDD73DC", // U+1D173, a musical format character beyond the BMP (Cf)
        ).forEach { assertEquals("USDC", Abi.visible(it), it) }
        assertEquals("\uD83D\uDE80 Moon", Abi.visible("\uD83D\uDE80 Moon"), "what is visible beyond the BMP stays")
        assertNull(Abi.text("00"), "neither a word nor a string")
        assertNull(Abi.text(word(32) + word(1_000_000) + "00".repeat(32)), "a length beyond the answer")
        assertNull(Abi.text(word(32) + word(2) + "ff" + "fe" + "00".repeat(30)), "not UTF-8")
    }
}

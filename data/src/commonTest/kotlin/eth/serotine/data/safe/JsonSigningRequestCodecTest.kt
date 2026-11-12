package eth.serotine.data.safe

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.SigningRequestDecoding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonSigningRequestCodecTest {

    private val codec = JsonSigningRequestCodec()
    private val safe = Address.of("0x" + "5a".repeat(20))
    private val bob = Address.of("0x" + "bb".repeat(20))
    private val usdc = Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238")
    private val ownerA = Address.of("0x" + "11".repeat(20))
    private val ownerB = Address.of("0x" + "ee".repeat(20))

    // Made-up signatures: well-formed, of no key anyone holds.
    private val signatureA = Signature("1".padStart(64, '0'), "2".padStart(64, '0'), recoveryId = 0)
    private val signatureB = Signature("3".padStart(64, '0'), "4".padStart(64, '0'), recoveryId = 1)

    private val transfer = SafeTx.call(bob, UInt256.of(100_000_000_000_000_000), ByteArray(0), nonce = 4)
    private val request = SigningRequest.of(
        Network.SEPOLIA,
        safe,
        transfer,
        listOf(SafeSignature.Ecdsa(ownerB, signatureB), SafeSignature.Ecdsa(ownerA, signatureA)),
    )

    private val text = "{\"v\":2,\"chainId\":11155111,\"safe\":\"0x${"5a".repeat(20)}\"," +
        "\"tx\":{\"to\":\"0x${"bb".repeat(20)}\",\"value\":\"100000000000000000\",\"data\":\"0x\",\"operation\":0," +
        "\"safeTxGas\":\"0\",\"baseGas\":\"0\",\"gasPrice\":\"0\",\"gasToken\":\"0x${"00".repeat(20)}\"," +
        "\"refundReceiver\":\"0x${"00".repeat(20)}\",\"nonce\":\"4\"}," +
        "\"signatures\":[{\"type\":\"eth.serotine.data.safe.WireSignatureEcdsa\",\"owner\":\"0x${"11".repeat(20)}\",\"signature\":\"0x${signatureA.r}${signatureA.s}1b\"}," +
        "{\"type\":\"eth.serotine.data.safe.WireSignatureEcdsa\",\"owner\":\"0x${"ee".repeat(20)}\",\"signature\":\"0x${signatureB.r}${signatureB.s}1c\"}]}"

    private fun decoded(text: String) = codec.decode(text)

    @Test
    fun aRequestIsOneLineOfJsonAndDecodesToItself() {
        assertEquals(text, codec.encode(request))
        assertEquals(SigningRequestDecoding.Decoded(request), decoded(text))
        assertEquals(SigningRequestDecoding.Decoded(request), decoded("\n  $text\n"), "whitespace around it")
    }

    @Test
    fun aTokenTransferWithOneSignatureFitsOneQrCodeEasily() {
        val data = Erc20.transferData(bob, UInt256.of(1_500_000))
        val typical = SigningRequest.of(
            Network.SEPOLIA,
            safe,
            SafeTx.call(usdc, UInt256.ZERO, data, nonce = 12),
            listOf(SafeSignature.Ecdsa(ownerA, signatureA)),
        )

        val encoded = codec.encode(typical)

        // 753 bytes of ASCII: a QR code of version 22 at error correction M (whose byte mode holds
        // 758 there and 2418 at version 40), and each further signature adds 202 — a 2-of-3
        // Safe's second signer receives 955, version 25.
        assertEquals(753, encoded.length)
        val twice = typical.withSignatures(listOf(SafeSignature.Ecdsa(ownerB, signatureB)))
        assertEquals(1006, codec.encode(twice).length)
        assertEquals(SigningRequestDecoding.Decoded(typical), decoded(encoded))
        assertTrue(encoded.all { it.code < 128 })
    }

    @Test
    fun aDelegateCallAndEveryRefundFieldTravelAsTheyAre() {
        val tx = SafeTx(
            to = usdc,
            value = UInt256.of(7),
            data = byteArrayOf(1, 2, 3),
            operation = SafeOperation.DELEGATECALL,
            safeTxGas = UInt256.of(50_000),
            baseGas = UInt256.of(100),
            gasPrice = UInt256.of(10),
            gasToken = usdc,
            refundReceiver = bob,
            nonce = Long.MAX_VALUE,
        )
        val unsigned = SigningRequest.of(Network.SEPOLIA, safe, tx)

        assertEquals(SigningRequestDecoding.Decoded(unsigned), decoded(codec.encode(unsigned)))
    }

    @Test
    fun anotherVersionOrChainIsSaidAsSuch() {
        // Version 1 is still supported for backward compatibility
        assertEquals(SigningRequestDecoding.UnknownVersion(1), decoded(text.replace("\"v\":2", "\"v\":1")))
        assertEquals(SigningRequestDecoding.UnknownVersion(3), decoded("{\"v\":3,\"anything\":true}"))
        assertEquals(
            // Chain 10, OP Mainnet: no network of this wallet's.
            SigningRequestDecoding.UnknownChain(10),
            decoded(text.replace("\"chainId\":11155111", "\"chainId\":10")),
        )
    }

    @Test
    fun anythingButExactlyARequestIsMalformed() {
        val malformed = listOf(
            "",
            "not json",
            "[]",
            "{}",
            "null",
            text.replace("\"v\":2", "\"v\":\"2\""),
            text.replace("\"v\":2", "\"v\":2.0"),
            // A field missing, unknown, null, or a second time.
            text.replace("\"nonce\":\"4\"", "\"nonce\":null"),
            text.replace(",\"nonce\":\"4\"", ""),
            text.replace("\"nonce\":\"4\"", "\"nonce\":\"4\",\"label\":\"Pay Bob\""),
            text.replace("\"nonce\":\"4\"", "\"nonce\":\"4\",\"nonce\":\"5\""),
            text.replace("{\"v\":2,", "{\"v\":2,\"v\":2,"),
            // Not in the one form: spaces, a checksummed or upper-case address, upper-case hex,
            // a number with a leading zero, a sign, a quoted operation.
            text.replace(",\"tx\":", ", \"tx\":"),
            text.replace("0x${"bb".repeat(20)}", "0x${"BB".repeat(20)}"),
            text.replace("0x${"bb".repeat(20)}", "0X${"bb".repeat(20)}"),
            text.replace("\"data\":\"0x\"", "\"data\":\"0xAB\""),
            text.replace("\"data\":\"0x\"", "\"data\":\"0xa\""),
            text.replace("\"data\":\"0x\"", "\"data\":\"\""),
            text.replace("\"nonce\":\"4\"", "\"nonce\":\"04\""),
            text.replace("\"nonce\":\"4\"", "\"nonce\":\"-4\""),
            text.replace("\"nonce\":\"4\"", "\"nonce\":4"),
            text.replace("\"operation\":0", "\"operation\":\"0\""),
            // Out of range: an operation, a nonce above 2^63 - 1, a value above 2^256 - 1.
            text.replace("\"operation\":0", "\"operation\":2"),
            text.replace("\"nonce\":\"4\"", "\"nonce\":\"9223372036854775808\""),
            text.replace("\"value\":\"100000000000000000\"", "\"value\":\"" + "9".repeat(78) + "\""),
            // Signatures: unsorted, two of one owner, an eth_sign v, an approval's v, a high s.
            text.replace("0x${"11".repeat(20)}", "0x${"ff".repeat(20)}"),
            text.replace("0x${"ee".repeat(20)}", "0x${"11".repeat(20)}"),
            text.replace("${signatureA.s}1b", "${signatureA.s}1f"),
            text.replace("${signatureA.s}1b", "${signatureA.s}01"),
            text.replace("${signatureA.s}1b", "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd03641401b"),
            // Too long to be one, and nested deeper than any request.
            text.replace("\"data\":\"0x\"", "\"data\":\"0x" + "00".repeat(JsonSigningRequestCodec.MAX_LENGTH) + "\""),
            "[".repeat(100_000),
            "{\"v\":2,\"chainId\":" + "[".repeat(50_000),
        )

        for (candidate in malformed) {
            assertEquals(SigningRequestDecoding.Malformed, decoded(candidate), candidate.take(200))
        }
    }

    @Test
    fun aRequestOfItsMaximumSizeDecodes() {
        // 64 KiB of call data: well beyond a QR code, within what text can carry.
        val big = SigningRequest.of(Network.SEPOLIA, safe, SafeTx.call(usdc, UInt256.ZERO, ByteArray(65_536), 0))

        assertEquals(SigningRequestDecoding.Decoded(big), decoded(codec.encode(big)))
    }
}

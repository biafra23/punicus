package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.KnownNftCollection
import eth.serotine.domain.model.KnownNftKind
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NftRef
import eth.serotine.domain.model.NftStandard
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.EnsNftReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [VerifiedEnsNfts] over a fake verified chain (issue #246). */
@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedEnsNftsTest {

    private val chain = FakeChain().apply { snapshot = running() }
    private val registry = Address.of("0x" + "e2".repeat(20))
    private val store = Address.of("0x" + "57".repeat(20))
    private val wrapper = Address.of("0x" + "a9".repeat(20))
    private val registrar = Address.of("0x" + "ba".repeat(20))
    private val registryCode = "6001"

    private fun TestScope.ens() = VerifiedEnsNfts(chain, StandardTestDispatcher(testScheduler), Network.SEPOLIA)

    private fun word(value: Long) = value.toString(16).padStart(64, '0')
    private fun word(address: Address) = "00".repeat(12) + address.hex.removePrefix("0x")

    /** An ABI `string` or `bytes`, alone: its offset, its length, its bytes padded to a word. */
    private fun abi(bytes: ByteArray): String {
        val hex = bytes.toHex()
        val padded = hex.padEnd((hex.length + 63) / 64 * 64, '0')
        return word(32) + word(bytes.size.toLong()) + padded
    }

    private fun wrapped() =
        KnownNftCollection(wrapper, NftStandard.ERC1155, "ENS (wrapped)", KnownNftKind.ENS_NAME_WRAPPER)

    private fun id(bytes: ByteArray) = UInt256.fromBigEndian(bytes)

    private fun v2(pin: String = codeHash(registryCode)) =
        KnownNftCollection(registry, NftStandard.ERC1155, "ENS", KnownNftKind.ENS_V2_REGISTRY, pin)

    /** An ENSv2 token id: [label]'s hash with its low 32 bits [version]. */
    private fun v2Id(label: String, version: Int) =
        VerifiedEnsNfts.keccak(label.encodeToByteArray()).also { it.fill(0, 28, 32); it[31] = version.toByte() }

    private fun setUpV2(label: String, version: Int) {
        chain.codes[registry] = ProvenCode(registryCode, 100)
        chain.calls[registry to "9dbba19d"] = CallOutcome.Returned(word(store), 100)
        val tokenId = v2Id(label, version)
        val stored = v2Id(label, 0)
        chain.calls[store to "b21bf7fa" + stored.toHex()] = CallOutcome.Returned(abi(label.encodeToByteArray()), 100)
        chain.calls[registry to "13c72608" + tokenId.toHex()] = CallOutcome.Returned(word(1_980_000_000), 100)
    }

    @Test
    fun namehashIsEnssOwn() {
        // ENS's published namehash("eth").
        assertEquals(
            "93cdeb708b7545dc668eb9280176169d1c33cfd8ed6f04690a0bcc88a93fc4ae",
            VerifiedEnsNfts.namehash(listOf("eth".encodeToByteArray())).toHex(),
        )
    }

    @Test
    fun anEnsV2TokenIsNamedFromItsLabelStoreWhateverItsVersion() = runTest {
        setUpV2("multisig", version = 3)
        val reading = ens().name(Network.SEPOLIA, v2(), NftRef(registry, id(v2Id("multisig", 3)), NftStandard.ERC1155))

        val unversioned = v2Id("multisig", 0)
        assertEquals(
            EnsNftReading.Named("multisig.eth", "0x" + unversioned.toHex(), 1_980_000_000, wrapped = false, 100),
            reading,
        )
    }

    @Test
    fun aLabelThatDoesNotHashToTheIdIsNotGiven() = runTest {
        setUpV2("multisig", version = 0)
        val tokenId = v2Id("multisig", 0)
        // The store answers another label for the same id.
        chain.calls[store to "b21bf7fa" + tokenId.toHex()] =
            CallOutcome.Returned(abi("mu1tisig".encodeToByteArray()), 100)
        val reading = ens().name(Network.SEPOLIA, v2(), NftRef(registry, id(tokenId), NftStandard.ERC1155))

        assertNull((reading as EnsNftReading.Named).name)
    }

    @Test
    fun aLabelWithAnInvisibleCharacterIsNotGiven() {
        assertNull(VerifiedEnsNfts.shownLabel("pay​pal".encodeToByteArray()))
        assertEquals("paypal", VerifiedEnsNfts.shownLabel("paypal".encodeToByteArray()))
    }

    @Test
    fun aRegistryWhoseCodeIsNotItsPinIsNotNamed() = runTest {
        setUpV2("multisig", version = 0)
        val tokenId = v2Id("multisig", 0)
        val token = NftRef(registry, id(tokenId), NftStandard.ERC1155)
        val reading = ens().name(Network.SEPOLIA, v2(pin = "00".repeat(32)), token)

        assertEquals(EnsNftReading.NotPinned, reading)
    }

    @Test
    fun aWrappedNameIsItsDnsEncodedNameWhenItsNamehashIsTheId() = runTest {
        val labels = listOf("pay".encodeToByteArray(), "alice".encodeToByteArray(), "eth".encodeToByteArray())
        val node = VerifiedEnsNfts.namehash(labels)
        val dns = labels.fold(ByteArray(0)) { acc, label -> acc + byteArrayOf(label.size.toByte()) + label } +
            byteArrayOf(0)
        chain.calls[wrapper to "20c38e2b" + node.toHex()] = CallOutcome.Returned(abi(dns), 120)
        chain.calls[wrapper to "0178fe3f" + node.toHex()] =
            CallOutcome.Returned(word(wrapper) + word(0) + word(1_990_000_000), 120)
        val collection = wrapped()
        val reading = ens().name(Network.SEPOLIA, collection, NftRef(wrapper, id(node), NftStandard.ERC1155))

        assertEquals(
            EnsNftReading.Named(
                "pay.alice.eth",
                "0x" + VerifiedEnsNfts.keccak(labels.first()).toHex(),
                1_990_000_000,
                wrapped = true,
                120,
            ),
            reading,
        )
    }

    @Test
    fun aWrappedSecondLevelNamesExpiryIsTheRegistrarsAndNoExpiryIsNone() = runTest {
        val labels = listOf("alice".encodeToByteArray(), "eth".encodeToByteArray())
        val node = VerifiedEnsNfts.namehash(labels)
        val dns = byteArrayOf(5) + labels[0] + byteArrayOf(3) + labels[1] + byteArrayOf(0)
        chain.calls[wrapper to "20c38e2b" + node.toHex()] = CallOutcome.Returned(abi(dns), 120)
        val collection = wrapped()
        val token = NftRef(wrapper, id(node), NftStandard.ERC1155)
        val grace = 90L * 24 * 60 * 60
        chain.calls[wrapper to "0178fe3f" + node.toHex()] =
            CallOutcome.Returned(word(wrapper) + word(0) + word(1_990_000_000 + grace), 120)
        assertEquals(1_990_000_000, (ens().name(Network.SEPOLIA, collection, token) as EnsNftReading.Named).expiry)

        chain.calls[wrapper to "0178fe3f" + node.toHex()] = CallOutcome.Returned(word(wrapper) + word(0) + word(0), 120)
        assertNull((ens().name(Network.SEPOLIA, collection, token) as EnsNftReading.Named).expiry)
    }

    @Test
    fun aRegistrarTokensLabelComesFromAReceiptLogThatHashesToIt() = runTest {
        val label = VerifiedEnsNfts.keccak("alice".encodeToByteArray())
        chain.calls[registrar to "d6e4fa86" + label.toHex()] = CallOutcome.Returned(word(1_970_000_000), 130)
        // NameRegistered(string label, bytes32 indexed labelhash, address indexed owner, uint256 baseCost, ...):
        // the string's offset, two more words, then the string.
        val data = "0x" + word(96) + word(5) + word(1_970_000_000) + word(5) +
            "alice".encodeToByteArray().toHex().padEnd(64, '0')
        val other = ReceiptLog(registrar, listOf("0x" + "11".repeat(32), "0x" + "22".repeat(32)), data)
        val controller = Address.of("0x" + "c0".repeat(20))
        val registered = ReceiptLog(controller, listOf("0x" + "33".repeat(32), "0x" + label.toHex()), data)
        val collection = KnownNftCollection(registrar, NftStandard.ERC721, "ENS", KnownNftKind.ENS_REGISTRAR)
        val token = NftRef(registrar, id(label), NftStandard.ERC721)
        val reading = ens().name(Network.SEPOLIA, collection, token, listOf(other, registered))

        assertEquals(
            EnsNftReading.Named("alice.eth", "0x" + label.toHex(), 1_970_000_000, wrapped = false, 130),
            reading,
        )
        val unnamed = ens().name(Network.SEPOLIA, collection, token, listOf(other))
        assertNull((unnamed as EnsNftReading.Named).name)
    }

    @Test
    fun anOffsetNearTheLargestIntIsOutOfShapeNotACrash() {
        // A log any contract in the transaction could emit: an offset that wraps past an added bounds check.
        val data = "0".repeat(56) + "7fffffe0" + "00".repeat(32)
        assertNull(VerifiedEnsNfts.abiBytes(data, allowTail = true))
        assertNull(VerifiedEnsNfts.abiBytes(word(32) + "0".repeat(56) + "7fffffe0", allowTail = true))
    }

    @Test
    fun anotherNetworkIsNeverAnsweredHere() = runTest {
        val reading = ens().name(Network.ETHEREUM, v2(), NftRef(registry, UInt256.ONE, NftStandard.ERC1155))
        assertEquals(EnsNftReading.Unavailable(ReadUnavailable.NO_ENGINE), reading)
    }
}

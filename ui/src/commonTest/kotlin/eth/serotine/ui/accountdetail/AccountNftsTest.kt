package eth.serotine.ui.accountdetail

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.KnownNftCollections
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NftRef
import eth.serotine.domain.model.NftStandard
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.usecase.HeldNft
import eth.serotine.domain.usecase.NftCollection
import eth.serotine.domain.usecase.NftHoldings
import kotlin.test.Test
import kotlin.test.assertEquals

/** [nftSection]: an account's NFTs as its screen draws them (docs/wallet-ux.md, *NFTs*). */
class AccountNftsTest {

    private val registrar = KnownNftCollections.of(Network.SEPOLIA).first()
    private val art = Address.of("0x" + "a7".repeat(20))

    private fun held(collection: NftCollection, id: UInt256, amount: Long = 1) = HeldNft(
        NftRef(collection.address, id, collection.known?.standard ?: NftStandard.ERC1155),
        UInt256.of(amount),
        collection,
        9_500,
    )

    @Test
    fun theBuiltInCollectionsAreShownAndTheUnlistedOnesSetApart() {
        val ens = NftCollection(registrar.address, registrar, null, null)
        val unlisted = NftCollection(art, null, "Art", "ART")
        val longId = UInt256.of(
            "98253155417735093419827374641934876012984101374925918283748577373519244107",
        )
        val section = nftSection(
            NftHoldings.Read(
                listOf(held(ens, longId), held(unlisted, UInt256.of(7), amount = 3)),
                start = 9_000,
                checked = 9_000L..9_600L,
                unproven = 1,
                notChecked = 0,
            ),
            unlistedOpen = false,
        )
        val registrarContract = "0x57f1887a8BF19b14fC0dF6Fd9B2acc9Af147eA85"
        assertEquals(listOf(NftRow("ENS", "#982531…4107", null, registrarContract, "block 9,500")), section.shown)
        assertEquals(listOf("Art"), section.unlisted.map { it.title })
        assertEquals("×3", section.unlisted.single().amount)
        assertEquals(false, section.unlistedOpen)
        assertEquals(null, section.empty)
        assertEquals("Received since block 9,000 · 1 not proven held yet", section.note)
        assertEquals(
            "Received since this wallet began following this account · 1 not proven held yet",
            section.simpleNote,
        )
    }

    @Test
    fun whileSomeAreStillToBeProvenItNeverSaysThereAreNone() {
        val section = nftSection(
            NftHoldings.Read(emptyList(), start = 9_000, checked = 9_000L..9_600L, unproven = 3, notChecked = 0),
            unlistedOpen = false,
        )
        assertEquals("Checking NFTs…", section.empty)
    }

    @Test
    fun whileItsNetworkIsOffTheyAreSaidToBeFromBefore() {
        val ens = NftCollection(registrar.address, registrar, null, null)
        val section = nftSection(
            NftHoldings.Read(listOf(held(ens, UInt256.of(1))), 9_000, 9_000L..9_600L, unproven = 0, notChecked = 0),
            unlistedOpen = false,
            off = "Sepolia",
        )
        assertEquals(
            "Sepolia is off: proven before, may have changed since · Received since block 9,000",
            section.note,
        )
    }

    @Test
    fun whatIsNotCoveredYetIsSaid() {
        val section = nftSection(
            NftHoldings.Read(emptyList(), start = 9_000, checked = 9_400L..9_600L, unproven = 0, notChecked = 4),
            unlistedOpen = false,
        )
        assertEquals("None found yet", section.empty)
        assertEquals(
            "Received since block 9,000 · still checking blocks 9,000 to 9,399 · 4 more not checked",
            section.note,
        )
        assertEquals(
            "Received since this wallet began following this account · still checking older blocks · " +
                "4 more not checked",
            section.simpleNote,
        )
    }

    @Test
    fun anUnnamedCollectionAndAShortIdReadPlainly() {
        assertEquals("#4021", tokenId(UInt256.of(4021)))
        assertEquals("#123456789012", tokenId(UInt256.of(123_456_789_012)))
        assertEquals("#123456…0123", tokenId(UInt256.of(1_234_567_890_123)))
        val section = nftSection(
            NftHoldings.Read(
                listOf(held(NftCollection(art, null, null, null), UInt256.of(1))),
                start = 9_000,
                checked = 9_000L..9_600L,
                unproven = 0,
                notChecked = 0,
            ),
            unlistedOpen = true,
        )
        assertEquals("Unnamed collection", section.unlisted.single().title)
        assertEquals(true, section.unlistedOpen)
    }

    @Test
    fun beforeAnythingIsReadTheSectionSaysWhy() {
        assertEquals("Checking NFTs…", nftSection(NftHoldings.Checking(9_000), unlistedOpen = false).empty)
        assertEquals("NFTs cannot be read right now.", nftSection(NftHoldings.Unavailable, false).empty)
    }
}

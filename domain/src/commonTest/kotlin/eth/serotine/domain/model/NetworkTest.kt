package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkTest {

    @Test
    fun theNetworksAreTheRuledOnesInTheOrderTheScreensListThem() {
        // The owner's ruling of 2026-10-22 (CLAUDE.md, *Data sources*): Ethereum and Gnosis Chain
        // beside Sepolia, as docs/networks.md's parts bring them. A network beyond these is a new
        // ruling first, not an edit to this test.
        assertEquals(listOf(Network.ETHEREUM, Network.GNOSIS, Network.SEPOLIA), Network.entries)
        assertEquals(1L, Network.ETHEREUM.chainId)
        assertEquals(100L, Network.GNOSIS.chainId)
        assertEquals(11_155_111L, Network.SEPOLIA.chainId)
        assertEquals(false, Network.ETHEREUM.testnet, "real funds")
        assertEquals(false, Network.GNOSIS.testnet, "real funds")
        assertEquals(false, Network.GNOSIS.ensNames, "no names on Gnosis Chain (docs/networks.md, *Names*)")
        assertEquals(true, Network.SEPOLIA.testnet)
        assertEquals(Network.ETHEREUM, Network.ofChainId(1))
    }

    @Test
    fun aSyncCommitteePeriodIsEightThousandOneHundredNinetyTwoSlots() {
        assertEquals(98_304L, Network.ETHEREUM.syncCommitteePeriodSeconds, "27.3 hours")
        assertEquals(98_304L, Network.SEPOLIA.syncCommitteePeriodSeconds)
        assertEquals(40_960L, Network.GNOSIS.syncCommitteePeriodSeconds, "11.4 hours")
    }
}

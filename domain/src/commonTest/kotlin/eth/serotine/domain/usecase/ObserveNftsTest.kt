package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Erc1155Transfers
import eth.serotine.domain.model.KnownNftCollections
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NftRef
import eth.serotine.domain.model.NftStandard
import eth.serotine.domain.model.TokenTransfers
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.IndexedLog
import eth.serotine.domain.repository.LogCoverage
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import eth.serotine.domain.repository.NftCollectionReading
import eth.serotine.domain.repository.NftContracts
import eth.serotine.domain.repository.NftHolding
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** [ObserveNfts] over a fake log index and fake NFT contracts (issue #246). */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveNftsTest {

    private val alice = Address.of("0x" + "11".repeat(20))
    private val bob = Address.of("0x" + "22".repeat(20))
    private val operator = Address.of("0x" + "0f".repeat(20))
    private val art = Address.of("0x" + "a7".repeat(20))
    private val wrapper = KnownNftCollections.of(Network.SEPOLIA).single { it.standard == NftStandard.ERC1155 }.address
    private val index = RecordingLogIndex()
    private val starts = MemoryHistoryStarts(alice to HistoryStart.Fixed(9_000))
    private val contracts = FakeNftContracts()

    private fun topic(address: Address) = "0x" + "00".repeat(12) + address.hex.removePrefix("0x")
    private fun word(value: Long) = value.toString(16).padStart(64, '0')

    private var logs = listOf<IndexedLog>()

    private fun log(emitter: Address, topics: List<String>, data: String, block: Long, logIndex: Int) = IndexedLog(
        ReceiptLog(emitter, topics, data),
        block,
        "0x" + "bb".repeat(32),
        "0x" + block.toString(16).padStart(64, '0'),
        logIndex,
    )

    private fun erc721(from: Address, to: Address, id: Long, block: Long) =
        log(art, listOf(TokenTransfers.TRANSFER, topic(from), topic(to), "0x" + word(id)), "0x", block, 0)

    private fun batchTo(to: Address, ids: List<Long>, block: Long) = log(
        wrapper,
        listOf(Erc1155Transfers.TRANSFER_BATCH, topic(operator), topic(Address.ZERO), topic(to)),
        "0x" + word(0x40) + word((3L + ids.size) * 32) + word(ids.size.toLong()) +
            ids.joinToString("") { word(it) } + word(ids.size.toLong()) + ids.joinToString("") { word(1) },
        block,
        1,
    )

    /** The index answers each query with the logs carrying alice at its position, of its events. */
    private fun covered(to: Long) {
        index.answer = { query ->
            LogsReading.Logs(
                logs.filter {
                    it.blockNumber in query.fromBlock..query.toBlock &&
                        it.log.topics.first() in query.topic0s &&
                        it.log.topics.getOrNull(query.position!!) == topic(alice)
                },
            )
        }
        index.coverage.value = LogCoverageReading.Read(mapOf(LogWatchKey.Mention(alice) to LogCoverage(9_000, 9_000L..to)))
    }

    private fun nft(collection: Address, id: Long, standard: NftStandard) = NftRef(collection, UInt256.of(id), standard)

    @Test
    fun whatItReceivedAndStillHoldsIsShownTheBuiltInCollectionsFirst() = runTest {
        logs = listOf(
            erc721(bob, alice, 6, 9_050),
            erc721(bob, alice, 5, 9_100),
            batchTo(alice, listOf(1, 2), 9_120),
            erc721(alice, bob, 6, 9_150),
        )
        contracts.held += listOf(nft(art, 5, NftStandard.ERC721), nft(wrapper, 1, NftStandard.ERC1155), nft(wrapper, 2, NftStandard.ERC1155))
        contracts.names[art] = NftCollectionReading.Read("Art", "ART", 9_200)
        covered(9_200)

        val seen = mutableListOf<NftHoldings>()
        backgroundScope.launch { ObserveNfts(index, starts, contracts)(alice.onSepolia).collect { seen += it } }
        runCurrent()

        val read = assertIs<NftHoldings.Read>(seen.last())
        assertEquals(
            listOf(nft(wrapper, 1, NftStandard.ERC1155), nft(wrapper, 2, NftStandard.ERC1155), nft(art, 5, NftStandard.ERC721)),
            read.nfts.map { it.nft },
        )
        assertEquals("ENS (wrapped)", read.nfts.first().collection.known?.name)
        assertEquals("Art", read.nfts.last().collection.name)
        assertEquals(0, read.unproven)
        assertEquals(0, read.notChecked)
        assertEquals(true, read.complete)
        // Token 6 was received and passed on: checked, and not held. Only the unlisted collection's name is read.
        assertEquals(4, contracts.holdingCalls)
        assertEquals(listOf(art), contracts.collectionCalls)
    }

    @Test
    fun anNftIsProvenAgainOnlyWhenALogNamesItAgain() = runTest {
        logs = listOf(erc721(bob, alice, 5, 9_100))
        contracts.held += nft(art, 5, NftStandard.ERC721)
        covered(9_200)
        val seen = mutableListOf<NftHoldings>()
        backgroundScope.launch { ObserveNfts(index, starts, contracts)(alice.onSepolia).collect { seen += it } }
        runCurrent()
        assertEquals(1, contracts.holdingCalls)

        covered(9_300) // the head moved, no log names it
        runCurrent()
        assertEquals(1, contracts.holdingCalls)

        logs = logs + erc721(alice, bob, 5, 9_350)
        contracts.held -= nft(art, 5, NftStandard.ERC721)
        covered(9_400)
        runCurrent()
        assertEquals(2, contracts.holdingCalls)
        assertEquals(emptyList(), assertIs<NftHoldings.Read>(seen.last()).nfts)
    }

    @Test
    fun aProofWithNoAnswerIsCountedAndAskedAgain() = runTest {
        logs = listOf(erc721(bob, alice, 5, 9_100))
        contracts.unavailable = true
        covered(9_200)
        val seen = mutableListOf<NftHoldings>()
        backgroundScope.launch { ObserveNfts(index, starts, contracts)(alice.onSepolia).collect { seen += it } }
        runCurrent()
        assertEquals(1, assertIs<NftHoldings.Read>(seen.last()).unproven)

        contracts.unavailable = false
        contracts.held += nft(art, 5, NftStandard.ERC721)
        covered(9_300)
        runCurrent()
        val read = assertIs<NftHoldings.Read>(seen.last())
        assertEquals(0, read.unproven)
        assertEquals(listOf(nft(art, 5, NftStandard.ERC721)), read.nfts.map { it.nft })
    }
}

/** [NftContracts] answering from [held] and [names], counting what it is asked. */
private class FakeNftContracts : NftContracts {
    val held = mutableSetOf<NftRef>()
    val names = mutableMapOf<Address, NftCollectionReading.Read>()
    var unavailable = false
    var holdingCalls = 0
    val collectionCalls = mutableListOf<Address>()

    override suspend fun holding(network: Network, holder: Address, nft: NftRef): NftHolding {
        holdingCalls++
        return when {
            unavailable -> NftHolding.Unavailable(ReadUnavailable.UNPROVEN)
            nft in held -> NftHolding.Held(UInt256.ONE, 9_200)
            else -> NftHolding.NotHeld(9_200)
        }
    }

    override suspend fun collection(network: Network, contract: Address): NftCollectionReading {
        collectionCalls += contract
        return names[contract] ?: NftCollectionReading.Read(null, null, 9_200)
    }
}

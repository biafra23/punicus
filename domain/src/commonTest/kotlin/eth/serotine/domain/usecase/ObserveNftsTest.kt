package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Erc1155Transfers
import eth.serotine.domain.model.KnownNftCollection
import eth.serotine.domain.model.KnownNftCollections
import eth.serotine.domain.model.KnownNftKind
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NftRef
import eth.serotine.domain.model.NftStandard
import eth.serotine.domain.model.TokenTransfers
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.EnsNftReading
import eth.serotine.domain.repository.EnsNfts
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
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.TransactionProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.repository.AddedNftList
import eth.serotine.domain.repository.AddedNfts
import eth.serotine.domain.repository.TokenChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** [ObserveNfts] over a fake log index and fake NFT contracts (issue #246). */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveNftsTest {

    private val alice = Address.of("0x" + "11".repeat(20))
    private val bob = Address.of("0x" + "22".repeat(20))
    private val operator = Address.of("0x" + "0f".repeat(20))
    private val art = Address.of("0x" + "a7".repeat(20))
    private val wrapper = known(KnownNftKind.ENS_NAME_WRAPPER)
    private val ens = FakeEnsNfts()
    private val receipts = FakeReceipts()
    private val added = MemoryAddedNfts()
    private val index = RecordingLogIndex()
    private val starts = MemoryHistoryStarts(alice to HistoryStart.Fixed(9_000))
    private val contracts = FakeNftContracts()

    private fun known(kind: KnownNftKind) = KnownNftCollections.of(Network.SEPOLIA).single { it.kind == kind }.address

    private fun TestScope.collectInto(seen: MutableList<NftHoldings>) = backgroundScope.launch {
        ObserveNfts(index, starts, contracts, ens, receipts, added)(alice.onSepolia).collect { seen += it }
    }

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
        val entry = LogWatchKey.Mention(alice) to LogCoverage(9_000, 9_000L..to)
        index.coverage.value = LogCoverageReading.Read(mapOf(entry))
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
        contracts.held += listOf(
            nft(art, 5, NftStandard.ERC721),
            nft(wrapper, 1, NftStandard.ERC1155),
            nft(wrapper, 2, NftStandard.ERC1155),
        )
        contracts.names[art] = NftCollectionReading.Read("Art", "ART", 9_200)
        covered(9_200)

        val seen = mutableListOf<NftHoldings>()
        collectInto(seen)
        runCurrent()

        val read = assertIs<NftHoldings.Read>(seen.last())
        assertEquals(
            listOf(
                nft(wrapper, 1, NftStandard.ERC1155),
                nft(wrapper, 2, NftStandard.ERC1155),
                nft(art, 5, NftStandard.ERC721),
            ),
            read.nfts.map { it.nft },
        )
        assertEquals("ENS (wrapped)", read.nfts.first().collection.known?.name)
        assertEquals("Art", read.nfts.last().collection.name)
        assertEquals(0, read.unproven)
        assertEquals(0, read.notChecked)
        assertEquals(true, read.complete)
        // Token 6 was received and passed on: its last move took it away, so it is not checked. Only the
        // unlisted collection's name is read.
        assertEquals(3, contracts.holdingCalls)
        assertEquals(listOf(art), contracts.collectionCalls)
    }

    @Test
    fun anNftIsProvenAgainOnlyWhenALogNamesItAgain() = runTest {
        logs = listOf(erc721(bob, alice, 5, 9_100))
        contracts.held += nft(art, 5, NftStandard.ERC721)
        covered(9_200)
        val seen = mutableListOf<NftHoldings>()
        collectInto(seen)
        runCurrent()
        assertEquals(1, contracts.holdingCalls)

        covered(9_300) // the head moved, no log names it
        runCurrent()
        assertEquals(1, contracts.holdingCalls)

        covered(9_499) // just short of an hour of Sepolia's slots since the proof at 9,200
        runCurrent()
        assertEquals(1, contracts.holdingCalls)

        covered(9_500) // an hour on: proven again, a log or none (an ENS name expires with none)
        runCurrent()
        assertEquals(2, contracts.holdingCalls)

        logs = logs + erc721(alice, bob, 5, 9_550)
        contracts.held -= nft(art, 5, NftStandard.ERC721)
        covered(9_600)
        runCurrent()
        assertEquals(2, contracts.holdingCalls, "an ERC-721 whose last move took it away is not checked")
        assertEquals(emptyList(), assertIs<NftHoldings.Read>(seen.last()).nfts)
    }

    @Test
    fun theBuiltInCollectionsAreCheckedBeforeAFloodOfAirdrops() = runTest {
        val spam = Address.of("0x" + "5b".repeat(20))
        logs = listOf(batchTo(alice, listOf(1), 9_010)) +
            (1..ObserveNfts.MAX_CHECKED + 5).map { id ->
                val topics = listOf(TokenTransfers.TRANSFER, topic(bob), topic(alice), "0x" + word(id.toLong()))
                log(spam, topics, "0x", 9_100L + id, 0)
            }
        contracts.held += nft(wrapper, 1, NftStandard.ERC1155)
        covered(9_300)
        val seen = mutableListOf<NftHoldings>()
        collectInto(seen)
        runCurrent()

        val read = assertIs<NftHoldings.Read>(seen.last())
        assertEquals(listOf(nft(wrapper, 1, NftStandard.ERC1155)), read.nfts.map { it.nft })
        assertEquals(6, read.notChecked)
        assertEquals(ObserveNfts.MAX_CHECKED, contracts.holdingCalls)
    }

    @Test
    fun anEnsTokenIsNamedAndARegistrarsLabelComesFromTheReceiptThatBroughtIt() = runTest {
        val registrar = known(KnownNftKind.ENS_REGISTRAR)
        val minted = listOf(TokenTransfers.TRANSFER, topic(Address.ZERO), topic(alice), "0x" + word(77))
        val mint = log(registrar, minted, "0x", 9_100, 0)
        logs = listOf(mint, batchTo(alice, listOf(1), 9_120))
        val name = nft(registrar, 77, NftStandard.ERC721)
        val wrapped = nft(wrapper, 1, NftStandard.ERC1155)
        contracts.held += listOf(name, wrapped)
        receipts.logs[mint.transactionHash] = listOf(mint.log)
        ens.needsLogs += name
        ens.names[name] = EnsNftReading.Named("alice.eth", "0x" + word(77), 1_900_000_000, wrapped = false, 9_200)
        ens.names[wrapped] = EnsNftReading.Named("pay.alice.eth", "0x" + word(1), 1_900_000_000, wrapped = true, 9_200)
        covered(9_200)
        val seen = mutableListOf<NftHoldings>()
        collectInto(seen)
        runCurrent()

        val read = assertIs<NftHoldings.Read>(seen.last())
        assertEquals(setOf("alice.eth", "pay.alice.eth"), read.nfts.mapNotNull { it.ens?.name }.toSet())
        assertEquals(1, ens.asked.single { it.first == name }.second, "the registration's receipt logs were given")
        assertEquals(0, ens.asked.single { it.first == wrapped }.second, "a wrapped name needs no receipt")

        covered(9_300)
        runCurrent()
        assertEquals(2, ens.asked.size, "a name is read once")
    }

    @Test
    fun aPinnedCollectionNotYetProvenKeepsItsPlaceBeforeAFloodOfAirdrops() = runTest {
        val v2 = known(KnownNftKind.ENS_V2_REGISTRY)
        val token = nft(v2, 5, NftStandard.ERC1155)
        val topics = listOf(Erc1155Transfers.TRANSFER_SINGLE, topic(operator), topic(Address.ZERO), topic(alice))
        val spam = Address.of("0x" + "5b".repeat(20))
        logs = listOf(log(v2, topics, "0x" + word(5) + word(1), 9_010, 0)) +
            (1..ObserveNfts.MAX_CHECKED + 5).map { id ->
                val transfer = listOf(TokenTransfers.TRANSFER, topic(bob), topic(alice), "0x" + word(id.toLong()))
                log(spam, transfer, "0x", 9_100L + id, 0)
            }
        contracts.held += token
        ens.names[token] = EnsNftReading.Named("multisig.eth", "0x" + word(5), null, wrapped = false, 9_200)
        covered(9_300)
        val seen = mutableListOf<NftHoldings>()
        collectInto(seen)
        runCurrent()

        val read = assertIs<NftHoldings.Read>(seen.last())
        assertEquals("multisig.eth", read.nfts.single().ens?.name)
        assertEquals(v2, read.nfts.single().collection.known?.address)
    }

    @Test
    fun aPinnedCollectionWhoseCodeIsNotItsPinIsUnlisted() = runTest {
        val v2 = known(KnownNftKind.ENS_V2_REGISTRY)
        val token = nft(v2, 5, NftStandard.ERC1155)
        logs = listOf(
            log(
                v2,
                listOf(Erc1155Transfers.TRANSFER_SINGLE, topic(operator), topic(Address.ZERO), topic(alice)),
                "0x" + word(5) + word(1),
                9_100,
                0,
            ),
        )
        contracts.held += token
        ens.names[token] = EnsNftReading.NotPinned
        covered(9_200)
        val seen = mutableListOf<NftHoldings>()
        collectInto(seen)
        runCurrent()

        val held = assertIs<NftHoldings.Read>(seen.last()).nfts.single()
        assertEquals(null, held.collection.known)
        assertEquals(null, held.ens)
    }

    @Test
    fun anAddedNftIsCheckedThoughNoLogBroughtItAndShownAsAdded() = runTest {
        val old = nft(art, 9, NftStandard.ERC721)
        logs = emptyList()
        added.added.value = listOf(alice.onSepolia to old)
        contracts.held += old
        covered(9_200)
        val seen = mutableListOf<NftHoldings>()
        collectInto(seen)
        runCurrent()

        val held = assertIs<NftHoldings.Read>(seen.last()).nfts.single()
        assertEquals(old, held.nft)
        assertEquals(true, held.added)

        contracts.held -= old
        covered(9_600) // an hour on: proven again, and gone
        runCurrent()
        assertEquals(emptyList(), assertIs<NftHoldings.Read>(seen.last()).nfts)
    }

    @Test
    fun aProofAgainWithNoAnswerKeepsTheLastOneAnswered() = runTest {
        logs = listOf(erc721(bob, alice, 5, 9_100))
        contracts.held += nft(art, 5, NftStandard.ERC721)
        covered(9_200)
        val seen = mutableListOf<NftHoldings>()
        collectInto(seen)
        runCurrent()
        assertEquals(1, assertIs<NftHoldings.Read>(seen.last()).nfts.size)

        contracts.unavailable = true
        covered(9_600) // an hour on: proven again, and no answer
        runCurrent()
        assertEquals(2, contracts.holdingCalls)
        val kept = assertIs<NftHoldings.Read>(seen.last())
        assertEquals(listOf(nft(art, 5, NftStandard.ERC721)), kept.nfts.map { it.nft })
        assertEquals(9_200, kept.nfts.single().blockNumber, "dated by the block it was proven at")

        covered(9_601) // asked again at every reading until one answers
        runCurrent()
        assertEquals(3, contracts.holdingCalls)
    }

    @Test
    fun aProofWithNoAnswerIsCountedAndAskedAgain() = runTest {
        logs = listOf(erc721(bob, alice, 5, 9_100))
        contracts.unavailable = true
        covered(9_200)
        val seen = mutableListOf<NftHoldings>()
        collectInto(seen)
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

/** [EnsNfts] answering from [names], each given only with the receipt logs it needs ([needsLogs]). */
private class FakeEnsNfts : EnsNfts {
    val names = mutableMapOf<NftRef, EnsNftReading>()
    val needsLogs = mutableSetOf<NftRef>()
    val asked = mutableListOf<Pair<NftRef, Int>>()

    override suspend fun name(
        network: Network,
        collection: KnownNftCollection,
        nft: NftRef,
        receiptLogs: List<ReceiptLog>,
    ): EnsNftReading {
        asked += nft to receiptLogs.size
        if (nft in needsLogs && receiptLogs.isEmpty()) {
            return EnsNftReading.Named(null, "0x" + "00".repeat(32), null, false, 9_200)
        }
        return names[nft] ?: EnsNftReading.Unavailable(ReadUnavailable.UNPROVEN)
    }
}

/** [ReceiptTracker] with [logs] per transaction hash, each included at block 9,100; any other never included. */
private class FakeReceipts : ReceiptTracker {
    val logs = mutableMapOf<String, List<ReceiptLog>>()

    override fun observe(network: Network, hash: String): Flow<TransactionProgress> =
        flowOf(
            logs[hash]?.let { TransactionProgress.Included(9_100, true, it) }
                ?: TransactionProgress.Unavailable(ReadUnavailable.UNPROVEN),
        )
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

/** [AddedNfts] in memory. */
private class MemoryAddedNfts : AddedNfts {
    val added = MutableStateFlow<List<Pair<AccountId, NftRef>>>(emptyList())

    override fun observe(account: AccountId): Flow<AddedNftList> =
        added.map { all -> AddedNftList.Loaded(all.filter { it.first == account }.map { it.second }) }

    override suspend fun add(account: AccountId, nft: NftRef): TokenChange {
        if (account to nft in added.value) return TokenChange.AlreadyPresent
        added.value += account to nft
        return TokenChange.Changed
    }

    override suspend fun remove(account: AccountId, nft: NftRef): TokenChange {
        if (account to nft !in added.value) return TokenChange.NotFound
        added.value -= account to nft
        return TokenChange.Changed
    }
}

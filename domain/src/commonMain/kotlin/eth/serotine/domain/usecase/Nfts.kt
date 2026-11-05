package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Erc1155Transfers
import eth.serotine.domain.model.KnownNftCollection
import eth.serotine.domain.model.KnownNftCollections
import eth.serotine.domain.model.NftRef
import eth.serotine.domain.model.NftStandard
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TokenTransfers
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.HistoryStarts
import eth.serotine.domain.repository.IndexedLog
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.NftCollectionReading
import eth.serotine.domain.repository.NftContracts
import eth.serotine.domain.repository.NftHolding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

/**
 * An NFT an account holds (issue #246): [amount] of [nft] (one for ERC-721), proven at [blockNumber]
 * (null: the engine did not say), of [collection].
 */
data class HeldNft(
    val nft: NftRef,
    val amount: UInt256,
    val collection: NftCollection,
    val blockNumber: Long?,
)

/**
 * The collection at [address]: [known] when it is built into the wallet (shown openly), else an
 * unlisted one, which anyone can have send an NFT to any address. [name] and [symbol] are what the
 * contract says of itself, each null while unread or when it says nothing as text.
 */
data class NftCollection(
    val address: Address,
    val known: KnownNftCollection?,
    val name: String?,
    val symbol: String?,
)

/** An account's NFTs (issue #246), or why there are none to show. */
sealed interface NftHoldings {

    /** The account's history has no start fixed yet: once the light client is synced, it has. */
    data object NotFollowed : NftHoldings

    /** It is followed from block [start], and the index has none of that span to read yet. */
    data class Checking(val start: Long) : NftHoldings

    /** Nothing can be read right now (the engine asleep or not running, the starts unreadable). */
    data object Unavailable : NftHoldings

    /**
     * The [nfts] proven held at the head: the built-in collections' first, then the most recently
     * received first. They were found among the NFTs the account received in blocks [checked], and it
     * is followed from block [start]: an NFT received before [start] is not found here (owner ruling,
     * 2026-11-05, #246). [unproven] were received and not proven held or not yet; [notChecked] were
     * received and not checked at all, past the [ObserveNfts.MAX_CHECKED] most recent.
     */
    data class Read(
        val nfts: List<HeldNft>,
        val start: Long,
        val checked: LongRange,
        val unproven: Int,
        val notChecked: Int,
    ) : NftHoldings {
        /** Whether every block from its start on is checked. */
        val complete: Boolean
            get() = checked.first <= start
    }
}

/**
 * An account's NFTs (issue #246): found among the NFTs its history in the engine's log index shows
 * it received (an ERC-721 `Transfer` to it at topic 2, an ERC-1155 `TransferSingle` or
 * `TransferBatch` to it at topic 3), each then proven held at the head ([NftContracts.holding]).
 * A log alone never makes an NFT held: one received and passed on, or burned, drops out once its
 * proof says so. The logs that name the account as the sender (topic 1, or 2 for ERC-1155) are read
 * too, so that a move away is proven again.
 *
 * An NFT is proven again only when a log naming it and the account appeared since its last proof,
 * or its last proof had no answer: holding it changes only through such a log. At most
 * [MAX_CHECKED] NFTs are checked, the most recently moved first, so that a flood of airdrops cannot
 * flood the light client with calls. Each collection's name is read once, while this runs.
 */
class ObserveNfts(
    private val index: LogIndex,
    private val starts: HistoryStarts,
    private val contracts: NftContracts,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(account: AccountId): Flow<NftHoldings> = flow {
        val address = account.address
        val key = LogWatchKey.Mention(address)
        val reader = IndexedReader(
            account.network,
            index,
            starts,
            key,
            queries = { range ->
                listOf(1, 2).map { position ->
                    LogQuery(key, range.first, range.last, listOf(TokenTransfers.TRANSFER), position)
                } + listOf(2, 3).map { position ->
                    LogQuery(
                        key,
                        range.first,
                        range.last,
                        listOf(Erc1155Transfers.TRANSFER_SINGLE, Erc1155Transfers.TRANSFER_BATCH),
                        position,
                    )
                }
            },
            decode = { log -> moveOf(address, log) },
        )
        val proofs = mutableMapOf<NftRef, Proof>()
        val names = mutableMapOf<Address, NftCollectionReading.Read>()
        emitAll(
            reader.observe().transformLatest { history ->
                when (history) {
                    IndexedHistory.NotFollowed -> emit(NftHoldings.NotFollowed)
                    is IndexedHistory.Checking -> emit(NftHoldings.Checking(history.start))
                    IndexedHistory.Unavailable -> emit(NftHoldings.Unavailable)
                    is IndexedHistory.Read -> {
                        val moves = history.events
                        // Each NFT the account received, with the last move naming it and the account:
                        // the events are in block order, so the last one written stays.
                        val last = mutableMapOf<NftRef, NftMove>()
                        for (move in moves) move.nfts.forEach { last[it] = move }
                        val received = moves.flatMap { it.received }.toSet()
                        val ordered = received.sortedWith(
                            compareByDescending<NftRef> { last.getValue(it).blockNumber }
                                .thenByDescending { last.getValue(it).logIndex },
                        )
                        val checking = ordered.take(MAX_CHECKED)
                        fun shown() = holdings(account, history, checking, ordered.size, proofs, names)
                        emit(shown())
                        for (nft in checking) {
                            val move = last.getValue(nft).let { it.transactionHash to it.logIndex }
                            val before = proofs[nft]
                            if (before != null && before.move == move && before.holding !is NftHolding.Unavailable) {
                                continue
                            }
                            proofs[nft] = Proof(move, contracts.holding(account.network, address, nft))
                        }
                        val held = checking.filter { proofs[it]?.holding is NftHolding.Held }.map { it.collection }
                        for (collection in held.distinct()) {
                            if (collection in names || KnownNftCollections.find(account.network, collection) != null) {
                                continue
                            }
                            val reading = contracts.collection(account.network, collection)
                            if (reading is NftCollectionReading.Read) names[collection] = reading
                        }
                        emit(shown())
                    }
                }
            }.distinctUntilChanged(),
        )
    }

    /** What [proofs] and [names] show of [checking], in [history]'s span, with [received] NFTs found. */
    private fun holdings(
        account: AccountId,
        history: IndexedHistory.Read<NftMove>,
        checking: List<NftRef>,
        received: Int,
        proofs: Map<NftRef, Proof>,
        names: Map<Address, NftCollectionReading.Read>,
    ): NftHoldings.Read {
        val held = checking.mapNotNull { nft ->
            val holding = proofs[nft]?.holding as? NftHolding.Held ?: return@mapNotNull null
            val known = KnownNftCollections.find(account.network, nft.collection)
            val said = names[nft.collection]
            val collection = NftCollection(nft.collection, known, said?.name, said?.symbol)
            HeldNft(nft, holding.amount, collection, holding.blockNumber)
        }
        // Stable: the built-in collections' first, each part in the order checked, the newest first.
        val nfts = held.filter { it.collection.known != null } + held.filter { it.collection.known == null }
        val unproven = checking.count { nft ->
            proofs[nft]?.holding.let { it == null || it is NftHolding.Unavailable }
        }
        return NftHoldings.Read(nfts, history.start, history.checked, unproven, received - checking.size)
    }

    /** The move a log records when it moves an NFT to or from [address], else null. */
    private fun moveOf(address: Address, log: IndexedLog): NftMove? {
        val contract = log.log.address
        val erc721 = (TokenTransfers.decode(contract, log.log.topics, log.log.data) as? TokenTransfer.Nft)
            ?.takeIf { it.from == address || it.to == address }
        // A batch is one log, and so one event, naming every id it moved.
        val erc1155 = Erc1155Transfers.decode(contract, log.log.topics, log.log.data)
            ?.filter { it.from == address || it.to == address }
            ?.takeIf { it.isNotEmpty() }
        val (nfts, received) = when {
            erc721 != null -> NftRef(contract, erc721.tokenId, NftStandard.ERC721).let { nft ->
                listOf(nft) to listOfNotNull(nft.takeIf { erc721.to == address })
            }
            erc1155 != null -> erc1155.map { NftRef(contract, it.id, NftStandard.ERC1155) }.distinct() to
                erc1155.filter { it.to == address }.map { NftRef(contract, it.id, NftStandard.ERC1155) }.distinct()
            else -> return null
        }
        return NftMove(nfts, received, log.blockNumber, log.transactionHash, log.logIndex, log.blockTimestamp)
    }

    /** The last move [move] (its transaction and log) an NFT's [holding] was proven after. */
    private data class Proof(val move: Pair<String, Int>, val holding: NftHolding)

    companion object {
        /** The most NFTs proven per account: the most recently moved; the rest are counted, not checked. */
        const val MAX_CHECKED: Int = 100
    }
}

/**
 * One log's move of the [nfts] it names to or from the account, logged in block [blockNumber]: those
 * in [received] reached it, the others left it.
 */
data class NftMove(
    val nfts: List<NftRef>,
    val received: List<NftRef>,
    override val blockNumber: Long,
    override val transactionHash: String,
    override val logIndex: Int,
    override val blockTimestamp: Long? = null,
) : IndexedEvent

package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.EnsNameTokens
import eth.serotine.domain.model.Erc1155Transfers
import eth.serotine.domain.model.KnownNftCollection
import eth.serotine.domain.model.KnownNftCollections
import eth.serotine.domain.model.KnownNftKind
import eth.serotine.domain.model.NftRef
import eth.serotine.domain.model.NftStandard
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TokenTransfers
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AddedNftList
import eth.serotine.domain.repository.AddedNfts
import eth.serotine.domain.repository.EnsNftReading
import eth.serotine.domain.repository.EnsNfts
import eth.serotine.domain.repository.HistoryStarts
import eth.serotine.domain.repository.IndexedLog
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.NftCollectionReading
import eth.serotine.domain.repository.NftContracts
import eth.serotine.domain.repository.NftHolding
import eth.serotine.domain.repository.ReceiptLog
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SeenEnsNameList
import eth.serotine.domain.repository.SeenEnsNames
import eth.serotine.domain.repository.TransactionProgress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An NFT an account holds (issue #246): [amount] of [nft] (one for ERC-721), proven at [blockNumber]
 * (null: the engine did not say), of [collection]; for a token of a built-in ENS collection, the name it
 * stands for ([ens]; null until read, and for any other NFT).
 */
data class HeldNft(
    val nft: NftRef,
    val amount: UInt256,
    val collection: NftCollection,
    val blockNumber: Long?,
    val ens: EnsNftReading.Named? = null,
    /** Whether the user added it by contract and id ([AddedNfts]), which they may take off again. */
    val added: Boolean = false,
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

/** How the user's list of added NFTs ([AddedNfts]) was read: in full, set aside as damaged, or not at all. */
enum class AddedListState { READ, SET_ASIDE, UNREADABLE }

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
     * moved first (received or sent, as [ObserveNfts] checks them). They were found among the NFTs
     * the account received in blocks [checked], and it
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
        /** The user's added NFTs as stored: their list read, set aside as damaged, or unreadable. */
        val addedList: AddedListState = AddedListState.READ,
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
 * too: an ERC-721 whose last move took it away is not checked, and an ERC-1155 that left is proven
 * again.
 *
 * An NFT is proven again when a log naming it and the account appeared since its last proof, when
 * its last proof had no answer or was made at a block before its last move, and in any case once
 * it is [REPROVE_AFTER_SECONDS] old: holding can change with no log (an ENS name expiring). A proof
 * again that gets no answer keeps the last one answered, so an NFT proven held stays listed, dated
 * by its block, and is asked about again at every reading. At most
 * [MAX_CHECKED] NFTs are checked, the built-in collections' first, then the most recently moved, so
 * that a flood of airdrops can neither flood the light client with calls nor push out the user's
 * ENS names. Each unlisted collection's name is read once, right after its first NFT is proven
 * held, while this runs, and each ENS token's name once too ([EnsNfts]; owner rulings, 2026-11-05 and
 * 2026-11-06): a .eth registrar token's label is looked for in the verified receipt of the transaction
 * that last brought it to the account. A pinned collection whose code is not its pin
 * ([EnsNftReading.NotPinned]) is shown as unlisted.
 *
 * The ENS names the wallet has seen point to the account ([SeenEnsNames]; issue #274) are candidates
 * too: the tokens each can be ([EnsNameTokens.candidates]) are checked as an added NFT is, among the
 * built-in collections' and before those the log index found, so a name received before the
 * account's history starts is found with no step of the user's. A candidate not held is not shown. A
 * .eth registrar token whose label no receipt gave takes it from a seen name whose hash is its id
 * ([EnsNameTokens.registrarLabel]).
 */
class ObserveNfts(
    private val index: LogIndex,
    private val starts: HistoryStarts,
    private val contracts: NftContracts,
    /** What a built-in ENS collection's token stands for: its name, expiry, wrapped or not. */
    private val ens: EnsNfts,
    /** The verified receipts a .eth registrar token's label is looked for in. */
    private val receipts: ReceiptTracker,
    /** The NFTs the user added by contract and id: checked as the ones found are, after the built-in ones. */
    private val added: AddedNfts,
    /** The ENS names seen pointing to the account: the tokens they can be are checked as added ones are. */
    private val seen: SeenEnsNames,
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
        val collectionNames = mutableMapOf<Address, NftCollectionReading.Read>()
        val ensNames = mutableMapOf<NftRef, EnsNftReading.Named>()
        val pins = Pins()
        /** The built-in collection at [collection], unless it is pinned and its code not proven to be its pin. */
        fun builtIn(collection: Address) = pins.builtIn(KnownNftCollections.find(account.network, collection))
        val addedNfts = added.observe(account)
            .map { list ->
                when (list) {
                    is AddedNftList.Loaded ->
                        list.nfts to if (list.setAside) AddedListState.SET_ASIDE else AddedListState.READ
                    AddedNftList.Unreadable -> emptyList<NftRef>() to AddedListState.UNREADABLE
                }
            }
            .onStart { emit(emptyList<NftRef>() to AddedListState.READ) }
            .distinctUntilChanged()
        // A list that cannot be read only finds no candidates: the NFTs found otherwise still show.
        val seenNames = seen.observe(account)
            .map { list -> (list as? SeenEnsNameList.Loaded)?.names.orEmpty().map { it.name } }
            .onStart { emit(emptyList()) }
            .distinctUntilChanged()
        emitAll(
            combine(reader.observe(), addedNfts, seenNames, ::Triple).transformLatest { (history, stored, names) ->
                val (addedRefs, addedState) = stored
                val ownAdded = addedRefs.toSet()
                // The tokens the names seen can be, checked as the user's added ones are, but never theirs to take off.
                val candidates = names.flatMap { EnsNameTokens.candidates(account.network, it) }.toSet()
                val addedList = ownAdded + candidates
                /** The label a seen name gives the registrar token [nft], checked by hashing; else null. */
                fun labelOf(nft: NftRef): String? =
                    EnsNameTokens.registrarLabel(nft.tokenId, names).takeIf {
                        KnownNftCollections.find(account.network, nft.collection)?.kind == KnownNftKind.ENS_REGISTRAR
                    }
                when (history) {
                    IndexedHistory.NotFollowed -> emit(NftHoldings.NotFollowed)
                    is IndexedHistory.Checking -> emit(NftHoldings.Checking(history.start))
                    IndexedHistory.Unavailable -> emit(NftHoldings.Unavailable)
                    is IndexedHistory.Read -> {
                        val moves = history.events
                        // Each NFT the account received, with the last move naming it and the account,
                        // and the last that brought it here: the events are in block order, so the last
                        // one written stays.
                        val last = mutableMapOf<NftRef, NftMove>()
                        val lastIn = mutableMapOf<NftRef, NftMove>()
                        for (move in moves) {
                            move.nfts.forEach { last[it] = move }
                            move.received.forEach { lastIn[it] = move }
                        }
                        // An added NFT not moved since is checked as if it had just been received, by a move
                        // of its own that the hourly proof keys on.
                        for (nft in addedList) {
                            if (nft !in last) last[nft] = NftMove(listOf(nft), listOf(nft), history.start, ADDED, -1)
                        }
                        // An ERC-721 whose last move took it away is gone: only a log naming the account
                        // brings it back. An ERC-1155 may have left in part, and is checked. An added one
                        // is checked whatever its last move: the user asked for it.
                        val received = (moves.flatMap { it.received } + addedList).toSet().filterNot { nft ->
                            nft.standard == NftStandard.ERC721 && nft !in last.getValue(nft).received &&
                                nft !in addedList
                        }
                        // The built-in collections' first, so that no flood of airdrops pushes them out
                        // of the NFTs checked; within each part, the most recently moved first. A pinned
                        // collection keeps its place until its code is proven not to be its pin: its proof
                        // is made only among the NFTs checked.
                        val ordered = received.sortedWith(
                            compareBy<NftRef> {
                                KnownNftCollections.find(account.network, it.collection)
                                    .let { known -> known == null || pins.mismatched(known) }
                            }
                                // The user's own next, before what anyone could have sent.
                                .thenBy { it !in addedList }
                                .thenByDescending { last.getValue(it).blockNumber }
                                .thenByDescending { last.getValue(it).logIndex },
                        )
                        val checking = ordered.take(MAX_CHECKED)
                        val head = history.checked.last
                        val reproveAfter = REPROVE_AFTER_SECONDS / account.network.secondsPerSlot
                        // Marked added only where no log brought it: one the history finds is not the user's
                        // to take off.
                        val ownOnly = ownAdded - moves.flatMap { it.received }.toSet()
                        fun shown() =
                            holdings(
                                history,
                                checking,
                                ordered.size,
                                proofs,
                                collectionNames,
                                ensNames,
                                ::builtIn,
                                ownOnly,
                                ::labelOf,
                            ).copy(addedList = addedState)
                        emit(shown())
                        for (nft in checking) {
                            val move = last.getValue(nft)
                            if (proofs[nft]?.standsFor(move, head, reproveAfter) == true) continue
                            val holding = contracts.holding(account.network, address, nft)
                            // A proof that gets no answer keeps the last one answered, and its name: the row stays,
                            // dated by the block it was proven at, as the network-off path keeps it. That proof no
                            // longer stands, so it is asked again at every reading until one answers.
                            val answered = proofs[nft]?.holding.let { it is NftHolding.Held || it is NftHolding.NotHeld }
                            if (holding is NftHolding.Unavailable && answered) continue
                            proofs[nft] = Proof(move.transactionHash to move.logIndex, head, holding)
                            // Proven again: its name is read again too (a renewal, a wrap or an unwrap).
                            ensNames.remove(nft)
                            val collection = nft.collection
                            val unnamed = collection !in collectionNames && builtIn(collection) == null
                            if (holding is NftHolding.Held && unnamed) {
                                val reading = contracts.collection(account.network, collection)
                                if (reading is NftCollectionReading.Read) collectionNames[collection] = reading
                            }
                        }
                        // What is proven shows now; the names, which may wait for a receipt, after.
                        emit(shown())
                        for (nft in checking) {
                            if (proofs[nft]?.holding !is NftHolding.Held || nft in ensNames) continue
                            val known = KnownNftCollections.find(account.network, nft.collection) ?: continue
                            if (pins.mismatched(known)) continue
                            val brought = lastIn[nft].takeIf { known.kind == KnownNftKind.ENS_REGISTRAR }
                            // A receipt not had is asked for again at the next reading, never taken for none.
                            val logs = if (brought == null) {
                                emptyList()
                            } else {
                                receiptLogs(account, brought.transactionHash) ?: continue
                            }
                            when (val reading = ens.name(account.network, known, nft, logs)) {
                                is EnsNftReading.Named -> {
                                    ensNames[nft] = reading
                                    pins.proven(known)
                                }
                                EnsNftReading.NotPinned -> pins.mismatch(known)
                                is EnsNftReading.Unavailable -> Unit // asked again at the next reading
                            }
                            emit(shown())
                        }
                    }
                }
            }.distinctUntilChanged(),
        )
    }

    /**
     * The verified logs of the transaction [hash] on the account's network, once a verified block
     * includes it (none when its receipt carried none that could be read); null when it cannot be had
     * within [RECEIPT_WAIT].
     */
    private suspend fun receiptLogs(account: AccountId, hash: String): List<ReceiptLog>? =
        withTimeoutOrNull(RECEIPT_WAIT) {
            val progress = receipts.observe(account.network, hash).first { it !is TransactionProgress.Pending }
            when (progress) {
                is TransactionProgress.Included -> progress.logs.orEmpty()
                is TransactionProgress.Finalized -> progress.logs.orEmpty()
                else -> null
            }
        }

    /**
     * What is known of the pinned collections' code ([KnownNftCollection.codeHash]): one is built in only
     * once a reading of it proved its code is its pin, and never once one proved it is not.
     */
    private class Pins {
        private val proven = mutableSetOf<Address>()
        private val mismatched = mutableSetOf<Address>()

        fun proven(collection: KnownNftCollection) {
            proven += collection.address
        }

        fun mismatch(collection: KnownNftCollection) {
            mismatched += collection.address
        }

        fun mismatched(collection: KnownNftCollection): Boolean = collection.address in mismatched

        fun builtIn(collection: KnownNftCollection?): KnownNftCollection? = collection?.takeIf {
            it.address !in mismatched && (it.codeHash == null || it.address in proven)
        }
    }

    /**
     * What [proofs], [names] and [ensNames] show of [checking], in [history]'s span, with [received] NFTs
     * found; a collection is built in as [builtIn] says, a registrar token's missing label as [labelOf].
     */
    private fun holdings(
        history: IndexedHistory.Read<NftMove>,
        checking: List<NftRef>,
        received: Int,
        proofs: Map<NftRef, Proof>,
        names: Map<Address, NftCollectionReading.Read>,
        ensNames: Map<NftRef, EnsNftReading.Named>,
        builtIn: (Address) -> KnownNftCollection?,
        addedList: Set<NftRef>,
        labelOf: (NftRef) -> String?,
    ): NftHoldings.Read {
        val held = checking.mapNotNull { nft ->
            val holding = proofs[nft]?.holding as? NftHolding.Held ?: return@mapNotNull null
            val known = builtIn(nft.collection)
            val said = names[nft.collection]
            val collection = NftCollection(nft.collection, known, said?.name, said?.symbol)
            // A registrar token's label no receipt gave is taken from a name seen, checked by hashing.
            val ens = ensNames[nft].takeIf { known != null }
                ?.let { named ->
                    if (named.name != null) named else labelOf(nft)?.let { named.copy(name = "$it.eth") } ?: named
                }
            HeldNft(nft, holding.amount, collection, holding.blockNumber, ens, added = nft in addedList)
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

    /**
     * An NFT's [holding], proven after its last move [move] (its transaction and log), while the index
     * covered up to block [checkedTo]: the proof's age where the engine did not name its block.
     */
    private data class Proof(val move: Pair<String, Int>, val checkedTo: Long, val holding: NftHolding) {

        /**
         * Whether this proof still stands for [last], the NFT's last move, at [head]: proven after that
         * very move, at or after its block, fewer than [reproveAfter] blocks ago, and answered.
         */
        fun standsFor(last: NftMove, head: Long, reproveAfter: Long): Boolean {
            val block = when (holding) {
                is NftHolding.Held -> holding.blockNumber
                is NftHolding.NotHeld -> holding.blockNumber
                is NftHolding.Unavailable -> return false
            } ?: checkedTo
            return move == (last.transactionHash to last.logIndex) && block >= last.blockNumber &&
                head - block < reproveAfter
        }
    }

    companion object {
        /** The most NFTs proven per account: the built-in collections' first, then the most recently moved. */
        const val MAX_CHECKED: Int = 100

        /**
         * How long a proof stands with no log naming its NFT: an hour. Holding can change with no log at
         * all, as an ENS name does when it expires and when it is renewed after its grace period, and a
         * call can revert for a while (a paused contract); a log out of shape, such as a batch too long
         * to read, is missed. So each NFT is proven again within the hour regardless.
         */
        const val REPROVE_AFTER_SECONDS: Long = 3_600

        /** The transaction an added NFT's own move names: none, as no log brought it. */
        private const val ADDED = "added"

        /**
         * How long a .eth registrar token's receipt is waited for at one reading: less than a slot, so a
         * new head does not cancel every wait; one not had is asked for again at the next reading.
         */
        val RECEIPT_WAIT: Duration = 10.seconds
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

package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TokenTransfers
import eth.serotine.domain.repository.HistoryStarts
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatchKey
import kotlinx.coroutines.flow.Flow

/**
 * Every ERC-20 and ERC-721 transfer to or from an account (issue #85), whichever token moved,
 * listed in the wallet or not: the `Transfer` logs that name it as the sender (topic 1) or the
 * receiver (topic 2), as the engine's log index holds them for its [LogWatchKey.Mention] entry,
 * read as [IndexedReader] reads an entry. A transfer to itself is one event.
 */
class ObserveTokenTransfers(private val index: LogIndex, private val starts: HistoryStarts) {

    operator fun invoke(account: AccountId): Flow<IndexedHistory<TokenTransferEvent>> {
        val address = account.address
        val key = LogWatchKey.Mention(address)
        return IndexedReader(
            account.network,
            index,
            starts,
            key,
            queries = { range ->
                listOf(1, 2).map { position ->
                    LogQuery(key, range.first, range.last, listOf(TokenTransfers.TRANSFER), position)
                }
            },
            decode = { log ->
                TokenTransfers.decode(log.log.address, log.log.topics, log.log.data)
                    ?.takeIf { it.from == address || it.to == address }
                    ?.let { TokenTransferEvent(it, log.blockNumber, log.transactionHash, log.logIndex) }
            },
        ).observe()
    }
}

/** A token's [transfer], in block [blockNumber], logged by transaction [transactionHash] at [logIndex] in the block. */
data class TokenTransferEvent(
    val transfer: TokenTransfer,
    override val blockNumber: Long,
    override val transactionHash: String,
    override val logIndex: Int,
) : IndexedEvent

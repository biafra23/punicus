package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.ApprovalReading
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.CowChain
import eth.serotine.domain.repository.CowVenueReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatch
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.PastReading
import eth.serotine.domain.repository.PriceFeeds
import eth.serotine.domain.repository.PriceHistory
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SafeApprovals
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TokenMetadata
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.VenueReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The chain ports of an engine that hosts one light client per network (docs/networks.md, *The
 * engine host*), each call answered by the adapter of the network it names, from [byNetwork]:
 * one [ChainPorts] per network the host serves. A network the host does not serve is refused
 * ([ReadUnavailable.NO_ENGINE], or the port's own way of saying so), never answered from another
 * network (CLAUDE.md, *Trust*).
 */
class NetworkRoutedPorts(private val byNetwork: Map<Network, ChainPorts>) {

    private fun of(network: Network): ChainPorts? = byNetwork[network]

    val nativeBalances: NativeBalances = object : NativeBalances {
        override fun observeBalance(network: Network, address: Address): Flow<BalanceReading> =
            of(network)?.nativeBalances?.observeBalance(network, address)
                ?: flowOf(BalanceReading.Unavailable(ReadUnavailable.NO_ENGINE))
    }

    val tokenBalances: TokenBalances = object : TokenBalances {
        override fun observeBalance(network: Network, holder: Address, token: Address): Flow<TokenBalanceReading> =
            of(network)?.tokenBalances?.observeBalance(network, holder, token)
                ?: flowOf(TokenBalanceReading.Unavailable(ReadUnavailable.NO_ENGINE))
    }

    val tokenMetadata: TokenMetadata = object : TokenMetadata {
        override fun observe(network: Network, token: Address): Flow<MetadataReading> =
            of(network)?.tokenMetadata?.observe(network, token)
                ?: flowOf(MetadataReading.Unavailable(ReadUnavailable.NO_ENGINE))
    }

    val addressKinds: AddressKinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address): Flow<KindReading> =
            of(network)?.addressKinds?.observeKind(network, address)
                ?: flowOf(KindReading.Unavailable(ReadUnavailable.NO_ENGINE))
    }

    val lightClient: LightClient = object : LightClient {
        override fun observeStatus(network: Network): Flow<LightClientStatus> =
            of(network)?.lightClient?.observeStatus(network) ?: flowOf(LightClientStatus.Unavailable)

        // A device property: every light client here dates finalized blocks, or none is offered.
        override val datesFinalizedBlocks: Boolean =
            byNetwork.isNotEmpty() && byNetwork.values.all { it.lightClient.datesFinalizedBlocks }
    }

    val networkUpgrades: NetworkUpgrades = object : NetworkUpgrades {
        override fun observeUpgrade(network: Network): Flow<NetworkUpgrade?> =
            of(network)?.networkUpgrades?.observeUpgrade(network) ?: flowOf(null)
    }

    val priceFeeds: PriceFeeds = object : PriceFeeds {
        override fun quotes(
            network: Network,
        ): Map<PriceId, PriceId> = of(network)?.priceFeeds?.quotes(network).orEmpty()

        override fun observe(network: Network, priceId: PriceId): Flow<FeedReading> =
            of(network)?.priceFeeds?.observe(network, priceId)
                ?: flowOf(FeedReading.Unavailable(ReadUnavailable.NO_ENGINE))
    }

    val priceHistory: PriceHistory = object : PriceHistory {
        override suspend fun before(network: Network, priceId: PriceId, secondsBefore: Long): PastReading =
            of(network)?.priceHistory?.before(network, priceId, secondsBefore)
                ?: PastReading.Unavailable(ReadUnavailable.NO_ENGINE)
    }

    val transactionInputs: TransactionInputs = object : TransactionInputs {
        override suspend fun nonce(network: Network, account: Address): NonceReading =
            of(
                network,
            )?.transactionInputs?.nonce(network, account) ?: NonceReading.Unavailable(ReadUnavailable.NO_ENGINE)

        override suspend fun minedNonce(network: Network, account: Address): NonceReading =
            of(network)?.transactionInputs?.minedNonce(network, account)
                ?: NonceReading.Unavailable(ReadUnavailable.NO_ENGINE)

        override suspend fun fees(network: Network): FeeReading =
            of(network)?.transactionInputs?.fees(network) ?: FeeReading.Unavailable(ReadUnavailable.NO_ENGINE)

        override suspend fun estimateGas(
            network: Network,
            from: Address,
            to: Address,
            value: UInt256,
            data: ByteArray,
        ): GasEstimate = of(network)?.transactionInputs?.estimateGas(network, from, to, value, data)
            ?: GasEstimate.Unavailable(ReadUnavailable.NO_ENGINE)

        override suspend fun simulate(
            network: Network,
            from: Address,
            to: Address,
            value: UInt256,
            data: ByteArray,
        ): Simulation = of(network)?.transactionInputs?.simulate(network, from, to, value, data)
            ?: Simulation.Unavailable(ReadUnavailable.NO_ENGINE)
    }

    val broadcaster: Broadcaster = object : Broadcaster {
        override suspend fun send(network: Network, raw: ByteArray): Broadcast =
            of(network)?.broadcaster?.send(network, raw)
                ?: Broadcast.Unavailable("no light client for ${network.displayName} on this device")
    }

    val receipts: ReceiptTracker = object : ReceiptTracker {
        override fun observe(network: Network, hash: String): Flow<TransactionProgress> =
            of(network)?.receipts?.observe(network, hash)
                ?: flowOf(TransactionProgress.Unavailable(ReadUnavailable.NO_ENGINE))
    }

    val names: NameResolver = object : NameResolver {
        override suspend fun resolve(network: Network, name: String): NameResolution =
            of(network)?.names?.resolve(network, name) ?: NameResolution.Unavailable(ReadUnavailable.NO_ENGINE)
    }

    val safeApprovals: SafeApprovals = object : SafeApprovals {
        override suspend fun approval(network: Network, safe: Address, owner: Address, hash: Digest): ApprovalReading =
            of(network)?.safeApprovals?.approval(network, safe, owner, hash)
                ?: ApprovalReading.Unavailable(ReadUnavailable.NO_ENGINE)
    }

    val uniswap: UniswapV3Chain = object : UniswapV3Chain {
        override suspend fun venue(network: Network): VenueReading =
            of(network)?.uniswap?.venue(network) ?: VenueReading.Unavailable(ReadUnavailable.NO_ENGINE)

        override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation =
            of(network)?.uniswap?.call(network, to, data) ?: Simulation.Unavailable(ReadUnavailable.NO_ENGINE)
    }

    val cow: CowChain = object : CowChain {
        override suspend fun venue(network: Network): CowVenueReading =
            of(network)?.cow?.venue(network) ?: CowVenueReading.Unavailable(ReadUnavailable.NO_ENGINE)

        override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation =
            of(network)?.cow?.call(network, to, data) ?: Simulation.Unavailable(ReadUnavailable.NO_ENGINE)
    }

    val logIndex: LogIndex = object : LogIndex {
        override suspend fun configure(network: Network, watch: List<LogWatch>, unwatch: List<LogWatchKey>): Boolean =
            of(network)?.logIndex?.configure(network, watch, unwatch) ?: false

        override fun observeCoverage(network: Network): Flow<LogCoverageReading> =
            of(network)?.logIndex?.observeCoverage(network) ?: flowOf(LogCoverageReading.Unavailable)

        override suspend fun logs(network: Network, query: LogQuery): LogsReading =
            of(network)?.logIndex?.logs(network, query) ?: LogsReading.Unavailable(ReadUnavailable.NO_ENGINE)
    }
}

/** The chain ports of one network's light client: what [NetworkRoutedPorts] routes a call to. */
class ChainPorts(
    val nativeBalances: NativeBalances,
    val tokenBalances: TokenBalances,
    val tokenMetadata: TokenMetadata,
    val addressKinds: AddressKinds,
    val lightClient: LightClient,
    val networkUpgrades: NetworkUpgrades,
    val priceFeeds: PriceFeeds,
    val priceHistory: PriceHistory,
    val transactionInputs: TransactionInputs,
    val broadcaster: Broadcaster,
    val receipts: ReceiptTracker,
    val names: NameResolver,
    val safeApprovals: SafeApprovals,
    val uniswap: UniswapV3Chain,
    val cow: CowChain,
    val logIndex: LogIndex,
)

package eth.serotine.ui

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.FeeTier
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.QuoterV2
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.SwapRouter02
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.UniswapV3Deployment
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.ApprovalReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.SafeApprovals
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.SafeTxQueue
import eth.serotine.domain.repository.SafeTxQueueList
import eth.serotine.domain.repository.SignatureRecovery
import eth.serotine.domain.repository.SigningRequestCodec
import eth.serotine.domain.repository.SigningRequestDecoding
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.VenueReading
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveSafe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Recovery of made-up signatures: [sign] makes one per owner and digest, and only such a pair
 * recovers to its owner; any other signature recovers to nobody. No key exists anywhere.
 */
internal class FakeRecovery : SignatureRecovery {
    private val made = mutableMapOf<Pair<String, Signature>, Address>()

    fun sign(owner: Address, digest: Digest): Signature {
        // r: a 1, the owner's 20 bytes and the start of the digest — below the curve order, never 0.
        val r = "1" + owner.hex.removePrefix("0x") + digest.hex.take(23)
        return Signature(r, "2".padStart(64, '0'), recoveryId = 0).also { made[digest.hex to it] = owner }
    }

    override fun recover(digest: Digest, signature: Signature): Address? = made[digest.hex to signature]
}

/** A [SafeTxQueue] in memory, merging as the port says; [unreadable] and [failSaves] make it fail. */
internal class MemorySafeTxQueue : SafeTxQueue {
    val requests = MutableStateFlow<List<SigningRequest>>(emptyList())
    val unreadable = MutableStateFlow(false)
    var failSaves = false

    /** While set, a removal waits for it. */
    var removeGate: CompletableDeferred<Unit>? = null

    override fun observe(safe: AccountId): Flow<SafeTxQueueList> = combine(requests, unreadable) { list, broken ->
        if (broken) {
            SafeTxQueueList.Unreadable
        } else {
            SafeTxQueueList.Loaded(list.filter { it.network == safe.network && it.safe == safe.address })
        }
    }

    override suspend fun save(request: SigningRequest): SigningRequest? {
        if (failSaves || unreadable.value) return null
        val queued = requests.value.firstOrNull { it.hash == request.hash }
        val merged = queued?.withSignatures(request.signatures) ?: request
        requests.value = if (queued == null) {
            requests.value + merged
        } else {
            requests.value.map { if (it === queued) merged else it }
        }
        return merged
    }

    override suspend fun remove(hash: Digest): Boolean {
        removeGate?.await()
        if (failSaves) return false
        requests.value = requests.value.filterNot { it.hash == hash }
        return true
    }

    override suspend fun prune(safe: AccountId, nonce: Long): Boolean {
        if (failSaves) return false
        requests.value = requests.value.filterNot {
            it.network == safe.network && it.safe == safe.address && it.nonce < nonce
        }
        return true
    }
}

/** On-chain approvals a test sets, per owner; the rest read as not approved, at block 120. */
internal class FakeApprovals : SafeApprovals {
    val approved = mutableMapOf<Address, ApprovalReading>()

    override suspend fun approval(network: Network, safe: Address, owner: Address, hash: Digest): ApprovalReading =
        approved[owner] ?: ApprovalReading.Read(approved = false, blockNumber = 120)
}

/** A codec over texts a test sets; [encode] makes a text that decodes back to its request. */
internal class FakeCodec : SigningRequestCodec {
    val texts = mutableMapOf<String, SigningRequestDecoding>()

    override fun encode(request: SigningRequest): String {
        val text = "request:${request.hash.hex}:${request.signers.size}"
        texts[text] = SigningRequestDecoding.Decoded(request)
        return text
    }

    override fun decode(text: String): SigningRequestDecoding = texts[text.trim()] ?: SigningRequestDecoding.Malformed
}

/**
 * A Safe on Sepolia, Treasury, with three owners — [alice], whose key the wallet holds, [bob],
 * watched, and [carol], not in the wallet — at threshold 2 and nonce 4; [dave], held here and no
 * owner; and the fakes of every port its transactions go through. Every signature is made up
 * ([FakeRecovery]): no key exists anywhere.
 */
internal class SafeWorld {
    val safe = Address.of("0x" + "5a".repeat(20))
    val alice = Account(
        Network.SEPOLIA,
        Address.of("0x" + "11".repeat(20)),
        AccountKind.EOA,
        "Alice",
        AccountCustody.InKeyStore,
    )
    val bob = Account(
        Network.SEPOLIA,
        Address.of("0x" + "22".repeat(20)),
        AccountKind.EOA,
        "Bob",
        AccountCustody.Watched,
    )
    val carol = Address.of("0x" + "33".repeat(20))
    val dave = Account(
        Network.SEPOLIA,
        Address.of("0x" + "44".repeat(20)),
        AccountKind.EOA,
        "Dave",
        AccountCustody.InKeyStore,
    )
    val treasury = Account(Network.SEPOLIA, safe, AccountKind.SAFE, "Treasury", AccountCustody.Watched)

    /** EIP-55's own example: 0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB. */
    val recipient = Address.of("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb")
    val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc")

    val kind = MutableStateFlow<KindReading>(KindReading.Syncing)
    var state = SafeState("1.4.1", l2 = false, listOf(alice.address, bob.address, carol), 2, 4, emptyList(), null)
        set(value) {
            field = value
            kind.value = KindReading.Verified(AddressKind.Safe(value), 100)
        }
    /** While set, a reading of the Safe begun now waits for it: a fresh set of proofs on its way. */
    var kindGate: CompletableDeferred<Unit>? = null
    val kinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address): Flow<KindReading> = if (address == safe) {
            flow {
                kindGate?.await()
                emitAll(kind)
            }
        } else {
            flowOf(KindReading.Verified(AddressKind.Eoa, 100))
        }
    }
    val accounts = FakeAccounts(alice, bob, dave, treasury)
    val observeSafe = ObserveSafe(kinds, accounts)
    val recovery = FakeRecovery()
    val queue = MemorySafeTxQueue()
    val approvals = FakeApprovals()
    val codec = FakeCodec()
    val inputs = FakeTransactionInputs()
    val sent = MemorySentTransfers()
    val signer = FakeSigner(recovery)
    val broadcaster = FakeBroadcaster()
    val receipts = FakeReceipts()
    val balances = FakeBalances()
    val tokenBalances = FakeTokenBalances()
    val catalog = FakeTokenCatalog(usdc)
    val userTokens = MemoryUserTokens()
    val metadata = FakeMetadata()
    val knownTokens = ObserveKnownTokens(catalog, userTokens, metadata)

    /** Uniswap as pinned on Sepolia, its code proven; QuoterV2 answers [quotes] by call data, or reverts. */
    val uniswapDeployment = UniswapV3Deployment(
        factory = Address.of("0x0227628f3f023bb0b980b67d528571c95c6dac1c"),
        quoterV2 = Address.of("0xed1f6473345f45b75f8179591dd5ba1888cf2fb3"),
        swapRouter02 = Address.of("0x3bfa4769fb09eefc5a80d6e87c3b9c650f7ae48e"),
        weth9 = Address.of("0xfff9976782d46cc05630d1f6ebab18b2324d6b14"),
    )
    val quotes = mutableMapOf<String, Simulation>()
    val uniswap = object : UniswapV3Chain {
        override suspend fun venue(network: Network): VenueReading = VenueReading.Verified(uniswapDeployment)

        override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation =
            quotes[data.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }]
                ?: Simulation.Reverted(100)
    }

    /** The verified head, at [HEAD_TIME]. */
    val head = MutableStateFlow<LightClientStatus>(
        LightClientStatus.Synced(headBlock = 100, headTimestamp = HEAD_TIME, finalized = null),
    )
    val lightClient = object : LightClient {
        override val datesFinalizedBlocks = true
        override fun observeStatus(network: Network): Flow<LightClientStatus> = head
    }

    /** USDC → ETH in the pinned router's 0.05 % pool, [amountIn] for at least [minimum] wei, paid to the Safe. */
    fun swap(nonce: Long, amountIn: Long = 100_000_000, minimum: Long = 40_000_000_000_000_000) = SafeTx.call(
        uniswapDeployment.swapRouter02,
        UInt256.ZERO,
        SwapRouter02.swap(
            uniswapDeployment,
            TransferAsset.Erc20Token(usdc),
            TransferAsset.Native,
            SWAP_ROUTE,
            UInt256.of(amountIn),
            UInt256.of(minimum),
            safe,
            HEAD_TIME + DAY,
        ).data,
        nonce,
    )

    /** The pinned router allowed to take [units] of the Safe's USDC. */
    fun approval(nonce: Long, units: Long = 100_000_000) = SafeTx.call(
        usdc.address,
        UInt256.ZERO,
        Erc20.approveData(uniswapDeployment.swapRouter02, UInt256.of(units)),
        nonce,
    )

    /** QuoterV2 quotes [out] wei for the [swap]'s [amountIn], at block 100. */
    fun quoteSwap(out: Long, amountIn: Long = 100_000_000) {
        val data = QuoterV2.quote(SWAP_ROUTE, UInt256.of(amountIn))
        quotes[data.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }] = Simulation.Succeeded(
            listOf(UInt256.of(out), UInt256.ZERO, UInt256.ZERO, UInt256.of(100_000)).joinToString("") { word ->
                word.toBytes32().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            },
            100,
        )
    }

    private val SWAP_ROUTE: SwapRoute
        get() = SwapRoute.direct(usdc.address, uniswapDeployment.weth9, FeeTier.LOW)

    init {
        kind.value = KindReading.Verified(AddressKind.Safe(state), 100)
    }

    /** A transfer of [wei] to [recipient] at [nonce]. */
    fun ether(nonce: Long, wei: Long = 100_000_000_000_000_000) =
        SafeTx.call(recipient, UInt256.of(wei), ByteArray(0), nonce)

    /** A transfer of [units] of USDC to [recipient] at [nonce]. */
    fun usdcTransfer(nonce: Long, units: Long = 1_500_000) =
        SafeTx.call(usdc.address, UInt256.ZERO, Erc20.transferData(recipient, UInt256.of(units)), nonce)

    /** A request for this Safe, signed by [first] and [second] (none when null). */
    fun request(tx: SafeTx, first: Address? = null, second: Address? = null): SigningRequest {
        val unsigned = SigningRequest.of(Network.SEPOLIA, safe, tx)
        return unsigned.withSignatures(
            listOfNotNull(first, second).map { SafeSignature.Ecdsa(it, recovery.sign(it, unsigned.hash)) },
        )
    }
}

/** The verified head's time in the Safe tests: a swap's deadline is a day after it. */
internal const val HEAD_TIME = 1_792_419_000L

/** A day, in seconds. */
internal const val DAY = 86_400L

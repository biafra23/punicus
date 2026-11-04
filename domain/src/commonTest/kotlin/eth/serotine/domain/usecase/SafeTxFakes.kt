package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.ApprovalReading
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeQuote
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.SafeApprovals
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.SafeTxQueue
import eth.serotine.domain.repository.SafeTxQueueList
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.SignatureRecovery
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.SigningRequestCodec
import eth.serotine.domain.repository.SigningRequestDecoding
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.repository.prunable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * A Safe on Sepolia with three owners — [alice], whose key the wallet holds, [bob], watched, and
 * [carol], not in the wallet — at threshold 2 and nonce 4, and the fakes of every port its
 * transactions go through. Every signature is made up ([FakeRecovery]): no key exists anywhere.
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
    val stranger = Address.of("0x" + "44".repeat(20))
    val recipient = Address.of("0x" + "bb".repeat(20))

    var state = SafeState("1.4.1", l2 = false, listOf(alice.address, bob.address, carol), 2, 4, emptyList(), null)
        set(value) {
            field = value
            kind.value = KindReading.Verified(AddressKind.Safe(value), 100)
        }
    val kind = MutableStateFlow<KindReading>(KindReading.Verified(AddressKind.Safe(state), 100))
    val kinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address): Flow<KindReading> = kind
    }
    val accounts = ListAccountRepository(
        alice,
        bob,
        Account(Network.SEPOLIA, safe, AccountKind.SAFE, "Treasury", AccountCustody.Watched),
    )
    val observeSafe = ObserveSafe(kinds, accounts)
    val recovery = FakeRecovery()
    val queue = MemorySafeTxQueue()
    val approvals = FakeApprovals()
    val codec = FakeCodec()
    val inputs = FakeTransactionInputs()
    val sent = MemorySentTransfers()
    val signer = FakeSigner(recovery)
    val broadcaster = FakeBroadcaster()

    var native: BalanceReading = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
    var tokens: TokenBalanceReading = TokenBalanceReading.Verified(TokenUnits.of("5000000"), 100)
    val nativeBalances = object : NativeBalances {
        override fun observeBalance(network: Network, address: Address): Flow<BalanceReading> = flowOf(native)
    }
    val tokenBalances = object : TokenBalances {
        override fun observeBalance(
            network: Network,
            holder: Address,
            token: Address,
        ): Flow<TokenBalanceReading> = flowOf(tokens)
    }

    /** A request for this Safe, signed by [first] and [second] (none when null). */
    fun request(tx: SafeTx, first: Address? = null, second: Address? = null): SigningRequest {
        val unsigned = SigningRequest.of(Network.SEPOLIA, safe, tx)
        return unsigned.withSignatures(
            listOfNotNull(first, second).map { SafeSignature.Ecdsa(it, recovery.sign(it, unsigned.hash)) },
        )
    }
}

/**
 * Recovery of made-up signatures: [sign] makes one per owner and digest, and only such a pair
 * recovers to its owner; any other signature recovers to nobody.
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

/** A [Signer] that signs with [recovery]'s made-up signatures, or answers [answer] when one is set. */
internal class FakeSigner(private val recovery: FakeRecovery) : Signer {
    var answer: Signing? = null
    var signAs: Address? = null
    val asked = mutableListOf<Pair<Address, Digest>>()

    override suspend fun sign(account: Address, digest: Digest, unlock: Unlock): Signing {
        asked += account to digest
        return answer ?: Signing.Signed(recovery.sign(signAs ?: account, digest))
    }
}

internal class FakeBroadcaster : Broadcaster {
    var answer: Broadcast = Broadcast.Sent("0x" + "00".repeat(32))
    val sent = mutableListOf<ByteArray>()

    override suspend fun send(network: Network, raw: ByteArray): Broadcast {
        sent += raw
        return answer
    }
}

/** On-chain approvals a test sets, per owner; the rest read as not approved. */
internal class FakeApprovals : SafeApprovals {
    val approved = mutableMapOf<Address, ApprovalReading>()
    val asked = mutableListOf<Address>()

    override suspend fun approval(network: Network, safe: Address, owner: Address, hash: Digest): ApprovalReading {
        asked += owner
        return approved[owner] ?: ApprovalReading.Read(approved = false, blockNumber = 120)
    }
}

/** A codec over texts a test sets; [encode] makes a text that decodes back to its request. */
internal class FakeCodec : SigningRequestCodec {
    val texts = mutableMapOf<String, SigningRequestDecoding>()

    override fun encode(request: SigningRequest): String =
        "request:${request.hash.hex}:${request.signers}".also { texts[it] = SigningRequestDecoding.Decoded(request) }

    override fun decode(text: String): SigningRequestDecoding = texts[text] ?: SigningRequestDecoding.Malformed
}

/** A [SafeTxQueue] in memory, merging as the port says. */
internal class MemorySafeTxQueue : SafeTxQueue {
    val requests = MutableStateFlow<List<SigningRequest>>(emptyList())
    var unreadable = false

    override fun observe(safe: AccountId): Flow<SafeTxQueueList> = requests.map { list ->
        if (unreadable) {
            SafeTxQueueList.Unreadable
        } else {
            SafeTxQueueList.Loaded(list.filter { it.network == safe.network && it.safe == safe.address })
        }
    }

    override suspend fun save(request: SigningRequest): SigningRequest? {
        if (unreadable) return null
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
        requests.value = requests.value.filterNot { it.hash == hash }
        return true
    }

    override suspend fun prune(safe: AccountId, nonce: Long): Boolean {
        requests.value = requests.value.filterNot {
            it.network == safe.network && it.safe == safe.address && it.nonce < nonce
        }
        return true
    }
}

internal class MemorySentTransfers : SentTransfers {
    val transfers = MutableStateFlow<List<SentTransfer>>(emptyList())

    override fun observe(): Flow<SentTransferList> = transfers.map { SentTransferList.Loaded(it) }

    override suspend fun record(transfer: SentTransfer): Boolean {
        transfers.value = transfers.value.filterNot { it.hash == transfer.hash } + transfer
        return true
    }


    override suspend fun prune(account: AccountId, minedCount: Long, unlistedBefore: Long): Boolean {
        transfers.value = transfers.value.filterNot { it.prunable(account, minedCount, unlistedBefore) }
        return true
    }
}

/** Verified transaction inputs a test sets: nonce 7, 10 gwei base fee, the estimate and the run. */
internal class FakeTransactionInputs : TransactionInputs {
    var gas = 80_000L
    var estimate: GasEstimate? = null
    var simulation: Simulation = Simulation.Succeeded("", 100)
    val simulated = mutableListOf<Triple<Address, Address, ByteArray>>()

    override suspend fun nonce(network: Network, account: Address): NonceReading = NonceReading.Read(7)

    /** Every account's verified count of mined transactions. */
    var mined = 7L

    override suspend fun minedNonce(network: Network, account: Address): NonceReading = NonceReading.Read(mined)

    override suspend fun fees(network: Network): FeeReading = FeeReading.Quoted(
        FeeQuote(
            nextBaseFee = UInt256.of(GWEI * 10),
            tips = FeeLevel.entries.associateWith { UInt256.of(GWEI) },
            blockNumber = 99,
        ),
    )

    override suspend fun estimateGas(
        network: Network,
        from: Address,
        to: Address,
        value: UInt256,
        data: ByteArray,
    ): GasEstimate =
        estimate ?: GasEstimate.Estimated(gas)

    override suspend fun simulate(
        network: Network,
        from: Address,
        to: Address,
        value: UInt256,
        data: ByteArray,
    ): Simulation {
        simulated += Triple(from, to, data)
        return simulation
    }

    private companion object {
        const val GWEI = 1_000_000_000L
    }
}

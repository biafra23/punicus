package eth.serotine.data.transfers

import eth.serotine.data.safe.WireRequest
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.SafeListing
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// How a sent transaction is stored: the one form FileSentTransfers and FileTransactionHistory
// both keep it in, so the two files never describe the same transaction two ways.

private const val APPROVAL = "approval"
private const val EXECUTION = "execution"

/**
 * One transaction, sent on the network of [chain] (absent in a record written before networks:
 * Sepolia, the only one there was; one of a network this build does not know does not decode):
 * a transfer's [to], [token] (null: the native currency) and [amount], a
 * Safe transaction's approval or execution ([safe]), a Safe's deployment ([deployment]), a
 * token approval ([approval]), a swap ([swap]), a CoW order's cancellation on chain
 * ([cancellation]) or a dApp's transaction ([dappCall]) — exactly one of the seven, else the file is
 * damaged.
 */
@Serializable
internal class StoredTransfer(
    val account: String,
    val nonce: Long,
    val raw: String,
    val to: String? = null,
    val token: StoredToken? = null,
    val amount: String? = null,
    val safe: StoredSafeCall? = null,
    val deployment: StoredDeployment? = null,
    val approval: StoredApproval? = null,
    val swap: StoredSwap? = null,
    val cancellation: StoredCowCancellation? = null,
    val dappCall: StoredDappCall? = null,
    val maxCost: String,
    val sentAt: Long,
    val maybeSent: Boolean = false,
    val chain: Long? = null,
) {
    fun toTransfer() = SentTransfer(
        network = if (chain == null) Network.SEPOLIA else requireNotNull(Network.ofChainId(chain)) { "a chain" },
        account = Address.of(account),
        nonce = nonce,
        signed = SignedTransfer(raw.hexBytes()),
        action = action(),
        maxCost = UInt256.of(maxCost),
        sentAt = sentAt,
        maybeSent = maybeSent,
    )

    private fun action(): SentAction {
        val kinds = listOfNotNull(deployment, safe, approval, swap, cancellation, dappCall)
        require(kinds.size <= 1) { "more than one kind of transaction" }
        val transferFields = to != null || token != null || amount != null
        require(kinds.isEmpty() || !transferFields) { "a transfer and another kind at once" }
        deployment?.let { return it.toAction() }
        safe?.let { return it.toAction() }
        approval?.let { return it.toAction() }
        swap?.let { return it.toAction() }
        cancellation?.let { return it.toAction() }
        dappCall?.let { return it.toAction() }
        return SentAction.Transfer(
            to = Address.of(requireNotNull(to) { "a transfer without a recipient" }),
            asset = token?.let { TransferAsset.Erc20Token(it.toToken()) } ?: TransferAsset.Native,
            amount = UInt256.of(requireNotNull(amount) { "a transfer without an amount" }),
        )
    }
}

/** What a token transfer needs shown again: its bundled icon's name too, not its price. */
@Serializable
internal class StoredToken(
    val address: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
    val icon: String? = null,
) {
    fun toToken() = Token(Address.of(address), symbol, name, decimals, icon)
}

/** A token approval: [spender] may take [amount] of [token]. */
@Serializable
internal class StoredApproval(val token: StoredToken, val spender: String, val amount: String) {
    fun toAction() = SentAction.TokenApproval(token.toToken(), Address.of(spender), UInt256.of(amount))
}

/**
 * A swap through [router]: [amountIn] of [sell] for at least [minimumOut] of [buy], each asset
 * a token, or the native currency (null); [weth] the WETH9 the router unwraps ETH bought from.
 */
@Serializable
internal class StoredSwap(
    val router: String,
    val sell: StoredToken? = null,
    val amountIn: String,
    val buy: StoredToken? = null,
    val minimumOut: String,
    val weth: String? = null,
) {
    fun toAction() = SentAction.Swap(
        router = Address.of(router),
        sell = sell.asset(),
        amountIn = UInt256.of(amountIn),
        buy = buy.asset(),
        minimumOut = UInt256.of(minimumOut),
        weth = weth?.let(Address::of),
    )
}

/** A dApp's transaction: its recipient [to], its [value] in wei, and the [dapp] it was sent for (its claim). */
@Serializable
internal class StoredDappCall(val to: String, val value: String, val dapp: String) {
    fun toAction() = SentAction.DappCall(Address.of(to), UInt256.of(value), dapp)
}

/** A CoW order's cancellation on chain: the order [uid], at the settlement contract [settlement]. */
@Serializable
internal class StoredCowCancellation(val settlement: String, val uid: String) {
    fun toAction() = SentAction.CowCancellation(Address.of(settlement), CowOrderUid.of(uid))
}

/** A Safe transaction's [kind] of call ([APPROVAL] or [EXECUTION]) and its [request]. */
@Serializable
internal class StoredSafeCall(val kind: String, val request: WireRequest) {
    fun toAction(): SentAction {
        require(request.v == WireRequest.VERSION) { "a request of another version" }
        val network = requireNotNull(Network.ofChainId(request.chainId)) { "a chain" }
        val decoded = requireNotNull(request.toRequest(network)) { "a request out of range" }
        return when (kind) {
            APPROVAL -> SentAction.SafeApproval(decoded)
            EXECUTION -> SentAction.SafeExecution(decoded)
            else -> throw IllegalArgumentException("a Safe call of no kind")
        }
    }
}

/**
 * A Safe's deployment: the [safe] it creates, of [version], with [owners] and [threshold], listed
 * as [label], and how its listing ended ([listed], a [SafeListing] name; null: not yet).
 */
@Serializable
internal class StoredDeployment(
    val safe: String,
    val owners: List<String>,
    val threshold: Int,
    val version: String,
    val label: String,
    val listed: String? = null,
) {
    fun toAction() = SentAction.SafeDeployment(
        safe = Address.of(safe),
        owners = owners.map(Address::of),
        threshold = threshold,
        version = version,
        label = label,
        listed = listed?.let { name -> requireNotNull(SafeListing.entries.firstOrNull { it.name == name }) },
    )
}

/** The transaction [entry] stores, or null when it does not decode: passed over, the rest read. */
internal fun decodedTransfer(entry: JsonElement): SentTransfer? = try {
    Json.decodeFromJsonElement(StoredTransfer.serializer(), entry).toTransfer()
} catch (e: IllegalArgumentException) {
    // A SerializationException (a field missing or of another type), or a field out of
    // range (an address, an amount, hex) as the stored types refuse it.
    null
}

/** The form [transfer] is stored in. */
internal fun storedTransfer(transfer: SentTransfer): StoredTransfer {
    val action = transfer.action
    val send = action as? SentAction.Transfer
    return StoredTransfer(
        account = transfer.account.hex,
        nonce = transfer.nonce,
        raw = transfer.signed.raw.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
        to = send?.to?.hex,
        token = send?.asset?.storedToken(),
        amount = send?.amount?.toDecimal(),
        safe = when (action) {
            is SentAction.SafeApproval -> safeCall(APPROVAL, action.request)
            is SentAction.SafeExecution -> safeCall(EXECUTION, action.request)
            is SentAction.Transfer, is SentAction.SafeDeployment, is SentAction.TokenApproval,
            is SentAction.Swap, is SentAction.CowCancellation, is SentAction.DappCall,
            -> null
        },
        dappCall = (action as? SentAction.DappCall)?.let { StoredDappCall(it.to.hex, it.value.toDecimal(), it.dapp) },
        approval = (action as? SentAction.TokenApproval)?.let {
            StoredApproval(stored(it.token), it.spender.hex, it.amount.toDecimal())
        },
        swap = (action as? SentAction.Swap)?.let {
            StoredSwap(
                it.router.hex,
                it.sell.storedToken(),
                it.amountIn.toDecimal(),
                it.buy.storedToken(),
                it.minimumOut.toDecimal(),
                it.weth?.hex,
            )
        },
        cancellation = (action as? SentAction.CowCancellation)?.let {
            StoredCowCancellation(it.settlement.hex, it.uid.hex)
        },
        deployment = (action as? SentAction.SafeDeployment)?.let {
            StoredDeployment(
                it.safe.hex,
                it.owners.map(Address::hex),
                it.threshold,
                it.version,
                it.label,
                it.listed?.name,
            )
        },
        maxCost = transfer.maxCost.toDecimal(),
        sentAt = transfer.sentAt,
        maybeSent = transfer.maybeSent,
        chain = transfer.network.chainId,
    )
}

/** A Safe transaction's call of [kind] as stored, its [request] in the form a signing request travels in. */
private fun safeCall(kind: String, request: SigningRequest) = StoredSafeCall(kind, WireRequest.of(request))

/** [token] as stored. */
internal fun stored(token: Token) = StoredToken(token.address.hex, token.symbol, token.name, token.decimals, token.icon)

/** The token this asset is, as stored; null for the native currency. */
internal fun TransferAsset.storedToken(): StoredToken? = (this as? TransferAsset.Erc20Token)?.token?.let(::stored)

/** The asset this stored token is: the native currency for none. */
internal fun StoredToken?.asset(): TransferAsset = this?.let { TransferAsset.Erc20Token(it.toToken()) }
    ?: TransferAsset.Native

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' }) { "not lower-case hex" }
    return ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

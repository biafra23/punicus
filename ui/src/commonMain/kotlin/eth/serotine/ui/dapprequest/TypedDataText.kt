package eth.serotine.ui.dapprequest

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip712
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.ShownText
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TypedData
import eth.serotine.domain.model.TypedDataMeaning
import eth.serotine.domain.model.TypedValue
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.usecase.DappSignContent
import eth.serotine.domain.usecase.MAX_SHOWN_VALUES
import eth.serotine.ui.components.Eip55

/** One row of typed data shown field by field: [label] at [depth], and its [value] (null for a struct or an array). */
data class TypedRow(val depth: Int, val label: String, val value: String?)

/**
 * Typed data as screen D5 shows it (docs/walletconnect.md, *Typed data*), display-ready: what it lets
 * someone do for the structs this app knows, its domain, and every field. Amounts are in a built-in
 * token's units where the token is one, else raw and said so; times are formatted with [formatTime].
 */
internal class TypedDataText(private val content: DappSignContent.Typed, private val formatTime: (Long) -> String) {

    private val data: TypedData get() = content.typed.data

    /** The screen's title. */
    val title: String
        get() = when (content.meaning) {
            is TypedDataMeaning.Permit, is TypedDataMeaning.Permit2Allowance, is TypedDataMeaning.Permit2Transfer ->
                "Approval request"
            is TypedDataMeaning.CowOrder -> "Swap order to sign"
            null -> "Data to sign"
        }

    /** What the system's biometric prompt says is signed: nothing the dApp states. */
    val promptSubject: String
        get() = when (content.meaning) {
            is TypedDataMeaning.Permit, is TypedDataMeaning.Permit2Allowance, is TypedDataMeaning.Permit2Transfer ->
                "Sign a dApp's approval"
            is TypedDataMeaning.CowOrder -> "Sign a swap order for a dApp"
            null -> "Sign a dApp's data"
        }

    /** What signing it lets someone do, in a sentence; null when the struct is not one this app knows. */
    val meaning: String?
        get() = when (val meaning = content.meaning) {
            is TypedDataMeaning.Permit -> "Lets ${short(meaning.spender)} take up to " +
                "${amount(meaning.value, meaning.token)} from this account, until ${until(meaning.deadline)}. " +
                APPROVAL_NOTE
            is TypedDataMeaning.Permit2Allowance -> "Lets ${short(meaning.spender)} take up to " +
                "${amount(meaning.amount, meaning.token, MAX_UINT160)} from this account through Permit2, " +
                "${expiration(meaning.expiration)}. $APPROVAL_NOTE"
            is TypedDataMeaning.Permit2Transfer -> "Lets ${short(meaning.spender)} take " +
                "${amount(meaning.amount, meaning.token)} from this account once, through Permit2, until " +
                "${until(meaning.deadline)}. $APPROVAL_NOTE"
            is TypedDataMeaning.CowOrder -> if (meaning.kind == "buy") {
                "An order on CoW Protocol to buy ${amount(meaning.buyAmount, meaning.buyToken)} for at most " +
                    "${amount(meaning.sellAmount, meaning.sellToken)}, until ${until(meaning.validTo)}. $ORDER_NOTE"
            } else {
                "An order on CoW Protocol to sell ${amount(meaning.sellAmount, meaning.sellToken)} for at least " +
                    "${amount(meaning.buyAmount, meaning.buyToken)}, until ${until(meaning.validTo)}. $ORDER_NOTE"
            }
            null -> null
        }

    /** The decoded struct's parts, each in full. */
    val meaningFields: List<SignInField>
        get() = when (val meaning = content.meaning) {
            is TypedDataMeaning.Permit -> listOf(
                SignInField("Token", token(meaning.token)),
                SignInField("Spender", Eip55.format(meaning.spender)),
                SignInField("Amount", amount(meaning.value, meaning.token)),
                SignInField("Until", until(meaning.deadline)),
            )
            is TypedDataMeaning.Permit2Allowance -> listOf(
                SignInField("Token", token(meaning.token)),
                SignInField("Spender", Eip55.format(meaning.spender)),
                SignInField("Amount", amount(meaning.amount, meaning.token, MAX_UINT160)),
                SignInField("Allowance", expiration(meaning.expiration)),
                SignInField("Signature good until", until(meaning.sigDeadline)),
            )
            is TypedDataMeaning.Permit2Transfer -> listOf(
                SignInField("Token", token(meaning.token)),
                SignInField("Spender", Eip55.format(meaning.spender)),
                SignInField("Amount", amount(meaning.amount, meaning.token)),
                SignInField("Until", until(meaning.deadline)),
            )
            is TypedDataMeaning.CowOrder -> listOf(
                SignInField("Sell", "${amount(meaning.sellAmount, meaning.sellToken)} (${token(meaning.sellToken)})"),
                SignInField("Buy", "${amount(meaning.buyAmount, meaning.buyToken)} (${token(meaning.buyToken)})"),
                SignInField(
                    "Paid to",
                    if (meaning.receiver == Address.ZERO) "This account" else Eip55.format(meaning.receiver),
                ),
                SignInField("Valid until", until(meaning.validTo)),
                SignInField("Partly filled", if (meaning.partiallyFillable) "Allowed" else "No: all at once or not"),
            )
            null -> emptyList()
        }

    /** The domain: who verifies the signature, on which chain. */
    val domainFields: List<SignInField>
        get() = data.types.getValue(Eip712.DOMAIN_TYPE).map { field ->
            val value = data.domain.getValue(field.name)
            when (field.name) {
                "chainId" -> SignInField("Network", network(Eip712.unsignedOf(value)))
                "verifyingContract" -> SignInField("Contract", atom(field.type, value))
                else -> SignInField(field.name.replaceFirstChar(Char::uppercaseChar), atom(field.type, value))
            }
        }

    /**
     * Every field of the message, in the order its type gives them: all of them, since data with more
     * than [MAX_ROWS] is refused, and only then cut.
     */
    val rows: List<TypedRow>
        get() {
            val rows = mutableListOf<TypedRow>()
            fun add(depth: Int, label: String, type: String, value: TypedValue) {
                if (rows.size > MAX_ROWS) return
                val fields = data.types[type]
                when {
                    fields != null -> {
                        rows += TypedRow(depth, label, null)
                        val struct = (value as TypedValue.Struct).fields
                        for (field in fields) add(depth + 1, field.name, field.type, struct.getValue(field.name))
                    }
                    type.endsWith("]") -> {
                        val items = (value as TypedValue.Items).items
                        rows += TypedRow(depth, label, if (items.size == 1) "1 item" else "${items.size} items")
                        val itemType = type.substringBeforeLast('[')
                        items.forEachIndexed { index, item -> add(depth + 1, "[$index]", itemType, item) }
                    }
                    else -> rows += TypedRow(depth, label, atom(type, value))
                }
            }
            for (field in data.types.getValue(data.primaryType)) {
                add(0, field.name, field.type, data.message.getValue(field.name))
            }
            if (rows.size > MAX_ROWS) {
                return rows.take(MAX_ROWS) + TypedRow(0, "…", "and more fields, not shown here")
            }
            return rows
        }

    private fun atom(type: String, value: TypedValue): String = when {
        type == "address" -> Eip55.format(Address.of((value as TypedValue.Text).text))
        type == "bool" -> if ((value as TypedValue.Bool).value) "true" else "false"
        type.startsWith("uint") -> Eip712.unsignedOf(value).toDecimal()
        else -> when (value) {
            // One line per value: a character that draws nothing, or a line break, is spelled out.
            is TypedValue.Text -> ShownText.escaped(value.text)
            is TypedValue.Number -> value.digits
            is TypedValue.Bool -> value.value.toString()
            else -> ""
        }
    }

    private fun tokenOf(address: Address): Token? = content.tokens[address]

    private fun token(address: Address): String =
        tokenOf(address)?.let { "${it.symbol} · ${Eip55.format(address)}" }
            ?: "${Eip55.format(address)} · not in the built-in token list"

    private fun amount(value: UInt256, address: Address, unlimited: UInt256 = UInt256.MAX): String {
        val token = tokenOf(address)
        return when {
            value == unlimited || value == UInt256.MAX -> "an unlimited amount of ${token?.symbol ?: "the token"}"
            token != null -> "${value.formatUnits(token.decimals)} ${token.symbol}"
            else -> "${value.toDecimal()} units of ${short(address)}"
        }
    }

    private fun until(seconds: UInt256): String {
        val long = seconds.toLongOrNull()
        return if (long == null || long > MAX_DATE_SECONDS) "no end" else formatTime(long)
    }

    private fun expiration(seconds: UInt256): String =
        if (seconds.isZero) "for the block it is used in" else "until ${until(seconds)}"

    private fun network(chainId: UInt256): String {
        val id = chainId.toLongOrNull()
        return id?.let(Network::ofChainId)?.let { "${it.displayName} ($id)" } ?: "Chain ${chainId.toDecimal()}"
    }

    companion object {
        /** The most rows shown of typed data's fields: what a request may have and still be signed. */
        const val MAX_ROWS = MAX_SHOWN_VALUES

        // Year 9999: beyond it a deadline is no date anyone can read, and means no end.
        private const val MAX_DATE_SECONDS = 253_402_300_799L

        private val MAX_UINT160 = UInt256.fromBigEndian(ByteArray(12) + ByteArray(20) { -1 })

        const val APPROVAL_NOTE =
            "Signing moves nothing itself, but the spender may then take it without asking you again."
        const val ORDER_NOTE = "Signing lets CoW Protocol's solvers fill it, without asking you again."

        fun short(address: Address): String = Eip55.format(address).let { it.take(6) + "…" + it.takeLast(4) }
    }
}

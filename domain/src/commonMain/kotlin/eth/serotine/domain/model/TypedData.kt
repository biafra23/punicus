package eth.serotine.domain.model

/**
 * A value of EIP-712 typed data as its JSON holds it, before any type is applied (docs/walletconnect.md,
 * *Typed data*). The adapter reads the JSON into these; [Eip712] applies the types to them.
 */
sealed interface TypedValue {
    /** A JSON string. */
    data class Text(val text: String) : TypedValue

    /** A JSON number, as written: an integer, never a fraction or an exponent. */
    data class Number(val digits: String) : TypedValue

    /** A JSON `true` or `false`. */
    data class Bool(val value: Boolean) : TypedValue

    /** A JSON object, its members in the order the JSON gave them. */
    data class Struct(val fields: Map<String, TypedValue>) : TypedValue

    /** A JSON array. */
    data class Items(val items: List<TypedValue>) : TypedValue
}

/** One member of a struct type: its [name] and its [type] (`address`, `uint256`, `Person[]`, …). */
data class TypedField(val name: String, val type: String)

/**
 * The typed data a dApp asks to have signed with `eth_signTypedData_v4`: the struct [types], the
 * [primaryType] of the [message], and the [domain], whose type is `EIP712Domain` among the [types].
 * As the dApp sent it, untrusted: [Eip712.encode] checks it and computes what is signed.
 */
data class TypedData(
    val types: Map<String, List<TypedField>>,
    val primaryType: String,
    val domain: Map<String, TypedValue>,
    val message: Map<String, TypedValue>,
)

/**
 * [data] checked against EIP-712, and the [digest] that signing it signs:
 * `keccak256(0x19 ‖ 0x01 ‖ domainSeparator ‖ hashStruct(message))`, computed here, never taken from the
 * dApp. Made only by [Eip712.encode], so every one holds a digest of data that is exactly EIP-712.
 */
@ConsistentCopyVisibility
data class EncodedTypedData internal constructor(
    val data: TypedData,
    val domainSeparator: String,
    val messageHash: String,
    val digest: Digest,
)

/**
 * EIP-712's encoding of typed data (`eth_signTypedData_v4`'s), done here so the digest the user approves
 * is computed where what it is the hash of can be shown (owner ruling, 2026-10-18, #50, widened
 * 2026-11-03 on #218). Strict: anything that is not exactly EIP-712 is refused, never repaired.
 *
 * - Types: names of letters, digits, `_` and `$`, not starting with a digit; each struct's members
 *   uniquely named, each of an atomic type (`address`, `bool`, `string`, `bytes`, `bytes1`…`bytes32`,
 *   `uint8`…`uint256`, `int8`…`int256`, in steps of 8), a struct type among them, or an array of one
 *   (`T[]`, `T[n]`, at most [MAX_DIMENSIONS] deep). `EIP712Domain` must be among them, its members
 *   only EIP-712's own, each of its own type: `string name`, `string version`, `uint256 chainId`,
 *   `address verifyingContract`, `bytes32 salt`. The primary type must be a struct type other than
 *   `EIP712Domain`.
 * - Values: every member of a struct present and nothing else, so nothing is shown that is not signed.
 *   An address is `0x` and 40 hex digits; a `bool` a JSON boolean; an integer a JSON integer, or a
 *   string of decimal digits or of `0x` hex, inside its type's range; `bytesN` exactly N bytes of hex
 *   and `bytes` any number; a string any JSON string but one holding a lone surrogate, which has no
 *   UTF-8; a fixed-size array exactly its length.
 * - Within limits: [MAX_TYPES] types, [MAX_FIELDS] members each, names of at most [MAX_NAME] characters,
 *   [MAX_ITEMS] items in an array, nesting [MAX_DEPTH] deep, and at most [MAX_VALUES] values in all.
 */
object Eip712 {
    const val MAX_TYPES = 64
    const val MAX_FIELDS = 64
    const val MAX_ITEMS = 1024
    const val MAX_DEPTH = 16
    const val MAX_DIMENSIONS = 4
    const val MAX_NAME = 64

    /** The most values (structs, arrays and atoms) the domain and the message may hold together. */
    const val MAX_VALUES = 4096

    /** EIP-712's domain members, each with its one type. */
    private val DOMAIN_FIELDS = mapOf(
        "name" to "string",
        "version" to "string",
        "chainId" to "uint256",
        "verifyingContract" to "address",
        "salt" to "bytes32",
    )

    /** The name of the domain's struct type. */
    const val DOMAIN_TYPE = "EIP712Domain"

    private val NAME = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
    private val ARRAY = Regex("^(.+)\\[([0-9]*)]$")
    private val DIMENSIONS = Regex("^(\\[[0-9]{0,4}])*$")
    private val HEX = Regex("^0x[0-9a-fA-F]*$")
    private val DECIMAL = Regex("^-?(0|[1-9][0-9]*)$")
    private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
    private val PREFIX = byteArrayOf(0x19, 0x01)

    /** [data] encoded and hashed, or null when it is not exactly EIP-712 (see [Eip712]). */
    fun encode(data: TypedData): EncodedTypedData? = try {
        checkTypes(data)
        require(count(data.domain.values) + count(data.message.values) <= MAX_VALUES)
        require(data.primaryType != DOMAIN_TYPE && data.primaryType in data.types)
        val separator = hashStruct(data.types, DOMAIN_TYPE, data.domain, 0)
        val message = hashStruct(data.types, data.primaryType, data.message, 0)
        EncodedTypedData(
            data,
            separator.toHex(),
            message.toHex(),
            Digest.of(keccak256(PREFIX + separator + message).toHex()),
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    /** `encodeType` of [type]: the struct, then every struct it refers to, sorted by name. */
    fun encodeType(types: Map<String, List<TypedField>>, type: String): String {
        val found = linkedSetOf<String>()
        collect(types, type, found)
        val others = (found - type).sorted()
        return (listOf(type) + others).joinToString("") { name ->
            name + types.getValue(name).joinToString(",", "(", ")") { "${it.type} ${it.name}" }
        }
    }

    private fun collect(types: Map<String, List<TypedField>>, type: String, found: MutableSet<String>) {
        if (!found.add(type)) return
        for (field in types.getValue(type)) {
            val base = baseOf(field.type)
            if (base in types) collect(types, base, found)
        }
    }

    /** [type] without its array dimensions: `Person` of `Person[2][]`. */
    private fun baseOf(type: String): String = type.substringBefore('[')

    /**
     * How many values [values] hold, structs and arrays among them, counted without recursion and no
     * further than [MAX_VALUES] + 1.
     */
    fun count(values: Collection<TypedValue>): Int {
        val waiting = ArrayDeque(values)
        var counted = 0
        while (waiting.isNotEmpty() && counted <= MAX_VALUES) {
            counted++
            when (val value = waiting.removeLast()) {
                is TypedValue.Struct -> waiting.addAll(value.fields.values)
                is TypedValue.Items -> waiting.addAll(value.items)
                else -> Unit
            }
        }
        return counted
    }

    private fun checkTypes(data: TypedData) {
        require(data.types.size <= MAX_TYPES && DOMAIN_TYPE in data.types)
        // The domain's members are EIP-712's own, each of its one type: what the domain says is read as such.
        require(data.types.getValue(DOMAIN_TYPE).all { DOMAIN_FIELDS[it.name] == it.type })
        for ((name, fields) in data.types) {
            require(name.length <= MAX_NAME && NAME.matches(name) && !isAtomic(name))
            require(fields.size <= MAX_FIELDS && fields.map { it.name }.toSet().size == fields.size)
            for (field in fields) {
                require(field.name.length <= MAX_NAME && NAME.matches(field.name))
                require(field.type.length <= MAX_NAME + MAX_DIMENSIONS * 6)
                val base = baseOf(field.type)
                require(isAtomic(base) || base in data.types)
                val dimensions = field.type.removePrefix(base)
                require(DIMENSIONS.matches(dimensions) && dimensions.count { it == '[' } <= MAX_DIMENSIONS)
                var type = field.type
                while (true) {
                    val array = ARRAY.matchEntire(type) ?: break
                    val length = array.groupValues[2]
                    require(length.isEmpty() || length.toInt() in 1..MAX_ITEMS)
                    type = array.groupValues[1]
                }
            }
        }
    }

    private fun isAtomic(type: String): Boolean = when {
        type == "address" || type == "bool" || type == "string" || type == "bytes" -> true
        type.startsWith("bytes") -> type.removePrefix("bytes").toIntOrNull()?.let { it in 1..32 } == true
        type.startsWith("uint") -> bitsOf(type.removePrefix("uint")) != null
        type.startsWith("int") -> bitsOf(type.removePrefix("int")) != null
        else -> false
    }

    private fun bitsOf(text: String): Int? =
        text.takeIf { it.isNotEmpty() && it[0] != '0' }?.toIntOrNull()?.takeIf { it in 8..256 && it % 8 == 0 }

    private fun hashStruct(
        types: Map<String, List<TypedField>>,
        type: String,
        value: Map<String, TypedValue>,
        depth: Int,
    ): ByteArray {
        require(depth <= MAX_DEPTH)
        val fields = types.getValue(type)
        // Every member present and nothing else: a value not in the type would be shown and not signed.
        require(value.keys == fields.map { it.name }.toSet())
        var encoded = keccak256(encodeType(types, type).encodeToByteArray())
        for (field in fields) encoded += encodeValue(types, field.type, value.getValue(field.name), depth + 1)
        return keccak256(encoded)
    }

    private fun encodeValue(
        types: Map<String, List<TypedField>>,
        type: String,
        value: TypedValue,
        depth: Int,
    ): ByteArray {
        require(depth <= MAX_DEPTH)
        ARRAY.matchEntire(type)?.let { array ->
            val items = (value as? TypedValue.Items)?.items ?: throw IllegalArgumentException("not an array")
            val length = array.groupValues[2]
            require(items.size <= MAX_ITEMS && (length.isEmpty() || items.size == length.toInt()))
            var concatenated = ByteArray(0)
            for (item in items) concatenated += encodeValue(types, array.groupValues[1], item, depth + 1)
            return keccak256(concatenated)
        }
        if (type in types) {
            val fields = (value as? TypedValue.Struct)?.fields ?: throw IllegalArgumentException("not a struct")
            return hashStruct(types, type, fields, depth)
        }
        return when {
            type == "address" -> {
                val text = (value as? TypedValue.Text)?.text?.takeIf(ADDRESS::matches)
                    ?: throw IllegalArgumentException("not an address")
                AbiWords.word(Address.of(text))
            }
            type == "bool" -> {
                val bool = (value as? TypedValue.Bool)?.value ?: throw IllegalArgumentException("not a bool")
                AbiWords.word(if (bool) 1L else 0L)
            }
            type == "string" -> {
                val text = (value as? TypedValue.Text)?.text ?: throw IllegalArgumentException("not a string")
                // A lone surrogate (JSON's "\uD800") has no UTF-8: encoding it would hash a replacement, not the text.
                require(!hasLoneSurrogate(text))
                keccak256(text.encodeToByteArray())
            }
            type == "bytes" -> keccak256(hexBytes(value))
            type.startsWith("bytes") -> {
                val bytes = hexBytes(value)
                require(bytes.size == type.removePrefix("bytes").toInt())
                bytes + ByteArray(32 - bytes.size)
            }
            type.startsWith("uint") -> unsigned(value, bitsOf(type.removePrefix("uint"))!!).toBytes32()
            type.startsWith("int") -> signed(value, bitsOf(type.removePrefix("int"))!!).toBytes32()
            else -> throw IllegalArgumentException("unknown type")
        }
    }

    private fun hasLoneSurrogate(text: String): Boolean {
        var at = 0
        while (at < text.length) {
            val char = text[at]
            when {
                char.isHighSurrogate() && text.getOrNull(at + 1)?.isLowSurrogate() == true -> at += 2
                char.isSurrogate() -> return true
                else -> at++
            }
        }
        return false
    }

    private fun hexBytes(value: TypedValue): ByteArray {
        val text = (value as? TypedValue.Text)?.text?.takeIf { HEX.matches(it) && it.length % 2 == 0 }
            ?: throw IllegalArgumentException("not hex bytes")
        return text.substring(2).hexToBytes()
    }

    /** [value] as an integer: its sign and its magnitude, from a JSON integer, a decimal string or `0x` hex. */
    private fun integer(value: TypedValue): Pair<Boolean, UInt256> {
        val text = when (value) {
            is TypedValue.Number -> value.digits
            is TypedValue.Text -> value.text
            else -> throw IllegalArgumentException("not an integer")
        }
        if (text.startsWith("0x")) {
            require(HEX.matches(text) && text.length in 3..66)
            val digits = text.substring(2).let { if (it.length % 2 == 1) "0$it" else it }
            return false to UInt256.fromBigEndian(digits.hexToBytes())
        }
        require(DECIMAL.matches(text) && text != "-0")
        val magnitude = UInt256.parse(text.removePrefix("-")) ?: throw IllegalArgumentException("too large")
        return text.startsWith("-") to magnitude
    }

    /**
     * An unsigned integer of typed data that [encode] checked (a JSON integer, a decimal string or `0x`
     * hex), read; throws on anything else.
     */
    fun unsignedOf(value: TypedValue): UInt256 = integer(value).second

    private fun unsigned(value: TypedValue, bits: Int): UInt256 {
        val (negative, magnitude) = integer(value)
        require(!negative && magnitude.bitLength() <= bits)
        return magnitude
    }

    /** [value] in two's complement over 256 bits, inside `int<bits>`'s range. */
    private fun signed(value: TypedValue, bits: Int): UInt256 {
        val (negative, magnitude) = integer(value)
        if (!negative) {
            require(magnitude.bitLength() <= bits - 1)
            return magnitude
        }
        // -2^(bits-1) is the lowest: a magnitude up to 2^(bits-1).
        require(magnitude.bitLength() <= bits - 1 || magnitude == powerOfTwo(bits - 1))
        return UInt256.MAX - magnitude + UInt256.ONE
    }

    private fun powerOfTwo(exponent: Int): UInt256 =
        UInt256.fromBigEndian(ByteArray(32).also { it[31 - exponent / 8] = (1 shl (exponent % 8)).toByte() })

    private fun UInt256.bitLength(): Int {
        val bytes = toBytes32()
        val first = bytes.indexOfFirst { it != 0.toByte() }.takeIf { it >= 0 } ?: return 0
        return (32 - first - 1) * 8 + (Int.SIZE_BITS - (bytes[first].toInt() and 0xff).countLeadingZeroBits())
    }
}

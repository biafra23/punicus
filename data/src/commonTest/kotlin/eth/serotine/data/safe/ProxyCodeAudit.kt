package eth.serotine.data.safe

/**
 * What a pinned SafeProxy's runtime code may do, checked over every path from the first byte: read
 * storage slot 0 and no other, `DELEGATECALL` to the address in it (masked to 160 bits, or as
 * 0.4.x getters read it, divided by `0x100**0`), and stop, return or revert. A reachable `SSTORE`,
 * `CALL`, `CALLCODE`, `STATICCALL`, `CREATE`/`CREATE2`, `SELFDESTRUCT`, `LOG*`, transient storage,
 * an unknown opcode, or a jump to anything but a constant `JUMPDEST` fails it; so does code that
 * never delegates at all.
 *
 * That is the property a proxy hash in [SafeDeployments] stands for: such a proxy does nothing of
 * its own, so whatever the account does is its slot 0 singleton's code, which detection requires
 * to be a pinned Safe singleton. The trailing Solidity metadata is never reached: every jump
 * target is a constant inside the code before it.
 *
 * A small symbolic walk, not an EVM: stack values are constants, slot 0's word, or opaque, and a
 * `JUMPI` whose condition folds to a constant takes only that branch (the selector dispatch of
 * the 0.4.x proxies compares calldata against constants, which stays opaque, so both branches are
 * walked). Returns the problems found; empty: the code passes.
 */
internal fun auditProxyCode(code: ByteArray): List<String> {
    val jumpDests = buildSet {
        var i = 0
        while (i < code.size) {
            val op = code[i].toInt() and 0xff
            if (op == 0x5b) add(i)
            i += 1 + pushSize(op)
        }
    }
    val problems = linkedSetOf<String>()
    var delegates = 0
    val seen = HashSet<Pair<Int, List<Value>>>()
    val work = ArrayDeque<Pair<Int, List<Value>>>().apply { add(0 to emptyList()) }
    while (work.isNotEmpty()) {
        val (start, initial) = work.removeLast()
        if (!seen.add(start to initial)) continue
        // Fail closed: a walk too large to finish is no proxy this audit can vouch for.
        if (seen.size > MAX_STATES) { problems += "more than $MAX_STATES states: not walked to the end"; break }
        val stack = initial.toMutableList()
        var pc = start
        walk@ while (pc < code.size) {
            val op = code[pc].toInt() and 0xff
            when {
                op == 0x5f -> stack += Value.Const.ZERO
                op in 0x60..0x7f -> {
                    val n = pushSize(op)
                    stack += code.copyOfRange(pc + 1, minOf(pc + 1 + n, code.size)).toBigNat()
                    pc += 1 + n
                    continue@walk
                }
                op in 0x80..0x8f -> stack += stack[stack.size - (op - 0x7f)]
                op in 0x90..0x9f -> {
                    val k = op - 0x8f
                    val top = stack.size - 1
                    stack[top] = stack[top - k].also { stack[top - k] = stack[top] }
                }
                op in FORBIDDEN -> { problems += "${FORBIDDEN[op]} reachable at $pc"; break@walk }
                op == 0x00 || op == 0xf3 || op == 0xfd || op == 0xfe -> break@walk
                op == 0x56 -> {
                    val target = (stack.removeLast() as? Value.Const)?.int
                    if (target == null || target !in jumpDests) { problems += "dynamic JUMP at $pc"; break@walk }
                    pc = target
                    continue@walk
                }
                op == 0x57 -> {
                    val target = (stack.removeLast() as? Value.Const)?.int
                    val condition = stack.removeLast()
                    if (target == null || target !in jumpDests) { problems += "dynamic JUMPI at $pc"; break@walk }
                    val zero = (condition as? Value.Const)?.isZero
                    if (zero != true) work += target to stack.toList()
                    if (zero == false) break@walk
                }
                op == 0x54 -> {
                    if (stack.removeLast() != Value.Const.ZERO) problems += "SLOAD of another slot at $pc"
                    stack += Value.Slot0
                }
                op == 0xf4 -> {
                    stack.removeLast()
                    val target = stack.removeLast()
                    repeat(4) { stack.removeLast() }
                    if (!target.isSlot0Address()) problems += "DELEGATECALL to something other than slot 0 at $pc"
                    delegates++
                    stack += Value.Opaque
                }
                op !in ARITY -> { problems += "unknown opcode $op at $pc"; break@walk }
                else -> {
                    val (inputs, outputs) = ARITY.getValue(op)
                    val args = List(inputs) { stack.removeLast() }
                    if (outputs == 1) stack += fold(op, args)
                }
            }
            pc++
        }
    }
    if (delegates == 0) problems += "no DELEGATECALL reachable"
    return problems.toList()
}

private sealed interface Value {
    /** A constant word, as its big-endian bytes without leading zeros. */
    data class Const(val bytes: List<Byte>) : Value {
        val isZero get() = bytes.isEmpty()
        val int: Int? get() = if (bytes.size > 3) null else bytes.fold(0) { a, b -> (a shl 8) or (b.toInt() and 0xff) }

        companion object {
            val ZERO = Const(emptyList())
            val ONE = Const(listOf(1.toByte()))
            val ADDRESS_MASK = Const(List(20) { 0xff.toByte() })
        }
    }

    /** The word in storage slot 0. */
    data object Slot0 : Value

    /** Slot 0 masked to an address: `AND(0xff…ff (20 bytes), slot 0)`, or divided by one. */
    data object Slot0Address : Value

    data object Opaque : Value
}

private fun Value.isSlot0Address() = this == Value.Slot0 || this == Value.Slot0Address

private fun fold(op: Int, args: List<Value>): Value = when (op) {
    // AND with the 160-bit mask, either side.
    0x16 -> when {
        args[0] == Value.Const.ADDRESS_MASK && args[1].isSlot0Address() -> Value.Slot0Address
        args[1] == Value.Const.ADDRESS_MASK && args[0].isSlot0Address() -> Value.Slot0Address
        else -> Value.Opaque
    }
    // DIV by one (0.4.x: `sload(0) / 0x100**0`): `0x100**0` folds to one below.
    0x04 -> if (args[0].isSlot0Address() && args[1] == Value.Const.ONE) args[0] else Value.Opaque
    // EXP of constants: only `x**0` = 1 is needed.
    0x0a -> if ((args[1] as? Value.Const)?.isZero == true) Value.Const.ONE else Value.Opaque
    // SHL and SUB of constants: solc ≥ 0.5.12 builds the address mask as `(1 << 160) - 1`.
    0x1b -> constants(args) { (shift, value) -> value.shl(shift) }
    0x03 -> constants(args) { (a, b) -> a.minus(b) }
    // ISZERO of a constant; anything else stays opaque, so both of its branches are walked.
    0x15 -> (args[0] as? Value.Const)?.let { if (it.isZero) Value.Const.ONE else Value.Const.ZERO } ?: Value.Opaque
    else -> Value.Opaque
}

/** Proxies are a few hundred bytes: a walk this large is no proxy's. */
private const val MAX_STATES = 20_000

private fun ByteArray.toBigNat(): Value.Const = Value.Const(dropWhile { it == 0.toByte() })

/** [op] over [args] when every one is a constant, as 32-byte big-endian words; opaque otherwise. */
private inline fun constants(args: List<Value>, op: (List<ByteArray>) -> ByteArray): Value {
    val words = args.map { (it as? Value.Const)?.word() ?: return Value.Opaque }
    return op(words).toBigNat()
}

private fun Value.Const.word(): ByteArray = ByteArray(32 - bytes.size) + bytes.toByteArray()

/** [this] word shifted left by [shift] bits, modulo 2²⁵⁶ (EVM `SHL`). */
private fun ByteArray.shl(shift: ByteArray): ByteArray {
    val n = Value.Const(shift.dropWhile { it == 0.toByte() }).int ?: return ByteArray(32)
    if (n >= 256) return ByteArray(32)
    val out = ByteArray(32)
    for (bit in 0 until 256 - n) {
        // Bit `bit` counted from the least significant end moves to `bit + n`.
        val from = 31 - bit / 8
        if ((this[from].toInt() shr (bit % 8)) and 1 == 1) {
            val to = bit + n
            out[31 - to / 8] = (out[31 - to / 8].toInt() or (1 shl (to % 8))).toByte()
        }
    }
    return out
}

/** [this] minus [other], modulo 2²⁵⁶ (EVM `SUB`). */
private fun ByteArray.minus(other: ByteArray): ByteArray {
    val out = ByteArray(32)
    var borrow = 0
    for (i in 31 downTo 0) {
        val d = (this[i].toInt() and 0xff) - (other[i].toInt() and 0xff) - borrow
        borrow = if (d < 0) 1 else 0
        out[i] = (d and 0xff).toByte()
    }
    return out
}

private fun pushSize(op: Int) = if (op in 0x60..0x7f) op - 0x5f else 0

private val FORBIDDEN = mapOf(
    0x55 to "SSTORE", 0xf0 to "CREATE", 0xf1 to "CALL", 0xf2 to "CALLCODE", 0xf5 to "CREATE2",
    0xfa to "STATICCALL", 0xff to "SELFDESTRUCT", 0xa0 to "LOG0", 0xa1 to "LOG1", 0xa2 to "LOG2",
    0xa3 to "LOG3", 0xa4 to "LOG4", 0x3c to "EXTCODECOPY", 0x5c to "TLOAD", 0x5d to "TSTORE",
)

/** Inputs and outputs of every other opcode this walk lets through. */
private val ARITY: Map<Int, Pair<Int, Int>> = buildMap {
    listOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0a, 0x0b, 0x10, 0x11, 0x12, 0x13, 0x14, 0x16, 0x17,
        0x18, 0x1a, 0x1b, 0x1c, 0x1d, 0x20).forEach { put(it, 2 to 1) }
    listOf(0x08, 0x09).forEach { put(it, 3 to 1) }
    listOf(0x15, 0x19, 0x31, 0x35, 0x3b, 0x3f, 0x40, 0x51).forEach { put(it, 1 to 1) }
    listOf(0x30, 0x32, 0x33, 0x34, 0x36, 0x38, 0x3a, 0x3d, 0x41, 0x42, 0x43, 0x45, 0x46, 0x47, 0x48, 0x58,
        0x59, 0x5a).forEach { put(it, 0 to 1) }
    listOf(0x37, 0x39, 0x3e, 0x5e).forEach { put(it, 3 to 0) }
    put(0x50, 1 to 0); put(0x52, 2 to 0); put(0x53, 2 to 0); put(0x5b, 0 to 0)
}

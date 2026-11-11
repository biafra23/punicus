package eth.serotine.data.privacypools

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertIs

// What the iOS tests of the binding read and compare with (PrivacyPoolsIosTest,
// PrivacyPoolsIosProvingTest): native/privacy-pools's files, where the build left them
// (PRIVACY_POOLS_DIR, which :data generates; the simulator reads the Mac's files).

/** The bytes of a file of native/privacy-pools. */
internal fun poolFile(relative: String): ByteArray =
    SystemFileSystem.source(Path(PRIVACY_POOLS_DIR, relative)).buffered().use { it.readByteArray() }

/** A reference vector of tests/vectors. */
internal fun poolVector(name: String): JsonElement =
    Json.parseToJsonElement(poolFile("tests/vectors/$name").decodeToString())

/** A decimal field element as 32 big-endian bytes. */
internal fun toField(decimal: String): ByteArray {
    val digits = IntArray(32)
    for (c in decimal) {
        require(c in '0'..'9') { "not a decimal: $decimal" }
        var carry = c - '0'
        for (i in 31 downTo 0) {
            val v = digits[i] * 10 + carry
            digits[i] = v and 0xff
            carry = v shr 8
        }
        require(carry == 0) { "wider than 256 bits: $decimal" }
    }
    return ByteArray(32) { digits[it].toByte() }
}

internal fun toField(n: Long): ByteArray = toField(n.toString())

internal fun hex(fields: List<ByteArray>): List<String> =
    fields.map { f -> f.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } }

internal fun JsonObject.element(key: String): ByteArray = toField(getValue(key).jsonPrimitive.content)

internal fun JsonObject.elements(key: String): List<ByteArray> =
    getValue(key).jsonArray.map { toField(it.jsonPrimitive.content) }

internal fun <T> ok(result: NativeResult<T>): T = assertIs<NativeResult.Ok<T>>(result).value

internal fun refused(result: NativeResult<*>): NativeError = assertIs<NativeResult.Refused>(result).error

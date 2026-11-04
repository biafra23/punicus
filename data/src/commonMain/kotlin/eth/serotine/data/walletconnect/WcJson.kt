package eth.serotine.data.walletconnect

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * JSON from outside this device (the relay's frames, a dApp's messages, typed data inside them), read
 * with its nesting bounded first. kotlinx's reader recurses once per level, so a document nested a few
 * thousand deep would overflow the stack of the relay client's reader; this one measures the nesting
 * with a scan that does not recurse, and reads only what is at most [MAX_DEPTH] deep.
 */
internal object WcJson {

    /** The deepest JSON read: deeper than any message of the protocol or any method's parameters. */
    const val MAX_DEPTH = 64

    /** [text] as JSON, or null when it is not JSON or nests deeper than [maxDepth]. */
    fun parse(text: String, maxDepth: Int = MAX_DEPTH): JsonElement? {
        if (depthOf(text) > maxDepth) return null
        return try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** How deep [text]'s brackets nest, those inside strings not counted. */
    fun depthOf(text: String): Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var escaped = false
        for (char in text) {
            when {
                inString && escaped -> escaped = false
                inString && char == '\\' -> escaped = true
                inString && char == '"' -> inString = false
                inString -> Unit
                char == '"' -> inString = true
                char == '[' || char == '{' -> {
                    depth++
                    if (depth > deepest) deepest = depth
                }
                char == ']' || char == '}' -> depth--
            }
        }
        return deepest
    }
}

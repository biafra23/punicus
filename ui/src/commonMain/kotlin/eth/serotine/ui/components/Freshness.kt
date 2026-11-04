package eth.serotine.ui.components

/** An age of [seconds] as a screen says it: "8 s", "2 min", "3 h". */
internal fun ageOf(seconds: Long): String = when {
    seconds < 120 -> "$seconds s"
    seconds < 7_200 -> "${seconds / 60} min"
    else -> "${seconds / 3_600} h"
}

/** "just now" or "2 min ago": how long before [now] a time [at] was, both in unix seconds. */
internal fun agoText(at: Long, now: Long): String {
    val seconds = now - at
    return if (seconds < 1) "just now" else "${ageOf(seconds)} ago"
}

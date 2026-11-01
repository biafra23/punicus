package eth.serotine.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.jvm.JvmName

/**
 * What an amount reads while amounts are hidden (docs/wallet-ux.md, *Hiding amounts*): one width
 * whatever the amount, so neither its digits nor its length show. The unit beside it stays
 * ("•••• ETH", "€••••"): what is held shows, how much does not.
 */
const val AMOUNT_MASK = "••••"

/** Test tag of the eye that hides and shows amounts, for UI tests. */
const val HIDE_AMOUNTS_TAG = "hide-amounts"

/**
 * [text], an amount as a screen shows it ("0.5 ETH", "€12,025.55", "≈ €3.10 · Uniswap 30-min
 * average", "< 0.0001 ETH"), with its figure masked: the number and its sign become [AMOUNT_MASK],
 * and a "< " goes; the unit, a "≈ " and words stay. An amount has one figure, its first: digits after
 * it belong to the unit ("1INCH"). Only the part before the first " · " is an amount: what follows
 * qualifies it ("price 2 h old") and is left as it is. A text with no figure in that part
 * ("Checking…", "No price", "no real value") is returned unchanged.
 */
fun maskAmount(text: String): String {
    val cut = text.indexOf(QUALIFIER).let { if (it < 0) text.length else it }
    val amount = text.substring(0, cut)
    if (!FIGURE.containsMatchIn(amount)) return text
    // "< 0.0001 ETH", "≈ < €0.01": that it is tiny is how much, too.
    return FIGURE.replaceFirst(amount.replace(LESS_THAN, ""), AMOUNT_MASK) + text.substring(cut)
}

/** [maskAmount] of a text that may be absent. Its own JVM name: on the JVM the two would clash. */
@JvmName("maskAmountOrNull")
fun maskAmount(text: String?): String? = text?.let { maskAmount(it) }

/** What a screen says when the eye's choice could not be saved: nothing changed. */
const val AMOUNTS_NOT_SAVED = "Couldn't save whether amounts are hidden. Nothing was changed."

/** What separates an amount from what qualifies it. */
private const val QUALIFIER = " · "

/** What stands in front of an amount too small to show ("< 0.0001 ETH"). */
private const val LESS_THAN = "< "

/** A figure: a sign, then digits with their grouping and decimal point, and the "+" of a value too large to show. */
private val FIGURE = Regex("""[+\-−]?\d[\d,.]*\+?""")

/**
 * The eye at the top left of a screen that shows amounts: open while they show, closed (and tinted,
 * so the mode is noticed) while they are hidden. A tap is [onToggle]. The screen draws it in its top
 * bar, beside Back where it has one.
 */
@Composable
fun HideAmountsButton(hidden: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onToggle,
        modifier = modifier.testTag(HIDE_AMOUNTS_TAG),
        colors = if (hidden) {
            IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            IconButtonDefaults.iconButtonColors()
        },
    ) {
        Icon(
            if (hidden) ClosedEye else SerotineIcons.Visibility,
            contentDescription = if (hidden) SHOW_AMOUNTS else HIDE_AMOUNTS,
        )
    }
}

/** What the eye says to a screen reader while amounts show. */
const val HIDE_AMOUNTS = "Hide amounts"

/** What the eye says to a screen reader while amounts are hidden. */
const val SHOW_AMOUNTS = "Show amounts"

/**
 * A closed eye: the lower lid's curve with four lashes, drawn as 2-unit round strokes in a 24-unit
 * square, the weight of the Material Symbols beside it. Not a Material Symbol (none is a closed
 * eye), so it is not in [SerotineIcons].
 */
private val ClosedEye: ImageVector by lazy {
    val builder = ImageVector.Builder("ClosedEye", 24.dp, 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    listOf(
        "M2 10s3.5 5 10 5 10-5 10-5",
        "M4.6 13.1L3 15.2",
        "M9.2 14.8l-0.6 2.5",
        "M14.8 14.8l0.6 2.5",
        "M19.4 13.1L21 15.2",
    ).forEach { path ->
        builder.addPath(
            addPathNodes(path),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    builder.build()
}

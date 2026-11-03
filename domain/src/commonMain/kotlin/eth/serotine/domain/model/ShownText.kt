package eth.serotine.domain.model

/**
 * Whether text a dApp sent draws as what it says (docs/walletconnect.md, *Signing in*): no control
 * character but a line break or a tab, no formatting character (a right-to-left override, a zero-width
 * space), no line or paragraph separator, and no other code point Unicode says draws nothing (its
 * default-ignorable ones: the tag characters, the variation selectors, the Hangul fillers among them).
 * Text that does not is shown otherwise: a message as hex, a typed-data string with each such character
 * spelled out ([escaped]).
 */
object ShownText {

    // Characters that draw nothing, or draw a line break that is not one: a control, a formatting
    // character (a right-to-left override, a zero-width space), a line or paragraph separator.
    private val HIDDEN = setOf(
        CharCategory.CONTROL,
        CharCategory.FORMAT,
        CharCategory.LINE_SEPARATOR,
        CharCategory.PARAGRAPH_SEPARATOR,
    )

    // Unicode's Default_Ignorable_Code_Point (DerivedCoreProperties.txt): drawn as nothing even by a
    // font that lacks them. Some are formatting characters, caught above already; the others are
    // marks or letters (U+034F, the variation selectors, the Hangul fillers), or outside the BMP,
    // where Char.category sees only a surrogate (the tag characters, U+E0000 to U+E0FFF).
    private val DEFAULT_IGNORABLE = listOf(
        0x00AD..0x00AD, 0x034F..0x034F, 0x061C..0x061C, 0x115F..0x1160, 0x17B4..0x17B5,
        0x180B..0x180F, 0x200B..0x200F, 0x202A..0x202E, 0x2060..0x206F, 0x3164..0x3164,
        0xFE00..0xFE0F, 0xFEFF..0xFEFF, 0xFFA0..0xFFA0, 0xFFF0..0xFFF8, 0x1BCA0..0x1BCA3,
        0x1D173..0x1D17A, 0xE0000..0xE0FFF,
    )

    /** Whether every code point of [text] draws as itself (line breaks and tabs among them). */
    fun isPlain(text: String): Boolean = codePoints(text).none { (codePoint, _) -> hidden(codePoint) }

    /**
     * [text] with each code point that draws nothing spelled out as `⟨U+XXXX⟩`, and line breaks and tabs
     * as `⟨LF⟩`, `⟨CR⟩` and `⟨TAB⟩`: for a value shown on one line, where they would hide or move text.
     */
    fun escaped(text: String): String = buildString {
        for ((codePoint, chars) in codePoints(text)) {
            when {
                codePoint == '\n'.code -> append("⟨LF⟩")
                codePoint == '\r'.code -> append("⟨CR⟩")
                codePoint == '\t'.code -> append("⟨TAB⟩")
                hidden(codePoint) -> append("⟨U+${codePoint.toString(16).uppercase().padStart(4, '0')}⟩")
                else -> append(chars)
            }
        }
    }

    private fun hidden(codePoint: Int): Boolean = when {
        codePoint == '\n'.code || codePoint == '\r'.code || codePoint == '\t'.code -> false
        // A lone surrogate, which JSON's \uD800 can spell: it draws as a replacement mark, not as itself.
        codePoint in 0xD800..0xDFFF -> true
        codePoint <= 0xFFFF && codePoint.toChar().category in HIDDEN -> true
        else -> DEFAULT_IGNORABLE.any { codePoint in it }
    }

    /** Each code point of [text], with the chars that spell it; a lone surrogate stands for itself, hidden. */
    private fun codePoints(text: String): Sequence<Pair<Int, String>> = sequence {
        var at = 0
        while (at < text.length) {
            val char = text[at]
            val low = text.getOrNull(at + 1)
            if (char.isHighSurrogate() && low != null && low.isLowSurrogate()) {
                yield(0x10000 + ((char.code - 0xD800) shl 10) + (low.code - 0xDC00) to text.substring(at, at + 2))
                at += 2
            } else {
                yield(char.code to char.toString())
                at += 1
            }
        }
    }
}

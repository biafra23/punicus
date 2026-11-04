package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShownTextTest {

    @Test
    fun hiddenCharactersAreSpelledOutAndTheRestKept() {
        assertTrue(ShownText.isPlain("USD Coin\n\tv2 \uD83D\uDC4B"))
        assertEquals("USD\u27E8U+200B\u27E9C", ShownText.escaped("USD\u200BC"))
        assertEquals("pay \u27E8U+202E\u27E9evil", ShownText.escaped("pay \u202Eevil"))
        assertEquals("a\u27E8U+E0041\u27E9b", ShownText.escaped("a\uDB40\uDC41b"))
        assertEquals("one\u27E8LF\u27E9two\u27E8TAB\u27E9", ShownText.escaped("one\ntwo\t"))
        assertEquals("gm \uD83D\uDC4B", ShownText.escaped("gm \uD83D\uDC4B"))
        // A lone surrogate, which JSON's \uD800 can spell, draws as a replacement mark: hidden.
        assertFalse(ShownText.isPlain("a\uD800b"))
        assertEquals("a\u27E8U+D800\u27E9b", ShownText.escaped("a\uD800b"))
    }
}

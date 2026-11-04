package eth.serotine.ui.tokens

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SimpleTokensScreenPreviews.kt. */
class SimpleTokensScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("simpleTokensScreen", simpleTokensScreenPreviews)
}

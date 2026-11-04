package eth.serotine.ui.swap

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SimpleSwapScreenPreviews.kt. */
class SimpleSwapScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("simpleSwapScreen", simpleSwapScreenPreviews)
}

package eth.serotine.ui.swap

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in SimpleSwapScreenPreviews.kt. */
class SimpleSwapScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.simpleSwap)
}

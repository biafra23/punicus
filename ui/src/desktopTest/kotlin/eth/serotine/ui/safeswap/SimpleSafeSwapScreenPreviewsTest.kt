package eth.serotine.ui.safeswap

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in SimpleSafeSwapScreenPreviews.kt. */
class SimpleSafeSwapScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.simpleSafeSwap)
}

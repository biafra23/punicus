package eth.serotine.ui.safeswap

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in SafeSwapScreenPreviews.kt. */
class SafeSwapScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.safeSwap)
}

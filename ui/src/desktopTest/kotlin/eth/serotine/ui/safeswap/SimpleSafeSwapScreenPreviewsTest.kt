package eth.serotine.ui.safeswap

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SimpleSafeSwapScreenPreviews.kt. */
class SimpleSafeSwapScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("simpleSafeSwapScreen", simpleSafeSwapScreenPreviews)
}

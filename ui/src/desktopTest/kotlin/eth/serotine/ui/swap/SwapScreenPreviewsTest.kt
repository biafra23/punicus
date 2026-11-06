package eth.serotine.ui.swap

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in SwapScreenPreviews.kt. */
class SwapScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.swap)
}

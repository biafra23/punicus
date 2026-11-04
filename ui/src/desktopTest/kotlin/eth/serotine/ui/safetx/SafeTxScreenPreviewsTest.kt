package eth.serotine.ui.safetx

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in SafeTxScreenPreviews.kt. */
class SafeTxScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.safeTx)
}

package eth.serotine.ui.safetx

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SimpleSafeTxScreenPreviews.kt. */
class SimpleSafeTxScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("simpleSafeTxScreen", simpleSafeTxScreenPreviews)
}

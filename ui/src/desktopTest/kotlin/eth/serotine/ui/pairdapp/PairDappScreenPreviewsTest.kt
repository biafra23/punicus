package eth.serotine.ui.pairdapp

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in PairDappScreenPreviews.kt. */
class PairDappScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("pairDappScreen", pairDappScreenPreviews)
}

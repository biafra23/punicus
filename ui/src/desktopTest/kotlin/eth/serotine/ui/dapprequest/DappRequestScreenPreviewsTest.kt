package eth.serotine.ui.dapprequest

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in DappRequestScreenPreviews.kt. */
class DappRequestScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.dappRequest)
}

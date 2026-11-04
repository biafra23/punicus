package eth.serotine.ui.addtoken

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in AddTokenScreenPreviews.kt. */
class AddTokenScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.addToken)
}

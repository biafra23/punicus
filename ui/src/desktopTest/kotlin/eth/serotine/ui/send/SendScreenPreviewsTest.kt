package eth.serotine.ui.send

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in SendScreenPreviews.kt. */
class SendScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.send)
}

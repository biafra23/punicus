package eth.serotine.ui.send

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in SimpleSendScreenPreviews.kt. */
class SimpleSendScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.simpleSend)
}

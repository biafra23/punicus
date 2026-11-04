package eth.serotine.ui.send

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SimpleSendScreenPreviews.kt. */
class SimpleSendScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("simpleSendScreen", simpleSendScreenPreviews)
}

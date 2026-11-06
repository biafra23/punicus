package eth.serotine.ui.update

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in UpdateRequiredScreenPreviews.kt. */
class UpdateRequiredScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.updateRequired)
}

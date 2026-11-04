package eth.serotine.ui.activitydetail

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in SimpleActivityDetailScreenPreviews.kt. */
class SimpleActivityDetailScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.simpleActivityDetail)
}

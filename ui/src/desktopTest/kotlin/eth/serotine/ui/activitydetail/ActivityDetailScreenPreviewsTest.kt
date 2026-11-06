package eth.serotine.ui.activitydetail

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in ActivityDetailScreenPreviews.kt. */
class ActivityDetailScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.activityDetail)
}

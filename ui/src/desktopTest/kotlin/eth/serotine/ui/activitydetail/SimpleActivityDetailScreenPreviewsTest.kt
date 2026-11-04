package eth.serotine.ui.activitydetail

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SimpleActivityDetailScreenPreviews.kt. */
class SimpleActivityDetailScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("simpleActivityDetailScreen", simpleActivityDetailScreenPreviews)
}

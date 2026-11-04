package eth.serotine.ui.accountdetail

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SimpleAccountDetailScreenPreviews.kt. */
class SimpleAccountDetailScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("simpleAccountDetailScreen", simpleAccountDetailScreenPreviews)
}

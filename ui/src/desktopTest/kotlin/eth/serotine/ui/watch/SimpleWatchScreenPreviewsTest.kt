package eth.serotine.ui.watch

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SimpleWatchScreenPreviews.kt. */
class SimpleWatchScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("simpleWatchScreen", simpleWatchScreenPreviews)
}

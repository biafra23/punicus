package eth.serotine.ui.addtoken

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SimpleAddTokenScreenPreviews.kt. */
class SimpleAddTokenScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("simpleAddTokenScreen", simpleAddTokenScreenPreviews)
}

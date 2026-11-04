package eth.serotine.ui.createsafe

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SimpleCreateSafeScreenPreviews.kt. */
class SimpleCreateSafeScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("simpleCreateSafeScreen", simpleCreateSafeScreenPreviews)
}

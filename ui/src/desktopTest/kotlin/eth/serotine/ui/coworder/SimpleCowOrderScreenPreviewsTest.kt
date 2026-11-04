package eth.serotine.ui.coworder

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SimpleCowOrderScreenPreviews.kt. */
class SimpleCowOrderScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("simpleCowOrderScreen", simpleCowOrderScreenPreviews)
}

package eth.serotine.ui.createsafe

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in CreateSafeScreenPreviews.kt. */
class CreateSafeScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.createSafe)
}

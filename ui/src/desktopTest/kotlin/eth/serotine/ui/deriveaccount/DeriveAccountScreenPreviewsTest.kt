package eth.serotine.ui.deriveaccount

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in DeriveAccountScreenPreviews.kt. */
class DeriveAccountScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.deriveAccount)
}

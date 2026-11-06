package eth.serotine.ui.createaccount

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in CreateAccountScreenPreviews.kt. */
class CreateAccountScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.createAccount)
}

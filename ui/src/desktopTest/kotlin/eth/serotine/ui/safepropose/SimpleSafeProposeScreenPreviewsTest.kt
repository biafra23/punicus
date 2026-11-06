package eth.serotine.ui.safepropose

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in SimpleSafeProposeScreenPreviews.kt. */
class SimpleSafeProposeScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.simpleSafePropose)
}

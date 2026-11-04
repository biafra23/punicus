package eth.serotine.ui.safepropose

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SimpleSafeProposeScreenPreviews.kt. */
class SimpleSafeProposeScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("simpleSafeProposeScreen", simpleSafeProposeScreenPreviews)
}

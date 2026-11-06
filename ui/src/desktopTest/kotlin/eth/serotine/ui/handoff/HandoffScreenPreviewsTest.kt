package eth.serotine.ui.handoff

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in HandoffScreenPreviews.kt. */
class HandoffScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("handoffScreen", handoffScreenPreviews)
}

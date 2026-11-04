package eth.serotine.ui.transferstatus

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in TransferStatusScreenPreviews.kt. */
class TransferStatusScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.transferStatus)
}

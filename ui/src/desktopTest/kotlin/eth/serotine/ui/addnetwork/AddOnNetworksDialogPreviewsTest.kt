package eth.serotine.ui.addnetwork

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in AddOnNetworksDialogPreviews.kt. */
class AddOnNetworksDialogPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.addOnNetworksDialog)
}

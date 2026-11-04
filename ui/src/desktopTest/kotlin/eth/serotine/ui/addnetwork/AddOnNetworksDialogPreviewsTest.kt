package eth.serotine.ui.addnetwork

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in AddOnNetworksDialogPreviews.kt. */
class AddOnNetworksDialogPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("addOnNetworksDialog", addOnNetworksDialogPreviews)
}

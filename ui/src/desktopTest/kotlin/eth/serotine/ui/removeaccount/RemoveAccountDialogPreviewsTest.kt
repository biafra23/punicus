package eth.serotine.ui.removeaccount

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in RemoveAccountDialogPreviews.kt. */
class RemoveAccountDialogPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.removeAccountDialog)
}

package eth.serotine.ui.renameaccount

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in RenameAccountDialogPreviews.kt. */
class RenameAccountDialogPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.renameAccountDialog)
}

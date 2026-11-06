package eth.serotine.ui.components

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in ScreenCaptureWarningDialogPreviews.kt. */
class ScreenCaptureWarningDialogPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.screenCaptureWarningDialog)
}

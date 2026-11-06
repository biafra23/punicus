package eth.serotine.ui.safeimport

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in SafeImportScreenPreviews.kt. */
class SafeImportScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.safeImport)
}

package eth.serotine.ui.safeimport

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SimpleSafeImportScreenPreviews.kt. */
class SimpleSafeImportScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("simpleSafeImportScreen", simpleSafeImportScreenPreviews)
}

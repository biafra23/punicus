package eth.serotine.ui.mnemonicimport

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in MnemonicImportScreenPreviews.kt. */
class MnemonicImportScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.mnemonicImport)
}

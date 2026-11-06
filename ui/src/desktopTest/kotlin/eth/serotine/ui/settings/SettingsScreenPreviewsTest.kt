package eth.serotine.ui.settings

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in SettingsScreenPreviews.kt. */
class SettingsScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.settings)
}

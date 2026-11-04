package eth.serotine.ui.safesettings

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SimpleSafeSettingsScreenPreviews.kt. */
class SimpleSafeSettingsScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("simpleSafeSettingsScreen", simpleSafeSettingsScreenPreviews)
}

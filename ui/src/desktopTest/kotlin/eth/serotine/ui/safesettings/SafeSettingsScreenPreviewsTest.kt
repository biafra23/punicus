package eth.serotine.ui.safesettings

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SafeSettingsScreenPreviews.kt. */
class SafeSettingsScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("safeSettingsScreen", safeSettingsScreenPreviews)
}

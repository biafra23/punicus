package eth.serotine.ui.settings

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SimpleSettingsScreenPreviews.kt. */
class SimpleSettingsScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("simpleSettingsScreen", simpleSettingsScreenPreviews)
}

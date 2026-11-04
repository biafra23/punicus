package eth.serotine.ui.ratewidget

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in WidgetPairScreenPreviews.kt. */
class WidgetPairScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("widgetPairScreen", widgetPairScreenPreviews)
}

package eth.serotine.ui.connections

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in ConnectionsScreenPreviews.kt. */
class ConnectionsScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("connectionsScreen", connectionsScreenPreviews)
}

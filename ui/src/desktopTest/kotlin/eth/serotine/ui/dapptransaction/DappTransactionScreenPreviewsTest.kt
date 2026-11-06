package eth.serotine.ui.dapptransaction

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in DappTransactionScreenPreviews.kt. */
class DappTransactionScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.dappTransaction)
}

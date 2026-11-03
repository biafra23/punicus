package eth.serotine.ui.dapptransaction

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in DappTransactionScreenPreviews.kt. */
class DappTransactionScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("dappTransactionScreen", dappTransactionScreenPreviews)
}

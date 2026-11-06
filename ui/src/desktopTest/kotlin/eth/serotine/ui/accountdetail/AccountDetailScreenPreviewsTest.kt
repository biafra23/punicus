package eth.serotine.ui.accountdetail

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in AccountDetailScreenPreviews.kt. */
class AccountDetailScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.accountDetail)
}

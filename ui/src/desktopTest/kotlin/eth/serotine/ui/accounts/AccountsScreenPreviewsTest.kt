package eth.serotine.ui.accounts

import eth.serotine.ui.renderEach
import eth.serotine.ui.Screens
import kotlin.test.Test

/** Renders every preview in AccountsScreenPreviews.kt. */
class AccountsScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(Screens.accounts)
}

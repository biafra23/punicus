package eth.serotine.ui.accounts

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SimpleAccountsScreenPreviews.kt. */
class SimpleAccountsScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("simpleAccountsScreen", simpleAccountsScreenPreviews)
}

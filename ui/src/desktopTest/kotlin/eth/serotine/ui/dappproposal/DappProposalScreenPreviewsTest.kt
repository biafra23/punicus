package eth.serotine.ui.dappproposal

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in DappProposalScreenPreviews.kt. */
class DappProposalScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("dappProposalScreen", dappProposalScreenPreviews)
}

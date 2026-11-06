package eth.serotine.data.handoff

import eth.serotine.domain.repository.HandoffAvailability
import eth.serotine.domain.repository.HandoffOpening
import eth.serotine.domain.repository.WalletHandoff

/**
 * The [WalletHandoff] of a platform that hands no payment link to another wallet: the desktop, and
 * for now iOS (issue #255), where several apps may claim `ethereum:` and the one opened is
 * undefined, and whose engine's typed reads have no verified block body to find the transfer in.
 * Send from another wallet is then left out.
 */
object NoWalletHandoff : WalletHandoff {
    override fun availability(): HandoffAvailability = HandoffAvailability.NOT_ON_THIS_DEVICE

    override suspend fun open(link: String): HandoffOpening = HandoffOpening.NO_WALLET
}

package eth.serotine.domain.usecase

import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SafeListing
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.TransactionProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Lists every Safe this wallet deployed (docs/wallet-ux.md, screen 15), whatever screen is open:
 * for each recorded deployment ([SentAction.SafeDeployment]) whose listing has not ended, it waits
 * for the verified receipt, then lists the Safe ([ListDeployedSafe]: once the light client proves
 * it after a deployment that succeeded; [ListDeployedSafe.afterRevert], which reads the address
 * once, after one that reverted), and records how that ended in the deployment's own record
 * ([SentAction.SafeDeployment.listed]), which the status screen shows. A Safe in the account list
 * already ends it as listed at once.
 *
 * Run for as long as the app is open (the hosts start it, never `App`): it picks up after a restart, as the record
 * is kept until the listing ends ([SentTransfers.prune]). An outcome that is no ending — the Safe
 * not verifiable again, the list not saved, a use case that broke its promise — is tried again
 * after [retryDelay]. A deployment is followed for [DEPLOYMENT_LIFETIME_SECONDS] after a peer took
 * it, and no longer (one never mined has no receipt to wait for); [clock] is in unix seconds. One
 * whose listing ended is never listed again: a Safe the user removes since stays removed.
 */
class FollowSafeDeployments(
    private val sentTransfers: SentTransfers,
    private val receipts: ReceiptTracker,
    private val accounts: AccountRepository,
    private val listDeployedSafe: ListDeployedSafe,
    private val clock: () -> Long = ::epochSecondsNow,
    private val retryDelay: Duration = 30.seconds,
) {
    /** Runs until cancelled. */
    suspend operator fun invoke() {
        sentTransfers.observe()
            .readEach(
                keys = { list ->
                    (list as? SentTransferList.Loaded)?.transfers.orEmpty().filter(::unlisted).toSet()
                },
                read = { transfer -> flow { emit(follow(transfer)) } },
            )
            .collect()
    }

    /** Whether [transfer] is a Safe's deployment whose listing has not ended. */
    private fun unlisted(transfer: SentTransfer): Boolean =
        (transfer.action as? SentAction.SafeDeployment)?.let { it.listed == null } == true

    /** Lists [transfer]'s Safe and records how that ended, or gives up at the end of its lifetime. */
    private suspend fun follow(transfer: SentTransfer) {
        val deployment = transfer.action as SentAction.SafeDeployment
        val left = transfer.sentAt + DEPLOYMENT_LIFETIME_SECONDS - clock()
        if (left <= 0) return
        withTimeoutOrNull(left.seconds) {
            while (true) {
                val listed = try {
                    listing(transfer, deployment)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // the ports and use cases promise not to throw; the backstop tries again
                }
                if (listed != null) {
                    sentTransfers.record(transfer.copy(action = deployment.copy(listed = listed)))
                    return@withTimeoutOrNull
                }
                delay(retryDelay)
            }
        }
    }

    /** How [deployment]'s listing ends now, or null when it has not ended and is to be tried again. */
    private suspend fun listing(transfer: SentTransfer, deployment: SentAction.SafeDeployment): SafeListing? {
        val list = accounts.observeAccounts().first()
        if (list is AccountList.Loaded &&
            list.accounts.any { it.network == transfer.network && it.address == deployment.safe }
        ) {
            return SafeListing.LISTED
        }
        val succeeded = when (
            val progress = receipts.observe(transfer.network, transfer.hash)
                .first { it is TransactionProgress.Included || it is TransactionProgress.Finalized }
        ) {
            is TransactionProgress.Included -> progress.succeeded
            is TransactionProgress.Finalized -> progress.succeeded
            else -> return null
        }
        val outcome = if (succeeded) listDeployedSafe(
                transfer.network,
                deployment,
            ) else listDeployedSafe.afterRevert(transfer.network, deployment)
        return when (outcome) {
            is DeployedSafeListing.Listed, is DeployedSafeListing.AlreadyInWallet -> SafeListing.LISTED
            DeployedSafeListing.NoSafe -> SafeListing.NO_SAFE
            DeployedSafeListing.NotTheSafe -> SafeListing.NOT_THE_SAFE
            is DeployedSafeListing.NotVerified, DeployedSafeListing.NotSaved -> null
        }
    }
}

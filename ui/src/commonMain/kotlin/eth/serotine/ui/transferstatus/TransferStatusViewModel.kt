package eth.serotine.ui.transferstatus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeListing
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveSentTransfer
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.SentTransferStatus
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.notSentItem
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The transfer status screen's ViewModel (docs/wallet-ux.md, screen 9): the transfer [hash] on
 * [network], what this device recorded of it and where its verified receipt says it stands
 * ([ObserveSentTransfer]), as [TransferStatusUiState]. [formatTime] dates the broadcast a peer
 * took in the user's own time zone.
 *
 * Its one intent, [retry], is for a transaction recorded as maybe sent (its broadcast found no peer
 * to answer: `SentTransfer.maybeSent`) and not mined: it sends the same signed bytes again
 * ([RebroadcastTransfer]), never a new transaction, which could take the next nonce and pay twice.
 * Done is navigation.
 *
 * A Safe's deployment is listed in the wallet by `FollowSafeDeployments`, which the host runs
 * whatever screen is open (docs/wallet-ux.md, screen 15); this screen only shows where that
 * stands, from the deployment's record and the account list ([ObserveAccounts]).
 */
class TransferStatusViewModel(
    private val hash: String,
    observeSentTransfer: ObserveSentTransfer,
    observeAccounts: ObserveAccounts,
    private val rebroadcastTransfer: RebroadcastTransfer,
    private val network: Network,
    private val formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {

    /** A retry of a transaction maybe sent: [running], and what the last one came to ([outcome], null: none yet). */
    private data class Retry(val running: Boolean = false, val outcome: NotSentItem? = null)

    private val retrying = MutableStateFlow(Retry())

    /** The status as last read: what [retry] sends again. */
    private var latest: SentTransferStatus? = null

    val uiState: StateFlow<TransferStatusUiState> =
        combine(
            observeSentTransfer(network, hash).onEach { latest = it },
            observeAccounts(),
            retrying,
            ::render,
        ).stateIn(
            viewModelScope,
            // Back after the timeout, no old step stands as current.
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            TransferStatusUiState.Loading(network.displayName),
        )

    /**
     * Broadcasts the transaction maybe sent again, byte for byte: only while its record says so and
     * no block has it. It goes on when the screen is left meanwhile; its record then says how it went.
     */
    fun retry() {
        val status = latest ?: return
        val transfer = status.transfer ?: return
        if (!transfer.maybeSent || mined(status.progress) || nonceUsed(status) || retrying.value.running) return
        retrying.value = Retry(running = true)
        viewModelScope.launch {
            val sending = withContext(NonCancellable) {
                try {
                    rebroadcastTransfer(transfer)
                } catch (e: Exception) {
                    null // the use case promises not to throw; the backstop
                }
            }
            retrying.value = Retry(outcome = outcomeOf(sending, whatOf(transfer)))
        }
    }

    /** What a retry came to, said where its record no longer can; null when a peer took it. */
    private fun outcomeOf(sending: TransferSending?, what: String): NotSentItem? = when (sending) {
        is TransferSending.Sent -> null
        is TransferSending.NotSent -> notSentItem(sending.broadcast, unverified = false, what, maybeSent = true)
        is TransferSending.RejectionUnverified -> notSentItem(sending.rejection, unverified = true, what)
        is TransferSending.NotSigned, null -> NotSentItem(RETRY_FAILED, canRetry = true)
    }

    private fun render(status: SentTransferStatus, list: AccountList, retry: Retry): TransferStatusUiState.Shown {
        val progress = status.progress
        // Broadcast: this screen opens for a transfer a peer took, and its record says when; one
        // maybe sent, until a block has it, says that no peer answered, its nonce used since or not.
        val unconfirmed = status.transfer?.maybeSent == true && !mined(progress)
        // Retried only while its nonce is not used: once it is, that nonce can never be mined again.
        val maybeSent = unconfirmed && !nonceUsed(status)
        val signed = StatusStep("Signed", StepState.DONE, status.transfer?.let { "On this device" })
        val takenAt = status.transfer?.let { ", ${formatTime(it.sentAt)}" }.orEmpty()
        val broadcast = when {
            maybeSent -> StatusStep(
                "Broadcast",
                StepState.UNCONFIRMED,
                "No peer confirmed it$takenAt: it may be on its way, or may not have reached the network",
                simpleLabel = "Sent",
                simpleDetail = "No peer answered$takenAt: it may be on its way, or may not have reached the network",
            )
            unconfirmed -> StatusStep(
                "Broadcast",
                StepState.UNCONFIRMED,
                "No peer confirmed it$takenAt",
                simpleLabel = "Sent",
                simpleDetail = "No peer answered$takenAt",
            )
            else -> StatusStep(
                "Broadcast",
                StepState.DONE,
                "Handed to the network's peers$takenAt",
                simpleLabel = "Sent",
                simpleDetail = status.transfer?.let { formatTime(it.sentAt) },
            )
        }
        // A used nonce the history recorded, whether the receipt is not found or not verifiable now.
        val used = nonceUsed(status)
        val included = when {
            used -> StatusStep(
                "Included",
                StepState.UNKNOWN,
                "Its nonce was used on chain while the app was not watching: by this transaction, unless " +
                    "another one took its place, such as one sent from this account elsewhere",
                simpleLabel = "Confirmed",
            )
            else -> when (progress) {
                TransactionProgress.Pending ->
                    StatusStep(
                        "Included",
                        StepState.WAITING,
                        "Waiting for a block to include it",
                        simpleLabel = "Confirmed",
                        simpleDetail = "Waiting to be confirmed",
                    )
                is TransactionProgress.Included -> includedStep(progress.blockNumber, progress.succeeded)
                is TransactionProgress.Finalized -> includedStep(progress.blockNumber, progress.succeeded)
                is TransactionProgress.Unavailable ->
                    StatusStep("Included", StepState.UNKNOWN, "Cannot be verified right now", simpleLabel = "Confirmed")
            }
        }
        val finalized = when {
            used -> StatusStep("Finalized", StepState.UNKNOWN, "Not known on this device", simpleLabel = "Final")
            else -> when (progress) {
                TransactionProgress.Pending, is TransactionProgress.Included ->
                    StatusStep(
                        "Finalized",
                        StepState.WAITING,
                        "Once the beacon chain finalizes its block",
                        simpleLabel = "Final",
                        simpleDetail = "Once the network finalizes it, its outcome can't be reversed",
                    )
                is TransactionProgress.Finalized ->
                    StatusStep(
                        "Finalized",
                        StepState.DONE,
                        "Block ${grouped(progress.blockNumber)} is finalized",
                        simpleLabel = "Final",
                        simpleDetail = "Its outcome can't be reversed by the network",
                    )
                is TransactionProgress.Unavailable ->
                    StatusStep("Finalized", StepState.UNKNOWN, "Cannot be verified right now", simpleLabel = "Final")
            }
        }
        val failed = (progress as? TransactionProgress.Included)?.succeeded == false ||
            (progress as? TransactionProgress.Finalized)?.succeeded == false
        val transfer = status.transfer
        return TransferStatusUiState.Shown(
            network = network.displayName,
            hash = hash,
            sent = transfer?.let(::summary),
            steps = listOf(signed, broadcast, included, finalized),
            note = when {
                progress is TransactionProgress.Unavailable -> unavailable(progress.reason)
                failed && transfer?.action is SentAction.Transfer ->
                    "It failed: the transfer reverted in its block, so nothing was moved. Its fee is paid all the same."
                failed && transfer?.action is SentAction.Swap ->
                    "It failed: the swap reverted in its block, so nothing was swapped: the price moved past its " +
                        "minimum, or its deadline passed. Its fee is paid all the same."
                failed && transfer?.action is SentAction.SafeDeployment ->
                    "It failed: this transaction reverted in its block and created no Safe itself. Its fee is " +
                        "paid all the same."
                failed -> "It failed: the transaction reverted in its block, so it did nothing. Its fee is paid all " +
                    "the same."
                else -> null
            },
            finished = progress is TransactionProgress.Finalized,
            safeTx = transfer?.let(::safeTxSummary),
            deployment = transfer?.let { deploymentSummary(it, progress, failed, list) },
            approval = transfer?.let(::approvalSummary),
            swap = transfer?.let { swapSummary(it, status.received) },
            cancellation = transfer?.let(::cancellationSummary),
            // A retry turned away keeps the record: what it came to stays on screen with Try again.
            notSent = if (maybeSent) {
                retry.outcome ?: NotSentItem(maybeSentText(transfer?.let(::whatOf) ?: "transaction"), true)
            } else {
                null
            },
            retrying = retry.running,
        )
    }

    /**
     * Whether the history says [status]'s nonce was used while no receipt of it was seen: neither
     * waiting for a block nor to be retried, since that nonce can never be mined again.
     */
    private fun nonceUsed(status: SentTransferStatus): Boolean =
        status.outcome == SentOutcome.NonceUsed && !mined(status.progress)

    private fun mined(progress: TransactionProgress): Boolean =
        progress is TransactionProgress.Included || progress is TransactionProgress.Finalized

    /**
     * What [transfer] is called in a sentence: "transfer", "approval", "execution", "deployment",
     * "swap", "cancellation" or, for a dApp's, "transaction".
     */
    private fun whatOf(transfer: SentTransfer): String = when (transfer.action) {
        is SentAction.Transfer -> "transfer"
        is SentAction.SafeApproval -> "approval"
        is SentAction.SafeExecution -> "execution"
        is SentAction.SafeDeployment -> "deployment"
        is SentAction.TokenApproval -> "approval"
        is SentAction.Swap -> "swap"
        is SentAction.CowCancellation -> "cancellation"
        is SentAction.DappCall -> "transaction"
    }

    private fun maybeSentText(what: String) = "No peer answered when this $what was broadcast, so it may not have " +
        "reached the network, or it may be on its way. Try again sends the same signed $what; nothing new is " +
        "sent from this account meanwhile, since a new one could pay twice."

    /** What [transfer] created, when it is a Safe's deployment, and where the Safe's listing stands. */
    private fun deploymentSummary(
        transfer: SentTransfer,
        progress: TransactionProgress,
        failed: Boolean,
        list: AccountList,
    ): DeploymentSummary? {
        val deployment = transfer.action as? SentAction.SafeDeployment ?: return null
        val owners = deployment.owners.size
        // After a revert, a Safe at the address is another sender's copy of the same call's doing.
        val copied = if (failed) {
            "A Safe with these owners and threshold is at its address all the same: another sender's copy of " +
                "the same deployment created it. "
        } else {
            ""
        }
        // The name it has in the wallet now, or the one it was to be listed under.
        val label = (list as? AccountList.Loaded)?.accounts
            ?.firstOrNull { it.network == network && it.address == deployment.safe }?.label
            ?: deployment.label
        val mined = progress is TransactionProgress.Included || progress is TransactionProgress.Finalized
        val (text, listingFailed) = when (deployment.listed) {
            SafeListing.LISTED -> "${copied}In your wallet as $label." to false
            SafeListing.NO_SAFE ->
                "This transaction created no Safe, and none is at its address: nothing is added to your wallet." to
                    true
            SafeListing.NOT_THE_SAFE -> "The address does not hold the Safe deployed, as the light client " +
                "verifies it, so it was not added to your wallet." to true
            null -> when {
                failed -> "Checking whether a Safe is at its address all the same…" to false
                mined -> "Checking the new Safe with the light client before it is listed…" to false
                else -> "Added to your wallet once a block has it and the light client verifies the Safe there, " +
                    "whether this screen is open or not." to false
            }
        }
        return DeploymentSummary(
            title = "Deployment of a Safe",
            safe = Eip55.format(deployment.safe),
            signers = "${deployment.threshold}/$owners",
            shape = "${deployment.threshold} of $owners ${if (owners == 1) "owner" else "owners"} · " +
                "Safe ${deployment.version}",
            from = Eip55.format(transfer.account),
            listing = text,
            listingFailed = listingFailed,
        )
    }

    private fun includedStep(block: Long, succeeded: Boolean) = if (succeeded) {
        StatusStep(
            "Included",
            StepState.DONE,
            "In block ${grouped(block)}, its receipt verified",
            simpleLabel = "Confirmed",
            simpleDetail = "Its receipt checked on chain",
        )
    } else {
        StatusStep(
            "Included",
            StepState.FAILED,
            "In block ${grouped(block)}, its receipt verified: it reverted",
            simpleLabel = "Failed",
            simpleDetail = "Its receipt checked on chain: it reverted",
        )
    }

    /** [asset]'s symbol as shown, its decimals and its bundled icon. */
    private fun shown(asset: TransferAsset): Triple<String, Int, String?> = when (asset) {
        TransferAsset.Native -> Triple(network.nativeSymbol, NATIVE_DECIMALS, network.nativeIcon)
        is TransferAsset.Erc20Token -> Triple(displaySymbol(asset.token.symbol), asset.token.decimals, asset.token.icon)
    }

    /** What [transfer] allowed, when it is a token approval. */
    private fun approvalSummary(transfer: SentTransfer): ApprovalSummary? {
        val approval = transfer.action as? SentAction.TokenApproval ?: return null
        val (symbol, decimals, icon) = shown(TransferAsset.Erc20Token(approval.token))
        // A zero approval takes the allowance back, before the whole amount is approved.
        val reset = approval.amount.isZero
        return ApprovalSummary(
            title = if (reset) "Allowance reset for a swap" else "Approval for a swap",
            amount = "${approval.amount.formatUnits(decimals)} $symbol",
            symbol = symbol,
            icon = icon,
            spenderLabel = if (reset) {
                "The swap's spender may take none, until the whole amount is approved"
            } else {
                "May be taken by the swap's spender"
            },
            spender = Eip55.format(approval.spender),
            from = Eip55.format(transfer.account),
        )
    }

    /** Which CoW order [transfer] invalidates, when it is that order's cancellation on chain. */
    private fun cancellationSummary(transfer: SentTransfer): CancellationSummary? {
        val cancellation = transfer.action as? SentAction.CowCancellation ?: return null
        return CancellationSummary(
            title = "Cancellation of a CoW order on chain",
            order = cancellation.uid.hex,
            settlement = Eip55.format(cancellation.settlement),
            from = Eip55.format(transfer.account),
        )
    }

    /** What [transfer] sold and buys at least, when it is a swap, and what it paid ([received]) once known. */
    private fun swapSummary(transfer: SentTransfer, received: UInt256?): SwapSummary? {
        val swap = transfer.action as? SentAction.Swap ?: return null
        val (soldSymbol, soldDecimals, soldIcon) = shown(swap.sell)
        val (boughtSymbol, boughtDecimals, boughtIcon) = shown(swap.buy)
        val minimum = "at least ${swap.minimumOut.formatUnits(boughtDecimals)} $boughtSymbol"
        // "At least" the minimum until the receipt's logs say what it paid (docs/swaps.md, *The record*).
        val bought = received?.let { "${it.formatUnits(boughtDecimals)} $boughtSymbol" } ?: minimum
        return SwapSummary(
            title = "Swap of ${swap.amountIn.formatUnits(soldDecimals)} $soldSymbol for $bought on Uniswap",
            sold = "${swap.amountIn.formatUnits(soldDecimals)} $soldSymbol",
            soldSymbol = soldSymbol,
            soldIcon = soldIcon,
            bought = bought,
            boughtSymbol = boughtSymbol,
            boughtIcon = boughtIcon,
            router = Eip55.format(swap.router),
            from = Eip55.format(transfer.account),
            received = received?.let {
                "Received, as the verified receipt's logs say; it was signed for $minimum."
            },
        )
    }

    /** What [transfer] sent, when it is a transfer. */
    private fun summary(transfer: SentTransfer): SentSummary? {
        val sent = transfer.action as? SentAction.Transfer ?: return null
        val (symbol, decimals, icon) = shown(sent.asset)
        return SentSummary(
            amount = "${sent.amount.formatUnits(decimals)} $symbol",
            symbol = symbol,
            icon = icon,
            to = Eip55.format(sent.to),
            from = Eip55.format(transfer.account),
        )
    }

    /** What [transfer] did, when it is an approval or an execution of a Safe transaction. */
    private fun safeTxSummary(transfer: SentTransfer): SafeTxSummary? {
        val (title, request) = when (val action = transfer.action) {
            is SentAction.SafeApproval -> "Approval of a Safe transaction" to action.request
            is SentAction.SafeExecution -> "Execution of a Safe transaction" to action.request
            is SentAction.Transfer, is SentAction.SafeDeployment, is SentAction.TokenApproval,
            is SentAction.Swap, is SentAction.CowCancellation, is SentAction.DappCall,
            -> return null
        }
        return SafeTxSummary(
            title = title,
            safe = Eip55.format(request.safe),
            nonce = "Safe nonce ${request.nonce}",
            from = Eip55.format(transfer.account),
        )
    }

    private fun unavailable(reason: ReadUnavailable): String = "Where it stands cannot be verified right now: " +
        when (reason) {
            ReadUnavailable.NO_ENGINE -> "there is no light client on this device."
            ReadUnavailable.NETWORK_OFF -> "the network's light client is not running."
            ReadUnavailable.STALE_ANCHOR -> "the light client refuses to sync."
            ReadUnavailable.SYNCING -> "the light client is still syncing. Serotine keeps checking."
            ReadUnavailable.UNPROVEN -> "its receipt could not be verified this time. Serotine keeps checking."
            ReadUnavailable.REFUSED -> "the light client on this device cannot read receipts."
        }

    private companion object {
        const val NATIVE_DECIMALS = 18
        const val RETRY_FAILED = "Serotine could not finish sending it again. Try again."
    }
}

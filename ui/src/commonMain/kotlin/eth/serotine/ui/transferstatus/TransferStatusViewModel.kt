package eth.serotine.ui.transferstatus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.OutboxList
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeListing
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.usecase.ConfirmQueued
import eth.serotine.domain.usecase.DiscardQueued
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveOutbox
import eth.serotine.domain.usecase.ObserveSentTransfer
import eth.serotine.domain.usecase.QUEUED_UNATTENDED_SECONDS
import eth.serotine.domain.usecase.QueuedDiscard
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.SentTransferStatus
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.agoText
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.notSentItem
import eth.serotine.ui.components.redatedWhile
import eth.serotine.ui.outbox.conflictReason
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
 *
 * While [hash] names a transfer queued in lenient mode ([ObserveOutbox]; docs/lenient-mode.md, *The
 * outbox*), the screen shows it instead, dated against [clock] (unix seconds) and saying what it
 * waits for: its network's light client to verify ([ObserveLightClient]), or the network, off, to be
 * turned on ([ObserveEnabledNetworks]). Its own intents: [discard] ([DiscardQueued]), [signAgain] (a
 * discard, then Send filled in with it) and, past its hour, [confirm] ([ConfirmQueued]). A discard is
 * told as one only when it deleted the transfer: one whose broadcast ran, or that was sent meanwhile,
 * never is. Once sent, the same id is the sent transfer's hash, shown as above.
 */
class TransferStatusViewModel(
    private val hash: String,
    observeSentTransfer: ObserveSentTransfer,
    observeAccounts: ObserveAccounts,
    private val rebroadcastTransfer: RebroadcastTransfer,
    private val network: Network,
    observeOutbox: ObserveOutbox,
    observeLightClient: ObserveLightClient,
    observeEnabledNetworks: ObserveEnabledNetworks,
    private val discardQueued: DiscardQueued,
    private val confirmQueued: ConfirmQueued,
    private val formatTime: (Long) -> String = ::formatDateTime,
    private val clock: () -> Long = ::epochSecondsNow,
) : ViewModel() {

    /** A retry of a transaction maybe sent: [running], and what the last one came to ([outcome], null: none yet). */
    private data class Retry(val running: Boolean = false, val outcome: NotSentItem? = null)

    private val retrying = MutableStateFlow(Retry())

    /**
     * What the user did with a queued transfer: [discarding] or [confirming] while that runs,
     * [discarded] once a discard deleted it, [gone] when a discard found it no longer queued (sent
     * meanwhile, or discarded already), [reopen] what Send opens with after Sign again or Edit, and
     * [error] a change that could not be made.
     */
    private data class Changes(
        val discarding: Boolean = false,
        val confirming: Boolean = false,
        val discarded: Boolean = false,
        val gone: Boolean = false,
        val reopen: SendAgain? = null,
        val reopenSafe: SafeTxAgain? = null,
        val error: String? = null,
    )

    /** What a queued transfer waits for to be checked and sent. */
    private enum class Waits {
        /** Its network's light client to catch up. */
        CATCHING_UP,

        /** Nothing: the light client verifies, so it is checked and sent once it checks out. */
        NOTHING,

        /** Its network, off, to be turned on: it runs no light client meanwhile. */
        NETWORK_ON,
    }

    private val changes = MutableStateFlow(Changes())

    /** The status as last read: what [retry] sends again. */
    private var latest: SentTransferStatus? = null

    /** The transfer queued under [hash], as last read: what [discard], [signAgain] and [confirm] act on. */
    private var queuedNow: QueuedTransaction? = null

    /** The transfer last read as queued under [hash]: what the screen shows while a discard of it runs. */
    private var lastQueued: QueuedTransaction? = null

    /** Whether the outbox can be read, and whether it lost what it held: what an id found nowhere is said as. */
    private val outboxState: Flow<OutboxState> = observeOutbox()
        .map { list ->
            when {
                list is OutboxList.Unreadable -> OutboxState.UNREADABLE
                (list as? OutboxList.Loaded)?.lost == true -> OutboxState.LOST
                else -> OutboxState.READ
            }
        }
        .distinctUntilChanged()

    /** The transfer queued under [hash] on [network], null while none is (or the outbox cannot be read). */
    private val queued: Flow<QueuedTransaction?> = observeOutbox()
        .map { list ->
            (list as? OutboxList.Loaded)?.queued?.firstOrNull { it.network == network && it.id.equals(hash, true) }
        }
        .distinctUntilChanged()
        .onEach { queued ->
            queuedNow = queued
            if (queued != null) lastQueued = queued
        }

    /** What a queued transfer waits for: its network turned on, its light client to catch up, or nothing. */
    private val waits: Flow<Waits> =
        combine(observeLightClient(network), observeEnabledNetworks()) { status, on ->
            when {
                network !in on -> Waits.NETWORK_ON
                status is LightClientStatus.Synced -> Waits.NOTHING
                else -> Waits.CATCHING_UP
            }
        }.distinctUntilChanged()

    val uiState: StateFlow<TransferStatusUiState> =
        combine(
            combine(observeSentTransfer(network, hash).onEach { latest = it }, observeAccounts(), retrying, ::Read),
            queued,
            waits,
            changes,
            outboxState,
        ) { read, queued, waits, changes, outbox -> Inputs(read, queued, waits, changes, outbox) }
            // A queued transfer's hour, and its age, are dated anew every second.
            .redatedWhile { it.queued != null }
            .map(::render)
            .stateIn(
                viewModelScope,
                // Back after the timeout, no old step stands as current.
                SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
                TransferStatusUiState.Loading(network.displayName),
            )

    /** The sent transfer's [status] as read, the account [list] and the [retry] of one maybe sent. */
    private data class Read(val status: SentTransferStatus, val list: AccountList, val retry: Retry)

    /** Everything the screen is drawn from: [read], what is [queued] under the id, and what that [waits] for. */
    private data class Inputs(
        val read: Read,
        val queued: QueuedTransaction?,
        val waits: Waits,
        val changes: Changes,
        val outbox: OutboxState,
    )

    /** What the outbox says of itself. */
    private enum class OutboxState { READ, UNREADABLE, LOST }

    /**
     * Discards the queued transfer: its signature is deleted, and nothing is sent. It waits while
     * its check runs; one a peer took meanwhile is shown as sent, never as discarded.
     */
    fun discard() = discard(reopen = false)

    /**
     * Sign again (after a conflict) or Edit: the queued transfer discarded, then Send opened with it
     * filled in ([TransferStatusUiState.Discarded.reopen]), to be reviewed and signed anew.
     */
    fun signAgain() = discard(reopen = true)

    private fun discard(reopen: Boolean) {
        val queued = queuedNow ?: return
        val current = changes.value
        if (queued.sending || current.discarding || current.discarded || current.gone) return
        val again = if (reopen) sendAgainOf(queued) else null
        val safeAgain = if (reopen) safeAgainOf(queued) else null
        if (reopen && again == null && safeAgain == null) return
        changes.value = current.copy(discarding = true, error = null)
        viewModelScope.launch {
            // To its end even when the screen is left meanwhile: a discard is made or not, never half.
            val outcome = withContext(NonCancellable) {
                try {
                    discardQueued(queued.accountId, queued.id)
                } catch (e: Exception) {
                    QueuedDiscard.NotSaved // the use case promises not to throw; the backstop
                }
            }
            val latest = changes.value.copy(discarding = false)
            changes.value = when (outcome) {
                // Only a transfer this discard deleted is told as discarded, and only then is Send reopened.
                QueuedDiscard.Discarded -> latest.copy(discarded = true, reopen = again, reopenSafe = safeAgain)
                QueuedDiscard.Gone -> latest.copy(gone = true)
                QueuedDiscard.BeingSent -> latest.copy(error = BEING_SENT_NOT_DISCARDED)
                QueuedDiscard.NotSaved -> latest.copy(error = DISCARD_NOT_SAVED)
            }
        }
    }

    /**
     * Still send this: the queued transfer past its hour, sent once its check passes within the hour
     * from now, with no new signature (decision 4).
     */
    fun confirm() {
        val queued = queuedNow ?: return
        val current = changes.value
        if (queued.signed == null || current.confirming || current.discarding) return
        changes.value = current.copy(confirming = true, error = null)
        viewModelScope.launch {
            val saved = withContext(NonCancellable) {
                try {
                    confirmQueued(queued.accountId, queued.id)
                } catch (e: Exception) {
                    false // the use case promises not to throw; the backstop
                }
            }
            // Not saved because a conflict deleted its bytes, or it was sent, meanwhile: the screen says that.
            val stillAsks = queuedNow?.let { it.signed != null && it.conflict == null } == true
            changes.value = changes.value.copy(confirming = false, error = CONFIRM_NOT_SAVED.takeIf { !saved && stillAsks })
        }
    }

    private fun render(inputs: Inputs): TransferStatusUiState {
        val (read, queued, waits, changes, outbox) = inputs
        val transfer = read.status.transfer
        // While its discard runs, the transfer is shown as it was, until the discard's outcome is known.
        val shown = queued ?: lastQueued.takeIf { changes.discarding && transfer == null }
        if (shown != null && !changes.discarded) return queuedState(shown, waits, changes)
        // Deleted under the outbox's lock before any broadcast of it: nothing was sent.
        if (changes.discarded) {
            return TransferStatusUiState.Discarded(network.displayName, DISCARDED, changes.reopen, changes.reopenSafe)
        }
        // No longer queued when the discard came: sent meanwhile (its record follows), or discarded already.
        if (changes.gone && transfer == null) {
            return TransferStatusUiState.Discarded(network.displayName, NO_LONGER_QUEUED)
        }
        // Neither queued nor recorded: never drawn as handed to the network, which nothing says it was.
        // The follower records a send before it lets the transfer go from the outbox, so one sent has its record.
        if (transfer == null) {
            when {
                outbox == OutboxState.UNREADABLE ->
                    return TransferStatusUiState.Discarded(network.displayName, OUTBOX_UNREADABLE)
                outbox == OutboxState.LOST -> return TransferStatusUiState.Discarded(network.displayName, OUTBOX_LOST)
                lastQueued != null -> return TransferStatusUiState.Discarded(network.displayName, LEFT_THE_OUTBOX)
            }
        }
        val sent = render(read.status, read.list, read.retry)
        // A discard that came after a peer took it: it was sent, and is never told as discarded.
        return if (changes.gone) sent.copy(note = sent.note ?: SENT_BEFORE_DISCARD) else sent
    }

    /** [queued] as the screen shows it while it waits in the outbox. */
    private fun queuedState(
        queued: QueuedTransaction,
        waits: Waits,
        changes: Changes,
    ): TransferStatusUiState.Queued {
        val now = clock()
        val name = network.displayName
        val conflict = queued.conflict
        val since = queued.confirmedAt ?: queued.signedAt
        val pastItsHour = conflict == null && !queued.sending && now - since > QUEUED_UNATTENDED_SECONDS
        val signedAgo = agoText(queued.signedAt, now)
        val first = when {
            conflict != null -> StatusStep(
                "Needs your signature again",
                StepState.FAILED,
                "${conflictReason(conflict, queued)} Nothing was sent, and its signature was deleted.",
            )
            queued.sending -> StatusStep("Sending…", StepState.WAITING, BEING_SENT)
            pastItsHour -> StatusStep(
                "Queued",
                StepState.UNKNOWN,
                "Not sent by itself after an hour: it waits for you.",
            )
            waits == Waits.NETWORK_ON -> StatusStep(
                "Queued",
                StepState.UNKNOWN,
                "$name is off, so nothing checks or sends it: turn $name on in Settings to send it.",
            )
            waits == Waits.NOTHING -> StatusStep(
                "Queued",
                StepState.WAITING,
                "$name has caught up: it's checked on chain, and sent once it checks out.",
            )
            else -> StatusStep(
                "Queued",
                StepState.WAITING,
                "Waits for $name to catch up. Keep Serotine open to send it sooner.",
            )
        }
        val signed = if (conflict != null) {
            StatusStep("Signed", StepState.UNKNOWN, "On this device, ${formatTime(queued.signedAt)}: deleted since")
        } else {
            StatusStep("Signed", StepState.DONE, "On this device, ${formatTime(queued.signedAt)}")
        }
        val broadcast = if (conflict != null) {
            StatusStep("Broadcast", StepState.UNKNOWN, "Not sent", simpleLabel = "Sent", simpleDetail = "Not sent")
        } else {
            StatusStep(
                "Broadcast",
                StepState.WAITING,
                "Once it checks out at a fresh verified head",
                simpleLabel = "Sent",
                simpleDetail = "Once it checks out on chain",
            )
        }
        val action = if (conflict != null) "Sign again" else "Edit"
        val safeAgain = safeAgainOf(queued)
        return TransferStatusUiState.Queued(
            network = name,
            id = queued.id,
            sent = summary(queued.action, queued.account),
            // Signed on this device first; then it waits in the outbox; then it is sent.
            steps = listOf(signed, first, broadcast),
            again = sendAgainOf(queued),
            safeAgain = safeAgain,
            safeTx = safeTxSummary(queued.action, queued.account),
            againLabel = action,
            againNote = if (safeAgain != null) {
                if (conflict != null) {
                    "Sign again discards it and opens its Safe transaction, to approve or execute it anew."
                } else {
                    "Edit discards it and opens its Safe transaction. Discard deletes its signature: nothing is sent."
                }
            } else if (conflict != null) {
                "Sign again discards it and opens Send with it filled in, to be reviewed and signed anew."
            } else {
                "Edit discards it and opens Send with it filled in. Discard deletes its signature: nothing is sent."
            },
            question = "Still send this? You signed it $signedAgo.".takeIf { pastItsHour },
            confirming = changes.confirming,
            busy = BEING_SENT.takeIf { queued.sending },
            discarding = changes.discarding,
            error = changes.error,
        )
    }

    /** The Safe transaction [queued] approves or executes, which Sign again and Edit open: null for anything else. */
    private fun safeAgainOf(queued: QueuedTransaction): SafeTxAgain? {
        val request = when (val action = queued.action) {
            is SentAction.SafeApproval -> action.request
            is SentAction.SafeExecution -> action.request
            else -> return null
        }
        return SafeTxAgain(queued.network.chainId, request.safe.hex, "0x" + request.hash.hex)
    }

    /** What Send opens with to sign [queued] again: its account, asset, recipient, amount and fee level. */
    private fun sendAgainOf(queued: QueuedTransaction): SendAgain? {
        val transfer = queued.action as? SentAction.Transfer ?: return null
        val (_, decimals, _) = shown(transfer.asset)
        return SendAgain(
            chainId = queued.network.chainId,
            from = queued.account.hex,
            asset = (transfer.asset as? TransferAsset.Erc20Token)?.token?.address?.hex,
            to = transfer.to.hex,
            amount = transfer.amount.formatUnits(decimals),
            fee = queued.level,
        )
    }

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
            sent = transfer?.let { summary(it.action, it.account) },
            steps = listOf(signed, broadcast, included, finalized),
            note = when {
                progress is TransactionProgress.Unavailable -> unavailable(progress.reason)
                failed && transfer?.action is SentAction.Transfer ->
                    "It failed: the transfer reverted in its block, so nothing was moved. Its fee is paid all the same."
                failed && transfer?.action is SentAction.Swap ->
                    "It failed: the swap reverted in its block, so nothing was swapped: the price moved past its " +
                        "minimum, or its deadline passed. Its fee is paid all the same."
                failed && (transfer?.action is SentAction.Wrap || transfer?.action is SentAction.Unwrap) ->
                    "It failed: the ${transfer?.let(::whatOf)} reverted in its block, so nothing was moved. Its fee " +
                        "is paid all the same."
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
            swap = transfer?.let { swapSummary(it, status.received) ?: wrapSummary(it) },
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
     * "swap", "wrap", "unwrap", "cancellation" or, for a dApp's, "transaction".
     */
    private fun whatOf(transfer: SentTransfer): String = when (transfer.action) {
        is SentAction.Transfer -> "transfer"
        is SentAction.SafeApproval -> "approval"
        is SentAction.SafeExecution -> "execution"
        is SentAction.SafeDeployment -> "deployment"
        is SentAction.TokenApproval -> "approval"
        is SentAction.Swap -> "swap"
        is SentAction.Wrap -> "wrap"
        is SentAction.Unwrap -> "unwrap"
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

    /** What [transfer] takes and pays, one for one through WETH9, when it is a wrap or an unwrap. */
    private fun wrapSummary(transfer: SentTransfer): SwapSummary? = when (val action = transfer.action) {
        is SentAction.Wrap -> {
            val weth = TransferAsset.Erc20Token(action.weth)
            wrapSummary(transfer, "Wrap", action.weth, action.amount, TransferAsset.Native, weth)
        }
        is SentAction.Unwrap -> {
            val weth = TransferAsset.Erc20Token(action.weth)
            wrapSummary(transfer, "Unwrap", action.weth, action.amount, weth, TransferAsset.Native)
        }
        else -> null
    }

    /** [transfer]'s [verb] of [amount] through [weth]: [sell] given, as much [buy] paid. */
    private fun wrapSummary(
        transfer: SentTransfer,
        verb: String,
        weth: Token,
        amount: UInt256,
        sell: TransferAsset,
        buy: TransferAsset,
    ): SwapSummary {
        val (soldSymbol, soldDecimals, soldIcon) = shown(sell)
        val (boughtSymbol, boughtDecimals, boughtIcon) = shown(buy)
        val sold = "${amount.formatUnits(soldDecimals)} $soldSymbol"
        return SwapSummary(
            title = "$verb of $sold into $boughtSymbol",
            sold = sold,
            soldSymbol = soldSymbol,
            soldIcon = soldIcon,
            bought = "${amount.formatUnits(boughtDecimals)} $boughtSymbol",
            boughtSymbol = boughtSymbol,
            boughtIcon = boughtIcon,
            router = Eip55.format(weth.address),
            from = Eip55.format(transfer.account),
            through = "Through WETH9, one for one",
        )
    }

    /** What [action] from [account] sends, when it is a transfer. */
    private fun summary(action: SentAction, account: Address): SentSummary? {
        val sent = action as? SentAction.Transfer ?: return null
        val (symbol, decimals, icon) = shown(sent.asset)
        return SentSummary(
            amount = "${sent.amount.formatUnits(decimals)} $symbol",
            symbol = symbol,
            icon = icon,
            to = Eip55.format(sent.to),
            from = Eip55.format(account),
        )
    }

    /** What [transfer] did, when it is an approval or an execution of a Safe transaction. */
    private fun safeTxSummary(transfer: SentTransfer): SafeTxSummary? = safeTxSummary(transfer.action, transfer.account)

    /** What [action] from [account] does, when it is an approval or an execution of a Safe transaction. */
    private fun safeTxSummary(action: SentAction, account: Address): SafeTxSummary? {
        val (title, request) = when (action) {
            is SentAction.SafeApproval -> "Approval of a Safe transaction" to action.request
            is SentAction.SafeExecution -> "Execution of a Safe transaction" to action.request
            is SentAction.Transfer, is SentAction.SafeDeployment, is SentAction.TokenApproval,
            is SentAction.Swap, is SentAction.Wrap, is SentAction.Unwrap, is SentAction.CowCancellation,
            is SentAction.DappCall,
            -> return null
        }
        return SafeTxSummary(
            title = title,
            safe = Eip55.format(request.safe),
            nonce = "Safe nonce ${request.nonce}",
            from = Eip55.format(account),
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

        /** Why Discard and Edit wait while a queued transfer's check and broadcast run. */
        const val BEING_SENT = "It's being checked and sent."
        const val DISCARDED = "Discarded: its signature was deleted, and nothing was sent."
        const val SENT_BEFORE_DISCARD = "It was sent before it could be discarded."
        const val OUTBOX_UNREADABLE = "The transactions waiting to be sent on this device cannot be read right " +
            "now, so where this one stands is not known. Nothing is sent from this wallet until they can be read; " +
            "Serotine keeps trying."
        const val OUTBOX_LOST = "A transaction waiting to be sent was lost: the key that sealed it on this device " +
            "is gone. If it was being sent when Serotine last closed, it may have gone out. Before you sign it " +
            "again from Send, wait until Serotine has caught up, and check the account's balance."
        const val LEFT_THE_OUTBOX = "It is no longer waiting to be sent from this device, and there is no record " +
            "of it being sent."
        const val NO_LONGER_QUEUED =
            "It is no longer waiting to be sent from this device: it was sent, or discarded, meanwhile."
        const val BEING_SENT_NOT_DISCARDED =
            "It's being checked and sent, so it was not discarded: it may reach the network. Its status follows."
        const val DISCARD_NOT_SAVED = "The outbox could not be changed, so it is still queued. Try again."
        const val CONFIRM_NOT_SAVED = "Your answer could not be saved, so it still waits for you. Try again."
    }
}

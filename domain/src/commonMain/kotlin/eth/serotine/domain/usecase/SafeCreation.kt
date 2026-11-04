package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeCreation
import eth.serotine.domain.model.SafeRelease
import eth.serotine.domain.model.SafeSettingsCalls
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeReleases
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One owner of a Safe being created (docs/wallet-ux.md, screen 13): an [Account] — an externally
 * owned account's key, held here or anywhere else, or any other address typed — or a [DeviceKey],
 * a hardware P-256 key of a device through its passkey signer. On chain each is just its
 * [address].
 */
sealed interface PlannedOwner {

    /** The owner as the Safe's owner list holds it. */
    val address: Address

    /** The key or contract at [address]: an account of this wallet's, or an address typed. */
    data class Account(override val address: Address) : PlannedOwner

    /**
     * A hardware-bound P-256 key, owning the Safe through its passkey signer at [address]
     * (docs/keys-and-signing.md, *A Safe owned by a hardware P-256 key*). Such a key has no backup
     * by design, so it is never the Safe's only owner. This build offers none yet: the passkey
     * signer is not built.
     */
    data class DeviceKey(override val address: Address) : PlannedOwner
}

/**
 * Why a Safe of the owners and threshold asked for is not planned (docs/wallet-ux.md, screen 14;
 * docs/keys-and-signing.md, *Backup and recovery*). Nothing is deployed for it.
 */
sealed interface SafePlanRefusal {

    /** No Safe release to create one from is pinned for the network. */
    data object NoRelease : SafePlanRefusal

    /** No owner at all. */
    data object NoOwners : SafePlanRefusal

    /** More than [max] owners: more than a wallet that checks every one of them proposes. */
    data class TooManyOwners(val max: Int) : SafePlanRefusal

    /**
     * [address] is the zero address or the sentinel `0x…01` that the Safe's own owner list is
     * linked from: the Safe refuses either as an owner.
     */
    data class ReservedAddress(val address: Address) : SafePlanRefusal

    /** [address] is among the owners twice. */
    data class DuplicateOwner(val address: Address) : SafePlanRefusal

    /**
     * A device's hardware key as the only owner: it has no backup, so losing the device would
     * lose the Safe for good. A recovery owner comes with it.
     */
    data object DeviceKeyAlone : SafePlanRefusal

    /** [threshold] is not between one and the number of [owners]. */
    data class ThresholdOutOfRange(val threshold: Int, val owners: Int) : SafePlanRefusal

    /**
     * Every one of more than one [owners] must sign: with one of them lost, the rest could never
     * reach the threshold again, not even to swap the lost one out. That shape only comes with a
     * recovery module (owner ruling, 2026-10-18, #54: every n-of-n with n above one), and none is
     * offered when a Safe is created here yet.
     */
    data class NeedsRecoveryModule(val owners: Int) : SafePlanRefusal

    /** The name is longer than [RenameAccount.MAX_LABEL_LENGTH]. */
    data object NameTooLong : SafePlanRefusal
}

/**
 * A Safe planned for deployment on [network]: of [release], with [owners] in their order and
 * [threshold], deployed with [saltNonce] (so its [address] is known before anything is sent), to be
 * listed as [label] (blank: named as a watched account is). [initializer] is its `setup` call,
 * [deployData] the `createProxyWithNonce` call that deploys it, and [address] where the release's
 * factory puts it (CREATE2, computed here). Only [PlanSafe] makes one.
 */
@ConsistentCopyVisibility
data class SafePlan internal constructor(
    val network: Network,
    val release: SafeRelease,
    val owners: List<Address>,
    val threshold: Int,
    val saltNonce: UInt256,
    val label: String,
) {
    /** `setup(owners, threshold, …)`: no module, no payment, [SafeRelease.fallbackHandler]. */
    val initializer: ByteArray
        get() = SafeCreation.setup(owners, threshold, release.fallbackHandler)

    /** The factory's `createProxyWithNonce` call that deploys the Safe and sets it up. */
    val deployData: ByteArray
        get() = SafeCreation.createProxyWithNonce(release.singleton, initializer, saltNonce)

    /** Where the factory deploys it, as computed here. */
    val address: Address = SafeCreation.proxyAddress(release, initializer, saltNonce)
}

/**
 * Plans a new Safe (docs/wallet-ux.md, screens 13 to 15): its owners and threshold, checked
 * against the shapes docs/keys-and-signing.md (*Backup and recovery*) allows, and the address it
 * will have. A device's hardware key is never the only owner; a threshold equal to the number of
 * owners above one (2 of 2, 3 of 3, …) needs a recovery module beside it, and this wallet pins
 * none, so it is refused: the reasoning the keys doc gives for 2 of 2 holds for every such
 * threshold. The Safe is of the release pinned for the network it is planned on ([SafeReleases]),
 * with no module, no payment and the release's fallback handler, and a fresh salt nonce from
 * [saltNonces] each time, so two plans of the same owners get two addresses. Refusals are typed;
 * nothing throws.
 */
class PlanSafe(
    private val releases: SafeReleases,
    private val saltNonces: () -> UInt256 = ::randomSaltNonce,
) {
    /** [owners] (in their order) at [threshold] on [network], to be listed as [label] once deployed. */
    operator fun invoke(network: Network, owners: List<PlannedOwner>, threshold: Int, label: String): SafePlanning {
        val name = label.trim()
        if (name.length > RenameAccount.MAX_LABEL_LENGTH) return SafePlanning.Refused(SafePlanRefusal.NameTooLong)
        val release = releases.forCreation(network) ?: return SafePlanning.Refused(SafePlanRefusal.NoRelease)
        (ownerListRefusal(owners) ?: shapeRefusal(owners, threshold))?.let { return SafePlanning.Refused(it) }
        val plan = SafePlan(network, release, owners.map { it.address }, threshold, saltNonces(), name)
        return SafePlanning.Planned(plan)
    }

    /** Whether a Safe release to create one from is pinned for [network] at all. */
    fun hasRelease(network: Network): Boolean = releases.forCreation(network) != null

    /** Why [owners] cannot own a Safe on [network] at any threshold (the owner step), or null when they can. */
    fun ownersRefusal(network: Network, owners: List<PlannedOwner>): SafePlanRefusal? =
        if (hasRelease(network)) ownerListRefusal(owners) else SafePlanRefusal.NoRelease

    /** Why [owners] cannot own a Safe at any threshold, the release aside. */
    private fun ownerListRefusal(owners: List<PlannedOwner>): SafePlanRefusal? {
        if (owners.isEmpty()) return SafePlanRefusal.NoOwners
        if (owners.size > MAX_OWNERS) return SafePlanRefusal.TooManyOwners(MAX_OWNERS)
        owners.firstOrNull { it.address == Address.ZERO || it.address == SafeSettingsCalls.SENTINEL }?.let {
            return SafePlanRefusal.ReservedAddress(it.address)
        }
        val seen = HashSet<Address>()
        owners.firstOrNull { !seen.add(it.address) }?.let { return SafePlanRefusal.DuplicateOwner(it.address) }
        if (owners.size == 1 && owners.single() is PlannedOwner.DeviceKey) return SafePlanRefusal.DeviceKeyAlone
        return null
    }

    /** Why [threshold] of [owners] is not a shape the wallet creates (the threshold step), or null when it is. */
    fun shapeRefusal(owners: List<PlannedOwner>, threshold: Int): SafePlanRefusal? = when {
        threshold < 1 || threshold > owners.size -> SafePlanRefusal.ThresholdOutOfRange(threshold, owners.size)
        threshold == owners.size && owners.size > 1 -> SafePlanRefusal.NeedsRecoveryModule(owners.size)
        else -> null
    }

    companion object {
        /** The most owners a Safe created here has. */
        const val MAX_OWNERS = 20
    }
}

/** The bytes of a salt nonce: a whole uint256. */
private const val SALT_NONCE_BYTES = 32

/**
 * A fresh salt nonce: 32 bytes of the platform's default random generator. It needs no secrecy,
 * only to differ between plans; were two plans of the same owners ever to meet, the second is
 * refused before signing ([SafeDeploymentRefusal.AlreadyDeployed]).
 */
private fun randomSaltNonce(): UInt256 = UInt256.fromBigEndian(Random.nextBytes(SALT_NONCE_BYTES))

/** The outcome of [PlanSafe]. */
sealed interface SafePlanning {

    /** [plan] is ready to be quoted. */
    data class Planned(val plan: SafePlan) : SafePlanning

    /** Not planned: [refusal]. */
    data class Refused(val refusal: SafePlanRefusal) : SafePlanning
}

/**
 * A Safe's deployment, quoted for review (docs/wallet-ux.md, screen 15): [plan], the transaction
 * [payer] sends for it ([call]: `createProxyWithNonce` to the release's factory, quoted as every
 * transaction from an account this wallet holds is, its verified run returning [SafePlan.address]),
 * and the block at which that address was verified to hold no code yet ([codelessAt]). Only
 * [QuoteSafeDeployment] makes one.
 */
@ConsistentCopyVisibility
data class SafeDeploymentQuote internal constructor(
    val plan: SafePlan,
    val payer: Address,
    val call: CallQuote,
    val codelessAt: Long,
)

/** Why a Safe's deployment was not quoted; nothing is signed. */
sealed interface SafeDeploymentRefusal {


    /**
     * The address the Safe would get holds code already ([kind]: a Safe deployed there before, or
     * another contract): a second deployment there reverts, its fee paid all the same.
     */
    data class AlreadyDeployed(val kind: AddressKind) : SafeDeploymentRefusal

    /** This wallet sent its deployment already, and no block has it yet. */
    data object AlreadySent : SafeDeploymentRefusal

    /**
     * This wallet sent the deployment of a Safe with the same owners, in the same order, and the same
     * threshold, at [safe] (under another salt), and no block has it yet: a second would be a second
     * Safe of the same shape, its fee paid for nothing the user asked twice for.
     */
    data class SameSafeSent(val safe: Address) : SafeDeploymentRefusal

    /**
     * The factory's verified run returned [returned] (null: no address at all), not the address
     * computed here: the pinned release does not match the chain, and nothing is sent.
     */
    data class AddressMismatch(val returned: Address?) : SafeDeploymentRefusal

    /** The light client has no verified head yet. */
    data object Syncing : SafeDeploymentRefusal

    /** A verified figure could not be had right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : SafeDeploymentRefusal

    /** The transaction itself was not quoted: [refusal] (the payer's funds, a revert, …). */
    data class NotQuoted(val refusal: CallRefusal) : SafeDeploymentRefusal
}

/**
 * Quotes the deployment of a planned Safe ([SafePlan]) from [payer], an account this wallet holds
 * (docs/wallet-ux.md, screen 15): `createProxyWithNonce` sent to the release's factory, quoted as
 * every transaction from an account this wallet holds is ([CallQuoter]: the nonce behind its own
 * transactions not mined yet, the fees at [FeeLevel], the gas, and the call run as a verified call
 * from [payer] at the head). Its run must return the address computed here, or it is refused
 * ([SafeDeploymentRefusal.AddressMismatch]): the CREATE2 prediction is what the review shows, and
 * the factory's own code agreeing with it on verified state is what lets it be signed.
 *
 * Refused first: an address that holds code already, as its verified code says
 * ([AddressKinds]: anything but no code, which reads as an account), a deployment of the same
 * address this wallet sent that no block has yet (as [QuoteSafeExecution] refuses a second
 * execution: the run at the head passes while the first is on its way, and the second reverts once
 * it is mined), and one of a Safe with the same owners, in their order, and the same threshold that
 * no block has yet either: planned again, it has another salt and so another address, and would
 * make a second Safe. On the network the Safe is planned on, the payer's there; [clock] is in unix
 * seconds.
 */
class QuoteSafeDeployment(
    private val kinds: AddressKinds,
    private val nativeBalances: NativeBalances,
    private val inputs: TransactionInputs,
    private val sentTransfers: SentTransfers,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(plan: SafePlan, payer: Address, level: FeeLevel): SafeDeploymentQuoting {
        fun refused(refusal: SafeDeploymentRefusal) = SafeDeploymentQuoting.Refused(refusal)
        val network = plan.network
        val quoter = CallQuoter(inputs, sentTransfers, network, clock)
        // A deployment of this Safe, or of one like it, sent and not mined is said as such first, even
        // the payer's own maybe sent: both read the record, and the engine only when a record matches.
        sentAndUnmined(plan)?.let { return refused(it) }
        // Another transaction of the payer's maybe still on its way: refused before anything else is read.
        quoter.heldBack(payer)?.let { return refused(SafeDeploymentRefusal.NotQuoted(it)) }
        val codelessAt = when (val reading = kinds.observeKind(network, plan.address).first()) {
            KindReading.Syncing -> return refused(SafeDeploymentRefusal.Syncing)
            is KindReading.Unavailable -> return refused(SafeDeploymentRefusal.Unavailable(reading.reason))
            is KindReading.Verified -> when (val kind = reading.kind) {
                AddressKind.Eoa -> reading.blockNumber
                else -> return refused(SafeDeploymentRefusal.AlreadyDeployed(kind))
            }
        }
        val native = when (val read = NativeRead.of(nativeBalances.observeBalance(network, payer).first())) {
            is NativeRead.Before -> read
            is NativeRead.NotRead -> return refused(SafeDeploymentRefusal.NotQuoted(read.refusal))
        }
        // What the run returned, to tell a prediction the chain disagrees with from a revert.
        var returned: String? = null
        val factory = plan.release.proxyFactory
        val quoting = quoter.quote(payer, factory, UInt256.ZERO, plan.deployData, level, native) {
            returned = it
            SafeCreation.returnedAddress(it) == plan.address
        }
        return when (quoting) {
            is CallQuoting.Quoted -> SafeDeploymentQuoting.Quoted(
                SafeDeploymentQuote(plan, payer, quoting.quote, codelessAt),
            )
            is CallQuoting.Refused -> {
                val ran = returned
                if (ran != null && SafeCreation.returnedAddress(ran) != plan.address) {
                    refused(SafeDeploymentRefusal.AddressMismatch(SafeCreation.returnedAddress(ran)))
                } else {
                    refused(SafeDeploymentRefusal.NotQuoted(quoting.refusal))
                }
            }
        }
    }

    /**
     * [SafeDeploymentRefusal.AlreadySent] when a deployment to [plan]'s address that an account of
     * this wallet sent is recorded and not mined, and [SafeDeploymentRefusal.SameSafeSent] when one
     * of a Safe with [plan]'s owners, in their order, and its threshold is: within its pool lifetime
     * ([RECORD_LIFETIME_SECONDS]), and its sender's verified count of mined transactions not past
     * it. A count that cannot be verified now is the refusal it is. Null when nothing is pending.
     */
    private suspend fun sentAndUnmined(plan: SafePlan): SafeDeploymentRefusal? {
        val since = clock() - RECORD_LIFETIME_SECONDS
        val sent = (sentTransfers.observe().first() as? SentTransferList.Loaded)?.transfers.orEmpty()
            .filter { it.network == plan.network && it.sentAt >= since }
            .mapNotNull { record -> (record.action as? SentAction.SafeDeployment)?.let { record to it } }
            .filter { (_, action) ->
                action.safe == plan.address || (action.owners == plan.owners && action.threshold == plan.threshold)
            }
        for ((record, action) in sent) {
            when (val mined = inputs.minedNonce(plan.network, record.account)) {
                is NonceReading.Read -> if (record.nonce >= mined.nonce) {
                    return if (action.safe == plan.address) {
                        SafeDeploymentRefusal.AlreadySent
                    } else {
                        SafeDeploymentRefusal.SameSafeSent(action.safe)
                    }
                }
                is NonceReading.Unavailable -> return SafeDeploymentRefusal.Unavailable(mined.reason)
            }
        }
        return null
    }
}

/** The outcome of [QuoteSafeDeployment]. */
sealed interface SafeDeploymentQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: SafeDeploymentQuote) : SafeDeploymentQuoting

    /** Not quoted: [refusal]. */
    data class Refused(val refusal: SafeDeploymentRefusal) : SafeDeploymentQuoting
}

/**
 * Sends a reviewed deployment ([SafeDeploymentQuote]): its transaction signed by the payer's key
 * as [unlock] opens it and broadcast, exactly as quoted, and recorded once a peer took it as the
 * deployment of its Safe ([SentAction.SafeDeployment]) — what keeps its nonce, what its status
 * shows, and what [ListDeployedSafe] lists once it is mined. As every transaction this wallet
 * sends ([SendTransfer]); a retry goes through [RebroadcastTransfer]. [clock] is in unix seconds.
 */
class DeploySafe(
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(quote: SafeDeploymentQuote, unlock: Unlock): TransferSending {
        val plan = quote.plan
        return signAndSend(
            signer,
            broadcaster,
            sentTransfers,
            plan.network,
            quote.call,
            SentAction.SafeDeployment(plan.address, plan.owners, plan.threshold, plan.release.version, plan.label),
            clock,
            unlock,
        )
    }
}

/**
 * Adds a Safe this wallet deployed to the account list (docs/wallet-ux.md, screen 15: "after
 * deployment the Safe is detected like a watched one before it is listed"): once the light client
 * proves code at its address — it waits for that, as long as it is asked to — a Safe with the
 * deployment's owners and threshold is watched ([WatchAddress], which detects it again and adds
 * it as a Safe, named as the deployment says). Anything else at the address is not listed. Its
 * owners held here make it a Safe this wallet can act for, as any watched Safe. A detection that
 * cannot be verified again when it is watched is tried again after [retryDelay], for as long as
 * it is asked to. Asked only once the deployment's receipt says it succeeded: then its code is
 * there by the time the light client's head has its block.
 *
 * A deployment whose receipt says it reverted is [afterRevert]'s: the same call sent by anyone
 * else first (a copy taken from the network's pools) creates the very same Safe at the very same
 * address, and leaves this one to revert. So the address is read once, and listed when it holds the
 * Safe planned; otherwise this transaction created no Safe.
 */
class ListDeployedSafe(
    private val kinds: AddressKinds,
    private val watchAddress: WatchAddress,
    private val retryDelay: Duration = 12.seconds,
) {
    suspend operator fun invoke(network: Network, deployment: SentAction.SafeDeployment): DeployedSafeListing {
        while (true) {
            // Codeless until the head the light client verifies includes the deployment's block.
            val reading = kinds.observeKind(network, deployment.safe)
                .first { it is KindReading.Verified && it.kind != AddressKind.Eoa } as KindReading.Verified
            if (!isThePlannedSafe(reading.kind, deployment)) return DeployedSafeListing.NotTheSafe
            when (val watched = watch(network, deployment)) {
                // Proven a moment ago, not now (a head moved past the proof, the light client stalled).
                null -> delay(retryDelay)
                else -> return watched
            }
        }
    }

    /**
     * After a deployment whose receipt says it reverted: the Safe listed when the address, read
     * once, holds the Safe planned (another sender's copy of the call created it); else
     * [DeployedSafeListing.NoSafe], or [DeployedSafeListing.NotVerified] when the address cannot be
     * read verified right now. It never waits.
     */
    suspend fun afterRevert(network: Network, deployment: SentAction.SafeDeployment): DeployedSafeListing {
        val kind = when (val reading = kinds.observeKind(network, deployment.safe).first()) {
            KindReading.Syncing -> return DeployedSafeListing.NotVerified(null)
            is KindReading.Unavailable -> return DeployedSafeListing.NotVerified(reading.reason)
            is KindReading.Verified -> reading.kind
        }
        if (kind == AddressKind.Eoa) return DeployedSafeListing.NoSafe
        if (!isThePlannedSafe(kind, deployment)) return DeployedSafeListing.NotTheSafe
        return watch(network, deployment) ?: DeployedSafeListing.NotVerified(null)
    }

    /** Whether [kind] is a Safe with [deployment]'s owners, in their order (the Safe keeps it), and threshold. */
    private fun isThePlannedSafe(kind: AddressKind, deployment: SentAction.SafeDeployment): Boolean {
        val state = (kind as? AddressKind.Safe)?.state ?: return false
        return state.owners == deployment.owners && state.threshold == deployment.threshold
    }

    /** The Safe watched under its name, or null when its kind cannot be verified again right now. */
    private suspend fun watch(network: Network, deployment: SentAction.SafeDeployment): DeployedSafeListing? =
        when (val watch = watchAddress(network, deployment.safe, deployment.label)) {
            is AddressWatch.Added -> DeployedSafeListing.Listed(watch.account)
            is AddressWatch.AlreadyInWallet -> DeployedSafeListing.AlreadyInWallet(watch.account)
            AddressWatch.Contract -> DeployedSafeListing.NotTheSafe
            AddressWatch.NameTooLong, AddressWatch.NotSaved -> DeployedSafeListing.NotSaved
            is AddressWatch.NotVerified -> null
        }
}

/** The outcome of [ListDeployedSafe]. */
sealed interface DeployedSafeListing {

    /** [account] is the Safe, listed now. */
    data class Listed(val account: Account) : DeployedSafeListing

    /** The address is in the wallet already, as [account]. */
    data class AlreadyInWallet(val account: Account) : DeployedSafeListing

    /** The address holds something other than the Safe deployed: not listed. */
    data object NotTheSafe : DeployedSafeListing

    /** After a reverted deployment: the address holds no code, so no Safe was created there. */
    data object NoSafe : DeployedSafeListing

    /**
     * After a reverted deployment: whether a Safe is at the address cannot be verified right now
     * ([reason]; null: the light client is syncing). Not listed; it is not asked again.
     */
    data class NotVerified(val reason: ReadUnavailable?) : DeployedSafeListing

    /** The account list could not be saved, or cannot be read: not listed. */
    data object NotSaved : DeployedSafeListing
}

package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.ReadUnavailable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Whether the ENS name each account's name follows ([Account.ensName]) still points to the account's address (issue
 * #67): resolved again as Watch resolved it ([NameResolver], against finalized state, on the account's own network), at
 * once and then every [interval] while observed; every [retryDelay] instead until the first verified answer, since
 * nothing is known of the name before it, while after it the answer stands until a verified one replaces it. One reader
 * per account ([readEach]), started when it follows a name and stopped when it no longer does (a rename, a removal).
 * What the Accounts rows and an account's header show of its name.
 */
class ObserveEnsNames(
    private val accounts: AccountRepository,
    private val names: NameResolver,
    private val interval: Duration = 10.minutes,
    private val retryDelay: Duration = 30.seconds,
) {
    /** Each account that follows an ENS name, by address; one has no entry until its first check. */
    operator fun invoke(): Flow<Map<AccountId, EnsNameCheck>> = accounts.observeAccounts()
        .readEach(
            keys = { list ->
                (list as? AccountList.Loaded)?.accounts.orEmpty()
                    .mapNotNullTo(LinkedHashSet()) { account -> account.ensName?.let { Followed(account.id, it) } }
            },
            read = { followed -> check(followed.id.network, followed.id.address, followed.name) },
        )
        .map { (_, read) -> read.mapKeys { (key, _) -> key.id } }

    /** One account following an ENS [name]: its [id] and the [name], a readEach key. */
    private data class Followed(val id: AccountId, val name: String)

    /** [name] checked against [address] on [network], at once and then again, as [invoke] checks each account's. */
    fun check(network: Network, address: Address, name: String): Flow<EnsNameCheck> = flow {
        // The last verified answer, which an unverifiable check after it leaves standing.
        var verified: EnsNameCheck? = null
        while (true) {
            val check = when (val resolved = names.resolve(network, name)) {
                is NameResolution.Resolved ->
                    if (resolved.address == address) EnsNameCheck.Verified(resolved.blockNumber) else EnsNameCheck.Moved
                NameResolution.NoAddress -> EnsNameCheck.Moved
                // Verified answers too, of a name that may still point here through what is not verified.
                NameResolution.NotAName, NameResolution.OffchainOnly -> EnsNameCheck.Unverifiable
                is NameResolution.Unavailable -> EnsNameCheck.Unavailable(resolved.reason)
            }
            if (check !is EnsNameCheck.Unavailable) {
                verified = check
                emit(check)
            } else if (verified == null) {
                emit(check)
            }
            delay(if (verified == null) retryDelay else interval)
        }
    }
}

/** One answer of [ObserveEnsNames]: whether an account's ENS name still points to its address. */
sealed interface EnsNameCheck {

    /** It does, in the finalized state of [blockNumber]. */
    data class Verified(val blockNumber: Long) : EnsNameCheck

    /**
     * A verified answer that it no longer does: it points to another address, or to none. The
     * name is then not shown as the account's.
     */
    data object Moved : EnsNameCheck

    /**
     * A verified answer that it can no longer be resolved here: its resolver answers only
     * through an off-chain gateway (CCIP-Read), or it is no name this resolver takes. It may
     * still point to the address, unverifiably, so the name is not shown as the account's.
     */
    data object Unverifiable : EnsNameCheck

    /** No verified answer yet, for [reason]; once there was one, that answer stands instead. */
    data class Unavailable(val reason: ReadUnavailable) : EnsNameCheck
}

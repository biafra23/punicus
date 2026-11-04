package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * A Safe as the light client proves it (docs/wallet-ux.md, screen 16): its verified state,
 * which of its owners the wallet holds a key for, and so what the user can do with it.
 */
class ObserveSafe(
    private val kinds: AddressKinds,
    private val accounts: AccountRepository,
) {
    operator fun invoke(safe: AccountId): Flow<SafeReading> =
        combine(kinds.observeKind(safe.network, safe.address), accounts.observeAccounts()) { reading, list ->
            when (reading) {
                KindReading.Syncing -> SafeReading.Checking
                is KindReading.Unavailable -> SafeReading.Unavailable(reading.reason)
                is KindReading.Verified -> when (val kind = reading.kind) {
                    is AddressKind.Safe -> {
                        // Owners held or watched anywhere in the wallet: an address is an owner on any chain,
                        // and its key, held here on one network, signs on every one. One entry per owner:
                        // the one held in the key store where there is one.
                        val inWallet = (list as? AccountList.Loaded)?.accounts.orEmpty()
                            .filter { it.address in kind.state.owners }
                            .groupBy { it.address }
                            .map { (_, entries) ->
                                entries.firstOrNull { it.custody == AccountCustody.InKeyStore } ?: entries.first()
                            }
                        SafeReading.Verified(kind.state, reading.blockNumber, inWallet)
                    }
                    else -> SafeReading.NotASafe
                }
            }
        }
}

/**
 * Every Safe in the wallet, read one by one ([readEach]): a Safe added or removed starts or stops
 * its own reading only. What the Accounts rows show of each.
 */
class ObserveSafes(
    private val accounts: AccountRepository,
    private val observeSafe: ObserveSafe,
) {
    operator fun invoke(): Flow<Map<AccountId, SafeReading>> = accounts.observeAccounts()
        .readEach(
            keys = { list ->
                (list as? AccountList.Loaded)?.accounts.orEmpty()
                    .filter { it.kind == AccountKind.SAFE }
                    .mapTo(LinkedHashSet()) { it.id }
            },
            read = { id -> observeSafe(id) },
        )
        .map { (_, read) -> read }
}

/** One answer of [ObserveSafe]. */
sealed interface SafeReading {

    /** Not read yet. */
    data object Checking : SafeReading

    /**
     * [state], verified at block [blockNumber]; [ownersInWallet], the wallet's accounts that are
     * among its owners, watched or held.
     */
    data class Verified(val state: SafeState, val blockNumber: Long, val ownersInWallet: List<Account>) : SafeReading {
        /** The owners whose keys the wallet holds, in the Safe's order. */
        val ownersHeld: List<Address>
            get() = state.ownersHeld(ownersInWallet)

        /** What the user can do with the Safe, holding [ownersHeld]. */
        val control: SafeControl
            get() = state.control(ownersHeld.size)
    }

    /** The address holds no recognised Safe (any more): never shown as one. */
    data object NotASafe : SafeReading

    /** No verified answer can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: ReadUnavailable) : SafeReading
}

/** What the user can do with a Safe (docs/wallet-ux.md, *Control is a badge*). */
enum class SafeControl {
    /** The wallet holds as many of its owners' keys as its threshold needs. */
    FULL,

    /** Some of its owners' keys, fewer than its threshold. */
    CO_SIGNER,

    /** None of them. */
    WATCH_ONLY,
}

/** The owners of this Safe whose keys the wallet holds: the accounts among [accounts] held in its key store. */
fun SafeState.ownersHeld(accounts: List<Account>): List<Address> {
    val held = accounts.filter { it.custody == AccountCustody.InKeyStore }.mapTo(HashSet()) { it.address }
    return owners.filter { it in held }
}

/** What the user can do with this Safe, holding [held] of its owners' keys. */
fun SafeState.control(held: Int): SafeControl = when {
    held >= threshold -> SafeControl.FULL
    held > 0 -> SafeControl.CO_SIGNER
    else -> SafeControl.WATCH_ONLY
}

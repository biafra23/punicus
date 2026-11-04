package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.KeyDerivation
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.MemoryStep
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Adds a further account of the recovery phrase an account held here comes from, without the
 * words: the "new account from the same phrase" capability. An imported phrase's envelope holds
 * its BIP-39 seed so that further accounts can be derived this way (owner ruling, 2026-10-15;
 * docs/keys-and-signing.md). The account is the phrase's next one along BIP-44's
 * `m/44'/60'/0'/0/i`, i the lowest index whose address the wallet does not hold yet on the
 * network of the account it comes from ([KeyStore.deriveAccount]), so the accounts there are the
 * ones other wallets list for the phrase, in their order, one removed coming back first. It is
 * added on that network, as an
 * [AccountKind.EOA] held in the key store named "Account N", and is held wherever its address
 * is watched, as an import's is ([addHeld]); Add on another network adds it elsewhere.
 *
 * The key store first, then the account list, as one step a cancelled caller does not cut in
 * half, under the app's [WalletChanges]: a derivation never interleaves with an import or a
 * removal. The [WalletPassword] goes to the key store, which opens the account's envelope with
 * it and seals the new one under it; nothing of it is kept.
 */
class DeriveAccount(
    private val keys: KeyStore,
    private val accounts: AccountRepository,
    /** The app's one [WalletChanges]. */
    private val changes: WalletChanges,
) {
    /** Adds the next account of the phrase [from] comes from, on [from]'s network. */
    suspend operator fun invoke(from: AccountId, password: WalletPassword): AccountDerivation =
        withContext(NonCancellable) { changes.serialized { derive(from, password) } }

    private suspend fun derive(from: AccountId, password: WalletPassword): AccountDerivation {
        val listed = when (val list = accounts.observeAccounts().first()) {
            is AccountList.Unreadable -> return AccountDerivation.ListUnreadable
            is AccountList.Loaded -> list.accounts
        }
        val account = listed.firstOrNull { it.id == from } ?: return AccountDerivation.NotFound
        if (account.kind != AccountKind.EOA || account.custody != AccountCustody.InKeyStore) {
            return AccountDerivation.NoKey
        }
        // Per network: an account of the phrase held only elsewhere is still the next one here.
        val held = listed
            .filter { it.network == from.network && it.custody == AccountCustody.InKeyStore }
            .mapTo(HashSet()) { it.address }
        return when (val stored = keys.deriveAccount(from.address, password, held)) {
            KeyDerivation.NoKey -> AccountDerivation.NoKey
            KeyDerivation.WrongPassword -> AccountDerivation.WrongPassword
            KeyDerivation.Unavailable -> AccountDerivation.Unavailable
            KeyDerivation.NotSaved -> AccountDerivation.KeyNotSaved
            is KeyDerivation.NotEnoughMemory -> AccountDerivation.NotEnoughMemory(stored.step)
            is KeyDerivation.Stored -> when (val added = accounts.addHeld(stored.address, listOf(from.network))) {
                is HeldAdd.InWallet -> AccountDerivation.Added(added.account, stored.index, stored.steppedDown)
                HeldAdd.ListNotSaved -> AccountDerivation.ListNotSaved
            }
        }
    }
}

/** The outcome of [DeriveAccount]. */
sealed interface AccountDerivation {

    /**
     * [account] is in the wallet and held in the key store: account [index] of the phrase (0 the
     * first). A new entry, or the address watched before and held from here on. [steppedDown] when
     * its envelope was sealed at a lighter password cost than this device's own, which the user is told.
     */
    data class Added(val account: Account, val index: Int, val steppedDown: Boolean = false) : AccountDerivation

    /** The account to derive from is not in the wallet (any more); nothing changed. */
    data object NotFound : AccountDerivation

    /** This device holds no key for the account to derive from (watched, a Safe, or its key gone); nothing changed. */
    data object NoKey : AccountDerivation

    /** That is not the wallet password; nothing changed. */
    data object WrongPassword : AccountDerivation

    /** The key store cannot open the account's key on this device; nothing changed. */
    data object Unavailable : AccountDerivation

    /** The new key could not be stored; nothing changed. */
    data object KeyNotSaved : AccountDerivation

    /** Not enough free memory at [step], to open the account's key or to protect the new one; nothing changed. */
    data class NotEnoughMemory(val step: MemoryStep) : AccountDerivation

    /** The account list cannot be read; nothing changed. */
    data object ListUnreadable : AccountDerivation

    /**
     * The new key is stored, but the account list could not be saved, so its account is not in it.
     * Adding an account from the same phrase again adds this one.
     */
    data object ListNotSaved : AccountDerivation
}

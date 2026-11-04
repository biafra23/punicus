package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyDerivation
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.NewMnemonic
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** An [AccountRepository] over a list a test sets, which can be made [unreadable] or to [failSaves]. */
internal class ListAccountRepository(vararg initial: Account) : AccountRepository {
    val accounts = MutableStateFlow(initial.toList())
    var unreadable = false
    var failSaves = false

    override fun observeAccounts(): Flow<AccountList> = accounts.map {
        if (unreadable) AccountList.Unreadable(AccountList.Reason.READ_FAILED) else AccountList.Loaded(it)
    }

    override suspend fun add(account: Account): AccountAdd = when {
        unreadable || failSaves -> AccountAdd.NotSaved
        accounts.value.any { it.id == account.id } -> AccountAdd.AlreadyPresent
        else -> {
            accounts.value = accounts.value + account
            AccountAdd.Added
        }
    }

    override suspend fun setCustody(id: AccountId, custody: AccountCustody): AccountChange =
        change(id) { current -> current.map { if (it.id == id) it.copy(custody = custody) else it } }

    override suspend fun rename(id: AccountId, label: String): AccountChange = change(id) { current ->
        current.map { if (it.id == id) it.copy(label = label, ensName = null) else it }
    }

    override suspend fun remove(id: AccountId): AccountChange = change(id) { current ->
        current.filterNot { it.id == id }
    }

    private fun change(id: AccountId, edit: (List<Account>) -> List<Account>): AccountChange = when {
        unreadable || failSaves -> AccountChange.NotSaved
        accounts.value.none { it.id == id } -> AccountChange.NotFound
        else -> {
            accounts.value = edit(accounts.value)
            AccountChange.Changed
        }
    }
}

/**
 * A [KeyStore] holding keys for the addresses in [stored], whose deletions can be made to
 * fail; an import stores the key of [importing], after [importDelayMillis].
 */
internal class RecordingKeyStore(stored: List<Address>) : KeyStore {
    override suspend fun newMnemonic(): NewMnemonic = NewMnemonic.Unavailable

    override suspend fun enableBiometrics(password: WalletPassword, confirmation: String): BiometricChange =
        BiometricChange.NotOnThisDevice

    override suspend fun disableBiometrics(): BiometricChange = BiometricChange.NotOnThisDevice

    val stored = stored.toMutableSet()
    val deleted = mutableListOf<Address>()
    var failDeletes = false
    var importing: Address? = null
    var importDelayMillis = 0L

    /** What a derivation answers, given the addresses it was told are held ([heldAtDerivation]). */
    var derivation: (Set<Address>) -> KeyDerivation = { KeyDerivation.Unavailable }
    var heldAtDerivation: Set<Address>? = null

    override suspend fun describe(): KeyStoreDescription = error("not used by these tests")

    override suspend fun deriveAccount(from: Address, password: WalletPassword, held: Set<Address>): KeyDerivation {
        heldAtDerivation = held
        return derivation(held).also { if (it is KeyDerivation.Stored) stored += it.address }
    }

    override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport {
        val address = importing ?: error("no import set up")
        delay(importDelayMillis)
        stored += address
        return KeyImport.Stored(address, KeyProtection.HARDWARE)
    }

    override suspend fun delete(address: Address): KeyDeletion {
        deleted += address
        return when {
            failDeletes -> KeyDeletion.Failed
            stored.remove(address) -> KeyDeletion.Deleted
            else -> KeyDeletion.NotFound
        }
    }
}

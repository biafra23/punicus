package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.KeyDerivation
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.MemoryStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** [DeriveAccount] over fake ports. */
class DeriveAccountTest {

    private val first = Account(
        Network.ETHEREUM,
        Address.of("0x" + "11".repeat(20)),
        AccountKind.EOA,
        "Account 1",
        AccountCustody.InKeyStore,
    )
    private val second = Address.of("0x" + "22".repeat(20))
    private val other = Address.of("0x" + "33".repeat(20))
    private val third = Address.of("0x" + "44".repeat(20))
    private val password = WalletPassword("correct horse battery")

    private val repository = ListAccountRepository(first)
    private val keys = RecordingKeyStore(listOf(first.address))
    private val derive = DeriveAccount(keys, repository, WalletChanges())

    private fun storedAt(address: Address, index: Int) = KeyDerivation.Stored(address, index, KeyProtection.HARDWARE)

    @Test
    fun theNextAccountIsAddedOnTheNetworkOfTheOneItComesFrom() = runTest {
        keys.derivation = { storedAt(second, 1) }

        val derived = derive(first.id, password)

        val added = Account(Network.ETHEREUM, second, AccountKind.EOA, "Account 2", AccountCustody.InKeyStore)
        assertEquals(AccountDerivation.Added(added, index = 1), derived)
        assertEquals(listOf(first, added), repository.accounts.value)
        assertEquals(setOf(first.address), keys.heldAtDerivation, "the key store skips what the wallet holds")
    }

    @Test
    fun theKeyStoreIsToldTheAddressesHeldOnTheAccountsNetwork() = runTest {
        val gnosis = first.copy(network = Network.GNOSIS)
        val held = Account(Network.ETHEREUM, other, AccountKind.EOA, "Other", AccountCustody.InKeyStore)
        val elsewhere = Account(Network.GNOSIS, third, AccountKind.EOA, "Elsewhere", AccountCustody.InKeyStore)
        val watched = Account(Network.ETHEREUM, second, AccountKind.EOA, "Watched", AccountCustody.Watched)
        repository.accounts.value = listOf(first, gnosis, held, elsewhere, watched)
        keys.derivation = { storedAt(second, 1) }

        val derived = derive(first.id, password)

        // Held on this network only: an account held on another one alone is still to come here.
        assertEquals(setOf(first.address, other), keys.heldAtDerivation)
        // The address was watched: it is held from here on, keeping its name.
        assertEquals(AccountDerivation.Added(watched.copy(custody = AccountCustody.InKeyStore), index = 1), derived)
        assertEquals(5, repository.accounts.value.size)
    }

    @Test
    fun onlyAnExternallyOwnedAccountHeldHereCanBeDerivedFrom() = runTest {
        val watched = Account(Network.ETHEREUM, second, AccountKind.EOA, "Watched", AccountCustody.Watched)
        val safe = Account(Network.ETHEREUM, other, AccountKind.SAFE, "Safe", AccountCustody.Watched)
        repository.accounts.value = listOf(first, watched, safe)

        assertEquals(AccountDerivation.NoKey, derive(watched.id, password))
        assertEquals(AccountDerivation.NoKey, derive(safe.id, password))
        assertEquals(AccountDerivation.NotFound, derive(first.id.copy(network = Network.SEPOLIA), password))
        assertNull(keys.heldAtDerivation, "the key store was not asked")
    }

    @Test
    fun theKeyStoresRefusalsChangeNothing() = runTest {
        val refusals = mapOf(
            KeyDerivation.NoKey to AccountDerivation.NoKey,
            KeyDerivation.WrongPassword to AccountDerivation.WrongPassword,
            KeyDerivation.Unavailable to AccountDerivation.Unavailable,
            KeyDerivation.NotSaved to AccountDerivation.KeyNotSaved,
            KeyDerivation.NotEnoughMemory(MemoryStep.CHECK_PASSWORD) to
                AccountDerivation.NotEnoughMemory(MemoryStep.CHECK_PASSWORD),
        )
        for ((refusal, outcome) in refusals) {
            keys.derivation = { refusal }
            assertEquals(outcome, derive(first.id, password))
        }
        assertEquals(listOf(first), repository.accounts.value)
    }

    @Test
    fun aListThatCannotBeReadOrSavedIsSaid() = runTest {
        keys.derivation = { storedAt(second, 1) }
        repository.unreadable = true
        assertEquals(AccountDerivation.ListUnreadable, derive(first.id, password))
        assertNull(keys.heldAtDerivation, "nothing is stored while the list cannot be read")

        repository.unreadable = false
        repository.failSaves = true
        assertEquals(AccountDerivation.ListNotSaved, derive(first.id, password))

        // Its key is stored, its account not listed: the next try asks for the same one, and adds it.
        repository.failSaves = false
        val derived = derive(first.id, password)
        assertEquals(setOf(first.address), keys.heldAtDerivation)
        assertEquals(second, (derived as AccountDerivation.Added).account.address)
    }

    @Test
    fun aSealThatSteppedDownIsSaid() = runTest {
        keys.derivation = { KeyDerivation.Stored(second, 1, KeyProtection.PASSWORD_ONLY, steppedDown = true) }

        val derived = derive(first.id, password)

        assertEquals(true, (derived as AccountDerivation.Added).steppedDown)
    }
}

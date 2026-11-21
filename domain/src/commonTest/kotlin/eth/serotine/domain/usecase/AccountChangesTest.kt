package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppIsolation
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PerAppAccount
import eth.serotine.domain.model.WalletPassword
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** [ObserveAccount], [RenameAccount] and [RemoveAccount] over fake ports. */
class AccountChangesTest {

    private val keyed = Account(
        Network.SEPOLIA,
        Address.of("0x" + "11".repeat(20)),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )
    private val watched =
        Account(Network.SEPOLIA, Address.of("0x" + "22".repeat(20)), AccountKind.EOA, "Savings", AccountCustody.Watched)
    private val stranger = Address.of("0x" + "33".repeat(20))

    private val repository = ListAccountRepository(keyed, watched)
    private val keys = RecordingKeyStore(listOf(keyed.address))

    private fun removeFrom(repository: ListAccountRepository) =
        RemoveAccount(repository, keys, WalletChanges(), MemoryAliases(), MemorySites())

    @Test
    fun observeAccountFollowsOneEntryUntilItIsGone() = runTest {
        val seen = mutableListOf<AccountLookup>()
        val collecting = launch { ObserveAccount(repository)(keyed.id).take(3).toList(seen) }
        testScheduler.runCurrent()
        repository.accounts.value = listOf(keyed.copy(label = "Spending"), watched)
        testScheduler.runCurrent()
        // A change to another entry is not a change to this one.
        repository.accounts.value = listOf(keyed.copy(label = "Spending"))
        testScheduler.runCurrent()
        repository.accounts.value = emptyList()
        collecting.join()

        assertEquals(
            listOf(
                AccountLookup.Found(keyed),
                AccountLookup.Found(keyed.copy(label = "Spending")),
                AccountLookup.NotInList,
            ),
            seen,
        )
    }

    @Test
    fun observeAccountSaysWhenTheListCannotBeRead() = runTest {
        repository.unreadable = true

        assertEquals(AccountLookup.ListUnavailable, ObserveAccount(repository)(keyed.id).first())
    }

    @Test
    fun renameTrimsTheNameAndRefusesABlankOrOverlongOne() = runTest {
        val rename = RenameAccount(repository)

        assertEquals(AccountRename.Renamed, rename(keyed.id, "  Spending  "))
        assertEquals("Spending", repository.accounts.value.first().label)
        assertEquals(AccountRename.Blank, rename(keyed.id, "   "))
        assertEquals(AccountRename.TooLong, rename(keyed.id, "x".repeat(RenameAccount.MAX_LABEL_LENGTH + 1)))
        assertEquals(AccountRename.Renamed, rename(keyed.id, "x".repeat(RenameAccount.MAX_LABEL_LENGTH)))
        assertEquals(AccountRename.NotFound, rename(stranger.onSepolia, "Anything"))
    }

    @Test
    fun aRenameThatCannotBeSavedSaysSo() = runTest {
        repository.failSaves = true

        assertEquals(AccountRename.NotSaved, RenameAccount(repository)(keyed.id, "Spending"))
        assertEquals("Daily", repository.accounts.value.first().label)
    }

    @Test
    fun removingAnAccountHeldInTheKeyStoreDeletesItsKeyAfterTheEntry() = runTest {
        assertEquals(AccountRemoval.Removed, removeFrom(repository)(keyed.id))

        assertEquals(listOf(watched), repository.accounts.value)
        assertEquals(listOf(keyed.address), keys.deleted)
    }

    @Test
    fun removingAWatchedAccountLeavesTheOtherKeysAlone() = runTest {
        assertEquals(AccountRemoval.Removed, removeFrom(repository)(watched.id))

        assertEquals(listOf(keyed), repository.accounts.value)
        assertEquals(emptyList(), keys.deleted, "a watched account has no key to delete")
    }

    @Test
    fun anImportAndARemovalOfTheSameAddressNeverInterleave() = runTest {
        val changes = WalletChanges()
        keys.importing = watched.address
        keys.importDelayMillis = 1_000
        val import = async { ImportMnemonicAccount(
            keys,
            repository,
            changes,
        )(Mnemonic("words"), WalletPassword("pw"), listOf(Network.SEPOLIA)) }
        runCurrent() // the import holds the wallet while it stores the key
        val removal = async { RemoveAccount(repository, keys, changes, MemoryAliases(), MemorySites())(watched.id) }
        advanceUntilIdle()

        assertEquals(MnemonicImport.KeyAdded(watched.copy(custody = AccountCustody.InKeyStore)), import.await())
        // The removal ran after it: it found the entry held, and its key went with it.
        assertEquals(AccountRemoval.Removed, removal.await())
        assertEquals(listOf(keyed), repository.accounts.value)
        assertEquals(setOf(keyed.address), keys.stored, "no key outlives its account, none is lost under one")
    }

    @Test
    fun aKeyThatCannotBeDeletedIsReportedWithTheEntryGone() = runTest {
        keys.failDeletes = true

        assertEquals(AccountRemoval.RemovedKeyKept, removeFrom(repository)(keyed.id))
        assertEquals(listOf(watched), repository.accounts.value)
    }

    @Test
    fun anEntryThatCannotBeRemovedKeepsItsKey() = runTest {
        repository.failSaves = true

        assertEquals(AccountRemoval.NotSaved, removeFrom(repository)(keyed.id))
        assertEquals(emptyList(), keys.deleted, "the key never goes before the entry")
    }

    @Test
    fun removingAnAddressNotInTheWalletChangesNothing() = runTest {
        assertEquals(AccountRemoval.NotFound, removeFrom(repository)(stranger.onSepolia))
        assertEquals(emptyList(), keys.deleted)

        repository.unreadable = true
        assertEquals(AccountRemoval.NotSaved, removeFrom(repository)(keyed.id))
    }

    @Test
    fun theLastAccountOfAPerAppAddressTakesItsAliasesWithIt() = runTest {
        val site = testHost("uniswap.org")
        val mark = PerAppAccount(site, AppIsolation.pathOf(site), keyed.address)
        val onEthereum = Account(
            Network.ETHEREUM,
            Address.of("0x" + "6".repeat(40)),
            AccountKind.EOA,
            "uniswap.org",
            AccountCustody.InKeyStore,
            perApp = mark,
        )
        val onGnosis = onEthereum.copy(network = Network.GNOSIS)
        // The same address watched on Sepolia: no per-app account, so it holds no alias up.
        val watchedThere = onEthereum.copy(network = Network.SEPOLIA, custody = AccountCustody.Watched, perApp = null)
        val repository = ListAccountRepository(onEthereum, onGnosis, watchedThere)
        val app = testHost("uniswap.app")
        val aliases = MemoryAliases(mapOf(app to onEthereum.address))
        val keysHere = RecordingKeyStore(listOf(onEthereum.address))
        val remove = RemoveAccount(repository, keysHere, WalletChanges(), aliases, MemorySites())
        remove(onEthereum.id)
        assertEquals(mapOf(app to onEthereum.address), aliases.linked.value, "still held on Gnosis Chain")
        remove(onGnosis.id)
        assertEquals(emptyMap(), aliases.linked.value, "restoring it later never links uniswap.app again unasked")
    }

    @Test
    fun aRemovedAccountIsForgottenByEverySiteThatRememberedIt() = runTest {
        val repository = ListAccountRepository(keyed, watched)
        val sites = MemorySites()
        val dapp = testHost("dapp.test")
        val other = testHost("other.test")
        sites.remembered.value = mapOf(dapp to listOf(keyed.id, watched.id), other to listOf(keyed.id))
        RemoveAccount(repository, keys, WalletChanges(), MemoryAliases(), sites)(keyed.id)
        assertEquals(mapOf(dapp to listOf(watched.id)), sites.remembered.value, "and a site left with none goes")
    }

    @Test
    fun aMemoryThatCannotBeSavedTakesNothingFromTheRemovalAndARemovalThatFailedForgetsNothing() = runTest {
        val dapp = testHost("dapp.test")
        val sites = MemorySites()
        sites.remembered.value = mapOf(dapp to listOf(keyed.id))
        sites.failSaves = true
        val repository = ListAccountRepository(keyed, watched)
        val removal = RemoveAccount(repository, keys, WalletChanges(), MemoryAliases(), sites)
        assertEquals(AccountRemoval.Removed, removal(keyed.id))
        assertEquals(mapOf(dapp to listOf(keyed.id)), sites.remembered.value, "kept, said by nothing but harmless")

        sites.failSaves = false
        val unsaved = ListAccountRepository(keyed, watched).also { it.failSaves = true }
        val failing = RemoveAccount(unsaved, keys, WalletChanges(), MemoryAliases(), sites)
        assertEquals(AccountRemoval.NotSaved, failing(keyed.id))
        assertEquals(mapOf(dapp to listOf(keyed.id)), sites.remembered.value, "a removal that failed forgets nothing")
    }
}

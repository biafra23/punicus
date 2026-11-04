package eth.serotine.ui.mnemonicimport

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.model.Network
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
import eth.serotine.domain.repository.MemoryStep
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.PasswordCost
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ImportMnemonicAccount
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.WalletChanges
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.ui.components.PasswordStep
import eth.serotine.ui.mnemonicimport.MnemonicImportUiState.Entering
import eth.serotine.ui.mnemonicimport.MnemonicImportUiState.Imported
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class MnemonicImportViewModelTest {

    private val twelveWords = "one two three four five six seven eight nine ten eleven twelve"
    private val address = Address.of("0x" + "12".repeat(20))

    private val password = "correct horse"
    private val keys = FakeKeyStore()
    private val repository = FakeAccountRepository()
    private val store = ViewModelStore()

    // Lazy: its init asks the key store on Dispatchers.Main, which @BeforeTest sets.
    private val viewModel: MnemonicImportViewModel by lazy {
        ViewModelProvider.create(
            owner = object : ViewModelStoreOwner {
                override val viewModelStore = store
            },
            factory = viewModelFactory {
                initializer {
                    val import = ImportMnemonicAccount(keys, repository, WalletChanges())
                    MnemonicImportViewModel(
                        import,
                        DescribeKeyStore(keys),
                        ObserveEnabledNetworks(networks),
                        SetNetworkEnabled(networks),
                    )
                }
            },
        )[MnemonicImportViewModel::class]
    }

    /** Words and the existing wallet password: everything an import needs. */
    private fun typeWordsAndPassword() {
        viewModel.onPhraseChange(twelveWords)
        viewModel.onPasswordChange(password)
        viewModel.onNetworkToggle(Network.SEPOLIA.chainId)
    }

    private val networks = MemoryNetworkSettings()

    @BeforeTest
    fun routeMainThroughTheTestScheduler() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun startsEmptyAndCountsTheWordsAsTheyAreTyped() {
        val start = assertIs<Entering>(viewModel.uiState.value)
        assertEquals("", start.phrase.text)
        assertEquals(0, start.wordCount)
        assertFalse(start.wordsAndPasswordReady)

        viewModel.onPhraseChange("one two three four five six seven eight nine ten eleven")
        assertEquals(11, entering().wordCount)
        assertFalse(entering().wordsAndPasswordReady)

        viewModel.onPhraseChange("$twelveWords ")
        assertEquals("$twelveWords ", entering().phrase.text, "the field shows back exactly what was typed")
        assertEquals(12, entering().wordCount)
        assertFalse(entering().wordsAndPasswordReady, "not without the wallet password")

        viewModel.onPasswordChange(password)
        assertTrue(entering().wordsAndPasswordReady)
    }

    @Test
    fun theKeyOfAWatchedAccountIsAnImportThatSucceeded() = runTest {
        repository.accounts.value = listOf(
        Account(Network.SEPOLIA, address, AccountKind.EOA, "Savings", AccountCustody.Watched),
    )
        keys.next = CompletableDeferred(KeyImport.Stored(address, KeyProtection.HARDWARE))
        typeWordsAndPassword()

        viewModel.onImport()
        runCurrent()

        assertEquals(Imported("Savings"), viewModel.uiState.value)
        assertEquals(AccountCustody.InKeyStore, repository.accounts.value.single().custody)
    }

    @Test
    fun aSuccessfulImportForgetsTheWordsAndReportsTheAccount() = runTest {
        val pending = CompletableDeferred<KeyImport>()
        keys.next = pending
        typeWordsAndPassword()

        viewModel.onImport()
        runCurrent()
        assertTrue(entering().importing)
        assertFalse(entering().canImport, "no second attempt while one runs")
        viewModel.onPhraseChange("changed while importing")
        assertEquals(twelveWords, entering().phrase.text, "the words are frozen while an attempt runs")

        pending.complete(KeyImport.Stored(address, KeyProtection.HARDWARE))
        runCurrent()

        assertEquals(Imported("Account 1"), viewModel.uiState.value)
        assertEquals(twelveWords, keys.received.single().text)
        assertEquals(
            listOf(Account(Network.SEPOLIA, address, AccountKind.EOA, "Account 1", AccountCustody.InKeyStore)),
            repository.accounts.value,
        )
    }

    @Test
    fun refusedWordsStayForCorrectionWithTheReason() = runTest {
        keys.next = CompletableDeferred(KeyImport.Invalid(MnemonicProblem.UnknownWord(position = 3)))
        typeWordsAndPassword()
        val typed = entering().phrase

        viewModel.onImport()
        runCurrent()

        assertSame(typed, entering().phrase)
        assertFalse(entering().importing)
        assertEquals("Word 3 is not in the BIP-39 English word list.", entering().error)

        viewModel.onPhraseChange("$twelveWords!")
        assertNull(entering().error, "editing clears the last refusal")
    }

    @Test
    fun anAccountAlreadyInTheWalletIsAnError() = runTest {
        repository.accounts.value = listOf(
        Account(Network.SEPOLIA, address, AccountKind.EOA, "Daily", AccountCustody.InKeyStore),
    )
        keys.next = CompletableDeferred(KeyImport.Stored(address, KeyProtection.HARDWARE))
        typeWordsAndPassword()

        viewModel.onImport()
        runCurrent()

        assertEquals(
            "“Daily” is in the wallet already; its key is stored on this device now.",
            entering().error,
        )
    }

    @Test
    fun aFaultInTheKeyAdapterIsAnErrorNotAHang() = runTest {
        keys.next = CompletableDeferred<KeyImport>().apply {
            completeExceptionally(IllegalStateException("could not load the native library"))
        }
        typeWordsAndPassword()

        viewModel.onImport()
        runCurrent()

        assertFalse(entering().importing)
        assertEquals(
            "The key store failed. Nothing was added. Try again; if it keeps failing, this device cannot " +
                "store the key.",
            entering().error,
        )
        assertTrue(entering().canImport, "the user can try again")
    }

    @Test
    fun notEnoughMemoryToProtectTheKeyIsSaidAsSuch() = runTest {
        keys.next = CompletableDeferred(KeyImport.NotEnoughMemory(MemoryStep.PROTECT))
        typeWordsAndPassword()

        viewModel.onImport()
        runCurrent()

        assertEquals("Not enough free memory to protect the key: close other apps and try again.", entering().error)
        assertEquals(twelveWords, entering().phrase.text)
        assertTrue(entering().canImport, "the user can try again")
    }

    @Test
    fun notEnoughMemoryToCheckThePasswordIsSaidAsThat() = runTest {
        keys.next = CompletableDeferred(KeyImport.NotEnoughMemory(MemoryStep.CHECK_PASSWORD))
        typeWordsAndPassword()

        viewModel.onImport()
        runCurrent()

        assertEquals(
            "Not enough free memory to check the password: close other apps and try again. Nothing was added.",
            entering().error,
        )
    }

    @Test
    fun aKeyProtectedAtALighterCostIsToldBeforeTheScreenCloses() = runTest {
        keys.next = CompletableDeferred(KeyImport.Stored(address, KeyProtection.SOFTWARE_KEY_STORE, steppedDown = true))
        typeWordsAndPassword()

        viewModel.onImport()
        runCurrent()

        assertEquals(
            Imported(
                "Account 1",
                "There was not enough free memory just now, so the password protection of this key uses less " +
                    "memory and more passes.",
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun aPasswordStretchedWithLessMemorySaysSoUnderThePassword() {
        keys.description = {
            KeyStoreDescription.Ready(
                passwordSet = false,
                KeyProtection.SOFTWARE_KEY_STORE,
                passwordCost = PasswordCost.LESS_MEMORY,
            )
        }
        typeWordsAndPassword()

        val step = assertIs<PasswordStep.New>(entering().step)
        assertTrue(
            step.protection.endsWith(
                "This device has little memory, so the password protection uses less memory and more passes.",
            ),
            step.protection,
        )
    }

    @Test
    fun aDeviceTheKeyAdapterCannotRunOnIsAnError() = runTest {
        keys.next = CompletableDeferred(KeyImport.Unavailable)
        typeWordsAndPassword()

        viewModel.onImport()
        runCurrent()

        assertEquals(
            "The key store failed. Nothing was added. Try again; if it keeps failing, this device cannot " +
                "store the key.",
            entering().error,
        )
        assertEquals(twelveWords, entering().phrase.text)
    }

    @Test
    fun hidingTheScreenDropsTheWords() {
        viewModel.onPhraseChange(twelveWords)

        viewModel.onHidden()

        assertEquals("", entering().phrase.text)
    }

    @Test
    fun hidingTheScreenDropsTheWordsButFinishesARunningAttempt() = runTest {
        val pending = CompletableDeferred<KeyImport>()
        keys.next = pending
        typeWordsAndPassword()
        viewModel.onImport()
        runCurrent()

        viewModel.onHidden()
        assertEquals("", entering().phrase.text)
        assertEquals("", entering().password.text)
        assertTrue(entering().importing, "still running, without the words")
        pending.complete(KeyImport.Stored(address, KeyProtection.HARDWARE))
        runCurrent()

        // Half an import would leave a stored key without its account.
        assertEquals(Imported("Account 1"), viewModel.uiState.value)
        assertEquals(
            listOf(Account(Network.SEPOLIA, address, AccountKind.EOA, "Account 1", AccountCustody.InKeyStore)),
            repository.accounts.value,
        )
    }

    @Test
    fun aLateRefusalAfterHidingDoesNotBringTheWordsBack() = runTest {
        val pending = CompletableDeferred<KeyImport>()
        keys.next = pending
        typeWordsAndPassword()
        viewModel.onImport()
        runCurrent()

        viewModel.onHidden()
        pending.complete(KeyImport.Invalid(MnemonicProblem.Checksum))
        runCurrent()

        assertEquals("", entering().phrase.text)
        assertEquals("", entering().password.text)
        assertFalse(entering().importing)
    }

    @Test
    fun aKeyStoredWithoutItsListEntryIsSaidAndThePasswordIsNowTheWallets() = runTest {
        keys.passwordSet = false
        repository.failSaves = true
        keys.next = CompletableDeferred(KeyImport.Stored(address, KeyProtection.HARDWARE))
        viewModel.onPhraseChange(twelveWords)
        viewModel.onPasswordChange(password)
        viewModel.onRepeatChange(password)
        viewModel.onNetworkToggle(Network.SEPOLIA.chainId)

        viewModel.onImport()
        runCurrent()

        assertEquals(
            "The key is stored, but the account list could not be saved. Add the same words again to finish.",
            entering().error,
        )
        assertEquals(twelveWords, entering().phrase.text)
        assertIs<PasswordStep.Existing>(entering().step, "the key store is asked again: a password is set now")
        assertTrue(entering().canImport, "the same words and password finish the job")
    }

    @Test
    fun aKeyStoreThatDoesNotWorkHereIsSaidAndNothingCanBeAdded() {
        keys.description = { KeyStoreDescription.Unavailable }
        typeWordsAndPassword()

        assertEquals(PasswordStep.Unavailable, entering().step)
        assertFalse(entering().canImport)
    }

    @Test
    fun aKeyStoreThatThrowsWhileDescribingIsUnavailableNotACrash() {
        keys.description = { throw IllegalStateException("the secure element is gone") }
        typeWordsAndPassword()

        assertEquals(PasswordStep.Unavailable, entering().step)
        assertFalse(entering().canImport)
    }

    @Test
    fun aWordCountBip39DoesNotDefineIsNotSubmitted() = runTest {
        viewModel.onPhraseChange("only three words")

        viewModel.onImport()
        runCurrent()

        assertEquals(emptyList(), keys.received)
    }

    @Test
    fun clearingTheViewModelDropsTheWords() {
        viewModel.onPhraseChange(twelveWords)

        store.clear()

        assertEquals("", entering().phrase.text)
    }

    @Test
    fun theFirstImportSetsThePasswordTypedTwice() {
        keys.passwordSet = false
        viewModel.onPhraseChange(twelveWords)
        assertIs<PasswordStep.New>(entering().step)

        viewModel.onPasswordChange("short")
        assertEquals("At least 8 characters", entering().passwordHint)
        viewModel.onPasswordChange(password)
        viewModel.onRepeatChange("correct hors")
        assertEquals("The two passwords differ", entering().passwordHint)
        assertFalse(entering().wordsAndPasswordReady)

        viewModel.onRepeatChange(password)
        assertEquals(null, entering().passwordHint)
        assertTrue(entering().wordsAndPasswordReady)
    }

    @Test
    fun aComputerWhoseTpmIsNotUsedYetIsToldSo() {
        keys.description = { KeyStoreDescription.Ready(passwordSet = false, KeyProtection.PASSWORD_ONLY_TPM_NOT_USED) }

        val protection = assertIs<PasswordStep.New>(entering().step).protection
        assertTrue("does not use this computer's TPM yet" in protection, protection)
    }

    @Test
    fun theDeviceProtectionIsSaid() {
        assertTrue(assertIs<PasswordStep.Existing>(entering().step).protection.contains("secure hardware"))
    }

    @Test
    fun aWrongPasswordIsSaidAndNothingIsAdded() = runTest {
        keys.next = CompletableDeferred(KeyImport.WrongPassword)
        typeWordsAndPassword()

        viewModel.onImport()
        runCurrent()

        assertEquals("That is not the wallet password.", entering().passwordHint, "said at the password")
        assertNull(entering().error)
        assertEquals(emptyList(), repository.accounts.value)

        viewModel.onPasswordChange("$password!")
        assertNull(entering().passwordHint, "editing clears it")
    }

    @Test
    fun aTooShortPasswordIsSaidAtThePassword() = runTest {
        keys.next = CompletableDeferred(KeyImport.PasswordTooShort)
        typeWordsAndPassword()

        viewModel.onImport()
        runCurrent()

        assertEquals("A wallet password has at least 8 characters.", entering().passwordHint)
    }

    @Test
    fun hidingTheScreenDropsThePasswordToo() {
        typeWordsAndPassword()

        viewModel.onHidden()

        assertEquals("", entering().password.text)
        assertIs<PasswordStep.Existing>(entering().step, "what the key store said is kept")
    }

    private fun entering(): Entering = assertIs<Entering>(viewModel.uiState.value)

    private class FakeKeyStore : KeyStore {
        override suspend fun newMnemonic(): NewMnemonic = NewMnemonic.Unavailable

        override suspend fun enableBiometrics(password: WalletPassword, confirmation: String): BiometricChange =
            BiometricChange.NotOnThisDevice

        override suspend fun deriveAccount(from: Address, password: WalletPassword, held: Set<Address>) =
            KeyDerivation.Unavailable

        override suspend fun disableBiometrics(): BiometricChange = BiometricChange.NotOnThisDevice

        var next = CompletableDeferred<KeyImport>()
        val received = mutableListOf<Mnemonic>()
        var passwordSet = true
        var description: () -> KeyStoreDescription = { KeyStoreDescription.Ready(passwordSet, KeyProtection.HARDWARE) }

        override suspend fun describe() = description()

        override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport {
            received += mnemonic
            return next.await().also { if (it is KeyImport.Stored) passwordSet = true }
        }

        override suspend fun delete(address: Address): KeyDeletion = error("not used by this test")
    }

    private class FakeAccountRepository : AccountRepository {
        val accounts = MutableStateFlow<List<Account>>(emptyList())

        override fun observeAccounts(): Flow<AccountList> = accounts.map { AccountList.Loaded(it) }

        var failSaves = false

        override suspend fun add(account: Account): AccountAdd {
            if (failSaves) return AccountAdd.NotSaved
            var added = false
            accounts.update { current ->
                added = current.none { it.address == account.address }
                if (added) current + account else current
            }
            return if (added) AccountAdd.Added else AccountAdd.AlreadyPresent
        }

        override suspend fun rename(id: AccountId, label: String): AccountChange = error("not used by this test")

        override suspend fun remove(id: AccountId): AccountChange = error("not used by this test")

        override suspend fun setCustody(id: AccountId, custody: AccountCustody): AccountChange {
            accounts.update { list -> list.map { if (it.id == id) it.copy(custody = custody) else it } }
            return AccountChange.Changed
        }
    }
}

package eth.serotine.ui.createaccount

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyDerivation
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.MemoryStep
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ImportMnemonicAccount
import eth.serotine.domain.usecase.NewRecoveryPhrase
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.WalletChanges
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.ui.components.PasswordStep
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class CreateAccountViewModelTest {

    private val hardhat = "test test test test test test test test test test test junk"
    private val account = Address.of("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266")
    private val accounts = FakeAccounts()
    private val keys = FakeKeyStore()
    private val networks = MemoryNetworkSettings()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): CreateAccountViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        return CreateAccountViewModel(
            NewRecoveryPhrase(keys),
            ImportMnemonicAccount(keys, accounts, WalletChanges()),
            DescribeKeyStore(keys),
            ObserveEnabledNetworks(networks),
            SetNetworkEnabled(networks),
            Random(7),
        )
    }

    /** Through the phrase and its check to the password. */
    private fun CreateAccountViewModel.checked() {
        onWrittenDown()
        val checking = uiState.value as CreateAccountUiState.Checking
        checking.positions.forEachIndexed { index, position ->
            onCheckedWordChange(index, " ${hardhat.split(" ")[position - 1].uppercase()} ")
        }
        onCheck()
        onNetworkToggle(Network.SEPOLIA.chainId)
    }

    @Test
    fun aNewPhraseIsShownCheckedAndStoredUnderTheNewPassword() = runTest {
        val viewModel = viewModel()
        runCurrent()
        val showing = assertIs<CreateAccountUiState.Showing>(viewModel.uiState.value)
        assertEquals(hardhat.split(" "), showing.words)

        viewModel.onWrittenDown()
        val checking = assertIs<CreateAccountUiState.Checking>(viewModel.uiState.value)
        assertEquals(3, checking.positions.toSet().size)
        assertTrue(checking.positions.all { it in 1..12 } && checking.positions == checking.positions.sorted())

        viewModel.onShowAgain()
        assertIs<CreateAccountUiState.Showing>(viewModel.uiState.value)
        viewModel.checked()
        runCurrent()
        val securing = assertIs<CreateAccountUiState.Securing>(viewModel.uiState.value)
        assertIs<PasswordStep.New>(securing.step)

        viewModel.onPasswordChange("correct horse battery")
        viewModel.onRepeatChange("correct horse battery")
        viewModel.onCreate()
        keys.next.complete(KeyImport.Stored(account, KeyProtection.HARDWARE))
        runCurrent()

        assertEquals(CreateAccountUiState.Created("Account 1"), viewModel.uiState.value)
        assertSame(showing.phrase, keys.received.single(), "the very words shown are the ones stored")
    }

    @Test
    fun aWordThatDoesNotMatchIsSaidAndNothingGoesOn() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.onWrittenDown()
        repeat(3) { viewModel.onCheckedWordChange(it, "zoo") }
        viewModel.onCheck()

        val checking = assertIs<CreateAccountUiState.Checking>(viewModel.uiState.value)
        assertTrue(checking.wrong)
        assertTrue(keys.received.isEmpty())
    }

    @Test
    fun leavingTheScreenDropsThePhraseAndANewOneIsMadeToGoOn() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.onHidden()
        assertIs<CreateAccountUiState.Restart>(viewModel.uiState.value)

        viewModel.startOver()
        runCurrent()
        assertIs<CreateAccountUiState.Showing>(viewModel.uiState.value)
        assertEquals(2, keys.made)

        viewModel.checked()
        runCurrent()
        viewModel.onHidden()
        assertIs<CreateAccountUiState.Restart>(
            viewModel.uiState.value,
            "no words or password held at the password step",
        )
    }

    @Test
    fun aStoreStillRunningWhenTheScreenIsLeftFinishesWithoutTheWords() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.checked()
        runCurrent()
        viewModel.onPasswordChange("correct horse battery")
        viewModel.onRepeatChange("correct horse battery")
        viewModel.onCreate()

        viewModel.onHidden()
        assertEquals(CreateAccountUiState.Finishing, viewModel.uiState.value)

        keys.next.complete(KeyImport.Stored(account, KeyProtection.HARDWARE))
        runCurrent()
        assertEquals(CreateAccountUiState.Created("Account 1"), viewModel.uiState.value)
    }

    @Test
    fun aKeyStoredButNotListedAfterTheScreenWasLeftSaysToImportThePhrase() = runTest {
        accounts.failSaves = true
        val viewModel = viewModel()
        runCurrent()
        viewModel.checked()
        runCurrent()
        viewModel.onPasswordChange("correct horse battery")
        viewModel.onRepeatChange("correct horse battery")
        viewModel.onCreate()
        viewModel.onHidden()

        keys.next.complete(KeyImport.Stored(account, KeyProtection.HARDWARE))
        runCurrent()
        // Not "start again": a new phrase would leave the stored key behind.
        assertEquals(CreateAccountUiState.NotListed, viewModel.uiState.value)
    }

    @Test
    fun aFailedCreateAsksTheKeyStoreAgainWhichPasswordItWants() = runTest {
        accounts.failSaves = true
        val viewModel = viewModel()
        runCurrent()
        viewModel.checked()
        runCurrent()
        viewModel.onPasswordChange("correct horse battery")
        viewModel.onRepeatChange("correct horse battery")
        viewModel.onCreate()
        keys.passwordSet = true // the key went in, and set the wallet password with it
        keys.next.complete(KeyImport.Stored(account, KeyProtection.HARDWARE))
        runCurrent()

        val securing = assertIs<CreateAccountUiState.Securing>(viewModel.uiState.value)
        assertTrue(securing.error!!.startsWith("The key is stored"), securing.error)
        assertIs<PasswordStep.Existing>(securing.step, "the wallet's password now, not a new one")
    }

    @Test
    fun notEnoughMemoryToProtectTheKeyIsSaidAndThePhraseStays() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.checked()
        runCurrent()
        viewModel.onPasswordChange("correct horse battery")
        viewModel.onRepeatChange("correct horse battery")
        viewModel.onCreate()
        keys.next.complete(KeyImport.NotEnoughMemory(MemoryStep.PROTECT))
        runCurrent()

        val securing = assertIs<CreateAccountUiState.Securing>(viewModel.uiState.value)
        assertEquals("Not enough free memory to protect the key: close other apps and try again.", securing.error)
        assertFalse(securing.creating, "the user can try again, with the same words")
    }

    @Test
    fun anAccountWhoseKeyWasProtectedAtALighterCostSaysSo() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.checked()
        runCurrent()
        viewModel.onPasswordChange("correct horse battery")
        viewModel.onRepeatChange("correct horse battery")
        viewModel.onCreate()
        keys.next.complete(KeyImport.Stored(account, KeyProtection.SOFTWARE_KEY_STORE, steppedDown = true))
        runCurrent()

        val created = assertIs<CreateAccountUiState.Created>(viewModel.uiState.value)
        assertEquals("Account 1", created.label)
        assertTrue(created.note!!.startsWith("There was not enough free memory just now"), created.note)
    }

    @Test
    fun aPhraseMadeAfterTheScreenWasLeftIsNeverShown() = runTest {
        keys.gate = CompletableDeferred()
        val viewModel = viewModel()
        viewModel.onHidden()
        keys.gate!!.complete(Unit)
        runCurrent()

        val restart = assertIs<CreateAccountUiState.Restart>(viewModel.uiState.value)
        assertTrue("Cross out" !in restart.message, "no words were shown, so none to cross out")
        viewModel.startOver()
        runCurrent()
        assertIs<CreateAccountUiState.Showing>(viewModel.uiState.value, "the next phrase is made while shown")
    }

    @Test
    fun startingOverWhileAPhraseIsBeingMadeMakesNoSecond() = runTest {
        keys.gate = CompletableDeferred()
        val viewModel = viewModel()
        viewModel.startOver()
        viewModel.startOver()
        keys.gate!!.complete(Unit)
        runCurrent()

        assertIs<CreateAccountUiState.Showing>(viewModel.uiState.value)
        assertEquals(1, keys.made)
    }

    @Test
    fun theCheckedWordsAreRedactedAsThePhraseIs() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.onWrittenDown()
        repeat(3) { viewModel.onCheckedWordChange(it, "junk") }

        assertTrue("junk" !in viewModel.uiState.value.toString(), viewModel.uiState.value.toString())
    }

    @Test
    fun aKeyStoreThatDoesNotWorkIsSaid() = runTest {
        keys.works = false
        val viewModel = viewModel()
        runCurrent()

        assertEquals(CreateAccountUiState.Unavailable, viewModel.uiState.value)
    }

    private inner class FakeKeyStore : KeyStore {
        var works = true
        var made = 0
        var gate: CompletableDeferred<Unit>? = null
        var passwordSet = false
        var next = CompletableDeferred<KeyImport>()
        val received = mutableListOf<Mnemonic>()

        override suspend fun newMnemonic(): NewMnemonic {
            if (!works) return NewMnemonic.Unavailable
            made++
            gate?.await()
            return NewMnemonic.Created(Mnemonic(hardhat))
        }

        override suspend fun describe() = KeyStoreDescription.Ready(passwordSet, KeyProtection.HARDWARE)

        override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport {
            received += mnemonic
            return next.await()
        }

        override suspend fun delete(address: Address): KeyDeletion = error("not used by these tests")

        override suspend fun enableBiometrics(password: WalletPassword, confirmation: String): BiometricChange =
            BiometricChange.NotOnThisDevice

        override suspend fun deriveAccount(from: Address, password: WalletPassword, held: Set<Address>) =
            KeyDerivation.Unavailable

        override suspend fun disableBiometrics(): BiometricChange = BiometricChange.NotOnThisDevice
    }
}

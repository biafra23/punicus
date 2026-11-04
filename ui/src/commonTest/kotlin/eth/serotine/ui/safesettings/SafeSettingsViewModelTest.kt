package eth.serotine.ui.safesettings

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeSettingsChange
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.BiometricUnlock
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyDerivation
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.EncodeSigningRequest
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ProposeSafeSettingsChange
import eth.serotine.domain.usecase.SignSafeTx
import eth.serotine.ui.SafeWorld
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.GUARD_REMOVED_WARNING
import eth.serotine.ui.components.MODULE_ENABLED_REFUSAL
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.ShareItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** [SafeSettingsViewModel] over fake ports, through the real use cases. */
@OptIn(ExperimentalCoroutinesApi::class)
class SafeSettingsViewModelTest {

    private val world = SafeWorld()
    private val clock = TestTimeSource()
    private val keys = object : KeyStore {
        override suspend fun describe() =
            KeyStoreDescription.Ready(true, KeyProtection.HARDWARE, BiometricUnlock.NotOnThisDevice)

        override suspend fun newMnemonic(): NewMnemonic = error("not used by these tests")

        override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport =
            error("not used by these tests")

        override suspend fun enableBiometrics(password: WalletPassword, confirmation: String): BiometricChange =
            error("not used by these tests")

        override suspend fun deriveAccount(from: Address, password: WalletPassword, held: Set<Address>) =
            KeyDerivation.Unavailable

        override suspend fun disableBiometrics(): BiometricChange = error("not used by these tests")

        override suspend fun delete(address: Address): KeyDeletion = error("not used by these tests")
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): SafeSettingsViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = SafeSettingsViewModel(
            AccountId(Network.SEPOLIA, world.safe),
            ObserveAccounts(world.accounts),
            world.observeSafe,
            DescribeKeyStore(keys),
            ProposeSafeSettingsChange(world.observeSafe, world.inputs, world.queue, Network.SEPOLIA),
            SignSafeTx(world.signer, world.observeSafe, world.recovery, world.queue, Network.SEPOLIA),
            EncodeSigningRequest(world.codec),
            clock,
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private val SafeSettingsViewModel.overview get() = assertIs<SafeSettingsUiState.Overview>(uiState.value)
    private val SafeSettingsViewModel.form get() = assertIs<SafeSettingsUiState.Edit>(uiState.value).form
    private val SafeSettingsViewModel.review get() = assertIs<SafeSettingsUiState.Review>(uiState.value)

    private val alice get() = Eip55.format(world.alice.address)
    private val bob get() = Eip55.format(world.bob.address)
    private val carol get() = Eip55.format(world.carol)
    private val dave get() = Eip55.format(world.dave.address)

    @Test
    fun theSettingsAreTheSafesVerifiedOwnersThresholdAndModulesWithWhatIsNeverOffered() = runTest {
        val overview = viewModel().overview

        assertEquals(SendFrom("Treasury", Eip55.format(world.safe)), overview.safe)
        assertEquals("Safe 1.4.1 · nonce 4", overview.title)
        assertEquals("2 of 3 owners must sign", overview.signers)
        assertEquals(
            listOf(
                SettingsOwner(alice, "Alice", heldHere = true),
                SettingsOwner(bob, "Bob", heldHere = false),
                SettingsOwner(carol, label = null, heldHere = false),
            ),
            overview.owners,
        )
        assertEquals("Checked on chain", overview.simpleCheckedAt, "simple mode's words, no block")
        assertTrue(overview.canChange)
        assertEquals(MODULE_ENABLED_REFUSAL, overview.enableModuleReason)
        assertTrue(overview.updateReason.startsWith("Updating a Safe's version runs a migration contract's code"))
    }

    @Test
    fun aWatchOnlySafeShowsEveryChangeGreyedOutAndOpensNone() = runTest {
        world.state = world.state.copy(owners = listOf(world.bob.address, world.carol))
        val viewModel = viewModel()

        val reason = assertNotNull(viewModel.overview.changeReason)
        assertTrue(reason.startsWith("No owner of this Safe has its key on this device"), reason)

        viewModel.onAddOwner()
        assertIs<SafeSettingsUiState.Overview>(viewModel.uiState.value)
    }

    @Test
    fun anOwnerAddedIsProposedSignedByAnOwnerHeldHereAndQueued() = runTest {
        val viewModel = viewModel()
        viewModel.onAddOwner()

        val form = viewModel.form
        assertEquals("Add an owner", form.title)
        assertEquals(ThresholdField(2, 4, "2 of 4 owners must sign"), form.threshold)
        assertEquals(listOf(AccountChoice("Dave", dave)), form.address?.accounts, "the wallet's accounts not owners yet")
        assertFalse(form.canReview, "no owner typed yet")

        viewModel.onPickAccount(dave)
        assertEquals("Dave, in this wallet: its key is on this device.", viewModel.form.address?.note)
        assertTrue(viewModel.form.canReview)

        viewModel.review()
        runCurrent()
        val review = viewModel.review
        assertEquals("Add an owner", review.action.title)
        assertEquals("Safe nonce 4", review.nonce)
        assertTrue(review.simulation.startsWith("The change, run as a verified call from the Safe at block 100"))
        assertTrue(review.simpleSimulation.orEmpty().startsWith("Checked on chain: the change should go through"))
        assertEquals(listOf(AccountChoice("Alice", alice)), review.owners)
        assertEquals(emptyList(), world.queue.requests.value, "nothing is queued before it is signed")

        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        val queued = world.queue.requests.value.single()
        assertEquals(
            SafeTxAction.SettingsChange(SafeSettingsChange.OwnerAdded(world.dave.address, 2)),
            SafeTxAction.of(queued.safe, queued.tx),
        )
        assertEquals(listOf(world.alice.address), queued.signers)
        assertEquals(SafeSettingsUiState.Proposed("Sepolia", "0x" + queued.hash.hex), viewModel.uiState.value)
    }

    @Test
    fun whatIsTypedIsCheckedAgainstTheSafeAsItIsTyped() = runTest {
        val viewModel = viewModel()
        viewModel.onAddOwner()

        viewModel.onAddressChange("0x1234")
        assertEquals(true, viewModel.form.address?.isError)
        assertFalse(viewModel.form.canReview)

        viewModel.onAddressChange(bob)
        assertEquals("$bob is an owner of this Safe already.", viewModel.form.problem)
        assertFalse(viewModel.form.canReview)

        viewModel.onAddressChange(Eip55.format(world.safe))
        assertEquals("The Safe cannot be its own owner.", viewModel.form.problem)
    }

    @Test
    fun anOwnerRemovedStartsBelowEveryOwnerNeededAndRefusesThatShape() = runTest {
        val viewModel = viewModel()
        viewModel.onRemoveOwner(carol)

        // 2 of 3 with one removed: 1 of 2 by default, as 2 of 2 needs a recovery module.
        val form = viewModel.form
        assertEquals(SettingsOwner(carol, label = null, heldHere = false), form.subject)
        assertEquals(ThresholdField(1, 2, "1 of 2 owners must sign"), form.threshold)
        assertTrue(form.canReview)

        viewModel.onThresholdChange(2)
        assertTrue(viewModel.form.problem!!.startsWith("It would leave all 2 owners needed to sign"))
        assertFalse(viewModel.form.canReview)

        viewModel.onThresholdChange(5)
        assertEquals(2, viewModel.form.threshold?.value, "held within the owners left")
    }

    @Test
    fun replacingTheOnlyOwnerHeldHereSaysTheSafeBecomesWatchOnlyHere() = runTest {
        val viewModel = viewModel()
        viewModel.onReplaceOwner(alice)

        viewModel.onAddressChange(Eip55.format(world.recipient))
        assertTrue(viewModel.form.warning!!.contains("becomes watch-only here"), viewModel.form.warning)

        // Replaced by another key held here: this device still signs.
        viewModel.onPickAccount(dave)
        assertEquals(
            "Its key is on this device, and so is the new owner's: once the change runs, this device signs for the " +
                "Safe as the new owner.",
            viewModel.form.warning,
        )

        viewModel.review()
        runCurrent()
        assertEquals("Replace an owner", viewModel.review.action.title)
        assertNotNull(viewModel.review.warning)
    }

    @Test
    fun theThresholdFormOpensAtTheSafesOwnAndWaitsForOneThatChangesIt() = runTest {
        val viewModel = viewModel()
        viewModel.onChangeThreshold()

        // Opened at the Safe's own threshold: nothing to review yet, and nothing wrong either.
        assertEquals(2, viewModel.form.threshold?.value)
        assertNull(viewModel.form.problem)
        assertFalse(viewModel.form.canReview)

        viewModel.onThresholdChange(1)
        assertNull(viewModel.form.problem)
        assertTrue(viewModel.form.canReview)

        viewModel.onThresholdChange(2)
        assertEquals("The threshold is that already.", viewModel.form.problem)
    }

    @Test
    fun aSafeOfOneOwnerKeepsItAndItsThresholdGreyedOutWithTheirReasons() = runTest {
        world.state = world.state.copy(owners = listOf(world.alice.address), threshold = 1)
        val viewModel = viewModel()

        val overview = viewModel.overview
        assertEquals("The Safe's only owner cannot be removed: replace it instead.", overview.removeBlocked)
        assertNotNull(overview.thresholdBlocked)
        viewModel.onRemoveOwner(alice)
        viewModel.onChangeThreshold()
        assertIs<SafeSettingsUiState.Overview>(viewModel.uiState.value)

        viewModel.onReplaceOwner(alice)
        assertEquals("Replace an owner", viewModel.form.title)
    }

    @Test
    fun aGuardRemovedCarriesItsWarning() = runTest {
        val guard = Address.of("0x" + "66".repeat(20))
        world.state = world.state.copy(guard = guard)
        val viewModel = viewModel()
        viewModel.onRemoveGuard()

        assertEquals(GUARD_REMOVED_WARNING, viewModel.form.warning)
        assertEquals(Eip55.format(guard), viewModel.form.subject?.address)
        assertTrue(viewModel.form.canReview)
    }

    @Test
    fun aChangeTheSafeRevertsOnIsRefusedOnTheFormAndBackLeadsOut() = runTest {
        world.inputs.simulation = Simulation.Reverted(101)
        val viewModel = viewModel()
        viewModel.onChangeThreshold()
        viewModel.onThresholdChange(1)

        viewModel.review()
        runCurrent()

        assertEquals(
            "The change reverts, run as a verified call from the Safe at block 101: the Safe refuses it as it is " +
                "now. Nothing was proposed.",
            viewModel.form.error,
        )
        assertEquals(
            "The change reverts, as checked on chain: the Safe refuses it as it is now. Nothing was proposed.",
            viewModel.form.simpleError,
            "simple mode's words, with no block",
        )
        // Changed, the form's error is cleared, and its simple words with it.
        viewModel.onThresholdChange(3)
        assertEquals(null, viewModel.form.error)
        assertEquals(null, viewModel.form.simpleError, "no simple words for an error no longer shown")
        viewModel.cancel()
        assertIs<SafeSettingsUiState.Overview>(viewModel.uiState.value)
    }

    @Test
    fun aSignatureNotSavedStaysOnTheReviewWhenTheChangeRevertsOnRefreshAndSimpleModeSaysSoWithNoBlock() = runTest {
        val viewModel = viewModel()
        viewModel.onChangeThreshold()
        viewModel.onThresholdChange(1)
        viewModel.review()
        runCurrent()
        world.queue.failSaves = true
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        assertIs<ShareItem>(viewModel.review.share, "signed, kept nowhere else")

        world.inputs.simulation = Simulation.Reverted(101)
        viewModel.refresh()
        runCurrent()

        val review = viewModel.review
        assertTrue("at block 101" in review.error.orEmpty(), review.error)
        assertEquals(
            "The change reverts, as checked on chain: the Safe refuses it as it is now. Nothing was proposed.",
            review.simpleError,
        )
        assertTrue(
            review.simpleSimulation.orEmpty().startsWith("When it was proposed, it was checked on chain"),
            "the run kept is said as from before the failure: ${review.simpleSimulation}",
        )

        world.inputs.simulation = Simulation.Succeeded("0x", 102)
        viewModel.refresh()
        runCurrent()

        assertTrue(viewModel.review.simpleSimulation.orEmpty().startsWith("Checked on chain: the change should go"))
    }

    @Test
    fun backFromTheReviewKeepsTheFormAsItWasFilledIn() = runTest {
        val viewModel = viewModel()
        viewModel.onChangeThreshold()
        viewModel.onThresholdChange(1)
        viewModel.review()
        runCurrent()
        viewModel.onPasswordChange("made-up test password")

        viewModel.edit()

        assertEquals(1, viewModel.form.threshold?.value)
        assertTrue(viewModel.form.canReview)
    }
}

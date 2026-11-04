package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeCreation
import eth.serotine.domain.model.SafeRelease
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeReleases
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeCreationTest {

    private val world = SafeWorld()
    private val alice = PlannedOwner.Account(world.alice.address)
    private val bob = PlannedOwner.Account(world.bob.address)
    private val carol = PlannedOwner.Account(world.carol)
    private val device = PlannedOwner.DeviceKey(Address.of("0x" + "de".repeat(20)))

    private val factory = Address.of("0x" + "fa".repeat(20))
    private val release = SafeRelease(
        "1.4.1",
        l2 = false,
        singleton = Address.of("0x" + "51".repeat(20)),
        proxyFactory = factory,
        proxyCreationCode = byteArrayOf(0x60, 0x80.toByte()),
        fallbackHandler = Address.ZERO,
    )
    private val releases = object : SafeReleases {
        var pinned: SafeRelease? = release
        override fun forCreation(network: Network): SafeRelease? = pinned
    }
    private var salts = 0L
    private val planSafe = PlanSafe(releases) { UInt256.of(++salts) }

    /** What each address holds: codeless unless a test says otherwise. */
    private val kind = MutableStateFlow<KindReading>(KindReading.Verified(AddressKind.Eoa, 100))
    private val kinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address): Flow<KindReading> = kind
    }
    private val quote = QuoteSafeDeployment(kinds, world.nativeBalances, world.inputs, world.sent) {
        1_800_000_000
    }

    private fun plan(vararg owners: PlannedOwner, threshold: Int = 1, label: String = "Treasury"): SafePlan =
        assertIs<SafePlanning.Planned>(planSafe(Network.SEPOLIA, owners.toList(), threshold, label)).plan

    private fun refusal(vararg owners: PlannedOwner, threshold: Int = 1): SafePlanRefusal =
        assertIs<SafePlanning.Refused>(planSafe(Network.SEPOLIA, owners.toList(), threshold, "")).refusal

    /** The factory's run returning [address], as `createProxyWithNonce` returns its proxy. */
    private fun returns(address: Address) {
        world.inputs.simulation = Simulation.Succeeded("0".repeat(24) + address.hex.removePrefix("0x"), 101)
    }

    @Test
    fun aPlanIsTheReleasesSetupAtItsPredictedAddress() {
        val plan = plan(alice, bob, carol, threshold = 2, label = "  Treasury ")

        assertEquals(listOf(world.alice.address, world.bob.address, world.carol), plan.owners)
        assertEquals(2, plan.threshold)
        assertEquals("Treasury", plan.label)
        assertEquals(release, plan.release)
        assertEquals(UInt256.ONE, plan.saltNonce)
        assertContentEquals(SafeCreation.setup(plan.owners, 2, Address.ZERO), plan.initializer)
        assertContentEquals(
            SafeCreation.createProxyWithNonce(release.singleton, plan.initializer, UInt256.ONE),
            plan.deployData,
        )
        assertEquals(SafeCreation.proxyAddress(release, plan.initializer, UInt256.ONE), plan.address)
    }

    @Test
    fun everyPlanHasASaltNonceOfItsOwn() {
        val first = plan(alice)
        val second = plan(alice)

        assertEquals(first.owners, second.owners)
        assertNotEquals(first.address, second.address)
    }

    @Test
    fun theDefaultSaltNoncesDiffer() {
        val random = PlanSafe(releases)
        val first = assertIs<SafePlanning.Planned>(random(Network.SEPOLIA, listOf(alice), 1, "")).plan
        val second = assertIs<SafePlanning.Planned>(random(Network.SEPOLIA, listOf(alice), 1, "")).plan

        assertNotEquals(first.saltNonce, second.saltNonce)
    }

    @Test
    fun theShapesTheKeysDocAllowsArePlanned() {
        plan(alice)
        plan(alice, bob)
        plan(alice, bob, carol, threshold = 2)
        // A device key with a recovery owner beside it, at threshold 1.
        plan(device, alice)
        plan(device, alice, bob, threshold = 2)
    }

    @Test
    fun aDeviceKeyIsNeverTheOnlyOwner() {
        assertEquals(SafePlanRefusal.DeviceKeyAlone, refusal(device))
    }

    @Test
    fun everyOwnerSigningNeedsARecoveryModuleWhichNoneIsOffered() {
        assertEquals(SafePlanRefusal.NeedsRecoveryModule(2), refusal(alice, bob, threshold = 2))
        assertEquals(SafePlanRefusal.NeedsRecoveryModule(3), refusal(alice, bob, carol, threshold = 3))
        assertNull(planSafe.shapeRefusal(listOf(alice), 1), "one of one: its key's recovery phrase backs it up")
    }

    @Test
    fun aThresholdOutsideTheOwnersIsRefused() {
        assertEquals(SafePlanRefusal.ThresholdOutOfRange(0, 2), refusal(alice, bob, threshold = 0))
        assertEquals(SafePlanRefusal.ThresholdOutOfRange(3, 2), refusal(alice, bob, threshold = 3))
    }

    @Test
    fun theOwnersAreChecked() {
        assertEquals(SafePlanRefusal.NoOwners, refusal())
        assertEquals(SafePlanRefusal.DuplicateOwner(world.alice.address), refusal(alice, bob, alice))
        assertEquals(
            SafePlanRefusal.DuplicateOwner(world.alice.address),
            refusal(alice, PlannedOwner.DeviceKey(world.alice.address)),
            "one address, whatever it is said to be",
        )
        assertEquals(
            SafePlanRefusal.ReservedAddress(Address.ZERO),
            refusal(alice, PlannedOwner.Account(Address.ZERO)),
        )
        val sentinel = Address.of("0x0000000000000000000000000000000000000001")
        assertEquals(SafePlanRefusal.ReservedAddress(sentinel), refusal(PlannedOwner.Account(sentinel), alice))
        val many = (1..21).map { PlannedOwner.Account(Address.of("0x" + it.toString(16).padStart(40, 'a'))) }
        assertEquals(
            SafePlanRefusal.TooManyOwners(PlanSafe.MAX_OWNERS),
            assertIs<SafePlanning.Refused>(planSafe(Network.SEPOLIA, many, 2, "")).refusal,
        )
        assertIs<SafePlanning.Planned>(planSafe(Network.SEPOLIA, many.take(20), 2, ""))
    }

    @Test
    fun aNameTooLongOrNoReleaseIsRefused() {
        assertEquals(
            SafePlanRefusal.NameTooLong,
            assertIs<SafePlanning.Refused>(planSafe(Network.SEPOLIA, listOf(alice), 1, "x".repeat(65))).refusal,
        )
        releases.pinned = null
        assertEquals(SafePlanRefusal.NoRelease, refusal(alice))
        assertEquals(SafePlanRefusal.NoRelease, planSafe.ownersRefusal(Network.SEPOLIA, listOf(alice)))
    }

    @Test
    fun theDeploymentIsQuotedWhenTheFactorysRunReturnsThePredictedAddress() = runTest {
        val plan = plan(alice, bob, carol, threshold = 2)
        returns(plan.address)

        val quoted = assertIs<SafeDeploymentQuoting.Quoted>(quote(plan, world.alice.address, FeeLevel.NORMAL)).quote

        assertEquals(plan, quoted.plan)
        assertEquals(world.alice.address, quoted.payer)
        assertEquals(100, quoted.codelessAt)
        val transaction = quoted.call.transaction
        assertEquals(factory, transaction.to)
        assertEquals(UInt256.ZERO, transaction.value)
        assertContentEquals(plan.deployData, transaction.data)
        assertEquals(7, transaction.nonce)
        assertEquals(Network.SEPOLIA.chainId, transaction.chainId)
        assertEquals(101, quoted.call.simulationBlock)
        val (from, to, data) = world.inputs.simulated.single()
        assertEquals(world.alice.address, from)
        assertEquals(factory, to)
        assertContentEquals(plan.deployData, data)
    }

    @Test
    fun aRunThatReturnsAnotherAddressIsRefused() = runTest {
        val plan = plan(alice)
        val elsewhere = Address.of("0x" + "ee".repeat(20))
        returns(elsewhere)

        assertEquals(
            SafeDeploymentRefusal.AddressMismatch(elsewhere),
            assertIs<SafeDeploymentQuoting.Refused>(quote(plan, world.alice.address, FeeLevel.NORMAL)).refusal,
        )
        world.inputs.simulation = Simulation.Succeeded("", 101)
        assertEquals(
            SafeDeploymentRefusal.AddressMismatch(null),
            assertIs<SafeDeploymentQuoting.Refused>(quote(plan, world.alice.address, FeeLevel.NORMAL)).refusal,
        )
    }

    @Test
    fun aRunThatRevertsIsNotSigned() = runTest {
        world.inputs.simulation = Simulation.Reverted(101)

        assertEquals(
            SafeDeploymentRefusal.NotQuoted(CallRefusal.Reverts(101)),
            assertIs<SafeDeploymentQuoting.Refused>(quote(plan(alice), world.alice.address, FeeLevel.NORMAL)).refusal,
        )
    }

    @Test
    fun anAddressWithCodeAlreadyIsRefused() = runTest {
        val plan = plan(alice)
        returns(plan.address)
        val safe = AddressKind.Safe(SafeState("1.4.1", false, listOf(world.alice.address), 1, 0, emptyList(), null))
        kind.value = KindReading.Verified(safe, 120)

        assertEquals(
            SafeDeploymentRefusal.AlreadyDeployed(safe),
            assertIs<SafeDeploymentQuoting.Refused>(quote(plan, world.alice.address, FeeLevel.NORMAL)).refusal,
        )
        kind.value = KindReading.Verified(AddressKind.Contract, 120)
        assertEquals(
            SafeDeploymentRefusal.AlreadyDeployed(AddressKind.Contract),
            assertIs<SafeDeploymentQuoting.Refused>(quote(plan, world.alice.address, FeeLevel.NORMAL)).refusal,
        )
        assertEquals(emptyList(), world.inputs.simulated, "nothing was run for it")
    }

    @Test
    fun anAddressNotVerifiedIsNotQuoted() = runTest {
        val plan = plan(alice)
        kind.value = KindReading.Syncing
        assertEquals(
            SafeDeploymentRefusal.Syncing,
            assertIs<SafeDeploymentQuoting.Refused>(quote(plan, world.alice.address, FeeLevel.NORMAL)).refusal,
        )
        kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(
            SafeDeploymentRefusal.Unavailable(ReadUnavailable.UNPROVEN),
            assertIs<SafeDeploymentQuoting.Refused>(quote(plan, world.alice.address, FeeLevel.NORMAL)).refusal,
        )
    }

    @Test
    fun aPayerThatCannotPayTheFeeIsRefused() = runTest {
        val plan = plan(alice)
        returns(plan.address)
        world.native = BalanceReading.Verified(Wei.of("1000"), 100)

        assertEquals(
            SafeDeploymentRefusal.NotQuoted(CallRefusal.InsufficientFunds),
            assertIs<SafeDeploymentQuoting.Refused>(quote(plan, world.alice.address, FeeLevel.NORMAL)).refusal,
        )
    }

    @Test
    fun aDeploymentSentAndNotMinedIsNotSentAgain() = runTest {
        val plan = plan(alice)
        returns(plan.address)
        world.sent.record(deploymentRecord(plan, nonce = 7))

        assertEquals(
            SafeDeploymentRefusal.AlreadySent,
            assertIs<SafeDeploymentQuoting.Refused>(quote(plan, world.bob.address, FeeLevel.NORMAL)).refusal,
        )
        // Mined (and so a block holds it, or another transaction took its nonce): the address check decides.
        world.inputs.mined = 8
        assertIs<SafeDeploymentQuoting.Quoted>(quote(plan, world.bob.address, FeeLevel.NORMAL))
    }

    @Test
    fun theSameOwnersAndThresholdSentAndNotMinedAreNotDeployedAgainUnderAnotherSalt() = runTest {
        val first = plan(alice, bob)
        world.sent.record(deploymentRecord(first, nonce = 7))
        val again = plan(alice, bob)
        returns(again.address)
        assertNotEquals(first.address, again.address, "planned anew: another salt")

        assertEquals(
            SafeDeploymentRefusal.SameSafeSent(first.address),
            assertIs<SafeDeploymentQuoting.Refused>(quote(again, world.alice.address, FeeLevel.NORMAL)).refusal,
        )
        // Another order of the owners, or another threshold, is another Safe.
        val reordered = plan(bob, alice)
        returns(reordered.address)
        assertIs<SafeDeploymentQuoting.Quoted>(quote(reordered, world.alice.address, FeeLevel.NORMAL))
        // Once the first is mined (or its nonce taken), it holds nothing back.
        world.inputs.mined = 8
        returns(again.address)
        assertIs<SafeDeploymentQuoting.Quoted>(quote(again, world.alice.address, FeeLevel.NORMAL))
    }

    @Test
    fun aDeploymentIsSignedByThePayerAndRecordedAsWhatItDeploys() = runTest {
        val plan = plan(alice, bob, carol, threshold = 2)
        returns(plan.address)
        val quoted = assertIs<SafeDeploymentQuoting.Quoted>(quote(plan, world.alice.address, FeeLevel.FAST)).quote

        val sending = DeploySafe(world.signer, world.broadcaster, world.sent) { 1_800_000_100 }(
            quoted,
            Unlock.WithBiometrics("Deploy the Safe"),
        )

        assertIs<TransferSending.Sent>(sending)
        assertEquals(world.alice.address, world.signer.asked.single().first)
        val recorded = world.sent.transfers.value.single()
        assertEquals(world.alice.address, recorded.account)
        assertEquals(1_800_000_100, recorded.sentAt)
        assertEquals(
            SentAction.SafeDeployment(plan.address, plan.owners, 2, "1.4.1", "Treasury"),
            recorded.action,
        )
        assertEquals(quoted.call.maxCost, recorded.maxCost)
    }

    @Test
    fun aDeployedSafeIsListedOnceTheLightClientProvesIt() = runTest {
        val accounts = ListAccountRepository(world.alice)
        val list = ListDeployedSafe(kinds, WatchAddress(accounts, kinds))
        val owners = listOf(world.alice.address, world.carol)
        val deployment = SentAction.SafeDeployment(world.safe, owners, 1, "1.4.1", "Vault")

        val listing = async { list(Network.SEPOLIA, deployment) }
        runCurrent()
        assertFalse(listing.isCompleted, "codeless: the head does not have it yet")

        val state = SafeState("1.4.1", false, listOf(world.alice.address, world.carol), 1, 0, emptyList(), null)
        kind.value = KindReading.Verified(AddressKind.Safe(state), 130)

        val listed = assertIs<DeployedSafeListing.Listed>(listing.await()).account
        assertEquals(Account(Network.SEPOLIA, world.safe, AccountKind.SAFE, "Vault", AccountCustody.Watched), listed)
        assertEquals(listOf(world.alice, listed), accounts.accounts.value)
    }

    @Test
    fun aSafeThatCannotBeVerifiedAgainWhenWatchedIsTriedAgain() = runTest {
        val accounts = ListAccountRepository(world.alice)
        val owners = listOf(world.alice.address, world.carol)
        val safe = KindReading.Verified(
            AddressKind.Safe(SafeState("1.4.1", false, owners, 1, 0, emptyList(), null)),
            130,
        )
        // Each read in turn: the detection, then the watch's own, which cannot be verified once.
        val answers = ArrayDeque(listOf(safe, KindReading.Unavailable(ReadUnavailable.UNPROVEN)))
        val flaky = object : AddressKinds {
            override fun observeKind(network: Network, address: Address): Flow<KindReading> =
                flowOf(answers.removeFirstOrNull() ?: safe)
        }
        val list = ListDeployedSafe(flaky, WatchAddress(accounts, flaky))

        val listed = list(Network.SEPOLIA, SentAction.SafeDeployment(world.safe, owners, 1, "1.4.1", "Vault"))

        assertEquals("Vault", assertIs<DeployedSafeListing.Listed>(listed).account.label)
        assertEquals(listOf(world.alice, listed.account), accounts.accounts.value)
    }

    @Test
    fun afterARevertTheAddressIsReadOnceAndTheSafeAnotherCopyCreatedIsListed() = runTest {
        val accounts = ListAccountRepository(world.alice)
        val list = ListDeployedSafe(kinds, WatchAddress(accounts, kinds))
        val owners = listOf(world.alice.address, world.carol)
        val deployment = SentAction.SafeDeployment(world.safe, owners, 1, "1.4.1", "Vault")

        // Codeless: nothing waits for code that will never come.
        assertEquals(DeployedSafeListing.NoSafe, list.afterRevert(Network.SEPOLIA, deployment))
        kind.value = KindReading.Unavailable(ReadUnavailable.STALE_ANCHOR)
        assertEquals(
            DeployedSafeListing.NotVerified(ReadUnavailable.STALE_ANCHOR),
            list.afterRevert(Network.SEPOLIA, deployment),
        )
        kind.value = KindReading.Syncing
        assertEquals(DeployedSafeListing.NotVerified(null), list.afterRevert(Network.SEPOLIA, deployment))
        val reversed = SafeState("1.4.1", false, owners.reversed(), 1, 0, emptyList(), null)
        kind.value = KindReading.Verified(AddressKind.Safe(reversed), 130)
        assertEquals(
            DeployedSafeListing.NotTheSafe,
            list.afterRevert(Network.SEPOLIA, deployment),
            "the owners in another order",
        )
        assertEquals(listOf(world.alice), accounts.accounts.value)

        // Someone's copy of the same call was mined first: the very Safe planned.
        val state = SafeState("1.4.1", false, owners, 1, 0, emptyList(), null)
        kind.value = KindReading.Verified(AddressKind.Safe(state), 130)
        val listed = assertIs<DeployedSafeListing.Listed>(list.afterRevert(Network.SEPOLIA, deployment)).account
        assertEquals(Account(Network.SEPOLIA, world.safe, AccountKind.SAFE, "Vault", AccountCustody.Watched), listed)
    }

    @Test
    fun theOwnersAreComparedInTheirOrder() = runTest {
        val list = ListDeployedSafe(kinds, WatchAddress(ListAccountRepository(world.alice), kinds))
        val owners = listOf(world.alice.address, world.carol)
        val reversed = SafeState("1.4.1", false, owners.reversed(), 1, 0, emptyList(), null)
        kind.value = KindReading.Verified(AddressKind.Safe(reversed), 130)

        assertEquals(
            DeployedSafeListing.NotTheSafe,
            list(Network.SEPOLIA, SentAction.SafeDeployment(world.safe, owners, 1, "1.4.1", "Vault")),
        )
    }

    @Test
    fun whetherAReleaseIsPinnedIsAskedOnItsOwn() {
        assertTrue(planSafe.hasRelease(Network.SEPOLIA))
        releases.pinned = null
        assertFalse(planSafe.hasRelease(Network.SEPOLIA))
    }

    @Test
    fun somethingElseAtTheAddressIsNotListed() = runTest {
        val accounts = ListAccountRepository(world.alice)
        val list = ListDeployedSafe(kinds, WatchAddress(accounts, kinds))
        val owners = listOf(world.alice.address, world.carol)
        val deployment = SentAction.SafeDeployment(world.safe, owners, 1, "1.4.1", "")

        kind.value = KindReading.Verified(AddressKind.Contract, 130)
        assertEquals(DeployedSafeListing.NotTheSafe, list(Network.SEPOLIA, deployment))
        val other = SafeState("1.4.1", false, listOf(world.alice.address, world.bob.address), 1, 0, emptyList(), null)
        kind.value = KindReading.Verified(AddressKind.Safe(other), 130)
        assertEquals(DeployedSafeListing.NotTheSafe, list(Network.SEPOLIA, deployment), "other owners")
        val raised = SafeState("1.4.1", false, listOf(world.alice.address, world.carol), 2, 0, emptyList(), null)
        kind.value = KindReading.Verified(AddressKind.Safe(raised), 130)
        assertEquals(DeployedSafeListing.NotTheSafe, list(Network.SEPOLIA, deployment), "another threshold")
        assertEquals(listOf(world.alice), accounts.accounts.value)
    }

    private fun deploymentRecord(plan: SafePlan, nonce: Long) = SentTransfer(
        network = Network.SEPOLIA,
        account = world.alice.address,
        nonce = nonce,
        signed = SignedTransfer(byteArrayOf(2, nonce.toByte())),
        action = SentAction.SafeDeployment(plan.address, plan.owners, plan.threshold, "1.4.1", plan.label),
        maxCost = UInt256.of(1000),
        sentAt = 1_799_999_000,
    )

    @Test
    fun aPayersEarlierTransactionMaybeSentRefusesTheDeploymentBeforeAnythingIsRead() = runTest {
        val plan = plan(alice, bob)
        returns(plan.address)
        val other = SentTransfer(
            Network.SEPOLIA,
            world.alice.address,
            7,
            SignedTransfer(byteArrayOf(2, 7)),
            SentAction.Transfer(world.carol, TransferAsset.Native, UInt256.ONE),
            UInt256.ONE,
            sentAt = 1_799_999_000,
            maybeSent = true,
        )
        world.sent.transfers.value = listOf(other)

        assertEquals(
            SafeDeploymentQuoting.Refused(SafeDeploymentRefusal.NotQuoted(CallRefusal.EarlierMaybeSent(other.hash))),
            quote(plan, world.alice.address, FeeLevel.NORMAL),
        )
        assertEquals(emptyList(), world.inputs.simulated, "nothing run")

        // Its own deployment maybe sent is named as such, before the generic refusal.
        world.sent.transfers.value = listOf(deploymentRecord(plan, 7).copy(maybeSent = true))
        assertEquals(
            SafeDeploymentQuoting.Refused(SafeDeploymentRefusal.AlreadySent),
            quote(plan, world.alice.address, FeeLevel.NORMAL),
        )
    }
}

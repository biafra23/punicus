package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SafeListing
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.TransactionProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** [FollowSafeDeployments] over fake ports: the listing of a deployed Safe, whatever screen is open. */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowSafeDeploymentsTest {

    private val world = SafeWorld()
    private val owners = listOf(world.alice.address, world.carol)
    private val deployment = SentTransfer(
        network = Network.SEPOLIA,
        account = world.alice.address,
        nonce = 7,
        signed = SignedTransfer(byteArrayOf(2, 7)),
        action = SentAction.SafeDeployment(world.safe, owners, 1, "1.4.1", "Vault"),
        maxCost = UInt256.of(1000),
        sentAt = 1_800_000_000,
    )
    private val accounts = ListAccountRepository(world.alice)
    private val kind = MutableStateFlow<KindReading>(KindReading.Verified(AddressKind.Eoa, 100))
    private val kinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address): Flow<KindReading> = kind
    }
    private val progress = MutableStateFlow<TransactionProgress>(TransactionProgress.Pending)
    private val receipts = object : ReceiptTracker {
        override fun observe(network: Network, hash: String): Flow<TransactionProgress> = progress
    }
    private var now = 1_800_000_100L

    private fun TestScope.follow() {
        val follower = FollowSafeDeployments(
            world.sent,
            receipts,
            accounts,
            ListDeployedSafe(kinds, WatchAddress(accounts, kinds)),
            clock = { now },
        )
        backgroundScope.launch { follower() }
        runCurrent()
    }

    private fun safeThere(owners: List<Address> = this.owners) {
        kind.value = KindReading.Verified(
            AddressKind.Safe(SafeState("1.4.1", false, owners, 1, 0, emptyList(), null)),
            130,
        )
    }

    private val listed: SafeListing?
        get() = (world.sent.transfers.value.single().action as SentAction.SafeDeployment).listed

    @Test
    fun aRecordedDeploymentIsListedOnceItsReceiptAndItsCodeAreVerifiedWhateverScreenIsOpen() = runTest {
        world.sent.transfers.value = listOf(deployment) // as after a restart: recorded before this run
        follow()
        assertEquals(listOf(world.alice), accounts.accounts.value)

        progress.value = TransactionProgress.Included(120, succeeded = true)
        runCurrent()
        assertEquals(listOf(world.alice), accounts.accounts.value, "codeless still at the light client's head")

        safeThere()
        runCurrent()
        assertEquals(
            listOf(
                world.alice,
                Account(Network.SEPOLIA, world.safe, AccountKind.SAFE, "Vault", AccountCustody.Watched),
            ),
            accounts.accounts.value,
        )
        assertEquals(SafeListing.LISTED, listed, "ended: never listed again")
    }

    @Test
    fun aRevertedDeploymentWithNoCodeEndsAsNoSafe() = runTest {
        world.sent.transfers.value = listOf(deployment)
        follow()

        progress.value = TransactionProgress.Finalized(120, succeeded = false)
        runCurrent()

        assertEquals(SafeListing.NO_SAFE, listed)
        assertEquals(listOf(world.alice), accounts.accounts.value)
    }

    @Test
    fun aRevertedDeploymentBehindACopyListsTheSafeTheCopyCreated() = runTest {
        world.sent.transfers.value = listOf(deployment)
        safeThere()
        follow()

        progress.value = TransactionProgress.Included(120, succeeded = false)
        runCurrent()

        assertEquals(SafeListing.LISTED, listed)
        assertEquals(world.safe, accounts.accounts.value.last().address)
    }

    @Test
    fun anotherSafeAtTheAddressEndsAsNotTheSafe() = runTest {
        world.sent.transfers.value = listOf(deployment)
        safeThere(owners.reversed())
        follow()

        progress.value = TransactionProgress.Included(120, succeeded = true)
        runCurrent()

        assertEquals(SafeListing.NOT_THE_SAFE, listed)
    }

    @Test
    fun aSafeInTheWalletAlreadyEndsAsListedAtOnce() = runTest {
        accounts.accounts.value += Account(
            Network.SEPOLIA,
            world.safe,
            AccountKind.SAFE,
            "Mine",
            AccountCustody.Watched,
        )
        world.sent.transfers.value = listOf(deployment)
        follow()

        assertEquals(SafeListing.LISTED, listed)
    }

    @Test
    fun anEndedListingIsNotFollowedAgainSoARemovedSafeStaysRemoved() = runTest {
        val ended = deployment.copy(
            action = (deployment.action as SentAction.SafeDeployment).copy(listed = SafeListing.LISTED),
        )
        world.sent.transfers.value = listOf(ended)
        progress.value = TransactionProgress.Finalized(120, succeeded = true)
        safeThere()
        follow()

        assertEquals(listOf(world.alice), accounts.accounts.value)
    }

    @Test
    fun anOutcomeThatIsNoEndingIsTriedAgain() = runTest {
        world.sent.transfers.value = listOf(deployment)
        accounts.failSaves = true
        safeThere()
        progress.value = TransactionProgress.Included(120, succeeded = true)
        follow()
        assertEquals(null, listed, "the list cannot be saved: not ended")

        accounts.failSaves = false
        advanceTimeBy(31_000)
        runCurrent()

        assertEquals(SafeListing.LISTED, listed)
        assertEquals(world.safe, accounts.accounts.value.last().address)
    }

    @Test
    fun aDeploymentPastItsLifetimeIsGivenUp() = runTest {
        world.sent.transfers.value = listOf(deployment)
        now = deployment.sentAt + DEPLOYMENT_LIFETIME_SECONDS
        safeThere()
        progress.value = TransactionProgress.Included(120, succeeded = true)
        follow()

        assertEquals(null, listed)
        assertEquals(listOf(world.alice), accounts.accounts.value)
    }
}

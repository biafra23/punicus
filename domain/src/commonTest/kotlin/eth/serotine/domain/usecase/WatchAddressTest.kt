package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class WatchAddressTest {

    private val address = Address.of("0x" + "ab".repeat(20))
    private val daily = Account(
        Network.SEPOLIA,
        Address.of("0x" + "11".repeat(20)),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )
    private val repository = ListAccountRepository(daily)
    private var reading: KindReading = KindReading.Verified(AddressKind.Eoa, 100)
    private val kinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address): Flow<KindReading> = flowOf(reading)
    }
    private val watch = WatchAddress(repository, kinds)

    @Test
    fun aVerifiedAccountIsAddedWatchedUnderTheGivenName() = runTest {
        val expected = Account(Network.SEPOLIA, address, AccountKind.EOA, "Savings", AccountCustody.Watched)

        assertEquals(AddressWatch.Added(expected), watch(Network.SEPOLIA, address, "  Savings "))
        assertEquals(listOf(daily, expected), repository.accounts.value)
    }

    @Test
    fun withoutANameItIsNamedAsAnImportWouldBe() = runTest {
        val added = watch(Network.SEPOLIA, address, "  ") as AddressWatch.Added

        assertEquals("Account 2", added.account.label)
    }

    @Test
    fun oneNameServesEveryNetworkAnAddressIsWatchedOn() = runTest {
        assertEquals("Savings", watch.sharedName("  Savings ", ensName = "savings.eth"), "the user's")
        assertEquals("", watch.sharedName("", ensName = "savings.eth"), "each follows the ENS name")
        assertEquals("Account 2", watch.sharedName("", ensName = null), "one taken once")

        repository.unreadable = true
        assertEquals(null, watch.sharedName("", ensName = null))
    }

    @Test
    fun withoutANameItsNameFollowsTheEnsNameItWasResolvedFrom() = runTest {
        val added = watch(Network.SEPOLIA, address, "  ", ensName = "savings.eth") as AddressWatch.Added

        assertEquals(
            Account(
                Network.SEPOLIA,
                address,
                AccountKind.EOA,
                "savings.eth",
                AccountCustody.Watched,
                ensName = "savings.eth",
            ),
            added.account,
        )
    }

    @Test
    fun onGnosisChainTheEnsNameIsOnlyTheLabelAndNotFollowed() = runTest {
        val added = watch(Network.GNOSIS, address, "", ensName = "savings.eth") as AddressWatch.Added

        assertEquals(
            Account(Network.GNOSIS, address, AccountKind.EOA, "savings.eth", AccountCustody.Watched),
            added.account,
            "no Gnosis registry was asked (docs/networks.md, *Names*)",
        )
    }

    @Test
    fun aNameGivenIsTheUsersEvenWithAnEnsName() = runTest {
        val added = watch(Network.SEPOLIA, address, "Savings", ensName = "savings.eth") as AddressWatch.Added

        assertEquals(
            Account(Network.SEPOLIA, address, AccountKind.EOA, "Savings", AccountCustody.Watched),
            added.account,
        )
    }

    @Test
    fun anEnsNameTooLongForARowIsNotFollowed() = runTest {
        val long = "a".repeat(RenameAccount.MAX_LABEL_LENGTH - 3) + ".eth"

        val added = watch(Network.SEPOLIA, address, "", ensName = long) as AddressWatch.Added

        assertEquals(
            Account(Network.SEPOLIA, address, AccountKind.EOA, "Account 2", AccountCustody.Watched),
            added.account,
        )
    }

    @Test
    fun aDelegatedAccountIsStillAnAccount() = runTest {
        reading = KindReading.Verified(AddressKind.DelegatedEoa(daily.address), 100)

        assertEquals(
            AccountCustody.Watched,
            (watch(Network.SEPOLIA, address, "") as AddressWatch.Added).account.custody,
        )
    }

    @Test
    fun aVerifiedSafeIsAddedAsASafe() = runTest {
        val state = SafeState("1.4.1", l2 = false, listOf(daily.address), 1, 0, emptyList(), guard = null)
        reading = KindReading.Verified(AddressKind.Safe(state), 100)

        val added = watch(Network.SEPOLIA, address, "Treasury") as AddressWatch.Added
        assertEquals(
            Account(Network.SEPOLIA, address, AccountKind.SAFE, "Treasury", AccountCustody.Watched),
            added.account,
        )
    }

    @Test
    fun aContractIsRefused() = runTest {
        reading = KindReading.Verified(AddressKind.Contract, 100)

        assertEquals(AddressWatch.Contract, watch(Network.SEPOLIA, address, "Some contract"))
        assertEquals(listOf(daily), repository.accounts.value)
    }

    @Test
    fun anUnverifiedKindIsRefused() = runTest {
        reading = KindReading.Syncing
        assertEquals(AddressWatch.NotVerified(null), watch(Network.SEPOLIA, address, ""))

        reading = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(AddressWatch.NotVerified(ReadUnavailable.UNPROVEN), watch(Network.SEPOLIA, address, ""))
        assertEquals(listOf(daily), repository.accounts.value)
    }

    @Test
    fun anAddressInTheWalletAlreadyIsNamedNotAddedAgain() = runTest {
        assertEquals(AddressWatch.AlreadyInWallet(daily), watch(Network.SEPOLIA, daily.address, "Again"))
    }

    @Test
    fun anAddressAddedByAnotherWriterMeanwhileIsNamed() = runTest {
        val other = Account(Network.SEPOLIA, address, AccountKind.EOA, "Theirs", AccountCustody.InKeyStore)
        val racing = object : AddressKinds {
            // The other writer adds the address while its kind is being proven.
            override fun observeKind(network: Network, address: Address): Flow<KindReading> = flow {
                repository.accounts.value = repository.accounts.value + other
                emit(reading)
            }
        }

        assertEquals(
            AddressWatch.AlreadyInWallet(other),
            WatchAddress(repository, racing)(Network.SEPOLIA, address, "Mine"),
        )
    }

    @Test
    fun anUnreadableListAddsNothing() = runTest {
        repository.unreadable = true

        assertEquals(AddressWatch.NotSaved, watch(Network.SEPOLIA, address, "Savings"))
    }

    @Test
    fun aNameTooLongOrAListThatCannotBeSavedChangesNothing() = runTest {
        assertEquals(
            AddressWatch.NameTooLong,
            watch(Network.SEPOLIA, address, "x".repeat(RenameAccount.MAX_LABEL_LENGTH + 1)),
        )

        repository.failSaves = true
        assertEquals(AddressWatch.NotSaved, watch(Network.SEPOLIA, address, "Savings"))
        assertEquals(listOf(daily), repository.accounts.value)
    }
}

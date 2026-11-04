package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.ReadUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveEnsNamesTest {

    private val savings = Address.of("0x" + "ab".repeat(20))
    private val elsewhere = Address.of("0x" + "cd".repeat(20))
    private val byName =
        Account(
            Network.SEPOLIA,
            savings,
            AccountKind.EOA,
            "savings.eth",
            AccountCustody.Watched,
            ensName = "savings.eth",
        )
    private val daily = Account(
        Network.SEPOLIA,
        Address.of("0x" + "11".repeat(20)),
        AccountKind.EOA,
        "Daily",
        AccountCustody.Watched,
    )

    private val names = object : NameResolver {
        var answer: NameResolution = NameResolution.Resolved("savings.eth", savings, 100)
        val asked = mutableListOf<String>()

        override suspend fun resolve(network: Network, name: String): NameResolution {
            asked += name
            return answer
        }
    }

    private val repository = ListAccountRepository(daily, byName)
    private val observe = ObserveEnsNames(repository, names, interval = 10.minutes, retryDelay = 30.seconds)

    private fun <T> TestScope.latest(flow: Flow<T>): () -> T? {
        var last: T? = null
        backgroundScope.launch { flow.collect { last = it } }
        runCurrent()
        return { last }
    }

    @Test
    fun onlyAnAccountThatFollowsANameIsCheckedAndItsNameVerified() = runTest {
        val checks = latest(observe())

        assertEquals(mapOf(savings.onSepolia to EnsNameCheck.Verified(100)), checks())
        assertEquals(listOf("savings.eth"), names.asked)
    }

    @Test
    fun aNameThatPointsElsewhereOrNowhereHasMovedAndOneOnlyOffchainCannotBeVerified() = runTest {
        names.answer = NameResolution.Resolved("savings.eth", elsewhere, 100)
        assertEquals(mapOf(savings.onSepolia to EnsNameCheck.Moved), latest(observe())())

        names.answer = NameResolution.NoAddress
        assertEquals(mapOf(savings.onSepolia to EnsNameCheck.Moved), latest(observe())())

        names.answer = NameResolution.OffchainOnly
        assertEquals(mapOf(savings.onSepolia to EnsNameCheck.Unverifiable), latest(observe())())
    }

    @Test
    fun theNameIsCheckedAgainAtTheIntervalAndSoonerWhileUnverified() = runTest {
        names.answer = NameResolution.Unavailable(ReadUnavailable.UNPROVEN)
        val checks = latest(observe())
        assertEquals(mapOf(savings.onSepolia to EnsNameCheck.Unavailable(ReadUnavailable.UNPROVEN)), checks())

        names.answer = NameResolution.Resolved("savings.eth", savings, 120)
        advanceTimeBy(30.seconds + 1.seconds)
        assertEquals(mapOf(savings.onSepolia to EnsNameCheck.Verified(120)), checks())

        names.answer = NameResolution.Resolved("savings.eth", elsewhere, 160)
        advanceTimeBy(9.minutes)
        assertEquals(mapOf(savings.onSepolia to EnsNameCheck.Verified(120)), checks(), "not before the interval")
        advanceTimeBy(1.minutes + 1.seconds)
        assertEquals(mapOf(savings.onSepolia to EnsNameCheck.Moved), checks())
    }

    @Test
    fun aVerifiedAnswerStandsWhileTheNextCheckCannotBeVerified() = runTest {
        names.answer = NameResolution.Resolved("savings.eth", elsewhere, 100)
        val checks = latest(observe())
        assertEquals(mapOf(savings.onSepolia to EnsNameCheck.Moved), checks())

        names.answer = NameResolution.Unavailable(ReadUnavailable.UNPROVEN)
        advanceTimeBy(10.minutes + 1.seconds)
        assertEquals(mapOf(savings.onSepolia to EnsNameCheck.Moved), checks(), "not shown as the account's name again")
        assertEquals(2, names.asked.size)

        // With an answer standing, an unverifiable check is retried at the interval, not sooner.
        advanceTimeBy(9.minutes)
        assertEquals(2, names.asked.size)
        advanceTimeBy(1.minutes + 1.seconds)
        assertEquals(3, names.asked.size)
    }

    @Test
    fun aRenamedAccountIsNoLongerChecked() = runTest {
        val checks = latest(observe())

        repository.rename(savings.onSepolia, "Savings")
        runCurrent()

        assertEquals(emptyMap(), checks())
        advanceTimeBy(30.minutes)
        assertEquals(1, names.asked.size)
    }
}

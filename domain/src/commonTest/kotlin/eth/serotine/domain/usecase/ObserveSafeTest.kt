package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.SafeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ObserveSafeTest {

    private val safe = Address.of("0x" + "5a".repeat(20))
    private val alice = Account(
        Network.SEPOLIA,
        Address.of("0x" + "11".repeat(20)),
        AccountKind.EOA,
        "Alice",
        AccountCustody.InKeyStore,
    )
    private val bob = Account(
        Network.SEPOLIA,
        Address.of("0x" + "22".repeat(20)),
        AccountKind.EOA,
        "Bob",
        AccountCustody.Watched,
    )
    private val carol = Address.of("0x" + "33".repeat(20))
    private val state =
        SafeState("1.4.1", l2 = false, listOf(alice.address, bob.address, carol), 2, 4, emptyList(), guard = null)
    private val kind = MutableStateFlow<KindReading>(KindReading.Verified(AddressKind.Safe(state), 9))
    private val kinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address): Flow<KindReading> = kind
    }
    private val treasury = Account(Network.SEPOLIA, safe, AccountKind.SAFE, "Treasury", AccountCustody.Watched)
    private val accounts = ListAccountRepository(alice, bob, treasury)
    private val observeSafe = ObserveSafe(kinds, accounts)

    @Test
    fun theOwnersWhoseKeysAreHeldDecideWhatTheUserCanDo() = runTest {
        val reading = observeSafe(safe.onSepolia).first() as SafeReading.Verified

        // Bob is watched only: his key is not held.
        assertEquals(listOf(alice.address), reading.ownersHeld)
        assertEquals(SafeControl.CO_SIGNER, reading.control)
        assertEquals(SafeControl.FULL, state.control(held = 2))
        assertEquals(SafeControl.WATCH_ONLY, state.control(held = 0))
    }

    @Test
    fun anAddressThatIsNoSafeIsNeverShownAsOne() = runTest {
        kind.value = KindReading.Verified(AddressKind.Contract, 9)

        assertEquals(SafeReading.NotASafe, observeSafe(safe.onSepolia).first())
    }

    @Test
    fun everySafeInTheWalletIsReadAndNothingElse() = runTest {
        val read = ObserveSafes(accounts, observeSafe)().first { it.isNotEmpty() }

        assertEquals(setOf(safe.onSepolia), read.keys)
    }
}

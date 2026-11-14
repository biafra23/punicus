package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.EnsNameTokens
import eth.serotine.domain.model.KnownNftCollections
import eth.serotine.domain.model.KnownNftKind
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NftRef
import eth.serotine.domain.model.NftStandard
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.NftCollectionReading
import eth.serotine.domain.repository.NftContracts
import eth.serotine.domain.repository.NftHolding
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SeenEnsName
import eth.serotine.domain.repository.SeenEnsNameList
import eth.serotine.domain.repository.SeenEnsNames
import eth.serotine.domain.repository.TokenChange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [RememberResolvedNames] and [AddEnsName] over fakes (issue #274). */
class SeenEnsNamesTest {

    private val mine = Address.of("0x51fd612218249b9f4cf76cbc80258bcc75d79f0d")
    private val stranger = Address.of("0x" + "22".repeat(20))
    private val accounts = ListAccountRepository(
        Account(Network.SEPOLIA, mine, AccountKind.EOA, "myotis1", AccountCustody.InKeyStore),
    )
    private val seen = MemorySeenEnsNames()

    private fun resolving(to: Address) = object : NameResolver {
        override suspend fun resolve(network: Network, name: String): NameResolution =
            NameResolution.Resolved(name, to, 12_000_000)
    }

    @Test
    fun aNameResolvedToAnAccountIsRememberedForItAndOnlyForIt() = runTest {
        val names = RememberResolvedNames(resolving(mine), accounts, seen)

        val answer = names.resolve(Network.SEPOLIA, "Diskordia.eth")
        assertEquals(NameResolution.Resolved("Diskordia.eth", mine, 12_000_000), answer, "the answer is unchanged")
        assertEquals(
            listOf(AccountId(Network.SEPOLIA, mine) to SeenEnsName("diskordia.eth", 12_000_000)),
            seen.seen.value,
        )

        // The same address on another network is another account, and not in the wallet.
        names.resolve(Network.ETHEREUM, "diskordia.eth")
        assertEquals(1, seen.seen.value.size)
    }

    @Test
    fun aNameResolvedToAnAddressNotInTheWalletIsNotKept() = runTest {
        RememberResolvedNames(resolving(stranger), accounts, seen).resolve(Network.SEPOLIA, "vitalik.eth")
        assertEquals(emptyList(), seen.seen.value)
    }

    @Test
    fun theTokensANameCanBeAreWorkedOutByHashing() {
        // ENSIP-1's own examples.
        assertEquals(
            UInt256.fromBigEndian(hex("de9b09fd7c5f901e23a3f19fecc54828e9c848539801e86591bd9801b019f84f")),
            EnsNameTokens.namehash("foo.eth"),
        )
        assertEquals(
            UInt256.fromBigEndian(hex("af2caa1c2ca1d027f1ac823b529d0a67cd144264b2789fa2ea4d63a67c7103cc")),
            EnsNameTokens.labelHash("vitalik"),
        )
        val registrar = KnownNftCollections.of(Network.ETHEREUM).single { it.kind == KnownNftKind.ENS_REGISTRAR }
        val wrapper = KnownNftCollections.of(Network.ETHEREUM).single { it.kind == KnownNftKind.ENS_NAME_WRAPPER }
        assertEquals(
            listOf(
                NftRef(registrar.address, EnsNameTokens.labelHash("vitalik"), NftStandard.ERC721),
                NftRef(wrapper.address, EnsNameTokens.namehash("vitalik.eth"), NftStandard.ERC1155),
            ),
            EnsNameTokens.candidates(Network.ETHEREUM, "vitalik.eth"),
        )
        // A subname is only ever a NameWrapper token; Gnosis Chain has no ENS; a typed name is normalized first.
        assertEquals(1, EnsNameTokens.candidates(Network.ETHEREUM, "pay.vitalik.eth").size)
        assertEquals(emptyList(), EnsNameTokens.candidates(Network.GNOSIS, "vitalik.eth"))
        assertEquals(emptyList(), EnsNameTokens.candidates(Network.ETHEREUM, "Vitalik.eth"))
        assertEquals("vitalik.eth", EnsNameTokens.normalized(" Vitalik.ETH "))
        assertEquals(null, EnsNameTokens.normalized("vitalik"))
        assertEquals(null, EnsNameTokens.normalized("vitalik..eth"))
        assertEquals(null, EnsNameTokens.normalized("vit alik.eth"))
    }

    @Test
    fun aRegistrarLabelIsGivenOnlyWhenItHashesToTheToken() {
        val id = EnsNameTokens.labelHash("diskordia")
        assertEquals("diskordia", EnsNameTokens.registrarLabel(id, listOf("other.eth", "diskordia.eth")))
        assertEquals(null, EnsNameTokens.registrarLabel(id, listOf("other.eth", "pay.diskordia.eth")))
    }

    @Test
    fun anEnsNameIsAddedOnlyOnceTheAccountIsProvenToHoldOneOfItsTokens() = runTest {
        val account = AccountId(Network.SEPOLIA, mine)
        val contracts = HoldingContracts()
        val add = AddEnsName(contracts, seen)

        assertEquals(EnsNameAddition.NotAName, add(account, "diskordia"))
        assertEquals(EnsNameAddition.NotAName, add(AccountId(Network.GNOSIS, mine), "diskordia.eth"))
        assertEquals(EnsNameAddition.NotHeld, add(account, "diskordia.eth"))

        contracts.unavailable = true
        assertEquals(EnsNameAddition.Unavailable(ReadUnavailable.UNPROVEN), add(account, "diskordia.eth"))
        contracts.unavailable = false

        // Wrapped: the NameWrapper holds the registrar's token, and the account the wrapper's.
        val wrapper = KnownNftCollections.of(Network.SEPOLIA).single { it.kind == KnownNftKind.ENS_NAME_WRAPPER }
        contracts.held += NftRef(wrapper.address, EnsNameTokens.namehash("diskordia.eth"), NftStandard.ERC1155)
        assertEquals(EnsNameAddition.Added, add(account, " Diskordia.eth"))
        assertEquals(listOf(account to SeenEnsName("diskordia.eth", 9_200)), seen.seen.value)
        assertEquals(EnsNameAddition.AlreadyAdded, add(account, "diskordia.eth"))
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** [NftContracts] holding [held] for anyone, at block 9,200. */
    private class HoldingContracts : NftContracts {
        val held = mutableSetOf<NftRef>()
        var unavailable = false

        override suspend fun holding(network: Network, holder: Address, nft: NftRef): NftHolding = when {
            unavailable -> NftHolding.Unavailable(ReadUnavailable.UNPROVEN)
            nft in held -> NftHolding.Held(UInt256.ONE, 9_200)
            else -> NftHolding.NotHeld(9_200)
        }

        override suspend fun collection(network: Network, contract: Address): NftCollectionReading =
            NftCollectionReading.Read(null, null, 9_200)
    }
}

/** [SeenEnsNames] in memory: [seen] holds every account's names, in the order remembered. */
internal class MemorySeenEnsNames : SeenEnsNames {
    val seen = MutableStateFlow<List<Pair<AccountId, SeenEnsName>>>(emptyList())

    override fun observe(account: AccountId): Flow<SeenEnsNameList> =
        seen.map { all -> SeenEnsNameList.Loaded(all.filter { it.first == account }.map { it.second }) }

    override suspend fun remember(account: AccountId, name: SeenEnsName): TokenChange {
        if (seen.value.any { it.first == account && it.second.name == name.name }) return TokenChange.AlreadyPresent
        seen.value += account to name
        return TokenChange.Changed
    }
}

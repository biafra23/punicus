package eth.serotine.data.safe

import eth.serotine.data.keys.fromHex
import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeCreation
import eth.serotine.domain.model.UInt256
import org.kotlincrypto.hash.sha3.Keccak256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The pins against the real SafeProxy runtime code, so a typo'd hash cannot pass for one. */
class SafeDeploymentsTest {

    /**
     * Each factory's proxy runtime code, as a verified create call of its `proxyCreationCode()`
     * returned it through the Myotis engine on Sepolia, 2026-10-17 (1.3.0's equal to its own
     * `proxyRuntimeCode()`); 1.0.0's and 1.1.1's (also 1.2.0's, which reuses 1.1.1's factory) as
     * an `eth_call` of `proxyRuntimeCode()` on Mainnet at block 26,289,609 (SafeDeployments.kt's
     * doc comment).
     */
    private val runtimeCode = mapOf(
        "1.0.0" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054163660008037600080366000845af43d6000803e" +
            "6000811415603d573d6000fd5b3d6000f3fea165627a7a723058201e7d648b83cfac072cbccefc2ffc62a6999d4a050ee87" +
            "a721942de1da9670db80029",
        "1.1.1" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea265627a7a72315820d8a00dc4fe6bf675a9d7416fc2d00bb3433362aa8186b7" +
            "50f76c4027269667ff64736f6c634300050e0032",
        "1.3.0" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea2646970667358221220d1429297349653a4918076d650332de1a1068c5f3e07" +
            "c5c82360c277770b955264736f6c63430007060033",
        "1.4.1" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea264697066735822122003d1488ee65e08fa41e58e888a9865554c535f2c7712" +
            "6a82cb4c0f917f31441364736f6c63430007060033",
        "1.5.0" to
            "608060405260005463a619486e60003560e01c14156024578060601b606c5260206060f35b3660008037600080366000845a" +
            "f43d6000803e806040573d6000fd5b3d6000f3fea2646970667358221220e61834ebd2d8cd909d362bf67c47ef58fd665df3" +
            "8e6dd036ce65611101d072e964736f6c63430007060033",
    )

    @Test
    fun everyPinnedProxyHashIsTheHashOfASafeProxysRuntimeCode() {
        val hashes = runtimeCode.values.map { Keccak256().digest(it.fromHex()).toHex() }.toSet()
        val oneZeroZeroHash = Keccak256().digest(runtimeCode.getValue("1.0.0").fromHex()).toHex()

        for (network in safeNetworks) {
            // Sepolia's singleton for 1.0.0 carries no code there (SafeDeployments.kt's doc
            // comment): its proxy hash is left out, so a proxy naming it is read as a plain
            // contract rather than shown as a Safe nothing proves holds real Safe code.
            val expected = if (network == Network.SEPOLIA) hashes - oneZeroZeroHash else hashes
            assertEquals(expected, SafeDeployments.of(network).proxyCodeHashes, network.displayName)
        }
    }

    @Test
    fun onlyFromVersionOneFiveAModuleGuardIsConsulted() {
        for (network in safeNetworks) {
            val singletons = SafeDeployments.of(network).singletons.values
            val everyVersion = setOf("1.0.0", "1.1.1", "1.2.0", "1.3.0", "1.4.1", "1.5.0")

            assertEquals(setOf("1.5.0"), singletons.filter { it.moduleGuard }.map { it.version }.toSet())
            assertEquals(
                if (network == Network.SEPOLIA) everyVersion - "1.0.0" else everyVersion,
                singletons.map { it.version }.toSet(),
                network.displayName,
            )
        }
    }

    @Test
    fun oneZeroZeroIsPinnedOnlyWhereItsSingletonCarriesCode() {
        val oneZeroZero = Address.of("0xb6029ea3b2c51d09a50b53ca8012feeb05bda35a")

        assertEquals(null, SafeDeployments.of(Network.SEPOLIA).singletons[oneZeroZero])
        assertEquals(SafeSingleton("1.0.0", l2 = false), SafeDeployments.of(Network.ETHEREUM).singletons[oneZeroZero])
        assertEquals(SafeSingleton("1.0.0", l2 = false), SafeDeployments.of(Network.GNOSIS).singletons[oneZeroZero])
    }

    @Test
    fun aNewSafeIsOneThatDetectionRecognises() {
        // The L1 singleton on Ethereum and Sepolia, SafeL2 on Gnosis Chain (the owner's ruling of 2026-10-22).
        for (network in safeNetworks) {
            val table = SafeDeployments.of(network)
            val release = assertNotNull(PinnedSafeReleases().forCreation(network), network.displayName)
            val l2 = network == Network.GNOSIS

            assertEquals("1.4.1", release.version)
            assertEquals(l2, release.l2, network.displayName)
            assertEquals(SafeSingleton("1.4.1", l2 = l2), table.singletons[release.singleton])
            assertEquals(Address.of("0x4e1DCf7AD4e460CfD30791CCC4F9c8a4f820ec67"), release.proxyFactory)
            assertEquals(Address.ZERO, release.fallbackHandler, "no fallback handler is pinned, so none is installed")
        }
    }

    @Test
    fun theProxyCreationCodeDeploysThePinnedProxyRuntimeCode() {
        val code = assertNotNull(PinnedSafeReleases().forCreation(Network.SEPOLIA)).proxyCreationCode
        // The constructor's CODECOPY: 0xab bytes from offset 0x119, then RETURN (`60ab80610119 6000396000f3`).
        val runtime = code.copyOfRange(0x119, 0x119 + 0xab)

        assertEquals(486, code.size)
        assertEquals(runtimeCode.getValue("1.4.1"), runtime.toHex())
        assertTrue(Keccak256().digest(runtime).toHex() in SafeDeployments.of(Network.SEPOLIA).proxyCodeHashes)
        // keccak256 of the whole creation code, as `cast keccak` gives it for the npm artifact's `bytecode`.
        assertEquals(
            "1856e0ee08399d74e0ea0b03adca210aeade6f748969ac023cdcb4dd62dcaf5f",
            Keccak256().digest(code).toHex(),
        )
    }

    @Test
    fun thePinnedReleasePredictsTheAddressCastComputes() {
        val release = assertNotNull(PinnedSafeReleases().forCreation(Network.SEPOLIA))
        val owners = listOf(
            Address.of("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"),
            Address.of("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"),
            Address.of("0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"),
        )
        val initializer = SafeCreation.setup(owners, 2, release.fallbackHandler)

        // The vector of domain's SafeCreationTest: `cast compute-address` over the same creation code.
        assertEquals(
            Address.of("0x2Ca0226086a7C24808217F7F8707827608B17Dc5"),
            SafeCreation.proxyAddress(release, initializer, UInt256.of(1_760_000_000_000)),
        )
    }

    /** The networks with Safe deployments pinned, each held to the same table on its own. */
    private val safeNetworks = Network.entries
}

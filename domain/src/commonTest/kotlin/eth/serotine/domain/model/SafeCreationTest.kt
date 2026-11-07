package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * A Safe's `setup` initializer, the factory's `createProxyWithNonce` call and the CREATE2 address
 * it deploys to, against vectors derived independently when these tests were written:
 *
 * - the calls with Foundry's `cast calldata` (its own ABI encoder), the salt and the init code hash
 *   with `cast keccak` / `cast concat-hex` / `cast to-uint256`, and the address with
 *   `cast compute-address <factory> --salt … --init-code-hash …` (cast 1.7.1);
 * - the proxy creation code is `SafeProxy`'s `bytecode` in `@safe-global/safe-contracts`
 *   1.4.1-build.0 (npm; `build/artifacts/contracts/proxies/SafeProxy.sol/SafeProxy.json`), the build
 *   the canonical 1.4.1 deployment came from: its `deployedBytecode` hashes to the SafeProxy runtime
 *   hash Punicus pins from Sepolia (`d7d408eb…`), and its SafeProxyFactory's to the factory's code
 *   hash in safe-global/safe-deployments (`0x50c3cdc4…`);
 * - [theFactorysOwnRunAgrees]: that factory and the 1.4.1 Safe singleton, deployed from the same
 *   package on a local anvil, returned this address from `createProxyWithNonce` (an `eth_call`),
 *   deployed the proxy there, and the proxy then answered `getOwners()` with the three owners in
 *   their order, `getThreshold()` 2, an empty fallback handler slot and the pinned runtime hash. A
 *   second run of the same call reverted ("Create2 call failed").
 */
class SafeCreationTest {

    private val factory = Address.of("0x4e1DCf7AD4e460CfD30791CCC4F9c8a4f820ec67")
    private val singleton = Address.of("0x41675C099F32341bf84BFc5382aF534df5C7461a")
    private val owners = listOf(
        Address.of("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"),
        Address.of("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"),
        Address.of("0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"),
    )
    private val release = SafeRelease("1.4.1", l2 = false, singleton, factory, PROXY_CREATION_CODE, Address.ZERO)

    @Test
    fun theInitializerIsSetupWithNoModuleAndNoPayment() {
        // cast calldata "setup(address[],uint256,address,bytes,address,address,uint256,address)"
        //   "[<owners>]" 2 0x0…0 0x 0x0…0 0x0…0 0 0x0…0
        val expected = "b63e800d" +
            word("100") + word("2") + word("0") + word("180") + word("0") + word("0") + word("0") + word("0") +
            word("3") + owners.joinToString("") { word(it.hex.removePrefix("0x")) } + word("0")

        assertEquals(expected, SafeCreation.setup(owners, 2, Address.ZERO).toHex())
    }

    @Test
    fun createProxyWithNonceCarriesTheSingletonTheInitializerAndTheSaltNonce() {
        val initializer = SafeCreation.setup(owners, 2, Address.ZERO)
        // cast calldata "createProxyWithNonce(address,bytes,uint256)" <singleton> <initializer> 1760000000000
        val expected = "1688f0b9" + word(singleton.hex.removePrefix("0x")) + word("60") + word("199c82cc000") +
            word("1a4") + initializer.toHex() + "00".repeat(28)

        assertEquals(
            expected,
            SafeCreation.createProxyWithNonce(singleton, initializer, UInt256.of(1_760_000_000_000)).toHex(),
        )
    }

    @Test
    fun aTwoOfThreeSafeIsPredictedWhereTheSepoliaFactoryPutsIt() {
        val initializer = SafeCreation.setup(owners, 2, Address.ZERO)

        assertEquals(
            Address.of("0x2Ca0226086a7C24808217F7F8707827608B17Dc5"),
            SafeCreation.proxyAddress(release, initializer, UInt256.of(1_760_000_000_000)),
        )
    }

    @Test
    fun aOneOfOneSafeAtSaltNonceZero() {
        val owner = Address.of("0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb")
        val initializer = SafeCreation.setup(listOf(owner), 1, Address.ZERO)

        assertEquals(
            Address.of("0xE2F4E66d2B978BB9C82907A2d8961017f0326156"),
            SafeCreation.proxyAddress(release, initializer, UInt256.ZERO),
        )
    }

    @Test
    fun theSaltAndTheInitCodeHashAreTheFactorys() {
        val initializer = SafeCreation.setup(owners, 2, Address.ZERO)
        // cast keccak $(cast concat-hex $(cast keccak <initializer>) $(cast to-uint256 1760000000000))
        val salt = "142f1df9fb78fb9a2219d277b833616024aa08e4d053b55981d7e58f2bb4ec2f"
        // cast keccak $(cast concat-hex <creation code> $(cast to-uint256 <singleton>))
        val initCodeHash = "76733d705f71b79841c0ee960a0ca880f779cde7ef446c989e6d23efc0a4adfb"

        assertEquals(salt, keccak256(keccak256(initializer) + UInt256.of(1_760_000_000_000).toBytes32()).toHex())
        assertEquals(initCodeHash, keccak256(PROXY_CREATION_CODE + AbiWords.word(singleton)).toHex())
        assertEquals(
            Address.of("0x2Ca0226086a7C24808217F7F8707827608B17Dc5"),
            SafeCreation.create2Address(factory, salt.hexToBytes(), initCodeHash.hexToBytes()),
        )
    }

    @Test
    fun theFactorysOwnRunAgrees() {
        // anvil's first two contract deployments from its first dev account: the factory, then the singleton.
        val local = SafeRelease(
            "1.4.1",
            l2 = false,
            singleton = Address.of("0xe7f1725e7734ce288f8367e1bb143e90bb3f0512"),
            proxyFactory = Address.of("0x5fbdb2315678afecb367f032d93f642f64180aa3"),
            proxyCreationCode = PROXY_CREATION_CODE,
            fallbackHandler = Address.ZERO,
        )
        val initializer = SafeCreation.setup(owners, 2, Address.ZERO)

        assertEquals(
            Address.of("0xDBB71d98814fCeD7A3d8A5835d2eDda420601A6d"),
            SafeCreation.proxyAddress(local, initializer, UInt256.of(1_760_000_000_000)),
        )
    }

    @Test
    fun anotherSaltNonceOrAnotherOwnerIsAnotherAddress() {
        val initializer = SafeCreation.setup(owners, 2, Address.ZERO)
        val predicted = SafeCreation.proxyAddress(release, initializer, UInt256.ONE)

        assertNotEquals(predicted, SafeCreation.proxyAddress(release, initializer, UInt256.of(2)))
        assertNotEquals(
            predicted,
            SafeCreation.proxyAddress(release, SafeCreation.setup(owners.reversed(), 2, Address.ZERO), UInt256.ONE),
        )
    }

    @Test
    fun theReturnedAddressIsReadFromOneWordOnly() {
        val word = word("2ca0226086a7c24808217f7f8707827608b17dc5")

        assertEquals(Address.of("0x2Ca0226086a7C24808217F7F8707827608B17Dc5"), SafeCreation.returnedAddress(word))
        assertNull(SafeCreation.returnedAddress(""), "nothing returned")
        assertNull(SafeCreation.returnedAddress(word + word), "two words")
        assertNull(SafeCreation.returnedAddress("1" + word.drop(1)), "bits above the address")
        assertNull(SafeCreation.returnedAddress(word.uppercase()), "not the engine's lower case")
    }

    @Test
    fun aReleaseKeepsItsOwnCopyOfTheCode() {
        val code = PROXY_CREATION_CODE
        val pinned = SafeRelease("1.4.1", l2 = false, singleton, factory, code, Address.ZERO)
        code[0] = 0

        assertContentEquals(PROXY_CREATION_CODE, pinned.proxyCreationCode)
        pinned.proxyCreationCode[0] = 0
        assertContentEquals(PROXY_CREATION_CODE, pinned.proxyCreationCode)
        assertEquals(release, pinned)
    }

    /** [hex] left-padded to one 32-byte word. */
    private fun word(hex: String) = hex.padStart(64, '0')

    private companion object {
        /** `type(SafeProxy).creationCode` of Safe 1.4.1 (see the class comment for its source); a fresh copy. */
        val PROXY_CREATION_CODE: ByteArray
            get() = (
                "608060405234801561001057600080fd5b506040516101e63803806101e6833981810160405260208110156100335760" +
                    "0080fd5b8101908080519060200190929190505050600073ffffffffffffffffffffffffffffffffffffffff168173ff" +
                    "ffffffffffffffffffffffffffffffffffffff1614156100ca576040517f08c379a00000000000000000000000000000" +
                    "000000000000000000000000000081526004018080602001828103825260228152602001806101c46022913960400191" +
                    "505060405180910390fd5b806000806101000a81548173ffffffffffffffffffffffffffffffffffffffff0219169083" +
                    "73ffffffffffffffffffffffffffffffffffffffff1602179055505060ab806101196000396000f3fe608060405273ff" +
                    "ffffffffffffffffffffffffffffffffffffff600054167fa619486e0000000000000000000000000000000000000000" +
                    "000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e600081" +
                    "14156070573d6000fd5b3d6000f3fea264697066735822122003d1488ee65e08fa41e58e888a9865554c535f2c77126a" +
                    "82cb4c0f917f31441364736f6c63430007060033496e76616c69642073696e676c65746f6e2061646472657373207072" +
                    "6f7669646564"
                ).hexToBytes()
    }
}

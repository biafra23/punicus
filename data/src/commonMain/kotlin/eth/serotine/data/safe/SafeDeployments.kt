package eth.serotine.data.safe

import eth.serotine.data.keys.fromHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeRelease

/**
 * A canonical Safe singleton: its [version] ("1.4.1"), whether it is the L2 variant ([l2]),
 * and whether it consults a module guard ([moduleGuard]: 1.5.0 on; older ones never read it).
 */
internal data class SafeSingleton(val version: String, val l2: Boolean, val moduleGuard: Boolean = false)

/**
 * The Safe deployments a Safe is recognised by, per network (docs/wallet-ux.md, *Safes*): the
 * runtime code hashes of the canonical SafeProxy contracts ([Table.proxyCodeHashes], lower-case
 * hex without `0x`), and the canonical singletons a proxy's slot 0 must name
 * ([Table.singletons]). A contract matching neither is "another contract", never a Safe.
 *
 * Read on Sepolia on 2026-10-17 with verified reads through the Myotis engine, at blocks
 * 11,933,239 to 11,933,240: each singleton's code, and its `VERSION()` as a verified call;
 * each proxy hash as the code a verified create call of the factory's `proxyCreationCode()`
 * returns (1.3.0's also from its `proxyRuntimeCode()`, the same). The eip155 deployments of
 * 1.3.0 have the same code as the canonical ones.
 *
 * A new Safe is created from [Table.creation] (docs/wallet-ux.md, *Safes*: creation): Safe 1.4.1,
 * its L1 singleton (Safe{Wallet}'s choice for Ethereum and its testnets) and its SafeProxyFactory,
 * with no fallback handler: `setup` installs none. (The one CompatibilityFallbackHandler a Safe
 * transaction may set is pinned in the domain, `PinnedFallbackHandlers`, beside the check that
 * refuses any other.) The factory's proxy creation code is `SafeProxy`'s `bytecode` in the npm package
 * `@safe-global/safe-contracts` 1.4.1-build.0
 * (`build/artifacts/contracts/proxies/SafeProxy.sol/SafeProxy.json`), the build the canonical
 * deployment came from: the runtime code it deploys hashes to the 1.4.1 proxy hash below, read on
 * Sepolia, and that package's SafeProxyFactory runtime code hashes to the factory's code hash in
 * safe-global/safe-deployments (`src/assets/v1.4.1/safe_proxy_factory.json`, `0x50c3cdc4…`). It
 * is only what is expected: a deployment is quoted only when the factory's verified run returns
 * the address computed from it.
 *
 * Ethereum has the same canonical deployments at the same addresses, so the same table. Read on
 * mainnet on 2026-10-23 at blocks 26,256,229 to 26,256,247 through two public nodes, which
 * agreed, a debugging source only (CLAUDE.md, *Data sources*): each singleton's `VERSION()` as
 * listed; each factory's proxy creation code, deployed by an `eth_call`, runs to the code whose
 * hash is pinned below (1.3.0's factory and its eip155 twin alike), and 1.4.1's factory returns
 * exactly [PROXY_CREATION_CODE_1_4_1] and has the code hash `0x50c3cdc4…` named above. The light
 * client proves a Safe's proxy and singleton before it is one, and `MyotisHostEthereumTest` does
 * so on the real chain.
 *
 * Gnosis Chain has them too, read the same way on 2026-10-23 at Gnosis blocks 48,904,729 to
 * 48,904,737 through two public Gnosis nodes, which agreed: the same `VERSION()`s and proxy
 * runtime hashes, and 1.4.1's factory's code hash `0x50c3cdc4…` with 486 bytes of creation code.
 * Only its [Table.creation] differs: Safe{Wallet} creates SafeL2 there, the 1.4.1 L2 singleton
 * (owner ruling, 2026-10-22; docs/networks.md, open question 6), whose events let any indexer
 * follow the Safe; `MyotisHostGnosisTest` proves the deployments through the light client.
 *
 * A Safe older than 1.3.0 is recognised too, on Ethereum and Gnosis Chain (issue: diskordia.eth,
 * an address the user reported as "a recognised Safe" that Serotine showed as a plain contract):
 * 1.0.0, 1.1.1 and 1.2.0, the three Mainnet releases of `safe-global/safe-deployments` before
 * 1.3.0 introduced the `Safe` rename and the guard. Their `GnosisSafe.sol` is `MasterCopy,
 * ModuleManager, OwnerManager, ...` (1.0.0's `BaseSafe` is `ModuleManager, OwnerManager` under
 * another name): the same six slots `readSafe` already reads, with no guard (read as none, since
 * nothing was ever written at its hashed slot) and, before 1.3.0 added `moduleGuard`, none of
 * that either. A Safe left on one of these singletons is still read as what it is; one moved
 * since to a pinned singleton by the owners' own `changeMasterCopy` call (as diskordia.eth was,
 * to the 1.3.0 L1 singleton already pinned above) needs only its [Table.proxyCodeHashes]
 * widened, since its proxy's own code never changes. Recognition is display only: a Safe left on
 * 1.0.0, 1.1.1 or 1.2.0 (unlike one migrated to a pinned ≥1.3.0 singleton) checks a different
 * EIP-712 domain than `SafeTx.hash` computes, so `SafeTxRefusal.TooOldToSign` keeps every
 * signing, approval and execution flow off it (`SafeTx.hash`'s KDoc). 1.2.0 reused 1.1.1's
 * `ProxyFactory` (`safe-deployments` lists no `v1.2.0` factory of its own), so one more proxy
 * hash plus three more singletons cover all three:
 * - 1.0.0's `ProxyFactory` 0x12302fE9c02ff50939BaAaaf415fc226C078613C and its singleton
 *   0xb6029EA3B2c51D09a50B53CA8012FeEB05bDa35A ("Gnosis Safe" `VERSION()` "1.0.0");
 * - 1.1.1's `ProxyFactory` 0x76E2cFc1F5Fa8F6a5b3fC4c8F4788F0116861F9B (also 1.2.0's) and its
 *   singleton 0x34CfAC646f301356fAa8B21e94227e3583Fe3F5F ("1.1.1");
 * - 1.2.0's singleton 0x6851D6fDFAfD08c0295C392436245E5bc78B0185 ("1.2.0").
 *
 * Each factory's `proxyRuntimeCode()` (`addacc0f`, unchanged since 1.0.0) and each singleton's
 * code and `VERSION()` were read as an `eth_call` on Mainnet at block 26,289,609 and on Gnosis
 * Chain at block 48,983,120 through two independent public nodes, which agreed (publicnode.com
 * and drpc.org, or publicnode.com and gnosischain.com's own; a debugging source only, CLAUDE.md,
 * *Data sources*: the Myotis engine is what proves them for a user): all five addresses have the
 * live code and the exact `VERSION()` their table entry claims, on both networks. 1.1.1's and
 * 1.2.0's mastercopies, Keccak-256 hashed, are byte for byte what Mainnet carries, 1.2.0's on
 * Gnosis too; 1.1.1's on Gnosis differs only in Solidity's trailing metadata hash (the functional
 * bytecode before it is identical: a separate build of the same source, which the EVM never
 * reads). Diskordia.eth's own deployed code, read directly, is exactly 1.1.1's `proxyRuntimeCode()`
 * byte for byte.
 *
 * **Sepolia leaves 1.0.0 out.** Unlike 1.1.1's and 1.2.0's, which carry live code there too
 * (`VERSION()` "1.1.1" and "1.2.0" again, read at Sepolia block 12,010,935) and so are pinned on
 * Sepolia as everywhere else, 1.0.0's `ProxyFactory` and its singleton answer with no code at
 * all on Sepolia (`eth_getCode` empty at both addresses, the same two nodes): Sepolia postdates
 * 1.0.0's 2019 Mainnet deployment by years and was never seeded with it. Pinning an address with
 * no code there would be a finding on its own (CLAUDE.md, *Trust*): recognition never reads a
 * singleton's own code, only that a proxy's slot 0 names a pinned address, so an attacker could
 * deploy the (public) 1.0.0 proxy runtime byte for byte at a fresh address with slot 0 set to
 * 0xb6029EA3B2c51D09a50B53CA8012FeEB05bDa35A and a constructor that writes whatever owners and
 * threshold it likes into the rest — Serotine would show it as a verified Safe, and a
 * `delegatecall` to that empty address always "succeeds" as a no-op, so nothing sent to it could
 * ever move again.
 */
internal object SafeDeployments {

    /**
     * The proxy code hashes and singletons of one network, and the release a new Safe is created
     * from there ([creation], null: none).
     */
    class Table(
        val proxyCodeHashes: Set<String>,
        val singletons: Map<Address, SafeSingleton>,
        val creation: SafeRelease?,
    )

    /** The deployments of [network]. */
    fun of(network: Network): Table = when (network) {
        Network.ETHEREUM -> ETHEREUM
        // 1.1.1's and 1.2.0's singletons carry live, verified-matching code here too (the doc
        // comment above); 1.0.0's does not, so Sepolia's table leaves it out.
        Network.SEPOLIA -> SEPOLIA
        Network.GNOSIS -> GNOSIS
    }

    private val ETHEREUM = table(l2 = false, includeOneZeroZero = true)

    private val SEPOLIA = table(l2 = false, includeOneZeroZero = false)

    private val GNOSIS = table(l2 = true, includeOneZeroZero = true)

    /**
     * A new Safe is created from 1.4.1's L1 singleton or, with [l2], its L2 one; [includeOneZeroZero]
     * (false: Sepolia alone) says whether 1.0.0's proxy hash and singleton are in [Table.proxyCodeHashes]
     * and [Table.singletons] — 1.1.1's and 1.2.0's are, on every network this table serves.
     */
    private fun table(l2: Boolean, includeOneZeroZero: Boolean) = Table(
        proxyCodeHashes = buildSet {
            if (includeOneZeroZero) {
                // 1.0.0, from ProxyFactory 0x12302fE9c02ff50939BaAaaf415fc226C078613C.
                add("f8fffcb9b9c73fbc4ffb6dc52f0001fa998951e0d4177894b1b899f7732b6eaf")
            }
            // 1.1.1 (also 1.2.0's, which reuses this factory), from ProxyFactory
            // 0x76E2cFc1F5Fa8F6a5b3fC4c8F4788F0116861F9B.
            add("aea7d4252f6245f301e540cfbee27d3a88de543af8e49c5c62405d5499fab7e5")
            // 1.3.0, from GnosisSafeProxyFactory 0xa6B71E26C5e0845f74c812102Ca7114b6a896AB2.
            add("b89c1b3bdf2cf8827818646bce9a8f6e372885f8c55e5c07acbd307cb133b000")
            // 1.4.1, from SafeProxyFactory 0x4e1DCf7AD4e460CfD30791CCC4F9c8a4f820ec67.
            add("d7d408ebcd99b2b70be43e20253d6d92a8ea8fab29bd3be7f55b10032331fb4c")
            // 1.5.0, from SafeProxyFactory 0x14F2982D601c9458F93bd70B218933A6f8165e7b.
            add("4e381985ca68b3e5d27b4425fa581c19cf33146d3f887a3cfca96f55528ea46f")
        },
        singletons = buildMap {
            if (includeOneZeroZero) this += singleton("0xb6029ea3b2c51d09a50b53ca8012feeb05bda35a", "1.0.0", l2 = false)
            this += singleton("0x34cfac646f301356faa8b21e94227e3583fe3f5f", "1.1.1", l2 = false)
            this += singleton("0x6851d6fdfafd08c0295c392436245e5bc78b0185", "1.2.0", l2 = false)
            this += singleton("0xd9db270c1b5e3bd161e8c8503c55ceabee709552", "1.3.0", l2 = false)
            this += singleton("0x3e5c63644e683549055b9be8653de26e0b4cd36e", "1.3.0", l2 = true)
            this += singleton("0x69f4d1788e39c87893c980c06edf4b7f686e2938", "1.3.0", l2 = false)
            this += singleton("0xfb1bffc9d739b8d520daf37df666da4c687191ea", "1.3.0", l2 = true)
            this += singleton(SINGLETON_1_4_1, "1.4.1", l2 = false)
            this += singleton(SINGLETON_L2_1_4_1, "1.4.1", l2 = true)
            this += singleton("0xff51a5898e281db6dfc7855790607438df2ca44b", "1.5.0", l2 = false, moduleGuard = true)
            this += singleton("0xedd160febbd92e350d4d398fb636302fccd67c7e", "1.5.0", l2 = true, moduleGuard = true)
        },
        creation = SafeRelease(
            version = "1.4.1",
            l2 = l2,
            singleton = Address.of(if (l2) SINGLETON_L2_1_4_1 else SINGLETON_1_4_1),
            proxyFactory = Address.of("0x4e1dcf7ad4e460cfd30791ccc4f9c8a4f820ec67"),
            proxyCreationCode = PROXY_CREATION_CODE_1_4_1.fromHex(),
            fallbackHandler = Address.ZERO,
        ),
    )

    private const val SINGLETON_1_4_1 = "0x41675c099f32341bf84bfc5382af534df5c7461a"
    private const val SINGLETON_L2_1_4_1 = "0x29fcb43b46531bca003ddc8fcb67ffe91900c762"

    private fun singleton(address: String, version: String, l2: Boolean, moduleGuard: Boolean = false) =
        Address.of(address) to SafeSingleton(version, l2, moduleGuard)
}

/**
 * `type(SafeProxy).creationCode` of Safe 1.4.1's SafeProxyFactory: 486 bytes, the constructor and
 * then, at offset 0x119, the 171 bytes of runtime code it deploys (see [SafeDeployments]).
 */
private const val PROXY_CREATION_CODE_1_4_1 =
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

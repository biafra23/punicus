package eth.serotine.data.safe

import eth.serotine.data.keys.fromHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeRelease

/**
 * A canonical Safe singleton: its [version] ("1.4.1"), whether it is the L2 variant ([l2]),
 * whether it consults a guard ([guard]: 1.3.0 on; older ones never read it, so a word in that
 * slot is nothing) and a module guard ([moduleGuard]: 1.5.0 on).
 */
internal data class SafeSingleton(
    val version: String,
    val l2: Boolean,
    val moduleGuard: Boolean = false,
    val guard: Boolean = true,
)

/**
 * The Safe deployments a Safe is recognised by, per network (docs/wallet-ux.md, *Safes*): the
 * runtime code hashes of the SafeProxy contracts ([Table.proxyCodeHashes], lower-case hex without
 * `0x`), and the Safe singletons a proxy's slot 0 must name ([Table.singletons]). A contract
 * matching neither is "another contract", never a Safe.
 *
 * **The singleton is the anchor; the proxy is audited.** A proxy does nothing of its own: it
 * reads slot 0 and delegates every call to it, so what the account does is its singleton's code.
 * The singletons are therefore pinned by address, per network, and only where that address holds
 * live code of the release it is pinned as. The proxies are pinned by code hash, the same set on
 * every network, each one checked (`auditProxyCode`, `SafeDeploymentsTest`) to reach nothing but
 * an `SLOAD` of slot 0, a `DELEGATECALL` to it, and a return or revert: no storage write, no
 * call, no create, no self-destruct, no log, no jump to a computed target. A proxy hash pinned on
 * a network where none was seen therefore widens nothing: such a proxy still names a singleton,
 * and only a pinned one makes it a Safe.
 *
 * **Proxies.** Safe's own factories for every release since 1.0.0 deploy one each (1.1.1's
 * factory a build of its own on Gnosis Chain, which differs from Mainnet's only in Solidity's
 * trailing metadata hash), and Safes were made in other ways too: the 2018–19 relay service's
 * `PayingProxy`s and the 0.x factories' proxies, Circles' factory on Gnosis Chain, and third
 * parties' builds of the same few lines. A survey on 2026-11-07 found them: the accounts that
 * `DELEGATECALL` each Safe singleton (Blockscout's internal transactions, Ethereum and Sepolia),
 * the emitters of Safe events (`ExecutionSuccess`, `ExecutionFailed`, `SafeSetup`) in windows
 * spread over each chain's history, the emitters of `ChangedMasterCopy` over all of it, and the
 * proxies the old factories' `ProxyCreation` events name; every distinct code among them whose
 * slot 0 names a Safe singleton, and that passes the audit, is pinned in [PROXY_CODE_HASHES] with
 * an account carrying it. Its reads were Blockscout's API and public nodes (gnosischain.com,
 * publicnode.com), a debugging source only (CLAUDE.md, *Data sources*): the light client proves
 * a Safe's proxy code and slot 0 before it is one. The survey samples: a proxy built some other
 * way and not yet seen still reads as "another contract", never as a Safe it is not.
 *
 * **Singletons.** Every Safe release in `safe-global/safe-deployments` (1.0.0, 1.1.1, 1.2.0,
 * 1.3.0 canonical and eip155, L1 and L2, 1.4.1 and 1.5.0, L1 and L2), and the older or forked
 * ones Safe's own Transaction Service indexes (`/api/v1/about/singletons/`, 2026-11-07): 0.0.2,
 * 0.1.0 and 1.1.0 on Ethereum, and Circles' 1.1.1 on Gnosis Chain. Each was read on 2026-11-07 on
 * each network: its code (present or not) and its `VERSION()`.
 * - 1.1.1 to 1.5.0 carry live code on all three networks (1.1.1's on Gnosis Chain a separate
 *   build: the functional bytecode is identical, the metadata hash differs).
 * - 1.0.0 and 0.1.0 on Ethereum and Gnosis Chain (0.1.0's code the same on both); 0.0.2 and 1.1.0
 *   on Ethereum alone; Circles' on Gnosis Chain alone, at two addresses holding byte for byte the
 *   same code. Circles' source (npm `@circles/safe-contracts` 1.0.20) is safe-contracts 1.1.1 with
 *   one line added, an `AddedOwner` event in `setupOwners`; its `VERSION()` is "1.1.1".
 * - Every release from 0.0.2 on keeps `MasterCopy`, the modules, the owners, their count, the
 *   threshold and the nonce in slots 0 to 5, each list behind the sentinel `0x1` (read in each
 *   tag's source), which is what `readSafe` reads; before 1.3.0 there is no guard and before
 *   1.5.0 no module guard, so neither slot is read ([SafeSingleton.guard], [SafeSingleton.moduleGuard]):
 *   a word an old Safe's own delegatecall wrote there protects nothing.
 *
 * **Recognised is not signable.** A Safe left on a release before 1.3.0 checks a different
 * EIP-712 domain than `SafeTx.hash` computes (0.0.2 and 0.1.0 another `SafeTx` type besides), so
 * `SafeTxRefusal.TooOldToSign` keeps every signing, approval and execution flow off it, and
 * refuses any version but 1.3.0, 1.4.1 and 1.5.0. A Safe moved since to a pinned ≥1.3.0
 * singleton by its owners' own `changeMasterCopy` is signed as what it is now (diskordia.eth on
 * Ethereum, 0xcba12315cc838375F0e1E9a9f5b2aFE0196B07B6 on Gnosis Chain): its proxy's code never
 * changes, which is why the old proxies matter.
 *
 * **Not pinned**, found by the same survey: a "Gnosis Safe" `VERSION()` "1.2.0" singleton on
 * Gnosis Chain at 0x9c75a217aea76663a9a37687606f099945eb0742 whose code is not 1.2.0's (a
 * different build of unknown source), a "1.4.0" one at 0xc962e67d9490e154d81181879ddf4cd3b65d2132
 * (1.4.0 was never deployed by Safe), and a few one-off 1.3.0 builds on Sepolia: a `VERSION()`
 * string proves nothing about the code behind it.
 *
 * **Sepolia leaves 1.0.0 and the 0.x releases out**: their addresses carry no code there. Pinning
 * an address with no code would be a finding on its own (CLAUDE.md, *Trust*): recognition never
 * reads a singleton's own code, only that a proxy's slot 0 names a pinned address, so an attacker
 * could deploy any pinned proxy runtime at a fresh address with slot 0 set to that empty address
 * and a constructor that writes whatever owners and threshold it likes into the rest — Punicus
 * would show it as a verified Safe, and a `delegatecall` to an empty address always "succeeds" as
 * a no-op, so nothing sent to it could ever move again.
 *
 * A new Safe is created from [Table.creation] (docs/wallet-ux.md, *Safes*: creation): Safe 1.4.1,
 * its L1 singleton (Safe{Wallet}'s choice for Ethereum and its testnets) or, on Gnosis Chain, its
 * L2 one (owner ruling, 2026-10-22; docs/networks.md, open question 6), and its SafeProxyFactory,
 * with no fallback handler: `setup` installs none. (The one CompatibilityFallbackHandler a Safe
 * transaction may set is pinned in the domain, `PinnedFallbackHandlers`, beside the check that
 * refuses any other.) The factory's proxy creation code is `SafeProxy`'s `bytecode` in the npm
 * package `@safe-global/safe-contracts` 1.4.1-build.0
 * (`build/artifacts/contracts/proxies/SafeProxy.sol/SafeProxy.json`), the build the canonical
 * deployment came from: the runtime code it deploys hashes to the 1.4.1 proxy hash, read on
 * Sepolia, and that package's SafeProxyFactory runtime code hashes to the factory's code hash in
 * safe-global/safe-deployments (`src/assets/v1.4.1/safe_proxy_factory.json`, `0x50c3cdc4…`), the
 * same on Ethereum and Gnosis Chain (read 2026-10-23). It is only what is expected: a deployment
 * is quoted only when the factory's verified run returns the address computed from it.
 * `MyotisHostEthereumTest` and `MyotisHostGnosisTest` prove the deployments through the light
 * client.
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
        Network.SEPOLIA -> SEPOLIA
        Network.GNOSIS -> GNOSIS
    }

    /**
     * Every SafeProxy runtime code a Safe is recognised by, on every network (see *Proxies* above):
     * Keccak-256 of the code, lower-case hex without `0x`. Each was found on a real account whose
     * slot 0 names a Safe singleton, and each passes `auditProxyCode` in `SafeDeploymentsTest`,
     * which holds every one of these codes: it only reads slot 0 and delegates to it.
     */
    private val PROXY_CODE_HASHES: Set<String> = setOf(
        // 1.0.0, from its ProxyFactory 0x12302fE9c02ff50939BaAaaf415fc226C078613C; e.g.
        // 0x3bcebeafee1476ae989ef108c2c825ad3be7a38b (Ethereum).
        "f8fffcb9b9c73fbc4ffb6dc52f0001fa998951e0d4177894b1b899f7732b6eaf",
        // 1.1.1, from its ProxyFactory 0x76E2cFc1F5Fa8F6a5b3fC4c8F4788F0116861F9B (also 1.2.0's) on Ethereum and
        // Sepolia; e.g. 0x89e48081004d48d371f0904c0633cf3f82a7a563 (Ethereum),
        // 0x2df1bddfb4dc6125e41ef380b71e42da3f793979 (Sepolia).
        "aea7d4252f6245f301e540cfbee27d3a88de543af8e49c5c62405d5499fab7e5",
        // 1.1.1, from the same factory on Gnosis Chain: its own build, only the metadata hash differs; e.g.
        // 0x52967393dc54d0e980041a4a87f61c000065f5c2 (Gnosis).
        "30cf24338d14750aab3433d8bb0cedd0e1263ee1f4f5f84cc482f3541ce61ad5",
        // 1.3.0, from GnosisSafeProxyFactory 0xa6B71E26C5e0845f74c812102Ca7114b6a896AB2 and its eip155 twin
        // 0xC22834581EbC8527d974F8a1c97E1bEA4EF910BC; e.g. 0xdab5dc22350f9a6aff03cf3d9341aad0ba42d2a6 (Ethereum),
        // 0x428fce210302022f8fb6d9a1e409fdc0076dc8a2 (Gnosis).
        "b89c1b3bdf2cf8827818646bce9a8f6e372885f8c55e5c07acbd307cb133b000",
        // 1.4.1, from SafeProxyFactory 0x4e1DCf7AD4e460CfD30791CCC4F9c8a4f820ec67; e.g.
        // 0x000000000034065b3a94c2118cfe5b4c0067b615 (Ethereum), 0x132f5f7b5371f9c3d41b80b247a003fe14c117de (Gnosis).
        "d7d408ebcd99b2b70be43e20253d6d92a8ea8fab29bd3be7f55b10032331fb4c",
        // 1.5.0, from SafeProxyFactory 0x14F2982D601c9458F93bd70B218933A6f8165e7b; e.g.
        // 0xd55c4bbaed4ba5cd8aceb19446d55c54313d94b3 (Ethereum), 0x6bebf90d543aa9fbe1552906a8782a4ec3699455 (Gnosis).
        "4e381985ca68b3e5d27b4425fa581c19cf33146d3f887a3cfca96f55528ea46f",
        // 170 bytes, solc 0.5.14; e.g. 0x02f84fdf488800c283711388765a9acc42febeb4 (Gnosis).
        "04300dd97a65ddc3d1870120937113cff2431488e69409b29e6694dfe999d91f",
        // 363 bytes, solc 0.4.x or 0.5.0; e.g. 0x9177eee5b477fe65293141e55b328f42c991012e (Ethereum).
        "a2149e1041c8e8693d46c3802fed55726adaf6118d698b3d6837a7d040a2b738",
        // 128 bytes, solc 0.5.12; e.g. 0x059af59a40f06cccc8332203f3e01d8f828eed7d (Ethereum).
        "6822dcbabf40f61e208fb0127b3640d2e7edd75edce4a7c7bef2eee6e16e83db",
        // 170 bytes, solc 0.5.12; e.g. 0xf9d67ee9cbdc848220e376a6a0594c3ed7cc5372 (Ethereum).
        "08c5547e6a24e8e11059160715b82d6533df7061e5175cc6dd77c80fcef95b0e",
        // 155 bytes, solc 0.7.6; e.g. 0x80220149cc035a5563ef3aa6b139e509410804a4 (Sepolia).
        "9b2c7738ad6f7668c0dbf458077fc4feb3972ca2cf4aff1f6e141fad961383d9",
        // 124 bytes, solc 0.8.4; e.g. 0xf73cefb5c1ea26b8677918ed13f12fe77d7dcf0e (Gnosis).
        "f1d9da6732f34f3d3558838ae5de4629ab3a1677d79c6091f49cae0f50e7b544",
        // 170 bytes, solc 0.5.16; e.g. 0x35911afed5e2e36f8c9189374554dde1c6c2f940 (Gnosis).
        "afe969a0aeba4a2a01697eb5cb1e77f879be28c3eb4564c8e0c671472ece3cd1",
        // 171 bytes, solc 0.6.0; e.g. 0xd6995cb71082c10d32254054983da4b3518e136e (Ethereum).
        "508e13281ad96d46d4c05e036d4327e1ad497604f6c8b3d726b7245473777051",
        // 170 bytes, solc 0.5.16; e.g. 0x7c45435f510f857e4b49b67f0fcf04878f865f34 (Gnosis).
        "f795b465d7d0ae759bcd9f7a138c0c7726ed2f7ccf878e81d2ce27207cec9352",
        // 161 bytes, solc 0.8.25; e.g. 0x858de93e8d8ba6942a0234c7e00ba62d7fbff7e0 (Ethereum),
        // 0xaf669e42c2e0d0a4869c80a3839a1cb2e503111c (Sepolia).
        "9cb083845b190ea14252e1b3d71bf271ea0f2e38713176a3c549bdee10c72509",
        // 363 bytes, solc 0.4.x or 0.5.0; e.g. 0xc25434eef69e92a6576e2e82cc07514b2393ec02 (Ethereum).
        "845cc1b596b9a947d0f61891c4a0e1f21151a085bce89ef1756ebd5d54f0e6b3",
        // 123 bytes, solc 0.8.20; e.g. 0x62b589dbf3b53c5c8b4f0428c802e1638809ab6a (Gnosis).
        "a8907185b40016f1ec01c4be40eff5a39077c723f7f0594dc498103889cbe4d7",
        // 170 bytes, solc 0.5.16; e.g. 0x30f17ea2782674f7b4999f6b0909d9be1e6e1dc4 (Gnosis).
        "7ee121a2afc1d36a1872010191f750e17abbacebf64d171b04845e6f6690036b",
        // 298 bytes, solc 0.4.x or 0.5.0; e.g. 0xc703839c822844b2b1e0cdcb7d5cb738068651b9 (Ethereum).
        "657afb2b65f6cd12b49638c04c70c8453ad0c15d66a861364fc52650ea1259d5",
        // 298 bytes, solc 0.4.x or 0.5.0; e.g. 0xd13c86b5d8371456875305505a99ddee3686a2aa (Ethereum).
        "f3d0b227157366efa7603b482a0176a3810d37b4366c4b5ce698943e085ecb5e",
        // 363 bytes, solc 0.4.x or 0.5.0; e.g. 0xfa29d3b68c5d99e44f363581b0ebaa023a7a8432 (Ethereum).
        "755530fddfb6ed34b17ec52dd353142e0b73a943b3663727dcde94f57d05831d",
        // 1.1.0, from its ProxyFactory 0x50e55Af101C777bA7A1d560a774A82eF002ced9F; e.g.
        // 0x9ff80c034f165bb25c041080634845e064d5599a (Ethereum).
        "d8724d863b8d429d28fc17e2e2f847118c297b4d9c7ec76ef9f8a31328762986",
        // 158 bytes, solc 0.8.31; e.g. 0x0b1aa37496c82199842604a34e452c52b1115df5 (Ethereum).
        "e23ecc4a9ddb192048659e13d82ab80372e5aadc68bc992a38bba07d5a5a9718",
        // 158 bytes, solc 0.8.31; e.g. 0x7eb3578c7f265992ed14b2bd7100da2072a2431b (Ethereum).
        "60526601bc9fff27f0a87ee0ca959b7849c8aa3dde380a2b3f0d500cd4f76559",
        // 123 bytes, solc 0.8.17; e.g. 0xb7900b3af2b9c5d1795f4aeb24aec20515dc8e67 (Gnosis).
        "341030d94f83499fbfba3995f438c7b49d4e766d1f83de36471ff86d626751d5",
        // 166 bytes, solc 0.8.25; e.g. 0x000000000000f2ef9adbed78a17dd54eb9279e68 (Gnosis).
        "d15744f2692c38c50163672a923d460335bbac7aaa6636cbb07c85a4967486d1",
        // 158 bytes, solc 0.8.24; e.g. 0x7a5693995bb39601ddc19a56f570f8615668d0b5 (Sepolia).
        "d30ffcc6951fc2f43853b37f2015e7c34a184909769bcc25ae0430e16434f1b5",
        // 171 bytes, solc 0.8.0; e.g. 0xe614e82c7397b79d156fe10146ef05fc832b0e9c (Sepolia).
        "1515751d9d5ad70f09faa30a05560445cfdb9dc6fc388318112af9ced2cb2e9e",
        // 169 bytes, solc 0.8.27; e.g. 0xedb89b030518012ad583d6569fe1e59a2628fa06 (Sepolia).
        "62708217736f2c9531fc08ec0cdf0f799ac34008f8b990fd191928bdf47af057",
        // 138 bytes, solc 0.8.26; e.g. 0x82003550aaa5f4bbbf8cfa41aff052e8d2f9972f (Sepolia).
        "bdc1fa1849a6ee5e99d18e1f993e4d789fc5ab0cde5ba7ee7545c462e493114d",
    )

    /** The singletons every network this app serves carries, at the same address with live code. */
    private val EVERYWHERE: Map<Address, SafeSingleton> = mapOf(
        singleton("0x34cfac646f301356faa8b21e94227e3583fe3f5f", "1.1.1", l2 = false),
        singleton("0x6851d6fdfafd08c0295c392436245e5bc78b0185", "1.2.0", l2 = false),
        singleton("0xd9db270c1b5e3bd161e8c8503c55ceabee709552", "1.3.0", l2 = false),
        singleton("0x3e5c63644e683549055b9be8653de26e0b4cd36e", "1.3.0", l2 = true),
        singleton("0x69f4d1788e39c87893c980c06edf4b7f686e2938", "1.3.0", l2 = false),
        singleton("0xfb1bffc9d739b8d520daf37df666da4c687191ea", "1.3.0", l2 = true),
        singleton(SINGLETON_1_4_1, "1.4.1", l2 = false),
        singleton(SINGLETON_L2_1_4_1, "1.4.1", l2 = true),
        singleton("0xff51a5898e281db6dfc7855790607438df2ca44b", "1.5.0", l2 = false, moduleGuard = true),
        singleton("0xedd160febbd92e350d4d398fb636302fccd67c7e", "1.5.0", l2 = true, moduleGuard = true),
    )

    /** 1.0.0's and 0.1.0's singletons: live on Ethereum and Gnosis Chain, no code on Sepolia. */
    private val ONE_ZERO_ZERO_AND_ZERO_ONE_ZERO: Map<Address, SafeSingleton> = mapOf(
        singleton("0xb6029ea3b2c51d09a50b53ca8012feeb05bda35a", "1.0.0", l2 = false),
        singleton("0x8942595a2dc5181df0465af0d7be08c8f23c93af", "0.1.0", l2 = false),
    )

    private val ETHEREUM = table(
        l2 = false,
        singletons = EVERYWHERE + ONE_ZERO_ZERO_AND_ZERO_ONE_ZERO + mapOf(
            // 0.0.2 and 1.1.0: live on Ethereum alone.
            singleton("0xac6072986e985aabe7804695ec2d8970cf7541a2", "0.0.2", l2 = false),
            singleton("0xae32496491b53841efb51829d6f886387708f99b", "1.1.0", l2 = false),
        ),
    )

    private val SEPOLIA = table(l2 = false, singletons = EVERYWHERE)

    private val GNOSIS = table(
        l2 = true,
        singletons = EVERYWHERE + ONE_ZERO_ZERO_AND_ZERO_ONE_ZERO + mapOf(
            // Circles' fork of 1.1.1, twice, byte for byte the same code: live on Gnosis Chain alone.
            singleton("0x2cb0ebc503de87cfd8f0eceed8197bf7850184ae", "1.1.1", l2 = false),
            singleton("0x16c08fd4d098a6d72da7196ad129d8b04425df91", "1.1.1", l2 = false),
        ),
    )

    /** A new Safe is created from 1.4.1's L1 singleton or, with [l2], its L2 one. */
    private fun table(l2: Boolean, singletons: Map<Address, SafeSingleton>) = Table(
        proxyCodeHashes = PROXY_CODE_HASHES,
        singletons = singletons,
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
        Address.of(address) to SafeSingleton(version, l2, moduleGuard, guard = version in GUARDED_VERSIONS)

    /** The releases that consult a guard: 1.3.0 introduced it. */
    private val GUARDED_VERSIONS = setOf("1.3.0", "1.4.1", "1.5.0")
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

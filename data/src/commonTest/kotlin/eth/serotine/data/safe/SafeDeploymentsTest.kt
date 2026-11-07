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
     * Every pinned proxy's runtime code, keyed by its hash, as `eth_getCode` returned it on
     * 2026-11-07 at the account named beside it (SafeDeployments.kt's doc comment, *Proxies*).
     */
    private val proxyCode: Map<String, String> = mapOf(
        // 1.0.0, from its ProxyFactory 0x12302fE9c02ff50939BaAaaf415fc226C078613C; e.g.
        // 0x3bcebeafee1476ae989ef108c2c825ad3be7a38b (Ethereum).
        "f8fffcb9b9c73fbc4ffb6dc52f0001fa998951e0d4177894b1b899f7732b6eaf" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054163660008037600080366000845af43d6000803e60" +
            "00811415603d573d6000fd5b3d6000f3fea165627a7a723058201e7d648b83cfac072cbccefc2ffc62a6999d4a050ee87a72" +
            "1942de1da9670db80029",
        // 1.1.1, from its ProxyFactory 0x76E2cFc1F5Fa8F6a5b3fC4c8F4788F0116861F9B (also 1.2.0's) on Ethereum and
        // Sepolia; e.g. 0x89e48081004d48d371f0904c0633cf3f82a7a563 (Ethereum),
        // 0x2df1bddfb4dc6125e41ef380b71e42da3f793979 (Sepolia).
        "aea7d4252f6245f301e540cfbee27d3a88de543af8e49c5c62405d5499fab7e5" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea265627a7a72315820d8a00dc4fe6bf675a9d7416fc2d00bb3433362aa8186b7" +
            "50f76c4027269667ff64736f6c634300050e0032",
        // 1.1.1, from the same factory on Gnosis Chain: its own build, only the metadata hash differs; e.g.
        // 0x52967393dc54d0e980041a4a87f61c000065f5c2 (Gnosis).
        "30cf24338d14750aab3433d8bb0cedd0e1263ee1f4f5f84cc482f3541ce61ad5" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea265627a7a723158200a23d2285c529de46060100feb312da05c409802321897" +
            "869814263b54bb746264736f6c634300050e0032",
        // 1.3.0, from GnosisSafeProxyFactory 0xa6B71E26C5e0845f74c812102Ca7114b6a896AB2 and its eip155 twin
        // 0xC22834581EbC8527d974F8a1c97E1bEA4EF910BC; e.g. 0xdab5dc22350f9a6aff03cf3d9341aad0ba42d2a6 (Ethereum),
        // 0x428fce210302022f8fb6d9a1e409fdc0076dc8a2 (Gnosis).
        "b89c1b3bdf2cf8827818646bce9a8f6e372885f8c55e5c07acbd307cb133b000" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea2646970667358221220d1429297349653a4918076d650332de1a1068c5f3e07" +
            "c5c82360c277770b955264736f6c63430007060033",
        // 1.4.1, from SafeProxyFactory 0x4e1DCf7AD4e460CfD30791CCC4F9c8a4f820ec67; e.g.
        // 0x000000000034065b3a94c2118cfe5b4c0067b615 (Ethereum), 0x132f5f7b5371f9c3d41b80b247a003fe14c117de (Gnosis).
        "d7d408ebcd99b2b70be43e20253d6d92a8ea8fab29bd3be7f55b10032331fb4c" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea264697066735822122003d1488ee65e08fa41e58e888a9865554c535f2c7712" +
            "6a82cb4c0f917f31441364736f6c63430007060033",
        // 1.5.0, from SafeProxyFactory 0x14F2982D601c9458F93bd70B218933A6f8165e7b; e.g.
        // 0xd55c4bbaed4ba5cd8aceb19446d55c54313d94b3 (Ethereum), 0x6bebf90d543aa9fbe1552906a8782a4ec3699455 (Gnosis).
        "4e381985ca68b3e5d27b4425fa581c19cf33146d3f887a3cfca96f55528ea46f" to
            "608060405260005463a619486e60003560e01c14156024578060601b606c5260206060f35b3660008037600080366000845a" +
            "f43d6000803e806040573d6000fd5b3d6000f3fea2646970667358221220e61834ebd2d8cd909d362bf67c47ef58fd665df3" +
            "8e6dd036ce65611101d072e964736f6c63430007060033",
        // 170 bytes, solc 0.5.14; e.g. 0x02f84fdf488800c283711388765a9acc42febeb4 (Gnosis).
        "04300dd97a65ddc3d1870120937113cff2431488e69409b29e6694dfe999d91f" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea265627a7a723158200c4327fdfe64d02c3f555f7d998190f35c428652fcf497" +
            "70f5a608de2ec22c0964736f6c634300050e0032",
        // 363 bytes, solc 0.4.x or 0.5.0; e.g. 0x9177eee5b477fe65293141e55b328f42c991012e (Ethereum).
        "a2149e1041c8e8693d46c3802fed55726adaf6118d698b3d6837a7d040a2b738" to
            "60806040526004361061004c576000357c0100000000000000000000000000000000000000000000000000000000900463ff" +
            "ffffff1680634555d5c91461008b5780635c60da1b146100b6575b73ffffffffffffffffffffffffffffffffffffffff6000" +
            "54163660008037600080366000845af43d6000803e6000811415610086573d6000fd5b3d6000f35b34801561009757600080" +
            "fd5b506100a061010d565b6040518082815260200191505060405180910390f35b3480156100c257600080fd5b506100cb61" +
            "0116565b604051808273ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffff" +
            "ffff16815260200191505060405180910390f35b60006002905090565b60008060009054906101000a900473ffffffffffff" +
            "ffffffffffffffffffffffffffff169050905600a165627a7a7230582007fffd557dfc8c4d2fdf56ba6381a6ce5b65b6260e" +
            "1492d87f26c6d4f1d041080029",
        // 128 bytes, solc 0.5.12; e.g. 0x059af59a40f06cccc8332203f3e01d8f828eed7d (Ethereum).
        "6822dcbabf40f61e208fb0127b3640d2e7edd75edce4a7c7bef2eee6e16e83db" to
            "60806040526001600160a01b036000541663530ca43760e11b6000351415602a578060005260206000f35b36600080376000" +
            "80366000845af43d6000803e806046573d6000fd5b3d6000f3fea265627a7a72315820349e0e1e4d129d6af192345f46ba63" +
            "00c7fb3e1045cf987467491b52b30b7cd764736f6c634300050c0032",
        // 170 bytes, solc 0.5.12; e.g. 0xf9d67ee9cbdc848220e376a6a0594c3ed7cc5372 (Ethereum).
        "08c5547e6a24e8e11059160715b82d6533df7061e5175cc6dd77c80fcef95b0e" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea265627a7a7231582099bf9ecf033b5a5cd7073e2e717749e94f49c79dbfc065" +
            "d6204cb99db2ba11ab64736f6c634300050c0032",
        // 155 bytes, solc 0.7.6; e.g. 0x80220149cc035a5563ef3aa6b139e509410804a4 (Sepolia).
        "9b2c7738ad6f7668c0dbf458077fc4feb3972ca2cf4aff1f6e141fad961383d9" to
            "60806040526000547fa619486e00000000000000000000000000000000000000000000000000000000600035141560405780" +
            "600c1b600c1c60005260206000f35b3660008037600080366000845af43d6000803e60008114156060573d6000fd5b3d6000" +
            "f3fea2646970667358221220bfbe5e66dfccd59d80684323ec36a561ddc5ef3b39a33a941f25cabefff21eb964736f6c6343" +
            "0007060033",
        // 124 bytes, solc 0.8.4; e.g. 0xf73cefb5c1ea26b8677918ed13f12fe77d7dcf0e (Gnosis).
        "f1d9da6732f34f3d3558838ae5de4629ab3a1677d79c6091f49cae0f50e7b544" to
            "6080604052600080546001600160a01b0316813563530ca43760e11b1415602857808252602082f35b368283378182368484" +
            "5af490503d82833e806041573d82fd5b503d81f3fea26469706673582212208dd75555e4cccb142dfa9cea468f2cf52b842a" +
            "3b2ae5c040a21e5902d317b8f864736f6c63430008040033",
        // 170 bytes, solc 0.5.16; e.g. 0x35911afed5e2e36f8c9189374554dde1c6c2f940 (Gnosis).
        "afe969a0aeba4a2a01697eb5cb1e77f879be28c3eb4564c8e0c671472ece3cd1" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea265627a7a72315820c79720b165380458f5a69345750b0a86c10a1feceae506" +
            "5b744a1e981bf980db64736f6c63430005100032",
        // 171 bytes, solc 0.6.0; e.g. 0xd6995cb71082c10d32254054983da4b3518e136e (Ethereum).
        "508e13281ad96d46d4c05e036d4327e1ad497604f6c8b3d726b7245473777051" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea264697066735822122056e8ae02bde6776b1debb4cdca0d5f13ebc4d11705a6" +
            "711c07d9ed69e0d5f37264736f6c63430006000033",
        // 170 bytes, solc 0.5.16; e.g. 0x7c45435f510f857e4b49b67f0fcf04878f865f34 (Gnosis).
        "f795b465d7d0ae759bcd9f7a138c0c7726ed2f7ccf878e81d2ce27207cec9352" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea265627a7a723158207c640d22cdc94b2a4ff3b2dcff71ca74e666d8490a66fe" +
            "432fc596537bc1780964736f6c63430005100032",
        // 161 bytes, solc 0.8.25; e.g. 0x858de93e8d8ba6942a0234c7e00ba62d7fbff7e0 (Ethereum),
        // 0xaf669e42c2e0d0a4869c80a3839a1cb2e503111c (Sepolia).
        "9cb083845b190ea14252e1b3d71bf271ea0f2e38713176a3c549bdee10c72509" to
            "6080604052600073ffffffffffffffffffffffffffffffffffffffff8154167fa619486e0000000000000000000000000000" +
            "0000000000000000000000000000823503604d57808252602082f35b3682833781823684845af490503d82833e806066573d" +
            "82fd5b503d81f3fea2646970667358221220f89ca516c377163343639e4278fbb9e3e2d018eddf7c97364e813fbb6dac5a87" +
            "64736f6c63430008190033",
        // 363 bytes, solc 0.4.x or 0.5.0; e.g. 0xc25434eef69e92a6576e2e82cc07514b2393ec02 (Ethereum).
        "845cc1b596b9a947d0f61891c4a0e1f21151a085bce89ef1756ebd5d54f0e6b3" to
            "60806040526004361061004c576000357c0100000000000000000000000000000000000000000000000000000000900463ff" +
            "ffffff1680634555d5c91461008b5780635c60da1b146100b6575b73ffffffffffffffffffffffffffffffffffffffff6000" +
            "54163660008037600080366000845af43d6000803e6000811415610086573d6000fd5b3d6000f35b34801561009757600080" +
            "fd5b506100a061010d565b6040518082815260200191505060405180910390f35b3480156100c257600080fd5b506100cb61" +
            "0116565b604051808273ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffff" +
            "ffff16815260200191505060405180910390f35b60006002905090565b60008060009054906101000a900473ffffffffffff" +
            "ffffffffffffffffffffffffffff1690509056fea165627a7a72305820551643afa6ad5a0e661514e9196f5d113ecc9db4f5" +
            "bb40ac121ef75261819edb0029",
        // 123 bytes, solc 0.8.20; e.g. 0x62b589dbf3b53c5c8b4f0428c802e1638809ab6a (Gnosis).
        "a8907185b40016f1ec01c4be40eff5a39077c723f7f0594dc498103889cbe4d7" to
            "6080604052600080546001600160a01b0316632cf35bc960e11b823501602757808252602082f35b3682833781823684845a" +
            "f490503d82833e806040573d82fd5b503d81f3fea2646970667358221220eaaddd75a63b28423ea15a98ca99d3faeebf720c" +
            "f05c8bc85426e94a3df53b7f64736f6c63430008140033",
        // 170 bytes, solc 0.5.16; e.g. 0x30f17ea2782674f7b4999f6b0909d9be1e6e1dc4 (Gnosis).
        "7ee121a2afc1d36a1872010191f750e17abbacebf64d171b04845e6f6690036b" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea265627a7a72315820cac3d62bfce6ec8b54a3201159f745e39db8fa84029fbc" +
            "aa233ea75c5ceaac8264736f6c63430005100032",
        // 298 bytes, solc 0.4.x or 0.5.0; e.g. 0xc703839c822844b2b1e0cdcb7d5cb738068651b9 (Ethereum).
        "657afb2b65f6cd12b49638c04c70c8453ad0c15d66a861364fc52650ea1259d5" to
            "6080604052600436106042577c010000000000000000000000000000000000000000000000000000000060003504634555d5" +
            "c98114607e5780635c60da1b1460a2575b73ffffffffffffffffffffffffffffffffffffffff600054163660008037600080" +
            "366000845af43d6000803e8015156079573d6000fd5b3d6000f35b348015608957600080fd5b50609060dd565b6040805191" +
            "8252519081900360200190f35b34801560ad57600080fd5b5060b460e2565b6040805173ffffffffffffffffffffffffffff" +
            "ffffffffffff9092168252519081900360200190f35b600290565b60005473ffffffffffffffffffffffffffffffffffffff" +
            "ff169056fea165627a7a72305820d278b7091559d63f0d2dad6039fe3d7d7bd89ed03fc718eb1b55e80fdbf00e590029",
        // 298 bytes, solc 0.4.x or 0.5.0; e.g. 0xd13c86b5d8371456875305505a99ddee3686a2aa (Ethereum).
        "f3d0b227157366efa7603b482a0176a3810d37b4366c4b5ce698943e085ecb5e" to
            "6080604052600436106042577c010000000000000000000000000000000000000000000000000000000060003504634555d5" +
            "c98114607e5780635c60da1b1460a2575b73ffffffffffffffffffffffffffffffffffffffff600054163660008037600080" +
            "366000845af43d6000803e8015156079573d6000fd5b3d6000f35b348015608957600080fd5b50609060dd565b6040805191" +
            "8252519081900360200190f35b34801560ad57600080fd5b5060b460e2565b6040805173ffffffffffffffffffffffffffff" +
            "ffffffffffff9092168252519081900360200190f35b600290565b60005473ffffffffffffffffffffffffffffffffffffff" +
            "ff169056fea165627a7a723058202e083288918c7cf7cc3632119cdbdf5d6fd9f32bc7f4f1c88c4d83a378d8be110029",
        // 363 bytes, solc 0.4.x or 0.5.0; e.g. 0xfa29d3b68c5d99e44f363581b0ebaa023a7a8432 (Ethereum).
        "755530fddfb6ed34b17ec52dd353142e0b73a943b3663727dcde94f57d05831d" to
            "60806040526004361061004c576000357c0100000000000000000000000000000000000000000000000000000000900463ff" +
            "ffffff1680634555d5c91461008b5780635c60da1b146100b6575b73ffffffffffffffffffffffffffffffffffffffff6000" +
            "54163660008037600080366000845af43d6000803e6000811415610086573d6000fd5b3d6000f35b34801561009757600080" +
            "fd5b506100a061010d565b6040518082815260200191505060405180910390f35b3480156100c257600080fd5b506100cb61" +
            "0116565b604051808273ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffff" +
            "ffff16815260200191505060405180910390f35b60006002905090565b60008060009054906101000a900473ffffffffffff" +
            "ffffffffffffffffffffffffffff1690509056fea165627a7a72305820e981cc2ed6eeaadc61173829f16576c194d53b26da" +
            "d5739100151620a9fd77ac0029",
        // 1.1.0, from its ProxyFactory 0x50e55Af101C777bA7A1d560a774A82eF002ced9F; e.g.
        // 0x9ff80c034f165bb25c041080634845e064d5599a (Ethereum).
        "d8724d863b8d429d28fc17e2e2f847118c297b4d9c7ec76ef9f8a31328762986" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea265627a7a72315820494b02eb8639f5e3eb9cccbe6fde8592bccf26b40e291c" +
            "f1e2286fec6d62698764736f6c634300050b0032",
        // 158 bytes, solc 0.8.31; e.g. 0x0b1aa37496c82199842604a34e452c52b1115df5 (Ethereum).
        "e23ecc4a9ddb192048659e13d82ab80372e5aadc68bc992a38bba07d5a5a9718" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff5f54167fa619486e00000000000000000000000000000000" +
            "0000000000000000000000005f3503604b57805f5260205ff35b365f5f375f5f365f845af43d5f5f3e5f81036064573d5ffd" +
            "5b3d5ff3fea2646970667358221220d4a6fa58ff3257d04595f70a9717db48c0590d70a0ba2e5eeb4ddd2680bebe6064736f" +
            "6c634300081f0033",
        // 158 bytes, solc 0.8.31; e.g. 0x7eb3578c7f265992ed14b2bd7100da2072a2431b (Ethereum).
        "60526601bc9fff27f0a87ee0ca959b7849c8aa3dde380a2b3f0d500cd4f76559" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff5f54167fa619486e00000000000000000000000000000000" +
            "0000000000000000000000005f3503604b57805f5260205ff35b365f5f375f5f365f845af43d5f5f3e5f81036064573d5ffd" +
            "5b3d5ff3fea26469706673582212208c37eeda23bd3727524b8c141eaa1fb446590d24863b19d8fed50ff7e331fe3764736f" +
            "6c634300081f0033",
        // 123 bytes, solc 0.8.17; e.g. 0xb7900b3af2b9c5d1795f4aeb24aec20515dc8e67 (Gnosis).
        "341030d94f83499fbfba3995f438c7b49d4e766d1f83de36471ff86d626751d5" to
            "6080604052600080546001600160a01b0316632cf35bc960e11b823501602757808252602082f35b3682833781823684845a" +
            "f490503d82833e806040573d82fd5b503d81f3fea2646970667358221220a34202347e8dc73d4f5a3ac0cc4213ed5de5f6c4" +
            "5fd0fa4d18903f12161dc13764736f6c63430008110033",
        // 166 bytes, solc 0.8.25; e.g. 0x000000000000f2ef9adbed78a17dd54eb9279e68 (Gnosis).
        "d15744f2692c38c50163672a923d460335bbac7aaa6636cbb07c85a4967486d1" to
            "60806040525f3603600c57005b73ffffffffffffffffffffffffffffffffffffffff5f54167fa619486e0000000000000000" +
            "00000000000000000000000000000000000000005f3503605357805f5260205ff35b365f80375f80365f845af43d5f803e5f" +
            "8103606c573d5ffd5b3d5ff3fea264697066735822122008ff64eb9bbec8ca8684e7497db27d232082ccc9b6d33e31434e12" +
            "ff0607ab7864736f6c63430008190033",
        // 158 bytes, solc 0.8.24; e.g. 0x7a5693995bb39601ddc19a56f570f8615668d0b5 (Sepolia).
        "d30ffcc6951fc2f43853b37f2015e7c34a184909769bcc25ae0430e16434f1b5" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff5f54167fa619486e00000000000000000000000000000000" +
            "0000000000000000000000005f3503604b57805f5260205ff35b365f80375f80365f845af43d5f803e5f81036064573d5ffd" +
            "5b3d5ff3fea2646970667358221220f7fb06bae2fcfceca484e049f25ae6cb576336492c542975e5bbfda541ad15e664736f" +
            "6c63430008180033",
        // 171 bytes, solc 0.8.0; e.g. 0xe614e82c7397b79d156fe10146ef05fc832b0e9c (Sepolia).
        "1515751d9d5ad70f09faa30a05560445cfdb9dc6fc388318112af9ced2cb2e9e" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea264697066735822122042d584a5825c8b0c451d63cc6eec56240a9994e5f049" +
            "99308520f35536bb24d764736f6c63430008000033",
        // 169 bytes, solc 0.8.27; e.g. 0xedb89b030518012ad583d6569fe1e59a2628fa06 (Sepolia).
        "62708217736f2c9531fc08ec0cdf0f799ac34008f8b990fd191928bdf47af057" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003503604f578060005260206000f35b3660008037600080366000845af43d6000803e60" +
            "008103606e573d6000fd5b3d6000f3fea2646970667358221220ed1bef2c485d70a0c7cd946fbf6d456410ab1b1de4b8fa4c" +
            "4382bffaa5ce396b64736f6c634300081b0033",
        // 138 bytes, solc 0.8.26; e.g. 0x82003550aaa5f4bbbf8cfa41aff052e8d2f9972f (Sepolia).
        "bdc1fa1849a6ee5e99d18e1f993e4d789fc5ab0cde5ba7ee7545c462e493114d" to
            "60806040525f547fa619486e000000000000000000000000000000000000000000000000000000005f35036039578060601b" +
            "600c5260205ff35b365f80375f80365f845af43d5f803e806050573d5ffd5b3d5ff3fea2646970667358221220bd6ddd04d2" +
            "f744c25da38607828fc1d99d28ec76a17493f63e375f50113a6f6b64736f6c634300081a0033",
    )

    private val oneOneOne = "aea7d4252f6245f301e540cfbee27d3a88de543af8e49c5c62405d5499fab7e5"
    private val oneOneOneGnosis = "30cf24338d14750aab3433d8bb0cedd0e1263ee1f4f5f84cc482f3541ce61ad5"
    private val oneFourOne = "d7d408ebcd99b2b70be43e20253d6d92a8ea8fab29bd3be7f55b10032331fb4c"

    @Test
    fun everyNetworkPinsExactlyTheProxiesWhoseCodeIsHeldHere() {
        for (network in safeNetworks) {
            assertEquals(proxyCode.keys, SafeDeployments.of(network).proxyCodeHashes, network.displayName)
        }
    }

    @Test
    fun everyPinnedProxyHashIsTheHashOfItsCode() {
        for ((hash, code) in proxyCode) assertEquals(hash, Keccak256().digest(code.fromHex()).toHex())
    }

    @Test
    fun everyPinnedProxyOnlyReadsSlotZeroAndDelegatesToIt() {
        for ((hash, code) in proxyCode) assertEquals(emptyList(), auditProxyCode(code.fromHex()), hash)
    }

    @Test
    fun theAuditRefusesAProxyThatDoesMoreOrSomethingElse() {
        val delegateToSlotZero = "60005473ffffffffffffffffffffffffffffffffffffffff16366000803760008036600084" + "5af4"
        assertEquals(emptyList(), auditProxyCode(delegateToSlotZero.fromHex()))
        // A storage write first; slot 1 read instead of 0; a fixed target; a CALL; nothing delegated.
        assertTrue("SSTORE reachable at 4" in auditProxyCode(("6001600155" + delegateToSlotZero).fromHex()))
        assertTrue(auditProxyCode(delegateToSlotZero.replaceFirst("600054", "600154").fromHex()).isNotEmpty())
        val fixed = "3660008037600080366000735aaeb6053f3e94c9b9a09f33669435e7ef1beaed5af4"
        assertTrue(auditProxyCode(fixed.fromHex()).any { "DELEGATECALL" in it })
        assertTrue(auditProxyCode(delegateToSlotZero.replace("5af4", "5af1").fromHex()).any { "CALL" in it })
        assertEquals(listOf("no DELEGATECALL reachable"), auditProxyCode("600054".fromHex()))
        // An EIP-7702 designator is no proxy at all.
        assertTrue(auditProxyCode("ef01005aaeb6053f3e94c9b9a09f33669435e7ef1beaed".fromHex()).isNotEmpty())
    }

    @Test
    fun gnosisOneOneOneProxyDiffersFromMainnetsOnlyInItsMetadataHash() {
        val mainnet = proxyCode.getValue(oneOneOne)
        val gnosis = proxyCode.getValue(oneOneOneGnosis)
        // The bzzr1 metadata: `a265627a7a72315820` + 32 bytes + `64736f6c634300050e0032`.
        val metadata = mainnet.indexOf("a265627a7a72315820") + "a265627a7a72315820".length

        assertEquals(mainnet.length, gnosis.length)
        assertEquals(mainnet.substring(0, metadata), gnosis.substring(0, metadata))
        assertEquals(mainnet.substring(metadata + 64), gnosis.substring(metadata + 64))
    }

    @Test
    fun onlyFromVersionOneFiveAModuleGuardIsConsulted() {
        for (network in safeNetworks) {
            val singletons = SafeDeployments.of(network).singletons.values
            val everywhere = setOf("1.1.1", "1.2.0", "1.3.0", "1.4.1", "1.5.0")

            assertEquals(setOf("1.5.0"), singletons.filter { it.moduleGuard }.map { it.version }.toSet())
            assertEquals(
                when (network) {
                    Network.ETHEREUM -> everywhere + setOf("0.0.2", "0.1.0", "1.0.0", "1.1.0")
                    Network.GNOSIS -> everywhere + setOf("0.1.0", "1.0.0")
                    Network.SEPOLIA -> everywhere
                },
                singletons.map { it.version }.toSet(),
                network.displayName,
            )
        }
    }

    @Test
    fun anOldSingletonIsPinnedOnlyWhereItCarriesCode() {
        // Read 2026-11-07: which network has code at each address (SafeDeployments.kt's doc comment).
        val live = mapOf(
            "0xb6029ea3b2c51d09a50b53ca8012feeb05bda35a" to setOf(Network.ETHEREUM, Network.GNOSIS), // 1.0.0
            "0x8942595a2dc5181df0465af0d7be08c8f23c93af" to setOf(Network.ETHEREUM, Network.GNOSIS), // 0.1.0
            "0xac6072986e985aabe7804695ec2d8970cf7541a2" to setOf(Network.ETHEREUM), // 0.0.2
            "0xae32496491b53841efb51829d6f886387708f99b" to setOf(Network.ETHEREUM), // 1.1.0
            "0x2cb0ebc503de87cfd8f0eceed8197bf7850184ae" to setOf(Network.GNOSIS), // Circles' 1.1.1
            "0x16c08fd4d098a6d72da7196ad129d8b04425df91" to setOf(Network.GNOSIS), // Circles' 1.1.1, again
        )
        for ((address, networks) in live) {
            for (network in safeNetworks) {
                assertEquals(
                    network in networks,
                    Address.of(address) in SafeDeployments.of(network).singletons,
                    "$address on ${network.displayName}",
                )
            }
        }
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
        assertEquals(proxyCode.getValue(oneFourOne), runtime.toHex())
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

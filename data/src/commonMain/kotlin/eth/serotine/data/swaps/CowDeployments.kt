package eth.serotine.data.swaps

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowDeployment
import eth.serotine.domain.model.Network

/**
 * A CoW Protocol deployment as it is pinned: its [deployment], and the runtime code hash each of
 * its two contracts must have ([codeHashes], lower-case hex without `0x`), which the engine adapter
 * proves before the deployment is a venue (`VerifiedCow`).
 */
internal class PinnedCow(val deployment: CowDeployment, val codeHashes: Map<Address, String>) {
    init {
        require(codeHashes.keys == setOf(deployment.settlement, deployment.vaultRelayer)) {
            "a code hash for each pinned contract, and no other"
        }
    }
}

/**
 * The CoW Protocol deployments orders are signed for, per network (docs/swaps.md, *An order, step
 * by step*).
 *
 * The addresses are CoW's, the same on every network it runs on (docs.cow.fi, *Core contracts*,
 * and cowprotocol/contracts' `networks.json`), read 2026-10-22. The code hashes were read on
 * 2026-10-22 at block 11,969,176 with Foundry's `cast codehash` against two public Sepolia RPC
 * nodes, which agreed, a debugging source only (CLAUDE.md, *Data sources*): what it says is what
 * is expected, and the app proves each hash through the light client before quoting anything, so a
 * wrong pin makes CoW unavailable, never a wrong order. Checked the same day: the settlement's
 * code dispatches every selector the domain calls (`domainSeparator`, `filledAmount`,
 * `invalidateOrder`) and `settle`; its `domainSeparator()` is the one the domain computes for
 * Sepolia; its `vaultRelayer()` is the relayer pinned here. Neither contract is a proxy, so
 * neither can change its code; the settlement's immutables (its authenticator, vault, relayer
 * and domain separator) are in that code, so its hash pins them too.
 */
internal object CowDeployments {

    /** The deployment pinned on [network], or null when none is. */
    fun of(network: Network): PinnedCow? = when (network) {
        Network.ETHEREUM, Network.GNOSIS -> null
        Network.SEPOLIA -> SEPOLIA
    }

    private val SEPOLIA = PinnedCow(
        CowDeployment(
            settlement = Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41"),
            vaultRelayer = Address.of("0xc92e8bdf79f0507f65a392b0ab4667716bfe0110"),
        ),
        mapOf(
            Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41") to
                "9fbace363dc778e25fecb202c12981d916faea80c9aab8167aeeedcaed84df53",
            Address.of("0xc92e8bdf79f0507f65a392b0ab4667716bfe0110") to
                "500097799c1379a3728ed70b17de4132de2c07f6937b041c361deaade22b6a5e",
        ),
    )
}

package eth.serotine.domain.model

/**
 * The payment links (EIP-681) Send from another wallet hands to another wallet app on this device
 * (issue #255; owner ruling, 2026-11-05): a transfer of the network's currency or of an ERC-20
 * token, which the other wallet signs and broadcasts. Serotine signs nothing and receives nothing
 * back through the link.
 *
 * A link is written in the one form every wallet that reads them was found to take (#255's spike:
 * MetaMask and Rainbow parse them with `eth-url-parser`):
 * - the target in its EIP-55 form, and `@` the network's decimal chain id, always: a wallet falls
 *   back to Ethereum mainnet without one;
 * - the network's currency: `ethereum:<recipient>@<chain id>?value=<wei>`;
 * - a token: `ethereum:<token>@<chain id>/transfer?address=<recipient>&uint256=<units>`;
 * - amounts as whole decimal numbers, never with an exponent or a fraction;
 * - nothing else: no `gas` parameters, which wallets drop; no ENS name, which they do not
 *   resolve; no `pay-` prefix.
 */
object PaymentLinks {

    /**
     * The least amount of wei MetaMask cannot open a link for: from 10^21 (1,000 ETH) up, its parser
     * writes the amount with an exponent and then refuses it (#255's spike, `metamask-mobile@260bd3a5`,
     * `eth-url-parser` 1.0.4 with bn.js). Other wallets may still open such a link.
     */
    val METAMASK_MAX_WEI_EXCLUSIVE: UInt256 = UInt256.of("1000000000000000000000")

    /**
     * The link that asks a wallet to send [amount] (in wei, or in [asset]'s token units) of [asset]
     * to [to] on [network].
     */
    fun of(network: Network, asset: TransferAsset, to: Address, amount: UInt256): String = when (asset) {
        TransferAsset.Native -> "ethereum:${checksummed(to)}@${network.chainId}?value=${amount.toDecimal()}"
        is TransferAsset.Erc20Token ->
            "ethereum:${checksummed(asset.token.address)}@${network.chainId}/transfer" +
                "?address=${checksummed(to)}&uint256=${amount.toDecimal()}"
    }
}

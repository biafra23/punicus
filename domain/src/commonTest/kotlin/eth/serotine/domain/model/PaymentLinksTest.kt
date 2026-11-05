package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class PaymentLinksTest {

    // EIP-55's own test vector, and Circle's USDC on Sepolia (BuiltInTokens).
    private val recipient = Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")
    private val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc")

    @Test
    fun `the network's currency is a plain link with the value in wei and the chain id`() {
        val link = PaymentLinks.of(Network.SEPOLIA, TransferAsset.Native, recipient, UInt256.of(100_000_000_000_000L))
        assertEquals("ethereum:0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed@11155111?value=100000000000000", link)
    }

    @Test
    fun `a token is a call of its transfer with the recipient and the amount in units`() {
        val link = PaymentLinks.of(Network.SEPOLIA, TransferAsset.Erc20Token(usdc), recipient, UInt256.of(100_000L))
        assertEquals(
            "ethereum:0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238@11155111/transfer" +
                "?address=0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed&uint256=100000",
            link,
        )
    }

    @Test
    fun `an amount is a whole number however large, never with an exponent`() {
        val amount = PaymentLinks.METAMASK_MAX_WEI_EXCLUSIVE
        val link = PaymentLinks.of(Network.ETHEREUM, TransferAsset.Native, recipient, amount)
        assertEquals("ethereum:0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed@1?value=1000000000000000000000", link)
    }

    @Test
    fun `each network names its own chain id`() {
        val link = PaymentLinks.of(Network.GNOSIS, TransferAsset.Native, recipient, UInt256.of(1L))
        assertEquals("ethereum:0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed@100?value=1", link)
    }
}

package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.UInt256
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Acceptance test of finding S12 of the security audit of 2026-11-01: a proposal made here refuses a
 * transfer to the zero address and a token transfer to the token's own contract (both burn the
 * amount), but a request imported from another device with the same contents passes [unsignable]
 * and can be signed. The co-signer's check should refuse what the proposal refuses. Ignored while
 * the finding is open; remove the @Ignore with the fix.
 */
class SafeTxUnsignableBurnTest {

    private val safe = Address.of("0x" + "11".repeat(20))
    private val token = Address.of("0x" + "22".repeat(20))

    @Ignore // Audit 2026-11-01, S12: open.
    @Test
    fun aTransferToTheZeroAddressIsRefusedAsTheProposalRefusesIt() {
        val tx = SafeTx.call(Address.ZERO, UInt256.ONE, ByteArray(0), nonce = 0)
        assertNotNull(SigningRequest.of(Network.SEPOLIA, safe, tx).unsignable(), "a burn is refused, not signed")
    }

    @Ignore // Audit 2026-11-01, S12: open.
    @Test
    fun aTokenTransferToTheTokenItselfIsRefusedAsTheProposalRefusesIt() {
        val tx = SafeTx.call(token, UInt256.ZERO, Erc20.transferData(token, UInt256.ONE), nonce = 0)
        assertNotNull(SigningRequest.of(Network.SEPOLIA, safe, tx).unsignable(), "tokens sent to their contract are lost")
    }
}

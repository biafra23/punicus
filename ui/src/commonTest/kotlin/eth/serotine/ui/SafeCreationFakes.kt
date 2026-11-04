package eth.serotine.ui

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeCreation
import eth.serotine.domain.model.SafeRelease
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.SafeReleases
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TransactionInputs

/**
 * A made-up Safe release: its singleton, its factory and a two-byte "creation code". The address a
 * Safe of it gets is computed as the real one is ([SafeCreation.proxyAddress]); nothing here is a
 * deployment anyone made.
 */
internal val TEST_RELEASE = SafeRelease(
    version = "1.4.1",
    l2 = false,
    singleton = Address.of("0x" + "51".repeat(20)),
    proxyFactory = Address.of("0x" + "fa".repeat(20)),
    proxyCreationCode = byteArrayOf(0x60, 0x80.toByte()),
    fallbackHandler = Address.ZERO,
)

/** [SafeReleases] pinning [release] (null: none) on every network. */
internal class FakeReleases(var release: SafeRelease? = TEST_RELEASE) : SafeReleases {
    override fun forCreation(network: Network): SafeRelease? = release
}

/**
 * [TransactionInputs] as [inputs] answers them, but for a call to [release]'s factory: its run
 * returns the address the factory would deploy the proxy to, read from the call's own salt nonce
 * and initializer — what the real factory's `createProxyWithNonce` returns — unless [deployment]
 * says otherwise.
 */
internal class FactoryInputs(
    private val inputs: FakeTransactionInputs = FakeTransactionInputs(),
    private val release: SafeRelease = TEST_RELEASE,
) : TransactionInputs by inputs {
    var deployment: Simulation? = null

    override suspend fun simulate(
        network: Network,
        from: Address,
        to: Address,
        value: UInt256,
        data: ByteArray,
    ): Simulation {
        if (to != release.proxyFactory) return inputs.simulate(Network.SEPOLIA, from, to, value, data)
        deployment?.let { return it }
        // createProxyWithNonce(singleton, initializer, saltNonce): the selector, three head words, the
        // initializer's length and the initializer.
        val salt = UInt256.fromBigEndian(data.copyOfRange(4 + 64, 4 + 96))
        val length = UInt256.fromBigEndian(data.copyOfRange(4 + 96, 4 + 128)).toLongOrNull()!!.toInt()
        val initializer = data.copyOfRange(4 + 128, 4 + 128 + length)
        val address = SafeCreation.proxyAddress(release, initializer, salt)
        return Simulation.Succeeded("0".repeat(24) + address.hex.removePrefix("0x"), 100)
    }
}

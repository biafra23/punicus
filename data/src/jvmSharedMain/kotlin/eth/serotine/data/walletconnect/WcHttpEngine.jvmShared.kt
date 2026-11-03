package eth.serotine.data.walletconnect

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

/**
 * Ktor's own CIO engine, on Android and the desktop. One connection attempt per connect: the relay
 * client's own backoff decides when to try again ([WcRelayClient]).
 */
internal actual fun walletConnectHttpEngine(): HttpClientEngine = CIO.create {
    endpoint.connectAttempts = 1
}

package eth.serotine.data.walletconnect

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

/** NSURLSession's WebSocket task beneath Ktor's Darwin engine, on iOS. */
internal actual fun walletConnectHttpEngine(): HttpClientEngine = Darwin.create()

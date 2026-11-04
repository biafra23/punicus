package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network

// What both engine hosts (the JVM targets' MyotisHost and iOS's) say to the engine the same
// way, whichever API they speak it through.

/** The engine's name for this network, as `MyotisEngine.availableNetworks()` lists it. */
internal fun Network.engineName(): String = when (this) {
    Network.ETHEREUM -> "mainnet"
    Network.GNOSIS -> "gnosis"
    Network.SEPOLIA -> "sepolia"
}

/** The 20 bytes the engine's reads take for an address. */
internal fun Address.bytes(): ByteArray = hex.removePrefix("0x").fromHex()

/** The engine's words for a name only an off-chain gateway (ERC-3668) can answer. */
internal fun isOffchainNameError(message: String): Boolean =
    "offchain" in message.lowercase() || "ERC-3668" in message

package eth.serotine.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * The port for what the device's connection costs: whether the network it is on now is
 * metered (mobile data, a metered hotspot), the condition the heavy downloads wait on unless the
 * user allows them there ([MeteredDownloadsSetting]). :data implements it per platform. Never
 * throws.
 */
interface ConnectionMeter {
    /** The connection's cost now, re-emitted when it changes. */
    fun observe(): Flow<ConnectionCost>
}

/** What [ConnectionMeter.observe] emits. */
enum class ConnectionCost {
    /** Wi-Fi, Ethernet or another network the platform reports as not metered. */
    UNMETERED,

    /**
     * A network the platform reports as metered, or one whose cost cannot be read now (no network
     * at all, or the platform failed to answer): a read error never lets a heavy download run.
     */
    METERED,

    /**
     * A platform that never reports metering (the desktop JVM): its connection is taken as
     * unmetered, as Myotis's desktop host takes it, and what the user allows on a metered one
     * changes nothing there.
     */
    NOT_REPORTED,
}

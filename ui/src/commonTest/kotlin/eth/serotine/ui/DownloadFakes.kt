package eth.serotine.ui

import eth.serotine.domain.repository.ConnectionCost
import eth.serotine.domain.repository.ConnectionMeter
import eth.serotine.domain.repository.MeteredDownloadsChoice
import eth.serotine.domain.repository.MeteredDownloadsSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A [ConnectionMeter] whose [cost] the test sets. */
internal class FakeConnectionMeter(initial: ConnectionCost = ConnectionCost.UNMETERED) : ConnectionMeter {
    val cost = MutableStateFlow(initial)

    override fun observe(): Flow<ConnectionCost> = cost
}

/** [MeteredDownloadsSetting] in memory, as the port's contract says one behaves; [failSaves] makes every save fail. */
internal class MemoryMeteredDownloads : MeteredDownloadsSetting {
    val choice = MutableStateFlow<MeteredDownloadsChoice>(MeteredDownloadsChoice.Chosen(allowed = false))
    var failSaves = false

    override fun observe(): Flow<MeteredDownloadsChoice> = choice

    override suspend fun allow(allowed: Boolean): Boolean {
        if (failSaves || choice.value is MeteredDownloadsChoice.Unreadable) return false
        choice.value = MeteredDownloadsChoice.Chosen(allowed)
        return true
    }
}

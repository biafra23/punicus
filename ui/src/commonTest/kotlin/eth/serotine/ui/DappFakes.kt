package eth.serotine.ui

import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.DappApproval
import eth.serotine.domain.repository.DappConnections
import eth.serotine.domain.repository.DappPairing
import eth.serotine.domain.repository.DappProposalId
import eth.serotine.domain.repository.DappRelayLink
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.DappSessions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** [DappSessions] of a build without a project ID: off. */
internal object OffDappSessions : DappSessions {
    override fun observe(): Flow<DappConnections> = flowOf(DappConnections.Off)
    override suspend fun pair(uri: String): DappPairing = DappPairing.Off
    override suspend fun approve(proposal: DappProposalId, accounts: List<Address>): DappApproval = DappApproval.Off
    override suspend fun reject(proposal: DappProposalId) = false
    override suspend fun disconnect(session: DappSessionId) = false
    override suspend fun extend(session: DappSessionId) = false
    override suspend fun updateAccounts(session: DappSessionId, accounts: List<Address>) = false
}

/** [DappSessions] over [connections], a test sets; it records what it is asked and answers what the test says. */
internal class FakeDappSessions : DappSessions {
    val connections = MutableStateFlow<DappConnections>(DappConnections.On(DappRelayLink.CONNECTED, emptyList(), emptyList()))
    var pairing: DappPairing = DappPairing.Paired(waitingForRelay = false)
    var approval: (DappProposalId, List<Address>) -> DappApproval = { _, _ -> DappApproval.Approved(DappSessionId("t"), true) }
    var saves = true
    val paired = mutableListOf<String>()
    val approved = mutableListOf<Pair<DappProposalId, List<Address>>>()
    val rejected = mutableListOf<DappProposalId>()
    val disconnected = mutableListOf<DappSessionId>()
    val extended = mutableListOf<DappSessionId>()

    override fun observe(): Flow<DappConnections> = connections
    override suspend fun pair(uri: String): DappPairing = pairing.also { paired += uri }
    override suspend fun approve(proposal: DappProposalId, accounts: List<Address>): DappApproval =
        approval(proposal, accounts).also { approved += proposal to accounts }
    override suspend fun reject(proposal: DappProposalId) = saves.also { rejected += proposal }
    override suspend fun disconnect(session: DappSessionId) = saves.also { disconnected += session }
    override suspend fun extend(session: DappSessionId) = saves.also { extended += session }
    override suspend fun updateAccounts(session: DappSessionId, accounts: List<Address>) = saves
}

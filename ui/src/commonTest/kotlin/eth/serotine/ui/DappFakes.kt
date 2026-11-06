package eth.serotine.ui

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Signature
import eth.serotine.domain.repository.DappAnswer
import eth.serotine.domain.repository.DappApproval
import eth.serotine.domain.repository.DappConnections
import eth.serotine.domain.repository.DappPairing
import eth.serotine.domain.repository.DappProposalId
import eth.serotine.domain.repository.DappRelayLink
import eth.serotine.domain.repository.DappRequest
import eth.serotine.domain.repository.DappRequestError
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappRequests
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.DappSessions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** [DappSessions] of a build without a project ID: off. */
internal object OffDappSessions : DappSessions {
    override fun observe(): Flow<DappConnections> = flowOf(DappConnections.Off)
    override suspend fun pair(uri: String): DappPairing = DappPairing.Off
    override suspend fun approve(proposal: DappProposalId, accounts: List<AccountId>): DappApproval = DappApproval.Off
    override suspend fun reject(proposal: DappProposalId) = false
    override suspend fun disconnect(session: DappSessionId) = false
    override suspend fun extend(session: DappSessionId) = false
    override suspend fun updateAccounts(session: DappSessionId, accounts: List<AccountId>) = false
}

/** [DappSessions] over [connections], a test sets; it records what it is asked and answers what the test says. */
internal class FakeDappSessions : DappSessions {
    val connections = MutableStateFlow<DappConnections>(DappConnections.On(DappRelayLink.CONNECTED, emptyList(), emptyList()))
    var pairing: DappPairing = DappPairing.Paired(waitingForRelay = false)
    var approval: (DappProposalId, List<AccountId>) -> DappApproval = { _, _ -> DappApproval.Approved(DappSessionId("t"), true) }
    var saves = true
    val paired = mutableListOf<String>()
    val approved = mutableListOf<Pair<DappProposalId, List<AccountId>>>()
    val rejected = mutableListOf<DappProposalId>()
    val disconnected = mutableListOf<DappSessionId>()
    val extended = mutableListOf<DappSessionId>()

    override fun observe(): Flow<DappConnections> = connections
    override suspend fun pair(uri: String): DappPairing = pairing.also { paired += uri }
    override suspend fun approve(proposal: DappProposalId, accounts: List<AccountId>): DappApproval =
        approval(proposal, accounts).also { approved += proposal to accounts }
    override suspend fun reject(proposal: DappProposalId) = saves.also { rejected += proposal }
    override suspend fun disconnect(session: DappSessionId) = saves.also { disconnected += session }
    override suspend fun extend(session: DappSessionId) = saves.also { extended += session }
    override suspend fun updateAccounts(session: DappSessionId, accounts: List<AccountId>) = saves
}

/** [DappRequests] of a build without a project ID: none, and every answer says it is off. */
internal object OffDappRequests : DappRequests {
    override fun observe(): Flow<List<DappRequest>> = flowOf(emptyList())
    override suspend fun answerSignature(request: DappRequestId, signature: Signature) = DappAnswer.OFF
    override suspend fun answerNull(request: DappRequestId) = DappAnswer.OFF
    override suspend fun answerChainSwitch(request: DappRequestId, network: Network) = DappAnswer.OFF
    override suspend fun answerTransaction(request: DappRequestId, hash: String) = DappAnswer.OFF
    override suspend fun refuse(request: DappRequestId, why: DappRequestError) = DappAnswer.OFF
    override suspend fun recordSafeTx(request: DappRequestId, safeTx: Digest) = false
}

/** [DappRequests] over [waiting], a test sets; an answer takes the request away and is recorded. */
internal class FakeDappRequests : DappRequests {
    val waiting = MutableStateFlow<List<DappRequest>>(emptyList())
    var answer = DappAnswer.DELIVERED
    val signatures = mutableListOf<Pair<DappRequestId, Signature>>()
    val refusals = mutableListOf<Pair<DappRequestId, DappRequestError>>()

    override fun observe(): Flow<List<DappRequest>> = waiting
    private fun answered(request: DappRequestId): DappAnswer {
        if (answer == DappAnswer.DELIVERED || answer == DappAnswer.NOT_DELIVERED) {
            waiting.value = waiting.value.filterNot { it.id == request }
        }
        return answer
    }
    override suspend fun answerSignature(request: DappRequestId, signature: Signature): DappAnswer =
        answered(request).also { signatures += request to signature }
    override suspend fun answerNull(request: DappRequestId): DappAnswer = answered(request)
    val switches = mutableListOf<Pair<DappRequestId, Network>>()
    override suspend fun answerChainSwitch(request: DappRequestId, network: Network): DappAnswer =
        answered(request).also { switches += request to network }
    val hashes = mutableListOf<Pair<DappRequestId, String>>()
    override suspend fun answerTransaction(request: DappRequestId, hash: String): DappAnswer =
        answered(request).also { hashes += request to hash }
    override suspend fun refuse(request: DappRequestId, why: DappRequestError): DappAnswer =
        answered(request).also { refusals += request to why }
    var recordSaves = true
    override suspend fun recordSafeTx(request: DappRequestId, safeTx: Digest): Boolean {
        val found = waiting.value.firstOrNull { it.id == request } ?: return false
        if (!recordSaves || (found.safeTx != null && found.safeTx != safeTx)) return false
        waiting.value = waiting.value.map { if (it.id == request) it.copy(safeTx = safeTx) else it }
        return true
    }
}

package eth.serotine.data.walletconnect

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.repository.DappAnswer
import eth.serotine.domain.repository.DappAsk
import eth.serotine.domain.repository.DappApproval
import eth.serotine.domain.repository.DappConnections
import eth.serotine.domain.repository.DappMetadata
import eth.serotine.domain.repository.DappPairing
import eth.serotine.domain.repository.DappProposal
import eth.serotine.domain.repository.DappProposalId
import eth.serotine.domain.repository.DappRefusal
import eth.serotine.domain.repository.DappRelayLink
import eth.serotine.domain.repository.DappRequest
import eth.serotine.domain.repository.DappRequestError
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappRequests
import eth.serotine.domain.repository.DappSession
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.DappSessions
import eth.serotine.domain.repository.caip2
import eth.serotine.domain.repository.dappNetworkOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * [DappSessions], and its sessions' [requests], over WalletConnect v2 (docs/walletconnect.md), its state in the
 * file at [path]. The
 * host builds one, with this build's project ID for its build type ([projectIdFor]), and calls [run]
 * while dApp messages are to arrive: the relay connection lives as long as that call.
 *
 * Without a project ID it is switched off: [observe] says [DappConnections.Off], every action answers
 * that it is off, and [run] connects to nothing (ruling 2).
 */
class WalletConnectDapps internal constructor(
    path: String,
    projectId: String,
    connector: WcRelayConnector,
    dispatcher: CoroutineDispatcher,
    nowMillis: () -> Long,
) : DappSessions {

    /** dApp sessions in the file at [path], over the platform's WebSocket client, with [projectId] (none: off). */
    constructor(path: String, projectId: String) :
        this(path, projectId, KtorRelayConnector(walletConnectHttpEngine()), Dispatchers.IO, ::epochMillisNow)

    private val walletConnect: WalletConnect? = projectId.takeIf(::isProjectId)?.let {
        WalletConnect(
            store = WcStore(path, dispatcher),
            connector = connector,
            projectId = it,
            metadata = SEROTINE,
            nowMillis = nowMillis,
        )
    }

    /** Keeps the relay connection until cancelled; returns only by cancellation, connecting to nothing when off. */
    suspend fun run(): Nothing = walletConnect?.run() ?: awaitCancellation()

    override fun observe(): Flow<DappConnections> {
        val on = walletConnect ?: return flowOf(DappConnections.Off)
        return combine(on.wallet.observe(), on.state) { view, state ->
            DappConnections.On(
                link = when (state) {
                    WcRelayClient.State.CONNECTED -> DappRelayLink.CONNECTED
                    WcRelayClient.State.CONNECTING -> DappRelayLink.CONNECTING
                    WcRelayClient.State.DISCONNECTED -> DappRelayLink.DISCONNECTED
                },
                proposals = view.proposals.map(::proposal),
                sessions = view.sessions.map(::session),
            )
        }.distinctUntilChanged()
    }

    override suspend fun pair(uri: String): DappPairing {
        val wallet = walletConnect?.wallet ?: return DappPairing.Off
        return when (val pairing = wallet.pair(uri)) {
            is WcWallet.Pairing.Paired -> DappPairing.Paired(waitingForRelay = !pairing.subscribed)
            is WcWallet.Pairing.Unreadable -> when (val why = pairing.why) {
                is WcPairingUri.Parse.UnsupportedVersion -> DappPairing.UnsupportedVersion
                is WcPairingUri.Parse.UnsupportedParameter -> DappPairing.UnsupportedParameter(why.parameter)
                else -> DappPairing.Unreadable
            }
            WcWallet.Pairing.Expired -> DappPairing.Expired
            WcWallet.Pairing.NoProposal -> DappPairing.NoProposal
            WcWallet.Pairing.TopicInUse -> DappPairing.TopicInUse
            WcWallet.Pairing.NotSaved -> DappPairing.NotSaved
        }
    }

    override suspend fun approve(proposal: DappProposalId, accounts: List<AccountId>): DappApproval {
        val wallet = walletConnect?.wallet ?: return DappApproval.Off
        return when (val approval = wallet.approve(proposal.pairing, proposal.id, accounts.map(::caip10))) {
            is WcWallet.Approval.Approved -> DappApproval.Approved(DappSessionId(approval.topic), approval.delivered)
            WcWallet.Approval.NoSuchProposal -> DappApproval.NoSuchProposal
            WcWallet.Approval.Expired -> DappApproval.Expired
            is WcWallet.Approval.Refused -> DappApproval.Refused(refusal(approval.why))
            WcWallet.Approval.InvalidAccounts -> DappApproval.InvalidAccounts
            WcWallet.Approval.NotSaved -> DappApproval.NotSaved
        }
    }

    override suspend fun reject(proposal: DappProposalId): Boolean {
        val wallet = walletConnect?.wallet ?: return false
        // A proposal that asks for too much is told why, else that the user declined.
        val why = wallet.observe().first().proposals
            .find { it.pairingTopic == proposal.pairing && it.id == proposal.id }?.refusal
            ?: WcProtocol.Error.USER_REJECTED
        return wallet.reject(proposal.pairing, proposal.id, why)
    }

    override suspend fun disconnect(session: DappSessionId): Boolean =
        walletConnect?.wallet?.disconnect(session.topic) ?: false

    override suspend fun extend(session: DappSessionId): Boolean = walletConnect?.wallet?.extend(session.topic) ?: false

    override suspend fun updateAccounts(session: DappSessionId, accounts: List<AccountId>): Boolean =
        walletConnect?.wallet?.updateAccounts(session.topic, accounts.map(::caip10)) ?: false

    /** The requests dApps send over these sessions, and their answers. */
    val requests: DappRequests = object : DappRequests {

        /**
         * The requests waiting, decoded strictly ([WcRequests]); none while off. Each is decoded once per
         * collection: a request's parameters never change, and typed data is hashed in its decoding.
         */
        override fun observe(): Flow<List<DappRequest>> {
            val on = walletConnect ?: return flowOf(emptyList())
            return flow {
                val decoded = HashMap<Pair<String, Long>, DappAsk>()
                emitAll(on.wallet.observe().map { view -> requestsOf(view, decoded) })
            }.distinctUntilChanged()
        }

        private fun requestsOf(view: WcView, decoded: MutableMap<Pair<String, Long>, DappAsk>): List<DappRequest> {
            val sessions = view.sessions.associateBy { it.topic }
            decoded.keys.retainAll(view.requests.map { it.topic to it.id }.toSet())
            return view.requests.map { request ->
                val session = sessions[request.topic]
                DappRequest(
                    id = DappRequestId(DappSessionId(request.topic), request.id),
                    dapp = metadata(request.dapp),
                    sessionAccounts = session?.accounts.orEmpty().mapNotNull(::accountId),
                    chain = request.chainId,
                    ask = decoded.getOrPut(request.topic to request.id) {
                        WcRequests.decode(request.method, request.params, request.chainId)
                    },
                    expiresAtSeconds = request.expirySeconds,
                    safeTx = request.safeTx?.let(Digest::parse),
                    sessionNetworks = networksOf(session?.chains.orEmpty()),
                )
            }
        }

        override suspend fun answerSignature(request: DappRequestId, signature: Signature): DappAnswer {
            val wallet = walletConnect?.wallet ?: return DappAnswer.OFF
            val hex = JsonPrimitive(WcRequests.signatureHex(signature))
            return answer(wallet.respond(request.session.topic, request.id, hex))
        }

        override suspend fun answerTransaction(request: DappRequestId, hash: String): DappAnswer {
            val wallet = walletConnect?.wallet ?: return DappAnswer.OFF
            return answer(wallet.respond(request.session.topic, request.id, JsonPrimitive(hash)))
        }

        override suspend fun answerNull(request: DappRequestId): DappAnswer {
            val wallet = walletConnect?.wallet ?: return DappAnswer.OFF
            return answer(wallet.respond(request.session.topic, request.id, JsonNull))
        }

        override suspend fun answerChainSwitch(request: DappRequestId, network: Network): DappAnswer {
            val wallet = walletConnect?.wallet ?: return DappAnswer.OFF
            return answer(wallet.respondChainSwitch(request.session.topic, request.id, network.caip2))
        }

        override suspend fun refuse(request: DappRequestId, why: DappRequestError): DappAnswer {
            val wallet = walletConnect?.wallet ?: return DappAnswer.OFF
            return answer(wallet.respondError(request.session.topic, request.id, WcRequests.error(why)))
        }

        override suspend fun recordSafeTx(request: DappRequestId, safeTx: Digest): Boolean {
            val wallet = walletConnect?.wallet ?: return false
            return wallet.recordSafeTx(request.session.topic, request.id, "0x" + safeTx.hex)
        }

        private fun answer(answer: WcWallet.Answer): DappAnswer = when (answer) {
            WcWallet.Answer.DELIVERED -> DappAnswer.DELIVERED
            WcWallet.Answer.NOT_DELIVERED -> DappAnswer.NOT_DELIVERED
            WcWallet.Answer.NO_SUCH_REQUEST -> DappAnswer.NO_SUCH_REQUEST
            WcWallet.Answer.EXPIRED -> DappAnswer.EXPIRED
            WcWallet.Answer.NOT_SAVED -> DappAnswer.NOT_SAVED
        }
    }

    private fun proposal(view: WcProposalView) = DappProposal(
        id = DappProposalId(view.pairingTopic, view.id),
        dapp = metadata(view.dapp),
        requiredChains = view.requiredChains,
        optionalChains = view.optionalChains,
        methods = view.methods,
        refusal = view.refusal?.let { why ->
            // One error code for all three: a chain Punicus does not serve required, both kinds of funds
            // required, or no chain it serves asked for at all.
            when (view.chainRefusal) {
                WcProtocol.ChainRefusal.MIXED -> DappRefusal.MIXED_FUNDS
                WcProtocol.ChainRefusal.NONE -> DappRefusal.NO_NETWORK
                WcProtocol.ChainRefusal.UNSUPPORTED -> DappRefusal.OTHER_CHAIN
                null -> refusal(why)
            }
        },
        expiresAtSeconds = view.expirySeconds,
    )

    private fun session(view: WcSessionView) = DappSession(
        id = DappSessionId(view.topic),
        dapp = metadata(view.dapp),
        accounts = view.accounts.mapNotNull(::accountId),
        methods = view.methods,
        expiresAtSeconds = view.expirySeconds,
        acknowledged = view.acknowledged,
        networks = networksOf(view.chains),
        requiredNetworks = networksOf(view.requiredChains.toList()),
    )

    companion object {
        /**
         * The project ID this build carries for [debug] builds or release builds, or empty when it has
         * none. The host knows which it is. Ruling 2 keeps the two apart; for now both hold the same
         * ID (owner ruling, 2026-11-05: one free-tier project).
         */
        fun projectIdFor(debug: Boolean): String =
            if (debug) WALLETCONNECT_PROJECT_ID_DEBUG else WALLETCONNECT_PROJECT_ID_RELEASE

        /** How Punicus names itself to a dApp: no icon, so a dApp fetches nothing of ours. */
        internal val SEROTINE = WcMetadata(
            name = "Punicus",
            description = "A wallet that verifies what it shows with an embedded light client.",
            url = "https://github.com/biafra23/punicus",
        )

        internal fun isProjectId(text: String): Boolean =
            text.isNotEmpty() && text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

        private fun metadata(stated: WcMetadata) = DappMetadata(stated.name, stated.url, stated.description)

        /** [account] as CAIP-10, `eip155:1:0x…`, the address in lower case. */
        private fun caip10(account: AccountId): String = "${account.network.caip2}:${account.address.hex.lowercase()}"

        /** The account CAIP-10 [account] names, or null for one out of shape or on a chain Punicus does not serve. */
        private fun accountId(account: String): AccountId? {
            val (chain, address) = WcProtocol.accountOf(account) ?: return null
            return AccountId(dappNetworkOf(chain) ?: return null, Address.parse(address) ?: return null)
        }

        private fun networksOf(chains: List<String>): Set<Network> = chains.mapNotNull(::dappNetworkOf).toSet()

        private fun refusal(why: WcProtocol.Error): DappRefusal = when (why) {
            WcProtocol.Error.UNSUPPORTED_METHODS -> DappRefusal.METHOD
            WcProtocol.Error.UNSUPPORTED_EVENTS -> DappRefusal.EVENT
            WcProtocol.Error.UNSUPPORTED_NAMESPACE_KEY -> DappRefusal.NAMESPACE
            else -> DappRefusal.OTHER_CHAIN
        }
    }
}

@OptIn(ExperimentalTime::class)
private fun epochMillisNow(): Long = Clock.System.now().toEpochMilliseconds()

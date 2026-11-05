package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.Eip712
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TypedData
import eth.serotine.domain.model.TypedDataMeaning
import eth.serotine.domain.model.TypedField
import eth.serotine.domain.model.TypedValue
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.DappAnswer
import eth.serotine.domain.repository.DappAsk
import eth.serotine.domain.repository.DappMetadata
import eth.serotine.domain.repository.DappRequest
import eth.serotine.domain.repository.DappRequestError
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappRequests
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DappSigningTest {

    private fun account(last: Char, kind: AccountKind = AccountKind.EOA, custody: AccountCustody = AccountCustody.InKeyStore) =
        Account(Network.SEPOLIA, Address.of("0x" + "0".repeat(39) + last), kind, "Account $last", custody)

    private val held = account('1')
    private val watched = account('2', custody = AccountCustody.Watched)
    private val safe = account('3', kind = AccountKind.SAFE, custody = AccountCustody.Watched)
    private val accounts = ListAccountRepository(held, watched, safe)
    private val enabled = MutableStateFlow(setOf(Network.SEPOLIA))
    private val networks = object : NetworkSettings {
        override fun observe(): Flow<Set<Network>> = enabled
        override suspend fun setEnabled(network: Network, on: Boolean): Boolean = error("not used")
    }
    private val now = 1_793_700_000L
    private val dapp = DappMetadata("Test dApp", "https://app.example.org/connect", "")

    private class FakeRequests : DappRequests {
        val waiting = MutableStateFlow<List<DappRequest>>(emptyList())
        val answered = mutableListOf<Pair<DappRequestId, Any?>>()
        var answer = DappAnswer.DELIVERED
        override fun observe(): Flow<List<DappRequest>> = waiting
        private fun done(id: DappRequestId, value: Any?): DappAnswer {
            if (answer == DappAnswer.NOT_SAVED) return answer
            answered += id to value
            waiting.value = waiting.value.filterNot { it.id == id }
            return answer
        }
        override suspend fun answerSignature(request: DappRequestId, signature: Signature) = done(request, signature)
        override suspend fun answerNull(request: DappRequestId) = done(request, null)
        override suspend fun answerChainSwitch(request: DappRequestId, network: Network) = done(request, network)
        override suspend fun answerTransaction(request: DappRequestId, hash: String) = done(request, hash)
        override suspend fun refuse(request: DappRequestId, why: DappRequestError) = done(request, why)
        override suspend fun recordSafeTx(request: DappRequestId, safeTx: Digest) = error("not used")
    }

    private class FakeSigner : Signer {
        var answer: Signing = Signing.Signed(Signature("1".padStart(64, '0'), "2".padStart(64, '0'), 1))
        val asked = mutableListOf<Pair<Address, Digest>>()
        override suspend fun sign(account: Address, digest: Digest, unlock: Unlock): Signing {
            asked += account to digest
            return answer
        }
    }

    private val requests = FakeRequests()
    private val signer = FakeSigner()

    private fun signIn(
        address: Address = held.address,
        domain: String = "app.example.org",
        chainId: Long = 11_155_111,
        extra: String = "",
    ) = """
        $domain wants you to sign in with your Ethereum account:
        ${address.hex}

        Sign in to Test dApp.

        URI: https://$domain/login
        Version: 1
        Chain ID: $chainId
        Nonce: abcdefgh12
        Issued At: 2026-11-03T10:00:00Z
    """.trimIndent() + extra

    private fun request(
        id: Long,
        text: String = signIn(),
        signer: Address = held.address,
        chain: String = "eip155:11155111",
        sessionAccounts: List<Address> = listOf(held.address, watched.address, safe.address),
        sessionNetwork: Network = Network.SEPOLIA,
    ) = DappRequest(
        DappRequestId(DappSessionId("t"), id),
        dapp,
        sessionAccounts.map { eth.serotine.domain.model.AccountId(sessionNetwork, it) },
        chain,
        DappAsk.PersonalSign(signer, PersonalMessage.of(text.encodeToByteArray())!!),
        now + 300,
    )

    private fun review(request: DappRequest, nowSeconds: Long = now) =
        reviewDappMessage(request, listOf(held, watched, safe), enabled.value, nowSeconds)!!

    @Test
    fun aSignInForTheAccountOnSepoliaFromTheDappsOwnSiteMayBeSigned() {
        val review = review(request(1))
        assertNull(review.refusal)
        assertTrue(review.warnings.isEmpty())
        assertEquals(held, review.account)
        assertEquals("app.example.org", review.signIn?.domain)
    }

    @Test
    fun whatCannotBeSignedIsRefusedWithItsReason() {
        assertEquals(DappSignRefusal.OTHER_CHAIN, review(request(1, chain = "eip155:1")).refusal)
        assertEquals(
            DappSignRefusal.NOT_SESSION_ACCOUNT,
            review(request(1, sessionAccounts = listOf(watched.address))).refusal,
        )
        assertEquals(DappSignRefusal.NOT_IN_WALLET, review(request(1, signIn(Address.of("0x" + "0".repeat(39) + "9")), Address.of("0x" + "0".repeat(39) + "9"), sessionAccounts = listOf(Address.of("0x" + "0".repeat(39) + "9")))).refusal)
        assertEquals(DappSignRefusal.NO_KEY, review(request(1, signIn(watched.address), watched.address)).refusal)
        assertEquals(DappSignRefusal.SAFE, review(request(1, signIn(safe.address), safe.address)).refusal)
        assertEquals(DappSignRefusal.SIGN_IN_OTHER_ADDRESS, review(request(1, signIn(watched.address))).refusal)
        assertEquals(DappSignRefusal.SIGN_IN_OTHER_CHAIN, review(request(1, signIn(chainId = 1))).refusal)
        val expiring = request(1, signIn(extra = "\nExpiration Time: 2026-11-03T10:10:00Z"))
        assertNull(review(expiring, nowSeconds = 1_793_700_599).refusal)
        assertEquals(DappSignRefusal.SIGN_IN_EXPIRED, review(expiring, nowSeconds = 1_793_700_600).refusal)
        val later = request(1, signIn(extra = "\nNot Before: 2026-11-03T10:10:00Z"))
        assertEquals(DappSignRefusal.SIGN_IN_NOT_YET_VALID, review(later, nowSeconds = 1_793_700_599).refusal)
        assertNull(review(later, nowSeconds = 1_793_700_600).refusal)
        enabled.value = emptySet()
        assertEquals(DappSignRefusal.NETWORK_OFF, review(request(1)).refusal)
        assertEquals(DappSignRefusal.ACCOUNTS_UNREADABLE, reviewDappMessage(request(1), null, enabled.value, now)!!.refusal)
    }

    @Test
    fun aSignInForAnotherSiteOrAMessageThatIsNoneIsWarnedAbout() {
        assertEquals(setOf(DappSignWarning.DOMAIN_NOT_DAPP_URL), review(request(1, signIn(domain = "app.example.org.evil.test"))).warnings)
        assertEquals(setOf(DappSignWarning.NOT_A_SIGN_IN), review(request(1, "Hello, dApp")).warnings)
        assertNull(review(request(1, "Hello, dApp")).refusal)
    }

    @Test
    fun theSiteIsTheHostAndThePortOfTheDappsUrl() {
        val message = { domain: String, scheme: String? ->
            eth.serotine.domain.model.SignInMessage.parse(signIn(domain = domain).let { if (scheme != null) "$scheme://$it" else it })!!
        }
        assertTrue(sameSite(message("app.example.org", null), "https://APP.example.org"))
        assertTrue(sameSite(message("app.example.org:443", null), "https://app.example.org/"))
        assertTrue(sameSite(message("localhost:3000", "http"), "http://localhost:3000/x"))
        assertTrue(!sameSite(message("app.example.org:8443", null), "https://app.example.org"))
        assertTrue(!sameSite(message("app.example.org", null), "http://app.example.org"))
        assertTrue(!sameSite(message("example.org", null), "https://app.example.org"))
        assertTrue(!sameSite(message("app.example.org", null), "app.example.org"))
    }

    private fun permit(owner: Address = held.address, chainId: TypedValue? = TypedValue.Number("11155111")): DappRequest {
        val domainFields = listOfNotNull(
            TypedField("name", "string"),
            TypedField("chainId", "uint256").takeIf { chainId != null },
            TypedField("verifyingContract", "address"),
        )
        val data = TypedData(
            types = mapOf(
                "EIP712Domain" to domainFields,
                "Permit" to listOf(
                    TypedField("owner", "address"),
                    TypedField("spender", "address"),
                    TypedField("value", "uint256"),
                    TypedField("nonce", "uint256"),
                    TypedField("deadline", "uint256"),
                ),
            ),
            primaryType = "Permit",
            domain = buildMap {
                put("name", TypedValue.Text("Token"))
                chainId?.let { put("chainId", it) }
                put("verifyingContract", TypedValue.Text(token.address.hex))
            },
            message = mapOf(
                "owner" to TypedValue.Text(owner.hex),
                "spender" to TypedValue.Text(spender.hex),
                "value" to TypedValue.Number("1000"),
                "nonce" to TypedValue.Number("0"),
                "deadline" to TypedValue.Number("1793703600"),
            ),
        )
        return request(7).copy(ask = DappAsk.SignTypedData(held.address, Eip712.encode(data)!!))
    }

    private val token = Token(Address.of("0x" + "0".repeat(38) + "aa"), "TKN", "Token", 6, null)
    private val spender = Address.of("0x" + "0".repeat(38) + "bb")

    @Test
    fun typedDataOfAKnownStructIsDecodedWithItsTokenAndSignedOverItsEip712Digest() = runTest {
        val review = reviewDappMessage(permit(), listOf(held, watched, safe), enabled.value, now, listOf(token))!!
        assertNull(review.refusal)
        assertTrue(review.warnings.isEmpty())
        val content = review.content as DappSignContent.Typed
        assertEquals(TypedDataMeaning.Permit(token.address, held.address, spender, UInt256.of(1000), UInt256.of(1_793_703_600)), content.meaning)
        assertEquals(mapOf(token.address to token), content.tokens)
        assertEquals(content.typed.digest, review.digest)
        requests.waiting.value = listOf(permit())
        val sign = SignDappMessage(requests, accounts, networks, signer, clock = { now })
        assertEquals(DappMessageSigning.Signed(delivered = true), sign(permit().id, Unlock.WithBiometrics("x")))
        assertEquals(listOf(held.address to content.typed.digest), signer.asked)
    }

    @Test
    fun typedDataForAnotherChainOrOwnerIsRefusedAndWithoutAChainOrAKnownStructWarnedAbout() {
        fun review(request: DappRequest) = reviewDappMessage(request, listOf(held, watched, safe), enabled.value, now)!!
        assertEquals(DappSignRefusal.TYPED_OTHER_CHAIN, review(permit(chainId = TypedValue.Number("1"))).refusal)
        assertEquals(DappSignRefusal.PERMIT_OTHER_OWNER, review(permit(owner = watched.address)).refusal)
        assertEquals(setOf(DappSignWarning.NO_CHAIN_ID), review(permit(chainId = null)).warnings)
        assertNull(review(permit(chainId = null)).refusal)
        val unknown = (permit().ask as DappAsk.SignTypedData).typed.data.let { data ->
            data.copy(
                types = data.types - "Permit" + ("Note" to listOf(TypedField("text", "string"))),
                primaryType = "Note",
                message = mapOf("text" to TypedValue.Text("hello")),
            )
        }
        val note = review(permit().copy(ask = DappAsk.SignTypedData(held.address, Eip712.encode(unknown)!!)))
        assertEquals(setOf(DappSignWarning.NOT_DECODED), note.warnings)
        assertNull((note.content as DappSignContent.Typed).meaning)
        // More values than the screen shows: refused, never cut.
        val large = unknown.copy(
            types = unknown.types + ("Note" to listOf(TypedField("text", "string[]"))),
            message = mapOf("text" to TypedValue.Items(List(MAX_SHOWN_VALUES) { TypedValue.Text("x") })),
        )
        val tooLarge = review(permit().copy(ask = DappAsk.SignTypedData(held.address, Eip712.encode(large)!!)))
        assertEquals(DappSignRefusal.TYPED_TOO_LARGE, tooLarge.refusal)
        assertEquals(DappRequestError.LIMIT_EXCEEDED, tooLarge.refusal!!.error)
    }

    @Test
    fun signingSignsTheEip191DigestAndAnswersWithTheSignature() = runTest {
        val first = request(1)
        requests.waiting.value = listOf(first)
        val sign = SignDappMessage(requests, accounts, networks, signer, clock = { now })
        assertEquals(DappMessageSigning.Signed(delivered = true), sign(first.id, Unlock.WithBiometrics("Sign in")))
        val ask = first.ask as DappAsk.PersonalSign
        assertEquals(listOf(held.address to ask.message.digest()), signer.asked)
        assertEquals(ask.message.digest(), review(first).digest)
        assertEquals(first.id, requests.answered.single().first)
        assertTrue(requests.answered.single().second is Signature)
        assertEquals(DappMessageSigning.Gone, sign(first.id, Unlock.WithBiometrics("Sign in")))
    }

    @Test
    fun nothingIsSignedForARefusedOrExpiredRequestAndARefusalOfTheSignerIsSaid() = runTest {
        val sign = SignDappMessage(requests, accounts, networks, signer, clock = { now })
        requests.waiting.value = listOf(request(1, signIn(watched.address), watched.address), request(2).copy(expiresAtSeconds = now))
        assertEquals(DappMessageSigning.Refused(DappSignRefusal.NO_KEY), sign(request(1).id, Unlock.WithBiometrics("x")))
        assertEquals(DappMessageSigning.Expired, sign(request(2).id, Unlock.WithBiometrics("x")))
        assertTrue(signer.asked.isEmpty())
        requests.waiting.value = listOf(request(3))
        signer.answer = Signing.WrongPassword
        assertEquals(DappMessageSigning.NotSigned(Signing.WrongPassword), sign(request(3).id, Unlock.WithBiometrics("x")))
        assertTrue(requests.answered.isEmpty())
    }

    @Test
    fun aDeclinedRequestIsAnsweredAsRejectedAndARefusedOneWithItsError() = runTest {
        val refuse = RefuseDappRequest(requests, accounts, networks, clock = { now })
        requests.waiting.value = listOf(request(1), request(2, signIn(safe.address), safe.address))
        assertEquals(DappAnswer.DELIVERED, refuse(request(1).id))
        refuse(request(2).id)
        assertEquals(DappAnswer.NO_SUCH_REQUEST, refuse(request(2).id))
        assertEquals<List<Pair<DappRequestId, Any?>>>(
            listOf(request(1).id to DappRequestError.USER_REJECTED, request(2).id to DappRequestError.UNSUPPORTED_METHOD),
            requests.answered,
        )
    }

    @Test
    fun requestsThatNeedNoDecisionAreAnsweredAtOnce() = runTest {
        fun asking(id: Long, ask: DappAsk) = request(id).copy(ask = ask)
        requests.waiting.value = listOf(
            request(1),
            asking(2, DappAsk.SwitchChain(11_155_111)),
            asking(3, DappAsk.SwitchChain(1)),
            asking(4, DappAsk.NotAnsweredYet("eth_sendTransaction")),
            asking(5, DappAsk.Malformed("personal_sign")),
        )
        val answering = backgroundScope.launch { AnswerDappRequestsAtOnce(requests, MemorySentTransfers(), MemoryTransactionHistory(), networks)() }
        runCurrent()
        assertEquals<List<Pair<DappRequestId, Any?>>>(
            listOf(
                request(2).id to Network.SEPOLIA,
                request(3).id to DappRequestError.UNRECOGNIZED_CHAIN,
                request(4).id to DappRequestError.UNSUPPORTED_METHOD,
                request(5).id to DappRequestError.INVALID_PARAMS,
            ),
            requests.answered,
        )
        assertEquals(listOf(request(1)), requests.waiting.value)
        answering.cancel()
    }

    @Test
    fun aRealFundsSessionSwitchesBetweenItsNetworksAndNeverToTestnet() = runTest {
        enabled.value = setOf(Network.ETHEREUM, Network.GNOSIS, Network.SEPOLIA)
        val mainnet = listOf(
            eth.serotine.domain.model.AccountId(Network.ETHEREUM, held.address),
            eth.serotine.domain.model.AccountId(Network.GNOSIS, held.address),
        )
        fun asking(id: Long, chainId: Long) =
            request(id, chain = "eip155:1").copy(
                sessionAccounts = mainnet,
                sessionNetworks = setOf(Network.ETHEREUM, Network.GNOSIS),
                ask = DappAsk.SwitchChain(chainId),
            )
        requests.waiting.value = listOf(asking(1, 100), asking(2, 1), asking(3, 11_155_111), asking(4, 137))
        val answering = backgroundScope.launch { AnswerDappRequestsAtOnce(requests, MemorySentTransfers(), MemoryTransactionHistory(), networks)() }
        runCurrent()
        assertEquals<List<Pair<DappRequestId, Any?>>>(
            listOf(
                asking(1, 100).id to Network.GNOSIS,
                asking(2, 1).id to Network.ETHEREUM,
                asking(3, 11_155_111).id to DappRequestError.UNRECOGNIZED_CHAIN,
                asking(4, 137).id to DappRequestError.UNRECOGNIZED_CHAIN,
            ),
            requests.answered,
        )
        answering.cancel()
    }

    @Test
    fun aSwitchToAHeldNetworkThatIsOffOrHasNoAccountLeftIsRefusedNeverAnsweredAsMade() = runTest {
        val ethereum = eth.serotine.domain.model.AccountId(Network.ETHEREUM, held.address)
        fun asking(id: Long, chainId: Long, accounts: List<eth.serotine.domain.model.AccountId>) =
            request(id, chain = "eip155:1").copy(
                sessionAccounts = accounts,
                sessionNetworks = setOf(Network.ETHEREUM, Network.GNOSIS),
                ask = DappAsk.SwitchChain(chainId),
            )
        val withGnosis = listOf(ethereum, eth.serotine.domain.model.AccountId(Network.GNOSIS, held.address))
        // Gnosis Chain off: held by the session, but nothing there could be served.
        enabled.value = setOf(Network.ETHEREUM)
        // Its Gnosis Chain account removed: the session still holds the chain, with no account there.
        requests.waiting.value = listOf(asking(1, 100, withGnosis), asking(2, 100, listOf(ethereum)), asking(3, 1, withGnosis))
        val answering = backgroundScope.launch {
            AnswerDappRequestsAtOnce(requests, MemorySentTransfers(), MemoryTransactionHistory(), networks)()
        }
        runCurrent()
        assertEquals<List<Pair<DappRequestId, Any?>>>(
            listOf(
                asking(1, 100, withGnosis).id to DappRequestError.UNRECOGNIZED_CHAIN,
                asking(2, 100, listOf(ethereum)).id to DappRequestError.UNRECOGNIZED_CHAIN,
                asking(3, 1, withGnosis).id to Network.ETHEREUM,
            ),
            requests.answered,
        )
        answering.cancel()
    }

    @Test
    fun aRequestIsReviewedOnItsOwnNetworkAgainstTheSessionsAccountsThere() {
        val onEthereum = Account(Network.ETHEREUM, held.address, AccountKind.EOA, "Main", AccountCustody.InKeyStore)
        val wallet = listOf(held, onEthereum)
        val both = setOf(Network.ETHEREUM, Network.SEPOLIA)
        fun reviewed(request: DappRequest) = reviewDappMessage(request, wallet, both, now)!!
        val asked = request(1, signIn(chainId = 1), chain = "eip155:1", sessionNetwork = Network.ETHEREUM)
        val review = reviewed(asked)
        assertNull(review.refusal)
        assertEquals(onEthereum, review.account, "the account on the request's network, not its Sepolia twin")
        // A sign-in for Sepolia, asked on Ethereum, names another chain than the account's.
        assertEquals(
            DappSignRefusal.SIGN_IN_OTHER_CHAIN,
            reviewed(request(1, chain = "eip155:1", sessionNetwork = Network.ETHEREUM)).refusal,
        )
        // A chain its session does not hold is refused, as is an account the session has on another network.
        val onGnosis = request(1, signIn(chainId = 100), chain = "eip155:100", sessionNetwork = Network.ETHEREUM)
        assertEquals(DappSignRefusal.OTHER_CHAIN, reviewed(onGnosis).refusal)
        val gnosisOnly = asked.copy(
            sessionAccounts = listOf(eth.serotine.domain.model.AccountId(Network.GNOSIS, held.address)),
            sessionNetworks = setOf(Network.ETHEREUM, Network.GNOSIS),
        )
        assertEquals(DappSignRefusal.NOT_SESSION_ACCOUNT, reviewed(gnosisOnly).refusal)
        assertEquals(DappSignRefusal.NETWORK_OFF, reviewDappMessage(asked, wallet, setOf(Network.SEPOLIA), now)!!.refusal)
    }

    @Test
    fun anAnswerThatCouldNotBeSavedIsTriedAgainUntilItIs() = runTest {
        requests.answer = DappAnswer.NOT_SAVED
        requests.waiting.value = listOf(request(1).copy(ask = DappAsk.NotAnsweredYet("eth_sendTransaction")))
        val answering = backgroundScope.launch { AnswerDappRequestsAtOnce(requests, MemorySentTransfers(), MemoryTransactionHistory(), networks, retrySeconds = 30)() }
        runCurrent()
        assertTrue(requests.answered.isEmpty())
        requests.answer = DappAnswer.DELIVERED
        testScheduler.advanceTimeBy(30_001)
        runCurrent()
        assertEquals<List<Pair<DappRequestId, Any?>>>(
            listOf(request(1).id to DappRequestError.UNSUPPORTED_METHOD),
            requests.answered,
        )
        assertTrue(requests.waiting.value.isEmpty())
        answering.cancel()
    }

    @Test
    fun theReviewsListOnlyMessagesAndFollowTheClock() = runTest {
        var clock = 1_793_700_000L
        requests.waiting.value = listOf(
            request(1, signIn(extra = "\nExpiration Time: 2026-11-03T10:10:00Z")),
            request(2).copy(ask = DappAsk.SwitchChain(1)),
        )
        val observe = ObserveDappMessages(requests, accounts, networks, ListCatalog(), clock = { clock }, tickSeconds = 15)
        var latest: List<DappMessageReview> = emptyList()
        backgroundScope.launch { observe().collect { latest = it } }
        runCurrent()
        assertNull(latest.single().refusal)
        clock = 1_793_700_600L
        testScheduler.advanceTimeBy(15_001)
        runCurrent()
        assertEquals(DappSignRefusal.SIGN_IN_EXPIRED, latest.single().refusal)
        assertEquals(1, observe().first().size)
    }
}

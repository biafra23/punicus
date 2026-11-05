package eth.serotine.ui.dapprequest

import eth.serotine.domain.model.Account
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.domain.model.TypedValue
import eth.serotine.domain.model.TypedField
import eth.serotine.domain.model.TypedData
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.Eip712
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.BiometricUnlock
import eth.serotine.domain.repository.DappAnswer
import eth.serotine.domain.repository.DappAsk
import eth.serotine.domain.repository.DappMetadata
import eth.serotine.domain.repository.DappRequest
import eth.serotine.domain.repository.DappRequestError
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyDerivation
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveDappMessages
import eth.serotine.domain.usecase.RefuseDappRequest
import eth.serotine.domain.usecase.SignDappMessage
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeDappRequests
import eth.serotine.ui.FakeSigner
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.ui.components.Eip55
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ViewModel of a dApp's request to sign a message (screen D4). */
@OptIn(ExperimentalCoroutinesApi::class)
class DappRequestViewModelTest {

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private val now = 1_793_700_000L
    private var clock = now
    private val ticks = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val held = Account(
        Network.SEPOLIA,
        Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"),
        AccountKind.EOA,
        "Main",
        AccountCustody.InKeyStore,
    )
    private val accounts = FakeAccounts(held)
    private val networks = MemoryNetworkSettings(setOf(Network.SEPOLIA))
    private val requests = FakeDappRequests()
    private val signer = FakeSigner()
    private var biometrics: BiometricUnlock = BiometricUnlock.NotOnThisDevice
    private val keys = object : KeyStore {
        override suspend fun describe() = KeyStoreDescription.Ready(true, KeyProtection.HARDWARE, biometrics)
        override suspend fun newMnemonic(): NewMnemonic = error("not used by these tests")
        override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport = error("not used")
        override suspend fun enableBiometrics(password: WalletPassword, confirmation: String): BiometricChange =
            error("not used by these tests")
        override suspend fun deriveAccount(from: Address, password: WalletPassword, held: Set<Address>) =
            KeyDerivation.Unavailable
        override suspend fun disableBiometrics(): BiometricChange = error("not used by these tests")
        override suspend fun delete(address: Address): KeyDeletion = error("not used by these tests")
    }

    private val signIn = """
        app.example.org wants you to sign in with your Ethereum account:
        ${Eip55.format(held.address)}

        Sign in to Example dApp.

        URI: https://app.example.org/login
        Version: 1
        Chain ID: 11155111
        Nonce: abcdefgh12
        Issued At: 2026-11-03T10:00:00Z
    """.trimIndent()

    private fun request(id: Long, text: String = signIn, url: String = "https://app.example.org") = DappRequest(
        DappRequestId(DappSessionId("t"), id),
        DappMetadata("Example dApp", url, ""),
        listOf(held.id),
        "eip155:11155111",
        DappAsk.PersonalSign(held.address, PersonalMessage.of(text.encodeToByteArray())!!),
        now + 300,
    )

    private val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USD Coin", 6, null)
    private val tokens = object : TokenCatalog {
        override fun observeTokens(network: Network): Flow<List<Token>> = flowOf(listOf(usdc))
    }

    private fun permit(id: Long): DappRequest {
        val data = TypedData(
            types = mapOf(
                "EIP712Domain" to listOf(
                    TypedField("name", "string"),
                    TypedField("chainId", "uint256"),
                    TypedField("verifyingContract", "address"),
                ),
                "Permit" to listOf(
                    TypedField("owner", "address"),
                    TypedField("spender", "address"),
                    TypedField("value", "uint256"),
                    TypedField("nonce", "uint256"),
                    TypedField("deadline", "uint256"),
                ),
            ),
            primaryType = "Permit",
            domain = mapOf(
                "name" to TypedValue.Text("USD Coin"),
                "chainId" to TypedValue.Number("11155111"),
                "verifyingContract" to TypedValue.Text(usdc.address.hex),
            ),
            message = mapOf(
                "owner" to TypedValue.Text(held.address.hex),
                "spender" to TypedValue.Text("0x3fc91a3afd70395cd496c647d5a6cc9d4b2b7fad"),
                "value" to TypedValue.Text("250000000"),
                "nonce" to TypedValue.Number("0"),
                "deadline" to TypedValue.Number("1793703600"),
            ),
        )
        return request(id).copy(ask = DappAsk.SignTypedData(held.address, Eip712.encode(data)!!))
    }

    @Test
    fun aRequestOnEthereumNamesItsNetworkAndIsSignedForItsAccountThere() = runTest {
        val onEthereum = Account(Network.ETHEREUM, held.address, AccountKind.EOA, "Savings", AccountCustody.InKeyStore)
        accounts.accounts.value = listOf(held, onEthereum)
        networks.setEnabled(Network.ETHEREUM, true)
        val text = signIn.replace("Chain ID: 11155111", "Chain ID: 1")
        requests.waiting.value = listOf(
            request(1, text).copy(
                chain = "eip155:1",
                sessionAccounts = listOf(onEthereum.id),
                sessionNetworks = setOf(Network.ETHEREUM, Network.GNOSIS),
            ),
        )
        val vm = viewModel()
        val state = vm.uiState.value
        assertEquals("On Ethereum, real funds", state.network)
        assertEquals("Savings", state.accountName, "the account on the request's network, not its Sepolia twin")
        assertNull(state.refusal)
    }

    private fun TestScope.viewModel(id: Long = 1): DappRequestViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val observe = ObserveDappMessages(requests, accounts, networks, tokens, clock = { now })
        val vm = DappRequestViewModel(
            "t/$id",
            observe,
            SignDappMessage(requests, accounts, networks, signer, clock = { now }),
            RefuseDappRequest(requests, accounts, networks, clock = { now }),
            DescribeKeyStore(keys),
            clock = { clock },
            formatTime = { "at $it" },
            ticks = ticks.map { },
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun aSignInIsShownFieldByFieldAndSignedWithThePassword() = runTest {
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        val state = vm.uiState.value
        assertEquals("Sign-in request", state.title)
        assertEquals("Sign in", state.signLabel)
        assertEquals("Main", state.accountName)
        assertEquals(Eip55.format(held.address), state.accountAddress)
        assertEquals(
            listOf("Site", "Account", "Statement", "URI", "Network", "Nonce", "Issued"),
            state.signIn.map { it.label },
        )
        assertEquals("Sepolia (11155111)", state.signIn.first { it.label == "Network" }.value)
        assertEquals("at 1793700000", state.signIn.first { it.label == "Issued" }.value)
        assertTrue(state.warnings.isEmpty())
        assertNull(state.refusal)
        assertEquals("The dApp waits for an answer for 5 more minutes.", state.waits)

        // Nothing is signed without the password.
        vm.sign()
        assertTrue(signer.asked.isEmpty())
        vm.onPasswordChange("correct horse")
        vm.sign()
        runCurrent()
        assertEquals(listOf("correct horse"), signer.passwords)
        assertEquals(request(1).id, requests.signatures.single().first)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun howLongTheDappWaitsIsSaidAgainAsTimePasses() = runTest {
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        assertEquals("The dApp waits for an answer for 5 more minutes.", vm.uiState.value.waits)
        clock = now + 250
        ticks.value = 1
        runCurrent()
        assertEquals("The dApp waits for an answer for about a minute more.", vm.uiState.value.waits)
    }

    @Test
    fun aPermitIsShownAsTheApprovalItIsWithItsDomainAndEveryField() = runTest {
        requests.waiting.value = listOf(permit(3))
        val vm = viewModel(3)
        val state = vm.uiState.value
        assertTrue(state.typed)
        assertEquals("Approval request", state.title)
        assertEquals("Sign", state.signLabel)
        assertTrue(state.meaning!!.startsWith("Lets 0x3fC9…7FAD take up to 250 USDC from this account, until at 1793703600."))
        assertEquals(
            listOf(
                SignInField("Token", "USDC · ${Eip55.format(usdc.address)}"),
                SignInField("Spender", "0x3fC91A3afd70395Cd496C647d5a6CC9D4B2b7FAD"),
                SignInField("Amount", "250 USDC"),
                SignInField("Until", "at 1793703600"),
            ),
            state.meaningFields,
        )
        assertEquals(listOf("Name", "Network", "Contract"), state.domainFields.map { it.label })
        assertEquals("Sepolia (11155111)", state.domainFields[1].value)
        assertEquals(listOf("owner", "spender", "value", "nonce", "deadline"), state.typedRows.map { it.label })
        assertEquals("250000000", state.typedRows[2].value)
        assertTrue(state.warnings.isEmpty())
        vm.onPasswordChange("correct horse")
        vm.sign()
        runCurrent()
        assertEquals(permit(3).id, requests.signatures.single().first)
    }

    @Test
    fun aWrongPasswordSaysSoAndTheFieldIsEmptied() = runTest {
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        signer.answer = Signing.WrongPassword
        vm.onPasswordChange("wrong")
        vm.sign()
        runCurrent()
        val state = vm.uiState.value
        assertEquals("That is not the wallet password. Nothing was signed.", state.unlock.error)
        assertEquals("", state.unlock.password.text)
        assertFalse(state.done)
        assertTrue(requests.signatures.isEmpty())
    }

    @Test
    fun aMessageThatIsNoSignInIsWarnedAboutAndBytesThatAreNoTextAreShownAsHex() = runTest {
        requests.waiting.value = listOf(
            request(1, "Hello, dApp"),
            request(2).copy(ask = DappAsk.PersonalSign(held.address, PersonalMessage.ofHex("deadbeef")!!)),
        )
        val text = viewModel(1).uiState.value
        assertEquals("Message to sign", text.title)
        assertEquals("Sign", text.signLabel)
        assertEquals("Hello, dApp", text.messageText)
        assertNull(text.messageHex)
        assertEquals("11 bytes", text.messageSize)
        assertEquals(1, text.warnings.size)
        val hex = viewModel(2).uiState.value
        assertNull(hex.messageText)
        assertEquals("deadbeef", hex.messageHex)
    }

    @Test
    fun aSignInForAnotherSiteIsWarnedAbout() = runTest {
        requests.waiting.value = listOf(request(1, url = "https://other.example.net"))
        assertEquals(
            listOf(DappRequestViewModel.warningLine(eth.serotine.domain.usecase.DappSignWarning.DOMAIN_NOT_DAPP_URL)),
            viewModel().uiState.value.warnings,
        )
    }

    @Test
    fun oneThatCannotBeSignedSaysWhyOnSignAndIsRefusedWithItsError() = runTest {
        requests.waiting.value = listOf(request(1, signIn.replace("Chain ID: 11155111", "Chain ID: 1")))
        val vm = viewModel()
        assertTrue(vm.uiState.value.signUnavailable)
        vm.onPasswordChange("correct horse")
        vm.sign()
        assertEquals("Cannot be signed", vm.uiState.value.explaining?.title)
        assertTrue(signer.asked.isEmpty())
        vm.dismissExplanation()
        vm.refuse()
        runCurrent()
        assertEquals(listOf(request(1).id to DappRequestError.INVALID_PARAMS), requests.refusals)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun aDeclinedRequestIsAnsweredAsRejected() = runTest {
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        vm.refuse()
        runCurrent()
        assertEquals(listOf(request(1).id to DappRequestError.USER_REJECTED), requests.refusals)
    }

    @Test
    fun anAnswerThatDidNotTakeSaysWhyAndARequestNoLongerWaitingEndsTheScreen() = runTest {
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        requests.answer = DappAnswer.NOT_SAVED
        vm.refuse()
        runCurrent()
        assertEquals(DappRequestViewModel.NOT_SAVED, vm.uiState.value.notice)
        requests.answer = DappAnswer.EXPIRED
        vm.onPasswordChange("correct horse")
        vm.sign()
        runCurrent()
        assertEquals(DappRequestViewModel.EXPIRED, vm.uiState.value.notice)
        requests.waiting.value = emptyList()
        runCurrent()
        assertTrue(vm.uiState.value.ended)
        assertFalse(vm.uiState.value.gone)
        assertTrue(viewModel(9).uiState.value.gone)
    }

    @Test
    fun withBiometricsOnAFingerprintSignsAndTheHiddenScreenDropsThePassword() = runTest {
        biometrics = BiometricUnlock.On
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        assertTrue(vm.uiState.value.unlock.biometrics)
        vm.onPasswordChange("typed")
        vm.onHidden()
        assertEquals("", vm.uiState.value.unlock.password.text)
        vm.signWithBiometrics()
        runCurrent()
        val unlock = signer.asked.single().second as Unlock.WithBiometrics
        assertEquals("Sign in to a dApp with ${Eip55.format(held.address)}", unlock.reason)
        assertTrue(vm.uiState.value.done)
    }
}

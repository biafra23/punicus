package eth.serotine.ui.send

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.BiometricUnlock
import eth.serotine.domain.repository.BiometricUnusable
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyDerivation
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.ObserveUsdPrice
import eth.serotine.domain.usecase.QuoteTransfer
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.SendTransfer
import eth.serotine.domain.usecase.WarmFeeHistory
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeBalances
import eth.serotine.ui.FakeBroadcaster
import eth.serotine.ui.FakeCowChain
import eth.serotine.ui.FakeFeeds
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.FakeNames
import eth.serotine.ui.FakeReceipts
import eth.serotine.ui.FakeSigner
import eth.serotine.ui.FakeTokenBalances
import eth.serotine.ui.FakeTokenCatalog
import eth.serotine.ui.FakeTransactionInputs
import eth.serotine.ui.MemoryCurrencySetting
import eth.serotine.ui.MemoryCowOrders
import eth.serotine.ui.MemorySentTransfers
import eth.serotine.ui.MemoryUserTokens
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.RecipientItem
import eth.serotine.ui.components.SYNC_RETRY_MS
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.TransferForm
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** [SendViewModel] over fake ports, through the real use cases. */
@OptIn(ExperimentalCoroutinesApi::class)
class SendViewModelTest {

    private val daily = Account(
        Network.SEPOLIA,
        Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )

    // EIP-55's own example: 0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359.
    private val savings = Account(
        Network.SEPOLIA,
        Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"),
        AccountKind.EOA,
        "Savings",
        AccountCustody.Watched,
    )
    private val friend = Address.of("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb")
    private val usdc =
        Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc", PriceId("USDC"))

    private val accounts = FakeAccounts(daily, savings)
    private val balances = FakeBalances()
    private val tokenBalances = FakeTokenBalances()
    private val feeds = FakeFeeds()
    private val inputs = FakeTransactionInputs()
    private val names = FakeNames()
    private val signer = FakeSigner()
    private val broadcaster = FakeBroadcaster()
    private val sent = MemorySentTransfers()
    private val cowOrders = MemoryCowOrders()
    private val light = object : LightClient {
        override val datesFinalizedBlocks = true
        override fun observeStatus(network: Network): Flow<LightClientStatus> = flowOf(LightClientStatus.Starting)
    }
    private var biometrics: BiometricUnlock = BiometricUnlock.NotOnThisDevice
    private val keys = object : KeyStore {
        override suspend fun describe() = KeyStoreDescription.Ready(true, KeyProtection.HARDWARE, biometrics)

        override suspend fun newMnemonic(): NewMnemonic = error("not used by these tests")

        override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport =
            error("not used by these tests")

        override suspend fun enableBiometrics(password: WalletPassword, confirmation: String): BiometricChange =
            error("not used by these tests")

        override suspend fun deriveAccount(from: Address, password: WalletPassword, held: Set<Address>) =
            KeyDerivation.Unavailable

        override suspend fun disableBiometrics(): BiometricChange = error("not used by these tests")

        override suspend fun delete(address: Address): KeyDeletion = error("not used by these tests")
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private val clock = TestTimeSource()

    /** Now, in unix seconds, as the compose step dates a balance's age against it. */
    private var wallClock = 1_800_000_000L
    private val receipts = FakeReceipts()

    private fun TestScope.viewModel(from: Address = daily.address, asset: Address? = null): SendViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val currency = MemoryCurrencySetting()
        val viewModel = SendViewModel(
            AccountId(Network.SEPOLIA, from),
            asset,
            ObserveAccounts(accounts),
            ObserveAccountValue(
                ObserveNativeBalance(balances),
                ObserveTokenHoldings(
                    ObserveKnownTokens(FakeTokenCatalog(usdc), MemoryUserTokens(), FakeMetadata()),
                    tokenBalances,
                ),
                ObserveUsdPrice(feeds),
                ObserveDisplayCurrencies(feeds, currency, accounts),
            ),
            DescribeKeyStore(keys),
            ResolveRecipient(names),
            QuoteTransfer(inputs, balances, tokenBalances, sent, cowOrders, FakeCowChain(), light, Network.SEPOLIA),
            SendTransfer(signer, broadcaster, sent) { 1_800_000_000 },
            RebroadcastTransfer(broadcaster, sent, receipts),
            WarmFeeHistory(inputs),
            clock,
            now = { wallClock },
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private val SendViewModel.compose get() = assertIs<SendUiState.Compose>(uiState.value).form
    private val SendViewModel.review get() = assertIs<SendUiState.Review>(uiState.value)

    private fun holding(eth: String = "1500000000000000000", usdcUnits: String = "250000000") {
        balances.of(daily.address).value = BalanceReading.Verified(Wei.of(eth), 9_412_118)
        tokenBalances.of(daily.address, usdc.address).value =
            TokenBalanceReading.Verified(TokenUnits.of(usdcUnits), 9_412_117)
    }

    /** Composes 0.1 ETH to Savings, picked from the wallet's accounts, and asks for the review. */
    private fun TestScope.reviewed(): SendViewModel {
        holding()
        val viewModel = viewModel()
        viewModel.onPickAccount(viewModel.compose.accounts.single().address)
        viewModel.onAmountChange("0.1")
        viewModel.review()
        runCurrent()
        return viewModel
    }

    @Test
    fun theFeeHistoryIsAskedForAsTheComposeStepOpensSoThatItIsReadyByTheReview() = runTest {
        holding()
        viewModel()
        runCurrent()

        // The light client builds its fee history on a first request: asked now, warm by Review.
        assertEquals(1, inputs.feeReads)
        assertEquals(0, inputs.estimated.size, "nothing quoted")
    }

    @Test
    fun eachBalanceSaysHowLongAgoItWasProvedAndIsDatedAnew() = runTest {
        balances.of(daily.address).value =
            BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118, provenAt = wallClock - 120)
        val viewModel = viewModel()
        runCurrent()
        assertEquals("updated 2 min ago", viewModel.compose.asset?.age)

        wallClock += 60
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals("updated 3 min ago", viewModel.compose.asset?.age, "the age is dated anew every second")
    }

    @Test
    fun composeShowsTheAccountItsHeldAssetsAndTheOtherAccounts() = runTest {
        holding()
        val viewModel = viewModel()
        runCurrent()

        val state = viewModel.compose
        assertEquals(SendFrom("Daily", "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2"), state.from)
        assertEquals("Sepolia", state.network)
        assertEquals(
            listOf(
                AssetChoice("ETH", "Ether", "eth", "1.5 ETH", verifiedAt = "block 9,412,118"),
                AssetChoice(
                    "USDC",
                    "USDC",
                    "usdc",
                    "250 USDC",
                    contract = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238",
                    verifiedAt = "block 9,412,117",
                ),
            ),
            state.assets,
        )
        assertEquals("ETH", state.asset?.symbol, "the network's currency, unless a token was asked for")
        assertEquals(listOf(AccountChoice("Savings", "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359")), state.accounts)
        assertEquals(FeeLevel.NORMAL, assertIs<SendUiState.Compose>(viewModel.uiState.value).fee)
        assertFalse(state.canReview)
    }

    @Test
    fun theTokenChosenInTheAssetMenuIsPreselectedAndAZeroBalanceTokenIsNotOffered() = runTest {
        holding(usdcUnits = "0")
        val viewModel = viewModel(asset = usdc.address)
        runCurrent()
        assertEquals("USDC", viewModel.compose.asset?.symbol, "asked for, so shown whatever its balance")

        viewModel.onAssetChange(null)
        assertEquals(listOf("ETH"), viewModel.compose.assets.map { it.symbol }, "none held, none offered")
    }

    @Test
    fun aTypedAddressIsCheckedAtTheInputAndShownChecksummed() = runTest {
        holding()
        val viewModel = viewModel()

        viewModel.onToChange("0xfB69160")
        assertNull(viewModel.compose.toNote, "no verdict while an address is still being typed")
        assertFalse(viewModel.compose.toError)

        viewModel.onToChange("0xfb6916095CA1df60bB79Ce92cE3Ea74c37c5d359")
        assertTrue(viewModel.compose.toError)
        assertEquals(
            "The capitals do not match this address's checksum: check it for a typo.",
            viewModel.compose.toNote,
        )
        assertNull(viewModel.compose.recipient)

        viewModel.onToChange("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb")
        assertEquals(RecipientItem("0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"), viewModel.compose.recipient)
        assertTrue(names.asked.isEmpty(), "an address is no name to resolve")

        viewModel.onToChange("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb00")
        assertEquals("Not an address: an address is 0x and 40 hexadecimal digits.", viewModel.compose.toNote)
    }

    @Test
    fun aNameIsResolvedOnceTheTypingPausesAndSaysItsBlock() = runTest {
        holding()
        names.names["friend.eth"] = NameResolution.Resolved("friend.eth", friend, 9_411_900)
        val viewModel = viewModel()

        for (typed in listOf("f", "friend", "friend.", "friend.e", "friend.eth")) viewModel.onToChange(typed)
        assertEquals("Resolving the name with verified calls…", viewModel.compose.toNote)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf("friend.eth"), names.asked, "the name typed, not every letter of it")
        assertEquals(
            RecipientItem(
                "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
                name = "friend.eth",
                resolvedAt = "Resolved at finalized block 9,411,900",
            ),
            viewModel.compose.recipient,
        )
        assertNull(viewModel.compose.toNote)
    }

    @Test
    fun aNameThatCannotBeResolvedHereSaysWhy() = runTest {
        holding()
        names.names["dark.eth"] = NameResolution.OffchainOnly
        names.names["later.eth"] = NameResolution.Unavailable(ReadUnavailable.UNPROVEN)
        val viewModel = viewModel()

        suspend fun typed(text: String): TransferForm {
            viewModel.onToChange(text)
            advanceTimeBy(1_000)
            runCurrent()
            return viewModel.compose
        }

        assertEquals(
            "Only an off-chain gateway gives this name's address, and Serotine asks none: it cannot be resolved here.",
            typed("dark.eth").toNote,
        )
        assertEquals("This name has no address.", typed("nobody.eth").toNote)
        val unavailable = typed("later.eth")
        assertEquals("The name could not be resolved with verified calls this time.", unavailable.toNote)
        assertTrue(unavailable.toError && unavailable.canRetryLookup)

        names.names["later.eth"] = NameResolution.Resolved("later.eth", friend, 9_411_901)
        viewModel.onRetryLookup()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB", viewModel.compose.recipient?.address)
        assertEquals(2, names.asked.count { it == "later.eth" })
    }

    @Test
    fun theAmountIsInTokenUnitsWithTheTokensDecimalsAndAFiatHintBesideIt() = runTest {
        holding()
        feeds.answer("USDC", 1.0)
        val viewModel = viewModel(asset = usdc.address)
        runCurrent()

        viewModel.onAmountChange("1.2345678")
        assertEquals("USDC has 6 decimals: at most 6 digits after the dot.", viewModel.compose.amountError)
        viewModel.onAmountChange("0,5")
        assertEquals("Use a dot for the decimals: 0.5, not 0,5.", viewModel.compose.amountError)
        viewModel.onAmountChange("5.")
        assertNull(viewModel.compose.amountError, "still being typed")
        viewModel.onAmountChange("0")
        assertEquals("Enter an amount above zero.", viewModel.compose.amountError)

        viewModel.onAmountChange("40")
        assertNull(viewModel.compose.amountError)
        assertEquals("≈ $40.00 · testnet prices", viewModel.compose.amountHint)

        viewModel.onMax()
        assertEquals("250", viewModel.compose.amount, "the whole verified balance, in token units")
    }

    @Test
    fun theReviewShowsTheVerifiedQuote() = runTest {
        val viewModel = reviewed()

        val review = viewModel.review
        assertEquals("0.1 ETH", review.amount)
        assertEquals(
            RecipientItem("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359", label = "Savings"),
            review.recipient,
        )
        // 21000 gas × (2 × 10 gwei + the normal tip of 2 gwei).
        assertEquals("0.000462 ETH", review.maxFee)
        assertEquals(
            "Normal fee: 21,000 gas × at most 22 gwei, from the verified fee history up to block 99. " +
                "Only what the block charges is paid.",
            review.feeDetail,
        )
        assertEquals(
            listOf(BalanceChange("ETH balance", "1.5 ETH", "at least 1.399538 ETH", "verified at block 9,412,118")),
            review.balances,
        )
        assertEquals(
            "The transfer, run as a verified call from this account at block 100, goes through.",
            review.simulation,
        )
        assertNull(review.pending)
        assertFalse(review.unlock.biometrics, "no biometric path on this device")
        assertFalse(review.canSign, "nothing typed yet")
    }

    @Test
    fun aTokenTransfersReviewShowsBothBalancesAndWhatEarlierSendsMayStillTake() = runTest {
        holding()
        inputs.estimate = GasEstimate.Estimated(51_000)
        val viewModel = viewModel(asset = usdc.address)
        runCurrent()
        viewModel.onToChange(friend.hex)
        viewModel.onAmountChange("40")
        viewModel.onFeeChange(FeeLevel.FAST)
        viewModel.review()
        runCurrent()

        val review = viewModel.review
        assertEquals("40 USDC", review.amount)
        assertEquals(
            listOf(
                BalanceChange("USDC balance", "250 USDC", "210 USDC", "verified at block 9,412,117"),
                // 51000 gas × 23 gwei.
                BalanceChange(
                    "ETH balance, which pays the fee",
                    "1.5 ETH",
                    "at least 1.498827 ETH",
                    "verified at block 9,412,118",
                    short = "ETH balance",
                ),
            ),
            review.balances,
        )
        assertEquals(
            listOf(Triple(daily.address, usdc.address, UInt256.ZERO)),
            inputs.estimated,
            "the token's own call",
        )
    }

    @Test
    fun eachRefusalOfTheQuoteIsSaidOnTheComposeStep() = runTest {
        holding()
        val viewModel = viewModel()
        viewModel.onToChange(friend.hex)

        suspend fun refusedFor(amount: String): String? {
            viewModel.onAmountChange(amount)
            viewModel.review()
            runCurrent()
            return viewModel.compose.error
        }

        assertEquals(
            "Not enough ETH for this amount and the most the fee can be: send less, to leave room for the fee. " +
                "Transfers not mined yet count too.",
            refusedFor("1.5"),
        )
        inputs.simulation = Simulation.Reverted(100)
        assertEquals(
            "The transfer reverts, run as a verified call at block 100: the recipient refuses it. Nothing was signed.",
            refusedFor("0.1"),
        )
        inputs.simulation = Simulation.Succeeded("", 100)
        inputs.estimate = GasEstimate.Unavailable(ReadUnavailable.UNPROVEN, "no proof peer")
        assertEquals("The gas estimate could not be verified this time: try again.", refusedFor("0.15"))
        inputs.estimate = GasEstimate.Estimated(21_000)
        val fees = inputs.fees
        inputs.fees = FeeReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals("The fee could not be verified this time: try again.", refusedFor("0.16"))
        inputs.fees = fees
        inputs.estimate = GasEstimate.Unavailable(ReadUnavailable.STALE_ANCHOR)
        assertEquals("The light client refuses to sync, so nothing can be quoted.", refusedFor("0.2"))
        balances.of(daily.address).value = BalanceReading.Syncing
        assertEquals("The light client is not synced yet: try again in a moment.", refusedFor("0.3"))
        assertEquals(0, signer.asked.size, "nothing was signed")
    }

    @Test
    fun aTokenTheAccountHoldsTooLittleOfIsRefused() = runTest {
        holding()
        val viewModel = viewModel(asset = usdc.address)
        runCurrent()
        viewModel.onToChange(friend.hex)
        viewModel.onAmountChange("300")
        viewModel.review()
        runCurrent()

        assertEquals(
            "This account holds less USDC than that, once what its transfers not mined yet and its CoW orders " +
                "that may still fill can take is set aside.",
            viewModel.compose.error,
        )
    }

    @Test
    fun whatALiveCowOrderMayStillTakeIsHeldBackAndSaid() = runTest {
        holding()
        val settlement = Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41")
        val hundredUsdc = UInt256.of(100_000_000)
        val order =
            CowOrder(usdc.address, CowOrder.NATIVE_BUY_TOKEN, daily.address, hundredUsdc, UInt256.ONE, 1_900_000_000)
        cowOrders.orders.value = listOf(
            CowOrderRecord(
                account = daily.address,
                network = Network.SEPOLIA,
                settlement = settlement,
                order = order,
                uid = order.uid(Network.SEPOLIA.chainId, settlement, daily.address),
                signature = FakeSigner.SIGNATURE,
                sell = TransferAsset.Erc20Token(usdc),
                buy = TransferAsset.Native,
                signedAt = 1_899_999_700,
                rangeStart = 9_412_000,
            ),
        )
        val viewModel = viewModel(asset = usdc.address)
        runCurrent()
        viewModel.onToChange(friend.hex)
        viewModel.onAmountChange("200")
        viewModel.review()
        runCurrent()
        assertTrue(viewModel.compose.error!!.startsWith("This account holds less USDC than that"), "250 less 100")

        viewModel.edit()
        viewModel.onAmountChange("100")
        viewModel.review()
        runCurrent()

        assertEquals(
            "This account's CoW orders that may still fill hold back 100 USDC. The balance after counts it.",
            viewModel.review.pending,
        )
    }

    @Test
    fun signingWithThePasswordBroadcastsTheQuoteAndEndsInItsStatus() = runTest {
        val viewModel = reviewed()

        viewModel.onPasswordChange("correct horse battery")
        assertTrue(viewModel.review.canSign)
        viewModel.sign()
        runCurrent()

        val sentState = assertIs<SendUiState.Sent>(viewModel.uiState.value)
        val record = sent.transfers.value.single()
        assertEquals(record.hash, sentState.hash)
        assertEquals(listOf("correct horse battery"), signer.passwords)
        assertEquals(daily.address, signer.asked.single().first)
        assertContentEquals(record.signed.raw, broadcaster.sent.single(), "what was signed is what was sent")
        assertEquals(
            SentAction.Transfer(savings.address, TransferAsset.Native, UInt256.of(100_000_000_000_000_000L)),
            record.action,
            "the amount typed, in wei",
        )
        assertEquals(7, record.nonce)
    }

    @Test
    fun aWrongPasswordIsSaidAndNothingLeavesTheDevice() = runTest {
        signer.answer = Signing.WrongPassword
        val viewModel = reviewed()

        viewModel.onPasswordChange("mistyped")
        viewModel.sign()
        runCurrent()

        val review = viewModel.review
        assertEquals("That is not the wallet password. Nothing was signed.", review.unlock.error)
        assertEquals("", review.unlock.password.text, "the password is gone")
        assertTrue(broadcaster.sent.isEmpty())
        assertTrue(sent.transfers.value.isEmpty())
    }

    @Test
    fun notEnoughMemoryToOpenTheKeyIsSaidAsSuch() = runTest {
        signer.answer = Signing.NotEnoughMemory
        val viewModel = reviewed()

        viewModel.onPasswordChange("correct horse battery")
        viewModel.sign()
        runCurrent()

        assertEquals(
            "Not enough free memory to open the key: close other apps and try again. Nothing was signed.",
            viewModel.review.unlock.error,
        )
        assertTrue(broadcaster.sent.isEmpty())
        assertTrue(sent.transfers.value.isEmpty())
    }

    @Test
    fun theFingerprintIsOfferedWhereItIsOnAndADismissedPromptLeavesThePassword() = runTest {
        biometrics = BiometricUnlock.On
        signer.answer = Signing.Cancelled
        val viewModel = reviewed()
        assertTrue(viewModel.review.canSignWithBiometrics)

        viewModel.signWithBiometrics()
        runCurrent()

        val unlock = assertIs<Unlock.WithBiometrics>(signer.asked.single().second)
        assertEquals("Send 0.1 ETH to 0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359", unlock.reason)
        assertEquals(60.seconds, -unlock.notAfter!!.elapsedNow(), "the prompt gets what is left of the quote's minute")
        val review = viewModel.review
        assertEquals(
            "The fingerprint or face check was dismissed. Nothing was signed: try again, or use the wallet password.",
            review.unlock.error,
        )
        assertTrue(review.unlock.biometrics, "dismissed, not broken: it may be tried again")

        signer.answer = Signing.BiometricsUnusable(BiometricUnusable.INVALIDATED)
        viewModel.signWithBiometrics()
        runCurrent()
        assertFalse(viewModel.review.unlock.biometrics, "the password is all that is left")
        assertTrue(broadcaster.sent.isEmpty())
    }

    @Test
    fun theBiometricPromptGetsWhatIsLeftOfTheQuotesAgeAndOneAnsweredLateSignsNothing() = runTest {
        biometrics = BiometricUnlock.On
        val viewModel = reviewed()
        clock += 40.seconds

        signer.answer = Signing.TimedOut
        viewModel.signWithBiometrics()
        runCurrent()

        assertEquals(20.seconds, -(signer.asked.single().second as Unlock.WithBiometrics).notAfter!!.elapsedNow())
        assertTrue(broadcaster.sent.isEmpty(), "nothing signed, nothing sent")
        assertEquals(2, inputs.estimated.size, "the quote made again")
        assertEquals(
            "The fingerprint or face check took longer than the quote could wait, so nothing was signed and the " +
                "quote was made again: check it, then sign.",
            viewModel.review.unlock.error,
        )
        assertTrue(viewModel.review.unlock.biometrics, "the prompt is still offered")

        // Too little left for a prompt: the quote is made again first, and nothing is asked.
        clock += 50.seconds
        viewModel.signWithBiometrics()
        runCurrent()
        assertEquals(1, signer.asked.size)
        assertEquals(3, inputs.estimated.size)
    }

    @Test
    fun theReviewStandsWhileTheAccountListIsNotThere() = runTest {
        val viewModel = reviewed()
        viewModel.onPasswordChange("correct horse battery")

        accounts.unreadable.value = true
        runCurrent()

        val review = viewModel.review
        assertEquals("Daily", review.from.label, "the sender as it was quoted")
        assertEquals("correct horse battery", review.unlock.password.text)
        viewModel.sign()
        runCurrent()
        assertIs<SendUiState.Sent>(viewModel.uiState.value)

        // Composing again is where the list's state shows.
        val composing = viewModel()
        assertIs<SendUiState.CannotSend>(composing.uiState.value)
    }

    @Test
    fun aTransferSignedButNotSentIsRetriedWithItsOwnBytes() = runTest {
        broadcaster.answers += Broadcast.Unavailable("no peer")
        val viewModel = reviewed()
        viewModel.onPasswordChange("correct horse battery")
        viewModel.sign()
        runCurrent()

        val notSent = assertIs<NotSentItem>(viewModel.review.notSent)
        assertTrue(notSent.canRetry)
        assertFalse(viewModel.review.canSign, "never a second signature")
        viewModel.refresh()
        viewModel.edit()
        assertIs<SendUiState.Review>(viewModel.uiState.value, "no new quote either")

        viewModel.retry()
        runCurrent()

        assertIs<SendUiState.Sent>(viewModel.uiState.value)
        assertEquals(1, signer.asked.size)
        assertEquals(2, broadcaster.sent.size)
        assertContentEquals(broadcaster.sent[0], broadcaster.sent[1], "the very bytes, not a new quote")
    }

    @Test
    fun aTransferMaybeSentHoldsTheNextOneBackAndOffersItsStatus() = runTest {
        broadcaster.answers += Broadcast.Unavailable("no peer")
        val first = reviewed()
        first.onPasswordChange("correct horse battery")
        first.sign()
        runCurrent()
        val maybe = sent.transfers.value.single()
        assertTrue(maybe.maybeSent)

        // The screen left, or the app restarted: the bytes are in the record, and no new one is quoted.
        val again = reviewed()
        val state = assertIs<SendUiState.Compose>(again.uiState.value)
        assertEquals(maybe.hash, state.earlier)
        assertEquals(
            "An earlier transaction from this account may still be on its way: no peer answered when it was " +
                "broadcast, and no block has it yet. Try it again from its status rather than sign a new one, " +
                "which could pay twice.",
            state.form.error,
        )
        assertEquals(1, signer.asked.size, "nothing signed for it")

        // Any change to the form starts over, and the link goes with the refusal.
        again.onAmountChange("0.2")
        assertNull(assertIs<SendUiState.Compose>(again.uiState.value).earlier)
    }

    @Test
    fun aRetryOfATransferMaybeSentTurnedAwayKeepsTryAgainAndBackClosed() = runTest {
        broadcaster.answers += Broadcast.Unavailable("no peer")
        broadcaster.answers += Broadcast.Rejected("already known")
        val viewModel = reviewed()
        viewModel.onPasswordChange("correct horse battery")
        viewModel.sign()
        runCurrent()

        viewModel.retry()
        runCurrent()

        val notSent = assertIs<NotSentItem>(viewModel.review.notSent)
        assertTrue(notSent.canRetry, "the record is still maybe sent: the same bytes may be tried again")
        assertTrue(notSent.message.startsWith("Turned away this time (the peer said: already known)."))
        viewModel.edit()
        assertIs<SendUiState.Review>(viewModel.uiState.value, "no new transfer while the first may be out there")
        assertEquals(listOf(true), sent.transfers.value.map { it.maybeSent })
    }

    @Test
    fun aRejectedTransferIsNotTriedAgain() = runTest {
        broadcaster.answers += Broadcast.Rejected("nonce too low")
        val viewModel = reviewed()
        viewModel.onPasswordChange("correct horse battery")
        viewModel.sign()
        runCurrent()

        val notSent = assertIs<NotSentItem>(viewModel.review.notSent)
        assertFalse(notSent.canRetry)
        assertEquals(
            "Signed, but not sent: judged on verified state, it can never be mined as signed (nonce too low). " +
                "Sending it again would not change that.",
            notSent.message,
        )
        viewModel.retry()
        assertEquals(1, broadcaster.sent.size)

        // Nothing went out, so a new transfer may be composed from where this one was.
        viewModel.edit()
        assertEquals("0.1", viewModel.compose.amount)
    }

    @Test
    fun aRetryRejectedWhileItsReceiptCannotBeVerifiedStaysOpen() = runTest {
        broadcaster.answers += Broadcast.Unavailable("no peer")
        broadcaster.answers += Broadcast.Rejected("nonce too low")
        val viewModel = reviewed()
        viewModel.onPasswordChange("correct horse battery")
        viewModel.sign()
        runCurrent()
        receipts.of(Eip1559Transaction.hashOf(broadcaster.sent.single())).value =
            TransactionProgress.Unavailable(ReadUnavailable.UNPROVEN)

        viewModel.retry()
        runCurrent()

        val notSent = assertIs<NotSentItem>(viewModel.review.notSent)
        assertTrue(notSent.canRetry, "the next try checks again")
        assertEquals(
            "Rejected this time (nonce too low), and whether the first broadcast reached a block after all cannot " +
                "be verified right now. Try again sends the same signed transfer and checks again; do not send a " +
                "new one meanwhile.",
            notSent.message,
        )
        viewModel.edit()
        assertIs<SendUiState.Review>(viewModel.uiState.value, "no new transfer while it may be in a block")
        assertEquals(
            listOf(true),
            sent.transfers.value.map { it.maybeSent },
            "kept as maybe sent: no new quote passes it after a restart either",
        )
    }

    @Test
    fun aQuoteTooOldAtTheTapIsMadeAgainBeforeAnythingIsSigned() = runTest {
        val viewModel = reviewed()
        viewModel.onPasswordChange("correct horse battery")
        clock += 61.seconds
        inputs.nonce = 8

        viewModel.sign()
        runCurrent()

        assertTrue(signer.asked.isEmpty(), "nothing signed on the old quote")
        assertEquals(2, inputs.estimated.size, "quoted again")
        assertEquals(
            "The quote was over a minute old, so it was made again: check it, then sign.",
            viewModel.review.unlock.error,
        )
        viewModel.sign()
        runCurrent()
        assertEquals(8, sent.transfers.value.single().nonce, "the fresh quote is the one signed")
    }

    @Test
    fun aReviewHiddenWhileItsSignatureRanIsQuotedAgainWhenNothingWasSent() = runTest {
        val viewModel = reviewed()
        viewModel.onShown()
        signer.pending = CompletableDeferred()
        viewModel.onPasswordChange("mistyped")
        viewModel.sign()
        runCurrent()

        viewModel.onHidden()
        viewModel.onShown() // back while the signature still runs: no quote yet
        assertEquals(1, inputs.estimated.size)
        signer.pending!!.complete(Signing.WrongPassword)
        runCurrent()

        assertEquals(2, inputs.estimated.size, "the quote from before it was hidden is made again")
    }

    @Test
    fun thePasswordIsDroppedWhenTheScreenIsHiddenAndTheReviewIsQuotedAgainOnReturn() = runTest {
        val viewModel = reviewed()
        viewModel.onPasswordChange("correct horse battery")

        viewModel.onHidden()
        assertEquals("", viewModel.review.unlock.password.text)

        inputs.nonce = 8
        viewModel.onShown()
        runCurrent()
        assertEquals(2, inputs.estimated.size, "quoted again")
        viewModel.onPasswordChange("correct horse battery")
        viewModel.sign()
        runCurrent()
        assertEquals(8, sent.transfers.value.single().nonce, "the fresh quote is the one signed")
    }

    @Test
    fun aQuoteIsNotReusedAcrossAChangeOfTheTransfer() = runTest {
        val viewModel = reviewed()
        viewModel.onPasswordChange("correct horse battery")

        viewModel.edit()
        assertIs<SendUiState.Compose>(viewModel.uiState.value)
        viewModel.onAmountChange("0.2")
        viewModel.review()
        runCurrent()

        assertEquals("0.2 ETH", viewModel.review.amount)
        assertEquals("", viewModel.review.unlock.password.text, "a new review, a new password")
        assertEquals(2, inputs.estimated.size)
    }

    @Test
    fun aRecipientsNameAskedWhileTheLightClientSyncsGreysOutTryAgainAndIsLookedUpAgainByItself() = runTest {
        holding()
        names.names["friend.eth"] = NameResolution.Unavailable(ReadUnavailable.SYNCING)
        val viewModel = viewModel()
        viewModel.onToChange("friend.eth")
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(viewModel.compose.canRetryLookup)
        assertNotNull(viewModel.compose.retryLookupWaits, "Try again greyed out, with its reason")

        names.names["friend.eth"] = NameResolution.Resolved("friend.eth", friend, 9_411_900)
        advanceTimeBy(SYNC_RETRY_MS)
        runCurrent()
        assertEquals("friend.eth", viewModel.compose.recipient?.name, "resolved by itself once it has caught up")
        assertNull(viewModel.compose.retryLookupWaits)
    }

    @Test
    fun aNameIsResolvedAgainWithEveryQuote() = runTest {
        holding()
        names.names["friend.eth"] = NameResolution.Resolved("friend.eth", friend, 9_411_900)
        val viewModel = viewModel()
        viewModel.onToChange("friend.eth")
        advanceTimeBy(1_000)
        viewModel.onAmountChange("0.1")
        viewModel.review()
        runCurrent()
        assertEquals("friend.eth", viewModel.review.recipient.name)

        names.names["friend.eth"] = NameResolution.Resolved("friend.eth", savings.address, 9_411_950)
        viewModel.refresh()
        runCurrent()

        assertEquals(
            "“friend.eth” no longer resolves to the address shown: check the recipient.",
            viewModel.compose.error,
        )
    }

    @Test
    fun aSignatureRunningWhenTheScreenIsHiddenStillLands() = runTest {
        val pending = CompletableDeferred<Signing>()
        signer.pending = pending
        val viewModel = reviewed()
        viewModel.onPasswordChange("correct horse battery")
        viewModel.sign()
        runCurrent()
        assertTrue(viewModel.review.signing)

        viewModel.onHidden()
        pending.complete(Signing.Signed(FakeSigner.SIGNATURE))
        runCurrent()

        assertIs<SendUiState.Sent>(viewModel.uiState.value)
    }

    @Test
    fun nothingCanBeSentFromAWatchOnlyAccount() = runTest {
        val viewModel = viewModel(from = savings.address)
        runCurrent()

        assertEquals(
            SendUiState.CannotSend(
                "Sepolia",
                "This account is watch-only: its key is not on this device, so nothing can be sent from it.",
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun aQuoteThatLandsWhileTheScreenIsHiddenIsMadeAgainBeforeItIsSigned() = runTest {
        holding()
        val viewModel = viewModel()
        viewModel.onShown()
        viewModel.onPickAccount(viewModel.compose.accounts.single().address)
        viewModel.onAmountChange("0.1")
        val gate = CompletableDeferred<Unit>()
        inputs.gate = gate

        viewModel.review()
        runCurrent()
        viewModel.onHidden()
        inputs.gate = null
        gate.complete(Unit)
        runCurrent()
        val quotes = inputs.estimated.size
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        assertEquals(quotes + 1, inputs.estimated.size, "quoted before the hide: made again, not signed")
        assertEquals(emptyList(), signer.asked)
        viewModel.onShown()
        runCurrent()
        assertEquals(quotes + 1, inputs.estimated.size)
    }

    @Test
    fun aRefreshInFlightAcrossAHideAndAShowIsMadeAgainWhenItLands() = runTest {
        val viewModel = reviewed()
        viewModel.onShown()
        val gate = CompletableDeferred<Unit>()
        inputs.gate = gate
        viewModel.refresh()
        runCurrent()

        viewModel.onHidden()
        viewModel.onShown()
        val quotes = inputs.estimated.size
        inputs.gate = null
        gate.complete(Unit)
        runCurrent()

        assertEquals(quotes + 1, inputs.estimated.size, "the refresh from before the hide is made again")
        assertFalse(viewModel.review.refreshing)
    }
}

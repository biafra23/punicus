package eth.serotine.ui.safeswap

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.FeeTier
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PoolKey
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.UniswapV3Factory
import eth.serotine.domain.model.UniswapV3Pool
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.BiometricUnlock
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyDerivation
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.repository.VenueReading
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.FindUniswapRoute
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveSafeTxQueue
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.ObserveUsdPrice
import eth.serotine.domain.usecase.ProposeSafeSwap
import eth.serotine.domain.usecase.SignSafeTx
import eth.serotine.ui.DAY
import eth.serotine.ui.FakeFeeds
import eth.serotine.ui.FakeSigner
import eth.serotine.ui.HEAD_TIME
import eth.serotine.ui.MemoryCurrencySetting
import eth.serotine.ui.SafeWorld
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.SendFrom
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** [SafeSwapViewModel] over fake ports, through the real use cases. */
@OptIn(ExperimentalCoroutinesApi::class)
class SafeSwapViewModelTest {

    private val world = SafeWorld()
    private val clock = TestTimeSource()
    private var wallClock = 1_800_000_000L
    private val feeds = FakeFeeds()
    private val router = world.uniswapDeployment.swapRouter02
    private val weth = world.uniswapDeployment.weth9
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

    /**
     * Uniswap as the Safe tests pin it: the factory lists the one USDC/WETH pool at 0.05 % and no
     * other, QuoterV2 answers the world's quotes, and USDC answers [allowance] for the Safe's router.
     */
    private var allowance = UInt256.ZERO
    private val pool = Address.of("0x" + "30".repeat(20))
    private val uniswap = object : UniswapV3Chain {
        override suspend fun venue(network: Network): VenueReading = VenueReading.Verified(world.uniswapDeployment)

        override suspend fun call(network: Network, to: Address, data: ByteArray): Simulation {
            val hex = data.hex()
            val listed = PoolKey.of(world.usdc.address, weth, FeeTier.LOW)
            return when {
                to == world.usdc.address && hex == Erc20.allowanceData(world.safe, router).hex() ->
                    Simulation.Succeeded(allowance.toBytes32().hex(), 100)
                to == world.uniswapDeployment.factory -> {
                    val listedHere = hex == UniswapV3Factory.getPool(listed).hex()
                    Simulation.Succeeded(if (listedHere) "00".repeat(12) + pool.hex.drop(2) else "00".repeat(32), 100)
                }
                to == pool && hex == UniswapV3Pool.liquidity().hex() ->
                    Simulation.Succeeded(UInt256.of(1_000).toBytes32().hex(), 100)
                else -> world.uniswap.call(Network.SEPOLIA, to, data)
            }
        }
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        asset: Address? = world.usdc.address,
        replacing: Digest? = null,
        signer: Signer = world.signer,
    ): SafeSwapViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = SafeSwapViewModel(
            AccountId(Network.SEPOLIA, world.safe),
            asset,
            replacing,
            ObserveAccounts(world.accounts),
            ObserveAccountValue(
                ObserveNativeBalance(world.balances),
                ObserveTokenHoldings(world.knownTokens, world.tokenBalances),
                ObserveUsdPrice(feeds),
                ObserveDisplayCurrencies(feeds, MemoryCurrencySetting(), world.accounts),
            ),
            world.observeSafe,
            ObserveSafeTxQueue(world.queue, world.observeSafe, world.recovery, Network.SEPOLIA),
            DescribeKeyStore(keys),
            ProposeSafeSwap(
                world.observeSafe,
                FindUniswapRoute(uniswap, world.inputs),
                uniswap,
                world.balances,
                world.tokenBalances,
                world.inputs,
                world.queue,
                world.lightClient,
                Network.SEPOLIA,
            ),
            SignSafeTx(signer, world.observeSafe, world.recovery, world.queue, Network.SEPOLIA),
            clock,
            formatTime = { "at $it" },
            now = { wallClock },
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private fun holding() {
        world.balances.of(world.safe).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
        world.tokenBalances.of(world.safe, world.usdc.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("250000000"), 99)
    }

    /** The router's multicall returning [out] wei and unwrapping it: what a run of the swap from the Safe gives. */
    private fun routerReturns(out: Long) {
        // The multicall's bytes[]: its two results, the swap's amount and the unwrap's empty one.
        world.inputs.runs[router] = Simulation.Succeeded(
            listOf(0x20L, 2L, 0x40L, 0x80L, 0x20L).joinToString("") { UInt256.of(it).toBytes32().hex() } +
                UInt256.of(out).toBytes32().hex() + UInt256.ZERO.toBytes32().hex(),
            100,
        )
    }

    private val SafeSwapViewModel.form get() = assertIs<SafeSwapUiState.Compose>(uiState.value).form
    private val SafeSwapViewModel.review get() = assertIs<SafeSwapUiState.Review>(uiState.value)

    /** Composes 100 USDC for ETH and asks for the review. */
    private fun TestScope.reviewed(signer: Signer = world.signer): SafeSwapViewModel {
        holding()
        world.quoteSwap(out = 40_200_000_000_000_000)
        val viewModel = viewModel(signer = signer)
        viewModel.onBuyChange(null)
        viewModel.onAmountChange("100")
        runCurrent()
        viewModel.review()
        runCurrent()
        return viewModel
    }

    @Test
    fun theFormSwapsFromTheSafeWithItsAssetsAndNoFee() = runTest {
        holding()
        val viewModel = viewModel()

        val form = viewModel.form
        assertEquals(SendFrom("Treasury", Eip55.format(world.safe)), form.from)
        assertEquals(listOf("ETH", "USDC"), form.sellOptions.map { it.symbol })
        assertEquals("USDC", form.sell?.symbol)
        assertEquals(false, form.fixed)
        assertTrue(form.info.orEmpty().contains("24 hours"), "the deadline the owners have")
    }

    @Test
    fun aSwapNeedingAnApprovalIsReviewedAsBothSafeTransactionsAndBothAreSignedAndQueued() = runTest {
        val viewModel = reviewed()

        val review = viewModel.review
        assertEquals(
            listOf(
                "Safe nonce 4" to "Allow a contract to take 100 USDC",
                "Safe nonce 5" to "Swap 100 USDC for at least 0.039999 ETH",
            ),
            review.transactions.map { it.nonce to it.action.title },
        )
        assertEquals("at least 0.039999 ETH", review.minimum)
        assertEquals("0.0402 ETH", review.quoted)
        assertEquals(
            "Refused by the router after at ${HEAD_TIME + DAY}: the verified head's time and 24 hours, for its " +
                "owners to sign and one to execute it.",
            review.deadline,
        )
        assertEquals(listOf("USDC → WETH"), review.route.map { it.pair })

        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        val proposed = assertIs<SafeSwapUiState.Proposed>(viewModel.uiState.value)
        val queued = world.queue.requests.value
        assertEquals(listOf(4L, 5L), queued.map { it.nonce })
        assertTrue(queued.all { it.signers == listOf(world.alice.address) }, "each signed by the owner held here")
        assertEquals("0x" + queued[1].hash.hex, proposed.hash, "the swap's screen opens")
        assertEquals(listOf("made-up test password", "made-up test password"), world.signer.passwords)
    }

    @Test
    fun aTokenThatRefusesOneAllowanceForAnotherIsSetToZeroFirstInThreeSafeTransactions() = runTest {
        allowance = UInt256.of(5_000_000)
        // USDT's kind: the approval of the amount reverts over the allowance there is, one of zero goes through.
        world.inputs.runOf = { to, data ->
            when {
                to != world.usdc.address -> null
                data.contentEquals(Erc20.approveData(router, UInt256.ZERO)) -> Simulation.Succeeded("", 100)
                data.contentEquals(Erc20.approveData(router, UInt256.of(100_000_000))) -> Simulation.Reverted(100)
                else -> null
            }
        }
        val viewModel = reviewed()

        val review = viewModel.review
        assertEquals(
            listOf(
                "Safe nonce 4" to "Allow a contract to take 0 USDC",
                "Safe nonce 5" to "Allow a contract to take 100 USDC",
                "Safe nonce 6" to "Swap 100 USDC for at least 0.039999 ETH",
            ),
            review.transactions.map { it.nonce to it.action.title },
        )
        assertTrue(review.approvalNote.orEmpty().contains("sets it to zero"), review.approvalNote)
        assertTrue(review.simulation.startsWith("The allowance set to zero, run as a verified call"), review.simulation)
        // Simple mode's words for the same: no block (docs/simple-ui.md).
        val simple = listOfNotNull(review.simpleSimulation, review.simpleQuotedAt)
        assertTrue(simple.size == 2 && simple.none { "block" in it }, simple.toString())

        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        assertIs<SafeSwapUiState.Proposed>(viewModel.uiState.value)
        assertEquals(listOf(4L, 5L, 6L), world.queue.requests.value.map { it.nonce })
    }

    @Test
    fun aSwapStoppedAfterItsResetSaysTheResetIsQueuedAndRefreshProposesTheRestAfterIt() = runTest {
        allowance = UInt256.of(5_000_000)
        world.inputs.runOf = { to, data ->
            when {
                to != world.usdc.address -> null
                data.contentEquals(Erc20.approveData(router, UInt256.of(100_000_000))) -> Simulation.Reverted(100)
                else -> null
            }
        }
        // The key store signs the reset, then runs short of memory for the approval.
        var calls = 0
        val signer = object : Signer {
            override suspend fun sign(account: Address, digest: Digest, unlock: Unlock): Signing =
                if (calls++ == 0) Signing.Signed(world.recovery.sign(account, digest)) else Signing.NotEnoughMemory
        }
        val viewModel = reviewed(signer)

        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        assertEquals(listOf(4L), world.queue.requests.value.map { it.nonce }, "the reset alone is queued")
        assertEquals(
            "The allowance set to zero is signed and queued; the approval and the swap are not. Refresh, and they " +
                "are proposed after it: Not enough free memory to open the key: close other apps and try again. " +
                "Nothing was signed.",
            viewModel.review.error,
        )
        viewModel.refresh()
        runCurrent()
        assertEquals(
            listOf(
                "Safe nonce 5" to "Allow a contract to take 100 USDC",
                "Safe nonce 6" to "Swap 100 USDC for at least 0.039999 ETH",
            ),
            viewModel.review.transactions.map { it.nonce to it.action.title },
            "no second reset",
        )
        assertTrue(viewModel.review.simulation.startsWith("The approval cannot run before the allowance set to zero"))
    }

    @Test
    fun aSwapStoppedAfterItsResetAndApprovalSaysBothAreQueued() = runTest {
        allowance = UInt256.of(5_000_000)
        world.inputs.runOf = { to, data ->
            when {
                to != world.usdc.address -> null
                data.contentEquals(Erc20.approveData(router, UInt256.of(100_000_000))) -> Simulation.Reverted(100)
                else -> null
            }
        }
        // The key store signs the reset and the approval, then runs short of memory for the swap.
        var calls = 0
        val signer = object : Signer {
            override suspend fun sign(account: Address, digest: Digest, unlock: Unlock): Signing =
                if (calls++ < 2) Signing.Signed(world.recovery.sign(account, digest)) else Signing.NotEnoughMemory
        }
        val viewModel = reviewed(signer)

        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        assertEquals(listOf(4L, 5L), world.queue.requests.value.map { it.nonce })
        assertTrue(
            viewModel.review.error.orEmpty().startsWith(
                "The allowance set to zero and the approval are signed and queued; the swap is not.",
            ),
            viewModel.review.error,
        )
    }

    @Test
    fun anAllowanceThatCoversTheAmountTakesTheSwapAlone() = runTest {
        allowance = UInt256.of(100_000_000)
        routerReturns(40_100_000_000_000_000)
        val viewModel = reviewed()

        val review = viewModel.review
        assertEquals(listOf("Safe nonce 4"), review.transactions.map { it.nonce })
        assertEquals(
            "The swap, run as a verified call from the Safe at block 100, returns 0.0401 ETH: at least its minimum.",
            review.simulation,
        )
    }

    @Test
    fun aSwapWhoseSignatureFailsAfterItsApprovalSaysTheApprovalIsQueuedAndRefreshProposesTheSwapAfterIt() = runTest {
        // The key store signs the approval, then runs short of memory for the swap.
        var calls = 0
        val signer = object : Signer {
            override suspend fun sign(account: Address, digest: Digest, unlock: Unlock): Signing =
                if (calls++ == 0) Signing.Signed(world.recovery.sign(account, digest)) else Signing.NotEnoughMemory
        }
        val viewModel = reviewed(signer)

        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        assertEquals(listOf(4L), world.queue.requests.value.map { it.nonce }, "the approval alone is queued")
        assertEquals(
            "The approval is signed and queued; the swap is not. Refresh, and the swap is proposed after the " +
                "approval: Not enough free memory to open the key: close other apps and try again. Nothing was " +
                "signed.",
            viewModel.review.error,
        )
        viewModel.refresh()
        runCurrent()
        assertEquals(listOf("Safe nonce 5"), viewModel.review.transactions.map { it.nonce }, "after the approval")
    }

    @Test
    fun signingAgainAfterTheSwapFailedSignsTheSwapAloneNotTheApprovalTwice() = runTest {
        // The key store signs the approval, runs short of memory for the swap, then signs it.
        var calls = 0
        val signer = object : Signer {
            override suspend fun sign(account: Address, digest: Digest, unlock: Unlock): Signing =
                if (calls++ == 1) Signing.NotEnoughMemory else Signing.Signed(world.recovery.sign(account, digest))
        }
        val viewModel = reviewed(signer)
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        assertEquals(listOf(4L), world.queue.requests.value.map { it.nonce })

        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        assertEquals(3, calls, "the approval once, the swap twice")
        assertEquals(listOf(4L, 5L), world.queue.requests.value.map { it.nonce })
        assertIs<SafeSwapUiState.Proposed>(viewModel.uiState.value)
    }

    @Test
    fun aSwapProposedAgainKeepsItsPairAndAmountAndTakesTheQueuedOnesNonce() = runTest {
        holding()
        allowance = UInt256.of(100_000_000)
        routerReturns(50_000_000_000_000_000)
        world.quoteSwap(out = 50_000_000_000_000_000)
        val stale = world.request(world.swap(4), world.carol)
        world.queue.save(stale)
        val viewModel = viewModel(asset = null, replacing = stale.hash)

        val form = viewModel.form
        assertEquals(true, form.fixed)
        assertEquals("USDC", form.sell?.symbol)
        assertEquals("ETH", form.buy?.symbol)
        assertEquals("100", form.amount)
        val compose = assertIs<SafeSwapUiState.Compose>(viewModel.uiState.value)
        assertTrue(compose.replacing.orEmpty().startsWith("Proposes the swap queued at Safe nonce 4 again"))
        viewModel.onAmountChange("5")
        assertEquals("100", viewModel.form.amount, "only the tolerance may change")

        viewModel.review()
        runCurrent()
        val review = viewModel.review
        assertEquals(listOf("Safe nonce 4"), review.transactions.map { it.nonce })
        assertEquals("at least 0.04975 ETH", review.minimum, "a fresh minimum")
        assertTrue(review.replaces.orEmpty().startsWith("Takes the place of the swap queued at Safe nonce 4"))
    }

    @Test
    fun aSwapToProposeAgainThatIsNoLongerQueuedSaysSo() = runTest {
        holding()
        val gone = world.request(world.swap(4))
        val viewModel = viewModel(asset = null, replacing = gone.hash)

        assertEquals(
            "The swap to propose again is no longer in this device's queue, or the Safe has passed its nonce: " +
                "there is nothing to put in its place.",
            assertIs<SafeSwapUiState.CannotSwap>(viewModel.uiState.value).message,
        )
    }

    @Test
    fun aSwapThatRevertsIsRefusedOnTheFormAndSimpleModeSaysSoWithNoBlock() = runTest {
        allowance = UInt256.of(100_000_000)
        world.inputs.runs[router] = Simulation.Reverted(100)
        val viewModel = reviewed()

        val form = viewModel.form
        assertTrue("block 100" in form.error.orEmpty(), form.error)
        assertEquals(
            "The swap reverts, or returns less than its minimum, as checked on chain. Nothing was proposed.",
            form.simpleError,
        )
    }

    @Test
    fun anApprovalThatRevertsAtAZeroAllowanceIsRefusedOnTheFormAndSimpleModeSaysSoWithNoBlock() = runTest {
        world.inputs.runOf = { to, data ->
            Simulation.Reverted(100).takeIf {
                to == world.usdc.address && data.contentEquals(Erc20.approveData(router, UInt256.of(100_000_000)))
            }
        }
        val viewModel = reviewed()

        val form = viewModel.form
        assertTrue("block 100" in form.error.orEmpty(), form.error)
        assertEquals(
            "USDC's contract refuses the approval with its allowance at zero, as checked on chain. Nothing was " +
                "proposed.",
            form.simpleError,
        )
    }

    @Test
    fun eachBalanceSaysHowLongAgoItWasProvedAndIsDatedAnew() = runTest {
        holding()
        world.tokenBalances.of(world.safe, world.usdc.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("250000000"), 99, provenAt = wallClock - 120)
        val viewModel = viewModel()
        runCurrent()
        assertEquals("updated 2 min ago", viewModel.form.sell?.age)

        wallClock += 60
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals("updated 3 min ago", viewModel.form.sell?.age, "the age is dated anew every second")
    }

    @Test
    fun aRefusalIsSaidOnTheForm() = runTest {
        holding()
        world.quoteSwap(out = 40_200_000_000_000_000)
        world.queue.save(world.request(world.swap(4)))
        val viewModel = viewModel()
        viewModel.onBuyChange(null)
        viewModel.onAmountChange("100")
        runCurrent()
        viewModel.review()
        runCurrent()

        assertEquals(
            "A swap of USDC is queued at Safe nonce 4, and counts on the router's allowance this one would set: " +
                "execute or reject it first.",
            viewModel.form.error,
        )
    }

    @Test
    fun aScreenLeftWhileTheApprovalIsSignedSignsNoSwapOverAnotherScreen() = runTest {
        val viewModel = reviewed()
        val gate = CompletableDeferred<Signing>()
        world.signer.pending = gate
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        viewModel.onHidden()
        gate.complete(Signing.Signed(FakeSigner.SIGNATURE))
        runCurrent()

        assertEquals(1, world.signer.asked.size, "the approval alone")
        assertEquals(listOf(4L), world.queue.requests.value.map { it.nonce })
        assertEquals(
            "The approval is signed and queued; the swap is not. Refresh, and the swap is proposed after the " +
                "approval: The screen was left before the swap was signed.",
            viewModel.review.error,
        )
    }

    @Test
    fun backDuringTheSwapsCheckSaysTheApprovalIsQueued() = runTest {
        val viewModel = reviewed()
        viewModel.onPasswordChange("made-up test password")
        val gate = CompletableDeferred<Signing>()
        world.signer.pending = gate
        viewModel.sign()
        runCurrent()
        // The approval signed; the Safe's check of the swap then waits for an unproven reading.
        world.kindGate = CompletableDeferred()
        gate.complete(Signing.Signed(FakeSigner.SIGNATURE))
        runCurrent()
        assertEquals(listOf(4L), world.queue.requests.value.map { it.nonce })

        viewModel.edit()

        assertEquals(
            "The approval is signed and queued; the swap is not. Review again, and the swap is proposed after the " +
                "approval.",
            viewModel.form.error,
        )
    }

    @Test
    fun aQueueThatCannotBeReadIsSaidAsSuchNotAsTheSwapGone() = runTest {
        holding()
        world.queue.unreadable.value = true
        val viewModel = viewModel(asset = null, replacing = world.request(world.swap(4)).hash)

        assertEquals(
            "This device's queue cannot be read right now, so the swap to propose again cannot be found. Serotine " +
                "keeps trying.",
            assertIs<SafeSwapUiState.CannotSwap>(viewModel.uiState.value).message,
        )
    }

    @Test
    fun aSafeWithNoOwnerHeldHereProposesNothing() = runTest {
        holding()
        world.state = world.state.copy(owners = listOf(world.bob.address, world.carol))
        val viewModel = viewModel()

        assertEquals(
            "No owner of this Safe has its key on this device: it is watch-only here, and nothing can be proposed " +
                "from it.",
            assertIs<SafeSwapUiState.CannotSwap>(viewModel.uiState.value).message,
        )
    }

    @Test
    fun theBiometricPromptSaysWhichOfTheTwoItSignsAndNamesTheRouterInFull() = runTest {
        biometrics = BiometricUnlock.On
        val viewModel = reviewed()

        viewModel.signWithBiometrics()
        runCurrent()

        val reasons = world.signer.asked.map { (it.second as Unlock.WithBiometrics).reason }
        val named = Eip55.format(router)
        assertEquals(
            listOf(
                "Sign Safe transaction 1 of 2 of Treasury, nonce 4: Allow a contract to take 100 USDC, spender $named",
                "Sign Safe transaction 2 of 2 of Treasury, nonce 5: Swap 100 USDC for at least 0.039999 ETH, " +
                    "router $named",
            ),
            reasons,
        )
    }

    private companion object {
        fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

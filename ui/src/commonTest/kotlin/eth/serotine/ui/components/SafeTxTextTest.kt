package eth.serotine.ui.components

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.FeeTier
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.RouterSwap
import eth.serotine.domain.model.SafeSettingsChange
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SafeTxGasTerms
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.CallRefusal
import eth.serotine.domain.usecase.QuoteFigure
import eth.serotine.domain.usecase.SafeSwapCheck
import eth.serotine.domain.usecase.SafeTxRefusal
import eth.serotine.domain.usecase.SwapNow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the Safe transaction screens say of a decoded action, of its gas terms and of a refusal. */
class SafeTxTextTest {

    // EIP-55's own examples.
    private val friend = Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359")
    private val module = Address.of("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb")
    private val usdc = Token(Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"), "USDC", "USD Coin", 6, "usdc")

    private fun item(action: SafeTxAction, tokens: List<DecodingToken> = listOf(DecodingToken(usdc))) =
        safeTxActionItem(action, nonce = 12, tokens, Network.SEPOLIA)

    @Test
    fun aRevertIsSaidWithItsBlockAndInSimpleModesWordsWithout() {
        val reverts = CallRefusal.Reverts(9_412_118)
        assertEquals(
            "It reverts, run as a verified call at block 9,412,118. Nothing was signed.",
            callRefusalText(reverts, Network.SEPOLIA),
        )
        assertEquals("It reverts, checked on chain. Nothing was signed.", simpleCallRefusalText(reverts))
        assertNull(simpleCallRefusalText(CallRefusal.Syncing), "it names no block: the same in both views")
    }

    @Test
    fun aSwapsDeadlineIsSaidOfTheRouterOnlyOnceItIsUniswaps() {
        val swap = RouterSwap(
            router = module,
            route = SwapRoute.direct(usdc.address, friend, FeeTier.MEDIUM),
            sellsEther = false,
            amountIn = UInt256.of(100_000_000),
            buysEther = false,
            minimumOut = UInt256.ONE,
            deadline = 1_792_419_600,
        )
        fun lines(check: SafeSwapCheck?) =
            safeSwapItem(SafeTxAction.Swap(swap), check, listOf(DecodingToken(usdc)), Network.SEPOLIA) { "at $it" }
                ?.lines

        // Another contract's code may ignore the deadline: it is said as signed, not as refused.
        assertEquals(listOf("Signed with a deadline of at 1792419600."), lines(SafeSwapCheck.NotUniswap(module)))
        assertEquals(
            listOf("Signed with a deadline of at 1792419600."),
            lines(SafeSwapCheck.Unavailable(ReadUnavailable.UNPROVEN)),
        )
        assertEquals(
            listOf("Checking the swap with the light client…", "Signed with a deadline of at 1792419600."),
            lines(null),
        )
        assertEquals(
            "Refused by the router after at 1792419600.",
            lines(SafeSwapCheck.SwapOnUniswap(swap, SwapNow.NoQuote, expired = false))?.last(),
        )
    }

    @Test
    fun aSwapIsOfferedToBeProposedAgainWhenItsMinimumFellBehindOrItsDeadlinePassed() {
        val swap = RouterSwap(
            router = module,
            route = SwapRoute.direct(usdc.address, friend, FeeTier.MEDIUM),
            sellsEther = false,
            amountIn = UInt256.of(100_000_000),
            buysEther = false,
            minimumOut = UInt256.of(1_000),
            deadline = 1_792_419_600,
        )
        fun offered(check: SafeSwapCheck) =
            safeSwapItem(SafeTxAction.Swap(swap), check, emptyList(), Network.SEPOLIA) { "$it" }?.proposeAgain

        // 2,000 now: a minimum of 1,000 leaves far more open than 0.5 % would.
        assertEquals(true, offered(SafeSwapCheck.SwapOnUniswap(swap, SwapNow.Quoted(UInt256.of(2_000), 9), false)))
        assertEquals(false, offered(SafeSwapCheck.SwapOnUniswap(swap, SwapNow.Quoted(UInt256.of(1_004), 9), false)))
        assertEquals(true, offered(SafeSwapCheck.SwapOnUniswap(swap, SwapNow.NoQuote, expired = true)))
        assertEquals(false, offered(SafeSwapCheck.NotUniswap(module)), "only Uniswap's own swap")
    }

    @Test
    fun aTransferIsItsAmountInTheAssetsUnitsAndItsRecipientInFull() {
        val ether = item(SafeTxAction.NativeTransfer(friend, UInt256.of(1_500_000_000_000_000_000)))
        assertEquals("Send 1.5 ETH", ether.title)
        assertEquals(listOf(ActionAddress("To", "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359")), ether.addresses)
        assertEquals("ETH", ether.symbol)

        val token = item(SafeTxAction.TokenTransfer(usdc.address, friend, UInt256.of(40_250_000)))
        assertEquals("Send 40.25 USDC", token.title)
        assertEquals(listOf("To", "Token contract"), token.addresses.map { it.what })
        assertNull(token.warning)
    }

    @Test
    fun anAddedTokenThatTakesABuiltInSymbolIsMarkedAsNotThatToken() {
        val impostor = Token(module, "USDC", "USD Coin", 6, icon = null)
        val transfer = SafeTxAction.TokenTransfer(module, friend, UInt256.of(40_000_000))

        val item = item(transfer, tokens = listOf(DecodingToken(impostor, sharesBuiltInSymbol = true)))

        assertEquals("Send 40 USDC", item.title)
        assertEquals("Not the built-in USDC: another contract", item.warning)
    }

    @Test
    fun aTransferOfATokenThisWalletDoesNotKnowIsInItsSmallestUnits() {
        val unknown = item(SafeTxAction.TokenTransfer(module, friend, UInt256.of(1_500_000)), tokens = emptyList())

        assertEquals("Send 1,500,000 units of a token", unknown.title)
        assertNull(unknown.symbol)
        assertEquals(1, unknown.notes.size)
    }

    @Test
    fun aChangeOfTheSafesSettingsSaysWhatChangesAndWarnsOfAModule() {
        assertEquals(
            SafeTxActionItem(
                title = "Add an owner",
                addresses = listOf(ActionAddress("New owner", "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359")),
                notes = listOf("The threshold becomes 2"),
            ),
            item(SafeTxAction.SettingsChange(SafeSettingsChange.OwnerAdded(friend, 2))),
        )
        val enabled = item(SafeTxAction.SettingsChange(SafeSettingsChange.ModuleEnabled(module)))
        assertEquals("Enable a module", enabled.title)
        assertEquals("A module can move the Safe's funds without the owners' signatures.", enabled.warning)
        assertEquals("Remove the guard", item(SafeTxAction.SettingsChange(SafeSettingsChange.GuardSet(null))).title)
    }

    @Test
    fun changesThatWeakenTheSafeWarnOfWhatTheyGiveUp() {
        val handler = item(SafeTxAction.SettingsChange(SafeSettingsChange.FallbackHandlerSet(module)))
        assertEquals(
            "The fallback handler answers the signature checks (EIP-1271) other contracts make for this Safe: one " +
                "that accepts every signature lets anyone spend what the Safe has approved (Permit2, permits, " +
                "orders), without its owners.",
            handler.warning,
        )
        assertEquals(
            "Whatever the guard enforces on the Safe's transactions stops: a limit, an allowlist, a delay.",
            item(SafeTxAction.SettingsChange(SafeSettingsChange.GuardSet(null))).warning,
        )
        assertEquals(
            "Whatever this module does for the Safe stops: a spending limit, a delay, a recovery path.",
            item(SafeTxAction.SettingsChange(SafeSettingsChange.ModuleDisabled(module))).warning,
        )
        // Taking the handler away stops what it answered: safe transfers to the Safe and its EIP-1271 checks.
        assertEquals(
            "Whatever the fallback handler answers for the Safe stops: tokens sent to it with a safe transfer " +
                "(ERC-721, ERC-1155) are refused, and the signature checks (EIP-1271) other contracts make for it " +
                "fail, so orders and permits signed for the Safe no longer work.",
            item(SafeTxAction.SettingsChange(SafeSettingsChange.FallbackHandlerSet(null))).warning,
        )
    }

    @Test
    fun aRejectionAContractCallAndADelegateCallSayWhatTheyAre() {
        assertEquals("Reject: use up nonce 12", item(SafeTxAction.Rejection).title)
        val call = item(SafeTxAction.ContractCall(module, UInt256.ZERO, "a9059cbb", 100))
        assertEquals("Call a contract", call.title)
        assertEquals("Function 0xa9059cbb, 100 bytes of call data", call.notes.first())
        val delegate = item(SafeTxAction.DelegateCall(module, selector = null, dataSize = 2))
        assertEquals("Delegate call", delegate.title)
        assertEquals("2 bytes of call data, shorter than a function's selector", delegate.notes.single())
        assertEquals(true, delegate.warning?.startsWith("A delegate call: the Safe would run this contract's code"))
    }

    @Test
    fun gasTermsAreListedWithTheAddressesTheyPay() {
        val terms = gasTermsItem(
            SafeTxGasTerms(UInt256.of(50_000), UInt256.ZERO, UInt256.of(1), module, friend),
            Network.SEPOLIA,
        )

        assertEquals(
            listOf(
                "safeTxGas: 50,000",
                "baseGas: 0",
                "gasPrice: 1 of the gas token's units",
                "A refund is paid in the gas token",
                "A refund goes to the refund receiver",
            ),
            terms.lines,
        )
        assertEquals(listOf("Gas token", "Refund receiver"), terms.addresses.map { it.what })
    }

    @Test
    fun aRefusalIsDisplayText() {
        assertEquals(
            "Not next: the Safe is at nonce 4, and the transactions before this one run first.",
            safeTxRefusalText(SafeTxRefusal.NotNext(4), Network.SEPOLIA),
        )
        assertEquals(
            // Chain 10, OP Mainnet, is no network of this wallet's.
            "A request for chain id 10, which is not a network this wallet supports.",
            safeTxRefusalText(SafeTxRefusal.WrongChain(10), Network.SEPOLIA),
        )
        assertEquals(
            "It reverts, run as a verified call at block 9,412,118. Nothing was signed.",
            safeTxRefusalText(SafeTxRefusal.NotQuoted(CallRefusal.Reverts(9_412_118)), Network.SEPOLIA),
        )
        assertEquals(
            "A figure could not be verified this time: try again.",
            safeTxRefusalText(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN), Network.SEPOLIA),
        )
        // The Safe's own check names what it could not verify; a light client that cannot verify at all says that.
        assertEquals(
            "The Safe's owners and nonce could not be verified this time: try again.",
            safeTxRefusalText(SafeTxRefusal.SafeUnavailable(ReadUnavailable.UNPROVEN), Network.SEPOLIA),
        )
        assertEquals(
            "There is no light client on this device, so nothing can be verified.",
            safeTxRefusalText(SafeTxRefusal.SafeUnavailable(ReadUnavailable.NO_ENGINE), Network.SEPOLIA),
        )
        assertEquals(
            "The fee could not be verified this time: try again.",
            safeTxRefusalText(
                SafeTxRefusal.NotQuoted(CallRefusal.Unavailable(ReadUnavailable.UNPROVEN, QuoteFigure.FEES)),
                Network.SEPOLIA,
            ),
        )
        assertEquals(
            "An earlier transaction from this account may still be on its way: no peer answered when it was " +
                "broadcast, and no block has it yet. Try it again from its status (Send on this account opens it) " +
                "rather than sign a new one, which could pay twice.",
            safeTxRefusalText(
                SafeTxRefusal.NotQuoted(CallRefusal.EarlierMaybeSent("0x" + "5e".repeat(32))),
                Network.SEPOLIA,
            ),
        )
        assertEquals(
            "This Safe is still on version 1.1.1, from before the way Serotine checks signatures today: it is " +
                "shown, but not signed, approved or executed here. Migrating it to a newer version (in Safe{Wallet} " +
                "or another app) fixes this.",
            safeTxRefusalText(SafeTxRefusal.TooOldToSign("1.1.1"), Network.SEPOLIA),
        )
    }
}

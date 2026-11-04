package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.FeeTier
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.SwapRouter02
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.UniswapV3Deployment
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.SigningRequestDecoding
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SafeTxSigningTest {

    private val world = SafeWorld()
    private val usdc = Token(Address.of("0x" + "cc".repeat(20)), "USDC", "USD Coin", 6, icon = null)
    private val unlock = Unlock.WithBiometrics("Sign the Safe transaction")

    private fun usdcTransfer(nonce: Long, amount: Long = 1_500_000) =
        SafeTx.call(usdc.address, UInt256.ZERO, Erc20.transferData(world.recipient, UInt256.of(amount)), nonce)

    private val import = ImportSigningRequest(world.codec, world.observeSafe, world.recovery, world.queue)
    private val sign = SignSafeTx(world.signer, world.observeSafe, world.recovery, world.queue, Network.SEPOLIA)
    private val propose = ProposeSafeTx(
        world.observeSafe,
        world.nativeBalances,
        world.tokenBalances,
        world.inputs,
        world.queue,
        Network.SEPOLIA,
    )

    @Test
    fun aRequestCheckedAgainstTheChainIsDecodedHereAndQueued() = runTest {
        val request = world.request(usdcTransfer(4), world.carol)

        val imported = assertIs<SigningRequestImport.Imported>(import(world.codec.encode(request)))

        assertEquals(
            SafeTxAction.TokenTransfer(usdc.address, world.recipient, UInt256.of(1_500_000)),
            imported.tx.action,
        )
        assertEquals(0, imported.queuedBehind, "the next to run")
        assertEquals(listOf(world.alice.address), imported.safe.ownersHeld)
        assertTrue(imported.saved)
        assertEquals(listOf(request), world.queue.requests.value)
    }

    @Test
    fun aRequestAheadOfTheSafesNonceIsQueuedBehindTheOthers() = runTest {
        val text = world.codec.encode(world.request(usdcTransfer(6)))

        val imported = assertIs<SigningRequestImport.Imported>(import(text))

        assertEquals(2, imported.queuedBehind)
    }

    @Test
    fun theSignaturesItCarriesJoinThoseQueuedHere() = runTest {
        world.queue.save(world.request(usdcTransfer(4), world.alice.address))

        val imported = assertIs<SigningRequestImport.Imported>(
            import(world.codec.encode(world.request(usdcTransfer(4), world.carol))),
        )

        assertEquals(listOf(world.alice.address, world.carol), imported.tx.request.signers)
        assertEquals(listOf(imported.tx.request), world.queue.requests.value)
    }

    @Test
    fun whatTheChainContradictsIsRefusedAndNothingIsKept() = runTest {
        suspend fun refusal(request: SigningRequest) =
            assertIs<SigningRequestImport.Refused>(import(world.codec.encode(request))).refusal

        assertEquals(SafeTxRefusal.NonceUsed(4), refusal(world.request(usdcTransfer(3))))
        assertEquals(SafeTxRefusal.NotAnOwner(world.stranger), refusal(world.request(usdcTransfer(4), world.stranger)))
        // Carol's signature of another transaction, claimed for this one.
        val other = world.request(usdcTransfer(5), world.carol).signatures.single()
        val forged = SigningRequest.of(Network.SEPOLIA, world.safe, usdcTransfer(4), listOf(other))
        assertEquals(SafeTxRefusal.BadSignature(world.carol), refusal(forged))
        // A signature that recovers to Alice, said to be Carol's.
        val alices = world.request(usdcTransfer(4), world.alice.address).signatures.single().signature
        val claimed = SigningRequest.of(
            Network.SEPOLIA,
            world.safe,
            usdcTransfer(4),
            listOf(SafeSignature.Ecdsa(world.carol, alices)),
        )
        assertEquals(SafeTxRefusal.BadSignature(world.carol), refusal(claimed))

        world.kind.value = KindReading.Verified(AddressKind.Contract, 100)
        assertEquals(SafeTxRefusal.NotASafe, refusal(world.request(usdcTransfer(4))))
        world.kind.value = KindReading.Syncing
        assertEquals(SafeTxRefusal.Syncing, refusal(world.request(usdcTransfer(4))))
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(SafeTxRefusal.SafeUnavailable(ReadUnavailable.UNPROVEN), refusal(world.request(usdcTransfer(4))))
        assertEquals(emptyList(), world.queue.requests.value)
    }

    @Test
    fun aSafeStillOnAPreOneThreeZeroSingletonIsNeitherImportedNorSignedThoughItIsRecognised() = runTest {
        // diskordia.eth's case, had it never moved to a pinned ≥1.3.0 singleton: recognised (its
        // owners and threshold are read and shown) but checks a hash this app does not compute.
        for (version in listOf("1.0.0", "1.1.1", "1.2.0")) {
            world.state = world.state.copy(version = version)
            val request = world.request(usdcTransfer(4), world.carol)

            assertEquals(
                SigningRequestImport.Refused(SafeTxRefusal.TooOldToSign(version)),
                import(world.codec.encode(request)),
            )
            assertEquals(
                SafeTxSignCheck.Refused(SafeTxRefusal.TooOldToSign(version)),
                sign.check(request, world.alice.address),
            )
        }
        assertEquals(emptyList(), world.queue.requests.value)
        assertEquals(emptyList(), world.signer.asked)
    }

    @Test
    fun aSafeMigratedToAPinnedSingletonSignsAsAnyOtherOne() = runTest {
        // changeMasterCopy only ever moves a Safe to a singleton this build pins (SafeDeployments):
        // state.version is what it reads there now, 1.3.0 on here, never one of the three above.
        world.state = world.state.copy(version = "1.3.0")

        assertIs<SafeTxSignCheck.Ready>(sign.check(world.request(usdcTransfer(4)), world.alice.address))
    }

    @Test
    fun aSafeThatCouldNotBeVerifiedThisTimeIsWaitedOutForItsNextReading() = runTest {
        val request = world.request(usdcTransfer(4), world.carol)
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        // The reading proves the Safe at its next poll, eight seconds on.
        launch {
            delay(8.seconds)
            world.kind.value = KindReading.Verified(AddressKind.Safe(world.state), 101)
        }

        val imported = assertIs<SigningRequestImport.Imported>(import(world.codec.encode(request)))

        assertEquals(101, imported.safe.blockNumber)
        assertEquals(8_000, currentTime, "imported once the Safe was proven, not before")
        assertEquals(listOf(request), world.queue.requests.value)
    }

    @Test
    fun aSafeStillUnprovenOnceTheBudgetIsSpentIsRefusedAsTheSafesCheck() = runTest {
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)

        assertEquals(
            SigningRequestImport.Refused(SafeTxRefusal.SafeUnavailable(ReadUnavailable.UNPROVEN)),
            import(world.codec.encode(world.request(usdcTransfer(4)))),
        )
        assertEquals(SAFE_CHECK_BUDGET.inWholeMilliseconds, currentTime)
        assertEquals(emptyList(), world.queue.requests.value)
    }

    @Test
    fun aCheckOutlastsTheAdaptersOwnRetriesAndSeesTheReadingAfterThem() = runTest {
        // As the engine adapter answers a slow Safe: its code proven (2 s), then its own retries
        // for its whole 20 s budget, unproven; the next poll 4 s on, and a read of 12 s that is.
        val slow = object : AddressKinds {
            override fun observeKind(network: Network, address: Address): Flow<KindReading> = flow {
                delay(22.seconds)
                emit(KindReading.Unavailable(ReadUnavailable.UNPROVEN))
                delay(16.seconds)
                emit(KindReading.Verified(AddressKind.Safe(world.state), 101))
            }
        }
        val importing =
            ImportSigningRequest(world.codec, ObserveSafe(slow, world.accounts), world.recovery, world.queue)
        val request = world.request(usdcTransfer(4), world.carol)

        val imported = assertIs<SigningRequestImport.Imported>(importing(world.codec.encode(request)))

        assertEquals(101, imported.safe.blockNumber)
        assertEquals(38_000, currentTime, "the wait did not cut off the read it was waiting on")
    }

    @Test
    fun aSignatureIsMadeAgainstTheStateItsCheckVerifiedNotAReadingSince() = runTest {
        val request = world.request(usdcTransfer(4))
        val checked = assertIs<SafeTxSignCheck.Ready>(sign.check(request, world.alice.address))
        // The Safe's reading changes after the check: the signature still stands on what was checked.
        world.state = world.state.copy(nonce = 9)

        val signed = assertIs<SafeTxSigning.Signed>(sign.sign(checked, unlock))

        assertEquals(4, checked.state.nonce)
        assertEquals(listOf(world.alice.address), signed.request.signers)
        assertEquals(listOf(world.alice.address to request.hash), world.signer.asked)
    }

    @Test
    fun aCheckThatRefusesAsksForNoKey() = runTest {
        world.kind.value = KindReading.Unavailable(ReadUnavailable.NO_ENGINE)

        assertEquals(
            SafeTxSignCheck.Refused(SafeTxRefusal.SafeUnavailable(ReadUnavailable.NO_ENGINE)),
            sign.check(world.request(usdcTransfer(4)), world.alice.address),
        )
        assertEquals(emptyList(), world.signer.asked)
    }

    @Test
    fun noEngineAndAnAnchorTheLightClientRefusesAreAnsweredAtOnce() = runTest {
        val text = world.codec.encode(world.request(usdcTransfer(4)))
        for (reason in listOf(ReadUnavailable.NO_ENGINE, ReadUnavailable.STALE_ANCHOR)) {
            world.kind.value = KindReading.Unavailable(reason)
            assertEquals(SigningRequestImport.Refused(SafeTxRefusal.SafeUnavailable(reason)), import(text))
        }
        assertEquals(0, currentTime, "asking again would change nothing: not waited for")
    }

    @Test
    fun theCheckBeforeSigningWaitsOutAnUnprovenReadingToo() = runTest {
        val request = world.request(usdcTransfer(4))
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        launch {
            delay(4.seconds)
            world.kind.value = KindReading.Verified(AddressKind.Safe(world.state), 101)
        }

        assertIs<SafeTxSigning.Signed>(sign(request, world.alice.address, unlock))
        assertEquals(4_000, currentTime)
    }

    @Test
    fun aTextThatIsNoRequestForThisNetworkIsRefusedAsTheCodecSays() = runTest {
        world.codec.texts["v2"] = SigningRequestDecoding.UnknownVersion(2)
        world.codec.texts["mainnet"] = SigningRequestDecoding.UnknownChain(1)

        assertEquals(SigningRequestImport.Refused(SafeTxRefusal.Malformed), import("hello"))
        assertEquals(SigningRequestImport.Refused(SafeTxRefusal.UnknownVersion(2)), import("v2"))
        assertEquals(SigningRequestImport.Refused(SafeTxRefusal.WrongChain(1)), import("mainnet"))
    }

    @Test
    fun aRequestImportedForOneSafeIsRefusedWhenItIsAnotherSafesAndNothingIsKept() = runTest {
        val text = world.codec.encode(world.request(usdcTransfer(4), world.carol))
        val other = Address.of("0x" + "6b".repeat(20))

        assertEquals(
            SigningRequestImport.Refused(SafeTxRefusal.OtherSafe(world.safe)),
            import(text, safe = other.onSepolia),
        )
        assertEquals(emptyList(), world.queue.requests.value)
        assertIs<SigningRequestImport.Imported>(import(text, safe = world.safe.onSepolia))
    }

    @Test
    fun aDelegateCallIsShownForWhatItIsAndNeverSigned() = runTest {
        val tx = SafeTx(
            usdc.address,
            UInt256.ZERO,
            Erc20.transferData(world.recipient, UInt256.ONE),
            SafeOperation.DELEGATECALL,
            UInt256.ZERO,
            UInt256.ZERO,
            UInt256.ZERO,
            Address.ZERO,
            Address.ZERO,
            nonce = 4,
        )
        val request = world.request(tx)

        val imported = assertIs<SigningRequestImport.Imported>(import(world.codec.encode(request)))
        assertTrue(imported.tx.delegateCall)
        assertEquals(SafeTxAction.DelegateCall(usdc.address, "a9059cbb", 68), imported.tx.action)

        assertEquals(SafeTxSigning.Refused(SafeTxRefusal.DelegateCall), sign(request, world.alice.address, unlock))
        assertEquals(emptyList(), world.signer.asked, "nothing reached the key store")
    }

    @Test
    fun anOwnerHeldHereSignsTheHashComputedHere() = runTest {
        val request = world.request(usdcTransfer(4), world.carol)

        val signed = assertIs<SafeTxSigning.Signed>(sign(request, world.alice.address, unlock))

        assertEquals(listOf(world.alice.address to request.hash), world.signer.asked)
        assertEquals(listOf(world.alice.address, world.carol), signed.request.signers)
        assertTrue(signed.saved)
        assertEquals(listOf(signed.request), world.queue.requests.value)
    }

    @Test
    fun onlyACurrentOwnerSignsATransactionTheSafeHasNotPassed() = runTest {
        val request = world.request(usdcTransfer(4), world.carol)

        assertEquals(
            SafeTxSigning.Refused(SafeTxRefusal.NotAnOwner(world.stranger)),
            sign(request, world.stranger, unlock),
        )
        assertEquals(
            SafeTxSigning.Refused(SafeTxRefusal.AlreadySigned(world.carol)),
            sign(request, world.carol, unlock),
        )
        assertEquals(
            SafeTxSigning.Refused(SafeTxRefusal.NonceUsed(4)),
            sign(world.request(usdcTransfer(3)), world.alice.address, unlock),
        )
        assertEquals(emptyList(), world.signer.asked)

        world.signer.answer = Signing.WrongPassword
        assertEquals(SafeTxSigning.NotSigned(Signing.WrongPassword), sign(request, world.alice.address, unlock))
        assertEquals(emptyList(), world.queue.requests.value)
    }

    @Test
    fun onlyCheckedSignaturesAreQueuedWithANewOne() = runTest {
        // Carried in from no check at all: a stranger's, and one said to be Carol's that is not.
        val unchecked = world.request(usdcTransfer(4), world.stranger)
        val notCarols = SafeSignature.Ecdsa(world.carol, unchecked.signatures.single().signature)
        val request = unchecked.withSignatures(listOf(notCarols))

        val signed = assertIs<SafeTxSigning.Signed>(sign(request, world.alice.address, unlock))

        assertEquals(listOf(world.alice.address), signed.request.signers)
        assertEquals(listOf(signed.request), world.queue.requests.value)
    }

    @Test
    fun aForgedSignatureInTheOwnersNameNeitherStandsForItNorKeepsItFromSigning() = runTest {
        val strangers = world.request(usdcTransfer(4), world.stranger).signatures.single().signature
        val forged = SigningRequest.of(
            Network.SEPOLIA,
            world.safe,
            usdcTransfer(4),
            listOf(SafeSignature.Ecdsa(world.alice.address, strangers)),
        )

        val signed = assertIs<SafeTxSigning.Signed>(sign(forged, world.alice.address, unlock))

        assertEquals(world.request(usdcTransfer(4), world.alice.address), signed.request)
    }

    @Test
    fun aSignatureOfAnOwnerRemovedSinceIsNeitherSharedNorShown() = runTest {
        world.queue.save(world.request(usdcTransfer(4), world.carol))
        world.state = world.state.copy(owners = listOf(world.alice.address, world.bob.address))

        val signed = assertIs<SafeTxSigning.Signed>(sign(world.request(usdcTransfer(4)), world.alice.address, unlock))
        val queued = assertIs<SafeTxQueueReading.Loaded>(
            ObserveSafeTxQueue(world.queue, world.observeSafe, world.recovery, Network.SEPOLIA)(world.safe).first(),
        )

        // Shared with Carol's, another device would refuse the whole request.
        assertEquals(listOf(world.alice.address), signed.request.signers)
        assertEquals(listOf(world.alice.address), queued.transactions.single().request.signers)
    }

    @Test
    fun aRequestQueuedFarAheadDoesNotPushAProposalBehindIt() = runTest {
        // Imported with no signature at an absurd nonce: it can only run once the gap is filled.
        world.queue.save(world.request(usdcTransfer(1_000, amount = 4_000_000)))
        world.queue.save(world.request(usdcTransfer(4)))

        val proposal = assertIs<SafeTxProposing.Proposed>(
            propose(world.safe, world.recipient, TransferAsset.Erc20Token(usdc), UInt256.of(2_000_000)),
        ).proposal

        assertEquals(5, proposal.request.nonce)
        assertEquals(1, proposal.queuedAhead)
        assertEquals(UInt256.of(1_500_000), proposal.queuedAmount, "the one at 4 alone")
    }

    @Test
    fun alternativesQueuedAtOneNonceHoldBackOnlyTheMostOneOfThemMoves() = runTest {
        // Two competing transfers at nonce 4: one of them runs, never both.
        world.queue.save(world.request(usdcTransfer(4, amount = 3_000_000)))
        world.queue.save(world.request(usdcTransfer(4, amount = 1_000_000)))

        val proposal = assertIs<SafeTxProposing.Proposed>(
            propose(world.safe, world.recipient, TransferAsset.Erc20Token(usdc), UInt256.of(2_000_000)),
        ).proposal

        assertEquals(5, proposal.request.nonce)
        assertEquals(1, proposal.queuedAhead)
        assertEquals(UInt256.of(3_000_000), proposal.queuedAmount, "the larger of the two, not their sum")
        assertEquals(UInt256.ZERO, proposal.balanceAfter)
    }

    @Test
    fun aQueuedSaleOfEtherMovesTheSafesEtherNotItsWeth() = runTest {
        val weth = Token(Address.of("0x" + "ee".repeat(20)), "WETH", "Wrapped Ether", 18, icon = null)
        val deployment = UniswapV3Deployment(
            factory = Address.of("0x" + "fa".repeat(20)),
            quoterV2 = Address.of("0x" + "0e".repeat(20)),
            swapRouter02 = Address.of("0x" + "50".repeat(20)),
            weth9 = weth.address,
        )
        val sale = SwapRouter02.swap(
            deployment,
            TransferAsset.Native,
            TransferAsset.Erc20Token(usdc),
            SwapRoute.direct(weth.address, usdc.address, FeeTier.MEDIUM),
            amountIn = UInt256.of(4_000_000),
            amountOutMinimum = UInt256.ONE,
            account = world.safe,
            deadline = 1_792_419_600,
        )
        world.queue.save(world.request(SafeTx.call(sale.to, sale.value, sale.data, 4)))

        // The swap wraps its value through WETH9: the Safe's own 5,000,000 WETH units stay its own.
        val proposal = assertIs<SafeTxProposing.Proposed>(
            propose(world.safe, world.recipient, TransferAsset.Erc20Token(weth), UInt256.of(2_000_000)),
        ).proposal
        assertEquals(UInt256.ZERO, proposal.queuedAmount)
        val ether = assertIs<SafeTxProposing.Proposed>(
            propose(world.safe, world.recipient, TransferAsset.Native, UInt256.ONE),
        ).proposal
        assertEquals(UInt256.of(4_000_000), ether.queuedAmount, "the value it sends")
    }

    @Test
    fun aSignatureThatRecoversToAnotherAddressIsNeverKept() = runTest {
        world.signer.signAs = world.bob.address

        assertEquals(
            SafeTxSigning.Refused(SafeTxRefusal.BadSignature(world.alice.address)),
            sign(world.request(usdcTransfer(4)), world.alice.address, unlock),
        )
        assertEquals(emptyList(), world.queue.requests.value)
    }

    @Test
    fun aProposalSaysWhenTheSafeWasCheckedBeforeItsBalanceIsRead() = runTest {
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        launch {
            delay(10.seconds)
            world.kind.value = KindReading.Verified(AddressKind.Safe(world.state), 101)
        }
        var checkedAt: Long? = null

        assertIs<SafeTxProposing.Proposed>(
            propose(world.safe, world.recipient, TransferAsset.Native, UInt256.ONE) { checkedAt = currentTime },
        )

        assertEquals(10_000, checkedAt, "the proposal's age runs from here, after the Safe's wait")
    }

    @Test
    fun aSignatureCheckGivenLessTimeThanTheBudgetGivesUpInIt() = runTest {
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)

        assertEquals(
            SafeTxSignCheck.Refused(SafeTxRefusal.SafeUnavailable(ReadUnavailable.UNPROVEN)),
            sign.check(world.request(usdcTransfer(4)), world.alice.address, budget = 7.seconds),
        )
        assertEquals(7_000, currentTime)
    }

    @Test
    fun aTransferIsProposedAtTheSafesNonceAndRunFromTheSafe() = runTest {
        val amount = UInt256.of(100_000_000_000_000_000) // 0.1 ETH of the Safe's 1

        val proposal = assertIs<SafeTxProposing.Proposed>(
            propose(world.safe, world.recipient, TransferAsset.Native, amount),
        ).proposal

        assertEquals(SafeTx.call(world.recipient, amount, ByteArray(0), nonce = 4), proposal.request.tx)
        assertEquals(emptyList(), proposal.request.signatures)
        assertEquals(SafeTxAction.NativeTransfer(world.recipient, amount), proposal.tx.action)
        assertEquals(UInt256.of(900_000_000_000_000_000), proposal.balanceAfter)
        assertEquals(SafeCallSimulation.Ran(100), proposal.simulation)
        assertEquals(world.safe to world.recipient, world.inputs.simulated.single().let { it.first to it.second })
        assertEquals(emptyList(), world.queue.requests.value, "nothing is queued until it is signed")
    }

    @Test
    fun aTokenTransferIsProposedAfterTheQueueAndWhatItMovesIsNotThisOnesToMove() = runTest {
        world.queue.save(world.request(usdcTransfer(4, amount = 3_000_000), world.carol))
        world.queue.save(world.request(usdcTransfer(2))) // passed: pruned
        val usdcAsset = TransferAsset.Erc20Token(usdc)

        assertEquals(
            SafeTxProposing.InsufficientToken,
            propose(world.safe, world.recipient, usdcAsset, UInt256.of(2_500_000)),
            "5 USDC held, 3 queued before it: 2.5 more would revert once those run",
        )
        val proposal = assertIs<SafeTxProposing.Proposed>(
            propose(world.safe, world.recipient, usdcAsset, UInt256.of(2_000_000)),
        ).proposal

        assertEquals(5, proposal.request.nonce, "after the one queued at 4")
        assertEquals(1, proposal.queuedAhead)
        assertEquals(UInt256.of(3_000_000), proposal.queuedAmount)
        assertEquals(UInt256.ZERO, proposal.balanceAfter)
        assertEquals(listOf(4L), world.queue.requests.value.map { it.nonce })
    }

    @Test
    fun aProposalThatCanNeverWorkIsRefusedBeforeAnythingIsSigned() = runTest {
        val usdcAsset = TransferAsset.Erc20Token(usdc)
        assertEquals(
            SafeTxProposing.ToZeroAddress,
            propose(world.safe, Address.ZERO, TransferAsset.Native, UInt256.ONE),
        )
        assertEquals(SafeTxProposing.ToTokenContract, propose(world.safe, usdc.address, usdcAsset, UInt256.ONE))
        assertEquals(
            SafeTxProposing.ToSafeItself,
            propose(world.safe, world.safe, TransferAsset.Native, UInt256.ONE),
        )
        assertEquals(SafeTxProposing.ToSafeItself, propose(world.safe, world.safe, usdcAsset, UInt256.ONE))
        assertEquals(SafeTxProposing.NothingToSend, propose(world.safe, world.recipient, usdcAsset, UInt256.ZERO))
        assertEquals(
            SafeTxProposing.InsufficientFunds,
            propose(world.safe, world.recipient, TransferAsset.Native, UInt256.of(2_000_000_000_000_000_000)),
        )

        world.inputs.simulation = Simulation.Succeeded("0".repeat(64), 101)
        assertEquals(SafeTxProposing.Reverts(101), propose(world.safe, world.recipient, usdcAsset, UInt256.ONE))
        world.inputs.simulation = Simulation.Reverted(102)
        assertEquals(SafeTxProposing.Reverts(102), propose(world.safe, world.recipient, usdcAsset, UInt256.ONE))

        world.tokens = TokenBalanceReading.NotAToken
        assertEquals(SafeTxProposing.NotAToken, propose(world.safe, world.recipient, usdcAsset, UInt256.ONE))

        // A balance last verified at an older head may have changed: nothing is proposed on it.
        val syncing = SafeTxProposing.Refused(SafeTxRefusal.Syncing)
        world.tokens = TokenBalanceReading.LastKnown(TokenUnits.of("5000000"), 99, asOf = null)
        assertEquals(syncing, propose(world.safe, world.recipient, usdcAsset, UInt256.ONE))
        world.native = BalanceReading.LastKnown(Wei.of("1000000000000000000"), 99, asOf = 1_792_100_000)
        assertEquals(syncing, propose(world.safe, world.recipient, TransferAsset.Native, UInt256.ONE))
        world.native = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
        world.tokens = TokenBalanceReading.Verified(TokenUnits.of("5000000"), 100)
        world.kind.value = KindReading.Verified(AddressKind.Eoa, 100)
        assertEquals(
            SafeTxProposing.Refused(SafeTxRefusal.NotASafe),
            propose(world.safe, world.recipient, usdcAsset, UInt256.ONE),
        )
    }

    @Test
    fun anEngineThatWillNotRunACallFromTheSafeLeavesItNotSimulatedAndSaysSo() = runTest {
        world.inputs.simulation = Simulation.Refused

        val proposal = assertIs<SafeTxProposing.Proposed>(
            propose(world.safe, world.recipient, TransferAsset.Native, UInt256.ONE),
        ).proposal

        assertEquals(SafeCallSimulation.NotRun, proposal.simulation)
    }

    @Test
    fun theQueueShowsWhatTheSafeHasNotPassedInNonceOrder() = runTest {
        world.queue.save(world.request(usdcTransfer(6)))
        world.queue.save(world.request(usdcTransfer(4)))
        world.queue.save(world.request(usdcTransfer(3)))

        val reading = assertIs<SafeTxQueueReading.Loaded>(
            ObserveSafeTxQueue(world.queue, world.observeSafe, world.recovery, Network.SEPOLIA)(world.safe).first(),
        )

        assertEquals(4, reading.safeNonce)
        assertEquals(listOf(4L, 6L), reading.transactions.map { it.request.nonce })

        assertTrue(RemoveQueuedSafeTx(world.queue)(world.request(usdcTransfer(6)).hash))
        assertEquals(listOf(4L, 3L), world.queue.requests.value.map { it.nonce }, "6 removed; 3 left to a prune")
    }

    @Test
    fun aRequestIsSharedAsTheCodecWritesIt() {
        val request = world.request(usdcTransfer(4), world.carol)
        val text = EncodeSigningRequest(world.codec)(request)

        assertEquals(SigningRequestDecoding.Decoded(request), world.codec.decode(text))
    }
}

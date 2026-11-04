package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.data.keys.toHex
import eth.serotine.data.safe.SafeDeployments
import eth.serotine.data.safe.SafeSingleton
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.kotlincrypto.hash.sha3.Keccak256

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedAddressKindsTest {

    private val address = Address.of("0x" + "ab".repeat(20))
    private val delegate = Address.of("0x63c0c19a282a1b52b07dd5a65b58948a07dae32b")
    private val chain = FakeChain()

    private val proxyCode = "608060405273ffffffffffffffffffffffffffffffffffffffff600054167f"
    private val singleton = Address.of("0x41675c099f32341bf84bfc5382af534df5c7461a")
    private val safes = SafeDeployments.Table(
        proxyCodeHashes = setOf(Keccak256().digest(proxyCode.fromHex()).toHex()),
        singletons = mapOf(singleton to SafeSingleton("1.4.1", l2 = false)),
        creation = null,
    )

    /** What the adapter's debug log said. */
    private val logged = mutableListOf<String>()

    private fun TestScope.kinds() =
        VerifiedAddressKinds(
            Network.SEPOLIA,
            chain,
            StandardTestDispatcher(testScheduler),
            4.seconds,
            safes,
            chain.clock(testScheduler),
            logged::add,
        )

    @Test
    fun codeTellsAnAccountADelegatedAccountAndAContractApart() {
        assertEquals(AddressKind.Eoa, VerifiedAddressKinds.kindOf(""))
        assertEquals(
            AddressKind.DelegatedEoa(delegate),
            VerifiedAddressKinds.kindOf("ef0100" + delegate.hex.removePrefix("0x")),
        )
        // Not a designator: one byte short, one byte long, another prefix.
        assertEquals(AddressKind.Contract, VerifiedAddressKinds.kindOf("ef0100" + "ab".repeat(19)))
        assertEquals(AddressKind.Contract, VerifiedAddressKinds.kindOf("ef0100" + "ab".repeat(21)))
        assertEquals(AddressKind.Contract, VerifiedAddressKinds.kindOf("ef0200" + "ab".repeat(20)))
        assertEquals(AddressKind.Contract, VerifiedAddressKinds.kindOf("6080604052"))
    }

    @Test
    fun aProvenKindIsLabelledWithItsBlock() = runTest {
        chain.snapshot = running(head = 9)
        chain.codes[address] = ProvenCode("ef0100" + delegate.hex.removePrefix("0x"), blockNumber = 9)

        assertEquals(
            KindReading.Verified(AddressKind.DelegatedEoa(delegate), 9),
            kinds().observeKind(Network.SEPOLIA, address).first(),
        )
    }

    @Test
    fun aKindIsNeverShownFromBeforeWhileTheHeadIsTooOld() = runTest {
        chain.snapshot = running(head = 9)
        chain.codes[address] = ProvenCode("ef0100" + delegate.hex.removePrefix("0x"), blockNumber = 9)
        val kinds = kinds()
        kinds.observeKind(Network.SEPOLIA, address).first()

        // Only balances are offered as last known; what an address is gates signing.
        chain.snapshot = running(head = 9, headAgeMs = 60_000)
        assertEquals(KindReading.Syncing, kinds.observeKind(Network.SEPOLIA, address).first())
    }

    @Test
    fun whatCannotBeProvenIsSaidSo() = runTest {
        chain.snapshot = running(head = 9)
        assertEquals(
            KindReading.Unavailable(ReadUnavailable.UNPROVEN),
            kinds().observeKind(Network.SEPOLIA, address).first(),
        )

        chain.snapshot = EngineSnapshot.Booting
        assertEquals(KindReading.Syncing, kinds().observeKind(Network.SEPOLIA, address).first())

        assertEquals(
            KindReading.Unavailable(ReadUnavailable.NO_ENGINE),
            VerifiedAddressKinds(Network.SEPOLIA, null, StandardTestDispatcher(testScheduler), 4.seconds, safes)
                .observeKind(Network.SEPOLIA, address)
                .first(),
        )
    }

    @Test
    fun anEngineStateThatCannotBeReadIsNotVerifiedNotSyncing() = runTest {
        // The engine's state crosses JNA: a broken native library fails with an Error. As for
        // balances (ProvenReads), that poll says not verified, and an address is never called
        // an account on the strength of a state nobody could read.
        chain.snapshot = running(head = 9)
        chain.failure = Error("native library broken")

        assertEquals(
            KindReading.Unavailable(ReadUnavailable.UNPROVEN),
            kinds().observeKind(Network.SEPOLIA, address).first(),
        )
    }

    @Test
    fun codeIsAcceptedOnlyWhenItHashesToTheProvenValue() {
        val contract = "6080604052".fromHex()
        val contractHash = "0x" + Keccak256().digest(contract).toHex()
        var fetched = 0
        val fetch = { fetched++; contract }

        // An absent account and the empty hash have no code to fetch.
        assertEquals(ProvenCode("", 5), provenCode(exists = false, null, 5, fetchCode = fetch))
        val emptyHash = "0x" + EMPTY_CODE_HASH.uppercase()
        assertEquals(ProvenCode("", 5), provenCode(exists = true, emptyHash, 5, fetchCode = fetch))
        assertEquals(0, fetched)

        assertEquals(ProvenCode("6080604052", 5), provenCode(exists = true, contractHash, 5, fetchCode = fetch))
        // The head moved and the code with it: not the proven code, so unproven.
        assertEquals(null, provenCode(exists = true, "0x" + "ab".repeat(32), 5, fetchCode = fetch))
        assertEquals(null, provenCode(exists = true, contractHash, 5) { null })
        assertEquals(null, provenCode(exists = true, null, 5, fetchCode = fetch))
    }

    // --- Safes.

    private val owner1 = Address.of("0x" + "11".repeat(20))
    private val owner2 = Address.of("0x" + "22".repeat(20))
    private val module = Address.of("0x" + "33".repeat(20))
    private val sentinel = Address.of("0x" + "0".repeat(39) + "1")

    private fun word(address: Address) = "0".repeat(24) + address.hex.removePrefix("0x")
    private fun word(n: Long) = n.toString(16).padStart(64, '0')

    /** A set-up 2-of-2 Safe at [address] with one module, as its storage holds it. */
    private fun safeStorage(threshold: Long = 2) {
        chain.codes[address] = ProvenCode(proxyCode, 9)
        chain.slots[Triple(address, 0L, null)] = word(singleton)
        chain.slots[Triple(address, 2L, sentinel)] = word(owner1)
        chain.slots[Triple(address, 2L, owner1)] = word(owner2)
        chain.slots[Triple(address, 2L, owner2)] = word(sentinel)
        chain.slots[Triple(address, 3L, null)] = word(2)
        chain.slots[Triple(address, 4L, null)] = word(threshold)
        chain.slots[Triple(address, 5L, null)] = word(7)
        chain.slots[Triple(address, 1L, sentinel)] = word(module)
        chain.slots[Triple(address, 1L, module)] = word(sentinel)
    }

    @Test
    fun aPinnedProxyOfAPinnedSingletonIsASafeWithItsOwnersThresholdNonceAndModules() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()

        assertEquals(
            KindReading.Verified(
                AddressKind.Safe(
                    SafeState("1.4.1", l2 = false, listOf(owner1, owner2), 2, 7, listOf(module), guard = null),
                ),
                9,
            ),
            kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing },
        )
    }

    @Test
    fun aSafesGuardIsRead() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        val guard = Address.of("0x" + "44".repeat(20))
        chain.storage[address to "4a204f620c8c5ccdca3fd54d003badd85ba500436a431f0cbda4f558c93c34c8"] = word(guard)

        val reading = kinds().observeKind(
            Network.SEPOLIA,
            address,
        ).first { it !is KindReading.Syncing } as KindReading.Verified
        assertEquals(guard, (reading.kind as AddressKind.Safe).state.guard)
    }

    @Test
    fun whatIsNotQuiteASafeIsAnotherContract() = runTest {
        chain.snapshot = running(head = 9)
        val kinds = kinds()
        suspend fun kind() =
            (kinds.observeKind(
                Network.SEPOLIA,
                address,
            ).first { it !is KindReading.Syncing } as KindReading.Verified).kind

        // A pinned proxy whose singleton is not pinned.
        safeStorage()
        chain.slots[Triple(address, 0L, null)] = word(Address.of("0x" + "99".repeat(20)))
        assertEquals(AddressKind.Contract, kind())

        // A threshold above its owners: not a set-up Safe.
        chain.snapshot = running(head = 10)
        safeStorage(threshold = 3)
        assertEquals(AddressKind.Contract, kind())

        // An owner list that does not lead back to the sentinel.
        chain.snapshot = running(head = 11)
        safeStorage()
        chain.slots[Triple(address, 2L, owner2)] = word(owner1)
        assertEquals(AddressKind.Contract, kind())
    }

    @Test
    fun aReadTheHeadKeepsMovingUnderOrWithAnUnprovenSlotIsUnproven() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        chain.slotBlocks[Triple(address, 5L, null)] = 10 // at every attempt
        assertEquals(
            KindReading.Unavailable(ReadUnavailable.UNPROVEN),
            kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing },
        )

        chain.slotBlocks.clear()
        chain.unprovenSlots += Triple(address, 2L, owner1)
        assertEquals(
            KindReading.Unavailable(ReadUnavailable.UNPROVEN),
            kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing },
        )
    }

    @Test
    fun aSafeWithoutModulesHasNoneButOneWhoseModuleListWasNeverWrittenIsNoSafe() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        chain.slots[Triple(address, 1L, sentinel)] = word(sentinel) // set up, empty: what setup writes
        chain.slots.remove(Triple(address, 1L, module))
        val kinds = kinds()
        suspend fun kind() =
            (kinds.observeKind(
                Network.SEPOLIA,
                address,
            ).first { it !is KindReading.Syncing } as KindReading.Verified).kind
        assertEquals(emptyList(), (kind() as AddressKind.Safe).state.modules)

        // Setup always writes the sentinel: a set-up Safe without it was written by other means.
        chain.snapshot = running(head = 20)
        chain.slotBlock = 20
        chain.slots.remove(Triple(address, 1L, sentinel))
        assertEquals(AddressKind.Contract, kind())
    }

    @Test
    fun aCountThatIsNotTheListsOrNoSmallNumberIsNoSafe() = runTest {
        chain.snapshot = running(head = 9)
        val kinds = kinds()
        suspend fun kind() =
            (kinds.observeKind(
                Network.SEPOLIA,
                address,
            ).first { it !is KindReading.Syncing } as KindReading.Verified).kind

        safeStorage()
        chain.slots[Triple(address, 3L, null)] = word(3) // three owners counted, two listed
        assertEquals(AddressKind.Contract, kind())

        chain.snapshot = running(head = 20)
        chain.slotBlock = 20
        safeStorage()
        chain.slots[Triple(address, 5L, null)] = "f".repeat(64) // a nonce no Safe reaches
        assertEquals(AddressKind.Contract, kind())
    }

    @Test
    fun aGuardWordThatIsNoAddressIsNoSafeAndAnOlderSafesModuleGuardSlotIsNothing() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        val planted = Address.of("0x" + "55".repeat(20))
        chain.storage[address to VerifiedAddressKinds.MODULE_GUARD_SLOT.toHex()] = word(planted)
        val kinds = kinds()
        suspend fun kind() =
            (kinds.observeKind(
                Network.SEPOLIA,
                address,
            ).first { it !is KindReading.Syncing } as KindReading.Verified).kind
        // 1.4.1 never consults a module guard: a word there is no protection.
        assertEquals(null, (kind() as AddressKind.Safe).state.moduleGuard)

        chain.snapshot = running(head = 20)
        chain.slotBlock = 20
        chain.storage[address to VerifiedAddressKinds.GUARD_SLOT.toHex()] = "ff".repeat(32)
        assertEquals(AddressKind.Contract, kind())
    }

    @Test
    fun aReadTheHeadMovedUnderIsMadeAgainAtOnce() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        chain.proofTakes = 500.milliseconds // the code and two reads, well within the budget
        // The fourth proof of the first read lands at the next head; the second read is all there.
        chain.slotBlockOfCall = { call -> if (call < 3) 9 else 10 }

        val reading = kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing }

        assertEquals(10, (reading as KindReading.Verified).blockNumber)
        assertEquals(true, reading.kind is AddressKind.Safe)
    }

    @Test
    fun storageFromBeforeTheCodesBlockIsNotTakenForIt() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        chain.codes[address] = ProvenCode(proxyCode, 12) // the code at 12, the slots at 9

        assertEquals(
            KindReading.Unavailable(ReadUnavailable.UNPROVEN),
            kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing },
        )
    }

    @Test
    fun slotsWithOneBlockLabelButProvenAgainstTwoAttestedSlotsAreNoOneState() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        // Every label says 9 (a peer's report can), but the proofs matched attested slots 18 and 19
        // by turns: two states, never shown as one. Every read straddles, so it ends unproven.
        chain.slotAnchorOfCall = { call -> 18L + call % 2 }

        assertEquals(
            KindReading.Unavailable(ReadUnavailable.UNPROVEN),
            kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing },
        )

        // One attested slot for all: one state, shown with its label.
        chain.slotAnchorOfCall = { 19 }
        val reading = kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing }
        assertEquals(9, (reading as KindReading.Verified).blockNumber)
    }

    @Test
    fun storageOfAnAttestedSlotBeforeTheCodesIsNotTakenForItWhateverTheLabelsSay() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        chain.codes[address] = ProvenCode(proxyCode, 9, ProofAnchor.Attested(20)) // the code matched slot 20
        chain.slotAnchorOfCall = { 19 } // the storage 19, both labelled 9

        assertEquals(
            KindReading.Unavailable(ReadUnavailable.UNPROVEN),
            kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing },
        )
    }

    @Test
    fun storageWalkedByHeaderChainAgainstCodeMatchedAtASlotIsNoOrderAndNoReading() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        // The code matched attested slot 20; every slot was walked from block 9. Nothing verified
        // orders a walk against a match, and no label decides it: a straddle, read again, unproven.
        chain.codes[address] = ProvenCode(proxyCode, 9, ProofAnchor.Attested(20))
        chain.slotWalked = 9

        assertEquals(
            KindReading.Unavailable(ReadUnavailable.UNPROVEN),
            kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing },
        )
    }

    @Test
    fun aCodeWalkAgainstSlotsMatchedAtOneSlotIsProvenAgainWithinTheRead() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        chain.proofTakes = 500.milliseconds // two reads and a code proof, well within the budget
        // The first code proof is a walk of block 9, the slots all matched attested slot 9: the code
        // is what straddles, so it is proven again, and this time it matched slot 9 as well.
        chain.codeOfCall = { call ->
            ProvenCode(proxyCode, 9, if (call == 0) ProofAnchor.Walked(9) else ProofAnchor.Attested(9))
        }

        val reading = kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing }

        assertEquals(9, (reading as KindReading.Verified).blockNumber)
        assertEquals(true, reading.kind is AddressKind.Safe)
        assertEquals(2, chain.codeCalls, "the code proven once more, within the one read")
    }

    @Test
    fun aCodeProofWithNoAnchorIsNeverTakenForTheSlotsState() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        // A stateRootMatch whose matched slot is unknown (matchedBeaconSlot < 0): no anchor, so
        // nothing orders it, however often it is proven again.
        chain.codeOfCall = { ProvenCode(proxyCode, 9, anchor = null) }

        assertEquals(
            KindReading.Unavailable(ReadUnavailable.UNPROVEN),
            kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing },
        )
        // Ten proofs of 1 s a read: the first read, the code again, and a second read the budget
        // cuts off before its last proof.
        assertEquals(2, chain.codeCalls, "proven again for each read the budget leaves time for, and no more")
    }

    @Test
    fun aSlotWhoseProofFailsOnceIsAskedForAgainWithinTheRead() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        // The third storage proof (the owner list's second entry) comes back null once: a slow peer.
        chain.slotFailsOnCall = { call -> call == 2 }

        val reading = kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing }

        assertEquals(9, (reading as KindReading.Verified).blockNumber)
        assertEquals(listOf(owner1, owner2), (reading.kind as AddressKind.Safe).state.owners)
        assertEquals(1, chain.codeCalls, "one read: only the slot was asked for again")
        assertEquals(true, logged.any { "slot 2 under ${owner1.hex} unproven, asked again" in it }, "$logged")
    }

    @Test
    fun aSlotThatStaysUnprovenFailsTheReadWhichIsMadeAgainAfterAPause() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        // The 3rd, 4th and 5th storage proofs fail: all three tries of one slot in the first read.
        chain.slotFailsOnCall = { call -> call in 2..4 }

        val reading = kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing }

        assertEquals(true, (reading as KindReading.Verified).kind is AddressKind.Safe)
        assertEquals(true, logged.any { "read 1: slot 2 under ${owner1.hex} unproven after 3 tries" in it }, "$logged")
        assertEquals(true, logged.any { "read in 2 reads" in it }, "$logged")
    }

    @Test
    fun aReadTheHeadMovedUnderThreeTimesIsMadeAgainUntilItsSlotsAgree() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        chain.proofTakes = 250.milliseconds
        // Nine storage proofs a read: each of the first three reads sees the head move halfway
        // through, the fourth is all at block 12. (Three reads were all a Safe got before the budget.)
        chain.slotBlockOfCall = { call -> if (call >= 27) 12 else 9L + call / 9 + (if (call % 9 >= 4) 1 else 0) }

        val reading = kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing }

        assertEquals(12, (reading as KindReading.Verified).blockNumber)
        assertEquals(true, reading.kind is AddressKind.Safe)
        assertEquals(true, logged.any { "read in 4 reads" in it }, "$logged")
        assertEquals(true, logged.any { "read 1: straddled" in it }, "$logged")
    }

    @Test
    fun aSafeTheHeadKeepsMovingUnderIsUnprovenOnceTheBudgetIsSpent() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        // Every other proof at the next block: every read straddles.
        chain.slotBlockOfCall = { call -> 9L + call % 2 }
        val start = chain.clock(testScheduler).markNow()

        assertEquals(
            KindReading.Unavailable(ReadUnavailable.UNPROVEN),
            kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing },
        )
        // The budget, the code's first proof in it, and no proof asked for past it.
        assertEquals(VerifiedAddressKinds.SAFE_READ_BUDGET, start.elapsedNow())
        assertEquals(true, logged.any { "not read after 2 reads" in it }, "$logged")
    }

    @Test
    fun thePausesBetweenFailedReadsSpendTheBudgetToo() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        chain.unprovenSlots += Triple(address, 0L, null) // the singleton slot, never proven
        val start = chain.clock(testScheduler).markNow()

        assertEquals(
            KindReading.Unavailable(ReadUnavailable.UNPROVEN),
            kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing },
        )
        // The code's proof (1 s), then four reads of three tries (3 s) each followed by a 1 s pause,
        // and a fifth whose last try ends the budget at 20 s.
        assertEquals(VerifiedAddressKinds.SAFE_READ_BUDGET, start.elapsedNow())
        assertEquals(true, logged.any { "read 5: out of time at slot 0" in it }, "$logged")
        assertEquals(true, logged.any { "not read after 5 reads" in it }, "$logged")
    }

    @Test
    fun theFirstCodeProofIsAskedForAgainLikeEveryOther() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        // Null, then a thrown JNA error, then the code.
        chain.codeOfCall = { call ->
            when (call) {
                0 -> null
                1 -> throw Error("native call failed")
                else -> ProvenCode(proxyCode, 9, ProofAnchor.Attested(9))
            }
        }

        val reading = kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing }

        assertEquals(true, (reading as KindReading.Verified).kind is AddressKind.Safe)
        assertEquals(3, chain.codeCalls)
        assertEquals(true, logged.any { "the code unproven, asked again (try 2 of 3)" in it }, "$logged")
    }

    @Test
    fun aProofTheEngineThrowsForIsAskedForAgainLikeOneThatCameBackNull() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        // A JNA call that fails with an Error, once.
        chain.slotThrowsOnCall = { call -> if (call == 2) Error("native call failed") else null }

        val reading = kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing }

        assertEquals(true, (reading as KindReading.Verified).kind is AddressKind.Safe)
        assertEquals(1, chain.codeCalls, "one read: only the slot was asked for again")
        assertEquals(true, logged.any { "the engine threw Error" in it }, "$logged")
    }

    @Test
    fun aReadCancelledMidwayStopsAtItsNextEngineCall() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        lateinit var reader: Job
        chain.onSlotCall = { call -> if (call == 3) reader.cancel() }

        reader = launch { kinds().observeKind(Network.SEPOLIA, address).collect {} }
        advanceUntilIdle()

        assertTrue(reader.isCancelled)
        assertEquals(4, chain.slotCalls, "the fourth proof was the last one asked for")
    }

    @Test
    fun aSubscriberWaitingOnAReadWhoseMakerIsCancelledMakesItItselfAndIsNotToldUnproven() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        // The first read's owner-list proof fails all three tries: the read pauses before it is made again.
        chain.slotFailsOnCall = { call -> call in 2..4 }
        val kinds = kinds()
        val first = launch { kinds.observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing } }
        runCurrent() // the first subscriber's read is in its pause
        val second = async { kinds.observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing } }
        runCurrent() // the second waits on it

        first.cancel() // a one-shot check that gave up, a screen left

        val reading = second.await()
        assertEquals(true, (reading as KindReading.Verified).kind is AddressKind.Safe, "$reading")
    }

    @Test
    fun walksOfOneBlockAreOneStateWhateverHeadTheyWereWalkedTo() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        // The code and every slot walked from block 9, each to whichever head was attested then.
        chain.codes[address] = ProvenCode(proxyCode, 9, ProofAnchor.Walked(9))
        chain.slotWalked = 9

        val reading = kinds().observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing }
        assertEquals(9, (reading as KindReading.Verified).blockNumber)
        assertEquals(true, reading.kind is AddressKind.Safe)
    }

    @Test
    fun aSafeIsProvenAgainOnlyEveryFewHeads() = runTest {
        chain.snapshot = running(head = 9)
        safeStorage()
        val kinds = kinds()
        kinds.observeKind(Network.SEPOLIA, address).first { it !is KindReading.Syncing }
        val asked = chain.proofRequests.size

        // The next head: the reading stands, nothing is asked again.
        chain.snapshot = running(head = 10)
        val standing = kinds.observeKind(
            Network.SEPOLIA,
            address,
        ).first { it !is KindReading.Syncing } as KindReading.Verified
        assertEquals(9, standing.blockNumber)
        assertEquals(asked, chain.proofRequests.size)

        // Five heads on: read again, and labelled with its new block.
        chain.snapshot = running(head = 14)
        chain.slotBlock = 14
        val again = kinds.observeKind(
            Network.SEPOLIA,
            address,
        ).first { it !is KindReading.Syncing } as KindReading.Verified
        assertEquals(14, again.blockNumber)
    }

    @Test
    fun theGuardSlotsAreTheHashesSafeKeepsThemAt() {
        assertEquals(
            "4a204f620c8c5ccdca3fd54d003badd85ba500436a431f0cbda4f558c93c34c8",
            VerifiedAddressKinds.GUARD_SLOT.toHex(),
        )
        assertEquals(
            "b104e0b93118902c651344349b610029d694cfdec91c589c91ebafbcd0289947",
            VerifiedAddressKinds.MODULE_GUARD_SLOT.toHex(),
        )
    }
}

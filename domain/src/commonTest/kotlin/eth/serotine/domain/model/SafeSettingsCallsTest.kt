package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SafeSettingsCallsTest {

    private val safe = Address.of("0x" + "5a".repeat(20))
    private val alice = Address.of("0x" + "11".repeat(20))
    private val bob = Address.of("0x" + "22".repeat(20))
    private val carol = Address.of("0x" + "33".repeat(20))

    private fun word(address: Address) = "0".repeat(24) + address.hex.removePrefix("0x")

    private fun word(value: Long) = value.toString(16).padStart(64, '0')

    private fun decoded(data: ByteArray) = SafeTxAction.of(safe, SafeTx.call(safe, UInt256.ZERO, data, nonce = 1))

    @Test
    fun eachCallIsTheSafesFunctionWithItsWords() {
        val sentinel = SafeSettingsCalls.SENTINEL
        // The selectors as the Safe contracts' ABI lists them.
        assertEquals("0d582f13" + word(carol) + word(2), SafeSettingsCalls.addOwner(carol, 2).toHex())
        assertEquals(
            "f8dc5dd9" + word(alice) + word(bob) + word(1),
            SafeSettingsCalls.removeOwner(alice, bob, 1).toHex(),
        )
        assertEquals(
            "e318b52b" + word(sentinel) + word(alice) + word(carol),
            SafeSettingsCalls.swapOwner(sentinel, alice, carol).toHex(),
        )
        assertEquals("694e80c3" + word(3), SafeSettingsCalls.changeThreshold(3).toHex())
        assertEquals("e009cfde" + word(sentinel) + word(bob), SafeSettingsCalls.disableModule(sentinel, bob).toHex())
        assertEquals("e19a9dd9" + word(Address.ZERO), SafeSettingsCalls.setGuard(null).toHex())
        assertEquals("f08a0323" + word(Address.ZERO), SafeSettingsCalls.setFallbackHandler(null).toHex())
    }

    @Test
    fun whatIsBuiltDecodesBackToTheChangeItWasBuiltFor() {
        assertEquals(
            SafeTxAction.SettingsChange(SafeSettingsChange.OwnerAdded(carol, 2)),
            decoded(SafeSettingsCalls.addOwner(carol, 2)),
        )
        assertEquals(
            SafeTxAction.SettingsChange(SafeSettingsChange.OwnerRemoved(bob, 1)),
            decoded(SafeSettingsCalls.removeOwner(alice, bob, 1)),
        )
        assertEquals(
            SafeTxAction.SettingsChange(SafeSettingsChange.OwnerSwapped(alice, carol)),
            decoded(SafeSettingsCalls.swapOwner(SafeSettingsCalls.SENTINEL, alice, carol)),
        )
        assertEquals(
            SafeTxAction.SettingsChange(SafeSettingsChange.ThresholdChanged(3)),
            decoded(SafeSettingsCalls.changeThreshold(3)),
        )
        assertEquals(
            SafeTxAction.SettingsChange(SafeSettingsChange.ModuleDisabled(bob)),
            decoded(SafeSettingsCalls.disableModule(SafeSettingsCalls.SENTINEL, bob)),
        )
        assertEquals(SafeTxAction.SettingsChange(SafeSettingsChange.GuardSet(null)), decoded(SafeSettingsCalls.setGuard(null)))
    }

    @Test
    fun theEntryBeforeOneIsTheSentinelForTheFirstAndItsNeighbourOtherwise() {
        val owners = listOf(alice, bob, carol)
        assertEquals(SafeSettingsCalls.SENTINEL, SafeSettingsCalls.previous(owners, alice))
        assertEquals(bob, SafeSettingsCalls.previous(owners, carol))
        assertNull(SafeSettingsCalls.previous(owners, safe))
    }
}

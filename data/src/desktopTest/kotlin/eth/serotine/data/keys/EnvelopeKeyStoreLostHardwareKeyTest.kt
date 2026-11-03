package eth.serotine.data.keys

import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStoreDescription
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Acceptance test of finding N3 of the security audit of 2026-11-01 (docs/keys-and-signing.md,
 * *A lost hardware key is said, not guessed around*): the key store asks the hardware secret whether
 * its key exists before anything could create one. [EnvelopeKeyStore.enableBiometrics] reads the
 * secret's protection first, which on Android (AndroidKeystoreSecret, `protection` lazy over
 * `key()`) generates the Keystore HMAC key when it is missing, so a lost key is remade beside the
 * envelopes sealed with the old one and the loss reads as "wrong password" from then on. Ignored
 * while the finding is open; remove the @Ignore with the fix.
 */
class EnvelopeKeyStoreLostHardwareKeyTest {

    private val directory: File = Files.createTempDirectory("serotine-keys").toFile()
    private val hardhat = Mnemonic("test test test test test test test test test test test junk")
    private val password = WalletPassword("correct horse battery")

    /**
     * A hardware secret shaped like Android's: reading [protection] makes the key when it is
     * missing, and a remade key mixes differently, so envelopes sealed before refuse every password.
     */
    private class RemakingHardware : HardwareSecret {
        override val tag: String = "fake-hw-v1"
        override val everyGuessNeedsDevice: Boolean = true
        var exists = true
        var remade = false
        private var key: Byte = 42

        override val protection: KeyProtection
            get() {
                if (!exists) {
                    exists = true
                    remade = true
                    key = 43
                }
                return KeyProtection.HARDWARE
            }

        override fun isProvisioned(): Boolean = exists

        override fun mix(input: ByteArray): ByteArray =
            ByteArray(32) { (input[it % input.size].toInt() xor key.toInt()).toByte() }
    }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Ignore // Audit 2026-11-01, N3: open.
    @Test
    fun turningOnBiometricsNeverRemakesALostHardwareKey() = runTest {
        val hardware = RemakingHardware()
        val store = EnvelopeKeyStore(
            directory.path,
            hardware,
            StandardTestDispatcher(testScheduler),
            biometricKey = FakeBiometricKey(),
        )
        assertIs<KeyImport.Stored>(store.importMnemonic(hardhat, password))
        hardware.exists = false // the Keystore lost its key: an OS upgrade, a cleared key store
        assertEquals(KeyStoreDescription.Unavailable, store.describe(), "the loss is said")

        assertEquals(BiometricChange.Failed, store.enableBiometrics(password, "Turn on biometric unlock"))

        assertFalse(hardware.remade, "no new key is made beside envelopes sealed with the lost one")
        assertEquals(KeyStoreDescription.Unavailable, store.describe(), "the loss is still said, not hidden as a wrong password")
    }
}

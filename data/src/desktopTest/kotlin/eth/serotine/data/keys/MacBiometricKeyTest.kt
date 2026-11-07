package eth.serotine.data.keys

import eth.serotine.domain.repository.BiometricUnusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runs on a Mac; the tests that make a key only where Touch ID can be offered now (a finger
 * enrolled, the sensor reachable) and the Secure Enclave can be used by this process, skipped
 * everywhere else (CI's Linux, a Mac without Touch ID or with its lid closed). Nothing here shows
 * the Touch ID prompt, apart from the tests that need a finger, which are ignored by default: run
 * them by hand at the Mac.
 */
class MacBiometricKeyTest {

    private val directory: File = Files.createTempDirectory("serotine-biometric").toFile()
    private val stateFile = directory.resolve(MacBiometricKey.STATE_FILE_NAME)
    private val key = MacBiometricKey(stateFile.path)

    @BeforeTest
    fun onlyOnAMac() = assumeTrue("not a Mac", MacSecurity.onMac)

    /** For the tests that make a key: a finger enrolled, the sensor reachable, the enclave usable. */
    private fun assumeTouchId() {
        assumeTrue("Touch ID is not offered here (${key.unavailable()})", key.unavailable() == null)
        assumeTrue("no usable Secure Enclave here", MacSecureEnclaveSecret.usable())
    }

    @AfterTest
    fun cleanUp() {
        key.delete()
        directory.deleteRecursively()
    }

    @Test
    fun noKeyIsOffAndNotInvalidated() = runBlocking {
        assertFalse(key.exists())
        assertFalse(key.isInvalidated())
        assertEquals(BiometricUnwrap.Unusable(BiometricUnusable.OFF), key.unwrap(ByteArray(16), "a reason"))
    }

    @Test
    fun theKeyIsMadeInTheEnclaveAndKeptOnlyAsItsHandle() {
        assumeTouchId()
        assertTrue(key.create())

        assertTrue(key.exists())
        assertFalse(key.isInvalidated(), "made under the fingers enrolled now")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(stateFile.toPath())))
        val dek = ByteArray(32) { it.toByte() }
        // Wrapping uses the public half: no finger needed, and the wrap is no copy of the DEK.
        val wrapped = key.wrap(dek)
        assertFalse(wrapped.toHex().contains(dek.toHex()))
        assertFalse(dek.toHex() in stateFile.readText())
    }

    @Test
    fun aNewKeyReplacesTheOldOneAndDeletingItLeavesNothing() {
        assumeTouchId()
        assertTrue(key.create())
        val first = stateFile.readText()

        assertTrue(key.create())
        assertTrue(first != stateFile.readText(), "a key of its own")

        key.delete()
        assertFalse(key.exists())
        assertFalse(stateFile.exists())
    }

    @Test
    fun anotherSetOfFingersInvalidatesTheKeyBeforeAnyPrompt() = runBlocking {
        assumeTouchId()
        assertTrue(key.create())
        // As if a finger had been added since: the set's fingerprint is not the one the key was made under.
        val state = Json.decodeFromString(BiometricKeyState.serializer(), stateFile.readText())
        val changed = BiometricKeyState(state.version, state.key, "00".repeat(32))
        stateFile.writeText(Json.encodeToString(BiometricKeyState.serializer(), changed))

        assertTrue(key.isInvalidated())
        assertEquals(BiometricUnwrap.Unusable(BiometricUnusable.INVALIDATED), key.unwrap(key.wrap(ByteArray(32)), "x"))
    }

    @Test
    fun aDamagedStateReadsAsOff() = runBlocking {
        stateFile.writeText("not json")

        assertEquals(BiometricUnwrap.Unusable(BiometricUnusable.OFF), key.unwrap(ByteArray(16), "a reason"))
    }

    @Ignore // Shows the Touch ID prompt: touch the sensor within the minute.
    @Test
    fun aFingerOpensTheWrap() = runBlocking {
        assumeTouchId()
        assertTrue(key.create())
        val dek = ByteArray(32) { (it * 7).toByte() }

        val opened = withTimeout(60_000) { key.unwrap(key.wrap(dek), "test Punicus's Touch ID unlock") }

        assertIs<BiometricUnwrap.Dek>(opened)
        assertContentEquals(dek, opened.bytes)
    }

    @Ignore // Shows the Touch ID prompt for a moment: leave it alone, the test dismisses it.
    @Test
    fun aCancelledCallerDismissesThePrompt() = runBlocking {
        assumeTouchId()
        assertTrue(key.create())
        val wrapped = key.wrap(ByteArray(32))

        val unwrap = async(Dispatchers.IO) { key.unwrap(wrapped, "test that Punicus dismisses its prompt") }
        delay(1_500)
        unwrap.cancel()

        // The prompt is gone, and with it the blocked decryption: the call ends, not the user.
        withTimeout(5_000) { unwrap.join() }
        assertTrue(unwrap.isCancelled)
    }

    @Ignore // Shows the Touch ID prompt: press its Cancel button, not the sensor.
    @Test
    fun aPromptTheUserDismissesIsACancel() = runBlocking {
        assumeTouchId()
        assertTrue(key.create())
        val wrapped = key.wrap(ByteArray(32))

        val outcome = withTimeout(60_000) { key.unwrap(wrapped, "test: press Cancel") }

        assertEquals(BiometricUnwrap.Cancelled, outcome)
    }
}

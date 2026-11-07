package eth.serotine.data.outbox

import eth.serotine.data.keys.FakeHardware
import eth.serotine.data.keys.MacSecurity
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Which enclave failures lose the desktop's outbox ([enclaveSecret]): only a state made on another
 * Mac. Every other failure may pass, and the outbox may hold a transaction already on the network.
 */
class DesktopOutboxTest {

    private val directory = Path(Files.createTempDirectory("serotine-desktop-outbox").toString())
    private val state = Path(directory, "outbox-enclave.json")
    private val madeOn = Path(directory, "outbox-mac")
    private val hardware = FakeHardware()

    private fun write(path: Path, text: String) = SystemFileSystem.sink(path).buffered().use { it.writeString(text) }

    private fun read(path: Path) = SystemFileSystem.source(path).buffered().use { it.readString() }

    private fun secret(thisMac: String?, usable: Boolean = true) =
        enclaveSecret(state, madeOn, thisMac, usable = { usable }, secretAt = { hardware })

    @AfterTest
    fun cleanUp() {
        SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(directory)
    }

    @Test
    fun aStateMadeOnAnotherMacIsALostKey() {
        write(state, "{}")
        write(madeOn, "old mac")

        assertFailsWith<OutboxKeyGone> { secret(thisMac = "this mac") }
        assertFailsWith<OutboxKeyGone>("whether this Mac has an enclave or not") {
            secret(thisMac = "this mac", usable = false)
        }
    }

    @Test
    fun anEnclaveThatDoesNotAnswerHereIsNoLostKey() {
        write(state, "{}")
        write(madeOn, "this mac")

        val failure = assertFailsWith<IllegalStateException> { secret(thisMac = "this mac", usable = false) }
        assertFalse(failure is OutboxKeyGone, "it may answer next time: the outbox stays unreadable, never deleted")
        assertSame(hardware, secret(thisMac = "this mac"), "a state that fails to open later is the outbox's to say")
    }

    @Test
    fun aMacNotKnownOnEitherSideIsNoLostKey() {
        write(state, "{}")
        // No record beside the state: made before records were kept, or never written.
        assertSame(hardware, secret(thisMac = "this mac"))
        // This Mac's identity not said this time.
        write(madeOn, "old mac")
        assertSame(hardware, secret(thisMac = null))
        assertFailsWith<IllegalStateException> { secret(thisMac = null, usable = false) }.also {
            assertFalse(it is OutboxKeyGone)
        }
    }

    @Test
    fun theMacIsRecordedBeforeAStateIsMade() {
        write(madeOn, "old mac")

        assertSame(hardware, secret(thisMac = "this mac"))
        assertEquals("this mac", read(madeOn), "a record without its state is replaced")

        // A state made now is this Mac's, and opens on it.
        write(state, "{}")
        assertSame(hardware, secret(thisMac = "this mac"))
    }

    @Test
    fun aRecordIsDroppedWhenThisMacIsNotKnownBeforeAStateIsMade() {
        write(madeOn, "old mac")

        assertSame(hardware, secret(thisMac = null))
        assertFalse(SystemFileSystem.exists(madeOn), "never left to name another Mac for the state made next")

        write(state, "{}")
        assertSame(hardware, secret(thisMac = "this mac"), "unknown, so never lost")
    }

    @Test
    fun aMacIsNamedByAHashOfItsIdentity() {
        val id = macId("00000000-1111-2222-3333-444444444444")
        assertEquals(64, id.length)
        assertFalse("00000000" in id)
        assertEquals(id, macId("00000000-1111-2222-3333-444444444444"))
        assertNotEquals(id, macId("00000000-1111-2222-3333-444444444445"))
    }

    @Test
    fun thisMacSaysTheSameIdentityEveryTime() {
        val security = MacSecurity.instance
        assumeTrue("not a Mac", security != null)
        val uuid = assertNotNull(security!!.platformUuid())
        assertTrue(uuid.isNotBlank())
        assertEquals(uuid, security.platformUuid())
    }
}

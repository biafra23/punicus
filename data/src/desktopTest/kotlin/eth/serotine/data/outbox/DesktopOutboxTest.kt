package eth.serotine.data.outbox

import eth.serotine.data.keys.FakeHardware
import eth.serotine.data.keys.MacSecurity
import eth.serotine.domain.repository.KeyProtection
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
    private val thisMac = macId("this Mac's IOPlatformUUID")
    private val oldMac = macId("the old Mac's IOPlatformUUID")

    /** A secret whose state opens here when [opens] says so. */
    private var opens = true
    private val hardware = FakeHardware(claims = { check(opens) { "does not open here" }; KeyProtection.HARDWARE })

    private fun write(path: Path, text: String) = SystemFileSystem.sink(path).buffered().use { it.writeString(text) }

    private fun read(path: Path) = SystemFileSystem.source(path).buffered().use { it.readString() }

    private fun secret(mac: String? = thisMac, usable: Boolean = true) =
        enclaveSecret(state, madeOn, mac, usable = { usable }, secretAt = { hardware })

    private fun assertNotLost(block: () -> Unit) {
        val failure = assertFailsWith<IllegalStateException> { block() }
        assertFalse(failure is OutboxKeyGone, "it may pass: the outbox stays unreadable, never deleted")
    }

    @AfterTest
    fun cleanUp() {
        SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(directory)
    }

    @Test
    fun aStateMadeOnAnotherMacThatDoesNotOpenHereIsALostKey() {
        write(state, "{}")
        write(madeOn, oldMac)
        opens = false

        assertFailsWith<OutboxKeyGone> { secret() }
        assertFailsWith<OutboxKeyGone>("whether this Mac has an enclave or not") { secret(usable = false) }
    }

    @Test
    fun aStateThatOpensHereIsThisMacsWhateverItsRecordSaid() {
        write(state, "{}")
        write(madeOn, oldMac)

        assertSame(hardware, secret())
        assertEquals(thisMac, read(madeOn), "the record now says so")
    }

    @Test
    fun aStateWithNoRecordIsRecordedOnceItOpensHere() {
        write(state, "{}")

        assertSame(hardware, secret())
        assertEquals(thisMac, read(madeOn))
    }

    @Test
    fun anEnclaveThatDoesNotAnswerOnTheMacThatMadeTheStateIsNoLostKey() {
        write(state, "{}")
        write(madeOn, thisMac)

        assertNotLost { secret(usable = false) }
        // A state that fails to open on its own Mac is the outbox's to say, as unreadable.
        opens = false
        assertSame(hardware, secret())
    }

    @Test
    fun aMacNotKnownOnEitherSideIsNoLostKey() {
        write(state, "{}")
        opens = false
        // No record beside the state: made before records were kept, or while IOKit did not answer.
        assertNotLost { secret() }
        // A record that names no Mac: damaged, or another version's.
        write(madeOn, "old mac")
        assertNotLost { secret() }
        // This Mac's identity not said this time.
        write(madeOn, oldMac)
        assertSame(hardware, secret(mac = null), "the outbox's to say, as unreadable")
        assertNotLost { secret(mac = null, usable = false) }
    }

    @Test
    fun theMacIsRecordedBeforeAStateIsMade() {
        write(madeOn, oldMac)

        assertSame(hardware, secret())
        assertEquals(thisMac, read(madeOn), "a record without its state is replaced")

        // A state made now is this Mac's: never lost here, even when it does not open.
        write(state, "{}")
        opens = false
        assertSame(hardware, secret())
    }

    @Test
    fun aRecordIsDroppedWhenThisMacIsNotKnownBeforeAStateIsMade() {
        write(madeOn, oldMac)

        assertSame(hardware, secret(mac = null))
        assertFalse(SystemFileSystem.exists(madeOn), "never left to name another Mac for the state made next")

        write(state, "{}")
        opens = false
        assertNotLost { secret() }
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

package eth.serotine.domain.usecase

import eth.serotine.domain.repository.AmountsHiddenSetting
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Whether the screens hide amounts, over a fake port. */
class HiddenAmountsTest {

    private val setting = object : AmountsHiddenSetting {
        val hidden = MutableStateFlow(false)
        var failSaves = false
        var saves = 0

        override fun observe(): Flow<Boolean> = hidden
        override suspend fun set(hidden: Boolean): Boolean {
            // A save takes its time, as a file's does: another toggle may start meanwhile.
            yield()
            if (failSaves) return false
            saves++
            this.hidden.value = hidden
            return true
        }
    }

    @Test
    fun eachToggleFlipsWhatIsStoredOrSaysItWasNotSaved() = runTest {
        val toggle = ToggleAmountsHidden(setting)
        assertFalse(ObserveAmountsHidden(setting)().first())

        assertTrue(toggle())
        assertTrue(ObserveAmountsHidden(setting)().first())
        assertTrue(toggle())
        assertFalse(ObserveAmountsHidden(setting)().first())

        setting.failSaves = true
        assertFalse(toggle())
        assertFalse(ObserveAmountsHidden(setting)().first())
    }

    @Test
    fun twoQuickTogglesHideAndShowAgain() = runTest {
        val toggle = ToggleAmountsHidden(setting)
        val first = async { toggle() }
        val second = async { toggle() }
        assertTrue(first.await() && second.await())

        assertEquals(2, setting.saves)
        assertFalse(setting.hidden.value, "hidden, then shown again: never saved the same twice")
    }
}

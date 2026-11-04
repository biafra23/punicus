package eth.serotine.domain.usecase

import eth.serotine.domain.model.InterfaceMode
import eth.serotine.domain.repository.InterfaceModeSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The view the screens are drawn in, over a fake port. */
class InterfaceModesTest {

    private val setting = object : InterfaceModeSetting {
        val mode = MutableStateFlow(InterfaceMode.SIMPLE)
        var failSaves = false

        override fun observe(): Flow<InterfaceMode> = mode
        override suspend fun set(mode: InterfaceMode): Boolean {
            if (failSaves) return false
            this.mode.value = mode
            return true
        }
    }

    @Test
    fun theViewIsSavedOrSaysItWasNot() = runTest {
        assertEquals(InterfaceMode.SIMPLE, ObserveInterfaceMode(setting)().first())

        assertTrue(SetInterfaceMode(setting)(InterfaceMode.DETAILED))
        assertEquals(InterfaceMode.DETAILED, ObserveInterfaceMode(setting)().first())

        setting.failSaves = true
        assertFalse(SetInterfaceMode(setting)(InterfaceMode.SIMPLE))
        assertEquals(InterfaceMode.DETAILED, ObserveInterfaceMode(setting)().first())
    }
}

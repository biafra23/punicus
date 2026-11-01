package eth.serotine.domain.usecase

import eth.serotine.domain.repository.AmountsHiddenSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Whether the screens hide amounts, over a fake port. */
class HiddenAmountsTest {

    private val setting = object : AmountsHiddenSetting {
        val hidden = MutableStateFlow(false)
        var failSaves = false

        override fun observe(): Flow<Boolean> = hidden
        override suspend fun set(hidden: Boolean): Boolean {
            if (failSaves) return false
            this.hidden.value = hidden
            return true
        }
    }

    @Test
    fun theChoiceIsSavedOrSaysItWasNot() = runTest {
        assertFalse(ObserveAmountsHidden(setting)().first())

        assertTrue(SetAmountsHidden(setting)(true))
        assertTrue(ObserveAmountsHidden(setting)().first())

        setting.failSaves = true
        assertFalse(SetAmountsHidden(setting)(false))
        assertTrue(ObserveAmountsHidden(setting)().first())
    }
}

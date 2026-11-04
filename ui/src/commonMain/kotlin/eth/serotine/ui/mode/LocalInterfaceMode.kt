package eth.serotine.ui.mode

import androidx.compose.runtime.staticCompositionLocalOf
import eth.serotine.domain.model.InterfaceMode

/**
 * The view the screens are drawn in (docs/simple-ui.md), which `App` provides. Only a Route reads
 * it, to pick `Simple<Feature>Screen` or `<Feature>Screen` for the same UiState; a screen never
 * does (`.claude/rules/ui.md`, *Two views*). Simple mode outside `App`, as in every build.
 */
val LocalInterfaceMode = staticCompositionLocalOf { InterfaceMode.SIMPLE }

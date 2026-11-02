package eth.serotine.ui.mnemonicimport

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import eth.serotine.domain.model.Mnemonic
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

/**
 * Acceptance test of finding S7 of the security audit of 2026-11-01: Compose offers Copy and Cut in
 * a text field's selection toolbar unless the field's visual transformation is the password one,
 * which is also what gives the field the Password semantics; a password keyboard type alone does
 * not. The seed-phrase field has neither, so the words can be copied to the system clipboard
 * (keyboard clipboard histories, cross-device clipboard sync). Ignored while the finding is open;
 * remove the @Ignore with the fix (a password transformation with a reveal toggle, or a toolbar
 * that offers Paste alone, which must then also set the Password semantics or adapt this test).
 */
class MnemonicFieldClipboardTest {

    @get:Rule
    val rule = createComposeRule()

    @Ignore("Audit 2026-11-01, S7: open.")
    @Test
    fun theSeedPhraseFieldIsAPasswordFieldSoItsToolbarOffersNoCopy() {
        rule.setContent {
            SerotineTheme {
                MnemonicImportScreen(
                    state = MnemonicImportUiState.Entering(Mnemonic("one two three")),
                    onPhraseChange = {},
                    onPasswordChange = {},
                    onRepeatChange = {},
                    onImport = {},
                    onBack = {},
                    onDone = {},
                )
            }
        }

        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }
}

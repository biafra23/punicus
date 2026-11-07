package eth.serotine.ui.security

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.PasswordCost
import eth.serotine.ui.components.protectionText
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// Settings → Security in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. SecurityScreenPreviewsTest renders each one in securityScreenPreviews, at the end.
// The password is made up. A preview has no window to keep out of screen capture, so the
// password field's preview shows the line saying so, which a device that blocks capture does not.

private val off = SecurityUiState.Ready(
    password = "Set",
    protection = KeyProtection.HARDWARE.protectionText,
    biometrics = BiometricRow("Off: signing asks for the wallet password.", on = false, canChange = true),
)

private val on = off.copy(
    biometrics = BiometricRow(
        "On: a fingerprint or a face can unlock a key to sign. The wallet password still works.",
        on = true,
        canChange = true,
    ),
    message = "Biometric unlock is on.",
)

@Composable
private fun SecurityPreview(state: SecurityUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SecurityScreen(
            state = state,
            onBiometricsChange = {},
            onPasswordChange = {},
            onConfirm = {},
            onCancel = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun SecurityOffPreview() = SecurityPreview(off)

@Preview
@Composable
private fun SecurityOffDarkPreview() = SecurityPreview(off, darkTheme = true)

@Preview
@Composable
private fun SecurityAskingPasswordPreview() = SecurityPreview(
    off.copy(enabling = PasswordPrompt(WalletPassword("made-up preview password"))),
)

@Preview
@Composable
private fun SecurityWrongPasswordPreview() =
    SecurityPreview(off.copy(enabling = PasswordPrompt(error = "That is not the wallet password.")))

@Preview
@Composable
private fun SecurityTurningOnPreview() = SecurityPreview(
    off.copy(enabling = PasswordPrompt(WalletPassword("made-up preview password"), working = true), changing = true),
)

@Preview
@Composable
private fun SecurityOnPreview() = SecurityPreview(on)

@Preview
@Composable
private fun SecurityInvalidatedPreview() = SecurityPreview(
    off.copy(
        biometrics = BiometricRow(
            "Stopped working: the enrolled fingerprints or faces changed. Turn it off, then on again, to use them.",
            on = true,
            canChange = true,
        ),
    ),
)

@Preview
@Composable
private fun SecurityTurningOffPreview() = SecurityPreview(on.copy(changing = true, message = null))

@Preview
@Composable
private fun SecurityWeakOnlyPreview() = SecurityPreview(
    off.copy(
        biometrics = BiometricRow(
            "This device's biometrics are not strong enough to unlock a key (Class 3 is needed), so it is not offered.",
            on = false,
            canChange = false,
        ),
    ),
)

@Preview
@Composable
private fun SecurityDesktopPreview() = SecurityPreview(
    off.copy(
        protection = KeyProtection.PASSWORD_ONLY.protectionText,
        biometrics = BiometricRow(
            "Not on this device yet: Punicus has no biometric unlock here, so signing asks for the wallet password.",
            on = false,
            canChange = false,
        ),
    ),
)

@Preview
@Composable
private fun SecurityLessMemoryPreview() = SecurityPreview(
    off.copy(
        protection = KeyStoreDescription.Ready(
            passwordSet = true,
            KeyProtection.SOFTWARE_KEY_STORE,
            passwordCost = PasswordCost.LESS_MEMORY,
        ).protectionText,
    ),
)

@Preview
@Composable
private fun SecurityNotEnoughMemoryPreview() = SecurityPreview(
    off.copy(
        message = "Not enough free memory to open the keys: close other apps and try again. Biometric unlock is off.",
    ),
)

@Preview
@Composable
private fun SecurityNoKeysPreview() = SecurityPreview(
    SecurityUiState.Ready(
        password = "Not set yet: the first key you store sets it",
        protection = null,
        biometrics = BiometricRow(
            "Off. Store a key first: biometric unlock opens stored keys.",
            on = false,
            canChange = false,
        ),
    ),
)

@Preview
@Composable
private fun SecurityLoadingPreview() = SecurityPreview(SecurityUiState.Loading)

@Preview
@Composable
private fun SecurityUnavailablePreview() = SecurityPreview(SecurityUiState.Unavailable)

/** Every preview above, which SecurityScreenPreviewsTest renders: a new preview goes in here too. */
internal val securityScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("off") { SecurityOffPreview() },
    ScreenPreview("off-dark") { SecurityOffDarkPreview() },
    ScreenPreview("asking-password") { SecurityAskingPasswordPreview() },
    ScreenPreview("wrong-password") { SecurityWrongPasswordPreview() },
    ScreenPreview("turning-on") { SecurityTurningOnPreview() },
    ScreenPreview("on") { SecurityOnPreview() },
    ScreenPreview("invalidated") { SecurityInvalidatedPreview() },
    ScreenPreview("turning-off") { SecurityTurningOffPreview() },
    ScreenPreview("weak-only") { SecurityWeakOnlyPreview() },
    ScreenPreview("desktop") { SecurityDesktopPreview() },
    ScreenPreview("less-memory") { SecurityLessMemoryPreview() },
    ScreenPreview("not-enough-memory") { SecurityNotEnoughMemoryPreview() },
    ScreenPreview("no-keys") { SecurityNoKeysPreview() },
    ScreenPreview("loading") { SecurityLoadingPreview() },
    ScreenPreview("unavailable") { SecurityUnavailablePreview() },
)

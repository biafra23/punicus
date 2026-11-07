package eth.serotine.ui.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.BiometricUnavailable
import eth.serotine.domain.repository.BiometricUnlock
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.DisableBiometricUnlock
import eth.serotine.domain.usecase.EnableBiometricUnlock
import eth.serotine.ui.components.protectionText
import eth.serotine.ui.security.SecurityUiState.Ready
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings → Security's ViewModel (docs/wallet-ux.md, screen 21): what the key store says about
 * the wallet password and biometric unlock, and turning biometric unlock on (with the wallet
 * password, which opens each stored key once so the biometric key can wrap it, then one
 * fingerprint or face check that proves it) and off (no password: it deletes a way in). One of
 * the ViewModels the [WalletPassword] passes through: it keeps it in its state only while its
 * field is shown, takes it out of the state at the tap that uses it (the call holds it from then
 * on), and never logs or saves it.
 */
class SecurityViewModel(
    private val describeKeyStore: DescribeKeyStore,
    private val enableBiometricUnlock: EnableBiometricUnlock,
    private val disableBiometricUnlock: DisableBiometricUnlock,
) : ViewModel() {

    private val state = MutableStateFlow<SecurityUiState>(SecurityUiState.Loading)

    val uiState: StateFlow<SecurityUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
    }

    /** The switch moved to [on]: turning on asks for the password, turning off happens at once. */
    fun onBiometricsChange(on: Boolean) {
        val ready = state.value as? Ready ?: return
        if (ready.changing || ready.enabling != null || !ready.biometrics.canChange || on == ready.biometrics.on) return
        if (on) {
            state.value = ready.copy(enabling = PasswordPrompt(), message = null)
            return
        }
        state.value = ready.copy(changing = true, message = null)
        viewModelScope.launch {
            val result = safely { disableBiometricUnlock() }
            // Here a failure means the key may still be there: the refresh shows where it stands.
            refresh(if (result == BiometricChange.Failed) NOT_TURNED_OFF else messageOf(result))
        }
    }

    /** The password field now reads [text]. */
    fun onPasswordChange(text: String) = state.update { current ->
        val prompt = (current as? Ready)?.enabling
        if (prompt == null || prompt.working) current else current.copy(enabling = PasswordPrompt(WalletPassword(text)))
    }

    /** Turns biometric unlock on with the typed password. */
    fun onConfirm() {
        val ready = state.value as? Ready ?: return
        val prompt = ready.enabling ?: return
        if (!prompt.canConfirm) return
        // The password leaves the state now, as the signing screens' does: it is held here for the
        // one call, and the field shows nothing while it runs. changing outlives the field: a
        // screen left and come back to still shows it running.
        val password = prompt.password
        val working = prompt.copy(password = WalletPassword.EMPTY, working = true, error = null)
        state.value = ready.copy(enabling = working, changing = true)
        viewModelScope.launch {
            val result = safely { enableBiometricUnlock(password, CONFIRMATION) }
            if (result == BiometricChange.WrongPassword) {
                state.update { current ->
                    when {
                        current !is Ready -> current
                        current.enabling != null ->
                            current.copy(enabling = PasswordPrompt(error = WRONG_PASSWORD), changing = false)
                        // The field went with the screen: the outcome is said instead.
                        else -> current.copy(changing = false, message = NOT_TURNED_ON_WRONG_PASSWORD)
                    }
                }
            } else {
                refresh(messageOf(result))
            }
        }
    }

    /** The password is not given after all. */
    fun onCancel() = state.update { current ->
        if (current is Ready && current.enabling?.working == false) current.copy(enabling = null) else current
    }

    /**
     * The screen stopped being shown: the password goes (CLAUDE.md, *Trust*; rule 8). A change
     * still running finishes, and its outcome is said when it lands.
     */
    fun onHidden() = state.update { current -> if (current is Ready) current.copy(enabling = null) else current }

    override fun onCleared() {
        state.update { current -> if (current is Ready) current.copy(enabling = null) else current }
    }

    private suspend fun refresh(message: String? = null) {
        val description = try {
            describeKeyStore()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KeyStoreDescription.Unavailable // the port promises not to throw; the backstop
        }
        state.value = when (description) {
            KeyStoreDescription.Unavailable -> SecurityUiState.Unavailable
            is KeyStoreDescription.Ready -> Ready(
                password = if (description.passwordSet) "Set" else "Not set yet: the first key you store sets it",
                protection = description.protectionText.takeIf { description.passwordSet },
                biometrics = rowOf(description.biometrics, description.passwordSet),
                message = message,
            )
        }
    }

    private suspend fun safely(change: suspend () -> BiometricChange): BiometricChange = try {
        change()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        BiometricChange.Failed // the port promises not to throw; the backstop
    }

    private fun rowOf(biometrics: BiometricUnlock, passwordSet: Boolean): BiometricRow = when (biometrics) {
        BiometricUnlock.NotOnThisDevice -> BiometricRow(NOT_ON_THIS_DEVICE, on = false, canChange = false)
        is BiometricUnlock.Unavailable -> BiometricRow(textOf(biometrics.reason), on = false, canChange = false)
        BiometricUnlock.Off -> if (passwordSet) {
            BiometricRow("Off: signing asks for the wallet password.", on = false, canChange = true)
        } else {
            BiometricRow(NO_KEYS, on = false, canChange = false)
        }
        BiometricUnlock.On -> BiometricRow(
            "On: a fingerprint or a face can unlock a key to sign. The wallet password still works.",
            on = true,
            canChange = true,
        )
        // Its key still exists: shown on, so that turning it off deletes it, and on again makes a new one.
        BiometricUnlock.Invalidated -> BiometricRow(
            "Stopped working: the enrolled fingerprints or faces changed. Turn it off, then on again, to use them.",
            on = true,
            canChange = true,
        )
    }

    private fun messageOf(change: BiometricChange): String? = when (change) {
        BiometricChange.Enabled -> "Biometric unlock is on."
        BiometricChange.Disabled -> "Biometric unlock is off, and its key is deleted from this device."
        BiometricChange.NoKeys -> NO_KEYS
        BiometricChange.NotOnThisDevice -> NOT_ON_THIS_DEVICE
        is BiometricChange.Unavailable -> textOf(change.reason)
        BiometricChange.Cancelled -> "The fingerprint or face check was dismissed, so biometric unlock is off."
        BiometricChange.NotConfirmed ->
            "The fingerprint or face check did not go through, so biometric unlock is off. Try again."
        BiometricChange.Failed ->
            "The key store failed while turning biometric unlock on. The wallet password still works."
        BiometricChange.NotEnoughMemory ->
            "Not enough free memory to open the keys: close other apps and try again. Biometric unlock is off."
        BiometricChange.WrongPassword -> null
    }

    private fun textOf(reason: BiometricUnavailable): String = when (reason) {
        BiometricUnavailable.NO_HARDWARE -> "This device has no fingerprint or face sensor Punicus can use."
        BiometricUnavailable.NOT_ENROLLED ->
            "No fingerprint or face is enrolled: add one in the system settings to use biometric unlock."
        BiometricUnavailable.WEAK_ONLY ->
            "This device's biometrics are not strong enough to unlock a key (Class 3 is needed), so it is not offered."
        BiometricUnavailable.NOT_IN_SECURE_HARDWARE ->
            "This device cannot keep the biometric key in secure hardware, so biometric unlock is not offered."
        BiometricUnavailable.NOT_ALLOWED ->
            "Biometrics are turned off for Punicus: allow Face ID for Punicus in the system settings to use it."
        BiometricUnavailable.NO_PASSCODE ->
            "This device has no passcode: set one in the system settings to use biometric unlock."
        BiometricUnavailable.LOCKED_OUT ->
            "Biometrics are locked after too many failed tries: unlock the device with its passcode, then try again."
    }

    private companion object {
        const val WRONG_PASSWORD = "That is not the wallet password."
        const val NOT_TURNED_OFF = "Biometric unlock could not be turned off. Try again."
        const val NOT_TURNED_ON_WRONG_PASSWORD =
            "That was not the wallet password, so biometric unlock was not turned on."

        /** What the system's prompt says while the new path is proven once. */
        const val CONFIRMATION = "Turn on biometric unlock for your keys"
        const val NO_KEYS = "Off. Store a key first: biometric unlock opens stored keys."
        const val NOT_ON_THIS_DEVICE =
            "Not on this device yet: Punicus has no biometric unlock here, so signing asks for the wallet password."
    }
}

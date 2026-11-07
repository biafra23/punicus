package eth.serotine.ui.components

import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.MemoryStep
import eth.serotine.domain.repository.PasswordCost

/**
 * Which password a seed screen (Import recovery phrase, Create account) asks for, as the key store
 * described itself: the password step both screens draw.
 */
sealed interface PasswordStep {

    /** The key store has not answered yet: no password field, nothing can be added. */
    data object Checking : PasswordStep

    /** This import sets the wallet password, typed twice; [protection] says, display-ready, how the seed is kept. */
    data class New(val protection: String) : PasswordStep

    /** A wallet password is set and must be given; [protection] as for [New]. */
    data class Existing(val protection: String) : PasswordStep

    /** The key store does not work on this device: nothing can be added. */
    data object Unavailable : PasswordStep
}

/**
 * How the device [this] describes protects a stored seed, display-ready: [KeyProtection.protectionText],
 * and where the password is stretched with less memory, a sentence that says so (the owner's ruling
 * of 2026-10-18).
 */
internal val KeyStoreDescription.Ready.protectionText: String
    get() = when (passwordCost) {
        PasswordCost.STANDARD -> protection.protectionText
        PasswordCost.LESS_MEMORY -> "${protection.protectionText} $LESS_MEMORY_TEXT"
    }

/** What the seed screens and Security add where the password is stretched with less memory. */
internal const val LESS_MEMORY_TEXT =
    "This device has little memory, so the password protection uses less memory and more passes."

/** What a seed screen says when Argon2id could not have the memory to protect the key. */
internal const val NOT_ENOUGH_MEMORY_TO_PROTECT =
    "Not enough free memory to protect the key: close other apps and try again."

/**
 * What a seed screen says when Argon2id could not have the memory at [step]: to protect the key,
 * or to check the password against a stored one, which an import does first; [nothing] says what
 * was not done ("Nothing was added.").
 */
internal fun notEnoughMemoryText(step: MemoryStep, nothing: String): String = when (step) {
    MemoryStep.PROTECT -> NOT_ENOUGH_MEMORY_TO_PROTECT
    MemoryStep.CHECK_PASSWORD ->
        "Not enough free memory to check the password: close other apps and try again. $nothing"
}

/**
 * What a seed screen says once a key was stored at a lighter password cost than this device's
 * own (`KeyImport.Stored.steppedDown`): the envelope keeps that cost.
 */
internal const val STEPPED_DOWN_TEXT =
    "There was not enough free memory just now, so the password protection of this key uses less memory and " +
        "more passes."

/** How [this] protects a stored seed, display-ready: what the seed screens say under the password. */
internal val KeyProtection.protectionText: String
    get() = when (this) {
        KeyProtection.HARDWARE ->
            "The seed is stored encrypted under your wallet password and a key in this device's " +
                "secure hardware: both are needed to open it, and a copy of it is useless elsewhere."
        KeyProtection.SOFTWARE_KEY_STORE ->
            "The seed is stored encrypted under your wallet password and this device's key store, " +
                "which is not in secure hardware here (an emulator, or a device without it)."
        KeyProtection.PASSWORD_ONLY ->
            "The seed is stored encrypted under your wallet password alone: Punicus uses no hardware " +
                "key store on this computer. Choose a strong password."
        KeyProtection.PASSWORD_ONLY_TPM_NOT_USED ->
            "The seed is stored encrypted under your wallet password alone: Punicus does not use this " +
                "computer's TPM yet, even if it has one. Choose a strong password."
    }

package eth.serotine.ui.safeimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.ImportSigningRequest
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.SafeTxRefusal
import eth.serotine.domain.usecase.SigningRequestImport
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.safeTxRefusalText
import eth.serotine.ui.components.simpleSafeTxRefusalText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The ViewModel of Import a request (docs/wallet-ux.md, screen 16): a signing request another
 * device shared as text, pasted here, for the Safe at [safe] on [network], and checked against the
 * chain before anything of it is believed or kept ([ImportSigningRequest]: decoded strictly, a
 * request for this Safe only, its nonce not passed, every signature a current owner's over the
 * hash computed here). While it runs the screen says it checks the Safe on chain, which can take
 * up to 45 s while the Safe's reading is made again. Every refusal is said; one that
 * passes is queued, and the screen goes to the transaction's, which says which queue it went to.
 * Scanning a QR code is not offered: the platforms' cameras are not wired in.
 */
class SafeImportViewModel(
    private val account: AccountId,
    observeAccount: ObserveAccount,
    private val importSigningRequest: ImportSigningRequest,
) : ViewModel() {
    /** The account this screen is about: its address, on its network. */
    private val safe: Address = account.address

    /** The account's network: everything here is read and sent there. */
    private val network: Network = account.network


    /** Where the import stands. */
    private sealed interface Step {
        data class Entering(
            val importing: Boolean = false,
            val error: String? = null,
            val simpleError: String? = null,
        ) : Step

        data class Imported(val hash: String) : Step
    }

    private val display = Eip55.format(safe)
    private val text = MutableStateFlow("")
    private val step = MutableStateFlow<Step>(Step.Entering())

    val uiState: StateFlow<SafeImportUiState> = combine(observeAccount(account), text, step) { lookup, text, step ->
        when (step) {
            is Step.Imported -> SafeImportUiState.Imported(network.displayName, step.hash)
            is Step.Entering -> SafeImportUiState.Entering(
                network = network.displayName,
                label = (lookup as? AccountLookup.Found)?.account?.label,
                address = display,
                text = text,
                importing = step.importing,
                error = step.error,
                simpleError = step.simpleError,
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        SafeImportUiState.Entering(network.displayName, label = null, address = display, text = ""),
    )

    /** The request's field now reads [value]. */
    fun onTextChange(value: String) {
        if ((step.value as? Step.Entering)?.importing != false) return
        text.value = value
        step.value = Step.Entering()
    }

    /** Checks the pasted request against the chain and, when it passes, queues it. */
    fun import() {
        val current = step.value as? Step.Entering ?: return
        val pasted = text.value
        if (current.importing || pasted.isBlank()) return
        step.value = Step.Entering(importing = true)
        viewModelScope.launch {
            val imported = try {
                importSigningRequest(pasted, account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The use case promises not to throw; the backstop.
                SigningRequestImport.Refused(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN))
            }
            step.update { latest ->
                if (latest != Step.Entering(importing = true)) return@update latest
                when (imported) {
                    is SigningRequestImport.Imported -> if (imported.saved) {
                        Step.Imported("0x" + imported.tx.request.hash.hex)
                    } else {
                        Step.Entering(error = NOT_SAVED)
                    }
                    is SigningRequestImport.Refused -> Step.Entering(
                        error = safeTxRefusalText(imported.refusal, network),
                        simpleError = simpleSafeTxRefusalText(imported.refusal),
                    )
                }
            }
        }
    }

    private companion object {
        const val NOT_SAVED =
            "The request passed every check, but this device's queue cannot be saved right now, so it is not " +
                "kept: import it again in a moment."
    }
}

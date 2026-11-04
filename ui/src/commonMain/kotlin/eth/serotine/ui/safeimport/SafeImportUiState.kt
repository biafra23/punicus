package eth.serotine.ui.safeimport

/**
 * Everything the screen that imports a signing request renders (docs/wallet-ux.md, screen 16,
 * Import a request; *Co-signing a Safe transaction*): the request's text as pasted, checked here
 * against the chain before anything of it is kept. Display-ready; a request is public data, so
 * nothing on this screen is secret.
 */
sealed interface SafeImportUiState {

    /** The network's name ("Sepolia"). */
    val network: String

    /**
     * The request being entered for the Safe at [address] (EIP-55), named [label] in the wallet
     * (null: not in it): [text] as pasted, [importing] while it is checked, and [error] why the
     * last one was refused, or null; [simpleError] that with no block, for simple mode, null where it
     * is [error] itself.
     */
    data class Entering(
        override val network: String,
        val label: String?,
        val address: String,
        val text: String,
        val importing: Boolean = false,
        val error: String? = null,
        val simpleError: String? = null,
    ) : SafeImportUiState {

        /** Something is pasted and nothing runs. */
        val canImport: Boolean
            get() = !importing && text.isNotBlank()
    }

    /** Checked and queued: [hash] is the Safe transaction's, which its screen opens by. */
    data class Imported(override val network: String, val hash: String) : SafeImportUiState
}

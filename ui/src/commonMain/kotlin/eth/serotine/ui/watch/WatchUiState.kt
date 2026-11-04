package eth.serotine.ui.watch

import eth.serotine.ui.components.Control
import eth.serotine.ui.components.NetworkLabel

/**
 * Everything the Watch address screen renders (docs/wallet-ux.md, screen 10): what is typed
 * ([address]: an address or an ENS name; [name]), why it names no address ([addressError]),
 * what the field says otherwise ([addressNote]: a name being resolved, the block it was
 * resolved at), whether a name that could not be verified may be resolved again
 * ([canRetryLookup]; greyed out with [retryLookupWaits] while asking by hand changes nothing, the
 * light client still syncing), the name an empty [name] leaves the account with ([nameDefault]: the ENS
 * name typed, which the account's name then follows; null: "Account N"), what the light client
 * says is at the address ([detection], null until the text names one), and how adding went.
 *
 * Where two or more networks have an answer (docs/networks.md, N10 and N11), [detection] stays null
 * and each network says its own ([networks]); an address with no code on every one of them is
 * offered on all of them by [question] ("Watch it on Ethereum and Gnosis Chain?"), [choosing] once the user
 * asked to tick the networks instead. A Safe or another contract is offered only where it is.
 * [cardNetwork] is the network [detection] was read on, which Add adds it on, named above the card:
 * with two registries on, a name may have an address on one of them alone. [noNetwork] says
 * that no network is on, so nothing can be checked. The networks that are off are
 * listed greyed out ([offNetworks]), each with Turn on as the way to check the address there too.
 *
 * Simple mode says no block (docs/simple-ui.md): [simpleAddressNote] is the field's note in its
 * words, null where that is [addressNote] itself.
 */
data class WatchUiState(
    val address: String = "",
    val name: String = "",
    val addressError: String? = null,
    val addressNote: String? = null,
    val addressLabel: String = "Address or ENS name",
    val addressHint: String = "0x and 40 hexadecimal digits, or an ENS name like name.eth",
    val canRetryLookup: Boolean = false,
    val retryLookupWaits: String? = null,
    val nameDefault: String? = null,
    val detection: Detection? = null,
    val adding: Boolean = false,
    val nameError: String? = null,
    val error: String? = null,
    /** Added: the screen leaves. */
    val done: Boolean = false,
    val networks: List<WatchNetwork> = emptyList(),
    val question: String? = null,
    val choosing: Boolean = false,
    val noNetwork: String? = null,
    val offNetworks: List<NetworkLabel> = emptyList(),
    val cardNetwork: NetworkLabel? = null,
    val simpleAddressNote: String? = null,
) {
    /** Whether adding now would name the account by [nameDefault]: no name typed, and an add it can be. */
    val followsEns: Boolean
        get() = nameDefault != null && name.isBlank() && (detection.addable || networks.any { it.offered })

    /**
     * Only a verified account or Safe can be added, and one add at a time: on the one network on,
     * or, with more, on all of them as [question] offers, or on those ticked.
     */
    val canAdd: Boolean
        get() = !adding && when {
            networks.isEmpty() -> detection.addable
            question != null && !choosing -> true
            else -> networks.any { it.offered && it.chosen }
        }
}

/**
 * One network's row while two or more are on (N10, N11): its [network], what is at the address
 * there ([detection]), whether it can be added there ([offered]: an account or a Safe, not yet in
 * the wallet), and whether it is ticked ([chosen]).
 */
data class WatchNetwork(
    val network: NetworkLabel,
    val detection: Detection,
    val offered: Boolean,
    val chosen: Boolean = false,
)

/** Whether this card is one that can be added: an account or a Safe. */
internal val Detection?.addable: Boolean
    get() = this is Detection.Account || this is Detection.Safe

/** The detection card: what is at the typed address, display-ready. */
sealed interface Detection {

    /** The light client is being asked. */
    data object Checking : Detection

    /**
     * No contract code at the EIP-55 [address] — an account a key controls, or a contract not
     * deployed yet — verified at the block [checkedAt] names; [delegate] is the EIP-55
     * address its EIP-7702 delegation points to, if it has one. [simpleCheckedAt] is [checkedAt]
     * with no block, for simple mode (docs/simple-ui.md).
     */
    data class Account(
        val address: String,
        val checkedAt: String,
        val delegate: String?,
        val simpleCheckedAt: String = checkedAt,
    ) : Detection

    /**
     * A Safe at the EIP-55 [address], verified at the block [checkedAt] names: its [title]
     * ("Safe 1.4.1"), who must sign ([signers]: "2 of 3 owners"), its EIP-55 [owners], its
     * [modules] — each can move its funds without the owners — its [guard] and [moduleGuard]
     * (null: none), the [control] the account will get, by the owners' keys held here, and the
     * [threshold] badge its blockie carries ("2/3"). [simpleCheckedAt] is [checkedAt] with no block,
     * for simple mode (docs/simple-ui.md).
     */
    data class Safe(
        val address: String,
        val title: String,
        val signers: String,
        val owners: List<String>,
        val modules: List<String>,
        val guard: String?,
        val checkedAt: String,
        val control: Control,
        val moduleGuard: String? = null,
        val threshold: String = "",
        val simpleCheckedAt: String = checkedAt,
    ) : Detection

    /** A contract that is not a recognised Safe, which cannot be watched. */
    data class Contract(val message: String) : Detection

    /** The address is in the wallet already, as [label]. */
    data class InWallet(val label: String) : Detection

    /** No verified answer right now, for the reason [message] gives. */
    data class Unavailable(val message: String) : Detection
}

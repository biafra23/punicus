package eth.serotine.domain.model

/** What kind of account an [Address] is, which decides how Punicus signs for it. */
enum class AccountKind {
    /** An externally owned account: a key pair signs its transactions directly. */
    EOA,

    /** A Safe{Wallet} smart-contract account: its owners sign Safe transactions for it. */
    SAFE,

    /**
     * A Safe{Wallet} that uses a P-256 hardware-bound owner via WebAuthn.
     * The key is stored in the device's Secure Enclave/StrongBox and never leaves it.
     */
    SAFE_P256,
}

/**
 * How the wallet holds an [Account], which decides what it can do with it: one variant per
 * way of holding one, never a flag (docs/architecture.md, *SOLID*). A [Watched] account has
 * no signing path at all.
 */
sealed interface AccountCustody {

    /** Only the address is known: the wallet shows what the chain says about it and signs nothing. */
    data object Watched : AccountCustody

    /** Its key is in this device's key store, sealed in the envelope of the recovery phrase it came from. */
    data object InKeyStore : AccountCustody

    /**
     * The account is owned by a P-256 key stored in the device's Secure Enclave/StrongBox.
     * The key never leaves the hardware; signing uses platform WebAuthn APIs.
     * This custody type applies only to Safe{Wallet} accounts with a hardware-bound owner.
     */
    data object HardwareBound : AccountCustody
}

/**
 * What names one account of the wallet: an [address] on a [network] (docs/networks.md, *An
 * account is an address on a network*). The same address on two networks is two accounts,
 * with two entries, two names and two histories; they share one key when it is held here.
 */
data class AccountId(val network: Network, val address: Address)

/**
 * An account Punicus manages, as the rest of the app sees it: an [address] on one [network],
 * identified by the two together ([id]). The immutable snapshot a repository emits; the key
 * material behind an [AccountCustody.InKeyStore] account is never part of it.
 */
data class Account(
    /** The one chain the account belongs to: everything about it is read and sent there. */
    val network: Network,
    val address: Address,
    val kind: AccountKind,
    /** The user-facing name, never blank; while the account follows [ensName], that name. */
    val label: String,
    val custody: AccountCustody,
    /**
     * The ENS name the account's name follows (issue #67), or null: set when it was watched by
     * that name and given no name of the user's, cleared once the user renames it. Whether the
     * name still points to the account's address is checked again while it is shown
     * (`ObserveEnsNames`): a name that no longer does is not shown as the account's.
     */
    val ensName: String? = null,
) {
    /** What names this account: its [network] and [address]. */
    val id: AccountId get() = AccountId(network, address)

    init {
        require(label.isNotBlank()) { "an account needs a label" }
        require(ensName == null || label == ensName) { "an account that follows an ENS name is named by it" }
    }
}

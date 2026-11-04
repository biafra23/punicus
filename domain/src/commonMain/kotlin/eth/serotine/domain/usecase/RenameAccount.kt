package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountRepository

/**
 * Gives an account a new name: [label] without its surrounding whitespace, refused when
 * nothing is left of it ([AccountRename.Blank]) or when it is longer than
 * [MAX_LABEL_LENGTH] ([AccountRename.TooLong]). The name is the user's own; two accounts
 * may share one, since the network and address are what identify an account.
 */
class RenameAccount(private val accounts: AccountRepository) {
    suspend operator fun invoke(id: AccountId, label: String): AccountRename {
        val name = label.trim()
        if (name.isEmpty()) return AccountRename.Blank
        if (name.length > MAX_LABEL_LENGTH) return AccountRename.TooLong
        return when (accounts.rename(id, name)) {
            AccountChange.Changed -> AccountRename.Renamed
            AccountChange.NotFound -> AccountRename.NotFound
            AccountChange.NotSaved -> AccountRename.NotSaved
        }
    }

    companion object {
        /** The longest name accepted: enough for any name a person gives an account, short enough for a row. */
        const val MAX_LABEL_LENGTH = 40
    }
}

/** The outcome of [RenameAccount]. */
sealed interface AccountRename {
    /** The account has the new name. */
    data object Renamed : AccountRename

    /** The name is empty once trimmed; nothing changed. */
    data object Blank : AccountRename

    /** The name is longer than [RenameAccount.MAX_LABEL_LENGTH]; nothing changed. */
    data object TooLong : AccountRename

    /** The account is not in the list (removed meanwhile); nothing changed. */
    data object NotFound : AccountRename

    /** The list could not be saved, or cannot be read; nothing changed. */
    data object NotSaved : AccountRename
}

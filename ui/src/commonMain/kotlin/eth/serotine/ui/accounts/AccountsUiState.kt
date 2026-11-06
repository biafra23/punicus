package eth.serotine.ui.accounts

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.usecase.EnsNameCheck
import eth.serotine.domain.usecase.SafeReading
import eth.serotine.domain.usecase.Total
import eth.serotine.domain.usecase.Valuation
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.HandoffChoice
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NOT_CHECKED
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.StaleAnchorWarning
import eth.serotine.ui.components.accountName
import eth.serotine.ui.components.agoText
import eth.serotine.ui.components.balanceText
import eth.serotine.ui.components.ensNameNote
import eth.serotine.ui.components.lastKnownNote
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.components.totalText
import eth.serotine.ui.components.valuationText
import eth.serotine.ui.platform.formatDateTime
import eth.serotine.ui.update.UpgradeBannerItem

/**
 * Everything the Accounts screen renders, as one immutable value: the screen is a pure
 * function of it, which is what makes the screen testable without a ViewModel and the
 * ViewModel testable without Compose. There is no network the app is "on" (docs/networks.md,
 * N1): [Ready] has a section per network with accounts, each with its own light client.
 */
sealed interface AccountsUiState {
    /** Each network's upgrade this version does not support, a banner each at the top; none when none. */
    val upgrades: List<UpgradeBannerItem>

    /**
     * Why Create Safe cannot be done now, display-ready, or null when it can: offered either way,
     * greyed out while this says why (owner ruling, 2026-10-18, #55). Only [Ready] knows whether an
     * account here can pay for the deployment.
     */
    val createSafeReason: String?

    /** The account stream has not emitted yet. */
    data class Loading(override val upgrades: List<UpgradeBannerItem> = emptyList()) : AccountsUiState {
        /** The accounts are not read yet: which of them could pay is not known. */
        override val createSafeReason: String
            get() = CREATE_SAFE_CHECKING
    }

    /**
     * The managed accounts, possibly none, in a section per network with accounts ([sections], in
     * `Network` order). [notice] tells the user, display-ready, when the stored list was damaged and
     * set aside, so these are only the accounts added since. [total] is the portfolio's in the
     * display currency ("$3,250.00", or "Checking…"; null with no accounts), over the networks
     * [totalLabel] names ("Total · EUR · Ethereum and Gnosis Chain"): the real networks that are on,
     * never a testnet beside them, or the testnet alone when it is all there is. [totalNote] says
     * what it leaves out ("1 asset without a price · Sepolia not included"). [message] is said once,
     * until the screen has ([AccountsViewModel.messageShown]): a network that could not be turned on.
     *
     * What simple mode draws instead of the sections' strips (docs/simple-ui.md, H1 to H3):
     * [freshness], how fresh the values shown are ("Updated 2 min ago · pull down to refresh",
     * "Live", "Checking Ethereum and Gnosis Chain…", or "Syncing Ethereum · 2 of 6 periods" while a
     * network catches up, with the age of the rest beside it; null while nothing is proven yet);
     * [unreachable], a banner per network that cannot be checked right now while it shows balances
     * from before; [refreshing], while a pull-to-refresh is being answered; and [simpleTotalNote],
     * [totalNote] with the accounts it leaves out of those networks ("1 Ethereum account not
     * counted"), null where it is [totalNote] itself.
     *
     * [amountsHidden] while the user hides amounts with the eye at the top left (docs/wallet-ux.md,
     * *Hiding amounts*): every amount here already reads "••••" beside its unit.
     *
     * Lenient mode's outbox (docs/lenient-mode.md, *Where it shows*): simple mode's [queued], a line
     * per network with something queued ("Ethereum: 1 waiting to send · Keep Serotine open to send
     * it sooner"), drawn under [freshness] (Detailed view says it in the section,
     * [NetworkSection.queued]); and [lostNotice], said until dismissed, when queued signed bytes
     * could not be opened since they were sealed (the seal key gone), null otherwise.
     */
    data class Ready(
        val sections: List<NetworkSection>,
        val notice: String? = null,
        val total: String? = null,
        val totalLabel: String? = null,
        val totalNote: String? = null,
        override val upgrades: List<UpgradeBannerItem> = emptyList(),
        val message: String? = null,
        val freshness: String? = null,
        val unreachable: List<UnreachableNetwork> = emptyList(),
        val refreshing: Boolean = false,
        val simpleTotalNote: String? = null,
        val amountsHidden: Boolean = false,
        val queued: List<String> = emptyList(),
        val lostNotice: String? = null,
    ) : AccountsUiState {
        init {
            require(accounts.distinctBy { it.chainId to it.address }.size == accounts.size) {
                "duplicate accounts in the list: the repository contract is one entry per network and address"
            }
        }

        /** Every account, section after section. */
        val accounts: List<AccountItem>
            get() = sections.flatMap { it.accounts }

        /**
         * Whether Create Safe can be done: once an externally owned account's key is held here, since
         * one pays for the deployment (docs/wallet-ux.md, screen 2). It is offered either way, greyed
         * out until then, and a tap on it says [createSafeReason] (owner ruling, 2026-10-18, #55).
         */
        val canCreateSafe: Boolean
            get() = accounts.any { it.kind == AccountKind.EOA && it.control == Control.FULL_CONTROL }

        /** Why Create Safe cannot be done now, and how to get there; null when it can. */
        override val createSafeReason: String?
            get() = CREATE_SAFE_NEEDS_A_KEY.takeUnless { canCreateSafe }
    }

    /**
     * The stored account list exists but cannot be shown, and nothing can be added to it
     * until it can, for the reason [message] gives, display-ready. Not an empty list: the
     * screen must neither show "no accounts" nor invite an add that would be refused.
     */
    data class ListUnavailable(
        val message: String,
        override val upgrades: List<UpgradeBannerItem> = emptyList(),
    ) : AccountsUiState {
        /** Nothing can be added while the list cannot be read: [message] says why. */
        override val createSafeReason: String
            get() = message
    }
}

/**
 * One network's section of the Accounts screen (docs/networks.md, N1, N2 and N18): its [name]
 * ("Gnosis Chain"), whether it is a [testnet], whether it is [on], its light client as the
 * section's status line ([status]; "Off in this wallet · nothing is checked" when it is off), its
 * [total] in the display currency ("€1,446.30", "Off"; null while nothing is valued) with what
 * that leaves out ([totalNote], "testnet prices"), and its [accounts]. [chainId] names the
 * network to the screen's actions. [blocked] says that this version can no longer follow the
 * network while others still can (docs/networks.md, *Upgrades and the update screen*): the
 * section is drawn in the error colour, with the way to the update. [staleAnchor] warns that its
 * light client refuses to sync from a checkpoint past the weak-subjectivity window, with the way
 * to catch up anyway (owner's request, 2026-10-24): drawn in the error colour too. [queued] is what
 * its accounts have queued in lenient mode, under its status line ("1 waiting to send · Keep
 * Serotine open to send it sooner"; docs/lenient-mode.md, *Where it shows*), null when nothing is.
 */
data class NetworkSection(
    val chainId: Long,
    val name: String,
    val testnet: Boolean,
    val on: Boolean,
    val status: LightClientItem,
    val accounts: List<AccountItem>,
    val total: String? = null,
    val totalNote: String? = null,
    val blocked: String? = null,
    val staleAnchor: StaleAnchorWarning? = null,
    val queued: String? = null,
)

/**
 * A network simple mode cannot check right now while it shows its accounts' balances from before
 * (docs/simple-ui.md, H3): [title] ("Can't check Ethereum right now") and what that means
 * ([text]). [chainId] names the network.
 */
data class UnreachableNetwork(val chainId: Long, val title: String, val text: String)

/**
 * One account as the list shows it: display strings only, derived once from the domain
 * model. [address] is EIP-55 checksummed. [balance] is a verified amount ("0.5 ETH") when
 * [balanceVerified], and otherwise says why there is none ("Checking…", "Syncing…", "Not
 * verified"), or is the amount last verified while no head can be read at, with
 * [balanceNote] saying so ("Last verified Oct 19, 2026, 2:32 PM · may have changed since"):
 * never a number from anywhere else. [value] is that balance in the display
 * currency, or why it has none ("No price"; null while the balance is not verified). For a
 * Safe, [signers] is its verified threshold of its owners ("2/3"; null until verified), and
 * [kindNote] what the row says of its kind ("Safe", or "Not a recognised Safe" once its
 * address no longer verifies as one); null for any other account. [label] is the account's
 * name, or its short address once the ENS name its name follows no longer points to it, which
 * [nameNote] then says. [networkOff] names the account's network while it is off in this wallet
 * (docs/networks.md, N18): its balance is then the one last verified, or "Not checked", with no
 * value, and Send waits for the network to be turned on. [networkBlocked] says that this version can
 * no longer follow the account's network, which greys out its Send.
 *
 * What simple mode's row says (docs/simple-ui.md, H1): its [network] for the blockie's icon, the
 * line under its name ([networkLine]: "Ethereum", "Gnosis Chain · Safe", "Sepolia · test
 * network"), how old a balance from before is ([ageNote], "3 h ago · may have changed"; "Ethereum
 * is off"), and the account's [accountTotal] in the display currency, every priced holding
 * together ("€12,025.55"; "no real value" on a testnet; null while not valued).
 */
data class AccountItem(
    val label: String,
    val address: String,
    /** The chain id of the account's network: with [address], what names the account to the screens it opens. */
    val chainId: Long,
    val kind: AccountKind,
    val control: Control,
    val balance: String,
    val balanceVerified: Boolean,
    val balanceNote: String? = null,
    val value: String? = null,
    val signers: String? = null,
    val kindNote: String? = null,
    val nameNote: String? = null,
    val networkOff: String? = null,
    val networkBlocked: String? = null,
    val network: NetworkLabel? = null,
    val networkLine: String = "",
    val ageNote: String? = null,
    val accountTotal: String? = null,
    /**
     * Send from another wallet (issue #255), for an EOA whose key is not on this device: greyed out
     * with its reason where it cannot be done now; null where it does not apply.
     */
    val handoff: HandoffChoice? = null,
) {
    /** `0x1234…abcd`, for a list row; the full address stays in [address]. */
    val shortAddress: String
        get() = shortAddress(address)

    /**
     * Whether Send can be done: for an account whose key is held here, and for a Safe with owners
     * held here, whose Send proposes a Safe transaction its owners sign; never for a watch-only one
     * (docs/wallet-ux.md, *Control is a badge*). Send is offered either way, greyed out when it
     * cannot, and a tap on it says [sendReason] (owner ruling, 2026-10-18, #55).
     */
    val canSend: Boolean
        get() = sendReason == null

    /** Why Send cannot be done from this account, and how to get there; null when it can. */
    val sendReason: String?
        get() = networkOff?.let { networkOffReason(it, label) }
            ?: networkBlocked
            ?: sendBlockedReason(kind, control)

    companion object {
        /**
         * [account] with its [balance], or with none read yet ([balance] null), and that
         * balance's [value] with the currency it is in (null before it is valued); for a Safe,
         * its verified [safe] reading. [address] is its EIP-55 form, which a caller that
         * re-renders the row per balance computes once; [formatTime] dates a balance last
         * verified (unix seconds); [ensName] is the latest check of the ENS name its name
         * follows, if it follows one (null: none yet). [networkOn] false: the account's network is
         * off in this wallet, and the row says so. [now] (unix seconds) dates a balance from before for
         * simple mode's row, and [total] is the account's value with every priced holding, in the
         * currency of [value].
         */
        fun from(
            account: Account,
            balance: BalanceReading?,
            network: Network,
            address: String = Eip55.format(account.address),
            value: Pair<Valuation?, DisplayCurrency>? = null,
            safe: SafeReading? = null,
            formatTime: (Long) -> String = ::formatDateTime,
            ensName: EnsNameCheck? = null,
            networkOn: Boolean = true,
            now: Long? = null,
            total: Total? = null,
        ): AccountItem {
            val off = network.displayName.takeUnless { networkOn }
            val lastKnown = balance as? BalanceReading.LastKnown
            val control = (safe as? SafeReading.Verified)?.let { Control.of(it.control) }
                ?: Control.of(account.kind, account.custody)
            val kindNote = when {
                account.kind != AccountKind.SAFE -> null
                safe == SafeReading.NotASafe -> "Not a recognised Safe"
                else -> "Safe"
            }
            return AccountItem(
                label = accountName(account, address, ensName),
                address = address,
                chainId = account.network.chainId,
                kind = account.kind,
                // A Safe's control is its verified owners'; until they are read, no more than watch-only.
                control = control,
                balance = when {
                    off != null && lastKnown == null -> NOT_CHECKED
                    else -> balance?.let { balanceText(it, network) } ?: "Checking…"
                },
                balanceVerified = balance is BalanceReading.Verified && off == null,
                balanceNote = when {
                    lastKnown != null -> lastKnownNote(lastKnown.blockNumber, lastKnown.asOf, off, formatTime)
                    off != null -> "$off is off"
                    else -> null
                },
                // A network that is off is left out of every total, its rows' values too (N18).
                value = value?.takeIf { off == null }
                    ?.let { (valuation, currency) -> valuationText(valuation, currency, network) },
                signers = (safe as? SafeReading.Verified)?.let { "${it.state.threshold}/${it.state.owners.size}" },
                kindNote = kindNote,
                nameNote = ensNameNote(account, ensName),
                networkOff = off,
                network = NetworkLabel.of(network),
                // Watch-only and co-signer are the control badge's to say.
                networkLine = listOfNotNull(network.displayName, kindNote, "test network".takeIf { network.testnet })
                    .joinToString(" · "),
                ageNote = lastKnown?.asOf?.let { at -> now?.let { agoText(at, it) } }.let { age ->
                    when {
                        // Off first: nothing else on simple mode's Home says the network is off.
                        off != null -> listOfNotNull("$off is off", age).joinToString(" · ")
                        lastKnown != null -> listOfNotNull(age, "may have changed").joinToString(" · ")
                        else -> null
                    }
                },
                accountTotal = when {
                    off != null -> null
                    network.testnet -> "no real value"
                    // Only a total that leaves nothing out: one that would have to say what it left out
                    // (docs/wallet-ux.md, *Prices*) gives way to the native balance.
                    else -> total?.takeIf { it.complete && it.leftOut == 0 && it.unverified == 0 && !it.noRate }
                        ?.let { totalText(it, value?.second ?: return@let null) }
                },
            )
        }
    }
}

/** What the user asks of an account row, by its [AccountItem.address]. */
sealed interface AccountAction {
    /** The chain id of the account's network. */
    val chainId: Long

    /** The account's address. */
    val address: String

    /** Open its detail screen (a tap). */
    data class Open(override val chainId: Long, override val address: String) : AccountAction

    /** Send from it; offered only when [AccountItem.canSend], for an account that is no Safe. */
    data class Send(override val chainId: Long, override val address: String) : AccountAction

    /** Send from a Safe: propose a Safe transaction; offered only when [AccountItem.canSend]. */
    data class Propose(override val chainId: Long, override val address: String) : AccountAction

    /** Show its address to receive to. */
    data class Receive(override val chainId: Long, override val address: String) : AccountAction

    /** Put its address on the clipboard. */
    data class Copy(override val chainId: Long, override val address: String) : AccountAction

    /** Give it another name. */
    data class Rename(override val chainId: Long, override val address: String) : AccountAction

    /** Take it out of the wallet. */
    data class Remove(override val chainId: Long, override val address: String) : AccountAction

    /** Add it on another network (docs/networks.md, N5); offered for an externally owned account. */
    data class AddOnNetwork(override val chainId: Long, override val address: String) : AccountAction

    /**
     * Add the next account of the recovery phrase it comes from (docs/wallet-ux.md, screen 3); offered
     * for an externally owned account, greyed out while its key is not on this device.
     */
    data class Derive(override val chainId: Long, override val address: String) : AccountAction

    /**
     * Send from it through another wallet app on this device (issue #255); offered for an EOA whose
     * key is not here, when [AccountItem.handoff] is available.
     */
    data class SendElsewhere(override val chainId: Long, override val address: String) : AccountAction
}

/**
 * Why nothing can be [done] ("sent", "swapped") from [account] while its [network] is off in this
 * wallet (docs/networks.md, N19), the way to get it being to turn the network on.
 */
internal fun networkOffReason(network: String, account: String, done: String = "sent"): String =
    "$network is off in this wallet, so its balances are not checked and nothing can be $done from $account. " +
        "Turned on, it checks them again first."

/**
 * Why nothing can be [done] ("sent", "swapped") from [network]'s accounts while this version can no
 * longer follow it, others still followed (docs/networks.md, *Upgrades and the update screen*).
 */
internal fun networkBlockedReason(network: String, done: String = "sent"): String =
    "This version of Serotine can no longer follow $network: nothing can be $done from its accounts until " +
        "you update the app."

/** What Create Safe says while the account list is still being read. */
internal const val CREATE_SAFE_CHECKING = "Checking which accounts can pay…"

/** What Create Safe says while no account whose key is on this device can pay for the deployment. */
internal const val CREATE_SAFE_NEEDS_A_KEY = "Creating a Safe needs an account whose key is on this device, to pay " +
    "for its deployment. Import or create an account, and fund it, first."

/**
 * Why Send cannot be done from an account of [kind] under [control], and how to get there, or
 * null when it can: the reason a greyed-out Send says on a tap (owner ruling, 2026-10-18, #55).
 */
internal fun sendBlockedReason(kind: AccountKind, control: Control): String? = when (kind) {
    AccountKind.EOA -> if (control == Control.FULL_CONTROL) null else watchOnlyReason("sent", "send")
    AccountKind.SAFE -> if (control != Control.WATCH_ONLY) {
        null
    } else {
        "No owner of this Safe has its key on this device, so nothing can be proposed from it. Import an owner's " +
            "recovery phrase to propose and sign its transactions."
    }
}

/**
 * Why Swap cannot be done from an account of [kind] under [control], and how to get there; null
 * when it can. A Safe's swap is proposed as Safe transactions its owners sign (docs/swaps.md, *A
 * Safe's swap*), as its Send is: an owner held here is all it needs. Greyed out, never hidden
 * (owner ruling, 2026-10-18, #55).
 */
internal fun swapBlockedReason(kind: AccountKind, control: Control): String? = when (kind) {
    AccountKind.EOA -> if (control == Control.FULL_CONTROL) null else watchOnlyReason("swapped", "swap")
    AccountKind.SAFE -> sendBlockedReason(kind, control)
}

/**
 * Why no account of an externally owned account's recovery phrase can be added from it under
 * [control], and how to get there, or null when one can: the reason a greyed-out New account from
 * the same phrase says on a tap (owner ruling, 2026-10-18, #55).
 */
internal fun deriveBlockedReason(control: Control): String? =
    if (control == Control.FULL_CONTROL) {
        null
    } else {
        "This account is watch-only: its key is not on this device, so no account of its recovery phrase can be " +
            "added from it. Import its recovery phrase to add its accounts."
    }

/** What the account menus call adding the next account of an account's recovery phrase. */
internal const val DERIVE_ACTION = "New account from the same phrase"

/** An account's key not on this device: nothing can be [done] from it, and how to [act] from it. */
private fun watchOnlyReason(done: String, act: String): String =
    "This account is watch-only: its key is not on this device, so nothing can be $done from it. Import its " +
        "recovery phrase to $act from it."

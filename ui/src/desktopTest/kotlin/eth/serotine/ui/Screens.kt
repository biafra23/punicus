package eth.serotine.ui

import eth.serotine.ui.accountdetail.accountDetailScreenPreviews
import eth.serotine.ui.accountdetail.simpleAccountDetailScreenPreviews
import eth.serotine.ui.accounts.accountsScreenPreviews
import eth.serotine.ui.accounts.simpleAccountsScreenPreviews
import eth.serotine.ui.activitydetail.activityDetailScreenPreviews
import eth.serotine.ui.activitydetail.simpleActivityDetailScreenPreviews
import eth.serotine.ui.addnetwork.addOnNetworksDialogPreviews
import eth.serotine.ui.addtoken.addTokenScreenPreviews
import eth.serotine.ui.addtoken.simpleAddTokenScreenPreviews
import eth.serotine.ui.components.screenCaptureWarningDialogPreviews
import eth.serotine.ui.connections.connectionsScreenPreviews
import eth.serotine.ui.coworder.cowOrderScreenPreviews
import eth.serotine.ui.coworder.simpleCowOrderScreenPreviews
import eth.serotine.ui.createaccount.createAccountScreenPreviews
import eth.serotine.ui.createsafe.createSafeScreenPreviews
import eth.serotine.ui.createsafe.simpleCreateSafeScreenPreviews
import eth.serotine.ui.currency.currencyScreenPreviews
import eth.serotine.ui.dappproposal.dappProposalScreenPreviews
import eth.serotine.ui.dapprequest.dappRequestScreenPreviews
import eth.serotine.ui.dapptransaction.dappTransactionScreenPreviews
import eth.serotine.ui.debug.debugScreenPreviews
import eth.serotine.ui.deriveaccount.deriveAccountScreenPreviews
import eth.serotine.ui.mnemonicimport.mnemonicImportScreenPreviews
import eth.serotine.ui.pairdapp.pairDappScreenPreviews
import eth.serotine.ui.ratewidget.widgetPairScreenPreviews
import eth.serotine.ui.receive.receiveScreenPreviews
import eth.serotine.ui.removeaccount.removeAccountDialogPreviews
import eth.serotine.ui.renameaccount.renameAccountDialogPreviews
import eth.serotine.ui.safeimport.safeImportScreenPreviews
import eth.serotine.ui.safeimport.simpleSafeImportScreenPreviews
import eth.serotine.ui.safepropose.safeProposeScreenPreviews
import eth.serotine.ui.safepropose.simpleSafeProposeScreenPreviews
import eth.serotine.ui.safesettings.safeSettingsScreenPreviews
import eth.serotine.ui.safesettings.simpleSafeSettingsScreenPreviews
import eth.serotine.ui.safeswap.safeSwapScreenPreviews
import eth.serotine.ui.safeswap.simpleSafeSwapScreenPreviews
import eth.serotine.ui.safetx.safeTxScreenPreviews
import eth.serotine.ui.safetx.simpleSafeTxScreenPreviews
import eth.serotine.ui.security.securityScreenPreviews
import eth.serotine.ui.send.sendScreenPreviews
import eth.serotine.ui.send.simpleSendScreenPreviews
import eth.serotine.ui.settings.settingsScreenPreviews
import eth.serotine.ui.settings.simpleSettingsScreenPreviews
import eth.serotine.ui.swap.simpleSwapScreenPreviews
import eth.serotine.ui.swap.swapScreenPreviews
import eth.serotine.ui.tokens.simpleTokensScreenPreviews
import eth.serotine.ui.tokens.tokensScreenPreviews
import eth.serotine.ui.transferstatus.simpleTransferStatusScreenPreviews
import eth.serotine.ui.transferstatus.transferStatusScreenPreviews
import eth.serotine.ui.update.updateRequiredScreenPreviews
import eth.serotine.ui.watch.simpleWatchScreenPreviews
import eth.serotine.ui.watch.watchScreenPreviews

/**
 * The map of screens: every screen once, with the slug that is its folder under `docs/screenshots/`.
 *
 * This is the one place a screen's slug is declared. A new previews list is registered here (and
 * [ScreensTest] fails until it is); a slug, once in `main`, stays with its screen so the same screen is
 * at the same path in every commit. The previews tests render a screen through its entry here
 * (`renderEach(Screens.send)`), and the index pages ([ScreenshotIndex]) list [all].
 */
internal object Screens {
    val accountDetail = Screen("account-detail", "Account detail", accountDetailScreenPreviews)
    val accounts = Screen("accounts", "Accounts", accountsScreenPreviews)
    val activityDetail = Screen("activity-detail", "Activity detail", activityDetailScreenPreviews)
    val addOnNetworksDialog = Screen("add-on-networks-dialog", "Add on networks (dialog)", addOnNetworksDialogPreviews)
    val addToken = Screen("add-token", "Add token", addTokenScreenPreviews)
    val connections = Screen("connections", "Connected dApps", connectionsScreenPreviews)
    val cowOrder = Screen("cow-order", "CoW order", cowOrderScreenPreviews)
    val createAccount = Screen("create-account", "Create account", createAccountScreenPreviews)
    val createSafe = Screen("create-safe", "Create Safe", createSafeScreenPreviews)
    val currency = Screen("currency", "Display currency", currencyScreenPreviews)
    val dappProposal = Screen("dapp-proposal", "dApp connection request", dappProposalScreenPreviews)
    val dappRequest = Screen("dapp-request", "dApp signing request", dappRequestScreenPreviews)
    val dappTransaction = Screen("dapp-transaction", "dApp transaction", dappTransactionScreenPreviews)
    val debug = Screen("debug", "Debug", debugScreenPreviews)
    val deriveAccount = Screen("derive-account", "Add account from the same phrase", deriveAccountScreenPreviews)
    val mnemonicImport = Screen("mnemonic-import", "Import recovery phrase", mnemonicImportScreenPreviews)
    val pairDapp = Screen("pair-dapp", "Connect a dApp", pairDappScreenPreviews)
    val receive = Screen("receive", "Receive", receiveScreenPreviews)
    val removeAccountDialog = Screen("remove-account-dialog", "Remove account (dialog)", removeAccountDialogPreviews)
    val renameAccountDialog = Screen("rename-account-dialog", "Rename account (dialog)", renameAccountDialogPreviews)
    val safeImport = Screen("safe-import", "Import Safe", safeImportScreenPreviews)
    val safePropose = Screen("safe-propose", "Propose Safe transaction", safeProposeScreenPreviews)
    val safeSettings = Screen("safe-settings", "Safe settings", safeSettingsScreenPreviews)
    val safeSwap = Screen("safe-swap", "Safe swap", safeSwapScreenPreviews)
    val safeTx = Screen("safe-tx", "Safe transaction", safeTxScreenPreviews)
    val screenCaptureWarningDialog =
        Screen("screen-capture-warning-dialog", "Screen capture warning (dialog)", screenCaptureWarningDialogPreviews)
    val security = Screen("security", "Security", securityScreenPreviews)
    val send = Screen("send", "Send", sendScreenPreviews)
    val settings = Screen("settings", "Settings", settingsScreenPreviews)
    val simpleAccountDetail =
        Screen("simple-account-detail", "Account detail (simple mode)", simpleAccountDetailScreenPreviews)
    val simpleAccounts = Screen("simple-accounts", "Home (simple mode)", simpleAccountsScreenPreviews)
    val simpleActivityDetail =
        Screen("simple-activity-detail", "Activity detail (simple mode)", simpleActivityDetailScreenPreviews)
    val simpleAddToken = Screen("simple-add-token", "Add token (simple mode)", simpleAddTokenScreenPreviews)
    val simpleCowOrder = Screen("simple-cow-order", "CoW order (simple mode)", simpleCowOrderScreenPreviews)
    val simpleCreateSafe = Screen("simple-create-safe", "Create Safe (simple mode)", simpleCreateSafeScreenPreviews)
    val simpleSafeImport = Screen("simple-safe-import", "Import Safe (simple mode)", simpleSafeImportScreenPreviews)
    val simpleSafePropose =
        Screen("simple-safe-propose", "Propose Safe transaction (simple mode)", simpleSafeProposeScreenPreviews)
    val simpleSafeSettings =
        Screen("simple-safe-settings", "Safe settings (simple mode)", simpleSafeSettingsScreenPreviews)
    val simpleSafeSwap = Screen("simple-safe-swap", "Safe swap (simple mode)", simpleSafeSwapScreenPreviews)
    val simpleSafeTx = Screen("simple-safe-tx", "Safe transaction (simple mode)", simpleSafeTxScreenPreviews)
    val simpleSend = Screen("simple-send", "Send (simple mode)", simpleSendScreenPreviews)
    val simpleSettings = Screen("simple-settings", "Settings (simple mode)", simpleSettingsScreenPreviews)
    val simpleSwap = Screen("simple-swap", "Swap (simple mode)", simpleSwapScreenPreviews)
    val simpleTokens = Screen("simple-tokens", "Tokens (simple mode)", simpleTokensScreenPreviews)
    val simpleTransferStatus =
        Screen("simple-transfer-status", "Transfer status (simple mode)", simpleTransferStatusScreenPreviews)
    val simpleWatch = Screen("simple-watch", "Watch address (simple mode)", simpleWatchScreenPreviews)
    val swap = Screen("swap", "Swap", swapScreenPreviews)
    val tokens = Screen("tokens", "Tokens", tokensScreenPreviews)
    val transferStatus = Screen("transfer-status", "Transfer status", transferStatusScreenPreviews)
    val updateRequired = Screen("update-required", "Update required", updateRequiredScreenPreviews)
    val watch = Screen("watch", "Watch address", watchScreenPreviews)
    val widgetPair = Screen("widget-pair", "Widget setup", widgetPairScreenPreviews)

    /** Every screen, in the order of the index pages: by slug. */
    val all: List<Screen> = listOf(
        accountDetail,
        accounts,
        activityDetail,
        addOnNetworksDialog,
        addToken,
        connections,
        cowOrder,
        createAccount,
        createSafe,
        currency,
        dappProposal,
        dappRequest,
        dappTransaction,
        debug,
        deriveAccount,
        mnemonicImport,
        pairDapp,
        receive,
        removeAccountDialog,
        renameAccountDialog,
        safeImport,
        safePropose,
        safeSettings,
        safeSwap,
        safeTx,
        screenCaptureWarningDialog,
        security,
        send,
        settings,
        simpleAccountDetail,
        simpleAccounts,
        simpleActivityDetail,
        simpleAddToken,
        simpleCowOrder,
        simpleCreateSafe,
        simpleSafeImport,
        simpleSafePropose,
        simpleSafeSettings,
        simpleSafeSwap,
        simpleSafeTx,
        simpleSend,
        simpleSettings,
        simpleSwap,
        simpleTokens,
        simpleTransferStatus,
        simpleWatch,
        swap,
        tokens,
        transferStatus,
        updateRequired,
        watch,
        widgetPair,
    )
}

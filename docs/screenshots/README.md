# Screenshots

Every screen of Serotine in every state its previews draw, rendered headless from the previews (`ui/src/commonMain/kotlin/eth/serotine/ui/**/<Feature>ScreenPreviews.kt`). CI renders them on every pull request and commits the ones the PR changes, so the images come from one environment and a diff of this folder shows only real changes. The same screen is always at the same path, `<screen>/<state>.png`; the map of screens is `ui/src/desktopTest/kotlin/eth/serotine/ui/Screens.kt`, and a state's name sits beside its preview in the screen's previews list. A state's dark twin ends in `-dark`.

[index.html](index.html) shows them all on one page from a checkout.

Clone without the images: `git clone --filter=blob:none https://github.com/biafra23/serotine.git` fetches a file only when a command needs it, so the history of this folder costs nothing until it is looked at. `git sparse-checkout set --no-cone '/*' '!/docs/screenshots/'` leaves it out of the working tree as well.

If the folder grows too much, there are two levers, both the owner's. Deleting the folder stops the growth and keeps every past image in the history. Rewriting the history with `git filter-repo --path docs/screenshots --invert-paths` drops them for good, but changes the SHA of every commit since the first screenshot: tags, links to commits and every existing clone must follow. CI fails a pull request once the folder, or one image, passes its size budget (`scripts/screenshots.py`).

| Screen | Folder | States |
|---|---|---|
| [Account detail](#account-detail) | `account-detail/` | 44 |
| [Accounts](#accounts) | `accounts/` | 35 |
| [Activity detail](#activity-detail) | `activity-detail/` | 11 |
| [Add on networks (dialog)](#add-on-networks-dialog) | `add-on-networks-dialog/` | 5 |
| [Add token](#add-token) | `add-token/` | 11 |
| [Connected dApps](#connections) | `connections/` | 6 |
| [CoW order](#cow-order) | `cow-order/` | 16 |
| [Create account](#create-account) | `create-account/` | 16 |
| [Create Safe](#create-safe) | `create-safe/` | 19 |
| [Display currency](#currency) | `currency/` | 4 |
| [dApp connection request](#dapp-proposal) | `dapp-proposal/` | 14 |
| [dApp signing request](#dapp-request) | `dapp-request/` | 20 |
| [dApp transaction](#dapp-transaction) | `dapp-transaction/` | 20 |
| [Debug](#debug) | `debug/` | 6 |
| [Add account from the same phrase](#derive-account) | `derive-account/` | 10 |
| [Send from another wallet](#handoff) | `handoff/` | 18 |
| [Import recovery phrase](#mnemonic-import) | `mnemonic-import/` | 12 |
| [Connect a dApp](#pair-dapp) | `pair-dapp/` | 11 |
| [Receive](#receive) | `receive/` | 7 |
| [Remove account (dialog)](#remove-account-dialog) | `remove-account-dialog/` | 7 |
| [Rename account (dialog)](#rename-account-dialog) | `rename-account-dialog/` | 6 |
| [Import Safe](#safe-import) | `safe-import/` | 7 |
| [Propose Safe transaction](#safe-propose) | `safe-propose/` | 17 |
| [Safe settings](#safe-settings) | `safe-settings/` | 17 |
| [Safe swap](#safe-swap) | `safe-swap/` | 13 |
| [Safe transaction](#safe-tx) | `safe-tx/` | 45 |
| [Screen capture warning (dialog)](#screen-capture-warning-dialog) | `screen-capture-warning-dialog/` | 3 |
| [Security](#security) | `security/` | 15 |
| [Send](#send) | `send/` | 32 |
| [Settings](#settings) | `settings/` | 21 |
| [Account detail (simple mode)](#simple-account-detail) | `simple-account-detail/` | 12 |
| [Home (simple mode)](#simple-accounts) | `simple-accounts/` | 16 |
| [Activity detail (simple mode)](#simple-activity-detail) | `simple-activity-detail/` | 4 |
| [Add token (simple mode)](#simple-add-token) | `simple-add-token/` | 2 |
| [CoW order (simple mode)](#simple-cow-order) | `simple-cow-order/` | 3 |
| [Create Safe (simple mode)](#simple-create-safe) | `simple-create-safe/` | 3 |
| [Import Safe (simple mode)](#simple-safe-import) | `simple-safe-import/` | 3 |
| [Propose Safe transaction (simple mode)](#simple-safe-propose) | `simple-safe-propose/` | 6 |
| [Safe settings (simple mode)](#simple-safe-settings) | `simple-safe-settings/` | 5 |
| [Safe swap (simple mode)](#simple-safe-swap) | `simple-safe-swap/` | 4 |
| [Safe transaction (simple mode)](#simple-safe-tx) | `simple-safe-tx/` | 15 |
| [Send (simple mode)](#simple-send) | `simple-send/` | 8 |
| [Settings (simple mode)](#simple-settings) | `simple-settings/` | 6 |
| [Swap (simple mode)](#simple-swap) | `simple-swap/` | 11 |
| [Tokens (simple mode)](#simple-tokens) | `simple-tokens/` | 2 |
| [Transfer status (simple mode)](#simple-transfer-status) | `simple-transfer-status/` | 13 |
| [Watch address (simple mode)](#simple-watch) | `simple-watch/` | 9 |
| [Swap](#swap) | `swap/` | 38 |
| [Tokens](#tokens) | `tokens/` | 7 |
| [Transfer status](#transfer-status) | `transfer-status/` | 36 |
| [Update required](#update-required) | `update-required/` | 2 |
| [Watch address](#watch) | `watch/` | 25 |
| [Widget setup](#widget-pair) | `widget-pair/` | 9 |

<a name="account-detail"></a>
## Account detail

`account-detail/`, 44 states: `assets`, `assets-dark`, `checking`, `head-too-old`, `by-name-with-impostor`, `safe-assets`, `safe-owners`, `safe-queue`, `safe-queue-dark`, `safe-queue-empty`, `safe-queue-unverified`, `safe-queue-unreadable`, `safe-reading`, `not-a-safe`, `loading`, `list-unavailable`, `ens-name-moved`, `activity`, `activity-dark`, `waiting-swap`, `waiting-swap-dark`, `activity-empty`, `activity-loading`, `activity-unavailable`, `activity-tokens`, `safe-activity`, `safe-activity-dark`, `narrow`, `safe-queue-reading`, `checking-dark`, `ethereum`, `network-off`, `safe-owners-network-off`, `assets-hidden`, `assets-hidden-dark`, `nfts`, `nfts-unlisted-open-dark`, `nfts-checking`, `nfts-none`, `nft-add`, `nft-add-unavailable`, `nft-adding`, `activity-queued`, `activity-queued-conflict-dark`

<a href="account-detail/assets.png"><img src="account-detail/assets.png" width="206" alt="assets"></a>
<a href="account-detail/assets-dark.png"><img src="account-detail/assets-dark.png" width="206" alt="assets-dark"></a>
<a href="account-detail/checking.png"><img src="account-detail/checking.png" width="206" alt="checking"></a>
<a href="account-detail/head-too-old.png"><img src="account-detail/head-too-old.png" width="206" alt="head-too-old"></a>
<a href="account-detail/by-name-with-impostor.png"><img src="account-detail/by-name-with-impostor.png" width="206" alt="by-name-with-impostor"></a>
<a href="account-detail/safe-assets.png"><img src="account-detail/safe-assets.png" width="206" alt="safe-assets"></a>
<a href="account-detail/safe-owners.png"><img src="account-detail/safe-owners.png" width="206" alt="safe-owners"></a>
<a href="account-detail/safe-queue.png"><img src="account-detail/safe-queue.png" width="206" alt="safe-queue"></a>
<a href="account-detail/safe-queue-dark.png"><img src="account-detail/safe-queue-dark.png" width="206" alt="safe-queue-dark"></a>
<a href="account-detail/safe-queue-empty.png"><img src="account-detail/safe-queue-empty.png" width="206" alt="safe-queue-empty"></a>
<a href="account-detail/safe-queue-unverified.png"><img src="account-detail/safe-queue-unverified.png" width="206" alt="safe-queue-unverified"></a>
<a href="account-detail/safe-queue-unreadable.png"><img src="account-detail/safe-queue-unreadable.png" width="206" alt="safe-queue-unreadable"></a>
<a href="account-detail/safe-reading.png"><img src="account-detail/safe-reading.png" width="206" alt="safe-reading"></a>
<a href="account-detail/not-a-safe.png"><img src="account-detail/not-a-safe.png" width="206" alt="not-a-safe"></a>
<a href="account-detail/loading.png"><img src="account-detail/loading.png" width="206" alt="loading"></a>
<a href="account-detail/list-unavailable.png"><img src="account-detail/list-unavailable.png" width="206" alt="list-unavailable"></a>
<a href="account-detail/ens-name-moved.png"><img src="account-detail/ens-name-moved.png" width="206" alt="ens-name-moved"></a>
<a href="account-detail/activity.png"><img src="account-detail/activity.png" width="206" alt="activity"></a>
<a href="account-detail/activity-dark.png"><img src="account-detail/activity-dark.png" width="206" alt="activity-dark"></a>
<a href="account-detail/waiting-swap.png"><img src="account-detail/waiting-swap.png" width="206" alt="waiting-swap"></a>
<a href="account-detail/waiting-swap-dark.png"><img src="account-detail/waiting-swap-dark.png" width="206" alt="waiting-swap-dark"></a>
<a href="account-detail/activity-empty.png"><img src="account-detail/activity-empty.png" width="206" alt="activity-empty"></a>
<a href="account-detail/activity-loading.png"><img src="account-detail/activity-loading.png" width="206" alt="activity-loading"></a>
<a href="account-detail/activity-unavailable.png"><img src="account-detail/activity-unavailable.png" width="206" alt="activity-unavailable"></a>
<a href="account-detail/activity-tokens.png"><img src="account-detail/activity-tokens.png" width="206" alt="activity-tokens"></a>
<a href="account-detail/safe-activity.png"><img src="account-detail/safe-activity.png" width="206" alt="safe-activity"></a>
<a href="account-detail/safe-activity-dark.png"><img src="account-detail/safe-activity-dark.png" width="206" alt="safe-activity-dark"></a>
<a href="account-detail/narrow.png"><img src="account-detail/narrow.png" width="206" alt="narrow"></a>
<a href="account-detail/safe-queue-reading.png"><img src="account-detail/safe-queue-reading.png" width="206" alt="safe-queue-reading"></a>
<a href="account-detail/checking-dark.png"><img src="account-detail/checking-dark.png" width="206" alt="checking-dark"></a>
<a href="account-detail/ethereum.png"><img src="account-detail/ethereum.png" width="206" alt="ethereum"></a>
<a href="account-detail/network-off.png"><img src="account-detail/network-off.png" width="206" alt="network-off"></a>
<a href="account-detail/safe-owners-network-off.png"><img src="account-detail/safe-owners-network-off.png" width="206" alt="safe-owners-network-off"></a>
<a href="account-detail/assets-hidden.png"><img src="account-detail/assets-hidden.png" width="206" alt="assets-hidden"></a>
<a href="account-detail/assets-hidden-dark.png"><img src="account-detail/assets-hidden-dark.png" width="206" alt="assets-hidden-dark"></a>
<a href="account-detail/nfts.png"><img src="account-detail/nfts.png" width="206" alt="nfts"></a>
<a href="account-detail/nfts-unlisted-open-dark.png"><img src="account-detail/nfts-unlisted-open-dark.png" width="206" alt="nfts-unlisted-open-dark"></a>
<a href="account-detail/nfts-checking.png"><img src="account-detail/nfts-checking.png" width="206" alt="nfts-checking"></a>
<a href="account-detail/nfts-none.png"><img src="account-detail/nfts-none.png" width="206" alt="nfts-none"></a>
<a href="account-detail/nft-add.png"><img src="account-detail/nft-add.png" width="206" alt="nft-add"></a>
<a href="account-detail/nft-add-unavailable.png"><img src="account-detail/nft-add-unavailable.png" width="206" alt="nft-add-unavailable"></a>
<a href="account-detail/nft-adding.png"><img src="account-detail/nft-adding.png" width="206" alt="nft-adding"></a>
<a href="account-detail/activity-queued.png"><img src="account-detail/activity-queued.png" width="206" alt="activity-queued"></a>
<a href="account-detail/activity-queued-conflict-dark.png"><img src="account-detail/activity-queued-conflict-dark.png" width="206" alt="activity-queued-conflict-dark"></a>

<a name="accounts"></a>
## Accounts

`accounts/`, 35 states: `accounts`, `accounts-dark`, `catching-up`, `head-too-old`, `head-too-old-dark`, `no-accounts`, `create-safe-greyed-out`, `create-safe-reason`, `send-reason`, `loading`, `loading-create-safe-greyed-out`, `list-set-aside`, `upgrade-ahead`, `upgrade-activated-dark`, `list-unavailable`, `catching-up-dark`, `narrow`, `ens-names`, `three-networks`, `three-networks-dark`, `one-network-catching-up`, `network-off`, `network-off-dark`, `send-needs-network`, `network-blocked`, `no-rate-on-a-network`, `network-blocked-dark`, `stale-anchor`, `stale-anchor-dark`, `stale-anchor-dialog`, `hidden`, `hidden-dark`, `queued`, `queued-dark`, `lost-queued`

<a href="accounts/accounts.png"><img src="accounts/accounts.png" width="206" alt="accounts"></a>
<a href="accounts/accounts-dark.png"><img src="accounts/accounts-dark.png" width="206" alt="accounts-dark"></a>
<a href="accounts/catching-up.png"><img src="accounts/catching-up.png" width="206" alt="catching-up"></a>
<a href="accounts/head-too-old.png"><img src="accounts/head-too-old.png" width="206" alt="head-too-old"></a>
<a href="accounts/head-too-old-dark.png"><img src="accounts/head-too-old-dark.png" width="206" alt="head-too-old-dark"></a>
<a href="accounts/no-accounts.png"><img src="accounts/no-accounts.png" width="206" alt="no-accounts"></a>
<a href="accounts/create-safe-greyed-out.png"><img src="accounts/create-safe-greyed-out.png" width="206" alt="create-safe-greyed-out"></a>
<a href="accounts/create-safe-reason.png"><img src="accounts/create-safe-reason.png" width="206" alt="create-safe-reason"></a>
<a href="accounts/send-reason.png"><img src="accounts/send-reason.png" width="206" alt="send-reason"></a>
<a href="accounts/loading.png"><img src="accounts/loading.png" width="206" alt="loading"></a>
<a href="accounts/loading-create-safe-greyed-out.png"><img src="accounts/loading-create-safe-greyed-out.png" width="206" alt="loading-create-safe-greyed-out"></a>
<a href="accounts/list-set-aside.png"><img src="accounts/list-set-aside.png" width="206" alt="list-set-aside"></a>
<a href="accounts/upgrade-ahead.png"><img src="accounts/upgrade-ahead.png" width="206" alt="upgrade-ahead"></a>
<a href="accounts/upgrade-activated-dark.png"><img src="accounts/upgrade-activated-dark.png" width="206" alt="upgrade-activated-dark"></a>
<a href="accounts/list-unavailable.png"><img src="accounts/list-unavailable.png" width="206" alt="list-unavailable"></a>
<a href="accounts/catching-up-dark.png"><img src="accounts/catching-up-dark.png" width="206" alt="catching-up-dark"></a>
<a href="accounts/narrow.png"><img src="accounts/narrow.png" width="206" alt="narrow"></a>
<a href="accounts/ens-names.png"><img src="accounts/ens-names.png" width="206" alt="ens-names"></a>
<a href="accounts/three-networks.png"><img src="accounts/three-networks.png" width="206" alt="three-networks"></a>
<a href="accounts/three-networks-dark.png"><img src="accounts/three-networks-dark.png" width="206" alt="three-networks-dark"></a>
<a href="accounts/one-network-catching-up.png"><img src="accounts/one-network-catching-up.png" width="206" alt="one-network-catching-up"></a>
<a href="accounts/network-off.png"><img src="accounts/network-off.png" width="206" alt="network-off"></a>
<a href="accounts/network-off-dark.png"><img src="accounts/network-off-dark.png" width="206" alt="network-off-dark"></a>
<a href="accounts/send-needs-network.png"><img src="accounts/send-needs-network.png" width="206" alt="send-needs-network"></a>
<a href="accounts/network-blocked.png"><img src="accounts/network-blocked.png" width="206" alt="network-blocked"></a>
<a href="accounts/no-rate-on-a-network.png"><img src="accounts/no-rate-on-a-network.png" width="206" alt="no-rate-on-a-network"></a>
<a href="accounts/network-blocked-dark.png"><img src="accounts/network-blocked-dark.png" width="206" alt="network-blocked-dark"></a>
<a href="accounts/stale-anchor.png"><img src="accounts/stale-anchor.png" width="206" alt="stale-anchor"></a>
<a href="accounts/stale-anchor-dark.png"><img src="accounts/stale-anchor-dark.png" width="206" alt="stale-anchor-dark"></a>
<a href="accounts/stale-anchor-dialog.png"><img src="accounts/stale-anchor-dialog.png" width="206" alt="stale-anchor-dialog"></a>
<a href="accounts/hidden.png"><img src="accounts/hidden.png" width="206" alt="hidden"></a>
<a href="accounts/hidden-dark.png"><img src="accounts/hidden-dark.png" width="206" alt="hidden-dark"></a>
<a href="accounts/queued.png"><img src="accounts/queued.png" width="206" alt="queued"></a>
<a href="accounts/queued-dark.png"><img src="accounts/queued-dark.png" width="206" alt="queued-dark"></a>
<a href="accounts/lost-queued.png"><img src="accounts/lost-queued.png" width="206" alt="lost-queued"></a>

<a name="activity-detail"></a>
## Activity detail

`activity-detail/`, 11 states: `sent`, `sent-dark`, `unconfirmed`, `unlisted-token`, `safe-event`, `loading`, `unavailable`, `unfamiliar-sender`, `hidden`, `hidden-dark`, `eth-logged`

<a href="activity-detail/sent.png"><img src="activity-detail/sent.png" width="206" alt="sent"></a>
<a href="activity-detail/sent-dark.png"><img src="activity-detail/sent-dark.png" width="206" alt="sent-dark"></a>
<a href="activity-detail/unconfirmed.png"><img src="activity-detail/unconfirmed.png" width="206" alt="unconfirmed"></a>
<a href="activity-detail/unlisted-token.png"><img src="activity-detail/unlisted-token.png" width="206" alt="unlisted-token"></a>
<a href="activity-detail/safe-event.png"><img src="activity-detail/safe-event.png" width="206" alt="safe-event"></a>
<a href="activity-detail/loading.png"><img src="activity-detail/loading.png" width="206" alt="loading"></a>
<a href="activity-detail/unavailable.png"><img src="activity-detail/unavailable.png" width="206" alt="unavailable"></a>
<a href="activity-detail/unfamiliar-sender.png"><img src="activity-detail/unfamiliar-sender.png" width="206" alt="unfamiliar-sender"></a>
<a href="activity-detail/hidden.png"><img src="activity-detail/hidden.png" width="206" alt="hidden"></a>
<a href="activity-detail/hidden-dark.png"><img src="activity-detail/hidden-dark.png" width="206" alt="hidden-dark"></a>
<a href="activity-detail/eth-logged.png"><img src="activity-detail/eth-logged.png" width="206" alt="eth-logged"></a>

<a name="add-on-networks-dialog"></a>
## Add on networks (dialog)

`add-on-networks-dialog/`, 5 states: `choose`, `choose-dark`, `not-saved`, `on-every-network`, `safe`

<a href="add-on-networks-dialog/choose.png"><img src="add-on-networks-dialog/choose.png" width="206" alt="choose"></a>
<a href="add-on-networks-dialog/choose-dark.png"><img src="add-on-networks-dialog/choose-dark.png" width="206" alt="choose-dark"></a>
<a href="add-on-networks-dialog/not-saved.png"><img src="add-on-networks-dialog/not-saved.png" width="206" alt="not-saved"></a>
<a href="add-on-networks-dialog/on-every-network.png"><img src="add-on-networks-dialog/on-every-network.png" width="206" alt="on-every-network"></a>
<a href="add-on-networks-dialog/safe.png"><img src="add-on-networks-dialog/safe.png" width="206" alt="safe"></a>

<a name="add-token"></a>
## Add token

`add-token/`, 11 states: `empty`, `reading`, `claim`, `claim-dark`, `impostor`, `known`, `not-a-token`, `invalid-address`, `adding`, `not-saved`, `networks`

<a href="add-token/empty.png"><img src="add-token/empty.png" width="206" alt="empty"></a>
<a href="add-token/reading.png"><img src="add-token/reading.png" width="206" alt="reading"></a>
<a href="add-token/claim.png"><img src="add-token/claim.png" width="206" alt="claim"></a>
<a href="add-token/claim-dark.png"><img src="add-token/claim-dark.png" width="206" alt="claim-dark"></a>
<a href="add-token/impostor.png"><img src="add-token/impostor.png" width="206" alt="impostor"></a>
<a href="add-token/known.png"><img src="add-token/known.png" width="206" alt="known"></a>
<a href="add-token/not-a-token.png"><img src="add-token/not-a-token.png" width="206" alt="not-a-token"></a>
<a href="add-token/invalid-address.png"><img src="add-token/invalid-address.png" width="206" alt="invalid-address"></a>
<a href="add-token/adding.png"><img src="add-token/adding.png" width="206" alt="adding"></a>
<a href="add-token/not-saved.png"><img src="add-token/not-saved.png" width="206" alt="not-saved"></a>
<a href="add-token/networks.png"><img src="add-token/networks.png" width="206" alt="networks"></a>

<a name="connections"></a>
## Connected dApps

`connections/`, 6 states: `connected`, `connected-dark`, `none-yet`, `not-saved`, `off`, `loading`

<a href="connections/connected.png"><img src="connections/connected.png" width="206" alt="connected"></a>
<a href="connections/connected-dark.png"><img src="connections/connected-dark.png" width="206" alt="connected-dark"></a>
<a href="connections/none-yet.png"><img src="connections/none-yet.png" width="206" alt="none-yet"></a>
<a href="connections/not-saved.png"><img src="connections/not-saved.png" width="206" alt="not-saved"></a>
<a href="connections/off.png"><img src="connections/off.png" width="206" alt="off"></a>
<a href="connections/loading.png"><img src="connections/loading.png" width="206" alt="loading"></a>

<a name="cow-order"></a>
## CoW order

`cow-order/`, 16 states: `live`, `live-dark`, `past-validity`, `cancellation-on-its-way`, `quoting-cancellation`, `cancel-refused`, `filled`, `not-filled`, `cancelled`, `not-provable`, `cancel-review`, `cancel-not-sent`, `cancel-sent`, `missing`, `loading`, `key-not-here`

<a href="cow-order/live.png"><img src="cow-order/live.png" width="206" alt="live"></a>
<a href="cow-order/live-dark.png"><img src="cow-order/live-dark.png" width="206" alt="live-dark"></a>
<a href="cow-order/past-validity.png"><img src="cow-order/past-validity.png" width="206" alt="past-validity"></a>
<a href="cow-order/cancellation-on-its-way.png"><img src="cow-order/cancellation-on-its-way.png" width="206" alt="cancellation-on-its-way"></a>
<a href="cow-order/quoting-cancellation.png"><img src="cow-order/quoting-cancellation.png" width="206" alt="quoting-cancellation"></a>
<a href="cow-order/cancel-refused.png"><img src="cow-order/cancel-refused.png" width="206" alt="cancel-refused"></a>
<a href="cow-order/filled.png"><img src="cow-order/filled.png" width="206" alt="filled"></a>
<a href="cow-order/not-filled.png"><img src="cow-order/not-filled.png" width="206" alt="not-filled"></a>
<a href="cow-order/cancelled.png"><img src="cow-order/cancelled.png" width="206" alt="cancelled"></a>
<a href="cow-order/not-provable.png"><img src="cow-order/not-provable.png" width="206" alt="not-provable"></a>
<a href="cow-order/cancel-review.png"><img src="cow-order/cancel-review.png" width="206" alt="cancel-review"></a>
<a href="cow-order/cancel-not-sent.png"><img src="cow-order/cancel-not-sent.png" width="206" alt="cancel-not-sent"></a>
<a href="cow-order/cancel-sent.png"><img src="cow-order/cancel-sent.png" width="206" alt="cancel-sent"></a>
<a href="cow-order/missing.png"><img src="cow-order/missing.png" width="206" alt="missing"></a>
<a href="cow-order/loading.png"><img src="cow-order/loading.png" width="206" alt="loading"></a>
<a href="cow-order/key-not-here.png"><img src="cow-order/key-not-here.png" width="206" alt="key-not-here"></a>

<a name="create-account"></a>
## Create account

`create-account/`, 16 states: `preparing`, `showing`, `showing-dark`, `checking`, `checked-wrong`, `new-password`, `password-hint`, `wrong-password`, `creating`, `key-store-stopped`, `created`, `created-stepped-down`, `restart`, `not-listed`, `key-store-unavailable`, `no-network`

<a href="create-account/preparing.png"><img src="create-account/preparing.png" width="206" alt="preparing"></a>
<a href="create-account/showing.png"><img src="create-account/showing.png" width="206" alt="showing"></a>
<a href="create-account/showing-dark.png"><img src="create-account/showing-dark.png" width="206" alt="showing-dark"></a>
<a href="create-account/checking.png"><img src="create-account/checking.png" width="206" alt="checking"></a>
<a href="create-account/checked-wrong.png"><img src="create-account/checked-wrong.png" width="206" alt="checked-wrong"></a>
<a href="create-account/new-password.png"><img src="create-account/new-password.png" width="206" alt="new-password"></a>
<a href="create-account/password-hint.png"><img src="create-account/password-hint.png" width="206" alt="password-hint"></a>
<a href="create-account/wrong-password.png"><img src="create-account/wrong-password.png" width="206" alt="wrong-password"></a>
<a href="create-account/creating.png"><img src="create-account/creating.png" width="206" alt="creating"></a>
<a href="create-account/key-store-stopped.png"><img src="create-account/key-store-stopped.png" width="206" alt="key-store-stopped"></a>
<a href="create-account/created.png"><img src="create-account/created.png" width="206" alt="created"></a>
<a href="create-account/created-stepped-down.png"><img src="create-account/created-stepped-down.png" width="206" alt="created-stepped-down"></a>
<a href="create-account/restart.png"><img src="create-account/restart.png" width="206" alt="restart"></a>
<a href="create-account/not-listed.png"><img src="create-account/not-listed.png" width="206" alt="not-listed"></a>
<a href="create-account/key-store-unavailable.png"><img src="create-account/key-store-unavailable.png" width="206" alt="key-store-unavailable"></a>
<a href="create-account/no-network.png"><img src="create-account/no-network.png" width="206" alt="no-network"></a>

<a name="create-safe"></a>
## Create Safe

`create-safe/`, 19 states: `owners`, `owners-dark`, `typing-an-owner`, `owners-refused`, `threshold`, `threshold-dark`, `threshold-refused`, `review`, `review-dark`, `review-typed`, `review-quoting`, `review-refused`, `review-biometrics`, `not-sent`, `review-signing`, `sent`, `cannot-create`, `loading`, `networks`

<a href="create-safe/owners.png"><img src="create-safe/owners.png" width="206" alt="owners"></a>
<a href="create-safe/owners-dark.png"><img src="create-safe/owners-dark.png" width="206" alt="owners-dark"></a>
<a href="create-safe/typing-an-owner.png"><img src="create-safe/typing-an-owner.png" width="206" alt="typing-an-owner"></a>
<a href="create-safe/owners-refused.png"><img src="create-safe/owners-refused.png" width="206" alt="owners-refused"></a>
<a href="create-safe/threshold.png"><img src="create-safe/threshold.png" width="206" alt="threshold"></a>
<a href="create-safe/threshold-dark.png"><img src="create-safe/threshold-dark.png" width="206" alt="threshold-dark"></a>
<a href="create-safe/threshold-refused.png"><img src="create-safe/threshold-refused.png" width="206" alt="threshold-refused"></a>
<a href="create-safe/review.png"><img src="create-safe/review.png" width="206" alt="review"></a>
<a href="create-safe/review-dark.png"><img src="create-safe/review-dark.png" width="206" alt="review-dark"></a>
<a href="create-safe/review-typed.png"><img src="create-safe/review-typed.png" width="206" alt="review-typed"></a>
<a href="create-safe/review-quoting.png"><img src="create-safe/review-quoting.png" width="206" alt="review-quoting"></a>
<a href="create-safe/review-refused.png"><img src="create-safe/review-refused.png" width="206" alt="review-refused"></a>
<a href="create-safe/review-biometrics.png"><img src="create-safe/review-biometrics.png" width="206" alt="review-biometrics"></a>
<a href="create-safe/not-sent.png"><img src="create-safe/not-sent.png" width="206" alt="not-sent"></a>
<a href="create-safe/review-signing.png"><img src="create-safe/review-signing.png" width="206" alt="review-signing"></a>
<a href="create-safe/sent.png"><img src="create-safe/sent.png" width="206" alt="sent"></a>
<a href="create-safe/cannot-create.png"><img src="create-safe/cannot-create.png" width="206" alt="cannot-create"></a>
<a href="create-safe/loading.png"><img src="create-safe/loading.png" width="206" alt="loading"></a>
<a href="create-safe/networks.png"><img src="create-safe/networks.png" width="206" alt="networks"></a>

<a name="currency"></a>
## Display currency

`currency/`, 4 states: `chosen`, `chosen-dark`, `set-aside`, `unreadable`

<a href="currency/chosen.png"><img src="currency/chosen.png" width="206" alt="chosen"></a>
<a href="currency/chosen-dark.png"><img src="currency/chosen-dark.png" width="206" alt="chosen-dark"></a>
<a href="currency/set-aside.png"><img src="currency/set-aside.png" width="206" alt="set-aside"></a>
<a href="currency/unreadable.png"><img src="currency/unreadable.png" width="206" alt="unreadable"></a>

<a name="dapp-proposal"></a>
## dApp connection request

`dapp-proposal/`, 14 states: `request`, `request-dark`, `none-chosen`, `either-kind`, `real-funds`, `real-funds-dark`, `missing-required`, `missing-required-dark`, `mixed-funds-refused`, `refused`, `explaining`, `not-saved`, `ended`, `loading`

<a href="dapp-proposal/request.png"><img src="dapp-proposal/request.png" width="206" alt="request"></a>
<a href="dapp-proposal/request-dark.png"><img src="dapp-proposal/request-dark.png" width="206" alt="request-dark"></a>
<a href="dapp-proposal/none-chosen.png"><img src="dapp-proposal/none-chosen.png" width="206" alt="none-chosen"></a>
<a href="dapp-proposal/either-kind.png"><img src="dapp-proposal/either-kind.png" width="206" alt="either-kind"></a>
<a href="dapp-proposal/real-funds.png"><img src="dapp-proposal/real-funds.png" width="206" alt="real-funds"></a>
<a href="dapp-proposal/real-funds-dark.png"><img src="dapp-proposal/real-funds-dark.png" width="206" alt="real-funds-dark"></a>
<a href="dapp-proposal/missing-required.png"><img src="dapp-proposal/missing-required.png" width="206" alt="missing-required"></a>
<a href="dapp-proposal/missing-required-dark.png"><img src="dapp-proposal/missing-required-dark.png" width="206" alt="missing-required-dark"></a>
<a href="dapp-proposal/mixed-funds-refused.png"><img src="dapp-proposal/mixed-funds-refused.png" width="206" alt="mixed-funds-refused"></a>
<a href="dapp-proposal/refused.png"><img src="dapp-proposal/refused.png" width="206" alt="refused"></a>
<a href="dapp-proposal/explaining.png"><img src="dapp-proposal/explaining.png" width="206" alt="explaining"></a>
<a href="dapp-proposal/not-saved.png"><img src="dapp-proposal/not-saved.png" width="206" alt="not-saved"></a>
<a href="dapp-proposal/ended.png"><img src="dapp-proposal/ended.png" width="206" alt="ended"></a>
<a href="dapp-proposal/loading.png"><img src="dapp-proposal/loading.png" width="206" alt="loading"></a>

<a name="dapp-request"></a>
## dApp signing request

`dapp-request/`, 20 states: `sign-in`, `sign-in-dark`, `biometrics`, `other-site`, `message`, `hex`, `refused`, `wrong-password`, `signing`, `explaining`, `ended`, `loading`, `approval`, `approval-dark`, `unlimited`, `swap-order`, `not-decoded`, `other-network`, `real-funds`, `real-funds-dark`

<a href="dapp-request/sign-in.png"><img src="dapp-request/sign-in.png" width="206" alt="sign-in"></a>
<a href="dapp-request/sign-in-dark.png"><img src="dapp-request/sign-in-dark.png" width="206" alt="sign-in-dark"></a>
<a href="dapp-request/biometrics.png"><img src="dapp-request/biometrics.png" width="206" alt="biometrics"></a>
<a href="dapp-request/other-site.png"><img src="dapp-request/other-site.png" width="206" alt="other-site"></a>
<a href="dapp-request/message.png"><img src="dapp-request/message.png" width="206" alt="message"></a>
<a href="dapp-request/hex.png"><img src="dapp-request/hex.png" width="206" alt="hex"></a>
<a href="dapp-request/refused.png"><img src="dapp-request/refused.png" width="206" alt="refused"></a>
<a href="dapp-request/wrong-password.png"><img src="dapp-request/wrong-password.png" width="206" alt="wrong-password"></a>
<a href="dapp-request/signing.png"><img src="dapp-request/signing.png" width="206" alt="signing"></a>
<a href="dapp-request/explaining.png"><img src="dapp-request/explaining.png" width="206" alt="explaining"></a>
<a href="dapp-request/ended.png"><img src="dapp-request/ended.png" width="206" alt="ended"></a>
<a href="dapp-request/loading.png"><img src="dapp-request/loading.png" width="206" alt="loading"></a>
<a href="dapp-request/approval.png"><img src="dapp-request/approval.png" width="206" alt="approval"></a>
<a href="dapp-request/approval-dark.png"><img src="dapp-request/approval-dark.png" width="206" alt="approval-dark"></a>
<a href="dapp-request/unlimited.png"><img src="dapp-request/unlimited.png" width="206" alt="unlimited"></a>
<a href="dapp-request/swap-order.png"><img src="dapp-request/swap-order.png" width="206" alt="swap-order"></a>
<a href="dapp-request/not-decoded.png"><img src="dapp-request/not-decoded.png" width="206" alt="not-decoded"></a>
<a href="dapp-request/other-network.png"><img src="dapp-request/other-network.png" width="206" alt="other-network"></a>
<a href="dapp-request/real-funds.png"><img src="dapp-request/real-funds.png" width="206" alt="real-funds"></a>
<a href="dapp-request/real-funds-dark.png"><img src="dapp-request/real-funds-dark.png" width="206" alt="real-funds-dark"></a>

<a name="dapp-transaction"></a>
## dApp transaction

`dapp-transaction/`, 20 states: `payment`, `payment-dark`, `token-transfer`, `approval`, `not-decoded`, `quoting`, `not-quoted`, `refused`, `safe`, `safe-dark`, `safe-no-owner`, `safe-signing`, `safe-queued`, `sending`, `not-sent`, `explaining`, `ended`, `loading`, `real-funds`, `real-funds-dark`

<a href="dapp-transaction/payment.png"><img src="dapp-transaction/payment.png" width="206" alt="payment"></a>
<a href="dapp-transaction/payment-dark.png"><img src="dapp-transaction/payment-dark.png" width="206" alt="payment-dark"></a>
<a href="dapp-transaction/token-transfer.png"><img src="dapp-transaction/token-transfer.png" width="206" alt="token-transfer"></a>
<a href="dapp-transaction/approval.png"><img src="dapp-transaction/approval.png" width="206" alt="approval"></a>
<a href="dapp-transaction/not-decoded.png"><img src="dapp-transaction/not-decoded.png" width="206" alt="not-decoded"></a>
<a href="dapp-transaction/quoting.png"><img src="dapp-transaction/quoting.png" width="206" alt="quoting"></a>
<a href="dapp-transaction/not-quoted.png"><img src="dapp-transaction/not-quoted.png" width="206" alt="not-quoted"></a>
<a href="dapp-transaction/refused.png"><img src="dapp-transaction/refused.png" width="206" alt="refused"></a>
<a href="dapp-transaction/safe.png"><img src="dapp-transaction/safe.png" width="206" alt="safe"></a>
<a href="dapp-transaction/safe-dark.png"><img src="dapp-transaction/safe-dark.png" width="206" alt="safe-dark"></a>
<a href="dapp-transaction/safe-no-owner.png"><img src="dapp-transaction/safe-no-owner.png" width="206" alt="safe-no-owner"></a>
<a href="dapp-transaction/safe-signing.png"><img src="dapp-transaction/safe-signing.png" width="206" alt="safe-signing"></a>
<a href="dapp-transaction/safe-queued.png"><img src="dapp-transaction/safe-queued.png" width="206" alt="safe-queued"></a>
<a href="dapp-transaction/sending.png"><img src="dapp-transaction/sending.png" width="206" alt="sending"></a>
<a href="dapp-transaction/not-sent.png"><img src="dapp-transaction/not-sent.png" width="206" alt="not-sent"></a>
<a href="dapp-transaction/explaining.png"><img src="dapp-transaction/explaining.png" width="206" alt="explaining"></a>
<a href="dapp-transaction/ended.png"><img src="dapp-transaction/ended.png" width="206" alt="ended"></a>
<a href="dapp-transaction/loading.png"><img src="dapp-transaction/loading.png" width="206" alt="loading"></a>
<a href="dapp-transaction/real-funds.png"><img src="dapp-transaction/real-funds.png" width="206" alt="real-funds"></a>
<a href="dapp-transaction/real-funds-dark.png"><img src="dapp-transaction/real-funds-dark.png" width="206" alt="real-funds-dark"></a>

<a name="debug"></a>
## Debug

`debug/`, 6 states: `info`, `info-dark`, `detail`, `empty`, `filtered`, `no-match`

<a href="debug/info.png"><img src="debug/info.png" width="206" alt="info"></a>
<a href="debug/info-dark.png"><img src="debug/info-dark.png" width="206" alt="info-dark"></a>
<a href="debug/detail.png"><img src="debug/detail.png" width="206" alt="detail"></a>
<a href="debug/empty.png"><img src="debug/empty.png" width="206" alt="empty"></a>
<a href="debug/filtered.png"><img src="debug/filtered.png" width="206" alt="filtered"></a>
<a href="debug/no-match.png"><img src="debug/no-match.png" width="206" alt="no-match"></a>

<a name="derive-account"></a>
## Add account from the same phrase

`derive-account/`, 10 states: `ask`, `ask-dark`, `typed`, `deriving`, `wrong-password`, `added`, `added-stepped-down`, `added-dark`, `unavailable`, `loading`

<a href="derive-account/ask.png"><img src="derive-account/ask.png" width="206" alt="ask"></a>
<a href="derive-account/ask-dark.png"><img src="derive-account/ask-dark.png" width="206" alt="ask-dark"></a>
<a href="derive-account/typed.png"><img src="derive-account/typed.png" width="206" alt="typed"></a>
<a href="derive-account/deriving.png"><img src="derive-account/deriving.png" width="206" alt="deriving"></a>
<a href="derive-account/wrong-password.png"><img src="derive-account/wrong-password.png" width="206" alt="wrong-password"></a>
<a href="derive-account/added.png"><img src="derive-account/added.png" width="206" alt="added"></a>
<a href="derive-account/added-stepped-down.png"><img src="derive-account/added-stepped-down.png" width="206" alt="added-stepped-down"></a>
<a href="derive-account/added-dark.png"><img src="derive-account/added-dark.png" width="206" alt="added-dark"></a>
<a href="derive-account/unavailable.png"><img src="derive-account/unavailable.png" width="206" alt="unavailable"></a>
<a href="derive-account/loading.png"><img src="derive-account/loading.png" width="206" alt="loading"></a>

<a name="handoff"></a>
## Send from another wallet

`handoff/`, 18 states: `compose`, `compose-dark`, `compose-over-balance`, `review`, `review-dark`, `review-metamask-limit`, `review-opening`, `review-no-wallet`, `waiting`, `waiting-dark`, `waiting-syncing`, `sent`, `failed`, `other-sender`, `other-amount`, `not-seen`, `loading`, `cannot`

<a href="handoff/compose.png"><img src="handoff/compose.png" width="206" alt="compose"></a>
<a href="handoff/compose-dark.png"><img src="handoff/compose-dark.png" width="206" alt="compose-dark"></a>
<a href="handoff/compose-over-balance.png"><img src="handoff/compose-over-balance.png" width="206" alt="compose-over-balance"></a>
<a href="handoff/review.png"><img src="handoff/review.png" width="206" alt="review"></a>
<a href="handoff/review-dark.png"><img src="handoff/review-dark.png" width="206" alt="review-dark"></a>
<a href="handoff/review-metamask-limit.png"><img src="handoff/review-metamask-limit.png" width="206" alt="review-metamask-limit"></a>
<a href="handoff/review-opening.png"><img src="handoff/review-opening.png" width="206" alt="review-opening"></a>
<a href="handoff/review-no-wallet.png"><img src="handoff/review-no-wallet.png" width="206" alt="review-no-wallet"></a>
<a href="handoff/waiting.png"><img src="handoff/waiting.png" width="206" alt="waiting"></a>
<a href="handoff/waiting-dark.png"><img src="handoff/waiting-dark.png" width="206" alt="waiting-dark"></a>
<a href="handoff/waiting-syncing.png"><img src="handoff/waiting-syncing.png" width="206" alt="waiting-syncing"></a>
<a href="handoff/sent.png"><img src="handoff/sent.png" width="206" alt="sent"></a>
<a href="handoff/failed.png"><img src="handoff/failed.png" width="206" alt="failed"></a>
<a href="handoff/other-sender.png"><img src="handoff/other-sender.png" width="206" alt="other-sender"></a>
<a href="handoff/other-amount.png"><img src="handoff/other-amount.png" width="206" alt="other-amount"></a>
<a href="handoff/not-seen.png"><img src="handoff/not-seen.png" width="206" alt="not-seen"></a>
<a href="handoff/loading.png"><img src="handoff/loading.png" width="206" alt="loading"></a>
<a href="handoff/cannot.png"><img src="handoff/cannot.png" width="206" alt="cannot"></a>

<a name="mnemonic-import"></a>
## Import recovery phrase

`mnemonic-import/`, 12 states: `checking`, `new-password`, `new-password-dark`, `password-hint`, `wrong-password`, `invalid-words`, `not-enough-memory`, `importing`, `key-store-unavailable`, `imported`, `imported-stepped-down`, `no-network`

<a href="mnemonic-import/checking.png"><img src="mnemonic-import/checking.png" width="206" alt="checking"></a>
<a href="mnemonic-import/new-password.png"><img src="mnemonic-import/new-password.png" width="206" alt="new-password"></a>
<a href="mnemonic-import/new-password-dark.png"><img src="mnemonic-import/new-password-dark.png" width="206" alt="new-password-dark"></a>
<a href="mnemonic-import/password-hint.png"><img src="mnemonic-import/password-hint.png" width="206" alt="password-hint"></a>
<a href="mnemonic-import/wrong-password.png"><img src="mnemonic-import/wrong-password.png" width="206" alt="wrong-password"></a>
<a href="mnemonic-import/invalid-words.png"><img src="mnemonic-import/invalid-words.png" width="206" alt="invalid-words"></a>
<a href="mnemonic-import/not-enough-memory.png"><img src="mnemonic-import/not-enough-memory.png" width="206" alt="not-enough-memory"></a>
<a href="mnemonic-import/importing.png"><img src="mnemonic-import/importing.png" width="206" alt="importing"></a>
<a href="mnemonic-import/key-store-unavailable.png"><img src="mnemonic-import/key-store-unavailable.png" width="206" alt="key-store-unavailable"></a>
<a href="mnemonic-import/imported.png"><img src="mnemonic-import/imported.png" width="206" alt="imported"></a>
<a href="mnemonic-import/imported-stepped-down.png"><img src="mnemonic-import/imported-stepped-down.png" width="206" alt="imported-stepped-down"></a>
<a href="mnemonic-import/no-network.png"><img src="mnemonic-import/no-network.png" width="206" alt="no-network"></a>

<a name="pair-dapp"></a>
## Connect a dApp

`pair-dapp/`, 11 states: `empty`, `pasted`, `pasted-dark`, `busy`, `expired`, `unreadable`, `with-scan`, `with-scan-dark`, `camera-refused`, `scanned-not-a-link`, `scanner-frame`

<a href="pair-dapp/empty.png"><img src="pair-dapp/empty.png" width="206" alt="empty"></a>
<a href="pair-dapp/pasted.png"><img src="pair-dapp/pasted.png" width="206" alt="pasted"></a>
<a href="pair-dapp/pasted-dark.png"><img src="pair-dapp/pasted-dark.png" width="206" alt="pasted-dark"></a>
<a href="pair-dapp/busy.png"><img src="pair-dapp/busy.png" width="206" alt="busy"></a>
<a href="pair-dapp/expired.png"><img src="pair-dapp/expired.png" width="206" alt="expired"></a>
<a href="pair-dapp/unreadable.png"><img src="pair-dapp/unreadable.png" width="206" alt="unreadable"></a>
<a href="pair-dapp/with-scan.png"><img src="pair-dapp/with-scan.png" width="206" alt="with-scan"></a>
<a href="pair-dapp/with-scan-dark.png"><img src="pair-dapp/with-scan-dark.png" width="206" alt="with-scan-dark"></a>
<a href="pair-dapp/camera-refused.png"><img src="pair-dapp/camera-refused.png" width="206" alt="camera-refused"></a>
<a href="pair-dapp/scanned-not-a-link.png"><img src="pair-dapp/scanned-not-a-link.png" width="206" alt="scanned-not-a-link"></a>
<a href="pair-dapp/scanner-frame.png"><img src="pair-dapp/scanner-frame.png" width="206" alt="scanner-frame"></a>

<a name="receive"></a>
## Receive

`receive/`, 7 states: `account`, `account-dark`, `safe-without-share`, `loading`, `list-unavailable`, `gnosis`, `network-off`

<a href="receive/account.png"><img src="receive/account.png" width="206" alt="account"></a>
<a href="receive/account-dark.png"><img src="receive/account-dark.png" width="206" alt="account-dark"></a>
<a href="receive/safe-without-share.png"><img src="receive/safe-without-share.png" width="206" alt="safe-without-share"></a>
<a href="receive/loading.png"><img src="receive/loading.png" width="206" alt="loading"></a>
<a href="receive/list-unavailable.png"><img src="receive/list-unavailable.png" width="206" alt="list-unavailable"></a>
<a href="receive/gnosis.png"><img src="receive/gnosis.png" width="206" alt="gnosis"></a>
<a href="receive/network-off.png"><img src="receive/network-off.png" width="206" alt="network-off"></a>

<a name="remove-account-dialog"></a>
## Remove account (dialog)

`remove-account-dialog/`, 7 states: `keyed`, `keyed-dark`, `watched`, `removing`, `key-kept`, `loading`, `key-stays`

<a href="remove-account-dialog/keyed.png"><img src="remove-account-dialog/keyed.png" width="206" alt="keyed"></a>
<a href="remove-account-dialog/keyed-dark.png"><img src="remove-account-dialog/keyed-dark.png" width="206" alt="keyed-dark"></a>
<a href="remove-account-dialog/watched.png"><img src="remove-account-dialog/watched.png" width="206" alt="watched"></a>
<a href="remove-account-dialog/removing.png"><img src="remove-account-dialog/removing.png" width="206" alt="removing"></a>
<a href="remove-account-dialog/key-kept.png"><img src="remove-account-dialog/key-kept.png" width="206" alt="key-kept"></a>
<a href="remove-account-dialog/loading.png"><img src="remove-account-dialog/loading.png" width="206" alt="loading"></a>
<a href="remove-account-dialog/key-stays.png"><img src="remove-account-dialog/key-stays.png" width="206" alt="key-stays"></a>

<a name="rename-account-dialog"></a>
## Rename account (dialog)

`rename-account-dialog/`, 6 states: `editing`, `editing-dark`, `blank`, `saving`, `list-unavailable`, `loading`

<a href="rename-account-dialog/editing.png"><img src="rename-account-dialog/editing.png" width="206" alt="editing"></a>
<a href="rename-account-dialog/editing-dark.png"><img src="rename-account-dialog/editing-dark.png" width="206" alt="editing-dark"></a>
<a href="rename-account-dialog/blank.png"><img src="rename-account-dialog/blank.png" width="206" alt="blank"></a>
<a href="rename-account-dialog/saving.png"><img src="rename-account-dialog/saving.png" width="206" alt="saving"></a>
<a href="rename-account-dialog/list-unavailable.png"><img src="rename-account-dialog/list-unavailable.png" width="206" alt="list-unavailable"></a>
<a href="rename-account-dialog/loading.png"><img src="rename-account-dialog/loading.png" width="206" alt="loading"></a>

<a name="safe-import"></a>
## Import Safe

`safe-import/`, 7 states: `empty`, `empty-dark`, `pasted`, `checking`, `refused`, `imported`, `wrong-network`

<a href="safe-import/empty.png"><img src="safe-import/empty.png" width="206" alt="empty"></a>
<a href="safe-import/empty-dark.png"><img src="safe-import/empty-dark.png" width="206" alt="empty-dark"></a>
<a href="safe-import/pasted.png"><img src="safe-import/pasted.png" width="206" alt="pasted"></a>
<a href="safe-import/checking.png"><img src="safe-import/checking.png" width="206" alt="checking"></a>
<a href="safe-import/refused.png"><img src="safe-import/refused.png" width="206" alt="refused"></a>
<a href="safe-import/imported.png"><img src="safe-import/imported.png" width="206" alt="imported"></a>
<a href="safe-import/wrong-network.png"><img src="safe-import/wrong-network.png" width="206" alt="wrong-network"></a>

<a name="safe-propose"></a>
## Propose Safe transaction

`safe-propose/`, 17 states: `compose`, `compose-dark`, `refused`, `proposing`, `review`, `review-dark`, `review-queued-not-simulated`, `review-not-saved`, `review-asking-to-drop`, `review-signing`, `review-checking`, `loading`, `watch-only`, `draft`, `draft-dark`, `draft-nonce-busy`, `never-verified`

<a href="safe-propose/compose.png"><img src="safe-propose/compose.png" width="206" alt="compose"></a>
<a href="safe-propose/compose-dark.png"><img src="safe-propose/compose-dark.png" width="206" alt="compose-dark"></a>
<a href="safe-propose/refused.png"><img src="safe-propose/refused.png" width="206" alt="refused"></a>
<a href="safe-propose/proposing.png"><img src="safe-propose/proposing.png" width="206" alt="proposing"></a>
<a href="safe-propose/review.png"><img src="safe-propose/review.png" width="206" alt="review"></a>
<a href="safe-propose/review-dark.png"><img src="safe-propose/review-dark.png" width="206" alt="review-dark"></a>
<a href="safe-propose/review-queued-not-simulated.png"><img src="safe-propose/review-queued-not-simulated.png" width="206" alt="review-queued-not-simulated"></a>
<a href="safe-propose/review-not-saved.png"><img src="safe-propose/review-not-saved.png" width="206" alt="review-not-saved"></a>
<a href="safe-propose/review-asking-to-drop.png"><img src="safe-propose/review-asking-to-drop.png" width="206" alt="review-asking-to-drop"></a>
<a href="safe-propose/review-signing.png"><img src="safe-propose/review-signing.png" width="206" alt="review-signing"></a>
<a href="safe-propose/review-checking.png"><img src="safe-propose/review-checking.png" width="206" alt="review-checking"></a>
<a href="safe-propose/loading.png"><img src="safe-propose/loading.png" width="206" alt="loading"></a>
<a href="safe-propose/watch-only.png"><img src="safe-propose/watch-only.png" width="206" alt="watch-only"></a>
<a href="safe-propose/draft.png"><img src="safe-propose/draft.png" width="206" alt="draft"></a>
<a href="safe-propose/draft-dark.png"><img src="safe-propose/draft-dark.png" width="206" alt="draft-dark"></a>
<a href="safe-propose/draft-nonce-busy.png"><img src="safe-propose/draft-nonce-busy.png" width="206" alt="draft-nonce-busy"></a>
<a href="safe-propose/never-verified.png"><img src="safe-propose/never-verified.png" width="206" alt="never-verified"></a>

<a name="safe-settings"></a>
## Safe settings

`safe-settings/`, 17 states: `overview`, `overview-dark`, `modules-and-guard`, `watch-only`, `add-owner`, `add-owner-proposing`, `replace-owner`, `remove-owner-refused`, `remove-guard`, `review`, `review-dark`, `review-queued`, `review-not-saved`, `review-asking-to-drop`, `review-checking`, `loading`, `unavailable`

<a href="safe-settings/overview.png"><img src="safe-settings/overview.png" width="206" alt="overview"></a>
<a href="safe-settings/overview-dark.png"><img src="safe-settings/overview-dark.png" width="206" alt="overview-dark"></a>
<a href="safe-settings/modules-and-guard.png"><img src="safe-settings/modules-and-guard.png" width="206" alt="modules-and-guard"></a>
<a href="safe-settings/watch-only.png"><img src="safe-settings/watch-only.png" width="206" alt="watch-only"></a>
<a href="safe-settings/add-owner.png"><img src="safe-settings/add-owner.png" width="206" alt="add-owner"></a>
<a href="safe-settings/add-owner-proposing.png"><img src="safe-settings/add-owner-proposing.png" width="206" alt="add-owner-proposing"></a>
<a href="safe-settings/replace-owner.png"><img src="safe-settings/replace-owner.png" width="206" alt="replace-owner"></a>
<a href="safe-settings/remove-owner-refused.png"><img src="safe-settings/remove-owner-refused.png" width="206" alt="remove-owner-refused"></a>
<a href="safe-settings/remove-guard.png"><img src="safe-settings/remove-guard.png" width="206" alt="remove-guard"></a>
<a href="safe-settings/review.png"><img src="safe-settings/review.png" width="206" alt="review"></a>
<a href="safe-settings/review-dark.png"><img src="safe-settings/review-dark.png" width="206" alt="review-dark"></a>
<a href="safe-settings/review-queued.png"><img src="safe-settings/review-queued.png" width="206" alt="review-queued"></a>
<a href="safe-settings/review-not-saved.png"><img src="safe-settings/review-not-saved.png" width="206" alt="review-not-saved"></a>
<a href="safe-settings/review-asking-to-drop.png"><img src="safe-settings/review-asking-to-drop.png" width="206" alt="review-asking-to-drop"></a>
<a href="safe-settings/review-checking.png"><img src="safe-settings/review-checking.png" width="206" alt="review-checking"></a>
<a href="safe-settings/loading.png"><img src="safe-settings/loading.png" width="206" alt="loading"></a>
<a href="safe-settings/unavailable.png"><img src="safe-settings/unavailable.png" width="206" alt="unavailable"></a>

<a name="safe-swap"></a>
## Safe swap

`safe-swap/`, 13 states: `compose`, `compose-dark`, `refused`, `propose-again`, `review`, `review-dark`, `review-swap-only`, `review-reset-first`, `review-replaces`, `review-signing`, `review-half-signed`, `loading`, `watch-only`

<a href="safe-swap/compose.png"><img src="safe-swap/compose.png" width="206" alt="compose"></a>
<a href="safe-swap/compose-dark.png"><img src="safe-swap/compose-dark.png" width="206" alt="compose-dark"></a>
<a href="safe-swap/refused.png"><img src="safe-swap/refused.png" width="206" alt="refused"></a>
<a href="safe-swap/propose-again.png"><img src="safe-swap/propose-again.png" width="206" alt="propose-again"></a>
<a href="safe-swap/review.png"><img src="safe-swap/review.png" width="206" alt="review"></a>
<a href="safe-swap/review-dark.png"><img src="safe-swap/review-dark.png" width="206" alt="review-dark"></a>
<a href="safe-swap/review-swap-only.png"><img src="safe-swap/review-swap-only.png" width="206" alt="review-swap-only"></a>
<a href="safe-swap/review-reset-first.png"><img src="safe-swap/review-reset-first.png" width="206" alt="review-reset-first"></a>
<a href="safe-swap/review-replaces.png"><img src="safe-swap/review-replaces.png" width="206" alt="review-replaces"></a>
<a href="safe-swap/review-signing.png"><img src="safe-swap/review-signing.png" width="206" alt="review-signing"></a>
<a href="safe-swap/review-half-signed.png"><img src="safe-swap/review-half-signed.png" width="206" alt="review-half-signed"></a>
<a href="safe-swap/loading.png"><img src="safe-swap/loading.png" width="206" alt="loading"></a>
<a href="safe-swap/watch-only.png"><img src="safe-swap/watch-only.png" width="206" alt="watch-only"></a>

<a name="safe-tx"></a>
## Safe transaction

`safe-tx/`, 45 states: `overview`, `overview-dark`, `share`, `share-as-text`, `signed`, `not-next`, `delegate-call`, `unpinned-fallback-handler`, `module-enabled`, `execute-reason`, `gas-terms`, `owners-checking`, `owners-unavailable`, `quoting`, `signing`, `signing-dark`, `signing-wrong-password`, `signing-runs`, `signing-checking`, `review-execution`, `review-execution-dark`, `review-approval`, `review-refreshing`, `review-signing`, `review-not-sent`, `loading`, `not-queued`, `swap`, `swap-minimum-behind`, `swap-expired`, `swap-not-uniswap`, `swap-approval`, `swap-propose-again-greyed`, `fallback-handler-removed`, `lenient-overview`, `lenient-signing`, `held`, `held-dark`, `held-checking`, `held-conflict`, `held-discarded`, `held-co-signature`, `draft-approval`, `draft-execution`, `draft-dark`

<a href="safe-tx/overview.png"><img src="safe-tx/overview.png" width="206" alt="overview"></a>
<a href="safe-tx/overview-dark.png"><img src="safe-tx/overview-dark.png" width="206" alt="overview-dark"></a>
<a href="safe-tx/share.png"><img src="safe-tx/share.png" width="206" alt="share"></a>
<a href="safe-tx/share-as-text.png"><img src="safe-tx/share-as-text.png" width="206" alt="share-as-text"></a>
<a href="safe-tx/signed.png"><img src="safe-tx/signed.png" width="206" alt="signed"></a>
<a href="safe-tx/not-next.png"><img src="safe-tx/not-next.png" width="206" alt="not-next"></a>
<a href="safe-tx/delegate-call.png"><img src="safe-tx/delegate-call.png" width="206" alt="delegate-call"></a>
<a href="safe-tx/unpinned-fallback-handler.png"><img src="safe-tx/unpinned-fallback-handler.png" width="206" alt="unpinned-fallback-handler"></a>
<a href="safe-tx/module-enabled.png"><img src="safe-tx/module-enabled.png" width="206" alt="module-enabled"></a>
<a href="safe-tx/execute-reason.png"><img src="safe-tx/execute-reason.png" width="206" alt="execute-reason"></a>
<a href="safe-tx/gas-terms.png"><img src="safe-tx/gas-terms.png" width="206" alt="gas-terms"></a>
<a href="safe-tx/owners-checking.png"><img src="safe-tx/owners-checking.png" width="206" alt="owners-checking"></a>
<a href="safe-tx/owners-unavailable.png"><img src="safe-tx/owners-unavailable.png" width="206" alt="owners-unavailable"></a>
<a href="safe-tx/quoting.png"><img src="safe-tx/quoting.png" width="206" alt="quoting"></a>
<a href="safe-tx/signing.png"><img src="safe-tx/signing.png" width="206" alt="signing"></a>
<a href="safe-tx/signing-dark.png"><img src="safe-tx/signing-dark.png" width="206" alt="signing-dark"></a>
<a href="safe-tx/signing-wrong-password.png"><img src="safe-tx/signing-wrong-password.png" width="206" alt="signing-wrong-password"></a>
<a href="safe-tx/signing-runs.png"><img src="safe-tx/signing-runs.png" width="206" alt="signing-runs"></a>
<a href="safe-tx/signing-checking.png"><img src="safe-tx/signing-checking.png" width="206" alt="signing-checking"></a>
<a href="safe-tx/review-execution.png"><img src="safe-tx/review-execution.png" width="206" alt="review-execution"></a>
<a href="safe-tx/review-execution-dark.png"><img src="safe-tx/review-execution-dark.png" width="206" alt="review-execution-dark"></a>
<a href="safe-tx/review-approval.png"><img src="safe-tx/review-approval.png" width="206" alt="review-approval"></a>
<a href="safe-tx/review-refreshing.png"><img src="safe-tx/review-refreshing.png" width="206" alt="review-refreshing"></a>
<a href="safe-tx/review-signing.png"><img src="safe-tx/review-signing.png" width="206" alt="review-signing"></a>
<a href="safe-tx/review-not-sent.png"><img src="safe-tx/review-not-sent.png" width="206" alt="review-not-sent"></a>
<a href="safe-tx/loading.png"><img src="safe-tx/loading.png" width="206" alt="loading"></a>
<a href="safe-tx/not-queued.png"><img src="safe-tx/not-queued.png" width="206" alt="not-queued"></a>
<a href="safe-tx/swap.png"><img src="safe-tx/swap.png" width="206" alt="swap"></a>
<a href="safe-tx/swap-minimum-behind.png"><img src="safe-tx/swap-minimum-behind.png" width="206" alt="swap-minimum-behind"></a>
<a href="safe-tx/swap-expired.png"><img src="safe-tx/swap-expired.png" width="206" alt="swap-expired"></a>
<a href="safe-tx/swap-not-uniswap.png"><img src="safe-tx/swap-not-uniswap.png" width="206" alt="swap-not-uniswap"></a>
<a href="safe-tx/swap-approval.png"><img src="safe-tx/swap-approval.png" width="206" alt="swap-approval"></a>
<a href="safe-tx/swap-propose-again-greyed.png"><img src="safe-tx/swap-propose-again-greyed.png" width="206" alt="swap-propose-again-greyed"></a>
<a href="safe-tx/fallback-handler-removed.png"><img src="safe-tx/fallback-handler-removed.png" width="206" alt="fallback-handler-removed"></a>
<a href="safe-tx/lenient-overview.png"><img src="safe-tx/lenient-overview.png" width="206" alt="lenient-overview"></a>
<a href="safe-tx/lenient-signing.png"><img src="safe-tx/lenient-signing.png" width="206" alt="lenient-signing"></a>
<a href="safe-tx/held.png"><img src="safe-tx/held.png" width="206" alt="held"></a>
<a href="safe-tx/held-dark.png"><img src="safe-tx/held-dark.png" width="206" alt="held-dark"></a>
<a href="safe-tx/held-checking.png"><img src="safe-tx/held-checking.png" width="206" alt="held-checking"></a>
<a href="safe-tx/held-conflict.png"><img src="safe-tx/held-conflict.png" width="206" alt="held-conflict"></a>
<a href="safe-tx/held-discarded.png"><img src="safe-tx/held-discarded.png" width="206" alt="held-discarded"></a>
<a href="safe-tx/held-co-signature.png"><img src="safe-tx/held-co-signature.png" width="206" alt="held-co-signature"></a>
<a href="safe-tx/draft-approval.png"><img src="safe-tx/draft-approval.png" width="206" alt="draft-approval"></a>
<a href="safe-tx/draft-execution.png"><img src="safe-tx/draft-execution.png" width="206" alt="draft-execution"></a>
<a href="safe-tx/draft-dark.png"><img src="safe-tx/draft-dark.png" width="206" alt="draft-dark"></a>

<a name="screen-capture-warning-dialog"></a>
## Screen capture warning (dialog)

`screen-capture-warning-dialog/`, 3 states: `screenshot`, `screenshot-dark`, `recording`

<a href="screen-capture-warning-dialog/screenshot.png"><img src="screen-capture-warning-dialog/screenshot.png" width="206" alt="screenshot"></a>
<a href="screen-capture-warning-dialog/screenshot-dark.png"><img src="screen-capture-warning-dialog/screenshot-dark.png" width="206" alt="screenshot-dark"></a>
<a href="screen-capture-warning-dialog/recording.png"><img src="screen-capture-warning-dialog/recording.png" width="206" alt="recording"></a>

<a name="security"></a>
## Security

`security/`, 15 states: `off`, `off-dark`, `asking-password`, `wrong-password`, `turning-on`, `on`, `invalidated`, `turning-off`, `weak-only`, `desktop`, `less-memory`, `not-enough-memory`, `no-keys`, `loading`, `unavailable`

<a href="security/off.png"><img src="security/off.png" width="206" alt="off"></a>
<a href="security/off-dark.png"><img src="security/off-dark.png" width="206" alt="off-dark"></a>
<a href="security/asking-password.png"><img src="security/asking-password.png" width="206" alt="asking-password"></a>
<a href="security/wrong-password.png"><img src="security/wrong-password.png" width="206" alt="wrong-password"></a>
<a href="security/turning-on.png"><img src="security/turning-on.png" width="206" alt="turning-on"></a>
<a href="security/on.png"><img src="security/on.png" width="206" alt="on"></a>
<a href="security/invalidated.png"><img src="security/invalidated.png" width="206" alt="invalidated"></a>
<a href="security/turning-off.png"><img src="security/turning-off.png" width="206" alt="turning-off"></a>
<a href="security/weak-only.png"><img src="security/weak-only.png" width="206" alt="weak-only"></a>
<a href="security/desktop.png"><img src="security/desktop.png" width="206" alt="desktop"></a>
<a href="security/less-memory.png"><img src="security/less-memory.png" width="206" alt="less-memory"></a>
<a href="security/not-enough-memory.png"><img src="security/not-enough-memory.png" width="206" alt="not-enough-memory"></a>
<a href="security/no-keys.png"><img src="security/no-keys.png" width="206" alt="no-keys"></a>
<a href="security/loading.png"><img src="security/loading.png" width="206" alt="loading"></a>
<a href="security/unavailable.png"><img src="security/unavailable.png" width="206" alt="unavailable"></a>

<a name="send"></a>
## Send

`send/`, 32 states: `compose-empty`, `compose-empty-dark`, `composed-token-to-name`, `compose-resolving`, `compose-input-errors`, `compose-gnosis-no-names`, `compose-name-unavailable`, `compose-quoting`, `compose-refused`, `compose-earlier-maybe-sent`, `compose-token-not-found`, `review`, `review-dark`, `review-token-with-biometrics`, `review-wrong-password`, `review-biometrics-cancelled`, `review-signing`, `review-refreshing`, `review-not-sent`, `review-rejected`, `loading`, `watch-only`, `sent`, `compose-lenient-last-verified`, `compose-draft-waits`, `compose-queued-first`, `draft-review`, `draft-review-dark`, `draft-review-token`, `draft-review-not-kept`, `draft-review-signing`, `queued`

<a href="send/compose-empty.png"><img src="send/compose-empty.png" width="206" alt="compose-empty"></a>
<a href="send/compose-empty-dark.png"><img src="send/compose-empty-dark.png" width="206" alt="compose-empty-dark"></a>
<a href="send/composed-token-to-name.png"><img src="send/composed-token-to-name.png" width="206" alt="composed-token-to-name"></a>
<a href="send/compose-resolving.png"><img src="send/compose-resolving.png" width="206" alt="compose-resolving"></a>
<a href="send/compose-input-errors.png"><img src="send/compose-input-errors.png" width="206" alt="compose-input-errors"></a>
<a href="send/compose-gnosis-no-names.png"><img src="send/compose-gnosis-no-names.png" width="206" alt="compose-gnosis-no-names"></a>
<a href="send/compose-name-unavailable.png"><img src="send/compose-name-unavailable.png" width="206" alt="compose-name-unavailable"></a>
<a href="send/compose-quoting.png"><img src="send/compose-quoting.png" width="206" alt="compose-quoting"></a>
<a href="send/compose-refused.png"><img src="send/compose-refused.png" width="206" alt="compose-refused"></a>
<a href="send/compose-earlier-maybe-sent.png"><img src="send/compose-earlier-maybe-sent.png" width="206" alt="compose-earlier-maybe-sent"></a>
<a href="send/compose-token-not-found.png"><img src="send/compose-token-not-found.png" width="206" alt="compose-token-not-found"></a>
<a href="send/review.png"><img src="send/review.png" width="206" alt="review"></a>
<a href="send/review-dark.png"><img src="send/review-dark.png" width="206" alt="review-dark"></a>
<a href="send/review-token-with-biometrics.png"><img src="send/review-token-with-biometrics.png" width="206" alt="review-token-with-biometrics"></a>
<a href="send/review-wrong-password.png"><img src="send/review-wrong-password.png" width="206" alt="review-wrong-password"></a>
<a href="send/review-biometrics-cancelled.png"><img src="send/review-biometrics-cancelled.png" width="206" alt="review-biometrics-cancelled"></a>
<a href="send/review-signing.png"><img src="send/review-signing.png" width="206" alt="review-signing"></a>
<a href="send/review-refreshing.png"><img src="send/review-refreshing.png" width="206" alt="review-refreshing"></a>
<a href="send/review-not-sent.png"><img src="send/review-not-sent.png" width="206" alt="review-not-sent"></a>
<a href="send/review-rejected.png"><img src="send/review-rejected.png" width="206" alt="review-rejected"></a>
<a href="send/loading.png"><img src="send/loading.png" width="206" alt="loading"></a>
<a href="send/watch-only.png"><img src="send/watch-only.png" width="206" alt="watch-only"></a>
<a href="send/sent.png"><img src="send/sent.png" width="206" alt="sent"></a>
<a href="send/compose-lenient-last-verified.png"><img src="send/compose-lenient-last-verified.png" width="206" alt="compose-lenient-last-verified"></a>
<a href="send/compose-draft-waits.png"><img src="send/compose-draft-waits.png" width="206" alt="compose-draft-waits"></a>
<a href="send/compose-queued-first.png"><img src="send/compose-queued-first.png" width="206" alt="compose-queued-first"></a>
<a href="send/draft-review.png"><img src="send/draft-review.png" width="206" alt="draft-review"></a>
<a href="send/draft-review-dark.png"><img src="send/draft-review-dark.png" width="206" alt="draft-review-dark"></a>
<a href="send/draft-review-token.png"><img src="send/draft-review-token.png" width="206" alt="draft-review-token"></a>
<a href="send/draft-review-not-kept.png"><img src="send/draft-review-not-kept.png" width="206" alt="draft-review-not-kept"></a>
<a href="send/draft-review-signing.png"><img src="send/draft-review-signing.png" width="206" alt="draft-review-signing"></a>
<a href="send/queued.png"><img src="send/queued.png" width="206" alt="queued"></a>

<a name="settings"></a>
## Settings

`settings/`, 21 states: `synced`, `synced-dark`, `starting`, `unreadable`, `head-too-old`, `head-too-old-dark`, `debug-build`, `debug-build-capture-allowed`, `debug-screen`, `debug-screen-not-saved`, `idle`, `network-not-saved`, `turn-off`, `stale-anchor`, `detailed-view`, `detailed-view-not-saved`, `connections-off`, `sign-while-syncing`, `sign-while-syncing-dark`, `sign-while-syncing-queued`, `sign-while-syncing-not-saved`

<a href="settings/synced.png"><img src="settings/synced.png" width="206" alt="synced"></a>
<a href="settings/synced-dark.png"><img src="settings/synced-dark.png" width="206" alt="synced-dark"></a>
<a href="settings/starting.png"><img src="settings/starting.png" width="206" alt="starting"></a>
<a href="settings/unreadable.png"><img src="settings/unreadable.png" width="206" alt="unreadable"></a>
<a href="settings/head-too-old.png"><img src="settings/head-too-old.png" width="206" alt="head-too-old"></a>
<a href="settings/head-too-old-dark.png"><img src="settings/head-too-old-dark.png" width="206" alt="head-too-old-dark"></a>
<a href="settings/debug-build.png"><img src="settings/debug-build.png" width="206" alt="debug-build"></a>
<a href="settings/debug-build-capture-allowed.png"><img src="settings/debug-build-capture-allowed.png" width="206" alt="debug-build-capture-allowed"></a>
<a href="settings/debug-screen.png"><img src="settings/debug-screen.png" width="206" alt="debug-screen"></a>
<a href="settings/debug-screen-not-saved.png"><img src="settings/debug-screen-not-saved.png" width="206" alt="debug-screen-not-saved"></a>
<a href="settings/idle.png"><img src="settings/idle.png" width="206" alt="idle"></a>
<a href="settings/network-not-saved.png"><img src="settings/network-not-saved.png" width="206" alt="network-not-saved"></a>
<a href="settings/turn-off.png"><img src="settings/turn-off.png" width="206" alt="turn-off"></a>
<a href="settings/stale-anchor.png"><img src="settings/stale-anchor.png" width="206" alt="stale-anchor"></a>
<a href="settings/detailed-view.png"><img src="settings/detailed-view.png" width="206" alt="detailed-view"></a>
<a href="settings/detailed-view-not-saved.png"><img src="settings/detailed-view-not-saved.png" width="206" alt="detailed-view-not-saved"></a>
<a href="settings/connections-off.png"><img src="settings/connections-off.png" width="206" alt="connections-off"></a>
<a href="settings/sign-while-syncing.png"><img src="settings/sign-while-syncing.png" width="206" alt="sign-while-syncing"></a>
<a href="settings/sign-while-syncing-dark.png"><img src="settings/sign-while-syncing-dark.png" width="206" alt="sign-while-syncing-dark"></a>
<a href="settings/sign-while-syncing-queued.png"><img src="settings/sign-while-syncing-queued.png" width="206" alt="sign-while-syncing-queued"></a>
<a href="settings/sign-while-syncing-not-saved.png"><img src="settings/sign-while-syncing-not-saved.png" width="206" alt="sign-while-syncing-not-saved"></a>

<a name="simple-account-detail"></a>
## Account detail (simple mode)

`simple-account-detail/`, 12 states: `assets`, `assets-dark`, `last-verified`, `syncing`, `activity`, `waiting-swap`, `safe`, `network-off`, `hidden`, `hidden-dark`, `nfts`, `activity-queued`

<a href="simple-account-detail/assets.png"><img src="simple-account-detail/assets.png" width="206" alt="assets"></a>
<a href="simple-account-detail/assets-dark.png"><img src="simple-account-detail/assets-dark.png" width="206" alt="assets-dark"></a>
<a href="simple-account-detail/last-verified.png"><img src="simple-account-detail/last-verified.png" width="206" alt="last-verified"></a>
<a href="simple-account-detail/syncing.png"><img src="simple-account-detail/syncing.png" width="206" alt="syncing"></a>
<a href="simple-account-detail/activity.png"><img src="simple-account-detail/activity.png" width="206" alt="activity"></a>
<a href="simple-account-detail/waiting-swap.png"><img src="simple-account-detail/waiting-swap.png" width="206" alt="waiting-swap"></a>
<a href="simple-account-detail/safe.png"><img src="simple-account-detail/safe.png" width="206" alt="safe"></a>
<a href="simple-account-detail/network-off.png"><img src="simple-account-detail/network-off.png" width="206" alt="network-off"></a>
<a href="simple-account-detail/hidden.png"><img src="simple-account-detail/hidden.png" width="206" alt="hidden"></a>
<a href="simple-account-detail/hidden-dark.png"><img src="simple-account-detail/hidden-dark.png" width="206" alt="hidden-dark"></a>
<a href="simple-account-detail/nfts.png"><img src="simple-account-detail/nfts.png" width="206" alt="nfts"></a>
<a href="simple-account-detail/activity-queued.png"><img src="simple-account-detail/activity-queued.png" width="206" alt="activity-queued"></a>

<a name="simple-accounts"></a>
## Home (simple mode)

`simple-accounts/`, 16 states: `everyday`, `everyday-dark`, `refreshing`, `syncing`, `unreachable`, `live`, `stale-anchor`, `empty`, `loading`, `set-aside`, `list-unavailable`, `hidden`, `hidden-dark`, `queued`, `queued-dark`, `lost-queued`

<a href="simple-accounts/everyday.png"><img src="simple-accounts/everyday.png" width="206" alt="everyday"></a>
<a href="simple-accounts/everyday-dark.png"><img src="simple-accounts/everyday-dark.png" width="206" alt="everyday-dark"></a>
<a href="simple-accounts/refreshing.png"><img src="simple-accounts/refreshing.png" width="206" alt="refreshing"></a>
<a href="simple-accounts/syncing.png"><img src="simple-accounts/syncing.png" width="206" alt="syncing"></a>
<a href="simple-accounts/unreachable.png"><img src="simple-accounts/unreachable.png" width="206" alt="unreachable"></a>
<a href="simple-accounts/live.png"><img src="simple-accounts/live.png" width="206" alt="live"></a>
<a href="simple-accounts/stale-anchor.png"><img src="simple-accounts/stale-anchor.png" width="206" alt="stale-anchor"></a>
<a href="simple-accounts/empty.png"><img src="simple-accounts/empty.png" width="206" alt="empty"></a>
<a href="simple-accounts/loading.png"><img src="simple-accounts/loading.png" width="206" alt="loading"></a>
<a href="simple-accounts/set-aside.png"><img src="simple-accounts/set-aside.png" width="206" alt="set-aside"></a>
<a href="simple-accounts/list-unavailable.png"><img src="simple-accounts/list-unavailable.png" width="206" alt="list-unavailable"></a>
<a href="simple-accounts/hidden.png"><img src="simple-accounts/hidden.png" width="206" alt="hidden"></a>
<a href="simple-accounts/hidden-dark.png"><img src="simple-accounts/hidden-dark.png" width="206" alt="hidden-dark"></a>
<a href="simple-accounts/queued.png"><img src="simple-accounts/queued.png" width="206" alt="queued"></a>
<a href="simple-accounts/queued-dark.png"><img src="simple-accounts/queued-dark.png" width="206" alt="queued-dark"></a>
<a href="simple-accounts/lost-queued.png"><img src="simple-accounts/lost-queued.png" width="206" alt="lost-queued"></a>

<a name="simple-activity-detail"></a>
## Activity detail (simple mode)

`simple-activity-detail/`, 4 states: `sent`, `sent-dark`, `hidden`, `hidden-dark`

<a href="simple-activity-detail/sent.png"><img src="simple-activity-detail/sent.png" width="206" alt="sent"></a>
<a href="simple-activity-detail/sent-dark.png"><img src="simple-activity-detail/sent-dark.png" width="206" alt="sent-dark"></a>
<a href="simple-activity-detail/hidden.png"><img src="simple-activity-detail/hidden.png" width="206" alt="hidden"></a>
<a href="simple-activity-detail/hidden-dark.png"><img src="simple-activity-detail/hidden-dark.png" width="206" alt="hidden-dark"></a>

<a name="simple-add-token"></a>
## Add token (simple mode)

`simple-add-token/`, 2 states: `claim`, `claim-dark`

<a href="simple-add-token/claim.png"><img src="simple-add-token/claim.png" width="206" alt="claim"></a>
<a href="simple-add-token/claim-dark.png"><img src="simple-add-token/claim-dark.png" width="206" alt="claim-dark"></a>

<a name="simple-cow-order"></a>
## CoW order (simple mode)

`simple-cow-order/`, 3 states: `filled`, `filled-dark`, `cancel-review`

<a href="simple-cow-order/filled.png"><img src="simple-cow-order/filled.png" width="206" alt="filled"></a>
<a href="simple-cow-order/filled-dark.png"><img src="simple-cow-order/filled-dark.png" width="206" alt="filled-dark"></a>
<a href="simple-cow-order/cancel-review.png"><img src="simple-cow-order/cancel-review.png" width="206" alt="cancel-review"></a>

<a name="simple-create-safe"></a>
## Create Safe (simple mode)

`simple-create-safe/`, 3 states: `owners`, `review`, `review-dark`

<a href="simple-create-safe/owners.png"><img src="simple-create-safe/owners.png" width="206" alt="owners"></a>
<a href="simple-create-safe/review.png"><img src="simple-create-safe/review.png" width="206" alt="review"></a>
<a href="simple-create-safe/review-dark.png"><img src="simple-create-safe/review-dark.png" width="206" alt="review-dark"></a>

<a name="simple-safe-import"></a>
## Import Safe (simple mode)

`simple-safe-import/`, 3 states: `empty`, `empty-dark`, `refused`

<a href="simple-safe-import/empty.png"><img src="simple-safe-import/empty.png" width="206" alt="empty"></a>
<a href="simple-safe-import/empty-dark.png"><img src="simple-safe-import/empty-dark.png" width="206" alt="empty-dark"></a>
<a href="simple-safe-import/refused.png"><img src="simple-safe-import/refused.png" width="206" alt="refused"></a>

<a name="simple-safe-propose"></a>
## Propose Safe transaction (simple mode)

`simple-safe-propose/`, 6 states: `compose`, `refused`, `review`, `review-dark`, `draft`, `draft-dark`

<a href="simple-safe-propose/compose.png"><img src="simple-safe-propose/compose.png" width="206" alt="compose"></a>
<a href="simple-safe-propose/refused.png"><img src="simple-safe-propose/refused.png" width="206" alt="refused"></a>
<a href="simple-safe-propose/review.png"><img src="simple-safe-propose/review.png" width="206" alt="review"></a>
<a href="simple-safe-propose/review-dark.png"><img src="simple-safe-propose/review-dark.png" width="206" alt="review-dark"></a>
<a href="simple-safe-propose/draft.png"><img src="simple-safe-propose/draft.png" width="206" alt="draft"></a>
<a href="simple-safe-propose/draft-dark.png"><img src="simple-safe-propose/draft-dark.png" width="206" alt="draft-dark"></a>

<a name="simple-safe-settings"></a>
## Safe settings (simple mode)

`simple-safe-settings/`, 5 states: `overview`, `overview-dark`, `refused`, `review`, `review-refused`

<a href="simple-safe-settings/overview.png"><img src="simple-safe-settings/overview.png" width="206" alt="overview"></a>
<a href="simple-safe-settings/overview-dark.png"><img src="simple-safe-settings/overview-dark.png" width="206" alt="overview-dark"></a>
<a href="simple-safe-settings/refused.png"><img src="simple-safe-settings/refused.png" width="206" alt="refused"></a>
<a href="simple-safe-settings/review.png"><img src="simple-safe-settings/review.png" width="206" alt="review"></a>
<a href="simple-safe-settings/review-refused.png"><img src="simple-safe-settings/review-refused.png" width="206" alt="review-refused"></a>

<a name="simple-safe-swap"></a>
## Safe swap (simple mode)

`simple-safe-swap/`, 4 states: `compose`, `refused`, `review`, `review-dark`

<a href="simple-safe-swap/compose.png"><img src="simple-safe-swap/compose.png" width="206" alt="compose"></a>
<a href="simple-safe-swap/refused.png"><img src="simple-safe-swap/refused.png" width="206" alt="refused"></a>
<a href="simple-safe-swap/review.png"><img src="simple-safe-swap/review.png" width="206" alt="review"></a>
<a href="simple-safe-swap/review-dark.png"><img src="simple-safe-swap/review-dark.png" width="206" alt="review-dark"></a>

<a name="simple-safe-tx"></a>
## Safe transaction (simple mode)

`simple-safe-tx/`, 15 states: `overview`, `overview-dark`, `review`, `swap`, `refused`, `signing`, `approval`, `lenient-overview`, `held`, `held-dark`, `held-co-signature`, `draft`, `held-conflict`, `held-discarded`, `draft-execution`

<a href="simple-safe-tx/overview.png"><img src="simple-safe-tx/overview.png" width="206" alt="overview"></a>
<a href="simple-safe-tx/overview-dark.png"><img src="simple-safe-tx/overview-dark.png" width="206" alt="overview-dark"></a>
<a href="simple-safe-tx/review.png"><img src="simple-safe-tx/review.png" width="206" alt="review"></a>
<a href="simple-safe-tx/swap.png"><img src="simple-safe-tx/swap.png" width="206" alt="swap"></a>
<a href="simple-safe-tx/refused.png"><img src="simple-safe-tx/refused.png" width="206" alt="refused"></a>
<a href="simple-safe-tx/signing.png"><img src="simple-safe-tx/signing.png" width="206" alt="signing"></a>
<a href="simple-safe-tx/approval.png"><img src="simple-safe-tx/approval.png" width="206" alt="approval"></a>
<a href="simple-safe-tx/lenient-overview.png"><img src="simple-safe-tx/lenient-overview.png" width="206" alt="lenient-overview"></a>
<a href="simple-safe-tx/held.png"><img src="simple-safe-tx/held.png" width="206" alt="held"></a>
<a href="simple-safe-tx/held-dark.png"><img src="simple-safe-tx/held-dark.png" width="206" alt="held-dark"></a>
<a href="simple-safe-tx/held-co-signature.png"><img src="simple-safe-tx/held-co-signature.png" width="206" alt="held-co-signature"></a>
<a href="simple-safe-tx/draft.png"><img src="simple-safe-tx/draft.png" width="206" alt="draft"></a>
<a href="simple-safe-tx/held-conflict.png"><img src="simple-safe-tx/held-conflict.png" width="206" alt="held-conflict"></a>
<a href="simple-safe-tx/held-discarded.png"><img src="simple-safe-tx/held-discarded.png" width="206" alt="held-discarded"></a>
<a href="simple-safe-tx/draft-execution.png"><img src="simple-safe-tx/draft-execution.png" width="206" alt="draft-execution"></a>

<a name="simple-send"></a>
## Send (simple mode)

`simple-send/`, 8 states: `compose`, `syncing`, `review`, `review-dark`, `compose-refused`, `compose-lenient-last-verified`, `draft-review`, `draft-review-dark`

<a href="simple-send/compose.png"><img src="simple-send/compose.png" width="206" alt="compose"></a>
<a href="simple-send/syncing.png"><img src="simple-send/syncing.png" width="206" alt="syncing"></a>
<a href="simple-send/review.png"><img src="simple-send/review.png" width="206" alt="review"></a>
<a href="simple-send/review-dark.png"><img src="simple-send/review-dark.png" width="206" alt="review-dark"></a>
<a href="simple-send/compose-refused.png"><img src="simple-send/compose-refused.png" width="206" alt="compose-refused"></a>
<a href="simple-send/compose-lenient-last-verified.png"><img src="simple-send/compose-lenient-last-verified.png" width="206" alt="compose-lenient-last-verified"></a>
<a href="simple-send/draft-review.png"><img src="simple-send/draft-review.png" width="206" alt="draft-review"></a>
<a href="simple-send/draft-review-dark.png"><img src="simple-send/draft-review-dark.png" width="206" alt="draft-review-dark"></a>

<a name="simple-settings"></a>
## Settings (simple mode)

`simple-settings/`, 6 states: `default`, `default-dark`, `off`, `sign-while-syncing`, `sign-while-syncing-queued`, `sign-while-syncing-not-saved`

<a href="simple-settings/default.png"><img src="simple-settings/default.png" width="206" alt="default"></a>
<a href="simple-settings/default-dark.png"><img src="simple-settings/default-dark.png" width="206" alt="default-dark"></a>
<a href="simple-settings/off.png"><img src="simple-settings/off.png" width="206" alt="off"></a>
<a href="simple-settings/sign-while-syncing.png"><img src="simple-settings/sign-while-syncing.png" width="206" alt="sign-while-syncing"></a>
<a href="simple-settings/sign-while-syncing-queued.png"><img src="simple-settings/sign-while-syncing-queued.png" width="206" alt="sign-while-syncing-queued"></a>
<a href="simple-settings/sign-while-syncing-not-saved.png"><img src="simple-settings/sign-while-syncing-not-saved.png" width="206" alt="sign-while-syncing-not-saved"></a>

<a name="simple-swap"></a>
## Swap (simple mode)

`simple-swap/`, 11 states: `compose`, `compose-dark`, `approval`, `approval-dark`, `pending`, `step-two`, `cow`, `reset-first`, `earlier-failed`, `one-step`, `not-sent`

<a href="simple-swap/compose.png"><img src="simple-swap/compose.png" width="206" alt="compose"></a>
<a href="simple-swap/compose-dark.png"><img src="simple-swap/compose-dark.png" width="206" alt="compose-dark"></a>
<a href="simple-swap/approval.png"><img src="simple-swap/approval.png" width="206" alt="approval"></a>
<a href="simple-swap/approval-dark.png"><img src="simple-swap/approval-dark.png" width="206" alt="approval-dark"></a>
<a href="simple-swap/pending.png"><img src="simple-swap/pending.png" width="206" alt="pending"></a>
<a href="simple-swap/step-two.png"><img src="simple-swap/step-two.png" width="206" alt="step-two"></a>
<a href="simple-swap/cow.png"><img src="simple-swap/cow.png" width="206" alt="cow"></a>
<a href="simple-swap/reset-first.png"><img src="simple-swap/reset-first.png" width="206" alt="reset-first"></a>
<a href="simple-swap/earlier-failed.png"><img src="simple-swap/earlier-failed.png" width="206" alt="earlier-failed"></a>
<a href="simple-swap/one-step.png"><img src="simple-swap/one-step.png" width="206" alt="one-step"></a>
<a href="simple-swap/not-sent.png"><img src="simple-swap/not-sent.png" width="206" alt="not-sent"></a>

<a name="simple-tokens"></a>
## Tokens (simple mode)

`simple-tokens/`, 2 states: `list`, `list-dark`

<a href="simple-tokens/list.png"><img src="simple-tokens/list.png" width="206" alt="list"></a>
<a href="simple-tokens/list-dark.png"><img src="simple-tokens/list-dark.png" width="206" alt="list-dark"></a>

<a name="simple-transfer-status"></a>
## Transfer status (simple mode)

`simple-transfer-status/`, 13 states: `confirmed`, `confirmed-dark`, `final`, `failed`, `maybe-sent`, `queued`, `queued-dark`, `queued-past-its-hour`, `queued-conflict`, `queued-safe-approval`, `queued-sending`, `queued-discarding`, `discarded`

<a href="simple-transfer-status/confirmed.png"><img src="simple-transfer-status/confirmed.png" width="206" alt="confirmed"></a>
<a href="simple-transfer-status/confirmed-dark.png"><img src="simple-transfer-status/confirmed-dark.png" width="206" alt="confirmed-dark"></a>
<a href="simple-transfer-status/final.png"><img src="simple-transfer-status/final.png" width="206" alt="final"></a>
<a href="simple-transfer-status/failed.png"><img src="simple-transfer-status/failed.png" width="206" alt="failed"></a>
<a href="simple-transfer-status/maybe-sent.png"><img src="simple-transfer-status/maybe-sent.png" width="206" alt="maybe-sent"></a>
<a href="simple-transfer-status/queued.png"><img src="simple-transfer-status/queued.png" width="206" alt="queued"></a>
<a href="simple-transfer-status/queued-dark.png"><img src="simple-transfer-status/queued-dark.png" width="206" alt="queued-dark"></a>
<a href="simple-transfer-status/queued-past-its-hour.png"><img src="simple-transfer-status/queued-past-its-hour.png" width="206" alt="queued-past-its-hour"></a>
<a href="simple-transfer-status/queued-conflict.png"><img src="simple-transfer-status/queued-conflict.png" width="206" alt="queued-conflict"></a>
<a href="simple-transfer-status/queued-safe-approval.png"><img src="simple-transfer-status/queued-safe-approval.png" width="206" alt="queued-safe-approval"></a>
<a href="simple-transfer-status/queued-sending.png"><img src="simple-transfer-status/queued-sending.png" width="206" alt="queued-sending"></a>
<a href="simple-transfer-status/queued-discarding.png"><img src="simple-transfer-status/queued-discarding.png" width="206" alt="queued-discarding"></a>
<a href="simple-transfer-status/discarded.png"><img src="simple-transfer-status/discarded.png" width="206" alt="discarded"></a>

<a name="simple-watch"></a>
## Watch address (simple mode)

`simple-watch/`, 9 states: `account`, `account-dark`, `safe`, `name-resolved`, `name-on-one-registry`, `name-unverified-on-one-registry`, `every-network`, `syncing`, `syncing-dark`

<a href="simple-watch/account.png"><img src="simple-watch/account.png" width="206" alt="account"></a>
<a href="simple-watch/account-dark.png"><img src="simple-watch/account-dark.png" width="206" alt="account-dark"></a>
<a href="simple-watch/safe.png"><img src="simple-watch/safe.png" width="206" alt="safe"></a>
<a href="simple-watch/name-resolved.png"><img src="simple-watch/name-resolved.png" width="206" alt="name-resolved"></a>
<a href="simple-watch/name-on-one-registry.png"><img src="simple-watch/name-on-one-registry.png" width="206" alt="name-on-one-registry"></a>
<a href="simple-watch/name-unverified-on-one-registry.png"><img src="simple-watch/name-unverified-on-one-registry.png" width="206" alt="name-unverified-on-one-registry"></a>
<a href="simple-watch/every-network.png"><img src="simple-watch/every-network.png" width="206" alt="every-network"></a>
<a href="simple-watch/syncing.png"><img src="simple-watch/syncing.png" width="206" alt="syncing"></a>
<a href="simple-watch/syncing-dark.png"><img src="simple-watch/syncing-dark.png" width="206" alt="syncing-dark"></a>

<a name="swap"></a>
## Swap

`swap/`, 38 states: `compose-empty`, `compose-empty-dark`, `composed`, `compose-estimate-checking`, `compose-estimate-unavailable`, `compose-high-impact`, `compose-quoting`, `compose-input-errors`, `compose-no-route`, `compose-cow-orders-unreadable`, `compose-on-cow`, `compose-cow-did-not-answer`, `compose-earlier-order-live`, `compose-approval-sent`, `compose-earlier-maybe-sent`, `compose-token-not-found`, `approval`, `approval-reset-first`, `review`, `review-dark`, `review-through-weth-with-biometrics`, `review-wrong-password`, `review-signing`, `review-refreshing`, `review-not-sent`, `cow-review`, `cow-review-dark`, `cow-review-uniswap-gives-more`, `order-placed`, `loading`, `watch-only`, `sent`, `approval-pending`, `review-step-two`, `approval-earlier-failed`, `wrap-review`, `wrap-review-dark`, `unwrap-review`

<a href="swap/compose-empty.png"><img src="swap/compose-empty.png" width="206" alt="compose-empty"></a>
<a href="swap/compose-empty-dark.png"><img src="swap/compose-empty-dark.png" width="206" alt="compose-empty-dark"></a>
<a href="swap/composed.png"><img src="swap/composed.png" width="206" alt="composed"></a>
<a href="swap/compose-estimate-checking.png"><img src="swap/compose-estimate-checking.png" width="206" alt="compose-estimate-checking"></a>
<a href="swap/compose-estimate-unavailable.png"><img src="swap/compose-estimate-unavailable.png" width="206" alt="compose-estimate-unavailable"></a>
<a href="swap/compose-high-impact.png"><img src="swap/compose-high-impact.png" width="206" alt="compose-high-impact"></a>
<a href="swap/compose-quoting.png"><img src="swap/compose-quoting.png" width="206" alt="compose-quoting"></a>
<a href="swap/compose-input-errors.png"><img src="swap/compose-input-errors.png" width="206" alt="compose-input-errors"></a>
<a href="swap/compose-no-route.png"><img src="swap/compose-no-route.png" width="206" alt="compose-no-route"></a>
<a href="swap/compose-cow-orders-unreadable.png"><img src="swap/compose-cow-orders-unreadable.png" width="206" alt="compose-cow-orders-unreadable"></a>
<a href="swap/compose-on-cow.png"><img src="swap/compose-on-cow.png" width="206" alt="compose-on-cow"></a>
<a href="swap/compose-cow-did-not-answer.png"><img src="swap/compose-cow-did-not-answer.png" width="206" alt="compose-cow-did-not-answer"></a>
<a href="swap/compose-earlier-order-live.png"><img src="swap/compose-earlier-order-live.png" width="206" alt="compose-earlier-order-live"></a>
<a href="swap/compose-approval-sent.png"><img src="swap/compose-approval-sent.png" width="206" alt="compose-approval-sent"></a>
<a href="swap/compose-earlier-maybe-sent.png"><img src="swap/compose-earlier-maybe-sent.png" width="206" alt="compose-earlier-maybe-sent"></a>
<a href="swap/compose-token-not-found.png"><img src="swap/compose-token-not-found.png" width="206" alt="compose-token-not-found"></a>
<a href="swap/approval.png"><img src="swap/approval.png" width="206" alt="approval"></a>
<a href="swap/approval-reset-first.png"><img src="swap/approval-reset-first.png" width="206" alt="approval-reset-first"></a>
<a href="swap/review.png"><img src="swap/review.png" width="206" alt="review"></a>
<a href="swap/review-dark.png"><img src="swap/review-dark.png" width="206" alt="review-dark"></a>
<a href="swap/review-through-weth-with-biometrics.png"><img src="swap/review-through-weth-with-biometrics.png" width="206" alt="review-through-weth-with-biometrics"></a>
<a href="swap/review-wrong-password.png"><img src="swap/review-wrong-password.png" width="206" alt="review-wrong-password"></a>
<a href="swap/review-signing.png"><img src="swap/review-signing.png" width="206" alt="review-signing"></a>
<a href="swap/review-refreshing.png"><img src="swap/review-refreshing.png" width="206" alt="review-refreshing"></a>
<a href="swap/review-not-sent.png"><img src="swap/review-not-sent.png" width="206" alt="review-not-sent"></a>
<a href="swap/cow-review.png"><img src="swap/cow-review.png" width="206" alt="cow-review"></a>
<a href="swap/cow-review-dark.png"><img src="swap/cow-review-dark.png" width="206" alt="cow-review-dark"></a>
<a href="swap/cow-review-uniswap-gives-more.png"><img src="swap/cow-review-uniswap-gives-more.png" width="206" alt="cow-review-uniswap-gives-more"></a>
<a href="swap/order-placed.png"><img src="swap/order-placed.png" width="206" alt="order-placed"></a>
<a href="swap/loading.png"><img src="swap/loading.png" width="206" alt="loading"></a>
<a href="swap/watch-only.png"><img src="swap/watch-only.png" width="206" alt="watch-only"></a>
<a href="swap/sent.png"><img src="swap/sent.png" width="206" alt="sent"></a>
<a href="swap/approval-pending.png"><img src="swap/approval-pending.png" width="206" alt="approval-pending"></a>
<a href="swap/review-step-two.png"><img src="swap/review-step-two.png" width="206" alt="review-step-two"></a>
<a href="swap/approval-earlier-failed.png"><img src="swap/approval-earlier-failed.png" width="206" alt="approval-earlier-failed"></a>
<a href="swap/wrap-review.png"><img src="swap/wrap-review.png" width="206" alt="wrap-review"></a>
<a href="swap/wrap-review-dark.png"><img src="swap/wrap-review-dark.png" width="206" alt="wrap-review-dark"></a>
<a href="swap/unwrap-review.png"><img src="swap/unwrap-review.png" width="206" alt="unwrap-review"></a>

<a name="tokens"></a>
## Tokens

`tokens/`, 7 states: `list`, `list-dark`, `checks`, `unreadable`, `built-in`, `built-in-rest`, `gnosis`

<a href="tokens/list.png"><img src="tokens/list.png" width="206" alt="list"></a>
<a href="tokens/list-dark.png"><img src="tokens/list-dark.png" width="206" alt="list-dark"></a>
<a href="tokens/checks.png"><img src="tokens/checks.png" width="206" alt="checks"></a>
<a href="tokens/unreadable.png"><img src="tokens/unreadable.png" width="206" alt="unreadable"></a>
<a href="tokens/built-in.png"><img src="tokens/built-in.png" width="206" alt="built-in"></a>
<a href="tokens/built-in-rest.png"><img src="tokens/built-in-rest.png" width="206" alt="built-in-rest"></a>
<a href="tokens/gnosis.png"><img src="tokens/gnosis.png" width="206" alt="gnosis"></a>

<a name="transfer-status"></a>
## Transfer status

`transfer-status/`, 36 states: `pending`, `pending-dark`, `included`, `finalized`, `failed`, `unavailable`, `no-record`, `safe-execution`, `safe-deployment`, `safe-listed`, `safe-listed-dark`, `safe-not-listed`, `maybe-sent`, `retrying`, `turned-away`, `loading`, `swap`, `swap-failed`, `approval`, `allowance-reset`, `swap-dark`, `swap-received`, `nonce-used`, `maybe-sent-nonce-used`, `cow-cancellation`, `wrap`, `queued`, `queued-dark`, `queued-sending`, `queued-past-its-hour`, `queued-conflict`, `discarded`, `queued-discarding`, `queued-not-discarded`, `queued-outbox-unreadable`, `queued-safe-approval`

<a href="transfer-status/pending.png"><img src="transfer-status/pending.png" width="206" alt="pending"></a>
<a href="transfer-status/pending-dark.png"><img src="transfer-status/pending-dark.png" width="206" alt="pending-dark"></a>
<a href="transfer-status/included.png"><img src="transfer-status/included.png" width="206" alt="included"></a>
<a href="transfer-status/finalized.png"><img src="transfer-status/finalized.png" width="206" alt="finalized"></a>
<a href="transfer-status/failed.png"><img src="transfer-status/failed.png" width="206" alt="failed"></a>
<a href="transfer-status/unavailable.png"><img src="transfer-status/unavailable.png" width="206" alt="unavailable"></a>
<a href="transfer-status/no-record.png"><img src="transfer-status/no-record.png" width="206" alt="no-record"></a>
<a href="transfer-status/safe-execution.png"><img src="transfer-status/safe-execution.png" width="206" alt="safe-execution"></a>
<a href="transfer-status/safe-deployment.png"><img src="transfer-status/safe-deployment.png" width="206" alt="safe-deployment"></a>
<a href="transfer-status/safe-listed.png"><img src="transfer-status/safe-listed.png" width="206" alt="safe-listed"></a>
<a href="transfer-status/safe-listed-dark.png"><img src="transfer-status/safe-listed-dark.png" width="206" alt="safe-listed-dark"></a>
<a href="transfer-status/safe-not-listed.png"><img src="transfer-status/safe-not-listed.png" width="206" alt="safe-not-listed"></a>
<a href="transfer-status/maybe-sent.png"><img src="transfer-status/maybe-sent.png" width="206" alt="maybe-sent"></a>
<a href="transfer-status/retrying.png"><img src="transfer-status/retrying.png" width="206" alt="retrying"></a>
<a href="transfer-status/turned-away.png"><img src="transfer-status/turned-away.png" width="206" alt="turned-away"></a>
<a href="transfer-status/loading.png"><img src="transfer-status/loading.png" width="206" alt="loading"></a>
<a href="transfer-status/swap.png"><img src="transfer-status/swap.png" width="206" alt="swap"></a>
<a href="transfer-status/swap-failed.png"><img src="transfer-status/swap-failed.png" width="206" alt="swap-failed"></a>
<a href="transfer-status/approval.png"><img src="transfer-status/approval.png" width="206" alt="approval"></a>
<a href="transfer-status/allowance-reset.png"><img src="transfer-status/allowance-reset.png" width="206" alt="allowance-reset"></a>
<a href="transfer-status/swap-dark.png"><img src="transfer-status/swap-dark.png" width="206" alt="swap-dark"></a>
<a href="transfer-status/swap-received.png"><img src="transfer-status/swap-received.png" width="206" alt="swap-received"></a>
<a href="transfer-status/nonce-used.png"><img src="transfer-status/nonce-used.png" width="206" alt="nonce-used"></a>
<a href="transfer-status/maybe-sent-nonce-used.png"><img src="transfer-status/maybe-sent-nonce-used.png" width="206" alt="maybe-sent-nonce-used"></a>
<a href="transfer-status/cow-cancellation.png"><img src="transfer-status/cow-cancellation.png" width="206" alt="cow-cancellation"></a>
<a href="transfer-status/wrap.png"><img src="transfer-status/wrap.png" width="206" alt="wrap"></a>
<a href="transfer-status/queued.png"><img src="transfer-status/queued.png" width="206" alt="queued"></a>
<a href="transfer-status/queued-dark.png"><img src="transfer-status/queued-dark.png" width="206" alt="queued-dark"></a>
<a href="transfer-status/queued-sending.png"><img src="transfer-status/queued-sending.png" width="206" alt="queued-sending"></a>
<a href="transfer-status/queued-past-its-hour.png"><img src="transfer-status/queued-past-its-hour.png" width="206" alt="queued-past-its-hour"></a>
<a href="transfer-status/queued-conflict.png"><img src="transfer-status/queued-conflict.png" width="206" alt="queued-conflict"></a>
<a href="transfer-status/discarded.png"><img src="transfer-status/discarded.png" width="206" alt="discarded"></a>
<a href="transfer-status/queued-discarding.png"><img src="transfer-status/queued-discarding.png" width="206" alt="queued-discarding"></a>
<a href="transfer-status/queued-not-discarded.png"><img src="transfer-status/queued-not-discarded.png" width="206" alt="queued-not-discarded"></a>
<a href="transfer-status/queued-outbox-unreadable.png"><img src="transfer-status/queued-outbox-unreadable.png" width="206" alt="queued-outbox-unreadable"></a>
<a href="transfer-status/queued-safe-approval.png"><img src="transfer-status/queued-safe-approval.png" width="206" alt="queued-safe-approval"></a>

<a name="update-required"></a>
## Update required

`update-required/`, 2 states: `required`, `required-dark`

<a href="update-required/required.png"><img src="update-required/required.png" width="206" alt="required"></a>
<a href="update-required/required-dark.png"><img src="update-required/required-dark.png" width="206" alt="required-dark"></a>

<a name="watch"></a>
## Watch address

`watch/`, 25 states: `empty`, `checking`, `account`, `account-dark`, `delegated-account`, `safe`, `not-a-safe`, `in-wallet`, `unavailable`, `invalid-address`, `name-too-long`, `gnosis-no-names`, `adding`, `not-saved`, `resolving-name`, `name-resolved`, `name-on-one-registry`, `name-unverified-on-one-registry`, `name-moved`, `name-unavailable`, `name-resolved-no-name`, `every-network`, `choose-networks`, `safe-on-one-network`, `no-network`

<a href="watch/empty.png"><img src="watch/empty.png" width="206" alt="empty"></a>
<a href="watch/checking.png"><img src="watch/checking.png" width="206" alt="checking"></a>
<a href="watch/account.png"><img src="watch/account.png" width="206" alt="account"></a>
<a href="watch/account-dark.png"><img src="watch/account-dark.png" width="206" alt="account-dark"></a>
<a href="watch/delegated-account.png"><img src="watch/delegated-account.png" width="206" alt="delegated-account"></a>
<a href="watch/safe.png"><img src="watch/safe.png" width="206" alt="safe"></a>
<a href="watch/not-a-safe.png"><img src="watch/not-a-safe.png" width="206" alt="not-a-safe"></a>
<a href="watch/in-wallet.png"><img src="watch/in-wallet.png" width="206" alt="in-wallet"></a>
<a href="watch/unavailable.png"><img src="watch/unavailable.png" width="206" alt="unavailable"></a>
<a href="watch/invalid-address.png"><img src="watch/invalid-address.png" width="206" alt="invalid-address"></a>
<a href="watch/name-too-long.png"><img src="watch/name-too-long.png" width="206" alt="name-too-long"></a>
<a href="watch/gnosis-no-names.png"><img src="watch/gnosis-no-names.png" width="206" alt="gnosis-no-names"></a>
<a href="watch/adding.png"><img src="watch/adding.png" width="206" alt="adding"></a>
<a href="watch/not-saved.png"><img src="watch/not-saved.png" width="206" alt="not-saved"></a>
<a href="watch/resolving-name.png"><img src="watch/resolving-name.png" width="206" alt="resolving-name"></a>
<a href="watch/name-resolved.png"><img src="watch/name-resolved.png" width="206" alt="name-resolved"></a>
<a href="watch/name-on-one-registry.png"><img src="watch/name-on-one-registry.png" width="206" alt="name-on-one-registry"></a>
<a href="watch/name-unverified-on-one-registry.png"><img src="watch/name-unverified-on-one-registry.png" width="206" alt="name-unverified-on-one-registry"></a>
<a href="watch/name-moved.png"><img src="watch/name-moved.png" width="206" alt="name-moved"></a>
<a href="watch/name-unavailable.png"><img src="watch/name-unavailable.png" width="206" alt="name-unavailable"></a>
<a href="watch/name-resolved-no-name.png"><img src="watch/name-resolved-no-name.png" width="206" alt="name-resolved-no-name"></a>
<a href="watch/every-network.png"><img src="watch/every-network.png" width="206" alt="every-network"></a>
<a href="watch/choose-networks.png"><img src="watch/choose-networks.png" width="206" alt="choose-networks"></a>
<a href="watch/safe-on-one-network.png"><img src="watch/safe-on-one-network.png" width="206" alt="safe-on-one-network"></a>
<a href="watch/no-network.png"><img src="watch/no-network.png" width="206" alt="no-network"></a>

<a name="widget-pair"></a>
## Widget setup

`widget-pair/`, 9 states: `networks`, `networks-dark`, `none-on`, `bases`, `bases-filtered`, `bases-no-match`, `bases-filtered-dark`, `quotes`, `quotes-dark`

<a href="widget-pair/networks.png"><img src="widget-pair/networks.png" width="206" alt="networks"></a>
<a href="widget-pair/networks-dark.png"><img src="widget-pair/networks-dark.png" width="206" alt="networks-dark"></a>
<a href="widget-pair/none-on.png"><img src="widget-pair/none-on.png" width="206" alt="none-on"></a>
<a href="widget-pair/bases.png"><img src="widget-pair/bases.png" width="206" alt="bases"></a>
<a href="widget-pair/bases-filtered.png"><img src="widget-pair/bases-filtered.png" width="206" alt="bases-filtered"></a>
<a href="widget-pair/bases-no-match.png"><img src="widget-pair/bases-no-match.png" width="206" alt="bases-no-match"></a>
<a href="widget-pair/bases-filtered-dark.png"><img src="widget-pair/bases-filtered-dark.png" width="206" alt="bases-filtered-dark"></a>
<a href="widget-pair/quotes.png"><img src="widget-pair/quotes.png" width="206" alt="quotes"></a>
<a href="widget-pair/quotes-dark.png"><img src="widget-pair/quotes-dark.png" width="206" alt="quotes-dark"></a>

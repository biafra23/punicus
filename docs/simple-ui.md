# Simple mode: the default view, with today's screens opt-in

Drawn 2026-10-26 and approved by the owner on 2026-10-27, with the decisions
in *The decisions this plan is built on*. The rulings are recorded in
`.claude/rules/trust.md` and `.claude/rules/ui.md`, and docs/wallet-ux.md
applies them. This page says what simple mode shows and how it is built. It
also says how the parts of `feature/simple-ui` deliver it. docs/wallet-ux.md
takes over each screen as its part lands. Where this page and the rules under
`.claude/` disagree, the rules win and this page is fixed.

The mockups (source: `simple-ui-screens.html`, which opens in a browser) are
numbered H1 to T1 below. The blockies, the QR code, the amounts, names and
addresses are made up. Where a mockup and this text differ, the text wins.

![Screens H1 to T1, numbered as below](simple-ui-screens.png)

## The decisions this plan is built on

These come from the owner's requests and answers (2026-10-26, mockups
approved 2026-10-27):

1. **Two views of the same wallet.** Simple mode is for people who use the
   wallet. Detailed view is today's screens, for those who want to see how
   the wallet checks the chain. Settings → Advanced → *Detailed view* turns
   it on. It is off by default.
2. **Simple mode in both builds.** The release and the debug app both start
   in simple mode. Detailed view is opt-in in both.
3. **No block numbers in simple mode.** The time since the last verified head
   is fine to show ("Updated 2 min ago"). The block stays one tap away, under
   *Details*.
4. **Live where the network allows it, refreshed on demand where it does
   not.** A network with block access lists (EIP-7928, BALs) updates the
   screen as it changes: today Sepolia, live since Glamsterdam. On Ethereum
   and Gnosis Chain, which have none yet, a value is read when its screen
   opens. Pull-to-refresh reads it again. In between it may grow old, and
   the screen says how old. This holds in simple mode only. Detailed view
   reads at every head, as today.
5. **A balance read a while ago counts, with its age.** In simple mode, a
   balance on a network without BALs counts in totals, at the price read
   with it, while it is under an hour old (the owner's "about an hour",
   pinned at 60 minutes) and the light client verifies. The screen shows its
   age. An older one is dimmed and left out of totals, as a balance last
   verified is today (docs/wallet-ux.md, *Rules every screen follows*).
6. **Swaps much simpler, two steps said out loud.** When the allowance falls
   short, the swap screen says "2 steps" before the review. The review
   numbers them, each with its own fee and its own confirmation.
7. **The Safe's threshold stays on its blockie, and the network joins it**
   (owner's request, 2026-10-27). In simple mode an account's blockie
   carries its network's icon in one corner and, for a Safe, its `t/n`
   threshold in the other.
8. **A check said with care.** The send review says "Checked on chain: this
   transfer should go through", not that it will (owner's request,
   2026-10-27). The check runs the transfer at the latest verified head, and
   another transaction can land first.

## What stays the same

Simple mode changes how the wallet presents what it knows. It does not
change what it trusts.

- Every value is verified by the light client or shown as not available.
- A quote, a review and a signature read fresh state, whatever the screen
  showed before: the rules of docs/wallet-ux.md for Send and Safe
  transactions, and of docs/swaps.md for swaps, apply unchanged.
- Sepolia keeps every testnet warning and is never added to a total with
  real funds.
- A network-upgrade warning says that peers report it.
- A missing prerequisite greys an action out (owner ruling, 2026-10-18,
  #55).
- Every address has a blockie. An address is shown in full where the user
  confirms it.
- Every screen has previews, now one set per view.

## Two views, one set of ViewModels

- **The setting.** A domain port `InterfaceModeSetting`
  (`observe(): Flow<InterfaceMode>`, `set(mode)`), with
  `InterfaceMode { SIMPLE, DETAILED }` and the use cases
  `ObserveInterfaceMode` and `SetInterfaceMode`. Its adapter,
  `FileInterfaceMode`, keeps it in `ui-mode.json` with the app's other files.
  A file that is missing or damaged reads as SIMPLE. The three hosts wire it
  as they wire `FileDebugScreen`.
- **One ViewModel, two screens.** An app-scoped `InterfaceModeViewModel`
  exposes the mode, as `DebugTabViewModel` exposes the Debug tab. Each
  `<Feature>Route` draws `Simple<Feature>Screen` or today's
  `<Feature>Screen` from the same UiState. A screen without a simple version
  yet draws the detailed one, so the switch works from the first part.
- **Leaves, not flags.** Where simple mode says something else (an age where
  detailed view names a block), the UiState gets a display-ready leaf of its
  own, such as `AccountsUiState.Ready.freshness`. A screen never interprets
  a mode flag.
- **Previews and tests.** Each `Simple<Feature>Screen` has
  `Simple<Feature>ScreenPreviews.kt`, its previews test and its headless
  screen tests, as every screen does (`.claude/rules/ui.md`).
- **The Debug tab's switch** moves to Settings → Advanced, under the
  *Detailed view* row, and is shown only while Detailed view is on. Turning
  Detailed view off hides the Debug tab as well and keeps its switch, so
  the tab is back once Detailed view is on again.

## Freshness and pull-to-refresh

- **Live or not.** A domain port `NetworkFreshness` says, per network,
  whether it is live: the engine has BAL account coverage at its readable
  verified head (`AddressActivity.hasAccountCoverage`). `MyotisHost`
  implements it.
- **Refreshing.** The use case `RefreshNetworkData(networks)` proves the
  balances and prices of those networks again, now, for every screen that
  shows them, and wakes their polls at once. Home offers `refresh()` for
  every network, an account for its own: what pull-to-refresh asks.
- **Opening is reading.** A screen, or any one-shot read, that starts
  reading a value gets a fresh proof, at the current head. Only a reader
  that has had one may be held a proof across heads. So a screen shows
  fresh values as it opens, and a quote, a review and a signature always
  read fresh state, in either view.
- **On demand.** Each network has a `ProofSchedule`, which its native
  balances, token balances and prices share. In simple mode
  (`FollowInterfaceMode`, run by the hosts), on a network that is not live,
  a proof stands across heads until a refresh or until it is an hour old,
  and is then made again. A live network keeps its per-head reads: its block
  access lists say which accounts a head touched, so only those are read
  again. Detailed view proves at every head, as before.
- **How old a value is.** A balance reading carries the time of the
  verified head it was proven at (`BalanceReading.Verified.provenAt`,
  `TokenBalanceReading.Verified.provenAt`), so a screen can say how old it
  is.
- **What the screen says.** A display-ready line in the Home UiState
  (`AccountsUiState.Ready.freshness`), re-dated every second:
  - "Live" on a network with BALs;
  - "Updated 2 min ago · pull down to refresh", the age of the oldest value
    the screen shows;
  - "Checking Ethereum and Gnosis Chain…" while a refresh runs;
  - "Can't check Ethereum right now", with the age of what is shown, when
    the light client cannot verify (H3).
- **Pull-to-refresh** (M3 `PullToRefreshBox`) on Home, Account and its
  Activity. It refreshes every network the screen shows. A Refresh button
  in the top bar does the same, so the gesture is never the only way (a
  mouse, a keyboard, a screen reader).
- **The light client's state.** Detailed view keeps the status strip on
  every screen whose content or actions depend on it (owner's request,
  2026-10-20). Simple mode replaces it with the freshness line, a banner
  when a network cannot be checked (H3), each network's state in Settings
  (T1), and a sync line (below). An action the light client blocks is
  greyed out and says why, in both views.
- **The sync line** (owner's requests, 2026-10-29: the sync status visible
  on every screen where it decides what the user can see or do). Every
  simple screen that reads a network draws a line under its top bar while
  that network's light client cannot serve verified reads, and none once
  it can. It names the network, in the strip's colour, with a progress bar
  where there is a fraction:
  - "Syncing Ethereum · starting", "Syncing Ethereum · 2 of 6 periods",
    "Syncing Ethereum · waiting for its first block";
  - "Waiting for a new Ethereum block · the last is 2 min old";
  - "Looking for Ethereum peers that can prove balances";
  - "Ethereum isn't syncing: its checkpoint is too old · decide in
    Settings → Networks";
  - "No light client for Ethereum on this device".

  Synced, asleep in the background, off or idle, it draws nothing: an
  action on a network that is off is greyed out with its Turn on, as
  before. Home and an account say the same in their freshness line while a
  network first catches up, instead of nothing.
- **A read refused while syncing says so.** A read the light client cannot
  verify because it is still syncing (starting, bootstrapping, catching up)
  is `ReadUnavailable.SYNCING`, never "this time": "The light client is
  still syncing, so the name can't be resolved yet." A name is looked up
  again by itself every few seconds while it is, and Try again is greyed
  out meanwhile; a tap says why. In both views.

## Screens

### Home and account

- **H1 · Home.** The total in the display currency, with "Updated 2 min ago
  · pull down to refresh" under it. Then one row per account: its blockie
  with the network icon (and a Safe's `t/n`), its name, its network (and
  "Safe" or "watch only"), its value and its native balance. A Sepolia
  account says "Sepolia · test network" and "no real value", and the list
  ends with "Test networks aren't counted in the total." The add button,
  and the Wallet | Settings bar. No network sections and no status strip:
  those are Detailed view's.
- **H2 · Home, pulled to refresh.** The spinner, and "Checking Ethereum and
  Gnosis Chain…" under the total, until a newer proof is shown (15 s at
  most). A wallet on live networks alone ("Live") needs no spinner. The
  mockup's per-row "checking…" and "live" are not built: the line under the
  total says it for the list.
- **H3 · Home, can't check right now.** A banner: "Can't check Ethereum
  right now", when its balances were last confirmed, that they may have
  changed, that sending from its accounts waits, and that the app keeps
  trying. It shows too for a light client with no fresh head to read at,
  with no balances from before. The rows of that network are
  dimmed, with "3 h ago · may have changed". The total leaves them out and
  says how many it does not count. A line says that sending and swapping
  from them wait until their balances can be checked again.
- **H4 · Account.** The blockie, the account's value, its short address
  with Copy, and chips for its network and its control ("Your key", "2 of 3
  sign", "Watch only"). Send, Swap and Receive. The Assets | Activity tabs.
  Each asset row has its icon, its name, its balance and its value, or "no
  price". "Updated 40 s ago · pull down to refresh" under the total, as
  on Home.
- **H5 · Account, Activity.** A card on top for a swap whose step 2 waits:
  "Swap 100 USDC → LINK · Step 1 done. Step 2 is waiting for you."
  with Continue. As built in part 7: "Step 1 done" only on the approval's
  verified receipt that it succeeded; otherwise the card says the approval
  is on its way, failed in its block, or that Continue checks where it
  stands. Dismiss beside Continue lets it go. The card shows in Detailed
  view too: it names no block. Then the rows, newest first: what happened, when
  it was sent or signed here (an event on chain is dated only by its block,
  so its row has no time), and how it ended in words ("Confirmed", "Final",
  "Failed", "Filled"), never a block number. Above them "Activity since you
  added it" (or since it was deployed from this device), and "older
  activity is still being checked" while it is. The mockup's day headings
  and amounts' values are not built yet.
- **H6 · Receive.** The account's blockie and name, the QR code, the full
  address in groups of four, "Only send assets on Ethereum to this address",
  with what happens otherwise, and Copy and Share.

### Send

- **S1 · Send.** The asset with what is available. The amount, large, with
  25%, 50% and Max. The recipient: an address or an ENS name, with the
  resolved address under it, and the user's own accounts as chips. One fee
  row: "Network fee ≈ €0.36 · Normal ›", which opens the fee levels. Review.
  Send proves the balance it offers when it opens, so the available amount,
  25%, 50% and Max never come from an aged balance. While the form stays
  open the proof may grow old, so each balance says its age ("1,250 USDC ·
  updated 2 min ago") in place of its block.
- **S2 · Send, review.** A sheet over the form: "Send 250 USDC" and its
  value, the recipient in full with its blockie, From with its network, the
  most the fee can be in the native token and its value, and the balance
  after. "Checked on chain: this transfer should go through" (decision 8).
  Details (the block, the gas limit, the fee caps, the nonce). "Confirm and
  send" with biometrics, or "Use password instead".
  As built in part 5 (#193), and open for the owner to decide: the review is
  a screen of its own, not a sheet over the form; it shows no value beside
  the amount or the fee, and its Details have no nonce, since the review's
  state carries neither yet.
- **S3 · Sent.** The amount and the recipient, then Sent → Confirmed (when,
  and the fee paid) → Final ("in about 13 min. After that it can't be
  reversed by the network."). Details (the hash, the blocks) and Done.
  As built in part 5 (#193), and open for the owner to decide: a Signed step
  comes before Sent; the hash stays in the body, where it can be copied, as
  in Detailed view; Confirmed says its receipt was checked on chain, without
  when or the fee paid; the waiting Final says what it means, without how
  long it takes.

### Swap

- **W1 · Swap.** The venue as two choices, Uniswap | CoW Protocol: the user
  chooses each swap's venue (owner's directions, 2026-10-18 and
  2026-10-22). "You pay" and "You get at least", each a card with its token,
  and the flip button between them. Each balance says how long ago it was
  proved ("updated 2 min ago"), as on Send. The price, the network fees,
  the price tolerance (behind the settings icon). When the allowance falls
  short, an info box: "2 steps. First you allow Uniswap to use 100 USDC,
  then you swap. Each is its own transaction with its own fee." Review.
  As built in part 6 (#194), and open for the owner to decide: the form has
  no "2 steps" box, since only the quote reads the allowance. The two steps
  are first said on the review ("Step 1 of 2"), so decision 6's "before the
  review" is not delivered yet.
- **W2 · Swap review, Uniswap, 2 steps.** The swap in one line ("100 USDC →
  7.18 LINK, at least · via Uniswap · from Main"). Step 1, "Allow Uniswap to
  use 100 USDC: only this amount, for this swap", with its fee. Step 2,
  "Swap: once step 1 is confirmed, the price is checked again and you
  confirm the swap", with its fee. "Price checked on chain". What happens
  if the price moves past the tolerance. Details (the route, the pool, the
  router, the block). "Confirm step 1 of 2".
  As built in part 6 (#194), and open for the owner to decide: the review is
  step 1 alone, headed by what it allows ("Allow Uniswap's SwapRouter02 to
  use 100 USDC"). It has no one-line swap, and says what step 2 is without
  its fee, which is quoted only once a block has the approval.
- **W3 · Swap, between the steps.** Step 1 done ("Confirmed 20 s ago · fee
  paid €0.38"). Step 2 quoted again: "Swap 100 USDC for at least 7.17 LINK.
  Price checked again just now (was 7.18)". "Confirm step 2 of 2", or Later.
  Later leaves step 2 waiting in the account's Activity (H5), and says that
  the permission lets Uniswap take the token only in a swap the user sends.
  As built in part 6 (#194), and open for the owner to decide: the screen
  waits for the approval's block ("Step 1 of 2 sent: the approval", its
  status and Later), then shows step 2's review, as W2's swap with "Step 2
  of 2". That review does not say when step 1 was confirmed or the fee it
  paid, nor the price quoted before.
- **W4 · Swap review, CoW Protocol.** The signed minimum ("100 USDC → 7.20
  LINK, at least"), and CoW's estimate marked "CoW's estimate, not checked
  on chain". Step 1, allow CoW Protocol to use 100 USDC. Step 2, "Sign the
  order: no network fee, CoW's fee is in the price, it fills within 30 min
  or expires". "Checked on chain: the minimum is never below what Uniswap
  gives, less the gas and your tolerance". What CoW's servers see: the
  order, the address and the IP address, and that Uniswap keeps it on the
  chain alone (owner ruling, 2026-10-22). Details. "Confirm step 1 of 2".
  As built in part 6 (#194), and open for the owner to decide: step 1 is the
  same approval review as W2's, naming CoW's vault relayer. The signed
  minimum, CoW's estimate and the check against Uniswap come on step 2's
  review, the order's, quoted once a block has the approval.

A swap whose allowance already suffices has one step: its review is W2
without the numbered steps, and says "Confirm swap". It has no mockup of its
own.

**Between the steps** (`ApprovalPending`, a new step of `SwapUiState` that
both views share). Today, once the approval is broadcast, the swap screen
returns to its form and the user presses Review again. Instead, the screen
waits for the approval's verified receipt, then quotes step 2 itself, with a
fresh quote and its own confirmation (W3). Nothing is signed for step 2
before the user confirms it. A step 2 left for later is kept as the swap's
inputs (the pair, the amount, the venue, the tolerance), never as a quote or
a signature, and Continue (H5) quotes it again. It is kept on this device in
a file of its own (`WaitingSwaps`), one per account and token sold, until a
swap or an order of that token is sent from the account or the user
dismisses its card. Continue opens Swap filled in with it and quotes it at
once: the review is numbered as the step that waited, and an approval asked
for again where the swap should come says that the earlier one may have
failed.

### Settings

- **T1 · Settings.** Display: Currency, Tokens. Networks: each with its
  switch and its state ("Up to date", "Test network · live", "Catching
  up…", "Can't check right now", "Off"). Security: password and
  fingerprint. Advanced: *Detailed view* ("Show the light client's state,
  blocks and proofs on every screen"), off by default: what Detailed view
  shows up front, since simple mode keeps the fee levels behind a row and
  the blocks under *Details*. About, with the version.

### The other screens

As built in part 8, Watch address, Create Safe, an Activity row's detail, a
CoW order's status, Tokens and Add token each have a simple version, the
same screen with simple mode's words:

- No light-client strip.
- No block. What the detailed screen dates by its block is said as
  "checked on chain": a card, a resolved name, an owner's name, a
  contract's claim, the factory's run, a cancellation's run. An order's end
  is "proven from this account's verified logs". An Activity row's detail
  says how it ended in words ("Final").
- A check is said with care, as on Send (decision 8): a run "should go
  through", and the address the factory returns "held no code when it was
  checked".
- Every block the screen no longer shows, the fee's make-up and the verified
  runs are one tap away, under *Details*.

As built in part 9, a Safe's transaction screens follow the same rules:
Import a request, Send from a Safe, a Safe's swap, and a Safe transaction's
overview, signing and review. The owners' stand, a swap's quote, an
approval's or an execution's run and every refusal are "checked on chain".
The forms of Send from a Safe and a Safe's swap say each balance's age, as
Send's and Swap's do.

As built in part 10, a Safe's settings follow them too (owner's request,
2026-10-28). The owners, the threshold, the modules and the guard are
"checked on chain". A change's run "should go through", and one that
reverts, or is refused, says so without its block. Every change offered or
greyed out, and every check, is as in Detailed view.

Create account, Import, New account from the same phrase and Receive name
no block, and draw the same in both views. The account menu offers New
account from the same phrase in both views, on Home's long press and in the
account's top-bar menu. The Debug tab is Detailed view's alone. Every other screen has a
simple version, or names no block and draws the same in both views:
Currency, Security, Add network, the update screen and the dialogs.

## Delivery: a multi-PR plan through `feature/simple-ui`

`feature/simple-ui` was created from `main` on 2026-10-26. Each part is a PR
into it. The owner allowed the parts to be merged into it without asking
(2026-10-26). The final PR, `feature/simple-ui` → `main`, is the owner's to
merge.

0. This page, its mockups, and the rulings in `.claude/rules/` and
   docs/wallet-ux.md.
1. The mode setting: the port, the adapter, the hosts, the Settings switch,
   and each Route choosing its screen.
2. Freshness and refresh: `NetworkFreshness`, `RefreshNetworkData`, the data
   layer's on-demand reads, the proof's time in the readings, and
   `refresh()` in the Home and account ViewModels.
3. Simple Home and Settings (H1 to H3, T1): the freshness line, the "can't
   check" banner, pull-to-refresh with a Refresh button beside it, and the
   network's icon on an account's blockie.
4. Simple Account and its Activity tab (H4, H5). Receive (H6) draws the
   same in both views: it names no block.
5. Simple Send and a transfer's status (S1 to S3). What it builds
   differently from S2 and S3 is said under each, open for the owner.
6. Simple Swap for both venues, and `ApprovalPending` (W1 to W4). Between
   the steps the screen re-quotes the swap every few seconds, for up to 15
   minutes, until a block has the approval. It pauses while the screen is
   hidden, and waits out a read that fails a few times in a row. A token that
   needs its allowance set to zero first takes three steps (zero, the whole
   amount, the swap), each confirmed on its own. An approval a block has
   that still leaves the allowance short is said before another is sent,
   with a link to its status. Leaving drops step 2 until part 7's Activity
   card brings it back.
7. Activity's waiting step (H5): the swap kept as its inputs, its card with
   Continue and Dismiss, and Swap opened from it.
8. Simple versions of the screens that showed the light client's strip or
   a block: Watch address, Create Safe, an Activity row's detail, a CoW
   order's status, Tokens and Add token (*The other screens* above).
9. Simple versions of a Safe's transaction screens: propose, swap, co-sign
   and execute, and import (*The other screens* above).
10. Simple Safe settings (owner's request, 2026-10-28; *The other screens*
    above).
11. The sync line on every simple screen that reads a network, and reads
    refused while syncing said so (owner's requests, 2026-10-29; *Freshness
    and pull-to-refresh* above).
12. Watch by name on every registry that is on, as docs/networks.md has it
    (owner's request, 2026-10-29; docs/networks.md, *Names*).

Then the final PR into `main`.

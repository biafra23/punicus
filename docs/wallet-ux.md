# Wallet UX: EOAs and Safe{Wallet}

The screens Serotine is built towards, for externally owned accounts and Safe
smart accounts, and the rules they follow. A screen PR is held to this page
together with docs/architecture.md (how a screen is built) and
docs/keys-and-signing.md (how a key is held and used). The rules here apply
the *Trust* and *Data sources* rules to the screens (their short form in
CLAUDE.md, their full text in `.claude/rules/trust.md` and
`.claude/rules/data-sources.md`); where this page and those rules disagree,
the rules win and this page is fixed.

The mockups below (source: `wallet-ux-screens.html`, which opens in a
browser) draw screens 1 to 20 with their numbers; Security (21) came later
and has no mockup. They are a sketch: the
blockies are real, the QR code, amounts and addresses are not, the account
rows abbreviate the control badges, and the token rows show monograms where
a built-in token gets its bundled icon. Where a mockup and this text differ,
the text wins.

![Screens 1 to 20, numbered as below](wallet-ux-screens.png)

## Rules every screen follows

- **Two views** (owner's decisions, 2026-10-26, mockups approved
  2026-10-27). Simple mode is the default in every build;
  docs/simple-ui.md describes it, with its own mockups H1 to T1. The
  screens on this page are Detailed view, which Settings → Advanced →
  *Detailed view* turns on. Both views draw the same UiState, and every rule
  below holds in both, except where a rule says what simple mode does
  instead. A screen without a simple version draws its detailed one in both.

- **Each account on its network, and named.** Ethereum and Gnosis Chain
  beside Sepolia, one chain per account (owner ruling, 2026-10-22; it
  replaces "Sepolia only" of 2026-10-15). There is no current network: a
  screen takes its network from the account it shows and names it with the
  network's mark, and the steps that add an account or create a Safe ask
  for the network first. docs/networks.md is the plan this page follows for
  networks, and its screens N1 to N20 add to the ones below. Every table this
  page mentions (tokens, price feeds, Safe singletons) is kept per `Network`.
- **Verified, or not available.** Every chain value comes from the embedded
  Myotis engine. When the engine cannot prove a value the screen says so
  ("not available", the light client's state); it never shows a guess, a
  value from another source, or an earlier value as if it were current. A
  value the user acts on (a balance on Send, a Safe's owners and threshold,
  a receipt) names the block it was verified at. In simple mode it names its
  age instead ("Updated 2 min ago"), and the block is under *Details*.
- **The light client's state is in sight wherever it decides something**
  (owner's request, 2026-10-20). Every screen whose content or actions
  depend on what the light client can verify draws its status strip (as on
  Accounts, 1) under the top bar: Account detail and Safe detail (4,
  16), their Activity tab (25) included, Send and its status (7 to 9),
  Swap (docs/swaps.md), Watch address (10), Create Safe (13 to 15), a
  Safe's transaction (17) and its proposal, swap and import, and the
  token list Settings opens and Add token (18, 20). A screen that needs
  nothing verified draws none: Receive (6), Rename and Remove, the recovery phrase screens (11, 12),
  Security (21) and Display currency (19). Settings itself (18) keeps its
  own Light client row instead of the strip. Simple mode draws no strip: a
  freshness line, a banner when a network cannot be checked, and each
  network's state in Settings take its place (docs/simple-ui.md).
- **A balance last verified is shown as such** (owner's request,
  2026-10-19). While the light client has no head it can read at (asleep,
  catching up, its verified head too old, or no peer serving state proofs),
  a balance it proved earlier, in this run or before a restart (kept in
  `last-balances.json` with the app's other files), stays on the Accounts and
  Account detail rows instead of "Syncing…": dimmed, with "Last verified
  ‹time of its head› · may have changed since" under it (the block when the
  time is unknown). It is left out of every total and has no fiat value, and
  nothing acts on it: Send's asset choice, a quote and a signature treat it
  as syncing. While the trust anchor is stale, a proof fails or the engine
  refuses, the row says that and shows no amount from before, and no other
  value (an address's kind, a Safe's owners, a price) is shown last known.
- **In simple mode a balance may be read a while ago** (owner's decisions,
  2026-10-26). On a network without block access lists (EIP-7928; Ethereum
  and Gnosis Chain today), simple mode proves balances when a screen opens
  and on pull-to-refresh, not at every head. While the light client
  verifies, such a balance shows its age and counts in totals, at the price
  read with it, for up to an hour; older, it is shown as last verified
  (the rule above). A network with them (Sepolia) updates live. Nothing
  acts on an aged balance: a quote, a review and a signature read fresh
  state, and Send proves the balance it offers (Max included) when it
  opens.
- **Amounts are token units.** An amount is entered, reviewed and signed in
  the token's own units. Fiat values are display-only (owner ruling,
  2026-10-16, *Prices* below): a hint next to an amount, a total, a sort key,
  never an input that becomes a signed amount.
- **The UI never touches a key.** Signing is a port: a request in, a signature
  out. The password field and the recovery phrase are the only secrets a
  screen holds, under the rules in docs/keys-and-signing.md, and the screens
  that show them call `protectFromScreenCapture()`.
- **Every address has a blockie.** The ethereum/blockies identicon (seeded
  with the lowercase address) appears next to every address on screen: rows,
  headers, from and to, review, owners, detection, receive. A Safe's blockie
  carries a `t/n` overlay: its threshold over its owner count. In simple
  mode an account's blockie also carries its network's icon, the network
  named in text beside it too (owner's request, 2026-10-27). An address is
  shown EIP-55 checksummed, shortened to `0x1234…abcd` where space is short
  and in full wherever the user confirms it.
- **An address typed or pasted is checked at the input.** A mixed-case
  address whose EIP-55 checksum fails is refused with that reason; an
  all-lowercase or all-uppercase one is accepted and shown checksummed.
- **ENS names resolve on chain, forward.** A name is resolved through the
  registry and its resolver with verified calls. A resolver that answers with
  an EIP-3668 `OffchainLookup` points at an HTTP gateway, an off-chain source
  no ruling allows, so the name is shown as not resolvable. A reverse record
  is shown only when the name it gives resolves forward to the same address.
  What is signed is always the address, shown in full beside the name.
- **Control is a badge.** Every account says what the user can do with it:
  - *Full control*: an EOA whose key is in this device's key store, or a Safe
    whose owners held here reach its threshold.
  - *Co-signer*: a Safe with some owners held here, fewer than its threshold.
  - *Watch-only*: nothing held here. Send is greyed out (a tap says why);
    Receive and the balances are as for any account.

  The badge shows its icon (a key, a person with a key, an eye) before its
  text, so the icons are learned, and its text is never broken over two
  lines: where icon and text do not fit on one line beside the name (a
  narrow phone, a long name), the badge shows its icon alone, in the same
  colours. A screen reader says its text either way, and the account's own
  screen, where it fits, shows both (#68).
- **A missing prerequisite greys an action out, never hides it** (owner
  ruling, 2026-10-18, #55). An action that cannot be done because something
  it needs is missing is shown disabled, and a tap on it still works: it says
  what is missing and how to get it (`UnavailableDialog`, with the ways to
  get it where there are some). An action that does not apply to a kind of
  account at all is not shown.
- **The keyboard never hides a form's button** (owner's request,
  2026-10-20). Every screen ends at the top of the on-screen keyboard (the
  `imePadding` around the screens in `App.kt`, `adjustResize` on Android,
  and on iOS `OnFocusBehavior.DoNothing`, so the view is not lifted a second
  time), so what the keyboard would cover can be scrolled to while the
  field typed into stays in view. The keyboard's Done key on a form's last
  field does what the form's button does (`submitOnDone`): it closes the
  keyboard and, when the button is enabled, submits; a field before it
  shows Next, which moves to the next field to type in (`nextTo`). Two
  exceptions: Create Safe's owner field adds the owner and keeps the
  keyboard for the next one; and the password and recovery-phrase steps
  (the unlock, Security, Create account, Import mnemonic), whose Done
  predates this rule, submit without reading the button's state or
  closing the keyboard, their ViewModels refusing what is not ready.
- **Rows on their way hold their room** (owner's request, 2026-10-22). A
  list whose rows arrive after the screen is drawn does not push what is
  below it down as they come: grey ghost rows in the shape and height of its
  rows (`components/Ghost.kt`) stand in until they are read. Where the
  number of rows is known first, there is a ghost per row: the Assets tab's
  one per token whose balance is not read at all yet (4), which its row
  takes over, or gives up at the bottom when the token is not held. Where
  it is not (Activity, 25; a Safe's Owners and Queue, 16; a Safe
  transaction's owners, 17), three ghosts stand in for the rows, and screen
  readers hear what is being read ("Reading the Safe's storage").
- **Long press is never the only way.** A long press (a right click on
  desktop) opens an item's menu; every action in it is also reachable from a
  visible control (the detail screen's buttons), and the menu has an
  accessibility label (`onLongClickLabel`).
- **Hiding amounts** (owner's request, 2026-11-01; mockups approved the
  same day). An eye at the top left of every screen that shows how much an
  account holds or moved — Accounts (1), an account (4) with its Assets and
  Activity (25), and an Activity row's detail (26) — hides the amounts: on
  Accounts it stands where Back would, elsewhere right after Back. Open
  while amounts show, closed and tinted while they are hidden; a tap
  toggles it, and the choice is kept across restarts
  (`AmountsHiddenSetting`, `FileAmountsHidden`). Hidden, every figure reads
  `••••` beside its unit (`•••• ETH`, `€••••`), a sign and a "< " in front
  of a tiny one included; what is held, names, addresses, networks, times
  and statuses stay, and so does everything that is not an amount
  ("Checking…", "No price"). Copy still copies what it copied. Send, Swap,
  a CoW order, a Safe transaction and a transfer's status always show their
  amounts: they are acted on, and a signature is never asked for blind. A
  Safe's Queue rows are those transactions' and show theirs too. Both views
  hide alike. The ViewModels mask, so the screens draw display-ready
  strings as everywhere else (`components/HiddenAmounts.kt`).

## Navigation

The bottom bar is **Accounts | Settings**: a `NavigationBar`, and a
`NavigationRail` on a wide desktop window. Each tab is a nested graph of the
one `NavHost`, and switching tabs keeps the user's place: Settings opens over
the Accounts tab's stack and closes back to it, with nothing saved aside (owner
ruling, 2026-10-18, #49, for now; docs/architecture.md). A third tab, Debug
(27), joins them while the user has it turned on in Settings, and opens over
the Accounts tab's stack in Settings' place. The light
client's status strip stays at the top of Accounts, and of every other screen
whose content or actions depend on it (*Rules every screen follows*). A flow that ends in a key
operation or a transaction (import, create account, create Safe, send, a
Safe transaction) is a destination that covers the bar until it ends. A
Safe's transactions are `safe/{address}/tx/{hash}` (17),
`safe/{address}/propose` (Send from it, with `?asset=` for a token chosen) and
`safe/{address}/import`: identifiers only, each screen reading the rest
through its use cases. Create Safe is `create-safe` (13 to 15, one screen
with three steps), and its deployment's status `transfer/{hash}` in its
place. The
update screen covers everything, the bar included, while a network upgrade
has left this version unable to follow Sepolia, and gives way to the Accounts
tab's start when it no longer has (docs/embedding-myotis.md, *Network
upgrades*).

## Screens

### Accounts tab

1. **Accounts**
   - The light client's status strip on top, coloured by what it can verify
     (owner's request, 2026-10-19): green while reads are verified at a fresh
     head; yellow while it has no fresh head to read at for a reason that
     passes on its own (the verified head older than reads allow, no peer
     serving state proofs, or asleep in the background), when balances show
     as last verified; red with no verified head
     yet (starting, bootstrapping, catching up), sync refused, or no light
     client. A dot before its text and the bar under it carry the colour; the
     text says the same in words. Settings shows the same dot.
   - Header: the portfolio's total in the display currency, marked "testnet
     prices" on Sepolia, with a count of the assets that have no price.
   - Under the status strip, a banner while peers announce or report a
     network upgrade this version does not support, with the way to the
     update (docs/embedding-myotis.md, *Network upgrades*).
   - One row per account: blockie (with `t/n` for a Safe), name, short
     address, control badge, native balance and its fiat value.
   - The FAB (2). A long press on a row opens 3; a tap opens 4, or 16 for a
     Safe.
   - With no account yet, it names every way the FAB adds one (watch,
     import, create) and says that Create Safe, there too, can be used once
     an account's key is on this device, the account that pays for it.
2. **FAB fan-out**: Watch address (10) · Import recovery phrase (11) · Create
   account (12) · Create Safe (13). Create Safe is always there, and greyed
   out until the user holds an EOA's key: an EOA pays for the deployment. A
   tap on it then says so, with Import recovery phrase and Create account
   as the ways to get one (owner ruling, 2026-10-18, #55).
3. **Account menu**: Send (greyed out when watch-only, a tap saying why;
   for a Safe, usable once an owner of it is held here, and it opens the
   proposal of a Safe transaction, 16) · Receive · Copy address · Rename · New
   account from the same phrase (an EOA's; greyed out when watch-only, a tap
   saying why) · Remove. Removing an account the user
   holds a key for says that only its recovery phrase brings it back, and
   asks for a confirmation, not the wallet password (owner ruling,
   2026-10-18, #17). The key store keeps one envelope per account, under its
   address, so the removal deletes that account's envelope and its biometric
   wrap; with the last envelope the wallet password and the biometric path go
   too (`EnvelopeKeyStore.delete`). New account from the same phrase is a
   screen of its own, protected from screen capture while it takes the wallet
   password: it adds an account of the recovery phrase the account comes from,
   on the account's network (the first of the phrase's accounts whose key the
   wallet does not hold there; one watched there becomes held), from the seed
   stored here, without the words (docs/keys-and-signing.md, *Further accounts of a phrase*); then
   it shows the account added, its name, address and number in the phrase.
   The account detail's top-bar menu (4) offers it too.
4. **Account detail**
   - Header: a large blockie, the account's total, the full address with
     Copy, the control badge.
   - Send, Swap and Receive buttons, in one line whose width they share,
     on a phone as narrow as an iPhone mini's too (Send greyed out as in 3;
     Swap, 22, greyed out, a tap saying why, when watch-only; on a Safe,
     Swap opens 24 and is greyed out as Send is: docs/swaps.md, *A Safe's
     swap*).
   - The tabs Assets | Activity: Assets is what follows, Activity is 25,
     drawn in the tab, read only while the tab is shown.
   - Sorted by value, highest first: assets without a price follow the
     priced ones, by name; a stale price sorts by the value it shows. The
     top bar's menu sorts by name instead ("Sort by name", then "Sort by
     value" back), offered while the asset rows are shown.
   - One row per asset: icon, symbol, amount, fiat value or "no price". A
     tap opens Send (7) with the asset chosen; where Send cannot be done
     (as in 3) the row is drawn as ever and the tap says why, as the
     greyed-out Send button does. A long press opens 5. A token whose
     balance is not read at all yet holds a ghost row's place after the
     rows (*Rows on their way hold their room*).
   - Below the assets, the account's NFTs (*NFTs*, issue #246): the built-in
     collections' first, then a collapsed "Unlisted collections" row. None
     counts in the total.
   - Footnote: the price source ("Chainlink, testnet prices") and the block
     the balances were verified at.
5. **Asset menu**: Send ‹symbol› (7 with the asset chosen; greyed out as in 3) ·
   Swap ‹symbol› (22 with the asset sold chosen; as Swap in 4) · Copy
   contract address · Hide token. The native currency's row has the menu
   too, with Send and Swap alone.
6. **Receive**: a QR code of the EIP-55 address, "Only send Sepolia assets"
   (for a Safe: "This Safe exists only on Sepolia"), Copy and Share.
7. **Send, compose**
   - From: the account, fixed.
   - Asset: the account's assets — its native currency and the tokens it
     holds — each with its verified balance and block; the one from 5
     preselected (shown whatever its balance, and said when it is not among
     the account's tokens).
   - To: an address, an ENS name or one of the user's accounts (offered as
     chips); the blockie appears once the input parses as an address or
     resolves. The address is checked at the input as everywhere; a name is
     resolved once the typing pauses, against finalized state, and says the
     block it was resolved at. Why a name does not resolve is said (no
     address, off-chain only, not a name this build resolves, or not
     verifiable right now, with Try again). The zero address, and for a
     token the token's own contract, are refused: nobody can ever move out
     what is sent there.
   - Amount, in the asset's units and decimals, with Max (the whole verified
     balance; for the native currency the quote then says the fee does not
     fit) and a fiat hint beside it ("≈ $40.00 · testnet prices"), the
     verified balance's value scaled to the amount: display only.
   - Fee: Slow | Normal | Fast, from the verified fee history.
   - Review quotes the transfer; a refusal (not enough of the asset or for
     the fee, the call reverts, not a token, not synced, not verifiable) is
     said here and nothing is signed. So is an earlier transaction from the
     account that may still be on its way (its broadcast found no peer to
     answer, and no block has it): nothing new is quoted from the account
     until it is mined or three hours old, and the step offers its status
     (9), where it is tried again.
8. **Send, review**
   - The amount in token units, exact; the recipient in full, with its
     blockie, the ENS name it was resolved from and its name in the wallet.
   - The most the fee can be (gas limit × max fee per gas), and the level,
     gas and fee cap it is made of.
   - A verified simulation: the transfer run as a verified call from the
     sender against the head, and the balance before → after, each with the
     block it was verified at (the native balance "at least", net of the most
     the fee can be; for a token, its balance and the native one that pays
     the fee), and what earlier transfers not mined yet may still take. A
     call that reverts stops at 7, before anything is signed.
   - The quote is never signed stale: it is made when the review is entered,
     again on Refresh, when the screen is shown again after it was not, and
     at the tap that would sign it when it is over a minute old (made again
     and said, not signed). The biometric prompt's deadline is the moment the
     quote turns a minute old (`Unlock.WithBiometrics.notAfter`, absolute, so
     no check between the tap and the prompt stretches it; with less than 15
     seconds left at the tap, the quote is made again first, and said so):
     one not answered in time is dismissed, nothing is signed, and the quote
     is made again and said.
     A name is resolved again with every quote (one
     that moved, or that cannot be verified again, is said, not signed), and
     back to 7 drops it.
   - Back, the system's included, returns to 7. It waits while a signature
     runs, and after a broadcast no peer answered it leaves Send: only Try
     again may send those bytes.
   - The password field, or the biometric prompt where the user enabled it
     ("Sign with fingerprint or face", the password the fallback), then Sign
     and send. Each refusal is said: a wrong password, a dismissed prompt,
     biometrics that cannot open the key now (use the password), no key, a
     key store that cannot open it.
   - Signed but not broadcast: said, and only the same signed bytes can be
     sent again (Try again, when no peer took it); a transfer the engine
     rejects on verified state is not offered again, and 7 is open again
     for a new one. Nothing is signed twice. One no peer answered may have
     reached one all the same, so it is recorded as maybe sent: leaving the
     screen, or the app, keeps its bytes for Try again on its status (9).
9. **Send, status**: what was sent (amount, asset, recipient, from this
   device's record; for a Safe's deployment "Deployment of a Safe", the Safe
   with its `t/n`, its version and the account that paid), then Signed → Broadcast → Included (receipt verified,
   its block; "it reverted" when it failed, its fee paid) → Finalized (its
   block at or below the engine's finalized head). A receipt that cannot be
   verified right now reads as such, never as pending. One maybe sent (no
   peer answered its broadcast) says at Broadcast that no peer confirmed it,
   until a block has it, with Try again, which sends the same signed bytes
   (`RebroadcastTransfer`). A retry turned away (rejected with no receipt, or
   refused) is said as the peer's word, and the record kept: "already
   known" or a nonce used past the light client's head come back so, and a
   deployment's record is its Safe's only copy. Its three hours and the
   mined count bound it. Done returns to where Send was opened from.
10. **Watch address**
    - An address or ENS name, and a name for the account.
    - A detection card built from verified code and storage: an EOA; an EOA
      with an EIP-7702 delegation (its code is the designator `0xef0100` ++
      an address), shown as "EOA, delegated to ‹address›" with that
      address's blockie, because the delegate's code can act for the
      account, and badged like any EOA by whether its key is held here; a
      Safe (version, `t of n`, the owners' blockies, enabled modules and a
      guard if any); or another contract.
    - The control badge the account will get (owners held here count).
    - As built (`WatchViewModel`, issue #71): an ENS name is resolved as
      Send's recipient is (`ResolveRecipient`, finalized state only, once the
      typing pauses), the field saying the address it resolves to, in full,
      and the finalized block it was resolved at, whatever the card says; a
      name that could not be verified offers Try again. At Add the name is resolved again: what is
      watched is the address it has then, and one that moved, or cannot be
      verified again, is said and nothing is added. An account added by a
      name and given none of the user's is named by it, and its name follows
      that ENS name (`Account.ensName`, issue #67) until the user renames it:
      the name field says so and shows the name. Wherever the name is shown
      (Accounts rows, the account's header) it is resolved again, at once and
      every 10 minutes (every 30 s until a first verified answer,
      `ObserveEnsNames`); once a verified answer says it no longer points to
      the account, or that it can no longer be verified to (its resolver
      answers only off-chain), the row and header show the short address and
      say why. A name with no verified answer right now stands as last
      verified. Primary names (the reverse record, any account) are still to
      come in #67. The zero address is refused: nobody holds its key.
11. **Import recovery phrase**: today's seed screen, with the account's name,
    the wallet password (on the first key) and the biometric opt-in (still to
    come; until then it is turned on in Security, 21).
12. **Create account**: the new phrase, shown on this screen alone and only
    until it is stored, protected from screen capture, the display kept on;
    then a check of three of its words; then the wallet password and
    the biometric opt-in (on the first key; still to come, as on 11).
13. **Create Safe 1/3, owners**: a name; owners from "This device (hardware
    key)", the user's mnemonic accounts and external addresses.
    - As built (`CreateSafeViewModel`, `PlanSafe`): the name (optional, as a
      watched account's; at most 40 characters); the wallet's externally
      owned accounts, held here ("Key on this device") or watched, each with
      its blockie and a check box, the first held one chosen to start with;
      and an address or ENS name typed, checked at the input and resolved as
      Send's recipient is, then added with its blockie, in full, and the name
      and finalized block it was resolved from. A name is resolved again with
      every quote of the review (15), as Send's recipient is: one that moved,
      or cannot be verified again, sends the review back here with the
      reason, and nothing is quoted or signed for it.
    - "This device (hardware key)" is listed but not offered: its passkey
      signer is not built yet, and the row says so. The domain already
      refuses a device key as the only owner (`SafePlanRefusal.DeviceKeyAlone`).
    - Refused at this step, each said: no owner, more than 20, the zero
      address or the owner list's sentinel `0x…01`, an owner twice.
14. **Create Safe 2/3, threshold and recovery**: a threshold stepper, and only
    the shapes docs/keys-and-signing.md (*Backup and recovery*) allows, its
    reasoning applied to every threshold:
    - a device key is never the only owner;
    - a threshold equal to the owner count above one (2 of 2, 3 of 3, …)
      only alongside a recovery module: with one owner lost, the rest could
      never reach it, not even to swap the lost one out (owner ruling,
      2026-10-18, #54).
    - As built: the stepper runs from 1 to the number of owners and starts at
      1 for up to two owners, a majority short of all of them above (2 of 3,
      3 of 4). Each value says what it means and how it stands with an owner
      lost; the rules above are listed under it. No recovery module is
      pinned, so none is offered, and every n-of-n above one is refused with
      the reason (`SafePlanRefusal.NeedsRecoveryModule`): 2 of 2 is not
      offered. That every n-of-n follows the keys doc's 2-of-2 rule is the
      owner's ruling (2026-10-18, #54); its change half, a change of an
      existing Safe that ends as an n-of-n, is refused on screen 17. Which
      recovery modules come stays open (#54, #72).
15. **Create Safe 3/3, review**: the address predicted locally (CREATE2), with
    its blockie and `t/n`; the version, owners and threshold; the deployment
    fee and the account that pays it; Deploy. After deployment the Safe is
    detected like a watched one (*Safes* below) before it is listed.
    - As built (`QuoteSafeDeployment`, `DeploySafe`): the address in full
      with its blockie and `t/n`, computed here from the factory, the
      singleton, the initializer and a fresh random salt nonce, and the
      factory's verified run returning that same address, with the block it
      ran at and the block the address held no code at; "Safe 1.4.1"; the
      owners in full; the paying account, chosen among those held here (an
      owner held here first), the fee level, the most the fee can be and what
      it is made of, the payer's balance before → at least after, and what
      its earlier transactions not mined yet may still take. Then the unlock
      and Sign and deploy, as 8's.
    - Refused and said, nothing signed: an address that holds code already, a
      deployment of it sent and not mined yet, a deployment of a Safe with the
      same owners (in their order) and threshold sent and not mined yet (a
      review planned again has another salt, so another address: it would be
      a second Safe), a run that returns another
      address (the pin does not match the chain), the payer's funds, a run
      that reverts, a light client not synced or not verifying.
    - The quote follows 8's freshness rules (made on entry, on Refresh, on a
      change of payer or fee, when shown again, and again at the tap when
      over a minute old), the password goes when the screen is hidden and
      after each attempt, Back returns to 2/3 (dropping the plan: the next
      review has another address), and one signed but not broadcast is sent
      again only as Try again (`RebroadcastTransfer`).
    - Sent, the status (9) replaces the screen and says "Deployment of a
      Safe", and where its listing stands; it shows that, and drives
      nothing. The app lists the Safe itself (`FollowSafeDeployments`, run
      by the host for as long as the app runs, whatever screen is open, and
      again after a restart): for each
      recorded deployment whose listing has not ended, it waits for the
      verified receipt, then, when it succeeded, for the light client to
      prove code at the address (`ListDeployedSafe`), checks that it is a
      Safe with the owners, in their order, and the threshold deployed, and
      watches it (`WatchAddress`, which detects it again) under the name
      given. A deployment that reverted has its address read once
      (`ListDeployedSafe.afterRevert`): someone else's copy of the same call
      mined first creates the very same Safe, which is then listed, and said;
      otherwise the status says this transaction created no Safe. An outcome
      that is no ending (not verifiable again, the list not saved) is tried
      again. How it ended is written into the deployment's record
      (`SentAction.SafeDeployment.listed`), which the record keeps until then
      (`SentTransfers.prune` drops an unlisted deployment only after
      `DEPLOYMENT_LIFETIME_SECONDS`, a week), so leaving the status screen,
      or the app, loses nothing; and a Safe once listed and then removed by
      the user is not listed again.
16. **Safe detail**: as 4, with the tabs Assets | Activity | Owners |
    Queue (n). The owners are shown with blockies, a "this device" mark
    on the ones held here, and the threshold; the modules and guard
    follow them. Safe settings, above them, opens 28; it is greyed out
    while the Safe's network is off (a tap offers Turn on) or this version
    can no longer follow it, as Send is.
    - Queue lists this device's pending transactions in nonce order, from
      the Safe's verified nonce on: one whose nonce the Safe has passed is
      gone (`ObserveSafeTxQueue`), and the file drops it at the next
      proposal, import or signature. Two at one nonce (a transfer and its
      rejection) are both listed; only one can ever run. Each row is what it
      does as decoded here ("Send 40 USDC", a token the wallet does not know
      in its smallest units, an added token that takes a built-in token's
      symbol marked "Not the built-in USDC: another contract"), with the
      blockie of the address it names, its nonce and how many run before it
      ("Nonce 13 · 1 before it"), its signatures against the threshold ("1
      of 2 signatures on this device": those this device holds and the Safe
      accepts now; approvals on chain show on 17), and "Not signed here" with
      the reason for a delegate call or gas terms.
      While the Safe cannot be verified, the rows say that neither its nonce
      nor the signatures are checked. A tap opens 17.
    - Import a request (above the rows): the text another owner's Share
      request gave, pasted, then `ImportSigningRequest`, for this Safe only (a
      request for another Safe is refused, `SafeTxRefusal.OtherSafe`, before
      anything is kept). The Safe's check says "Checking the Safe on
      chain…" while it runs: a reading that could not be verified this time
      is waited out for the next one, for up to 45 s, and one still
      unproven then says "The Safe's owners and nonce could not be verified
      this time: try again." (`SafeTxRefusal.SafeUnavailable`; the checks
      before Sign, Approve, Execute and a proposal wait the same way,
      docs/embedding-myotis.md). Every refusal is said under the field; one
      that passes is queued and 17 opens for it, in place of the import,
      saying "Added to ‹Safe›'s queue on this device." until a step begins.
      Scanning its QR code is not built: no camera is wired in yet, and the
      screen says to paste the text.
    - Send from a Safe (the header's button and the account menu, both only
      while an owner of it is held here) proposes a transfer
      (`ProposeSafeTx`): 7's form, from the Safe, with no fee to choose —
      whoever executes it pays — native or
      ERC-20, a plain call with no refund, at the Safe's verified nonce or
      the first after those queued from it without a gap (one queued far
      ahead, as an imported request can be, moves nothing; how far ahead of
      the Safe's nonce a request may be signed is not bounded, the owner's
      open question, issue #59). The Safe's balance less what the
      transactions queued before it move must cover it, and the Safe's call
      is run as a verified call from the Safe; the review says when the
      engine would not run it ("Not simulated"). The review shows the Safe
      transaction decoded here, its Safe nonce and how many queued here run
      before it, and the Safe's balance before, what those move, and after,
      with its block. It is proposed again on Refresh, when the screen is shown
      again, and at the tap that signs it when it is over a minute old, as 8's
      quote is; its minute runs from after the proposal's Safe check. The
      tap that signs checks the Safe again first, within what is left of
      that minute (less the prompt's 15 s for the biometric prompt): a check
      that outlasts it signs nothing and proposes again, saying "Checking the
      Safe on chain took the time the quote had left…". An owner held here,
      chosen when there are several, signs it
      (`SignSafeTx`, the password or the biometric prompt as on 8); it is then
      queued and 17 opens for it, in place of the proposal. Nothing is queued
      until an owner held here signs it, so a proposal given up leaves no
      nonce taken. A proposal the queue cannot save once signed is shared
      from the review (its QR code and text), not lost: Back asks before it
      leaves that signature behind, and a refresh that proposes another
      transaction (another nonce) drops it and says so. The review stands on
      its proposal while the Safe's reading or the account list is not
      there; a refresh or the signature checks the Safe again.
17. **Safe transaction**
    - The action, decoded here from the raw transaction (`SafeTxAction`): a
      native or ERC-20 transfer, an ERC-20 approval, a swap through a router
      exactly as Serotine builds one and paid to the Safe, a change of the
      Safe's settings (an owner added, removed or swapped, the threshold, a
      module, the guard, the fallback handler), a rejection (the Safe calling
      itself with nothing: it uses up the nonce), or a contract call with its
      selector; and the Safe nonce, with how many run before it.
    - An approval or a swap says where it stands now (docs/swaps.md, *A
      Safe's swap*): whether its spender or router is Uniswap's pinned
      SwapRouter02, its code proven, with a warning when it is not; for a
      swap, what it returns now by a verified QuoterV2 quote against its
      minimum, its deadline, and when it has passed, that it can never run.
      A minimum fallen behind the market by more than the default tolerance
      is said here and on the execution's review, with what a sandwich could
      take. Either, or a deadline passed, offers to propose the swap again
      (24, the swap fixed and only the tolerance open): at its nonce, in its
      place, with a fresh minimum and deadline.
    - A change that weakens the Safe carries a warning wherever it is shown
      (16's row, 17, the Sign step, the biometric prompt): a module disabled,
      a guard set or removed (its limits, allowlist or delay stop), a
      fallback handler set (it answers EIP-1271 for the Safe: one that
      accepts every signature lets anyone spend what the Safe approved,
      through Permit2, permits or orders) or removed (what it answered
      stops: safe transfers of ERC-721 and ERC-1155 tokens to the Safe are
      refused, and its EIP-1271 checks fail, so orders and permits signed
      for it no longer work; owner's decision, 2026-10-20, on #106's
      review). A fallback handler set that is not the pinned canonical
      CompatibilityFallbackHandler 1.4.1 (`0xfd0732Dc…Ec99`,
      `PinnedFallbackHandlers`) is refused: shown, and never signed,
      approved or executed here (`SafeTxRefusal.UnpinnedFallbackHandler`;
      owner ruling, 2026-10-18, #63), on 16's row and 17 with its reason.
      Setting the pinned one, or removing the handler, is signed with its
      warning; so are a guard removed and a module disabled, which keep
      their warning and are not refused. A module enabled is no longer a
      warning alone: it is refused, whatever the module, shown and never
      signed, approved or executed here (`SafeTxRefusal.ModuleEnabled`;
      owner ruling, 2026-10-18, on #54: play it safe), on 16's row and 17
      with its reason, "Enabling a module is refused: a module can move the
      Safe's funds without its owners. Which modules may be enabled is still
      open (#54)." A temporary, warned "dangerous mode" past it is planned
      (#78), not built.
    - A call of the Safe itself is signed only when it decodes exactly: a
      settings change whose data is that function's encoding, or the empty,
      zero-value rejection. Any other self-call, whatever its selector (a
      trailing byte, an address word with dirty upper bytes, an unknown
      function, ether sent to itself), is refused as "a call to the Safe
      itself that could not be decoded exactly"
      (`SafeTxRefusal.UndecodedSelfCall`): Solidity ignores trailing data and
      the Safe's decoder masks addresses, so on chain it could still change a
      setting, the fallback handler of #63 included.
    - A change that would leave the Safe an n-of-n above one (a threshold
      changed to the owner count, an owner removed or added with the
      threshold at the count that leaves) is refused when it is signed,
      approved or executed, against the Safe's verified owners and threshold
      then (`SafeTxRefusal.NeedsRecoveryModule`): the change half of the
      owner's ruling (2026-10-18, #54), whose creation half is screen 14's.
      No recovery module is recognised yet, so the Safe's modules do not
      lift it. A threshold at or above the owner count it leaves is refused
      (above it reverts anyway). Screen 17 reads it with the owners, and
      shows it as the transaction's refusal, offering nothing to sign. The
      check sees the Safe's current owners and threshold only, not changes
      queued at earlier nonces: Serotine's own execution quote catches a
      combination that ends as an n-of-n, but another wallet holding the
      signatures could still execute it. Closing that fully is part of how far
      ahead of a Safe's nonce a request may be signed, the owner's open
      question (#59).
    - `delegatecall` is marked as such and decoded no further: the Safe runs
      the target's code as itself. This wallet shows it and does not sign,
      approve or execute it (`SafeTxRefusal.DelegateCall`); a batch made by
      another wallet through MultiSend arrives as one. By the owner's ruling
      (2026-10-18, #57) a delegate call is to be co-signed only when its
      target is the pinned MultiSendCallOnly 1.4.1 and each inner call is
      decoded and shown. Not built yet (#57): until it is, every delegate
      call is refused, and the refusal says batches come later.
    - Gas and refund terms, when any of `safeTxGas`, `baseGas`, `gasPrice`,
      `gasToken` or `refundReceiver` is not zero: shown, and marked as what
      this wallet does not sign, approve or execute
      (`SafeTxRefusal.GasTerms`), as it proposes none. A refund pays
      `(gas used + baseGas) × gasPrice` in `gasToken` to `refundReceiver`,
      bounded only by what the Safe holds (for ether the gas price is capped,
      `baseGas` is not), and with a gas price the call gets exactly
      `safeTxGas`; a `safeTxGas` without one lets a failing call use up the
      nonce. Whether to allow bounded terms, with the most a refund costs
      shown, is the owner's open question (issue #58).
    - Each owner's state (`ReadSafeTxOwners`): signed (a signature that
      recovers to it over the hash computed here), approved on chain (a
      verified `approvedHashes`), missing, or not verifiable now; a signature
      of an address that is no owner now counts for nobody.
    - Share request (QR or text) · Sign (an owner held here) · Approve on
      chain · Execute, once the threshold is met. Execute may be sent from
      any account held here, owner or not, which pays for it (owner ruling,
      2026-10-18, #56); an owner's sending counts as its own confirmation.
      Execute is greyed out, not hidden, while the confirmations fall short or
      another transaction runs first, a tap saying why (#55). Known not to
      follow #55 yet, tracked in #74: Sign and Approve on chain are hidden
      where they cannot be done, and so is Execute while the owners' states
      cannot be read. Only the Safe's
      next nonce can be executed, and an approval or
      execution sent and not mined yet is not offered again, from any
      account held here. What is shown and shared carries the signatures of the
      Safe's current owners only, so another device never refuses it for an
      owner removed since.
    - As built (`SafeTxViewModel`, by the Safe and the transaction's hash):
      the Safe's blockie with `t/n`, its name and address; the action card,
      every address in full with its blockie; the Safe nonce and how many
      run before it; the hash computed here; the gas and refund terms, when
      any, and the refusal; then each owner's row (blockie, name, "This
      device", its stand and its block), the threshold and the
      confirmations, read again on Check again, whenever the request's
      signatures change and when the screen is shown again. That reading
      checks the Safe as a one-shot check does: a reading that could not be
      verified this time is waited out for the next one, for up to 45 s, the
      ghost rows standing in for them meanwhile (*Rows on their way hold
      their room*), said to screen readers as "Reading the owners and their
      approvals".
    - Share request opens the request's text (`EncodeSigningRequest`) as one
      QR code when it fits one (else it says to share the text), with Copy
      text and, where the platform has a share sheet, Share. It is offered
      only while the Safe is verified: what is shared then carries only
      signatures its current owners made, checked here.
    - Sign opens a step: the owner held here who signs, when there are
      several, and the unlock of 8. The tap that signs checks the Safe first
      ("Checking the Safe on chain…", the same wait), and only once it
      passes is the key asked for, the biometric prompt included; leaving
      the screen or Back during the check stops it, so no prompt comes up
      over another screen. The signature is made against the state that
      check verified. The signed request is queued; one the queue cannot
      save stays on the screen, to be shared before it is left. Approve on
      chain and Execute check the Safe as their quote is made, saying the
      same; the quote's minute runs from after that check. While any of
      these checks shows, the display is kept on.
    - Approve on chain, offered while the confirmations fall short, and
      Execute review the EOA transaction as 8 reviews a transfer: the
      account that sends it (an owner held here first; for Execute, any
      account held here whose sending reaches the threshold), the fee level,
      the most the fee can be, the account's balance before and after, the
      verified run, and for Execute the confirmations it is executed with. An
      approval the confirmations made needless meanwhile says so and is not
      signed. Each step offers only what the owners read for the request as
      it is now say: a new signature reads them again before anything is
      offered on it.
      The quote follows 8's freshness rules and its Back, and one signed but
      not broadcast is sent again only as Try again, through
      `RebroadcastTransfer`; a quote made or refreshed while the screen was
      hidden is made again before it is signed. A step signing goes on when
      the transaction leaves the queue meanwhile; one that does not ends,
      its password with it. Sent, the status (9) replaces the screen, and
      says "Approval of a Safe transaction" or "Execution of a Safe
      transaction", with the Safe and its nonce.
    - Remove from this device asks first, then takes it out of the queue:
      a signature already shared stays valid elsewhere until the Safe's
      nonce passes it. One the Safe's nonce has passed says it ran, or that
      another transaction used its nonce.

28. **Safe settings** (issue #183), from the Owners tab of 16: the Safe's
    settings as its verified storage holds them, and the changes of them
    an owner held here proposes, each a Safe transaction its owners sign
    like any other (`ProposeSafeSettingsChange`).
    - Shown: the owners (blockies, "This device" on the ones held here),
      the threshold, the modules, the guard and, from 1.5.0, the module
      guard, with the block they were verified at. Each owner offers
      Replace and Remove; below them Add an owner, Change the threshold,
      Disable for each module and Remove the guard where there is one.
      While no owner of the Safe is held here every one of them is greyed
      out, and a tap says why (owner ruling, 2026-10-18, #55). Enable a
      module and Update the Safe's version are shown greyed out too, and a
      tap says why they are never offered here: a module enabled is refused
      (owner ruling, 2026-10-18, on #54), and a version update runs a
      migration contract's code as the Safe, a delegate call, which this
      wallet does not sign (#57) until a migration contract is pinned.
    - Each change has its form: the owner it removes or replaces; the new
      owner's address, typed or picked from the wallet's accounts on the
      Safe's network that are not owners yet (an address only, no name); and
      the threshold it leaves, a stepper from 1 to the owners the Safe then
      has, starting at the Safe's own, or one below the owner count where
      that would leave every owner needed. What is filled in is checked as
      it is typed against the Safe as verified (`SafeSettingsChange.problem`):
      an address that is no owner's (zero, the list sentinel `0x1`, the Safe
      itself), an owner already, an owner or module no longer there, the
      only owner removed, a threshold out of range or unchanged, and the
      n-of-n above one the owner's ruling refuses (2026-10-18, #54; 17's
      `NeedsRecoveryModule`), each said under it with Review greyed out.
      Removing or replacing an owner held here says what it does to this
      device's part: it signs with its other owners held here only, or,
      with none left, the Safe becomes watch-only here. A module disabled
      and the guard removed carry 17's warnings.
    - Review proposes it from verified state: the Safe checked as a
      one-shot check (17's wait), the change checked against it again, the
      call the Safe's own function on itself with no ether, the entry before
      an owner or module removed or replaced named from its verified list,
      at the Safe's next nonce as 16's Send finds it, and run as a verified
      call from the Safe to itself, which a revert stops. A change queued
      before it on this device that changes the settings too is said: this
      one is built against the lists as they are now, and its execution's
      verified run says whether it still goes through. The review, its
      minute, its refresh, the signature (the owner held here who signs,
      the unlock of 8, the Safe checked again first) and a request the queue
      cannot save follow 16's Send exactly; signed and queued, 17 opens for
      it in place of the settings. The biometric prompt names the change in
      full, its warning with it.

### Activity (issue #85)

Screens 22 to 24 are the swap screens (docs/swaps.md).

25. **Activity**: the transactions this device sent for an account, its
    token transfers and, for a Safe, its own events on chain, by date, in
    the Activity tab of the account's detail (4, 16). Newest first; the top
    bar's menu turns the rows round ("Oldest first", then "Newest first"
    back), offered while the tab is shown, as it sorts the assets on 4.
    Each row is dated: a sent transaction by when a peer took it, a CoW
    order by when it was signed, and an event on chain by when its block
    was produced, as the block's verified header says, which the engine
    reports with the log; from an engine that does not report it, the
    event names its block alone ("Block 9,415,302") and goes among the
    dated rows by that block. A sent transaction seen in a block goes by
    the block's time where an event in that block says it, before the
    events of that block; one still waiting for its block goes by when a
    peer took it, and above every event dated by its block alone.
    - First, what the list covers: transactions sent from this device, and
      on chain from the block its history starts at on (when it was
      deployed from this device, or added here: "since added", owner ruling
      2026-10-20) its token transfers and a Safe's events, each with how far
      it is checked so far, or why it is not listed yet. For an EOA, ETH it
      received and transactions sent from it elsewhere are not listed yet
      (issue #85 has the plan for them).
    - One row per transaction: the blockie of its other side, what it did
      ("Sent 0.25 ETH", "Swapped 100 USDC for at least 4.28845 LINK",
      "Executed: Send 40 USDC", "Deployed a Safe"), whom or what else it
      concerns ("To Bob · 0x5aAe…eAed", "Safe nonce 4 · Treasury · …"), when
      a peer took it, and how it ended as this device
      saw it: in its block or finalized there, reverted, no block seen yet,
      no peer confirmed its broadcast, or mined while the app was not
      watching unless another transaction took its nonce. A tap opens its
      detail (26), and from there its status (9).
    - A Safe's Activity lists the approvals and executions of its
      transactions and its deployment, whichever account here sent them
      ("Executed transaction 4: Send 40 USDC", "By Alice · …"). An approval
      or execution says what the Safe transaction does as its queue row
      does (16: "Send 40 USDC", "Add an owner", "Call a contract"), its
      transfer in the units of a token the wallet knows.
    - The CoW orders an account signed here (docs/swaps.md) sit among its
      transactions by date: "CoW order: 40 USDC for at least 0.0159 ETH"
      by when it was signed while it may fill, then as the chain proves it,
      by its fill's or cancellation's block ("Swapped 40 USDC for 0.0161 ETH
      on CoW", "CoW order cancelled"), or by when it was signed when it
      never filled ("CoW order not filled"). A fill's transfer of what it sold is not listed
      again. A tap opens the order's status, not 26.
    - And its events on chain, from the engine's verified log index
      (`ObserveSafeHistory`): ETH received ("Received 0.5 ETH", "From Bob ·
      …"), executions and failed ones, approvals, its set-up, owner,
      threshold, module, guard and fallback-handler changes, a module's
      transactions and messages signed. An execution or approval says what
      the Safe transaction did where this device holds its request, in its
      history of approvals and executions ("Executed: Send 40 USDC",
      "Failed: Send 40 USDC", "Approved: Add an owner"), and "a Safe
      transaction" where it holds none: the log carries only the hash. Each
      sits among the sent transactions by date, or by its block ("Block
      9,415,302") where the engine did not date it, and opens its detail (26);
      one whose transaction is listed as sent from here, in a block, is left
      out when its row says the same (an execution, an approval, a
      deployment); a failure and a settings change are not. A module
      enabled, or a fallback handler other than the pinned one, is a
      warning; so is every other change that weakens the Safe (a module
      disabled, a guard set or removed, the handler removed), with the
      warning its Safe transaction is signed with (17). A transaction sent
      from here that waits for its block goes by when a peer took it, above
      every event dated by its block alone.
    - And every ERC-20 and ERC-721 transfer to or from the account, whichever
      token moved (`ObserveTokenTransfers`, the log index's topic entry):
      "Received 250 USDC" / "From Treasury · …" for a token the wallet shows
      (listed or added, not hidden), "Sent 1 USDC" for a fee its transfer
      paid to another address, "USDC NFT #7" for an ERC-721. A contract the
      wallet does not show can log any transfer it likes, so its row says
      only "An unlisted token logged a transfer to it", names the contract
      and not the other side, and is marked as unconfirmed. A transfer of
      nothing is left out: anyone can have a token log one from any address,
      which plants a look-alike address in a history. An added token whose
      symbol is a built-in token's warns, as every screen does. One received
      from an address this wallet never sent to and that is none of its
      accounts (`ObserveSentToAddresses`: the recipients of the transfers
      and Safe transactions its accounts sent, approved or executed) is
      cautioned, "From an address you have not sent to: compare every
      character before you copy it" (owner's request on #136, 2026-10-21):
      anyone can send a little from a look-alike of an address the user
      uses, so that it is copied from the history later. ETH a Safe
      received is cautioned alike. By block like
      the events, opening its detail (26); the transfer a row sent from here, in
      a block, already says (its token, amount and recipient, or the amount
      sold in a swap) is that row alone.
    - Where the list comes from: every transaction a peer took (or maybe
      took) goes into the history as it is recorded
      (`HistoryRecordingSentTransfers`), which is kept for good, unlike the
      record that holds nonces back, which forgets one once its nonce is
      mined. How each ended is recorded by `FollowSentTransactions`, which
      the hosts run while the app is open: the verified receipt's block and
      result, then its finalization. The engine finds a receipt only for a
      transaction mined while it watches for it, so one mined while the app
      was closed is told by its account's verified mined count instead: its
      nonce is used, by this transaction or by one that took its place,
      which the device cannot tell apart. The status screen (9) reads the
      same history once the record has forgotten a transaction.
26. **Activity detail**: one row of 25 in full, under the Accounts tab like
    its list (`activity/{address}/tx/{hash}`, `?log=` the log index for an
    event). Its row's headline and status first, then every party it
    concerns, each address in full (EIP-55, its blockie, the wallet's name
    for it) and every hash (the transaction's, a Safe transaction's, a
    message's), each selectable and with Copy, then what it moved, its
    nonce, when a peer took it (an event: when its block was produced, where
    the engine reported it) and its block. Read from what 25 is read from,
    so it says what the row says.
    - A transaction sent from here: From, its other side by kind (To, the
      token contract and the amount; the spender and the allowance; the
      router and both tokens of a swap; the Safe, its nonce, the Safe
      transaction's hash and whom it calls; a deployment's Safe, owners,
      threshold and version), and Status, which opens 9 (Try again is
      there).
    - A token's transfer the wallet shows: From, To, the amount or the
      token id, and the contract. One of a contract it does not show names
      the contract alone, as its row does, and says why: such a contract can
      log any transfer between any addresses, and an address copied from it
      could be a look-alike planted for the user. An ERC-20 one the wallet
      does not list has Add token, which opens 20 under the Accounts tab
      with the contract filled in and read; its claim is shown before it is
      added, as from Settings, and once added the detail says what moved.
      An ERC-721 one, a hidden token or a built-in one its contract
      contradicts has none (the wallet adds ERC-20 tokens, and lists the
      others already).
    - A Safe's event: what it names (the sender of ETH received, an owner,
      a module, a guard, a handler, the hashes), and the Safe.
    - A transfer whose row is cautioned about its sender says why beside
      its fields: the sender is none of the user's accounts and was never
      sent to from this device, and a look-alike is compared in full before
      anything is sent to it.
    - One no longer there (a reorganisation replaced its block, once every
      block from the history's start is checked again) says so; while the
      blocks it is in are still being checked, or its history cannot be
      read yet, it is loading (the strip says why the light client is not
      answering). A transfer of nothing is not detailed, as it is not listed.

### Settings tab

18. **Settings**: Display currency · Tokens (n built in, m added) · Security
    (wallet password, biometric unlock; 21) · Network (Sepolia, the only one
    for now) · Light client (status) · Debug build (the debug Android app
    only: *Allow screen recording*, rule 8 of docs/keys-and-signing.md) ·
    Advanced (*Detailed view*, off by default, docs/simple-ui.md; and under
    it, only while it is on, *Debug tab*, a switch in every build: 27;
    turning it off empties the log) · About. While Detailed view is off,
    the screens are simple mode's and the Debug tab is hidden, its switch
    kept. Tokens opens the list of every token the wallet knows, each with its check (*Tokens* below) and a visible ⋮ menu:
    Copy contract, Hide or Show, and Remove for an added one; Add token
    opens 20.
19. **Display currency**: USD, EUR, GBP, CHF, JPY, ETH and BTC, limited to the
    ones with a pinned feed on the network (*Prices* below): on Sepolia every
    one but CHF, which has no feed there. A stored choice the network does
    not offer reads as USD. It is a file of its own
    (`display-currency.json`), and values only: no amount is entered or
    signed in it.
20. **Add token by address**: the contract's address, then its symbol, name
    and decimals read from the contract and labelled as its claim, with the
    monogram icon it will get. The address only, never an ENS name (owner's
    decision, 2026-10-20, on #121): anyone can point a name such as
    `usdc-sepolia.eth` at a look-alike contract, and the name would lend it
    a standing the contract's own claims do not have.
21. **Security**
    - Wallet password: whether one is set, and how this device keeps the keys
      under it.
    - Biometric unlock, a switch: what it does, or why this device cannot offer
      it (no sensor, nothing enrolled, only weak biometrics, no secure hardware,
      no biometric path on this platform yet).
    - Turning it on asks for the wallet password here, under the screen
      protections of a screen that takes the password, and drops it when the
      screen is left. Turning it off asks nothing: it deletes a way in.
    - After a change of the enrolled biometrics it reads "Stopped working",
      with the switch still on: turning it off deletes the invalidated key,
      and turning it on again makes a new one.

### Debug tab

27. **Debug**, shown while Settings' *Debug tab* switch is on (a file of its
    own, `debug-screen.json`): a log of every request the app asks of the
    Myotis engine, newest first. At INFO, each request's `io.myotis.api`
    name, whether it was answered (ok) or not (failed), its time and how long
    it took; at DEBUG, also what it asked and what came back, cut at 2,000
    characters each. A filter keeps the lines that contain the text typed,
    and the trash icon empties the log. The log records only while the
    switch is on, and is kept in memory only, the last 2,000 records, never
    written to a file and never sent anywhere; it starts at INFO with each
    launch, and a lower level shows more from then on, never what went
    unrecorded before. What it holds is public chain data
    and the app's own requests (addresses, calls, signed transactions), never
    a key, a password or a recovery phrase. More logs are to join it under
    sources of their own.

## Prices

The ruling (CLAUDE.md, *Data sources*, 2026-10-16): Chainlink aggregators
read through the light client. Applied:

- **A feed table per network**: price id → feed address, the currency it
  quotes in, its decimals and its heartbeat, pinned in code from Chainlink's
  feed directory (`ChainlinkFeeds`). A token names its price id; a token
  without one has no price. The pools that price what no feed does are a
  table of their own (`UniswapPools`, below). The pins say what is expected; the contract is
  the source of truth. On Sepolia the table has ETH, BTC, LINK, USDC, EUR,
  GBP and JPY, each against USD; UNI has no Sepolia feed, so it has no price,
  and neither has a token the user adds.
- **Where no feed is pinned, a pool's average** prices a token (owner
  ruling, 2026-10-29, #205; .claude/rules/data-sources.md): a pinned Uniswap
  pool's 30-minute average, today for COW and SAFE on Ethereum. Its value
  reads "≈ $123.45 · Uniswap 30-min average", it counts in totals, and a
  total that includes one says "includes Uniswap averages" under it.
- **A price is a verified call** of `latestRoundData()` and `decimals()`,
  each at the verified head it is made at (two calls for now; one Multicall3
  call once its code hash is pinned, as for balances). The feed address is a
  proxy whose aggregator Chainlink can swap, and `decimals()` answers from the
  aggregator, so it is read every time: one that differs from the pinned
  value is no price, and the row that would show the price says why ("No
  price: its feed changed"). A non-positive answer is no price, and so is a
  round dated more than two minutes after the head. An `updatedAt` older than
  the feed's heartbeat and ten minutes' grace (a heartbeat update lands a
  little after the heartbeat), measured against the time of the verified
  head the read started at and never the device's clock, is stale: shown
  with its age and left out of totals, like no price.
- **Every price goes through USD.** A feed quoted in another currency is
  converted with that currency's own USD feed first (an X/ETH answer through
  ETH/USD). A display currency other than USD divides the USD value by its
  own USD rate (EUR/USD, GBP/USD, CHF/USD, JPY/USD; for ETH and BTC, ETH/USD
  and BTC/USD). A currency whose feed is not pinned on the network is not
  offered.
- **A converted price is only as good as its worst feed.** Every feed on the
  path (the token's, a conversion feed, the display currency's) passes the
  same checks; one that fails makes the price no price, and the oldest
  `updatedAt` on the path is the age shown and the one the stale rule
  applies to, totals included.
- **Totals add only priced holdings**, and say how many assets they left out.
  A held asset with no price, or a stale one, is left out and counted; a
  balance or price that cannot be verified right now is counted as "not
  verified"; one still being read makes the total incomplete ("still
  checking"). When the display currency's own rate has no price, that is
  said once ("No EUR rate"), not as a missing price on every asset. An
  amount of the display currency itself (ETH shown in ETH) is its own
  value, with no feed asked. A portfolio total adds the accounts valued in
  the currency chosen, so a change of currency never sums two of them, and
  counts what it leaves out per account: one unpriced token held in two
  accounts is two holdings left out.
- **On Sepolia every fiat value says "testnet prices"**: Chainlink's Sepolia
  feeds are testnet feeds, and Sepolia's tokens have no market value of
  their own. Each row's value carries it ("$3,000.00 · testnet prices"), and
  each total the note right under it.
- **The trust is in the oracle.** The light client proves what the aggregator
  contract says; it does not make the oracle right. That is the reason a
  price never becomes a signed amount.

## Home-screen widget

An Android home-screen widget with one exchange rate, a pair chosen when it
is placed (issue #178) in three steps:

1. **The network**, among the networks that are on and have accounts (every
   network that is on when none of those is).
2. **The price**: the network's native currency first, then each price its
   built-in tokens are priced by, each once and named with what it prices
   ("ETH — Ether · WETH", "USDC — USDC · USDC.e" on Gnosis Chain, "EUR —
   EURe"). Only a price with a feed pinned on that network, or a pool
   pinned for the fallback (*Prices*), is offered: a token with no price, an
   added one included, has no rate. A filter above the list narrows it as it
   is typed into (owner's request, 2026-10-29, #207): a price stays when its
   symbol or one of the names it prices holds the text, ignoring case ("usdc.e"
   keeps "USDC — USDC · USDC.e"); a greyed-out token matches by its symbol and
   stays greyed out. Nothing matched says "No price matches “…”". The filter
   reads only the list on screen, so it starts no light client, and it empties
   when another network is chosen.
3. **The currency**: the display currency first, which the widget then
   follows as the screens have it (screen 19; USD where that is the price
   itself, none is chosen, or the network does not offer the one stored),
   then USD and each display currency with a feed pinned on that network,
   but the price itself.

Choosing reads only the pinned tables, so it starts no light client. The
widget shows the watched asset's bundled icon in its top-left corner (its
network's own icon for a native currency, a built-in token's own otherwise;
none where none is bundled), then the pair — "LINK · EUR", "€12.50" — with
its change over the 24 hours before it was read, "24h +2.31%" in green,
"24h −1.05%" in red, "24h 0.00%" when it rounds to no move, and a line that
names its network and dates it: "Sepolia · Verified Oct 21, 2:05 PM · may
have changed since · testnet prices". The choice is kept; another pair is
another widget. One placed before pairs shows its network's native currency
in the display currency, and one placed before networks shows Sepolia's. A
widget of a network that is off keeps its last rate and says "Gnosis Chain
is off" in place of "may have changed since", and nothing reads it until
the network is on (docs/networks.md, *The widget*). A refresh reads only
the pairs the placed widgets show, each network waking alone. A tap on the
refresh button reads it now (below); a tap anywhere else opens the app.
The desktop has no home screen, and iOS's widget (a WidgetKit extension) is
still to come.

- **Only verified rates, dated.** The rate is the price the screens show
  (*Prices*, above), every check included, or, where no feed is pinned, a
  pool's 30-minute average (*Prices*, above); the change is the same path read
  from each feed's own round history, a day back by the head's own time:
  `getRoundData` calls of the feed's proxy at the verified head, never a
  call at a past block and never a rate the device kept, so both ends of the
  change are what the light client proved. A pool on the path is read a day
  back the same way, by its own observations (`observe` at the verified
  head). A day back with no verified, fresh round on the path (a stale or
  unproven feed, an aggregator that took over since), or a pool whose
  history does not reach that far, shows "24h —", never a change against
  something else. A pool-sourced rate adds "Uniswap 30-min average" to the
  line that names its network and dates it. The
  widget shows the last rate read, from a file of its own
  (`last-exchange-rate.json`: a price and its times per pair, public data
  only), with
  the date and time it was verified and marked as possibly changed since,
  as the one older answer CLAUDE.md's *Trust* allows is (a balance; a kept
  rate is not on that list, so this is for the owner to rule on); a stale
  one says how old its price was ("price 2 h old"), and none read yet says
  "No verified rate yet". It is a price like any other: shown, never
  signed.
- **Sparing the battery and metered data.** Drawing the widget never starts
  the light client, and the widget never wakes the device on a timer of its
  own (`updatePeriodMillis` 0). While the app is closed, a JobScheduler job
  refreshes it about every three hours, and only while a widget is placed,
  on an unmetered network and a battery that is not low, so the system
  batches it and holds it back in Doze and Data Saver; it wakes the engine
  for that read alone (none of the app's other background work), gives the
  light client four minutes, and lets it sleep again. Placing a widget asks
  for one such run at once. While the app is open, whose light client runs
  anyway, the rate is read again when it is half an hour old or the display
  currency changes: the rate now is the screens' own reading, and the day
  back is a handful of calls. The search for a day back's round starts at as
  many rounds back as heartbeats fit, so an hourly feed takes two calls, and
  the rounds read are kept in memory (a round, once written, never changes),
  so a search soon after reads few or none again.
- **Refresh now.** A small button beside the 24-hour change (the rate below
  keeps the whole width; on a narrow widget the pair gives way first, then
  the change, and the button stays) reads it at once, on any network, metered
  included, since the user asked for it: one job, expedited
  where Android offers that (12 and later) and its quota allows, waking the
  engine for that read alone as the background refresh does, and joining a
  background read already under way rather than making a second. A spinner
  stands in the button's place until it ends. Without a network that reaches
  the internet (a captive portal included) it says so and reads nothing; a
  refresh Android has not started within two minutes is given up and said
  so; a read that gets no verified rate says so too and leaves the last one
  shown. A second tap while one is on its way adds nothing.

## Tokens

The ruling (CLAUDE.md, *Data sources*, 2026-10-16): a built-in list, more by
address. Applied:

- **A built-in entry** is its address, symbol, name, decimals, icon key and
  price id, keyed by network. On Sepolia the list is the issuers' own test
  deployments, USDC and EURC (Circle), PYUSD (Paxos), LINK and UNI, with WETH9
  (the WETH of Uniswap's list and of the swaps), and the tokens of Aave v3's
  Sepolia market: Aave's own GHO and its faucet tokens DAI, USDT, WBTC and
  AAVE, which anyone can mint and so have no price (the owner's choices,
  2026-10-17 and 2026-10-22). WETH has ether's price, one ether being locked
  in WETH9 for each. Uniswap's own list names only UNI and WETH on Sepolia,
  and its pools there hold look-alikes of most other symbols, so the list is
  not taken from them. It is checked twice against the contract's
  `symbol()` and `decimals()`: by the PR that adds it, which shows the values
  read, and by the app with verified calls each time it runs. (That reading
  of the ruling's "when it is listed" as two checks is the owner's to
  confirm: issue #53.) An entry its
  contract contradicts, or whose contract does not answer as a token, is not
  shown, and Settings → Tokens says why. While the check has no verified
  answer yet the entry is shown: its balance is a verified call of its own.
- **Icons are bundled** as Compose `ImageVector`s, converted from SVGs at a
  pinned commit, with their provenance and licence stated (`TokenIcons`): the
  CC0 `cryptocurrency-icons` set, and for EURC, PYUSD and GHO, which it lacks,
  the MIT-licensed `web3icons` drawings of their issuers' logos (the owner's
  choice, 2026-10-22). WETH is the CC0 ether glyph on a colour of its own.
  Every built-in token has one. A token the user adds gets a monogram of its
  symbol. Nothing is fetched to draw a token.
- **A token the user adds** is read from its contract (`symbol()`, `name()`,
  `decimals()`, verified) and labelled as the contract's claim, with the block
  it was read at, cut on screen beyond 24 characters of symbol or 48 of name.
  One whose symbol is a built-in token's is marked as not that
  token, on the Add card, in Settings → Tokens and on every account row. Two
  added tokens sharing a symbol are not marked (Settings → Tokens shows each
  one's address); whether they should be is the owner's open question (issue
  #51). An
  address the wallet already knows, or that does not answer as a token,
  cannot be added, and nothing is added without a verified answer. The added
  tokens and the hidden ones are the user's settings, kept in a file of their
  own (`tokens.json`, beside the account list). While that file cannot be
  read, nothing can be changed and the account screens say that hidden tokens
  show and added ones are missing; a damaged one is set aside, and Settings →
  Tokens says the settings started over.
- **Hiding** a token takes it off every account's rows, from the asset menu
  (5) or Settings → Tokens, which shows it again. A built-in token can only be
  hidden; an added one can also be removed.
- **Balances are verified `balanceOf` calls**, one per token and account,
  each labelled with the block it ran against. A call that reverts, or returns
  anything but one word, is a verified "not a token", not a zero. The list has
  eleven entries on Sepolia and is still read a call at a time; batching the
  calls is the next step, through
  Multicall3's `aggregate3`. Its code hash on Sepolia is
  `0xd5c15df687b16f2ff992fc8d767b4216323184a2bbc6ee2f9c398c318e770891`,
  proven 2026-10-17, and is pinned before any batch trusts it.
- **Only known tokens show.** An account lists the built-in tokens it holds
  and the ones the user added, minus the hidden ones. Finding other tokens
  an address holds takes an indexer, which the *Trust* rules exclude.

## NFTs

The owner's request (2026-11-05, #246): "It should be possible to see any NFT
associated with an address. Maybe below the tokens. Also decode ENS NFTs so
they are helpful." The rulings (owner rulings, 2026-11-05, #246; the
*Data sources* rules) applied:

- **Found, then proven.** An NFT is found among the ERC-721 `Transfer`s and
  ERC-1155 `TransferSingle`/`TransferBatch`es to the account that the
  engine's log index holds (`ObserveNfts`). It is listed only once a verified
  call at the head says the account holds it: `ownerOf(id)` names it, or
  `balanceOf(account, id)` is above zero. One passed on or burned drops out
  at its next proof. An NFT is proven again when a log naming it and the
  account appears, when its last proof had no answer, and within the hour in
  any case: an ENS name changes holder at expiry with no log. At most 100 are
  checked, the built-in collections' first, then the most recently moved;
  the rest are counted, so a flood of airdrops can neither flood the light
  client with calls nor push the user's ENS names out.
- **Where its coverage starts.** The index covers the account from its
  history's start. The list says so ("NFTs received since block n"), and
  offers *Add an NFT*: a contract and a token id, kept once the account is
  proven to hold it.
- **On chain only.** A row shows the collection's name (its `name()`, the
  contract's claim, or the built-in name) and the token id; an ERC-1155
  amount above one shows as "×n", masked while amounts are hidden. The
  section says images are not shown: nothing is fetched from IPFS or HTTP,
  and an image from an on-chain `data:` URI is drawn in a later part.
- **Older answers.** Detailed view dates each row by the block it was
  proven held at. While the account's network is off, nothing is proven
  again, and the section says the NFTs were proven before and may have
  changed since. They are read only while the Assets tab is shown.
- **Below the assets** (screen 4, in both views): the built-in collections'
  rows, then "Unlisted NFTs (n)", collapsed until tapped, each of its rows
  saying "not confirmed" with its contract. A line under them says what the
  list covers ("Received since block 9,000 · 1 not proven held yet"; simple
  mode: "Received since this wallet began following this account"). While
  some are still to be proven and none is yet, it says "Checking NFTs…",
  never that there are none.
- **Built in, or unlisted.** The built-in collections (`KnownNftCollections`)
  are ENS's: the .eth registrar (ERC-721) and the NameWrapper (ERC-1155), on
  Ethereum and Sepolia. Their NFTs are shown openly. Any other collection's
  sit under a collapsed "Unlisted collections" row, marked unconfirmed:
  anyone can send an NFT to any address, and a name is only a contract's
  claim.
- **ENS names, decoded.** A NameWrapper token's id is the name's namehash,
  and the contract's own `names(id)` gives the name. A registrar token's id
  is the label's hash: the label comes from the controller's
  `NameRegistered` log, or from a name the user types, checked by hashing; one
  not found shows as `[hash].eth`. The row shows the name, its expiry and
  grace period, and whether it is wrapped; its detail adds what the name
  resolves to, forward-resolved as Send does. Nothing is read from ENS's
  metadata service.
- **No price.** An NFT never counts in a total.

## Safes

- **Detection** (issue #10) reads only verified state, each value with its
  block: the proxy's code hash must be a pinned SafeProxy runtime hash; slot
  0 (the singleton) must be a pinned canonical singleton for the network
  (1.0.0, 1.1.1, 1.2.0, 1.3.0, 1.4.1 or 1.5.0, L1 or L2 where there is one);
  the owners are the linked list in slot 2 walked from the sentinel `0x1`
  with storage proofs, the threshold is slot 4 and the nonce slot 5. The
  modules (slot 1's list), the guard and, from 1.5.0, the module guard are
  read too, because a module can move funds without the owners' signatures;
  a Safe older than 1.3.0 has neither guard, and is read as having none.
  Anything that does not match is "another contract", never a Safe. The
  pins (`SafeDeployments`) cover 1.0.0, 1.1.1 and 1.2.0 (one proxy shared by
  the last two) on Ethereum and Gnosis Chain, and, everywhere, 1.3.0, 1.4.1
  and 1.5.0, L1 and L2, and the SafeProxy runtime code of each factory: a
  pre-1.3.0 Safe whose owners moved it since to a pinned singleton
  (`changeMasterCopy`) is recognised by its own older proxy pointing at that
  pinned singleton, same as one still on its original singleton. Sepolia
  pins 1.1.1 and 1.2.0 too (their singletons carry live code there) but not
  1.0.0, whose Mainnet singleton predates Sepolia and has none there — a
  recognised Safe always names a singleton proven to hold real Safe code on
  that network, never merely an address this app expects one at. A Safe's
  control badge follows its verified owners: as many held here as its
  threshold is full control, fewer is co-signer, none is watch-only, and so
  is a Safe not read yet.
  - The numbered slots carry the block they were proven at; the guards sit
    at hashed slots no storage proof names, so they are verified reads at the
    head, which may be a head newer than that block.
  - The owner and module lists are what the Safe's own getters return.
    Storage written outside them (a setup delegatecall can plant a module the
    list does not link) is not something reading the lists can rule out; the
    co-signing flow's own checks against the chain stand for that.
  - The stored kind is what was verified when the account was added. An
    address watched while it was still counterfactual stays an account after
    its Safe is deployed; re-detecting it is a follow-up.
- **Creation** deploys Safe 1.4.1 through `SafeProxyFactory.createProxyWithNonce`
  with a `setup` call, from an EOA the user holds. The address is predicted
  locally and shown before the deployment is signed.
  - The release (`SafeReleases`, pinned in `SafeDeployments`): 1.4.1's L1
    singleton `0x41675C09…7461a` (Safe{Wallet}'s choice for Ethereum and its
    testnets) and SafeProxyFactory `0x4e1DCf7A…20ec67`, and the factory's
    proxy creation code, taken from `@safe-global/safe-contracts`
    1.4.1-build.0 and checked against the pinned proxy runtime hash and
    safe-deployments' factory code hash.
  - `setup`: the owners in their order and the threshold; no module (`to`
    zero, `data` empty); no fallback handler, since CompatibilityFallbackHandler
    is not pinned (so the Safe takes no ERC-721/1155 safe transfers and
    answers no EIP-1271 call until one is set); no payment.
  - The address: `keccak256(0xff ‖ factory ‖ keccak256(keccak256(initializer)
    ‖ saltNonce) ‖ keccak256(proxyCreationCode ‖ uint256(singleton)))[12:]`
    (`SafeCreation`), with vectors re-derived with Foundry's `cast` and the
    factory's own run on a local node. The factory's verified run on Sepolia
    must return it before anything is signed.
- **A hardware P-256 owner** signs through the passkey signer, under
  docs/keys-and-signing.md (*A Safe owned by a hardware P-256 key*).

## Co-signing a Safe transaction

The ruling (CLAUDE.md, *Data sources*, 2026-10-16): no off-chain service.
Applied:

- **A signing request is data, not a description**: a versioned compact
  JSON object with the chain id, the Safe, the `SafeTx` fields (`to`,
  `value`, `data`, `operation`, `safeTxGas`, `baseGas`, `gasPrice`,
  `gasToken`, `refundReceiver`, `nonce`) and the signatures collected so far.
  It carries no label, summary or hash for the receiver to trust. Version 1
  (`JsonSigningRequestCodec`):

  ```
  {"v":1,"chainId":11155111,"safe":"0x…","tx":{"to":"0x…","value":"0","data":"0x…",
   "operation":0,"safeTxGas":"0","baseGas":"0","gasPrice":"0","gasToken":"0x…",
   "refundReceiver":"0x…","nonce":"4"},"signatures":[{"owner":"0x…","signature":"0x…"}]}
  ```

  - One line, in this key order; addresses and bytes lower-case hex with
    `0x`; every uint256, the nonce included, a decimal string without a
    leading zero; each signature `r ‖ s ‖ v` (65 bytes, `v` 27 or 28, a low
    `s`) of the raw hash; one per owner, in ascending owner order. A high `s`
    and an `eth_sign`-style `v` of 31 or 32 are refused, stricter than the
    Safe contract; whether to accept them, for signers that make them, is the
    owner's open question (issue #60).
  - Decoded only when the text, less the whitespace around it, is exactly
    the encoding of what it decodes to: a second value of a key, an unknown
    field, a comment, another form of a number cannot ride along. Another
    `v` is "a newer version", another chain "not Sepolia"; anything else,
    and text over 140,000 characters or nested deeper than the format, is
    "not a signing request".
- **It travels as one QR code**, or as text through the platform's share
  sheet when it is too large for one. A USDC transfer with one signature is
  702 bytes, a version-21 QR code at error correction M; each further
  signature adds 202 (904 for a 2-of-3 Safe's second signer). Nothing else
  leaves the device: the queue of pending transactions and signatures is
  kept locally (`FileSafeTxQueue`, beside the account list), and a
  signature is queued only once it has passed the checks below.
- **The receiving device checks it against the chain** before showing it
  (`ImportSigningRequest`):
  the chain id is Sepolia; the Safe is detected as a Safe; the nonce is not
  below the Safe's verified nonce (how far above it may be is not bounded:
  issue #59); the `SafeTx` hash is computed here; every
  signature is a current owner's over that hash. An ECDSA signature must
  recover to the owner; a contract owner's, such as the passkey signer of a
  hardware P-256 key, must pass a verified EIP-1271 `isValidSignature` call
  to that owner (version 1 carries ECDSA signatures only; the passkey owner
  brings the next version). A request that fails
  any check is refused with the reason. What the screen shows (17) is
  decoded here from those fields.
- **`approveHash` is the on-chain alternative**: an owner account the user
  holds approves the hash in a transaction, which needs no transport between
  devices. It is quoted and sent as any transaction from that account is
  (Send's quote: nonce, fees, a verified run).
- **Execution** is `execTransaction` sent by any account the user holds,
  owner or not, which pays for it (owner ruling, 2026-10-18, #56), once
  signatures and approvals reach the threshold, followed like a send (9).
  The executing account, when it is an owner, confirms it by sending it; approvals on chain are read
  only when the signatures fall short. Its run is the whole execution — the
  Safe's checks, its guard, the call — as a verified call from that account,
  and one that would fail is not signed.

## Not part of this page

Still the owner's decisions (CLAUDE.md, docs/keys-and-signing.md and issue
#11):

- importing a raw private key (an EOA comes from a recovery phrase);
- the synced-passkey owner tier and hardware external signers (#42);
- which recovery modules come (none is pinned, so none is offered when a
  Safe is created, screen 14), and how: still open (issues #54, #72);
- any network beyond Ethereum, Gnosis Chain and Sepolia (owner ruling,
  2026-10-22; docs/networks.md).

Swapping tokens has a page of its own, docs/swaps.md (issue #69). The swap
screens there are numbered from 22.

dApp connections have a page of their own too, docs/walletconnect.md (issue
#22; owner rulings, 2026-11-03). Their screens there are numbered D1 to D8.

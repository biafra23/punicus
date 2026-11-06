# Lenient mode: prepare and sign while the light client syncs

Drawn 2026-11-06 at the owner's request, and reviewed on its PR (#258).
**Decided the same day: the owner accepted every proposal on this page**,
the twelve decisions and the six rule changes (owner rulings, 2026-11-06).
The rulings are in `.claude/rules/trust.md`, *Lenient mode*, and in the
places *The rules it changes* names; where this page and the rules under
`.claude/` disagree, the rules win and this page is fixed. docs/wallet-ux.md
and docs/simple-ui.md take over the screens as each part lands.

This page has no mockups yet. They come with the screens' part (*Delivery*).

## The request

The owner's words (2026-11-06), lightly edited: abbreviations written out
and typos fixed.

> Strict and lenient mode. Switch in Settings.
>
> The problem: at the moment sync blocks almost any action (except some
> navigation). I would like to allow the user to do almost everything while
> knowing they operate on possibly outdated data, with the risk that
> something fails later and needs to be redone. For example creating and
> sending a transaction could be handled like an email draft in an email
> program: create, sign, put in the outgoing queue. The user can start
> creating the transaction while the sync runs in the background. As soon
> as an intermediate result is known to conflict, tell the user. If this
> happens after the signature, ask again for the signature. This would be
> the behaviour of the lenient mode. Strict, the default, keeps working as
> right now.

## The one rule this design rests on: sign early, never send early

- **Lenient mode moves the signature earlier, never the sending.** Nothing
  leaves the device until it has been checked again at a fresh verified
  head: no broadcast, no CoW order, no Safe signature shown as a QR code or
  as text, no `approveHash`, no execution, no answer to a dApp.
- **What changes:** what a draft may be built on (the state the light client
  last verified, labelled with its age), and when the user may sign it
  (before the light client has caught up).
- **What does not change:** what the wallet trusts. Every figure a draft
  uses was verified by the light client, only earlier. The check before
  sending reads everything again at a fresh head. Peer-trusted is still never
  an option, and nothing comes from another source.
- Why: the risk of a draft built on old state stays on the device. The worst
  a wrong draft can do is fail its check, which costs the user a second
  signature, never funds.

## What blocks today

On `main` as of 2026-11-06, while a network's light client cannot serve
verified reads (starting, bootstrapping, catching up, waiting for a head or
for peers that prove state):

- **Send** opens. The network's currency shows "Syncing…", a token is offered
  only once its balance is verified as held, Max is off, and an ENS name is
  not resolved (it is looked up again every few seconds). Review is refused:
  "The light client is still syncing, so nothing can be quoted yet."
  (`SendViewModel`, `assetOptions` in `components/TransferInputs.kt`).
- **Every transaction this wallet signs** is quoted through `CallQuoter`
  (`domain/…/usecase/Calls.kt`), which refuses a balance last verified
  (`BalanceReading.LastKnown`) as `CallRefusal.Syncing`. So do the Safe
  transaction's checks (`SafeTxRefusal.Syncing`, `SafeTxSigning.kt`), swaps,
  wraps and CoW orders (`Swaps.kt`, `Wraps.kt`, `CowSwaps.kt`).
- The rules behind it: a balance proved earlier is "never acted on" (owner's
  request, 2026-10-19), and "a quote, a review and a signature always read
  fresh state, in either view" (owner's decisions, 2026-10-26), both in
  `.claude/rules/trust.md`. docs/wallet-ux.md applies them: "Send's asset
  choice, a quote and a signature treat it as syncing", and screen 8's "the
  quote is never signed stale".

Strict mode keeps all of this, unchanged.

## The setting

- **Settings → Advanced → *Sign while syncing***, a switch. Off is strict
  mode, on is lenient mode. Its line: "Prepare and sign transactions before
  the light client has caught up. They wait to be sent until they check out
  on chain. If something changed, you're asked to sign again."
- **Off by default in every build** (the owner's "strict, the default").
- One switch for the app, not one per network (decision 1).
- It is independent of the view: simple mode and Detailed view both have it.
- Turning it off leaves what is already queued in place: those transactions
  are still checked and sent, or discarded by the user. Only new ones follow
  strict mode (decision 10).
- Built as the view setting is (docs/simple-ui.md, *Two views, one set of
  ViewModels*): a domain port `SyncPolicySetting` (`observe(): Flow<SyncPolicy>`,
  `set(policy)`), `SyncPolicy { STRICT, LENIENT }`, the use cases
  `ObserveSyncPolicy` and `SetSyncPolicy`, and its adapter `FileSyncPolicy`
  in `sync-policy.json` with the app's other files. A file that is missing
  or damaged reads as STRICT.

## What lenient mode changes, action by action

| Action | Strict (today) | Lenient |
|---|---|---|
| Send from an account whose key is here, the network's currency or a token | Review refused while syncing | Reviewed on the state last verified, signed, and queued (*The outbox*) |
| A Safe transaction: proposing one, co-signing one | Refused while syncing | Signed and held until checked; the QR code and text wait for the check (*Safe transactions*) |
| A Safe transaction: approving on chain, executing | Refused while syncing | Queued like a send |
| Swap on Uniswap or CoW | Refused while syncing | Unchanged: strict in both modes (*What stays strict*) |
| Wrap and unwrap | Refused while syncing | Unchanged, for now (*What stays strict*) |
| A dApp's request over WalletConnect | Refused while syncing | Unchanged: strict in both modes |
| Create Safe | Refused while syncing | Unchanged, for now |
| Send from another wallet | Signs nothing here | Unchanged |
| Watch address, Add token | Wait for a verified read | Unchanged: not part of this plan |

Every action the table leaves strict is refused while syncing, as today,
and says why. In lenient mode it also says that the setting does not apply
to it. Where lenient mode greys a step out, a tap on it says what it waits
for (owner ruling, 2026-10-18, #55).

A network that is off is not syncing: it runs no light client, and its
accounts' actions stay greyed out with "Turn on" as the way to get them
(owner ruling, 2026-10-22), in either mode. Nothing is drafted on it, even
where inputs were kept for it before it was turned off.

## What a draft is built on

A draft is built on the state the light client verified last. Each figure
has its age, and the review shows it.

- **Balances.** The native and token balances last verified, which the app
  already keeps across restarts (`LastBalancesFile`). The asset list offers a
  token whose balance was last verified as held, labelled "as of 12 min
  ago". Max on a token takes that balance.
- **Max on the network's currency differs from strict Max.** Strict Max is
  the whole balance, and the quote then says the fee does not fit
  (docs/wallet-ux.md, screen 7). A draft has no quote to say it, so lenient
  Max leaves room for the most the fee can be: the gas ceiling below times
  the fee cap. It also leaves room for the account's transactions not mined
  yet. A Max draft then passes the funds check of *The check before
  sending*.
- **The nonce.** Not kept today. New: the account's verified count of mined
  transactions, with its block, kept across restarts. The nonce signed is
  the one `CallQuoter` takes today: past that count and past the account's
  own transactions not mined yet (`SentTransfers`).
- **The fees.** Not kept today. New: the network's last verified `FeeQuote`,
  with its block. The fee cap is the one a strict quote signs:
  `FeeQuote.maxFeePerGas` (twice the next base fee plus the level's tip).
  Only what the block charges is paid.
- **The gas limit.** An estimate needs fresh state, so a draft signs a
  pinned ceiling for its kind of call: one for the network's currency, one
  for a token's `transfer`. They are high enough for a Safe or another
  contract as recipient. The PR that codes them pins the values, each
  measured on its network. Gas not used is not paid. The ceiling shows only
  in the most the fee can be.
- **No simulation.** A verified call needs a fresh head. The review says the
  transfer runs on chain before it is sent.
- **No ENS names.** A name is resolved against finalized state, and no
  resolution is kept. A draft takes an address, or one of the user's
  accounts, and a name typed waits for the light client, as today
  (decision 8).
- **With nothing kept, the draft waits.** An account with no nonce verified
  on this device, or a network with no fee quote, cannot be signed for: the
  review is refused as today, and says what it waits for.

## The draft's review

The same review screen (8 in Detailed view, S2 in simple mode), with these
differences:

- A line at the top: "Prepared on data from 12 min ago. It's checked on
  chain again before it's sent." Each figure shows its age, in simple mode,
  or its block, in Detailed view.
- In place of the simulation, or simple mode's "Checked on chain: this
  transfer should go through": "Not checked yet: it runs on chain before
  it's sent."
- The balance after says "if nothing changed".
- No fiat values, here or in Send's form (its fiat hint beside the
  amount). A price is never shown last known (docs/wallet-ux.md, *Rules
  every screen follows*; owner's request, 2026-10-19), and none is fresh
  while the light client catches up. The amounts and the fee are in token
  units, as every signed amount is.
- The button says "Sign and queue", in place of "Confirm and send".
- Screen 8's minute rule applies to the check, not to the signature. A
  strict quote is never signed more than a minute old, and the biometric
  prompt's deadline is that minute (`Unlock.WithBiometrics.notAfter`). A
  draft is old by definition, and the check before sending is fresh, so
  the prompt has no deadline.

## The outbox

- **One queued transaction per account** (decision 6). While one is queued,
  nothing new is quoted from that account, in either mode, as with a
  transaction maybe sent today (`CallRefusal.EarlierMaybeSent`): "A
  transaction from this account is waiting to be sent. Send or discard it
  first." Why: two queued transactions take consecutive nonces, and a
  conflict on the first breaks the second as well.
- **Where it shows.** The transfer's status screen (9 in Detailed view, S3 in
  simple mode) gets a step after Signed, before the broadcast: "Queued:
  waits for Ethereum to catch up". The account's Activity lists it first. Home says "1 waiting to
  send" under the network's sync line.
- **What the user can do with it:** Discard (its signature is deleted;
  nothing was sent), and Edit (discard, and open the form filled with its
  inputs).
- **Discard and Edit wait while the check runs.** A check and its broadcast
  are one step that a Discard cannot cut in half, as a strict send's
  broadcast and record are today (`SendTransfer`). While they run, Discard
  and Edit are greyed out: "It's being checked and sent." A tap that comes
  after a peer took the transaction finds it sent, and says so. It is never
  told as discarded.
- **Sent by itself once it checks out** (decision 3). When the network's
  light client verifies at a fresh head, the outbox runs the check below. A
  transaction that passes is broadcast as a strict send is, and recorded in
  `SentTransfers`. From there the status, the "maybe sent" retry
  (`RebroadcastTransfer`) and the receipts are today's.
- **Not after an hour** (decision 4). A queued transaction that has not been
  sent within an hour of its signature is not sent by itself. It waits for
  the user: "Still send this? You signed it 2 h ago." Send now runs the check
  and sends it when it passes, with no new signature; Discard deletes it.
  Why: a payment the user wanted an hour ago may be one they no longer want,
  and the wallet cannot tell.
- **While the app runs** (decision 7). The check needs a verified head, and
  the light client's networking sleeps while the app is in the background
  (`LightClientStatus.Sleeping`). On Android it is kept on for a grace period
  after the last screen leaves (`GracePeriodJob`), and the outbox runs during
  it too. After that a queued transaction waits until the app is back in the
  foreground. The status step and Home say so: "Waits for Ethereum to catch
  up. Keep Serotine open to send it sooner." The hosts run it as they run
  `FollowSafeDeployments`: a use case `FollowOutbox`, never from `App` or
  `AppGraph`. A send and a conflict are each told in a notification, through
  a new port `OutboxNotifier`, implemented in the hosts as `UpgradeNotifier`
  is.

## The check before sending

The check makes the strict quote again, fresh, from the draft's inputs (the
account, the recipient, the asset, the amount, the fee level), and compares
it with what was signed. It is `QuoteTransfer` and `CallQuoter` as they are,
plus the comparison, with one difference: the funds row replaces the quote's
own test of the fee. A peer tests the signed figures, not the fresh ones.

| The check | It passes when | Otherwise the user is told |
|---|---|---|
| The quote | It is made, not refused: enough of the asset, the call does not revert, nothing earlier maybe sent | The refusal, in the words strict mode uses for it |
| The nonce | The fresh nonce is the one signed | "Another transaction from this account came first." A higher nonce means one used it; a lower one means a gap the signed one would wait behind |
| The gas | The fresh estimate is within the signed limit | "It needs more gas than it was signed for." |
| The fee | The signed fee cap covers the next block's verified base fee, raised by the headroom of decision 12, plus the signed tip | "Fees rose since you signed: the most it can cost is now …" |
| The funds | The fresh balance of the network's currency covers the signed transaction's upfront cost (its value plus its gas limit times its fee cap) and the most the account's transactions not mined yet can cost | "Not enough ETH for the most this can cost." (xDAI on Gnosis Chain) |

- **Why the funds row.** A peer admits a transaction only when the balance
  covers the upfront cost of the signed bytes. The quote's own test uses the
  fresh estimate and the fresh fee cap, and either way it tests the wrong
  figures. The draft signed the gas ceiling and the older cap, which can
  cost more: the quote's test would pass a transaction every peer refuses.
  The fresh cap can be the higher one when the base fee rose within the
  headroom: the quote's test would then refuse a transaction the signed
  bytes cover, and ask for a signature for nothing. A token transfer has
  the same need in the network's currency: its value is zero, its fee is
  not.
- **Not a conflict:** a balance that changed but still covers it, lower
  fees, a newer head. The transaction is sent.
- **An earlier transaction maybe sent** (no peer answered its broadcast)
  holds the queued one back, as it holds a strict quote back today. The
  queued one waits until the earlier one is mined or three hours old
  (docs/wallet-ux.md, screen 7). Its hour runs meanwhile (decision 4).
- **A broadcast the network rejects** is a conflict too. It is not
  recorded, as in strict mode (`signAndSend`), so it holds no nonce; the
  user is told the network's words and asked to sign again. One that no
  peer answered may have been taken all the same: it is recorded as maybe
  sent, as in strict mode, and its status offers to try it again.
- **A conflict never fixes itself.** Every field is in the signature, so the
  user is asked to sign again. Sign again opens the review with the fresh
  quote, read fresh as in strict mode now that the light client verifies.
  Discard deletes it.
- **The old signature is deleted when the conflict is found.** Except after
  a rejected broadcast, it was never broadcast, so deleting the one copy is
  what keeps it from being sent. That holds only while the copy never left
  the device (decision 5). A rejected one was seen by the peer that rejected
  it; what retires it is the new signature at the same nonce being mined, as
  after a rejected broadcast in strict mode (`signAndSend`). A nonce gap
  is the case that matters: the signed transaction becomes valid again once
  the gap fills, and a copy kept elsewhere would then make a payment the
  user chose not to make.

## The signed bytes kept

- A queued transaction holds at most one signed transaction. Signing again
  replaces it. Discard, a conflict and a send's end delete it.
- **They are not public data.** `SentTransfers` keeps signed bytes as public
  data because they are public once broadcast. Queued bytes are not: whoever
  has them can broadcast them. They are not key material either: they sign
  nothing else, and they move only what the user signed, once.
- **Not the session secrets' profile.** WalletConnect's session secrets are
  kept in an app-private file with no seal because they cannot move funds
  (`.claude/rules/trust.md`, *What a dApp sends is untrusted input*; owner
  rulings, 2026-11-03, #22). Queued bytes can, so a place alone is not
  enough.
- Decided (decision 5): kept across restarts in an app-private file of its
  own, `outbox.json`, **sealed with a hardware key of its own**, out of
  cloud backup and device-to-device transfer, never logged, never in a
  UiState, and deleted with the queued transaction. The key is made as the
  envelope's hardware factor is (`HardwareSecret`): in Android's Keystore,
  or in the Secure Enclave of an iPhone or a Mac. It needs no password and
  no biometric prompt, so the outbox still sends by itself. A copied file is
  then useless off the device.
- **What the seal does not stop.** Code running inside the app can open the
  file, as it could send the transaction itself. On a Mac, so can any
  program running as the same user: the enclave's key is bound to the Mac,
  not to the app (docs/keys-and-signing.md, *A Mac with a Secure Enclave*).
- **Where there is no seal, memory only.** A device with no hardware key to
  seal with keeps the queued bytes in memory only: an Android device whose
  Keystore is software only, Windows and Linux for now, a TPM present or not
  (owner ruling, 2026-10-16), a Mac without a Secure Enclave, and the iOS
  simulator.
- **A seal key lost loses the queued transaction.** When the key store no
  longer has the key (it was reset, or the app was restored on another
  device), the file cannot be opened. The queued transaction is said as
  lost, and the user is asked to sign again. A lost hardware key is said,
  not guessed around (docs/keys-and-signing.md).
- The other choices: memory only everywhere, so a restart asks for the
  signature again, which on a phone that stops apps in the background is
  often; or the file with no seal, which is simpler and leaves a copied file
  sendable.

## Safe transactions in lenient mode

This is the plan's last part. It needs rule change 4 for the Safe's own
values, below.

- **Drafted on the Safe last verified.** The Safe's nonce, owners and
  threshold, last verified and kept, labelled with their age. Today no
  other value than a balance is shown last known (docs/wallet-ux.md, *Rules
  every screen follows*; owner's request, 2026-10-19), so rule change 4
  covers them.
- **The refusals apply when drafting, unchanged.** Setting a fallback
  handler other than the pinned one or none (owner ruling, 2026-10-18,
  #63), enabling a module (owner ruling, 2026-10-18, on #54), and a call of
  the Safe itself that is not decoded exactly are decided by the
  transaction itself, and are refused before anything is signed.
- **The signature is held, not queued.** `SafeTxQueue` saves only what was
  checked against the chain (its KDoc), so a lenient signature of a
  `SafeTx` waits in the outbox until checked. The QR code and the text
  wait with it.
- **The check:** the Safe verified as a Safe; the `SafeTx` nonce not used
  yet; the signer a current owner. A request passes into `SafeTxQueue`, and
  its QR code and text are offered.
- **Conflicts:** a nonce used by another Safe transaction changes the
  `SafeTx` hash, so the owner signs again. A signer that is no longer an
  owner cannot sign it at all, so the request is discarded and the user
  told.
- **A held signature is not a queued transaction.** It does not take the
  owner account's place in the outbox, since it spends no nonce of that
  account: the owner may send from it meanwhile. One held signature per
  Safe and `SafeTx` nonce, so a second proposal for the same nonce waits
  for the first to be checked or discarded.
- **Approving on chain and executing** are transactions from an account
  whose key is here, and go through the outbox like a send.

## What stays strict, and why

- **Swaps, on Uniswap and on CoW.** A minimum from an old quote is
  either too high (the swap reverts) or too low (the difference is there for
  anyone to take). So the check would have to treat any move beyond the
  tolerance as a conflict, and nearly every swap would be signed twice. On
  CoW the signed minimum must be at least the verified Uniswap quote less
  the gas and the tolerance (owner ruling, 2026-10-22), which an old quote
  cannot show. While syncing, the swap's review is greyed out: "A swap's
  price needs a fresh check, so it waits for the light client, also with
  Sign while syncing on."
- **Wraps and unwraps**, for now. They have no price and no minimum: a
  wrap is a 1:1 deposit into the pinned wrapped-currency contract. So they
  could be queued like a send, with the same check. They are left out only
  to keep the first version small, and could join Send's part.
- **A dApp's requests.** A dApp waits for the transaction's hash, and a
  request that cannot be met before it expires gets an error (`.claude/rules/trust.md`,
  *What a dApp sends is untrusted input*). A queued answer comes after the
  dApp gave up.
- **Create Safe**, for now. Its deployment is a transaction like a send and
  could follow. It is left out to keep the first version small.
- **Send from another wallet** signs nothing here and is unchanged.
- **Watch address and Add token** sign nothing. A read that waits for the
  light client is a separate question from this plan.

## The rules it changes

The owner's to change (`.claude/rules/instructions.md`). The owner accepted
each as proposed (owner rulings, 2026-11-06), and part 1 records them:

1. `.claude/rules/trust.md`, *One older answer is shown, labelled as one*
   (owner's request, 2026-10-19): a balance proved earlier is "never acted
   on". Proposed: "never acted on, except that in lenient mode a draft may
   be built and signed on it, labelled, and is sent only after the check at
   a fresh head". The same words in `.claude/rules/ui.md`, the `ux` skill
   and docs/wallet-ux.md (*Rules every screen follows*: "Send's asset
   choice, a quote and a signature treat it as syncing").
2. `.claude/rules/trust.md`, *In simple mode a value may be read a while
   ago* (owner's decisions, 2026-10-26): "A quote, a review and a signature
   always read fresh state, in either view." Proposed: "In strict mode, a
   quote, a review and a signature always read fresh state, in either view.
   In lenient mode a review and a signature may read the state last
   verified, labelled, and the check before sending always reads fresh
   state." The same in docs/simple-ui.md, *What stays the same*.
3. docs/wallet-ux.md, screen 8: "The quote is never signed stale", with its
   minute and the biometric prompt's deadline. Proposed: strict mode only;
   in lenient mode the minute applies to the check.
4. docs/wallet-ux.md, *Rules every screen follows* (owner's request,
   2026-10-19): "no other value (an address's kind, a Safe's owners, a
   price) is shown last known".
   Proposed: in a lenient draft, labelled, the values the draft is built on
   may be shown last known, and are checked before anything is sent or shown
   to another owner. For a send (part 4) these are the account's nonce and
   the network's fee quote; for a Safe transaction (part 5) also the Safe's
   nonce, owners and threshold. A price stays never shown last known, so the
   draft's review has no fiat values (*The draft's review*).
5. New: a queued transaction's signed bytes are kept as decision 5 says.
   docs/keys-and-signing.md, the contract for how keys are held, records the
   outbox's seal key beside the envelope's hardware keys: what it seals, that
   it needs no authorization, and that a lost one is said.
6. The code that states these rules in its KDoc follows them in the part
   that changes them: `LastBalancesFile` ("Shown only, never acted on: a
   quote and a signature read a fresh proof"), `CallQuoter` and the quote
   use cases that refuse a balance last verified as syncing, and
   `ProofSchedule` and `ProvenReads`, which quote the fresh-state sentence.

## Decisions for the owner

Each with the answer this page proposed, which the owner accepted (owner
rulings, 2026-11-06):

1. **One switch, or one per network?** One switch for the app.
2. **The default?** Strict, in every build (the owner's request).
3. **Sent by itself once it checks out?** Yes, within the hour of
   decision 4. The email outbox works this way.
4. **How long may a queued transaction wait before it needs the user's
   tap?** An hour from its signature, as simple mode's hour.
5. **The signed bytes across a restart?** Kept, sealed with a hardware key
   of their own, as *The signed bytes kept* says; in memory only on a
   device without a hardware key store Serotine uses. Not the file with no
   seal: whoever copies it could send what it holds. After a nonce gap, the
   user signs again at the lower nonce, and a leaked copy of the old
   signature would then make the payment a second time.
6. **How many queued transactions per account?** One, for now.
7. **When does the outbox run?** While the app runs and during Android's
   grace period after it, with a notification for a send and for a
   conflict. Waking the light client for it in the background, with a job
   as the widget's refresh does (`RateRefreshJob`), can follow.
8. **ENS names in a draft?** Not until the light client can resolve them.
   An address or one of the user's own accounts instead.
9. **What stays strict?** Swaps, wraps, dApp requests and Create Safe, as
   *What stays strict* says.
10. **Turning the setting off with transactions queued?** They stay queued,
    are checked and sent, or are discarded by the user.
11. **The words.** "Sign while syncing" on the switch, rather than "strict"
    and "lenient", which this page uses for the two behaviours.
12. **How much fee headroom must the signed cap still leave at the check?**
    Two blocks' largest rise of the base fee: the signed cap covers 1.125²
    (about 1.27) times the next base fee, plus the signed tip. Less is a
    conflict. A strict quote signs twice the base fee, about six blocks'
    rise. With no headroom, a transaction that only just covers the next
    block can sit unmined when the base fee rises, and the account's next
    transaction waits behind it. Strict's full headroom would make the check
    a conflict whenever the base fee rose at all since signing.

## Delivery: a multi-PR plan through `feature/lenient-mode`

`main` stays releasable at every commit (owner ruling, 2026-09-30), so the
parts land on `feature/lenient-mode` and reach `main` together. Each part
follows the five steps of docs/architecture.md's checklist.

1. **The rulings.** The rule changes above, recorded where they apply, and
   this page's fixes from its review. Documents only.
2. **The kept inputs.** The account's verified count of mined transactions
   and the network's fee quote, kept across restarts. No visible change.
3. **The outbox, in the domain and in `:data`, and run by the hosts.** The
   port `Outbox`, its sealed file adapter with each platform's seal key, and
   the memory-only adapter where there is none. The use cases:
   `DraftTransfer` (the draft), `QueueTransfer` (the draft, signed),
   `CheckQueued` (the check), `FollowOutbox` (run by the hosts, with each
   host's `OutboxNotifier`), `DiscardQueued` and `ConfirmQueued` (the user's
   tap past the hour), which share one `OutboxLock`. Every use case that
   quotes from an account takes the outbox, so nothing new is quoted from
   one with something queued. Nothing can be queued yet: the outbox stays
   empty until part 4. Tests with fake ports, the conflicts of *The check
   before sending* each among them.
4. **Send in lenient mode.** The setting, the draft's review in both views,
   the queued step on the status screen, Activity and Home, Sign again and
   Discard. The mockups and
   docs/wallet-ux.md's screens come first in this part.
5. **Safe transactions in lenient mode**, on the rule changes part 1
   records, rule change 4's Safe values among them. The port `KeptSafes`
   and its file, `kept-safes.json`, which the address kinds' adapter keeps
   each Safe it proves in, as part 2 keeps the mined counts. The use cases:
   `DraftSafeTx` (the proposal's draft), `HoldSafeSignature` (a proposal or
   a co-signature signed and held, in the outbox's sealed file beside what
   is queued, one per Safe and nonce), `CheckHeldSignature` (its check, run
   by `FollowOutbox`, which saves what passes to the Safe's queue and tells
   the host's `OutboxNotifier`), `DiscardHeld`; `DraftSafeCall` and
   `QueueSafeCall` (an approval on chain or an execution, queued like a
   send), which `CheckQueued` checks with the strict quote made again. A
   swap, and the token approval it needs, stay strict, as Swap does. The
   screens: Send from a Safe's draft (16), the held signature and the drafts
   of 17, the queued approval or execution on 9, in both views.

The final PR, `feature/lenient-mode` → `main`, links every part.

---
description: "Data sources. The embedded engine is the only source of chain data; the three networks; Chainlink prices; the built-in token list; Safe co-signing without a service; history from the log index; swaps on Uniswap and CoW; CoW's order API; dApp connections over WalletConnect."
paths:
  - "domain/**/*"
  - "data/**/*"
  - "ui/**/*"
  - "android-app/**/*"
  - "desktop-app/**/*"
  - "ios-app/**/*"
  - "*.kts"
  - "docs/**/*"
---

# Data sources

Loaded for every code module and for `docs/`. docs/wallet-ux.md applies these
rulings to the screens.

## The engine is the only source of chain data

The embedded Myotis engine (devp2p/libp2p inside it). Calling a node over
HTTP may be used for debugging only. It is never a production path (the
Myotis rule, carried over).

## Networks: Ethereum and Gnosis Chain beside Sepolia, one chain per account

(Owner ruling, 2026-10-22, accepting docs/networks.md. It replaces "Sepolia
only" of 2026-10-15.)

- The networks are the values of `eth.serotine.domain.model.Network`:
  Ethereum (chain id 1), Gnosis Chain (100) and Sepolia (11155111). The parts
  of `feature/networks` brought them (docs/networks.md, *Delivery*).
- **There is no current network.** Each account belongs to one chain and is
  named by its network and address (`AccountId`). Every chain port takes the
  network with each call. A network no adapter serves is refused, never
  answered from another one. A screen takes its network from the account it
  shows.
- The same EOA on two networks is two accounts that share one key. The key
  is deleted only with the last account at that address held here.
- **Each network can be turned off** (Settings → Networks). A network that is
  off runs no light client. Its accounts stay listed, their actions greyed
  out with "Turn on" as the way to get them.
- Defaults: a release build starts with Ethereum and Gnosis Chain on and
  Sepolia off; a debug build with Sepolia alone. Each network gets its
  build's default the first time a start finds it unrecorded. The user's
  choice stands from then on.
- **Sepolia keeps every testnet warning** and is never added to a total with
  real funds.
- The plan's open questions are decided as docs/networks.md proposes (the
  same ruling):
  - no names on Gnosis Chain, which watches and sends to addresses only;
  - xDAI priced by Chainlink's xDAI/USD feed on Gnosis, or DAI/USD read on
    Gnosis where there is none;
  - the update screen only when every network with accounts is blocked; a
    network blocked alone turns its own section red;
  - the display currencies: any network with accounts can price, said per
    section where one cannot;
  - SafeL2 1.4.1 created on Gnosis Chain, the L1 singleton on Ethereum and
    Sepolia;
  - Swap greyed out on Gnosis Chain until a venue is pinned there;
  - the network step starts with nothing ticked;
  - Sepolia off after an update from the Sepolia-only release;
  - Receive kept on a network that is off, saying arrivals show once it is
    on;
  - no extra real-funds notice beyond the network step's "Real funds" line;
  - the built-in token lists of Ethereum and Gnosis Chain from the plan's
    candidates, each entry proven on its chain by the PR that pins it.
- Adding a network beyond these three is the owner's decision and is recorded
  here first.

## Prices come from Chainlink aggregators, read through the light client

(Owner ruling, 2026-10-16.)

- A price is a verified `eth_call` of a pinned aggregator's
  `latestRoundData()` and `decimals()`: on-chain state like any other. There
  is no HTTP price API.
- On Sepolia these are Chainlink's Sepolia feeds, and every fiat value says
  "testnet prices".
- A token without a feed shows "no price" and is left out of every total,
  unless the pool fallback below prices it.
- **Prices are display-only.** An amount is entered, reviewed and signed in
  token units, never derived from a price. The light client proves what the
  aggregator says, not that the oracles are right. That is why a price never
  reaches a signature.
- One exception: the fallback to a pool's average, below (owner ruling,
  2026-10-29, #205). It excepts Chainlink-only prices, not display-only.

## A Uniswap pool's average prices a token where no feed is pinned

(Owner ruling, 2026-10-29, #205. It narrows *Prices come from Chainlink
aggregators*: on the screens, in the totals and on the home-screen widget,
labelled and counted in totals, over a 30-minute window, and never in place
of a stale feed.)

- A pinned Chainlink feed always wins. Where a price id has one on the
  network, it prices the token everywhere. A feed that is stale is shown as
  stale. It is never replaced by a pool's average.
- Only where the network pins no feed for a price id, a Uniswap v3
  time-weighted average price (TWAP) of a pinned pool prices it. Today that
  is Ethereum, for COW and SAFE. Gnosis Chain pins no Uniswap, and Sepolia has
  no market.
- The average is a verified call: `observe` of a pinned pool. The pool is a
  pool only when the pinned factory's `getPool(tokenA, tokenB, fee)` returns
  it in a verified call, as for swaps (docs/swaps.md, *Pins*). There is no
  HTTP price API.
- It is quoted in WETH. USD is the pool average times Chainlink's ETH/USD.
  Another display currency divides by that currency's pinned USD feed. Those
  feeds are read with every check a feed-priced value has: a stale feed on
  the path makes the value stale, said as for any feed, and an unproven one
  makes it no price.
- The window is 30 minutes. The reading is no price when any of these holds:
  - the pool holds fewer than two observations, or its oldest is younger
    than the window. An idle pool's `observe` extrapolates its last tick, so
    without this check its "average" is just its spot price;
  - its liquidity is below a pinned floor;
  - the average and the pool's current tick differ by more than a pinned
    band.
- Every value priced this way says so: "Uniswap 30-min average" beside an
  asset's value, and on the widget's line that names its network and dates
  it.
- **It counts in totals.** A total that includes one says so under it
  ("includes Uniswap averages"), as a Sepolia total says "testnet prices".
- The widget's 24-hour change is the whole path a day back, as for a
  feed-priced rate (docs/wallet-ux.md, *Only verified rates, dated*): the
  pool's average a day back (`observe` at the verified head), and each feed
  on the path from its own round history. It is "24h —" when the pool's
  history does not reach a day back, or when a feed on the path has no
  verified, fresh round there.
- The pools, their liquidity floor and the divergence band are pinned by
  the PR that codes this, each proven on chain.
- One price id shows one source at a time. A feed pinned later replaces the
  average with no prompt.
- The average is display-only, like every price: it never reaches a
  signature, and a swap's amounts come from its verified quote, never from
  it.
- Why: Chainlink has no feed for these tokens on Ethereum. A pool is one
  venue and can be moved at a cost, so it is the fallback and is labelled as
  one. The light client proves what the pool says, not that its price is
  right.

## The token list is built in

(Owner ruling, 2026-10-16.)

- A pinned list of popular tokens per `Network`: address, symbol, name,
  decimals and a bundled icon. Every entry is checked against the contract's
  own `symbol()` and `decimals()` when it is listed.
- The user adds more by address in Settings. Their metadata is read from the
  contract and shown as the contract's claim, with a monogram icon.
- No remote token list and no icon CDN: one would learn which tokens the user
  holds.

## Safe co-signing uses no off-chain service

(Owner ruling, 2026-10-16.)

- A signing request moves between devices as a QR code or as text. It carries
  the raw `SafeTx` fields and the signatures collected so far, never a
  description of them.
- The co-signer decodes it itself and checks it against the chain: the Safe
  (verified as a Safe), its nonce and owners, and the `SafeTx` hash
  recomputed locally. A signer that is not a current owner is refused.
- An owner may approve on chain with `approveHash` instead. Once the
  threshold is met, any owner executes it.
- Any account whose key is on this device may send that execution and pay
  for it, owner or not. An owner that executes counts as its own
  confirmation (owner ruling, 2026-10-18, #56).
- The Safe Transaction Service stays unwired.

## An account's history comes from the engine's verified log index

(Owner rulings, 2026-10-20, #85; docs/embedding-myotis.md, *History*.)

- The history is a Safe's own events and every log naming an account as a
  topic, walked back over verified headers.
- An account not created here is walked from the block it was added at,
  never further back ("since added"), and the history says so.
- The walk fetches receipts only for the blocks whose bloom flags an account.
  That tells the serving peer which blocks matter to this device. Accepted
  as is: the log index makes that trade-off for any watch-list.

## Swaps: Uniswap first, then CoW Protocol; the user chooses the venue

(Owner's directions, 2026-10-18 and 2026-10-22; docs/swaps.md, issues #69
and #144.)

- The user decides which venue each swap uses. Uniswap stays available when
  CoW is not.
- Uniswap's contracts are chain data like any other: pinned, read with
  verified calls, and sent to like any transaction.

## CoW Protocol's order API is an off-chain service, used only for a swap the user sends to CoW

(Owner ruling, 2026-10-22.)

- CoW learns the swaps the user sends to it and no others: the account's
  address, the pair, the amounts, the device's IP address and, for a signed
  order, the order. With Uniswap chosen, CoW is never contacted.
- The app data carries nothing beyond the required fields. The IP address is
  not hidden for now.
- Of CoW's answers about an order, only its off-chain life is shown. A fill,
  and every amount it moved, comes from the account's verified logs alone.
- The swap's review also shows CoW's estimate, marked as not verified, to
  compare the venues. This follows from #144's design point 3, not from the
  ruling's words.
- The order is built, hashed and checked here. Its signed minimum is never
  below the verified Uniswap quote less the gas and the tolerance. A pair
  with no Uniswap route is not offered on CoW.

## dApps connect over WalletConnect v2; its relay is an off-chain service

(Owner rulings, 2026-11-03, #22, accepting its recommendations;
docs/walletconnect.md is the contract.)

- **WalletConnect v2 is the dApp connection protocol**, in both directions: a
  dApp connects to Serotine, and Serotine connects to an external wallet that
  signs for a Safe owner.
- **The relay never supplies chain state.** It sees the device's IP address,
  the topics, the timing and size of messages and the project ID. The
  payloads are end-to-end encrypted: it cannot read or forge one, only
  withhold, delay or replay it. A request seen before on its topic is
  dropped, so a replay never prompts the user again.
- **What arrives through it is untrusted input**, decoded and shown as such.
  The dApp's name and URL are shown as "stated by the dApp, not verified".
- **The Reown project ID is never committed.** A Gradle property supplies it
  at build time, from a CI secret, with separate IDs for debug and release. A
  build without one has WalletConnect switched off and says so.
- **The optional services stay off:** the Verify API, fetching dApp icons
  (a request to a host the dApp chooses, which reveals the IP address), the
  push (echo) server and Link Mode.
- **A dApp session names Sepolia alone, for now.** Ethereum and Gnosis Chain
  come by a later ruling, recorded here first. A proposal that requires
  another chain is rejected.
- **dApps connecting to Serotine come first**; an external wallet as a Safe
  owner's signer follows (docs/walletconnect.md, *The plan*).

## Open decisions

Each is recorded here before its code lands. Today: dApp sessions on
Ethereum and Gnosis Chain, and each optional WalletConnect service above.

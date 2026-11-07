---
description: "Data sources. The embedded engine is the only source of chain data; the three networks; Chainlink prices; the built-in token list; Safe co-signing without a service; history from the log index; an account's NFTs, on chain only; swaps on Uniswap and CoW; CoW's order API; dApp connections over WalletConnect."
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

## An account's NFTs: found in its verified history, proven held, read on chain only

(Owner rulings, 2026-11-05, #246.)

- **Found in the log index, proven at the head.** An NFT is found among the
  ERC-721 `Transfer`s and ERC-1155 `TransferSingle`/`TransferBatch`es the
  engine's log index holds for the account. It is shown only once a verified
  `ownerOf` names the account, or a verified `balanceOf` is above zero. A log
  alone never makes an NFT held.
- **On-chain metadata only.** A collection's `name()` and `symbol()` and a
  token's id, read with verified calls. An image or metadata is shown only
  when `tokenURI`/`uri` is an on-chain `data:` URI. IPFS and HTTP are not
  fetched: the screen says the image is not on chain.
- **Unasked NFTs are collapsed.** The built-in collections (ENS's registrar
  and NameWrapper, per network) are shown openly. Any other collection sits
  under a collapsed "Unlisted collections" row, marked unconfirmed: anyone can
  send an NFT to any address.
- **Older NFTs are added by contract and id.** The index covers the account
  from its start only. The list says so, and offers "Add an NFT": a contract
  and a token id, kept once the account is proven to hold it.
- ENS tokens are decoded on chain (the NameWrapper's `names`, the registrar's
  labels from logs), never through ENS's metadata service.
- **ENSv2 too** (owner ruling, 2026-11-06, #246): on Sepolia, which runs on
  ENSv2, its .eth registry is a built-in collection, pinned by its code hash.
  A label comes from its label store and is checked by hashing. A redeploy
  shows its NFTs as unlisted until the pin is updated.
- NFTs have no price and never count in a total.

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

## Heavy downloads stay off a metered connection unless the user allows them

(Owner ruling, 2026-10-20, with #85's history.)

- A heavy download is network work that can run to hundreds of megabytes and
  that nobody is waiting on: the log index's walk back over the account
  history first. A read the user waits on (a balance, a send's review) never
  is one.
- On a connection the platform reports as metered, or one whose cost cannot
  be read, it waits for an unmetered one. As built: Android's metered
  networks, and iOS's expensive paths (cellular, a hotspot) and Low Data
  Mode.
- Settings has the opt-in switch that lets it run there
  (`MeteredDownloadsSetting`, off by default). A choice that cannot be read
  is off.
- A platform that reports no metering (the desktop) runs it on any
  connection, as Myotis's desktop host does. The switch is greyed out there,
  and says why.
- Paused, the log index still follows the new blocks. Each network's index
  is paused and resumed alike.

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
  at build time, from a CI secret or variable. A build without one has
  WalletConnect switched off and says so.
- **One project ID serves debug and release builds, for now** (owner ruling,
  2026-11-05; it amends the separate IDs of 2026-11-03, #22). A free-tier
  Reown account has one project. CI reads it from one repository secret or
  variable, `WALLETCONNECT_PROJECT_ID`, for both builds. The build keeps a
  debug and a release property, so a second project later needs only the
  workflows to pass two names again.
- **The optional services stay off:** the Verify API, fetching dApp icons
  (a request to a host the dApp chooses, which reveals the IP address), the
  push (echo) server and Link Mode.
- **A dApp session holds real funds or testnet, never both** (owner ruling,
  2026-11-05, on #22; it amends "a dApp session names Sepolia alone, for
  now" of 2026-11-03, #22, ruling 9).
  - One session may hold Ethereum and Gnosis Chain accounts, and the dApp
    may switch between them with `wallet_switchEthereumChain`.
  - A Sepolia session is Sepolia alone. A dApp can never switch a session
    from testnet to real funds, nor back.
  - A proposal that requires a chain Serotine does not serve, or chains of
    both kinds, is rejected. Accounts of the other kind than those already
    chosen are greyed out on the proposal screen, a tap saying why.
  - Each request names one of its session's networks and an account of the
    session on that network, or it is refused.
- **dApps connecting to Serotine come first**; an external wallet as a Safe
  owner's signer follows (docs/walletconnect.md, *The plan*).

## Send from another wallet hands a payment link to another wallet app

(Owner rulings, 2026-11-05, #255, accepting its recommendations.)

- For an EOA whose key is not on this device, a transfer of the network's
  currency or of an ERC-20 token is handed to another wallet app as an
  EIP-681 payment link. That wallet signs and broadcasts it, through its own
  provider, which the review says learns the transfer and the IP address.
  Serotine contacts no service.
- Every network that is on. It is not a dApp session, so the ruling on dApp
  sessions' networks (real funds or testnet, never both; owner ruling,
  2026-11-05, on #22) does not bind it.
- Only `ethereum:` and `metamask://` links, never MetaMask's `https://` form,
  which falls back to its website.
- Nothing comes back through the link. What the transfer became is read from
  verified blocks alone, for 10 minutes after the handoff; after that, the
  account's history.
- On mobile; not on the desktop. Built for Android so far. iOS is not built
  yet, which is no ruling: the engine's iOS reads have no verified block body,
  and iOS opens an undefined one of several apps that claim `ethereum:`.

## Open decisions

Each is recorded here before its code lands. Today: each optional
WalletConnect service above.

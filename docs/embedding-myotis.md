# Embedding Myotis

Serotine reads the chain through an embedded [Myotis](https://github.com/biafra23/myotis)
engine — a sync-committee light client that verifies every answer against
Ethereum's consensus, so the wallet never trusts an RPC provider. This page is
how that engine reaches the app and the rules around it. The library landed in
Myotis with PR [#654](https://github.com/biafra23/myotis/pull/654) (merged
2026-10-15); its artifacts are published by the first Myotis `v*` tag after
it, which is when the coordinates below resolve.

## The library

| artifact | what |
|---|---|
| `eth.myotis:myotis-engine:<version>` | the root: Gradle module metadata that picks `-android` (an AAR with the Rust engine's native libraries for `arm64-v8a` and `x86_64`) for an Android consumer and `-jvm` for a desktop one |
| `eth.myotis:myotis-api` | the contract (`MyotisEngine`, `ChainHandle`, `VerifiedReads`, the ports) — an `api` dependency of the engine, so it is on the consumer's compile classpath |
| `eth.myotis:myotis-engine-ports` | the default port actuals (node key file, peer caches, per-network file layout) — the other `api` dependency |

Its whole third-party graph is JNA, slf4j and minimal-json; JNA is pinned
strictly to the native stub the AAR carries, so never force another JNA
version. On Android, Serotine takes JNA's classes from the libsodium bindings'
JNA AAR instead of the engine's jar (the two would duplicate them), and the app
re-states the strict pin as a constraint (`android-app/build.gradle.kts`). Entry point: `io.myotis.engine.Myotis` (`isAvailable()`,
`newEngine()`, `defaultPorts(...)`); the consumer guide is
`docs/android-library.md` in the Myotis repository.

## Where it comes from

`settings.gradle.kts` already declares the token-free `maven-repo` branch of
biafra23/myotis as a Maven repository, filtered to the `eth.myotis` group.
Every Myotis `v*` tag merges its artifacts into that branch; versions
accumulate, and the per-artifact metadata is merged on each publish. The same
set is on GitHub Packages (a token with `read:packages`) and, per release, as
`myotis-engine-<tag>-maven-repo.zip` for a build that reaches neither host.
Verify downloads against the release's `myotis-engine.SHA256SUMS`. For a
consumer developed against an unreleased engine, Myotis's
`./gradlew publishToMavenLocal` publishes the set into `~/.m2` (add
`mavenLocal()`, filtered to `eth.myotis`, while that lasts — never commit it).

## Where it goes in Serotine

- **`:data` only.** The engine is a data source. An adapter in
  `eth.serotine.data.engine` implements the domain's ports (balances,
  address kinds, prices, the transaction inputs, broadcast, receipts, ENS
  names — each its own port) over `io.myotis.api`; `:domain` and
  `:ui` never import `io.myotis.*`.
- **`io.myotis.api` and nothing below it.** Engine internals are not on the
  classpath (the library does not ship them), and the Myotis rule carries over
  verbatim: a host talks only to the API contract. The library's default
  ports (`io.myotis.engine.ports`, `Myotis.defaultPorts`) are part of its
  public surface and may be used.
- **The composition roots own the engine's lifecycle.** One engine per process,
  hosting a light client per network that is on and has accounts or a
  screen asking for it (Ethereum, Gnosis Chain, Sepolia; CLAUDE.md, *Data
  sources*; docs/networks.md, *The engine host*),
  created where the `AppGraph` is built, with the engine's state directory
  under the app's private files (Android: `filesDir/myotis`; desktop: the
  app's data directory). Each host supplies its platform's ports (on Android,
  the DNS servers of the active network, as the consumer guide shows).
- **Android:** the `:data` module's Android target is what resolves the AAR;
  minSdk 29, two ABIs — a build that ships other ABIs has no engine there.
  The engine's start belongs in a foreground service once sync runs in the
  background, as in the Myotis app. Until then the background runs are two
  JobScheduler jobs: the daily network-upgrade check (*Network upgrades*,
  below), which holds the engine awake for five minutes, and, while an
  exchange-rate widget is placed, its refresh on an unmetered network about
  every three hours, which wakes the engine for that one read
  (docs/wallet-ux.md, *Home-screen widget*).
- **Desktop:** the JVM jar does not carry the host library;
  `eth.myotis:myotis-engine-natives-<platform>` does, at the engine's version
  (macOS arm64 and x64, Linux x64 and arm64; Windows has none yet). The
  desktop's `stageMyotisLibrary` takes the host's library out of it, with
  JNA's native stub, into the app's resources (`appResourcesRootDir`), as
  Myotis's own desktop app does, so each OS/arch leg of the installer
  workflows ships its own, and `main()` points the engine at it. The packaged
  app never loads a library extracted to a temporary file, which a signed
  macOS bundle may refuse. A Windows build runs with no light client, and its
  balances say so.
- **iOS** consumes the engine's plain C ABI through Myotis's iOS module
  (`eth.myotis:myotis-engine-ios`, at the engine's version): its typed API
  carries `io.myotis.api`'s names and meanings, and each target's cinterop
  klib carries the engine's static library. `:data`'s iosMain has the iOS
  `MyotisHost`, the JVM one's twin over that API: one engine, a `ChainNode` per
  network wired to the same `TransactingChain` seam and the same common
  adapters, routed by `NetworkRoutedPorts`. The engine there asks
  for no ports (its peer caches and snapshot live under its data directory)
  and answers a read on a paused or starting handle at once as unavailable.
  The log index (*History*) is twinned too, from Myotis 0.1.18 (its #687).
  The iOS module reads no block by number in 0.1.18 (its block reads are
  internal), so the iOS host gives the finalized block no timestamp, and no
  CoW order is offered on iOS until it does (docs/swaps.md, *Proving an
  order's end*).
- **The engine's own log** is not printed by the engine: its tracing lines go
  into a ring in the library (4096 lines, the oldest dropped when full), which
  a host drains with `Myotis.drainLogs`. A debug build's `MyotisHost` (on each platform) drains
  it every second while the engine runs (`EngineLogPump`) into the same
  `debugLog` the adapters write to, each line marked `Myotis:` (logcat in the
  debug app, stderr in `:desktop-app:run`, NSLog in the iOS debug build, so a
  device's log has it without Xcode attached; the iOS host's own reports,
  why there is no light client among them, go there too, in every build). A
  release build leaves it undrained; a failed start still reports its last
  40 lines.

## What exists today

- **`MyotisHost`** (`eth.serotine.data.engine`, in `:data`'s `jvmSharedMain`)
  is the process's one engine, hosting one light client per network
  (docs/networks.md, *The engine host*): a `ChainNode` per `Network`, created
  in the engine (`MyotisEngine.create`) when it is to run and unregistered
  (`MyotisEngine.stop`) when it is not. Each composition root creates it with
  `MyotisHost.start(dataDir, dnsServers)` and runs `FollowNetworks` beside it,
  which starts a network's light client while the network is on and an
  account or a screen needs it, and stops it 30 s after nothing does, or at
  once when it is turned off (`LightClientControl`). Its ports route each call
  to the light client of the network it names (`NetworkRoutedPorts`), and a
  stopped network's reads say so (`ReadUnavailable.NETWORK_OFF`, its last
  verified balance offered as `LastKnown`). Loading the native library,
  creating the engine and starting a chain all run on one control thread, in
  the order asked, so nothing blocks a main thread. The desktop stops it after
  its window closes. Android pauses every running light client's networking
  30 s after nothing holds the engine any more (a visible activity, the daily
  network-upgrade check) and resumes them with the next hold;
  `AndroidDnsServers` supplies the active network's resolvers. The iOS
  `MyotisHost` follows networks on and off the same way (`FollowNetworks`),
  pauses every running light client when the app goes to the background and
  resumes them when it comes back.
- **Readiness, as Myotis reads it.** The adapters read `ChainHandle.status()`,
  a snapshot that never blocks, the way Myotis's own readiness ladder does:
  `SYNCING` is the checkpoint bootstrap; `CATCHING_UP` walks sync-committee
  periods from `syncStartPeriod`; `SYNCED` serves reads only with a verified
  head younger than 45 s and at least one peer serving state proofs.
- **Verified balances.** `VerifiedNativeBalances`, common code, proves a
  balance through `ChainHandle.requestAccount` whenever the verified head has
  moved. It is accepted only with no `failReason` and a `verifyMethod`, and is
  labelled with the block the proof anchors to: `VerifiedReads.getBalance`
  answers a block number from the current head, so it cannot be labelled. From
  Myotis 0.1.20 a watched account on a chain with block access lists may be
  answered with no proof fetched (`verifyMethod` `blockAccessList`: an earlier
  proof carried forward by verified BALs, Myotis's docs/address-activity.md),
  labelled with the block it answers at, up to 2 below the head; its
  `matchedBeaconSlot` attests that block's state root, so a code read anchors
  to it as to a `stateRootMatch` (`accountProofAnchor`). While
  reads cannot be verified, the row shows why ("Syncing…", "Sync refused", "Not
  verified", "No light client"), with one exception: while there is no head
  to read at (not synced, asleep, a stale head, no proof peer), a balance
  proven earlier is offered
  as `LastKnown`, labelled with its block and the time of the head it was
  proven at, and the row says it may have changed since (owner's request,
  2026-10-19; docs/wallet-ux.md). `ProvenReads` keeps that last proof per key;
  every adapter but the two balance ones reads it as syncing. The balance
  adapters also keep it across restarts in `LastBalancesFile`
  (`last-balances.json` beside the account list, a path the host passes to
  `MyotisHost.start`): at most 256 entries, rewritten when an amount changes
  or its stored proof is 50 blocks old, so the time a row shows after a
  restart can be up to ten minutes earlier than the last proof of that same
  amount. Why a node is
  down is written to stderr, with the engine's own log when it refused to
  start.
- **Verified token balances.** `VerifiedTokenBalances` runs each token's
  `balanceOf(holder)` through `VerifiedReads.callDetailed`, polled and cached
  like the rest, through `ProvenReads`. It is labelled with the block the
  engine names (`-1`, none, when it does not). `REVERTED` is a verified "not a
  token". `REFUSED`, and `INFEASIBLE` for a plain call, are a permanent refusal
  (`ReadUnavailable.REFUSED`, "cannot be read on this device"). `UNAVAILABLE`
  is unproven, retried at the next head.
- **Verified token metadata.** `VerifiedTokenMetadata` reads a contract's
  `decimals()`, `symbol()` and `name()` the same way, for the check of a
  built-in entry and for Add token, labelled with the block of the
  `decimals()` call. `decimals()` and `symbol()` must answer: a revert, an
  answer that is not a number or an ABI string, or decimals above 77 is a
  verified "not a token". A missing `name()` is the symbol. A refused call is
  `REFUSED` at once, without the calls after it. A description and a refusal
  are kept for the rest of the process (the 256 most recent): a token's
  description is read once, not at every head. "Not a token" ends the read
  but is not kept, since the contract may not be deployed at that head yet.
  Text a contract returns loses, code point by code point, whatever renders
  as nothing or moves text: controls, separators, format characters of any
  plane (bidi overrides, zero-width ones, tags), the rest of Unicode's
  default-ignorable set (variation selectors, the Hangul fillers) and the
  blank Braille cell, so none can reorder a line or hide in a symbol.
- **Verified prices.** `VerifiedPriceFeeds` reads each pinned Chainlink feed
  (`ChainlinkFeeds`, per network) with two verified calls, `decimals()` and
  `latestRoundData()`, through `ProvenReads`, at every verified head. Decimals
  other than the pin, a negative or zero answer, or what is no round are no
  price; a refusal is `REFUSED`. A round's age is measured against the
  time of the verified head the read started at, which the engine snapshot
  derives from the BLS-attested slot (genesis + slot × seconds per slot),
  never the device's clock; with no head time known yet the read is
  unproven. A round older than its feed's heartbeat and ten minutes' grace
  is stale, and one dated more than two minutes after the head is no feed's
  answer. (`ChainHandle.getBlockVerified`, which would give a block's
  timestamp, is not available on the Rust engine.)
- **Verified pool averages** (owner ruling, 2026-10-29, #205). Where no feed
  is pinned for a price id, `VerifiedPoolPrices` reads a pinned Uniswap v3
  pool (`UniswapPools`, per network; Ethereum's COW and SAFE today) through
  `ProvenReads`, again every five heads, quoted in ETH, and `FeedsThenPools`
  routes each price id, now and a day back, to its feed or, only where none
  is, its pool. Once per adapter, the factory's proven code is checked
  against its pin and its `getPool` against the pool's address. Then fewer
  than two observations or an oldest one younger than the 30-minute window
  (`slot0`, `observations`), an in-range depth (`liquidity()` at `slot0`'s
  price, as the WETH it stands for) below the pool's floor, and an average
  (`observe`) more than 500 ticks from the tick now are each no price, with
  their own reason. `slot0`, `liquidity()` and `observe` must answer at one
  block of one hash, or the read is unproven. A reading is dated by the head
  and never stale on its own; ETH/USD and the display currency's feed carry
  theirs. The day back is the same pool's `observe` over the window ending a
  day before the head, at the head, and is "no round that old" past its
  observations.
- **One fetch per key and head.** `ProvenReads` keeps the last proof per key,
  and a subscriber that arrives while that key's proof for its head is being
  fetched waits for that fetch rather than making the same blocking calls:
  the account screen, the Accounts screen and every account of the portfolio
  read the same prices and balances.
- **Verified address kinds.** `VerifiedAddressKinds` reads what is at an
  address for the Watch screen, polled, cached and refused the same way (the
  two share `ProvenReads`). The code hash comes from `requestAccount`'s proof,
  labelled with its block. The empty hash means an EOA. Any other hash fetches
  the code with `VerifiedReads.getCode`, which the engine checks against a
  proven hash too. The adapter accepts the code only if it hashes to the
  proof's value: the head may move between the two reads. An EIP-7702
  delegation designator (`0xef0100` and 20 bytes) makes a delegated EOA, and
  any other code makes a contract — or a Safe, below.
- **Verified Safes.** Code whose Keccak-256 is a pinned SafeProxy runtime
  hash (`SafeDeployments`, per network) is read further, with storage proofs
  (`ChainHandle.getStorageProof`, a mapping entry by its key):
  - slot 0 must name a pinned canonical singleton, whose version it takes;
  - the owners are the list of the mapping at slot 2, walked from the
    sentinel `0x1` back to it, as many as slot 3 counts;
  - the threshold (slot 4) is between 1 and that many;
  - slot 5 holds the nonce, and slot 1's list the modules;
  - the guard is the word at `keccak256("guard_manager.guard.address")`, and
    1.5.0's module guard the one at
    `keccak256("module_manager.module_guard.address")`.

  A slot proof is used only once the engine tied it to a beacon-attested
  root (`storageProofLabel`), and it carries two things. Its anchor
  (`ProofAnchor`) is the attested slot whose state root it matched, from the
  light client's own state (for a header-chain match, the block walked from,
  whichever head it was walked to). Its label is the block it is shown with:
  the block of the root the proof was read at (`peerBlockNumber`, as balances
  and code are shown), not below the finalized block, once the matched slot
  lies from the finalized slot up to the head the reads resolved, the later of
  it and the optimistic slot (from Gloas on the optimistic one can trail the
  finalized one). Since Myotis 0.1.17 the slots are the blocks' own (Myotis
  #658), and the result's block numbers are sampled at other moments than its
  slots, so no slot picks one of them. That number is the serving peer's
  report on some engines, so it is shown, never relied on. Any other match is
  no proof.
  Every proof of a reading must share one anchor (and one label), and the
  state must not be before the code's, as their anchors tell: attested slots
  compared, walked blocks compared. A walk against a match, which nothing
  verified orders, is read again, never decided by a label.
  A read the head moved under is made again at once; a proof that comes back
  null or throws (a slow or lost snap peer, a failed JNA call), the code's
  first one included, is asked for again, three times in all, and a read it
  still fails in is made again after a second's pause. Both are retried for
  as long as the read's budget lasts, 20 s from the first code proof
  (`SAFE_READ_BUDGET`, checked between the engine's calls, which block and
  are not cut off; a cancelled read stops at the next one), and past it the
  reading is unproven until the next poll. A
  subscriber that joined a read whose maker was cancelled midway makes it
  again itself, rather than take the cut-off read for unproven. However often a Safe is read, a
  reading whose slots do not share one anchor is never taken. A dozen proofs
  one after the other often outlast a head, which is what makes the head move
  under them; the root fix is the engine's, several slots in one proof at one
  block (biafra23/myotis#662), and once it lands a Safe is proven in one
  request. Until then the debug builds log which proof failed or straddled
  and how many reads a Safe took (`MyotisHost.start`'s `debugLog`: logcat in
  the debug app, stderr in `:desktop-app:run`, NSLog in the iOS debug build;
  public chain data only). A
  one-shot check of a Safe (an import, the checks before Sign, Approve,
  Execute and a proposal, and a transaction's owners as its screen reads
  them) waits out an unproven reading for the next one, for up to 45 s
  (`ObserveSafe.check`'s `SAFE_CHECK_BUDGET`): longer than the read's 20 s
  and one 4 s poll together with room for the next read (`SafeBudgetsTest`
  holds the two to it), so the wait normally does not cancel the read it is
  waiting on and sees the one after it. Normally, not always: the 20 s are
  checked only between blocking engine calls, the poll reads the engine's
  state outside them, and the wait cannot end before a blocked JNA call
  returns. One with no engine, a refused anchor or a read the engine refuses
  is answered at once. A quote's or a proposal's age (its minute) runs from
  after its Safe check, from the first of the figures it protects. Signing
  checks the Safe first, a step that leaving the screen or Back stops, and
  only then asks for the key, in a step run to its end (`SignSafeTx.check`,
  then `SignSafeTx.sign` against the state that check verified); a
  proposal's check gets only what is left of its minute (less the prompt's
  15 s for the biometric prompt), and one that outlasts it proposes again,
  saying so, instead of signing.
  The guards sit at hashed slots, which `getStorageProof` cannot name,
  so they are verified `VerifiedReads.getStorageAt` reads at the head: they
  may be a head later than the label. A list that does not lead back to the
  sentinel, an unknown singleton or a Safe not set up is "another contract".
  A Safe takes a dozen proofs and its owners rarely change, so its reading
  stands for five heads (about a minute) before it is proven again, always
  labelled with its own block. The pins were read on Sepolia on 2026-10-17:
  each singleton's `VERSION()` as a verified call, each proxy hash from a
  verified create call of its factory's `proxyCreationCode()`.
- **The light client's progress.** `EngineLightClient` drives the
  `LightClient` port, and the Accounts tab shows it in a strip under the top bar:
  - starting;
  - bootstrapping from the checkpoint;
  - verifying sync-committee periods (*n of m*);
  - waiting for a fresh head or for proof peers;
  - "Verified at block N";
  - asleep, a stale anchor, or no engine.

  A cold start on Sepolia, measured on a Mac: bootstrapped after 1 s, verified
  reads after about 20 to 30 s.
- **Resolving the library before a Myotis tag publishes it.** In a Myotis
  checkout, `./gradlew -PpublishRelease publishToMavenLocal` puts the version
  the next tag publishes into `~/.m2`. Pass Serotine's builds an init script
  that adds `mavenLocal()` filtered to `eth.myotis`, with `-I`; the repository
  itself never names `mavenLocal()`:

  ```kotlin
  // myotis-local.init.gradle.kts, kept outside the repository
  settingsEvaluated {
      dependencyResolutionManagement.repositories.mavenLocal {
          content { includeGroup("eth.myotis") }
      }
  }
  ```
- **Desktop, a host library built from Myotis.** `:desktop-app:run` uses the
  published one from the natives artifact.
  `./gradlew :desktop-app:run -PmyotisLibraryDir=<dir>` runs a locally built
  `libmyotis_engine.{dylib,so}` instead (Myotis: `./gradlew cargoBuildHost`,
  then `rust/target/release`). The same property runs
  `MyotisHostSepoliaTest`, a real-network test that is skipped otherwise.
- **Android 10 and INTERNET.** An install that gains INTERNET on an update,
  rather than having it from the first install, can keep getting `EPERM` from
  netd's eBPF socket filter until it is reinstalled or the device reboots. The
  engine then reports "listen failed" and the list says "No light client". No
  released Serotine was ever without the permission, so this is a development
  trap, not a user path.

## Sending

The engine's half of a transfer is four
adapters over `VerifiedReads` and `EnsApi`, behind domain ports, all on a
`TransactingChain` seam that extends `VerifiedChain`, which the Send screens
(docs/wallet-ux.md, screens 7 to 9) put to use. Every read is made only while the light client can verify reads
at its head; the broadcast is the engine's to judge (it does so on verified
state). All of it runs on the adapters' dispatcher, and an engine that fails,
or a `CancellationException` its own code throws, is no answer, as for every
verified read.

- **`EngineTransactionInputs`** (`TransactionInputs`):
  - The nonce is `getTransactionCount(address, "pending")`: the verified count
    plus the sends this node made that are not mined yet, so a second transfer
    right after a first does not reuse its nonce. That record is the engine's
    memory, gone with the process and expiring; the app's own record of sent
    transfers (`SentTransfers`, kept in `sent-transfers.json` beside the account
    list) covers a restart: `QuoteTransfer` never quotes below its last unmined
    nonce plus one, nor below the mined count, and holds back what those
    transfers may still take of the native balance and of the token's.
    - A transfer is recorded once a peer took it. One the engine rejected
      or refused holds no nonce, so a later quote may take the same one, and
      only one of the two can ever be mined.
    - A broadcast that came back unavailable (no peer answered) may have
      reached one all the same, so it is recorded too, as maybe sent
      (`SentTransfer.maybeSent`): it holds neither the nonce nor the cost,
      and its receipt is watched by its hash. While one from the account is
      not mined and within its lifetime, a new quote from that account is
      refused (`CallRefusal.EarlierMaybeSent`): the earlier transaction may
      still be on its way, and it is retried (`RebroadcastTransfer`, from the
      review or from its status screen), never replaced by a new one, which
      could take the next nonce and pay twice. A retry a peer takes makes it
      an ordinary record; one turned away (rejected with no receipt, or
      refused) keeps it as it was, since a retry's rejection says nothing
      sure of the first broadcast ("already known", "replacement
      underpriced", a nonce used past the light client's head), and its
      lifetime and the mined count bound it.
    - A record stops holding its nonce and its cost three hours after a peer
      took it if no block did (`RECORD_LIFETIME_SECONDS`, which runs from
      that broadcast, a retry's included): kept holding them, it would hold
      every later transfer behind a nonce no block may ever take. Three hours
      is geth's `txpool.lifetime`, which evicts only queued (not yet
      executable) transactions, so an executable one can stay in a pool
      longer, and be mined later; that is safe, since a nonce is mined once
      whichever transaction takes it. The record itself stays until the
      account's mined count passes its nonce, so its status screen still
      finds it.
    - A retry of a broadcast no peer answered may come back rejected because
      the first broadcast reached a peer after all and a block used its
      nonce. `RebroadcastTransfer` checks the verified receipt before it
      believes the rejection, so such a transfer reads as sent, not as one
      that can never be mined. A receipt that cannot be verified right now
      leaves both verdicts open (`RejectionUnverified`): the review says so and
      keeps Try again, which checks again, and offers no new transfer.
  - The fee levels come from `feeHistory` over the last ten blocks at the 10th,
    50th and 90th percentile (`EngineJson.feeQuote`). Each level's tip is the
    median of its percentile, never below 0.001 gwei and never below the
    slower level's. The next base fee is the history's last; the fee cap is
    twice that plus the tip.
  - The engine's fee history is cold on its first request after a start, and
    on one more than 120 s after the last: it builds ten blocks of it, waits
    5 s for that, and answers nothing, and a caller right behind gets nothing
    at once; the build goes on, and a request a few seconds later is
    answered. So an empty answer is asked again every 2.5 s, for up to 15 s
    (`EngineTransactionInputs.fees`), the wait a coroutine delay a cancelled
    quote stops at; and Send, Create Safe and a Safe transaction's screen ask
    for it once as they open (`WarmFeeHistory`), so it is warm by the review.
  - The gas is `estimateGasDetailed`, so a revert, an infeasible transfer and
    a refusal are told apart. It is the engine's answer as it stands, priced
    under the fork the chain runs: on Sepolia, Amsterdam since 2026-10-06, a
    plain transfer estimates below the 21000 of earlier forks (EIP-2780;
    17433 when probed), so Serotine adds no floor of its own.
  - The simulation is `callDetailed` from the sender at the head, the transfer
    as it will run.
- **`EngineBroadcaster`** (`Broadcaster`): `sendRawTransactionDetailed`. The
  engine judges a send on verified state before it gossips it, and its verdict
  (sent, rejected with geth's reason, refused, unavailable) is passed on.
- **`EngineReceipts`** (`ReceiptTracker`): `getTransactionReceipt` every poll,
  verified against the including block's receipts root. A receipt at or below
  the engine's finalized block (`StatusSnapshot.finalizedBlockNumber`) ends the
  watch as finalized. The engine looks for a transaction from 8 blocks below
  its head at the first poll of its hash and forward from there, so a
  transaction mined while nothing watched it is never found by its hash.
  That is why `FollowSentTransactions` watches every transaction sent while
  the app is open and records what it sees in the history, and why the
  history tells one mined while the app was closed by its account's mined
  count (docs/wallet-ux.md, screen 25).
- **`EngineNames`** (`NameResolver`): `ens().resolveAddress(name,
  EnsRoot.FINALIZED)` and nothing fresher. `PEER_HEAD` would be a peer's claim,
  and `AUTO` falls back to it, so an answer not `verified` is no answer.
  - The engine is built without a CCIP-Read gateway (`EnginePorts.httpGateway`
    null). A name whose resolver answers through one (EIP-3668) is off-chain
    data no ruling allows, and the request would tell the gateway the name
    and the device's address. The engine then refuses the name, and it reads
    as `OffchainOnly`, not as "try again".
  - The engine lower-cases a name and normalizes nothing else (no ENSIP-15),
    so a look-alike (a Cyrillic letter, a zero-width joiner) would hash to
    another node. Until normalization exists, only ASCII letter-digit-hyphen
    labels are resolved.

The transaction itself is the domain's: `Eip1559Transaction` builds the
signing digest the signer signs and the raw bytes the broadcaster sends, and
`QuoteTransfer` and `SendTransfer` (`:domain`) put the four together with the
balances and the signer. What is signed is exactly what the quote shows: only
`QuoteTransfer` makes a quote, and nothing copies it. A transfer signed but not
broadcast keeps its signed bytes (`SignedTransfer`), and a retry
(`RebroadcastTransfer`) sends those bytes again rather than quoting anew, which
could take the next nonce while the first is still on its way.

The quote and the send are not a transfer's alone: `CallQuoter` and
`signAndSend` (internal to `:domain`'s use cases) quote and send any
transaction from an account the wallet holds, a Safe transaction's approval
or execution included, with the same nonce record, fees, estimate, verified
run and receipt check on a rejected retry. `SentTransfers` records what each
one did (`SentAction`: a transfer, a Safe transaction's approval or
execution, or a Safe's deployment), and its nonce and most cost hold a later
one back whatever it did.

## Safe transactions

A Safe transaction is the domain's too (`SafeTx`, its EIP-712 hash, the
`execTransaction` and `approveHash` calldata; docs/wallet-ux.md, *Co-signing
a Safe transaction*). What the engine adds:

- **The Safe's state** is the verified inspection above (`VerifiedAddressKinds`):
  owners, threshold and nonce, each storage-proven at one block. A request,
  a signature or an execution is checked against it.
- **Approvals on chain** are `VerifiedSafeApprovals` (`SafeApprovals`): the
  Safe's own getter `approvedHashes(owner, hash)` as a verified call
  (`callDetailed`, no sender) at the head, labelled with the block the engine
  names. A word other than zero is an approval; a revert or an answer that is
  not one word is no answer. The mapping is two levels deep, which
  `getStorageProof` cannot name, so it is a call rather than a proof.
- **What is simulated, and how.** No state override is used, though the API
  has them (`callWithOverrides`, gated by `supportsStateOverrides()`): an
  answer under an override is the caller's hypothesis, not a chain fact.
  - A proposal (`ProposeSafeTx`) runs the Safe's call alone, as a verified
    call *from the Safe's address* at the head (`TransactionInputs.simulate`).
    Not run there: the Safe's own checks (the owners' signatures do not exist
    yet; the nonce) and its guard. An engine that will not run a call from an
    address with code answers `Simulation.Refused`, and the proposal says it
    is not simulated rather than failing.
  - An execution (`QuoteSafeExecution`) runs all of it: `execTransaction`
    with the signatures it will carry, as a verified call from the executing
    account, estimated with `estimateGasDetailed` as any transaction is. The
    Safe's signature and nonce checks, its guard and the call itself are in
    that run. It must return `true`: `false` is a call that failed under a
    non-zero gas price, which would still use up the nonce. The run has the
    engine's gas, not the limit the execution is signed with (`callDetailed`
    takes none), and that limit is what the Safe's call gets when the
    transaction has a `safeTxGas` and no gas price: a call that runs out
    there returns `false` instead of reverting, so it can pass the run and
    fail on chain. That is one reason no transaction with gas or refund
    terms is signed, approved or executed here (`SafeTxRefusal.GasTerms`):
    with all of them zero a failing call reverts the execution, and the
    estimate finds a limit it passes at.
  - An approval (`QuoteSafeApproval`) runs `approveHash` from the owner, which
    the Safe reverts for anyone else.
  - A Safe's deployment (`QuoteSafeDeployment`) runs the factory's
    `createProxyWithNonce` from the paying account, estimated and run as any
    transaction is. The run must return the address computed in `:domain`
    (CREATE2 over the pinned proxy creation code, `SafeCreation`), so the
    factory's code on verified state, not the pin, has the last word; before
    it, that address must read as holding no code (`AddressKinds`, the
    inspection above). No new engine read: the deployment needs none.
- **The co-signing medium is no engine read.** A signing request travels as
  text between devices (`JsonSigningRequestCodec`), and signatures are
  checked with libsecp256k1 (`Secp256k1Recovery`), not with the engine.

## History

An account's history beyond what this device sent (issue #85) comes from the
engine's verified log index (Myotis `docs/eth-getlogs-design.md`), a
watch-list the engine walks back over verified headers, fetching receipts
only for the blocks whose `logsBloom` flags an entry. Its logs are served
only inside the span it covers, and a query outside it is refused, never
answered with an empty list.

- **`EngineLogIndex`** (`LogIndex`): `ChainHandle.setLogIndexConfig`,
  `logIndexStatusJson` and `VerifiedReads.getLogs`. Two kinds of entry:
  - a `LogWatchKey.Emitter` is an address entry: every log the contract
    emits, a Safe's own events (`SafeSetup`, `ExecutionSuccess`,
    `SafeReceived`, …);
  - a `LogWatchKey.Mention` is a topic entry (Myotis #673, in 0.1.17): every
    log from any contract carrying the address, left-padded to 32 bytes, as
    topic 1, 2 or 3, so every token's `Transfer` to or from the account,
    listed or not.
  - Entries are never restricted by event, never named (a name would put the
    user's labels into the engine's files), and a topic entry always covers
    positions 1 to 3. A push that disagreed with an entry the engine holds
    on its restriction would replace the whole index.
  - A change is pushed only while reads can be verified: the engine holds a
    push made earlier until it is ready. The engine unions what is pushed
    with what it holds and keeps each entry's lowest block, and `unwatch`
    drops an entry with its coverage and logs.
  - Logs are taken only when every one is what the query selects, inside its
    range; anything else is no answer. A query names both ends of its range:
    the answer does not say which range it read.
  - The engine takes an entry's `fromBlock` as saying nothing of it comes
    before that block, and answers a range reaching below it from there on.
    For an account not created here that is not so, so the adapter reads the
    entry's block from the status first and refuses such a range
    (`LogsReading.BeforeStart`) rather than let it read as complete.
- **The finalized block and its time.** The light client's status carries
  the finalized block (`StatusSnapshot.finalizedBlockNumber`) with its own
  header's timestamp (`FinalizedTimes`): `VerifiedReads.getBlockByNumber` of
  that block.
  - It is read once per finalized block, only while the light client is
    synced and not paused, and after a read that failed not again for 12 s,
    as the status is polled every second.
  - It is read in a scope of its own, never in the status poll, which must
    not block; until the newest finalized block's header is had, the newest
    one had stands, finalized too, only older.
  - `FinalizedHeaders` reads it through the engine chain
    (`VerifiedChain.blockByNumber`), so the Debug tab's log has it as
    `getBlockByNumber`, as every other request.
  - A header counts only when its `number` is the block asked for: the
    engine has answered a read for a past block from its head before.

  Not the finalized slot's time: the slot the engine pairs with
  the block can be a later, empty one (docs/swaps.md, *Proving an order's
  end*). A CoW order's end is proven at this block.
- **`ProveCowOrderEnds`** (`:domain`, run by the hosts beside
  `FollowAccountLogs`) reads each live CoW order's `Trade` and
  `OrderInvalidated` from its account's `Mention` entry, up to the finalized
  block (docs/swaps.md, *Proving an order's end*).
- **`FollowAccountLogs`** (`:domain`, run by the hosts while the engine runs,
  never by `App`) keeps the index following the accounts:
  - Every account is a `Mention` entry, and a Safe an `Emitter` entry too,
    from where its history starts (`HistoryStarts`, kept in
    `history-starts.json`).
  - An account is noted with the time it is added (`HistoryNotingAccounts`,
    the account list the hosts hand everything). Once the light client is
    synced, the time is turned into a block counted back from the verified
    head, at 12 s a slot, plus an hour for a device clock that is off; or an
    earlier block a transaction sent from here concerning it was seen in.
    An account added before starts were noted starts at the head.
  - Nothing before an account's start is walked: for an account not created
    here, its history is checked since it was added (owner ruling,
    2026-10-20).
  - An account removed is unwatched, and its start forgotten once the engine
    took that.
- **`ObserveSafeHistory`** and **`ObserveTokenTransfers`** (`:domain`)
  read, for the account detail's Activity tab, a Safe's own events (`SafeEvents`: Safe
  1.3.0's and 1.4.1's, each in the form its version indexes it in) from its
  `Emitter` entry, and an account's ERC-20 and ERC-721 `Transfer`s
  (`TokenTransfers`) from its `Mention` entry, queried at topic 1 and topic
  2. Both read as `IndexedReader` does: from where the account's history
  starts, or the lowest block the index covers of it when higher, to the
  top of what it covers; again as that moves, only the blocks it grew by
  and the top 64 read before (coverage is polled, so a reorganisation can
  look like growth); all again when it shrank.
- **What the walk tells a peer.** Receipts are fetched only for the blocks
  whose bloom flags an entry, so the serving peer learns which blocks matter
  to this device. That is the trade-off the log index makes for any
  watch-list, accepted as is (owner ruling, 2026-10-20). The engine runs no
  RPC listener here, so no other process or page can test addresses against
  the index.

## Network upgrades

A hard fork the engine's build does not implement is the one thing that ends
a working install: past it, the light client can no longer verify the chain
(the sync committee signs under the new fork's version), and the wallet would
look stuck syncing. Myotis detects one ahead of time and Serotine tells the
user to update — before it, and, after it, nothing else.

- **What Myotis reports.** `StatusSnapshot.upgradeAdvisory()`: an
  `UpgradeAdvisory` with a phase (`SCHEDULED`, its activation ahead;
  `ACTIVE`, passed), the activation time (0 when unknown), the fork id
  upgraded peers use (`0x00000000` when unknown) and how many distinct peer
  networks back it. Two detectors feed it: the execution layer's EIP-2124 fork
  ids in peers' eth Status (enabled on Sepolia) and the consensus layer's ENR
  `eth2` fields and Status digests (every network), each judged against the
  build's own fork schedule, with one vote per IPv4 /24 or IPv6 /48, at least
  three sources and more than those contradicting it. It is reported in every
  lifecycle state, asleep included. Upgraded clients announce a fork from the
  day their release carries it, so the warning comes weeks ahead.
- **It is peer data, never verified.** Lying peers can raise a false warning,
  never a wrong answer: nothing in verification reads it. So Serotine shows
  it as what peers report, and claims it can no longer follow the network
  only when the light client's own state agrees — not synced, or synced with
  a stale head — the rule Myotis sets for every host (CLAUDE.md, *Trust*).
- **In Serotine.** `EngineNetworkUpgrades` (`eth.serotine.data.engine`) is the
  `NetworkUpgrades` port over it, polled with the engine's status; a malformed
  field becomes unknown rather than failing the status poll the light client
  and every verified read share. `ObserveUpgradeWarning` (`:domain`) joins it with the light
  client's state and the upgrade recorded from earlier reports:
  - *Ahead*: a banner on Accounts, with the date and the way to the update;
  - *Activated*, as peers report or by its announced time on the clock, the
    light client not showing this version cut off: the banner says so;
  - *Update required*, an activation peers confirmed (reported now, or
    recorded as active) and a light client that is not verifying: the app
    shows the update screen and nothing else, the back stack cleared, until
    the light client verifies again (or the app is updated). A recorded time
    that merely passed on the clock never locks the app: a fork can move.
- **Notifications.** `UpgradeNotices` (`:domain`) shows a notice when peers
  first report an upgrade, a week and a day before its activation, and once
  when it has activated; then nothing more. Each is shown once, the most
  urgent when several fall due together. The record that remembers this
  (`FileKnownUpgradeStore`, `eth.serotine.data.upgrade`, `upgrade.json` next to
  the account list) belongs to one app version: an updated app starts with
  none, and its own engine says whether its build supports the upgrade. An app
  opened after an activation peers confirmed knows it from the record at once,
  before peers report it again. A changing count of reporting networks is not
  written. A notice is shown before the record says it was: told twice
  (should the app die in between) beats never. The activation notice and the
  update screen say "peers report" only when they did; one due by the clock
  alone says the announced time has passed.
- **While the app is closed.** Android: a daily JobScheduler job (any network,
  battery not low) wakes the engine for five minutes, long enough for peers to
  report an upgrade, and no longer once peers confirmed an upgrade activated
  and the user has been told (all is said; only an update changes it); an
  inexact alarm (`setAndAllowWhileIdle`, no exact-alarm
  permission) shows each reminder at its time, re-armed after a reboot or an
  update; the notification permission is asked for at launch (Android 13 and
  later; Android stops asking after two refusals), and without it the app's
  own screens still say everything. The
  desktop notifies through the system tray while it runs; a reminder that fell
  due while it was closed is shown at its next start.

## What the engine's rules mean for a wallet

- **Every chain read is verified or refused.** The engine answers from the
  verified head; when it cannot prove an answer it fails the call. Serotine
  shows that state (`CATCHING_UP`, `STALE_ANCHOR`, an unproven read) instead
  of filling in from anywhere else. There is no RPC-provider fallback, in
  any build type.
- **A stale anchor is passed only on the user's word** (CLAUDE.md, *Trust*,
  owner's request of 2026-10-24). A light client parked in `STALE_ANCHOR`
  (its newest anchor, the embedded checkpoint or the persisted snapshot,
  older than the weak-subjectivity bound) stays parked until the user
  confirms Accounts' "Catch up anyway…" for that network:
  `SyncPastStaleAnchor` checks it is still parked, and the host calls the
  chain's `acceptStaleAnchor()` on its control lane. The consent lasts for
  the run (the engine never persists it), so a restart that still finds the
  anchor stale asks again. The bound (`setWsBoundPeriods`) keeps the
  engine's default.
- **A parameter that can change the answer is applied or refused**, never
  accepted and ignored — the Myotis rule, and the reason a port's contract
  spells out what each argument does.
- **Not everything a wallet needs is chain data**, and what is not is a
  separate decision for the owner — what is fetched, over what, and how its
  answer is trusted or shown as untrusted — recorded in CLAUDE.md under *Data
  sources* before the code lands. Prices, the token list and Safe
  co-signing are decided there (2026-10-16), each without an off-chain
  service. CoW Protocol's order API is the first off-chain service allowed
  (2026-10-22), for the swaps the user sends to CoW (docs/swaps.md).
  WalletConnect's relay is the second (2026-11-03, #22), for dApp
  connections (docs/walletconnect.md). It carries requests and answers and
  never chain state: what a dApp asks about the chain is read through the
  engine.

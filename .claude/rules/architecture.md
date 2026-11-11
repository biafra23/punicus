---
description: "The modules, the dependency rule, MVVM, the Myotis boundary, dependency injection, SOLID, tests per layer and the feature checklist."
paths:
  - "domain/**/*"
  - "data/**/*"
  - "ui/**/*"
  - "android-app/**/*"
  - "desktop-app/**/*"
  - "ios-app/**/*"
  - "native/**/*"
  - "*.kts"
---

# Architecture

Loaded when you work in `domain/`, `data/`, `ui/` or a host module.
**docs/architecture.md is the contract.** This file is the summary a reviewer
holds a diff against. Where the two disagree, docs/architecture.md wins.

Punicus is a clean architecture with MVVM in the presentation layer.

## The modules

### `domain`

Entities and value objects (`Account`, `Address`, `AccountKind`), the ports
(`AccountRepository`, ...) and the use cases (`ObserveAccounts`, ...).

- Pure Kotlin. Its only libraries are coroutines (`Flow`) and kotlincrypto's
  Keccak-256 (`sha3`).
- Keccak-256 is allowed for any of Ethereum's hashing, as pure computation
  (owner ruling, 2026-10-18, #50; widened from a closed list of uses by the
  owner's ruling, 2026-11-03, on #218). That covers every signing digest
  (EIP-1559, EIP-191, any EIP-712: a `SafeTx`, a CoW order, a dApp's typed
  data), transaction hashes, CREATE2 addresses, EIP-55, ABI selectors and
  event topics. A digest a signer signs is computed in `:domain`.
- The ruling covers that one library. Any other library in `:domain` still
  needs a ruling, and JSON is still reached through a port.
- Targets desktop JVM and iOS. Deliberately NO Android target: Android
  consumers resolve its JVM variant, so nothing in it can ever import
  `android.*`.

### Native code

Our own native code lives in this repository (owner ruling, 2026-11-11,
docs/privacy-pools.md D9): the Privacy Pools library in Rust, under
`native/privacy-pools/`, built through cargo by Gradle and CI for every
target. Only `:data`'s adapters load it, through JNA on Android and the
desktop and through cinterop on iOS. It depends on nothing of ours.

### `data`

The adapters behind the ports. Targets Android, desktop and iOS; the Android
target is what resolves the engine's AAR. One package per area,
`eth.serotine.data.<area>`:

| Package | Holds |
|---|---|
| `account` | `FileAccountRepository`: the account list, persisted as a JSON file. `InMemoryAccountRepository` for tests. |
| `files` | `VersionedJsonFile`: the versioned JSON file every file adapter keeps its value in. |
| `keys` | `EnvelopeKeyStore`: imported seeds in envelopes (docs/keys-and-signing.md). `Secp256k1Recovery`: finds the address a signature recovers to; it holds no key. |
| `engine` | `MyotisHost`, the Myotis engine adapter: one engine hosting a light client per network that is on and needed. It gives verified native and token balances, token metadata, prices (a pinned feed's, or a pinned pool's average where no feed is) and address kinds; the peer-reported network-upgrade advisory; for sending, the nonce, fees, gas, broadcast, receipts and ENS names, and the mined counts, fee quotes and Safes last verified, kept for a lenient draft; a Safe's owners' approvals on chain; Uniswap v3's pinned contracts, proven by their code, and its verified calls; CoW Protocol's the same way; the verified log index that follows the accounts' histories; the finalized block with its header's timestamp; and a feed's round of a day ago from its round history. Its glue over `io.myotis.api` lives in a `jvmSharedMain` source set the Android and desktop targets share. |
| `upgrade` | The record of a known network upgrade. |
| `tokens` | The built-in token list and the user's token settings. |
| `prices` | The Chainlink feed table, the Uniswap pools that price what no feed does, and the feed-first routing between them. |
| `safe` | The pinned Safe deployments, the queue of Safe transactions and the signing-request codec. |
| `swaps` | The pinned Uniswap v3 and CoW Protocol deployments, and the swaps whose next step waits for the user. |
| `cow` | CoW's order API, over Ktor, and the record of the CoW orders signed here. |
| `handoff` | `NoWalletHandoff`: the `WalletHandoff` of a platform that hands no payment link to another wallet (the desktop, iOS for now; issue #255). Android's is the host's. |
| `walletconnect` | WalletConnect v2 (docs/walletconnect.md): its crypto over libsodium, the relay client over Ktor's WebSocket client, the wallet role of the Sign protocol, and its state, the sessions' keys among it, in a file of its own. |
| `transfers` | The record of sent transactions and the history of them, kept for good. |
| `privacypools` | The native Privacy Pools library through JNA (`PrivacyPoolsNative`, docs/privacy-pools.md): Poseidon, the Lean IMT and the two circuits' provers, every buffer that crosses and may hold a secret cleared. No port yet: the use cases come in the plan's later parts. |
| `outbox` | Lenient mode's outbox (docs/lenient-mode.md): what is signed and waits for its check, a Safe signature held until its check among it, in a file sealed with a hardware key of its own, or in memory only where there is none (docs/keys-and-signing.md, *The outbox's seal key*). |
| `history` | Where each account's history starts. |
| `network` | What the device's connection costs: metered or not, as Android's `ConnectivityManager` and iOS's `NWPathMonitor` say; the desktop reports none. |
| `rates` | The exchange rate last verified, for the home-screen widget. |
| `logs` | The Debug tab's log, in memory only. The engine adapter writes each request into it. |
| `settings` | The display currency, the swap venue last chosen, the Debug tab's switch, the view chosen (simple mode or Detailed view), whether amounts are hidden, the sync policy (strict, or lenient: *Sign while syncing*), and whether the heavy downloads may use a metered connection. |

### `ui`

The shared Compose Multiplatform UI. Depends on `:domain` only.

- One package per screen: `<Feature>Screen` (stateless), `<Feature>Route`
  (binds the ViewModel), `<Feature>ViewModel`, `<Feature>UiState`.
- `components`: what several screens draw (blockies, the QR code, EIP-55).
- The theme.
- `App`: the one `NavHost` under the Accounts | Settings bar. One destination
  per screen; the destination's back-stack entry owns the screen's ViewModel.
  Settings opens over the Accounts tab's stack and closes back to it, with
  nothing saved aside (owner ruling, 2026-10-18, #49, for now).
- `AppGraph`: the composition root's contract. Ports in, ViewModel factories
  out.

The UI rules (previews, the greyed-out rule, screenshots) are in
`.claude/rules/ui.md`.

### The hosts

`android-app` (`eth.serotine`; the debug build is `eth.serotine.debug`),
`desktop-app`, and `ios-app` (`eth.serotine`; the SerotineKit framework and,
in `xcode/`, the SwiftUI shell's XcodeGen spec).

- Each host has ONE composition root: `SerotineApplication`, `main()`,
  `SerotineWallet`. It builds the `AppGraph` from `:data`'s adapters and
  renders `App` (on iOS through `MainViewController(wallet)`).
- The composition root also runs the app-wide work no screen owns:
  `FollowUpgradeReports`, `FollowSafeDeployments`, and on Android the
  exchange-rate widget's `FollowExchangeRate`. Never `App` or `AppGraph`.
- A port whose adapter is the platform's own output (`UpgradeNotifier`:
  Android's notifications and alarm, the desktop's tray, iOS's local
  notifications) is implemented in the host. So is a home-screen widget
  (Android's `RateWidget`, drawing `:ui`'s display-ready `rateWidgetContent`).
- The desktop app runs once per user: it holds an `InstanceLock` on its data
  directory for the process's lifetime.

More in `.claude/rules/hosts.md`.

## The dependency rule: arrows point inward only

- `:domain` depends on nothing of ours.
- `:ui` and `:data` depend on `:domain`.
- The hosts depend on `:ui` and `:data`. They are the only places that know
  which adapter implements which port.

Each of these is a finding: a screen reaching for a repository; a use case
importing a Compose or Android type; `:data` depending on `:ui`.

## MVVM

- A screen is a pure function of an immutable UiState: a sealed interface or
  a data class whose leaves are display-ready (strings, enums). A UiState
  never holds a `Flow`, a repository or a platform type.
- A ViewModel is the multiplatform `androidx.lifecycle.ViewModel`. It exposes
  `StateFlow<UiState>`, derived from use cases with
  `stateIn(viewModelScope, WhileSubscribed(5s), ...)`. It handles intents by
  calling use cases. It holds no platform reference (no `Context`, no
  `Window`).
- A ViewModel depends on use cases, never on a repository.
- Errors the user must see are UiState variants, not escaping exceptions.

## The Myotis boundary

docs/embedding-myotis.md has the details.

- All chain data comes from the embedded engine (`eth.myotis:myotis-engine`,
  resolved from Myotis's `maven-repo` branch, declared in
  `settings.gradle.kts`).
- Use the `io.myotis.api` contract and only that contract. No engine
  internals. The library's default ports, `io.myotis.engine.ports`, are part
  of its public surface and may be used.
- The engine is a data source, so its adapter lives in `:data`
  (`eth.serotine.data.engine`) and implements domain ports.
- `:domain` and `:ui` never import `io.myotis.*`.
- The composition roots own the engine's lifecycle: one engine per process.
- History: the library landed in Myotis with its PR #654 (2026-10-15). The
  first Myotis `v*` tag after it publishes the artifacts; until then the
  repository entry is inert.

## Dependency injection

Manual constructor injection from the composition roots. A missing dependency
is a compile error. No service locator, no static singletons, no DI framework.
A DI framework comes only when the graph is too large to read in one file, and
that is the owner's decision, not a drive-by dependency.

## SOLID, as docs/architecture.md spells it out

- **Single responsibility.** One ViewModel per screen, one use case per
  capability, one adapter per data source.
- **Open/closed.** Vary behaviour through new port implementations and new
  UiState variants, not through flags.
- **Liskov.** An adapter honours its port's documented contract in full,
  including the documented failure behaviour.
- **Interface segregation.** Small ports, each named for one concern. No
  `WalletRepository` catch-all.
- **Dependency inversion.** `:domain` defines the ports; `:data` depends on
  them, never the other way round.

## Tests per layer

All of them run in `./gradlew build`.

| Layer | Test | Where |
|---|---|---|
| `:domain` | value objects and use cases, with fake ports | `domain/src/commonTest` |
| `:data` | adapters, against the port's contract | `data/src/commonTest` |
| `:ui` ViewModels | state machines, with `runTest` and `Dispatchers.setMain` | `ui/src/commonTest` |
| `:ui` screens | the stateless screens, headless with `createComposeRule` | `ui/src/desktopTest` |

A test that needs a device or the network is `@Ignore`d by default and
documented.

## Adding a feature

Follow the checklist at the end of docs/architecture.md, in this order:

1. Domain first: the entity or value object, the port, the use case. Tests
   with a fake port.
2. The adapter in `:data`, tested against the port's contract.
3. UiState and ViewModel. Test the state transitions.
4. The stateless screen with a UI test and a preview per state, and the Route.
5. Wire it: `AppGraph` gets the ViewModel factory; the hosts supply the new
   adapter in their composition roots.

The PR describes the feature along these steps, so a reviewer can check each
layer.

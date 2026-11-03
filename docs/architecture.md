# Architecture

Serotine is laid out as a clean architecture with MVVM in the presentation
layer. This page is the contract every change is held to (the reviewer reads
it through CLAUDE.md); the Accounts tab listing the managed accounts, an
account's own screens, and the recovery-phrase screen that adds one show each
rule in code.

## The modules and the dependency rule

```
                 ┌──────────────┐        ┌──────────────┐
                 │ android-app  │        │ desktop-app  │      hosts, with ios-app: composition roots
                 └──────┬───────┘        └──────┬───────┘
                        │  build AppGraph from   │
                        ▼  :data, render :ui     ▼
        ┌──────────────────────┐        ┌──────────────────────┐
        │         ui           │        │         data         │
        │ screens, ViewModels, │        │ adapters behind the  │
        │ UiState, AppGraph    │        │ ports (Myotis, …)    │
        └──────────┬───────────┘        └──────────┬───────────┘
                   │                               │
                   ▼                               ▼
               ┌──────────────────────────────────────┐
               │                domain                │
               │  entities, value objects, ports,     │
               │  use cases — pure Kotlin             │
               └──────────────────────────────────────┘
```

**Dependencies point inward, and only inward.**

- `:domain` depends on nothing of ours and on no framework. Its libraries are
  coroutines' `Flow` and kotlincrypto's Keccak-256 (`sha3`), both pure Kotlin
  on every target. Keccak-256 is there by the owner's ruling (2026-10-18,
  #50), widened (owner ruling, 2026-11-03, on #218) from a closed list of
  uses to any of Ethereum's hashing, as pure computation: every signing
  digest (EIP-1559, EIP-191, EIP-712 for a Safe transaction, a CoW order or
  a dApp's typed data), transaction hashes, CREATE2 addresses, EIP-55, ABI
  selectors and event topics. The digest a signer signs is computed here,
  where the use case can show what it is the hash of. It is the one library
  besides coroutines, and any other still needs a ruling. It has
  no Android target on purpose: Android
  consumers resolve its JVM variant, as they do kotlinx-coroutines, so nothing
  in it can ever import `android.*` (and that variant emits Java 17 class
  files, since Android dexes it). What it cannot do in pure Kotlin it asks a
  port for: secp256k1 recovery (`SignatureRecovery`), JSON
  (`SigningRequestCodec`).
- `:data` depends on `:domain`. It implements the ports (`AccountRepository`,
  `KeyStore` and `Signer`, `KnownUpgradeStore`, `TokenCatalog`, `UserTokens`,
  `DisplayCurrencySetting`, `DebugScreenSetting`, `DebugLog` (in memory, written by the
  engine adapter through `LogSink`), `SentTransfers`, and `NativeBalances`, `TokenBalances`,
  `TokenMetadata`, `PriceFeeds`, `AddressKinds`, `LightClient`,
  `NetworkUpgrades`, and for sending `TransactionInputs`, `Broadcaster`,
  `ReceiptTracker` and `NameResolver`, over the embedded Myotis engine; for a
  Safe's transactions `SafeTxQueue` and `SigningRequestCodec`
  (`eth.serotine.data.safe`), `SignatureRecovery` (`eth.serotine.data.keys`,
  libsecp256k1) and `SafeApprovals`, over the engine; for creating a Safe
  `SafeReleases`, the pinned release in `eth.serotine.data.safe`; for an
  account's history `TransactionHistory`, `HistoryStarts`
  (`eth.serotine.data.history`) and `LogIndex`, over the engine) and owns
  every data source.
- `:ui` depends on `:domain`. A ViewModel takes **use cases**, never a
  repository, and never sees `:data`.
- The hosts depend on `:ui` and `:data` and are the **only** places that know
  which adapter implements which port. Each has one composition root
  (`SerotineApplication` on Android, `main()` on desktop) that builds an
  `AppGraph` and hands it to `App`. A port whose adapter is the platform's own
  output rather than a data source — `UpgradeNotifier`, the notifications —
  is implemented in the host itself, since it needs the host's activity, icon
  or tray; the decision what to show stays a use case in `:domain`. The same
  goes for a seam an adapter needs from the host's window: the biometric prompt
  the key adapter's `BiometricPrompter` asks for is the Android host's
  `PlatformBiometricPrompter`, shown on the activity in front.

Anything that violates the arrows — a screen reaching for a repository, a use
case importing a Compose or Android type, `:data` depending on `:ui` — is a
review finding, whatever it saves.

## Packages

| package | contents |
|---|---|
| `eth.serotine.domain.model` | entities and value objects (`Account`, `Address`, `AccountKind`, `SafeTx` and its `SigningRequest`, `SafeRelease`) and the encodings computed here (`SafeCreation`: `setup`, `createProxyWithNonce` and the CREATE2 address) — immutable, validated in `init`/factory |
| `eth.serotine.domain.repository` | the ports, as interfaces named after what they manage |
| `eth.serotine.domain.usecase` | one class per user-visible capability, `operator fun invoke`; what several share is internal there (`CallQuoter` and `signAndSend`, the quote and the send of any transaction from an account the wallet holds: a transfer, a Safe transaction's approval or execution, a Safe's deployment) |
| `eth.serotine.data.<area>` | adapters, one package per area (`account`, `keys`, `engine`, `upgrade`, `tokens`, `prices`, `safe` — the pinned deployments and the release a Safe is created from, the queue of Safe transactions and the signing requests' text —, `settings`, `transfers`, `history` — where each account's history starts —, `rates` — the exchange rate last verified, for a home-screen widget —, `logs` — the Debug tab's log, in memory, and the `LogSink` adapters write it through —, `files`) |
| `eth.serotine.ui.<feature>` | one package per screen: `<Feature>Screen`, `<Feature>ViewModel`, `<Feature>UiState` (`send` holds one screen with two steps, compose and review; `safepropose` is Send from a Safe, on the same transfer form; `transferstatus` follows a transaction once sent; `safetx` is one Safe transaction with its signing, approval and execution steps; `safeimport` imports a signing request; `createsafe` holds one screen with three steps, owners, threshold and review) |
| `eth.serotine.ui.activity` | an account's Activity, a tab of its detail rather than a screen, so with no ViewModel of its own: `AccountActivity`, which `AccountDetailViewModel` reads while the tab is shown, `ActivityUiState` and the list's items (`activityItems`), which `AccountDetailScreen` draws in the tab |
| `eth.serotine.ui.ratewidget` | what a host's home-screen widget shows of the exchange rate last verified, display-ready (`rateWidgetContent`), which the host draws in its own widget toolkit; and the widget's configuration (`WidgetPairScreen`: network, price, currency), whose steps the host computes: no ViewModel |
| `eth.serotine.ui.debug` | the Debug tab (`DebugScreen`, `DebugViewModel`, `DebugUiState`), and `DebugTabViewModel`, which tells `App` whether the bar shows the tab |
| `eth.serotine.ui.lightclient` | `LightClientViewModel`, the light client's status strip as the screens that depend on it draw it: no screen of its own |
| `eth.serotine.ui.components` | stateless composables more than one screen draws (the blockie, an account's avatar, a token's icon, a QR code, the long-press menu, the transfer form, the unlock that signs, a Safe transaction's decoded action, a signing request shared) and the display helpers and display-ready types they share (EIP-55, amounts, the light client's line, the seed screens' password step, the transfer form's inputs and texts, what a Safe transaction does and why one is refused); no ViewModel, no use case |
| `eth.serotine.ui.platform` | the shared UI's `expect`/`actual` seams (keeping a window out of screen capture, keeping the display on, the clipboard, the share sheet, dates in the user's locale) |
| `eth.serotine.ui.theme` | Material 3 theme |
| `eth.serotine.android`, `eth.serotine.desktop`, `eth.serotine.ios` | the hosts |

## MVVM, the contract

- **A screen is a pure function of its UiState.** `AccountsScreen(state)` renders
  any `AccountsUiState` and holds no state of its own beyond transient UI details
  (scroll position, an expanded row). A UiState is a sealed interface or an
  immutable data class; its leaves are display-ready values (strings, enums),
  never a `Flow`, a repository or a platform type.
- **One `<Feature>Route` binds the screen to its ViewModel** —
  `collectAsStateWithLifecycle()`, plus the glue between the screen's lifecycle
  and its ViewModel: a state that ends the screen (an import that succeeded)
  becomes its navigation callback, and a screen that stops being shown becomes
  an intent (the seed screen drops its words); nothing else. It is the only
  composable that knows the ViewModel exists, which keeps every other screen
  renderable in a test with a hand-made state.
- **Every screen has previews.** `<Feature>ScreenPreviews.kt`, next to the
  screen, holds a `@Preview` (the multiplatform
  `org.jetbrains.compose.ui.tooling.preview.Preview`) per state the screen
  draws differently, plus one in the dark theme: the screen in
  `SerotineTheme`, fed a hand-made UiState with made-up data, as its UI
  tests are. Android Studio renders them on the Android target, and
  `<Feature>ScreenPreviewsTest` renders each one in the build.
- **Navigation is one `NavHost`, in `App`**, with a destination per screen. A
  screen's ViewModel is created by `viewModel {}` inside its destination, so
  the destination's back-stack entry owns it: it survives configuration
  changes while the screen is on the back stack and is cleared when the
  screen is popped. Routes say where the user wants to go through callbacks
  (`onAddAccount`, `onBack`); only `App` holds the `NavController`. The one
  exception is the update screen: its ViewModel decides whether the app shows
  anything else, so `App` creates it in the host's scope and navigates to the
  screen, clearing the back stack (both tabs' stacks included), while an
  update is required, and to the Accounts tab's start once it no longer is.
  The light client's status strip is the other ViewModel `App` creates in
  the host's scope (`LightClientViewModel`): one for every screen that draws
  the strip, so each of those screens' ViewModels and UiStates stay about
  what the screen does. `App` hands it to those screens' routes, which
  collect it beside their own ViewModel and give the screen its
  display-ready `LightClientItem`; Accounts and Settings keep the light
  client's state in their own UiState.
- **App-wide work runs in the hosts, not in `App`.** What no screen owns is a
  use case the composition root builds from its ports and runs for the
  process's life, beside the `AppGraph` (ports in, ViewModel factories out):
  `FollowUpgradeReports`, which notifies of a network upgrade, and
  `FollowSafeDeployments`, which lists the Safes this wallet deployed
  whatever screen is open (docs/wallet-ux.md, screen 15). On Android both run
  while the application holds the engine; on the desktop, while `main()`
  runs; on iOS, while the app is in the foreground. `FollowDebugScreen`, which
  keeps the debug log recording only while the Debug tab is on and empties it
  when the tab is turned off (screen 27), runs for the life of the process on
  every host: it needs no engine.
- **Two tabs, Accounts | Settings, under a bottom bar** — a `NavigationBar`, and
  a `NavigationRail` on a wide desktop window — around that one `NavHost`.
  Each tab is a nested graph. Settings opens over the Accounts tab's stack and
  closes back down to it, so switching tabs keeps the user's place in Accounts
  with no stack saved aside to go stale (owner ruling, 2026-10-18, #49: this
  tab model stands for now); re-selecting the current tab returns
  to its start. A third tab, Debug, joins the bar while the user has it
  turned on in Settings, and follows the same model: it opens over the
  Accounts tab's stack in Settings' place (one of the two at a time), and
  `App` reads whether it shows from `DebugTabViewModel`, created in the
  host's scope like the update screen's. A route's arguments are identifiers only
  (`account/{address}`), never an object: the destination's ViewModel loads
  what it shows through its use cases. The bar shows on the tabs' screens; a
  flow that ends in a key operation or a transaction (import, create, send,
  a Safe transaction, create Safe) is a destination of its own that covers it, and so is
  the update screen, outside both tabs. The screens themselves are
  docs/wallet-ux.md.
- **A ViewModel exposes `StateFlow<UiState>` and intent methods.** It derives
  state from use cases with `stateIn(viewModelScope, WhileSubscribed(5s), …)`,
  handles user intents by calling use cases, and holds no platform reference
  (no `Context`, no `Window`). It is the multiplatform
  `androidx.lifecycle.ViewModel`, created through `viewModel { graph.… }` so
  the host's `ViewModelStoreOwner` scopes it.
- **Use cases are the UI's only door into the domain.** A ViewModel's
  constructor lists what the screen can do; adding a capability adds a use
  case, not a repository call.
- **Errors are state.** A failure the user must see becomes a UiState variant
  (`Error(message)`), not an exception that escapes a coroutine.

## SOLID, as it applies here

- **Single responsibility** — a ViewModel per screen, a use case per
  capability, an adapter per data source. A class whose name needs "and" is
  two classes.
- **Open/closed** — behaviour varies through new implementations of a port or
  new UiState variants, not through flags threaded through existing code.
- **Liskov** — an adapter honours its port's contract in full, including the
  documented failure behaviour (`observeAccounts()` never fails; a port that
  can fail says so in its KDoc and its callers handle it).
- **Interface segregation** — ports are small and named for one concern.
  `AccountRepository` manages accounts; signing, balances and Safe
  transactions get ports of their own rather than one `WalletRepository`.
- **Dependency inversion** — `:domain` defines the ports; `:data` depends on
  them, never the other way round. No service locator, no static singletons:
  constructor injection from the composition root. (Manual wiring, no DI
  framework, until the graph is too large to read in one file — then a
  decision for the owner, not a drive-by dependency.)

## Kotlin conventions

- Immutable by default: `val`, `data class`, `List` not `MutableList` in any
  signature; `copy` for change.
- Value classes for domain identifiers (`Address`), validated at construction
  so an invalid one cannot exist.
- Structured concurrency only: `viewModelScope`, `runTest` in tests, never
  `GlobalScope`; `Flow` for streams, `suspend` for one-shot calls; no blocking
  on the main thread.
- `expect`/`actual` for the few platform seams, in the smallest source set
  that needs them; the common code never branches on the platform.
- KDoc on every public type: what it is, what it guarantees, what it never
  does. Comments say why; the code says what.
- Formatting: the official Kotlin style (`.editorconfig`), 120 columns,
  trailing commas on multi-line declarations and calls.

## Tests, per layer

| layer | kind | where |
|---|---|---|
| `:domain` | pure unit tests of value objects and use cases with fakes | `domain/src/commonTest` |
| `:data` | adapter tests against the port's contract | `data/src/commonTest` |
| `:ui` ViewModels | state-machine tests with `runTest` + a fake use case input, `Dispatchers.setMain` | `ui/src/commonTest` |
| `:ui` screens | headless Compose UI tests of the stateless screens (`createComposeRule`) | `ui/src/desktopTest` |
| hosts | the composition root wires and the app boots (an instrumented smoke test once there is an engine to boot) | per host |

`./gradlew build` runs all of them on the desktop JVM and as Android unit
tests. A test that needs a device or a network is `@Ignore`d by default and
documented.

## Adding a feature — the checklist

1. **Domain first.** The entity or value object, the port it needs (or an
   extension of one), the use case. Tests with a fake port.
2. **Adapter.** Implement the port in `:data`; test it against the port's
   contract.
3. **UiState + ViewModel.** The sealed state, the ViewModel mapping use-case
   output to it, the intents. Test the state transitions.
4. **Screen + Route.** The stateless screen, a UI test and a preview per state,
   the Route.
5. **Wire it.** `AppGraph` gets the ViewModel factory; the hosts supply the
   new adapter in their composition roots.
6. The PR describes the feature along these five lines, so a reviewer can
   check each layer against this page.

# Kotlin idiom review

A snapshot review of how idiomatic Serotine's Kotlin is, taken on 2026-11-01 at
commit 7a7994c4 (`main`). It changes no code. Every finding names the files and
lines it was read at, so each can be checked, fixed or dismissed on its own.
Whether anything here becomes work is the owner's call.

## What was reviewed and how

- Scope: every Kotlin source of the repository. 818 `.kt` files and the eight
  `.kts` scripts, 132k lines: 80.5k in the main source sets of `domain`, `data`,
  `ui`, `android-app`, `desktop-app` and `ios-app`, 50.5k of tests, and 1.1k of
  Gradle scripts.
- Method: five reading passes, one per area (domain, data, the `ui` ViewModels
  and UiStates, the `ui` composables, the tests), plus the hosts and the build
  scripts, and a set of repository-wide counts (grep heuristics and a
  brace-matching function-length scan). The main source sets were read in full;
  of the 48 preview files and the 97 Compose screen tests a sample was read in
  full and the rest skimmed for the pattern. A second pass re-checked a sample
  of every area's citations against the files.
- Yardstick: the Kotlin coding conventions, kotlinx.coroutines and Flow
  practice, the Compose API guidelines, and the repository's own rules in
  `.claude/rules/platform.md`, `architecture.md` and `ui.md`. Where the two
  differ, the repository's rule wins and the text says so.
- Severity: **High** is non-idiomatic in a way that risks a bug or breaks a
  stated convention of this repository. **Medium** is what a Kotlin reviewer
  would ask to change. **Low** is style, consistency and polish.
- Finding ids: `X-` cuts across modules, `D-` domain, `A-` data (the
  adapters), `V-` ViewModels and UiStates, `C-` composables, `T-` tests, `H-`
  hosts, `G-` Gradle.

## The verdict

The code is idiomatic, modern Kotlin, and unusually disciplined at the line
level. Results are sealed interfaces, not exceptions: no port throws, no
`kotlin.Result` appears in a signature, and `when` over those results is the
dominant control flow. Nullability and mutability are exemplary: seven `!!` in
80k lines of main code, no `lateinit`, no `MutableList` in a declared type, no
wildcard import. Coroutine hygiene is careful: structured concurrency only,
`CancellationException` rethrown before every broad catch that could see one,
`NonCancellable` only around steps that must complete, `Mutex` and
`MutableStateFlow.update {}` instead of `synchronized`. KDoc covers every one of
the 795 public top-level declarations, and it says what a type guarantees and
which ruling it follows. The Compose layer follows the stated MVVM shape
closely: screens are pure functions of display-ready state, and no ViewModel
holds Compose state.

The weaknesses are structural, not line-level. The biggest are these.

1. Trust-critical logic exists in several copies. The review/sign/hide/requote
   machinery is re-implemented in eight ViewModels and has started to drift.
   The engine adapter exists as two hand-copied 800-line hosts. The hosts
   inline the same failure-reporting block twenty times. Compose fragments and
   preview sample data are copy-pasted across files.
2. The stated convention "value classes for identifiers" stops at addresses,
   amounts and digests. Transaction and block hashes, topics and selectors
   travel as plain `String` through every layer.
3. Nothing enforces the stated style. `.editorconfig` declares
   `ktlint_official`, but no formatter or linter runs in the build, and the
   drift that a formatter would catch is already measurable.
4. The largest units are too large to review comfortably: 14 main files over
   800 lines, 70 functions over 60 lines, including 200-line quote use cases
   in the code that moves money and a 457-line navigation composable.
5. A few flow pipelines write back into their ViewModel from inside
   `combine`/`scan`/`map` lambdas, and a few `@Volatile` fields in common data
   code are updated with non-atomic read-modify-write.

Everything else is polish: `Pair` and `Triple` where a data class would name
the parts, `else ->` on sealed subjects, Java-flavoured collection construction,
`println` as the logging facility, and time kept as `Long` seconds with the
unit sometimes in the name and sometimes not.

## The numbers

All counts are over the main source sets unless the row says tests.

| Measure | Count |
|---|---|
| Files / lines | 818 `.kt` + 8 `.kts` / 132k (80.5k main + 50.5k tests + 1.1k Gradle) |
| Main files over 800 lines / over 1,000 lines | 14 / 5 |
| Block-bodied functions / over 60 lines / of those, composables | 1,325 / 70 / 50 |
| Public top-level declarations / without KDoc | 795 / 0 |
| Internal top-level declarations / without KDoc | 368 / 14 |
| `!!` / `lateinit` / `GlobalScope` / wildcard imports | 7 / 0 / 0 / 0 |
| `MutableList`, `MutableMap` or `MutableSet` in a declared type | 0 |
| Lines over 120 columns (main / tests) | 17 / 105 (81 in one test-vector file) |
| `catch (e: Exception)` or `Throwable` / paired with a `CancellationException` rethrow | 173 / 73 |
| `ensureActive()` after a broad catch (data) / `NonCancellable` sites (data / ui) | 15 / 18 + 12 |
| `return@label` / of those `return@cancellingReads` in domain use cases | 319 / 171 |
| `Pair<` or `Triple<` in a declared type | 33 (+ ~30 built with `to`) |
| Value classes / `hash…: String` declarations in domain | 7 / 29 |
| Sealed interfaces / `data object` / `data class` | 210 / 326 / 757 |
| `stateIn` with `WhileSubscribed(5 s)` / `Eagerly` (ui) | 39 / 6 |
| `MutableStateFlow` `update {}` / `.value = x.copy(...)` (ui) | 110 / 37 |
| `@Volatile` (data / ui) / `synchronized` (desktop code only) | 18 / 1 / 4 |
| `println`, `System.err` or `NSLog` as logging (data + hosts) | 28 + 25 |
| Files declaring more than one top-level type | 149 of 390 |
| ktlint, detekt or spotless tasks in the build | 0 |

## Findings that cut across modules

### X-01 No formatter or linter runs; the declared style is drifting — High

- `.editorconfig` declares `ktlint_official`, 120 columns and trailing commas
  "for any formatter the repository adopts later". No Gradle task checks it.
- Measured drift: 17 main lines over 120 columns (6 in data, 7 in ui
  composables, 3 in ViewModels, 1 in domain); import order against the
  official layout in 8 domain files and 5 ui files, 4 unsorted groups more;
  about 12 mis-indented lambda bodies and wraps in data
  (`VerifiedNativeBalances.kt:61-69`, `EngineTransactionInputs.kt:41-50`,
  `NetworkRouted.kt:101-103`), 4 in ViewModels
  (`SafeSwapViewModel.kt:298-302`, `AccountDetailViewModel.kt:315-320`); 8
  double blank lines in ViewModels; one unused private property
  (`RenameAccountViewModel.kt:25`).
- Trailing commas, no semicolons, no wildcard imports, 4-space indent and
  no tabs: clean throughout. The drift is small today.
- Idiomatic form: one `ktlint --format` pass, then `ktlintCheck` (the
  `org.jlleitschuh.gradle.ktlint` or `org.jmailen.kotlinter` plugin) in
  `./gradlew build`, as the `.editorconfig` anticipates. Every style finding
  below marked "ktlint" disappears with it.

### X-02 Transaction and block hashes, topics and selectors are plain `String`s — High

- The rule (`platform.md`, *Kotlin conventions*): "Value classes for
  identifiers, validated at construction, so an invalid one cannot exist."
  `Address`, `Wei`, `TokenUnits`, `Digest`, `CowOrderUid`, `SlippageTolerance`
  and `PriceId` follow it. Hashes do not: 29 `hash…: String` declarations in
  domain, carried through data and ui.
- Where: `domain/.../model/Transfer.kt:26`,
  `repository/ReceiptTracker.kt:18` (`observe(network, hash: String)`),
  `repository/Broadcaster.kt:23` (`Sent(val hash: String)`),
  `repository/TransactionHistory.kt:32`, `repository/LogIndex.kt:94-101,
  142-143` (topics validated by a regex in `init`, `blockHash`,
  `transactionHash`), `model/SafeEvents.kt:13, 19, 46` (`safeTxHash`,
  `messageHash`: the same 32 bytes `SigningRequest.hash: Digest` holds), the
  twelve `*Quoting.EarlierMaybeSent(val hash: String)` leaves,
  `model/SafeTxAction.kt` (`selector: String?`).
- Why it matters: `ReceiptTracker.observe` and `TransactionHistory.settle`
  accept any string; a Safe event's hash cannot be compared with a signing
  request's `Digest` without hand-written `"0x" + hex` glue; the one shape
  check is a regex in a query's `init`.
- Idiomatic form: `@JvmInline value class TxHash private constructor(val hex:
  String)` with `parse`/`of` as `Address` has (`model/Address.kt:14-31`), a
  `Topic` and a `Selector` the same way, or `Digest` reused for the Safe
  event hashes.

### X-03 Logic that should exist once exists in copies — High

Each copy is listed under its module; together they are the review's main
structural finding, because the copies carry trust-critical behaviour and have
already begun to differ.

- V-02: the review/sign/hide/requote machinery in eight ViewModels
  (`biometricsOffered()` in 8 files, `requoteIfHidden()` in 8, `safely()` in 6,
  `retry()` byte-identical in Send, CreateSafe and SafeTx); the copies disagree
  on what `idle` means and have four `onHidden` variants.
- A-01: two `MyotisHost`s (jvmSharedMain 839 lines, iosMain 794) that share
  about 320 to 450 identical lines and two concurrency models; a KDoc has
  already come loose in one (`iosMain/.../MyotisHost.kt:472-476`).
- H-01: the "rethrow `CancellationException`, report everything else" block
  inlined about 20 times in the Android and desktop hosts while the iOS host
  has the helper (`SerotineWallet.kt:380-388`).
- A-07: `WORD_HEX_DIGITS` defined 5 times, `UNKNOWN_BLOCK` 4, `REFUSED` 3,
  `val JSON = Json` in 15 companions, `report()` 4 times, two hex codecs.
- C-04: capture-warning text in 5 files, spinner-in-button 25 times in 16
  files, the refresh-quote button 9 times, the password pair twice, identical.
- C-09: preview sample addresses re-declared in 21 files; sample accounts
  rebuilt per file.
- V-03 and the data count: 73 hand-rolled `catch (e: CancellationException)
  { throw e }` blocks and six identical `NonCancellable` backstops, none
  through a shared helper.

### X-04 `Pair` and `Triple` where a data class would name the parts — Medium

- 33 declared sites in main code, plus about 30 built with `to`, in every
  module. The signal at the use site is `.first`/`.second`.
- Where: `domain/.../model/UniswapV3Calls.kt:338` (`Pair<SwapRoute,
  Pair<UInt256, UInt256>>?`), `usecase/FindUniswapRoute.kt:161, 176-180`,
  `usecase/CowOrderEnds.kt:89` (`Triple` through `combine`);
  `data/.../logs/MemoryDebugLog.kt:33, 79` (the public `LogSink.log(...,
  List<Pair<LogLevel, String>>)`), `engine/AddressActivity.kt:88, 117,
  223-226`, `cow/HttpCowApi.kt:262` (read as `refusal.second` at `:141-144`),
  `swaps/UniswapDeployments.kt:81-84` (four `Pair<String, String>` meaning
  address-and-hash); `ui/.../swap/SwapViewModel.kt:300, 321, 694-696, 1671`
  (`Pair<Int, Int>` step positions; the domain's `WaitingSwap` already names
  them `step`/`steps`), `activity/ActivityRows.kt:100-104, 203, 322, 346`,
  `watch/WatchViewModel.kt:547, 570`, `components/TransferInputs.kt:243`;
  `android-app/.../SerotineApplication.kt:261-273` (`rateReads` as a `Pair`
  then destructured).
- Idiomatic form: a small, often private, `data class` (`StepPosition(number,
  of)`, `Pin(address, codeHash)`, `Refusal(why, errorType)`, `FieldNote(text,
  isError)`). The CoreFoundation `vararg entries: Pair<Pointer, Pointer>`
  dictionaries and `combine(..., ::Pair)` glue that is destructured on the
  next line are fine.

### X-05 Units too large to review comfortably — Medium

- 14 main files over 800 lines: `SwapViewModel.kt` 1,707, `SafeTxViewModel.kt`
  1,291, `SafeSwapViewModel.kt` 1,104, `CreateSafeViewModel.kt` 1,072, `App.kt`
  1,045, `AccountDetailScreen.kt` 1,013, `SerotineApplication.kt` 968,
  `SafeSettingsViewModel.kt` 966, `SafeTxSigning.kt` 891, `SafeProposeViewModel.kt`
  879, `EnvelopeKeyStore.kt` 856, `SwapScreen.kt` 841, jvmShared
  `MyotisHost.kt` 839, `SendViewModel.kt` 826.
- 70 block-bodied functions over 60 lines. Outside composables: `openWallet`
  248 (desktop `Main.kt:136`), `ProposeSafeSwap.invoke` 207
  (`SafeSwaps.kt:235-441`), `QuoteSwap.invoke` 169 (`Swaps.kt:172-340`),
  `QuoteCowSwap.invoke` 153 (`CowSwaps.kt:146-298`), `TransferStatusViewModel.render`
  117, `AccountsViewModel.simple` 111, `CallQuoter.quote` 109
  (`Calls.kt:208-316`), `SafeSwapViewModel.sign` 100,
  `VerifiedPriceHistory.search` 100, `VerifiedPoolPrices.average` 98.
  Composables: `Destinations` 457 (`App.kt:226`), `SwapScreen` 199,
  `AccountDetailScreen` 199, `SettingsScreen` 160.
- Idiomatic form: split along the comments the code already has (the quote
  use cases: "the floor", "the allowance", "only now does anything reach
  CoW"), a private function per phase returning a small sealed step, as
  `FindUniswapRoute` does with `PoolRead`/`Quoted`. X-03's shared machinery
  halves the ViewModels; C-01 deletes most of `Destinations`.

### X-06 One helper for the cancellation-safe backstop, and one policy — Medium

- The idiom itself is right and applied consistently: 73 `catch (e:
  CancellationException) { throw e }` before a broad catch, 15
  `ensureActive()` after a `catch (e: Throwable)` around a JNA or cinterop
  call, 36 `withContext(NonCancellable)` around steps that must complete. The
  100 broad catches without a paired rethrow were sampled and are those
  shapes, each commented (`SendViewModel.kt:555-558`, `MyotisHost.kt:206-212`,
  `JsonSigningRequestCodec.kt:39`).
- What is missing is the helper: `suspend inline fun <T> backstop(fallback:
  T, block: suspend () -> T): T` and one `reportingFailure(what) { }`, so the
  comment "the use case promises not to throw; the backstop" lives in one
  place. Eight ViewModels call use cases with no backstop at all
  (`DeriveAccountViewModel.kt:93`, `RemoveAccountViewModel.kt:77`,
  `RenameAccountViewModel.kt:56`, `AddOnNetworksViewModel.kt:97`,
  `CurrencyViewModel.kt:51`); decide once whether a broken promise is caught
  or crashes.

### X-07 `println`, `System.err` and `NSLog` are the logging facility — Medium

- 28 call sites in 16 data files and about 25 in the hosts, behind four
  private `report()` helpers that each wrap `println`.
- Where: `data/.../files/VersionedJsonFile.kt:259`, `keys/EnvelopeKeyStore.kt:854`,
  jvmShared `MyotisHost.kt:249, 272, 498, 507, 839` (`System.err`), iosMain
  `MyotisHost.kt:783` (`NSLog` with its own `%` escaping),
  `desktopMain/.../MacBiometricKey.kt:54, 75, 108, 180`;
  `android-app/.../SerotineApplication.kt:152, 166, 416, 566, 711, 812, 842,
  861, 882` (`System.err` on Android; `Log.d` only for the engine's debug log
  at `:136`);
  `ios-app/.../SerotineWallet.kt:253, 386, 409` (`println`, although the same
  file says at `:126-127` that `println` reaches no one on a device);
  `desktop-app/.../Main.kt:411, 425, 427`, `TrayUpgradeNotifier.kt:33, 49`.
- Idiomatic form: the seam exists already. `MyotisHost` and
  `VerifiedAddressKinds` take `debugLog: ((String) -> Unit)?`, and
  `MemoryDebugLog` implements `LogSink`. Give the file adapters, the key store
  and the hosts' followers the same function, defaulting to `::println`, so
  each host sends diagnostics where its log is read: `Log.w` on Android,
  `NSLog` on iOS, stderr on the desktop.

### X-08 `else ->` on sealed and enum subjects defeats exhaustiveness — Medium

- About 25 of the domain's 42 `else ->` branches sit on a sealed or enum
  subject; the ViewModels add eight `error("unreachable")` branches that exist
  only because a result type mixes outcomes and refusals.
- Where: `domain/.../usecase/Swaps.kt:261-264` and `SafeSwaps.kt:302-305`
  (`when (finding)` over `RouteFinding` with `else -> return
  finding.asSwapQuoting()`), `FindUniswapRoute.kt:196-204` (`Simulation`),
  `Calls.kt:381-384, 395-399` (`Signing`, `Broadcast`), `CowSwaps.kt:483-486`,
  `SafeTxSigning.kt:525-528`, `SafeCreation.kt:293-296` (`AddressKind`),
  `SafeSwaps.kt:50-54, 97-101`, `Transfers.kt:299-303`,
  `TransactionHistories.kt:218-222`; `ui/.../swap/SwapViewModel.kt:1503-1506,
  1547-1550, 1603-1606`, `SafeSwapViewModel.kt:993`, `SendViewModel.kt:741`,
  `CowOrderViewModel.kt:435-436`.
- Idiomatic form: list the remaining variants, as `CowCancellations.kt:162-170`
  and `RemoveAccount.kt:47-62` do; a nested `sealed interface Refusal :
  SwapQuoting` makes the ViewModels' `refusal(quoting)` exhaustive. The point
  of sealed results is that a new variant fails compilation at every consumer;
  `else` routes it silently to "not signed" or `null`.

### X-09 Java-flavoured collection construction — Low

- About 45 sites: `mapTo(HashSet())`, `filterTo(LinkedHashSet())`,
  `LinkedHashMap<K, V>()`, `ArrayList<Long>(n)` filled with `+=`,
  `toMutableList().apply { set(i, ...) }`.
- Where: `domain/.../usecase/AccountLabels.kt:10`, `DeriveAccount.kt:56`,
  `ObserveUpgradeWarning.kt:163, 166`, `RateWidgetChoices.kt:28`,
  `SafeCreation.kt:166-167`; `data/.../tokens/FileUserTokens.kt:40, 58`,
  `networks/FileNetworkSettings.kt:62`, `engine/VerifiedPriceHistory.kt:47, 117, 180`,
  `engine/Abi.kt:180`, `account/FileAccountRepository.kt:97`,
  `transfers/FileTransactionHistory.kt:68, 82`;
  `ui/.../activity/AccountActivity.kt:259-267`,
  `activitydetail/ActivityDetailViewModel.kt:172-208, 227-263`,
  `safesettings/SafeSettingsViewModel.kt:644`, `components/QrMatrix.kt:439`
  (`ArrayList<Boolean>` as a bit buffer); `android-app/.../SerotineApplication.kt:315-316`.
- Idiomatic form: `map { }.toSet()` (insertion-ordered already),
  `mutableMapOf()`, `buildList(capacity) { }`, `map { if (it.id == id) new
  else it }` for a replacement (`InMemoryAccountRepository` does it that way),
  `BooleanArray` for bits.

### X-10 Time as `Long` seconds, with the unit sometimes in the name — Low

- 22 `Long` epoch fields in domain (`epochMillis`, `activationTime`,
  `updatedAt`, `readAt`, `sentAt`, `syncCommitteePeriodSeconds`); 11 `const
  val *_SECONDS` beside `Duration` constants (`Calls.kt:327, 334`,
  `CowSwaps.kt:50, 56`, `SafeTxSigning.kt:309`); 30 constructors take `clock:
  () -> Long = ::epochSecondsNow` (`usecase/Clock.kt:8`); mixed units in one
  companion (`SerotineApplication.kt:945-963`: `PAUSE_DELAY_MS = 30_000L`
  beside `RATE_READ_TIMEOUT = 4.minutes`; desktop `Main.kt:389`;
  `UpgradeCheckJob.kt:76` with `TimeUnit`).
- Chain timestamps are unix seconds and `kotlin.time.Instant`/`Clock` are
  still `@ExperimentalTime` in Kotlin 2.2.21 (`usecase/Clock.kt:7`), so a
  `Long` is defensible. The unit in the name is not optional, and durations
  that only feed `delay` or `withTimeoutOrNull` should be `Duration`.

### X-11 File layout against the stated conventions — Low

- 149 of 390 main files declare more than one top-level type, and 35 domain
  files are named after a topic: `SafeTxSigning.kt` holds 15 public types in
  891 lines, `SafeTxOnChain.kt` 12, `SafeCreation.kt` 12, `KeyStore.kt` 12,
  `Swaps.kt` 9 in 797 lines. The Kotlin style guide allows this; finding
  `ProposeSafeTx` in `SafeTxSigning.kt` or `QuoteTokenApproval` in `Swaps.kt`
  costs a search.
- `.claude/rules/ui.md` lists `<Feature>Route.kt` among a screen's files. No
  such file exists: 28 Routes live in `<Feature>Screen.kt`
  (`accounts/AccountsScreen.kt:90`, `send/SendScreen.kt:97`,
  `safetx/SafeTxScreen.kt:128`), and `WidgetPairUiState` lives in
  `ratewidget/WidgetPairScreen.kt:71-374`. Either move the Routes or change the
  rule's table.

## Domain (`domain/src/commonMain`, 146 files, 15.9k lines)

The most idiomatic module. 126 sealed interfaces with 237 `data object` and
423 `data class` leaves; 105 use cases as classes with constructor injection
and `operator fun invoke`; `@ConsistentCopyVisibility` with `internal
constructor` on all 17 quote types so only a use case can make one;
hand-written `equals`/`hashCode` on every `ByteArray` holder with the reason
stated; redacted `toString` on `Mnemonic` and `WalletPassword`; limb-based
`UInt256` with `...OrNull` arithmetic used everywhere money is computed; no
`java.*`, no `Dispatchers.IO`, no `@Volatile`. D-01 (hashes) is X-02 and D-04
(`else ->`) is X-08.

### D-02 Whole use-case bodies inside `cancellingReads { }` — Medium

- 171 of the module's 184 labelled returns are `return@cancellingReads`:
  `Swaps.kt` 38, `CowSwaps.kt` 38, `SafeSwaps.kt` 33, `Calls.kt` 18,
  `Transfers.kt` 15, `SafeTxSigning.kt` 13, `CowCancellations.kt` 13. The
  helper itself (`usecase/Calls.kt:343-349`, a `coroutineScope` that cancels
  the reads an early refusal no longer needs) is good.
- Idiomatic form: `suspend operator fun invoke(...) = cancellingReads {
  quote(...) }` with the body a private `suspend fun CoroutineScope.quote(...)`
  that uses plain `return`. A labelled return from a 200-line lambda reads
  like `continue` and hides which scope is being left; the named function also
  gives X-05's long bodies a seam.

### D-03 150 to 210-line `invoke` bodies in the quoting layer — Medium

- `SafeSwaps.kt:235-441` (207 lines, four `var`s, a nested `fun requestOf`
  filled across three levels of `when`), `Swaps.kt:172-340` (169),
  `CowSwaps.kt:146-298` (153), `Calls.kt:208-316` (109),
  `Transfers.kt:199-290` (92), `Swaps.kt:649-722` (74),
  `SafeTxSigning.kt:612-676` (65). Isolated to the quoting layer; the rest of
  the module is short functions.
- See X-05 for the shape.

### D-05 Results smuggled out through captured `var`s and casts — Medium

- `Swaps.kt:281-287` assigns `var returned` inside the `accepts` callback
  handed to `CallQuoter.quote`, then `checkNotNull(returned)` at `:321`; the
  same in `SafeCreation.kt:303-319`. `Swaps.kt:211-222` and
  `Transfers.kt:226-238` set `var tokenBlock` through `.also { }` although the
  sealed leaf already carries the block. `CowSwaps.kt:293` re-casts `reading
  as TokenBalanceReading.Verified` ninety lines after the `when` that matched
  it; `SafeCreation.kt:418` casts after `first { it is Verified }`;
  `FollowSafeDeployments.kt:63` casts after a filter; `Calls.kt:352` writes
  `fold(UInt256.ZERO as UInt256?)`.
- Idiomatic form: let `CallQuoting.Quoted` carry the accepted run's return
  (or let `accepts` return a value), destructure the leaf into `val`s,
  `filterIsInstance<KindReading.Verified>()`, `fold<UInt256, UInt256?>(ZERO)`.
  These are the only places in the module where reading order no longer
  matches data flow.

### D-07 One-shot reads as `observe().first()` — Low

- 66 `.first()` calls in 28 files on observe-only ports
  (`Calls.kt:192, 226`, `TransactionHistories.kt:176-177` on every nonce
  poll, `CowSwaps.kt:165-168`, `SafeTxSigning.kt:735, 742`).
- `first()` is right when only a `Flow` is exposed. The smell is the missing
  API: the record ports (`SentTransfers`, `CowOrders`, `AccountRepository`,
  `HistoryStarts`) could offer `suspend fun current()`, which also removes the
  "hangs if the adapter never emits" edge the port KDoc does not rule out.

### D-11 Exceptions from `init { require }` as parse control flow — Low

- `model/UniswapV3Calls.kt:273-279` (`catch (e: IllegalArgumentException)`,
  `catch (e: IndexOutOfBoundsException)`, `e` unused), `:302-304`, `:378`
  (`runCatching { SwapRoute(...) }.getOrNull()` on untrusted call data),
  `usecase/TokenSettingsChanges.kt:34-35`. All non-suspend, so no cancellation
  hazard.
- Idiomatic form: `catch (_: IllegalArgumentException)` (Kotlin 2.2) and an
  `orNull` factory beside the throwing constructor, as `Address.parse`/`of`
  pair. Decoding hostile bytes by catching the model's own `require` means any
  `require` added later silently becomes "not a swap".

### D-12 `PriceId` is the one value class with a public throwing constructor — Low

- `model/PriceId.kt:10-13` versus the private-constructor + `parse`/`of`
  shape of `Address`, `Wei`, `TokenUnits`, `Digest` and `CowOrderUid`. The
  hosts then validate with `runCatching { PriceId(it) }.getOrNull()`
  (`RateWidgetPairs.kt:28`, `RateWidgetConfigureActivity.kt:65`).

### D-14 Tri-state `Boolean?` — Low

- `usecase/SafeSwaps.kt:122, 125` (`expired`, `meetsMinimum`, null when the
  head's time is unknown). A small sealed or enum type says it in the type,
  as the rest of the module does for every other "unknown".

### D-15 Small stdlib idioms — Low

- `model/SwapRoutes.kt:59-66` (manual `var best` loop, `maxByOrNull`),
  `model/AbiWords.kt:110-115` (`ByteArray +=` in a loop),
  `usecase/ObserveUpgradeWarning.kt:184-192` (`all { it != null }` then
  `checkNotNull`, `mapNotNull`), `model/UInt256.kt:110-121` (`mutableListOf`
  accumulation, `buildList` or `generateSequence`).

### Domain style, with ktlint (X-01)

- Import layout in 8 files (`ObserveEnsNames.kt:12-18`,
  `TransactionHistories.kt:20-33`, `Networks.kt:16-27`,
  `SafeTxSigning.kt:34-44`, `ObserveUsdPrice.kt:9-14`,
  `ReadExchangeRate.kt:11-17`, `FollowUpgradeReports.kt:5-7`,
  `ObserveUpgradeWarning.kt:12-19`), 4 unsorted groups
  (`repository/SentTransfers.kt:5-6`, `usecase/SafeSwaps.kt:4-5`,
  `SafeTxOnChain.kt:3-4`, `CowOrderViews.kt:8-9`), wrapping at
  `ObserveKnownTokens.kt:67-68`, `Networks.kt:144-145`,
  `SafeTxOnChain.kt:380-383`, one 124-column line
  (`SafeSettingsChanges.kt:50`).

## Data (`data/src/*Main`, 111 files, 15.1k lines)

Modern, mostly idiomatic adapters: sealed results (`CallOutcome`,
`EngineReading<out T>` with `data object Syncing : EngineReading<Nothing>`),
cold polling `flow {}` ending in `distinctUntilChanged().flowOn(dispatcher)`,
`channelFlow` for the file watcher, `transformWhile` to end a stream on a final
answer, `CompletableDeferred` to share an in-flight proof, `kotlin.time`
throughout with `TimeSource` injected, kotlinx-io in common code, private
`@Serializable` DTOs per adapter with a lenient version read then a strict
decode, secrets zeroed in `finally`, and four `expect`s in `commonMain` plus one
in `iosMain` with leaf actuals: exactly "the smallest source set". A-04
(`Pair`) is X-04, A-07 (duplicated constants) is X-03, A-08 (logging) is X-07,
A-10 (collections) is X-09.

### A-01 Two hand-copied `MyotisHost`s instead of a common core — High

- `jvmSharedMain/.../engine/MyotisHost.kt` (839 lines) and
  `iosMain/.../engine/MyotisHost.kt` (794): the same class shape, ports and
  KDoc (`:84-90` vs `:86-91`); `ChainNode` and the 90-line port wiring
  byte-identical but for one line (`:342-438` vs `:331-437`); `HandleChain`
  with 20 reads of the form `(state as? Running)?.handle ?: return null`
  (`:579-772` vs `:564-726`); the state machine, `observeLive`, `refresh`,
  `setOnDemand`, `set`, `syncPastStaleAnchor` and the constants duplicated
  verbatim. Between 320 (unique lines) and 450 (all lines) identical lines.
- Two concurrency models for one job: the JVM host uses
  `Executors.newSingleThreadExecutor { Thread(task, "myotis-control") }`,
  `control.execute` and `submit().get(SHUTDOWN_WAIT_MS)` (`:110-112, 240-255,
  262-291`), the only Java concurrency in the module; the iOS host uses
  `Dispatchers.IO.limitedParallelism(1)`, `scope.launch(control)` and
  `runBlocking { withTimeoutOrNull { } }` (`:111-114, 238-247, 260-283`).
- Already drifting: `pause`/`resume` return `Unit` on one and `Job` on the
  other; the `datesFinalizedBlocks` override exists only on iOS
  (`:418-425`, done by `object : LightClient by EngineLightClient(...)`);
  an orphaned KDoc at iOS `:472-476`.
- Idiomatic form: one `internal` common `EngineHost` holding `ChainNode`,
  the wiring, the state machine and `HandleChain`, over a small seam (an
  interface or `expect class` for create engine, create chain handle, status
  fields, stop, pause, resume, log). The control lane as the coroutine shape
  iOS already has, which runs unchanged on the JVM. The rule is "new code in
  `commonMain`, unless it is a platform seam".

### A-02 `@Volatile` fields updated by non-atomic read-modify-write — Medium

- `engine/AddressActivity.kt:120` (`askedAt = askedAt + ...`), `:183`,
  `:195`, `:208`, `:168`, on five `@Volatile` fields declared `:75-100`;
  `engine/VerifiedPriceFeeds.kt:93, 107` (`aggregators = aggregators - id`),
  whose KDoc at `:53-57` admits "a write lost to a race only costs that feed
  one more proof"; `engine/VerifiedPoolPrices.kt:189, 204`. These run from
  `ProvenReads.readingAt` (`ProvenReads.kt:220-221`, "asked outside the
  lock") on every subscriber's poll, so the races are real; their failure
  direction is benign (a proof is re-made), but only one of the three files
  says so.
- The single-writer `@Volatile var state` in `ChainNode` is the correct use.
- Idiomatic form: `MutableStateFlow.update {}` (the module already does this
  in `MemoryDebugLog`, `ProofSchedule`, `InMemoryAccountRepository`), the
  `Mutex` the neighbouring `ProvenReads` uses, or atomicfu. `@Volatile`
  guarantees visibility only. The rule file names `MutableStateFlow`, `Mutex`
  and atomicfu for common code.

### A-03 `!!` through `runCatching` as one file's decoding strategy — Medium

- `rates/FileLastExchangeRate.kt:125-136`: five `!!` inside `runCatching {
  RateSnapshot(PriceId(base!!), currency, rate!!, ..., stale!!) }.getOrNull()`,
  the module's only `!!` and only `runCatching`; `:113-116` a `Boolean?`
  parameter; `:140-152` an 11-argument positional constructor call to a class
  whose fields are all optional.
- Idiomatic form: `val base = base ?: return null`, named arguments, a plain
  `Boolean` once the caller has decided. `runCatching` also swallows `Error`
  and `CancellationException`, which the rest of the module rethrows.

### A-05 A property that does I/O, creates a key, or throws — Medium

- `keys/HardwareSecret.kt:19` (`val protection: KeyProtection`);
  `androidMain/.../AndroidKeystoreSecret.kt:32` (`by lazy { protectionOf(key())
  }` generates a Keystore key), `desktopMain/.../MacSecureEnclaveSecret.kt:40-48`
  (`@Synchronized get()` round-trips the Secure Enclave and `error()`s),
  `iosMain/.../IosSecureEnclaveSecret.kt:52-59`,
  `keys/UnavailableHardware.kt:12` (`get() = error(reason)`).
  `EnvelopeKeyStore.protection()` (`:96`) already wraps it as a function, and
  `isProvisioned()` beside it is one.
- Idiomatic form: `fun protection(): KeyProtection`. The conventions reserve
  properties for members that do not throw, are cheap and return the same
  value each time.

### A-09 Public classes with `internal constructor` that nothing outside builds — Low

- 19 `class X internal constructor(` (`engine/VerifiedPriceFeeds.kt:39`,
  `VerifiedPoolPrices.kt:64`, `VerifiedNativeBalances.kt:25`,
  `VerifiedTokenBalances.kt:32`, `VerifiedAddressKinds.kt:62`,
  `EngineLogIndex.kt:76`, `EngineTransactionInputs.kt:30`, ...),
  `engine/NetworkRouted.kt:58-217` public though used only by the two hosts.
  The hosts import `MyotisHost`, the `File*` adapters, the key-store entry
  points and `HttpCowApi` only.
- Idiomatic form: `internal class`.

### A-11 `Dispatchers.IO` hard-coded inside the two engine hosts — Low

- 21 occurrences per host (`jvmSharedMain/.../MyotisHost.kt:115, 118, 121,
  370-437`; iOS `:111, 114, 121, 361-436`). Every `File*` adapter and
  `EnvelopeKeyStore` take `dispatcher: CoroutineDispatcher = Dispatchers.IO`
  as a constructor parameter, which is the right shape; `MyotisHost.start`
  should too.

### A-12 Hex strings built with `+` — Low

- 15 `"0x" + ...` sites (`engine/VerifiedAddressKinds.kt:393, 402, 414`,
  `engine/EngineLogIndex.kt:296`, `engine/LoggedChain.kt:178`,
  `safe/SafeTxWire.kt:99`, `keys/Address.kt:20`); `engine/Abi.kt:11, 18, 40`
  (`"70a08231" + "0".repeat(24) + holder.hex.removePrefix("0x")`),
  `engine/Abi.kt:177-189` (`StringBuilder` beside `buildString` at `:141`).
  Templates, or one `hexWord(selector, ...)` helper so the padding arithmetic
  lives in one place.

### A-13 Exceptions as control flow in decoders, and a side effect in a lazy message — Low

- `engine/AddressActivity.kt:245` (`require` inside `try ... catch (e:
  Exception) { null }`), `history/FileHistoryStarts.kt:108`,
  `transfers/FileTransactionHistory.kt:110`, `transfers/StoredTransfers.kt:137`,
  `cow/FileCowOrders.kt:220, 266`: `IllegalArgumentException` thrown so that a
  `catch (e: IllegalArgumentException)` at `StoredTransfers.kt:168` and
  `FileCowOrders.kt:202` catches it together with `SerializationException`,
  which extends it; the coupling is named in comments, not in a type.
  `keys/Bip32.kt:34` runs `master.wipe()` inside a `check` message lambda.
- Idiomatic form: a private `class DamagedEntry : IllegalArgumentException`,
  or `null` up the chain; `if (!valid) { master.wipe(); error(...) }`.

### A-14 Casts and shapes that work around the type system — Low

- `engine/EngineReceipts.kt:59` (`snapshot as EngineSnapshot.Running` after
  `whyUnreadable(snapshot)` returned null in another function), iOS
  `MyotisHost.kt:418-425` (delegation override to flip one flag that
  `EngineLightClient.kt:104` hard-codes), `engine/EngineTransactionInputs.kt:72`
  (`reading !== COLD`, identity against a data-class sentinel),
  `engine/EngineLogIndex.kt:244-247` (non-local `return null` and
  `return@mapNotNull null` in one lambda).
- Idiomatic form: `fun readable(snapshot): Running?`; a constructor parameter
  for the flag; a local sealed result for "cold".

### A-15 Long functions in the engine adapters — Low

- `engine/VerifiedPriceHistory.kt:66-165` (`search`, six `var`s driving a
  bisection), `engine/VerifiedPoolPrices.kt:181-278` (`average`),
  `engine/ProvenReads.kt:203-274` (`readingAt`, `while (true)`/`continue` and
  non-local returns from `withLock` lambdas), `keys/EnvelopeKeyStore.kt:593-654`
  (`import`), `:427-484`. See X-05.

### A-16 Naming and doc nits — Low

- `InMemoryAccountRepository` vs `MemoryDebugLog` for the same idea;
  `MyotisHost.start(...)` is a factory whose KDoc says nothing runs until
  `set` (`create`/`of`); `safe/PinnedSafeReleases.kt:7-9` says "on Sepolia"
  though it serves every network; the loose KDoc at iOS `MyotisHost.kt:472-476`.

### Data style, with ktlint (X-01)

- Mis-indented lambda bodies and wraps at `engine/VerifiedNativeBalances.kt:61-69`,
  `engine/EngineTransactionInputs.kt:41-44, 47-50, 119-121`,
  `engine/NetworkRouted.kt:101-103, 118-120`, `history/FileHistoryStarts.kt:50-52`,
  `tokens/FileUserTokens.kt:53-58`, `engine/LastBalancesFile.kt:49-51`,
  `networks/FileNetworkSettings.kt:66-68`, `engine/Abi.kt:69-73`,
  `engine/VerifiedPoolPrices.kt:353-355`, jvmShared `MyotisHost.kt:681-683`,
  iOS `EnvelopeCrypto.ios.kt:337-340, 362-365`; six lines over 120 columns
  (`VerifiedTokenBalances.kt:26, 76`, `VerifiedPriceFeeds.kt:89, 106`,
  `ProvenReads.kt:58`, jvmShared `CallOutcomes.kt:9`).

## ViewModels and UiStates (`ui`, 65 files, 18.0k lines)

Line-level, this is convention-abiding Kotlin: every ViewModel exposes
`StateFlow<UiState>`, 39 of 45 `stateIn`/`shareIn` sites use
`WhileSubscribed(5 s)`, 25 of them with the commented `replayExpirationMillis =
0`; every UiState is a sealed interface or data class of display-ready leaves
with computed `canSign`/`idle`/`sendReason` properties that keep the greyed-out
rule in one place; all 42 broad catches outside `NonCancellable` rethrow
`CancellationException` first; 131 of 131 top-level declarations have KDoc; no
`mutableStateOf`, `Dispatchers.IO`, `runBlocking` or `lateinit`. The dominant
shape, intent functions over private `step`/`inputs` `MutableStateFlow`s
combined with use-case flows into a `Model` and rendered by a private
`render()`, is applied uniformly across 30 ViewModels. V-02 is X-03, V-03 is
X-06, V-05 is X-04, V-16 is X-09, V-18 is X-08.

### V-01 Flow pipelines with side effects — High

- `safetx/SafeTxViewModel.kt:415`: `combine(context.onEach(::follow), view,
  step, swapCheck, ::render)`, where `follow` (`:961-988`) calls `step.update`
  and `job?.cancel()`; `step` is an input of that same `combine`.
  `SafeTxViewModel.kt:327, 922`: a `scan` accumulator assigns the field
  `lastNonce`. `createsafe/CreateSafeViewModel.kt:254-275`: the `combine`
  lambda assigns `network` (the module's only `@Volatile`, `:131-132`) and
  `warmed` (not volatile) and launches `warmFees()`.
  `accountdetail/AccountDetailViewModel.kt:312-314, 492-494`: a `map` runs
  `Dating.finish`, which assigns `oldestShown` and does `compareAndSet` on an
  upstream input. Milder: `accounts/AccountsViewModel.kt:218`,
  `transferstatus/TransferStatusViewModel.kt:71` (`onEach { latest = it }`).
- All of it runs on `Main.immediate` today, so it is not a data race; with
  `replayExpirationMillis = 0` restarting these chains, the order of the side
  effects is hard to predict, and `@Volatile` in common code is the wrong tool
  either way.
- Idiomatic form: keep transforms pure and carry the derived value in the
  stream (a `scan` accumulator holding the last nonce; a `Model` holding the
  chosen network); launch work from intents or from a terminal `onEach {
  }.launchIn(viewModelScope)` that is visibly a side effect; never mutate a
  `combine` input from inside that `combine`.

### V-04 Intents decide from the rendered `uiState.value` — Medium

- `watch/WatchViewModel.kt:329-330, 379-389` (reads `uiState.value.networks`
  and maps display chain ids back to `Network`; its own comment at
  `:377-380` notes the lag), `addtoken/AddTokenViewModel.kt:173-175`
  (`uiState.value.card as? TokenCard.Claim`, then `Address.parse(shown.address)`),
  `addnetwork/AddOnNetworksViewModel.kt:91-93`.
- Idiomatic form: decide from the `MutableStateFlow`s the intent owns and keep
  the display-ready state write-only. Round-tripping EIP-55 strings through
  `Address.parse` to recover a value the ViewModel already holds is the symptom.

### V-06 Nested `combine(..., ::Pair)` and lists indexed by `ordinal` — Medium

- `accountdetail/AccountDetailViewModel.kt:254-268` (a five-arm `combine`
  whose fifth arm is `combine(combine(view, ensName, ::Pair), activityState,
  combine(networkOn, networkBlocked, swapVenue, ::Triple), ::Triple)`, then
  double destructuring), `accounts/AccountsViewModel.kt:145-152`,
  `watch/WatchViewModel.kt:204-210`, `addtoken/AddTokenViewModel.kt:139-144`.
  Ordinal indexing: `accounts/AccountsViewModel.kt:108-131` builds `live`,
  `statuses`, `staleAnchors` as lists "in `Network` order" read at `:214, 224,
  229, 241, 392, 397`; `settings/SettingsViewModel.kt:84, 110`.
- Idiomatic form: `settings/SettingsViewModel.kt:135-140, 248-253` already
  shows it, a private data class per group, or `combine(vararg)` into one
  named `Inputs` as `AccountsViewModel.kt:181-192` does; `Map<Network, T>`
  through `Network.entries.associateWith { }` instead of `list[network.ordinal]`.

### V-07 Smart casts defeated by mutable properties — Medium

- `safetx/SafeTxViewModel.kt:998-1000` (`inStep && lastContext != null ->
  lastContext!!`, the module's one `!!`),
  `createaccount/CreateAccountViewModel.kt:80` (`if (state.value !is Securing
  || (state.value as Securing).creating) return`). Bind to a local first, as
  the same files do everywhere else (`SafeTxViewModel.kt:484`,
  `CreateAccountViewModel.kt:130`).

### V-08 No Compose stability on UiStates; rendering logic in a UiState file — Medium

- No `@Immutable`/`@Stable` in any of the 32 `*UiState.kt` files and no
  stability configuration in `ui/build.gradle.kts`; data classes with `List`
  leaves (`accounts/AccountsUiState.kt:72-84`,
  `accountdetail/AccountDetailUiState.kt:48-75`, `swap/SwapUiState.kt:114-142`)
  are therefore unstable to the Compose compiler.
  `accounts/AccountsUiState.kt:230-297` is a 68-line `AccountItem.from(Account,
  BalanceReading?, ..., Total?)` that takes domain types and builds display
  strings, ViewModel work in the UiState file; `:85-89` validates with
  `require` in a UiState `init`.
- Idiomatic form (Compose API guidelines): mark the UiState hierarchy
  `@Immutable` or ship a stability configuration naming
  `kotlin.collections.List`; move `AccountItem.from` into the ViewModel.

### V-09 State split between plain `var`s and the `StateFlow`, fed by `collect` in `init` — Medium

- `createaccount/CreateAccountViewModel.kt:54-75` (`var on`, `var chosen`,
  `var shown`, `init { viewModelScope.launch { ...collect { on = ...;
  refreshNetworks() } } }`), `mnemonicimport/MnemonicImportViewModel.kt:45-66`,
  `watch/WatchViewModel.kt:87-97`, `addtoken/AddTokenViewModel.kt:76-85`.
- Idiomatic form: the networks-on set belongs in the `MutableStateFlow` beside
  the typed fields (as `addnetwork/AddOnNetworksViewModel.kt:39-46`'s `Form`
  does) and the UiState is combined from them; a lifetime collector is
  `observeEnabledNetworks().onEach { }.launchIn(viewModelScope)`. The six
  dialog ViewModels that expose `MutableStateFlow.asStateFlow()` with no
  upstream flow are a reasonable deviation from "derived with `stateIn`"; the
  `var`s beside them are not.

### V-10 Plain `class` models in `StateFlow<Model?>` disable conflation — Low

- `swap/SwapViewModel.kt:219`, `safeswap/SafeSwapViewModel.kt:183`,
  `safepropose/SafeProposeViewModel.kt:154`, `send/SendViewModel.kt:160`,
  `createsafe/CreateSafeViewModel.kt:156`, `safetx/SafeTxViewModel.kt:193, 205`
  (`private class Model(` feeding `stateIn`); `Outcome` a plain class in
  `watch`, `addtoken`, `currency` but `Form` a data class in `addnetwork`.
  `StateFlow` skips equal values; with identity equality every upstream tick
  re-runs `render()`. If identity is wanted, say so; otherwise `data class`.

### V-11 `value = snapshot.copy(...)` beside `update {}`, and one read-modify-write — Low

- `settings/SettingsViewModel.kt:221` (`networkNotSaved.value = if (saved)
  networkNotSaved.value - network else networkNotSaved.value + network`); 37
  snapshot writes such as `swap/SwapViewModel.kt:544, 631, 816`,
  `safetx/SafeTxViewModel.kt:563, 715`, `createsafe/CreateSafeViewModel.kt:394,
  402` next to 110 `update {}` calls, sometimes in one function
  (`send/SendViewModel.kt:412` then `:415`). No race on `Main.immediate`;
  `update { }` makes the guard and the write one step and survives a
  dispatcher change.

### V-12 `SharingStarted.Eagerly` in screen-scoped ViewModels — Low

- `watch/WatchViewModel.kt:84`, `addtoken/AddTokenViewModel.kt:64, 69, 73`.
  The `init` collector already subscribes for the ViewModel's lifetime, so
  `WhileSubscribed` behaves the same; the app-scoped `DebugTabViewModel` and
  `InterfaceModeViewModel` are fine.

### V-13 Labelled returns from long `launch` bodies — Low

- 56 in scope (`return@launch` 28, `return@update` 12, `return@combine` 6):
  `swap/SwapViewModel.kt:752-772` (five in one `launch`), `:821-824, 832-840`
  (from inside a `when` branch that is the right-hand side of a `val`),
  `safeswap/SafeSwapViewModel.kt:525, 549, 604`,
  `addnetwork/AddOnNetworksViewModel.kt:50-56`.
  `createsafe/CreateSafeViewModel.kt:540, 571` shows the alternative: `job =
  viewModelScope.launch { quote(asked, note) }` with a named `suspend fun`.

### V-14 `takeIf` on a constant whose predicate ignores the receiver; deep `?.let` nests — Low

- 51 of 117 `takeIf`/`takeUnless` lambdas never read `it`
  (`safesettings/SafeSettingsViewModel.kt:688-691, 838`,
  `watch/WatchViewModel.kt:235`, `deriveaccount/DeriveAccountViewModel.kt:100`,
  `accounts/AccountsUiState.kt:105`): `if (cond) TEXT else null` reads as what
  it is. Nests at `createsafe/CreateSafeViewModel.kt:733`,
  `accounts/AccountsUiState.kt:280-287`, `swap/SwapViewModel.kt:482-484`.

### V-15 Identity-widening `.map<X, X?> { it }` before `onStart { emit(null) }` — Low

- `accountdetail/AccountDetailViewModel.kt:194, 212`,
  `coworder/CowOrderViewModel.kt:192`, `watch/WatchViewModel.kt:189`,
  `addtoken/AddTokenViewModel.kt:127`, `activity/ActivitySources.kt:76`.
  `Flow` is covariant: `.onStart<X?> { emit(null) }` is enough, as
  `AccountDetailViewModel.kt:222` already does.

### V-17 ViewModel style, with ktlint (X-01), and small hygiene — Low

- Lines over 120 columns at `safesettings/SafeSettingsViewModel.kt:897, 918`,
  `addtoken/AddTokenViewModel.kt:216`; double blank lines in 8 files;
  continuation indentation at `safeswap/SafeSwapViewModel.kt:298-302`,
  `accountdetail/AccountDetailViewModel.kt:315-320`,
  `createsafe/CreateSafeViewModel.kt:750-758`, `activity/AccountActivity.kt:148-149`;
  unused `address` at `renameaccount/RenameAccountViewModel.kt:25`; `!= false`
  double negatives at `mnemonicimport/MnemonicImportViewModel.kt:71`,
  `safeimport/SafeImportViewModel.kt:84`; guards that compare against a freshly
  built `Step.Composing(quoting = true)` at `send/SendViewModel.kt:315`,
  `safeswap/SafeSwapViewModel.kt:340`, `safepropose/SafeProposeViewModel.kt:299`
  where `swap/SwapViewModel.kt:490-494` keeps the instance; 243 lines ending in
  `" +` where `listOfNotNull(...).joinToString(" ")` is used elsewhere in the
  same files (`swap/SwapViewModel.kt:1252-1257, 1330, 1355-1363`,
  `safeswap/SafeSwapViewModel.kt:839-847`).

### V-19 The composition root builds the same use case several times per screen — Low

- `AppGraph.kt:350, 354, 357` (`observeSafe()` three times in
  `safeTxViewModel`), `:666, 668` (`knownTokens()` twice). Harmless while
  they are stateless; a `val` per factory would make the sharing explicit.

## Composables (`ui` screens, routes, components, previews, platform sets; 29k lines)

Highly idiomatic and disciplined. Every screen is a pure function of its
UiState; the 48 `mutableStateOf` sites are all transient chrome inside
composables, none in a Route; `collectAsStateWithLifecycle` at 91 sites;
one-shot navigation callbacks use `rememberUpdatedState` + `LaunchedEffect(key)`;
`LifecycleEventEffect` for hide/show; theme roles everywhere, with the only
fixed hues carrying a contrast rationale; `when` over sealed UiStates
exhaustive; `tailrec` context unwrapping and an Android capture protection that
restores prior window flags; no `!!`, `lateinit`, `println` or wildcard import.
C-04 (copied fragments) and C-09 (preview samples) are X-03, C-06 (Route files)
is X-11, C-10 (`Pair`) is X-04.

### C-01 Navigation is stringly typed, hand-parsed, and the parse-or-pop block is repeated ten times — High

- `App.kt:226-682` (`Destinations`, 457 lines, 7 parameters). The "parse the
  argument or pop" block at `:315-331, 459-482, 493-524, 526-548, 560-575,
  590-607, 608-620, 622-637, 638-663, 664-676`; `navArgument(X) { type =
  StringType; nullable = true; defaultValue = null }` at `:309-313, 453-457,
  487-491, 554-558, 579-588`; routes assembled by template and compared as
  strings at `:702-703, 714, 778, 864, 877-878`; the regexes `TRANSACTION_HASH`,
  `ORDER_UID`, `AMOUNT_TEXT` at `:1001, 1015, 1042` re-validate what the
  ViewModel already typed.
- Idiomatic form: navigation-compose 2.9.2, the pinned multiplatform build,
  supports type-safe routes: `@Serializable data class SafeTx(val chainId:
  Long, val address: String, val hash: String)`, `composable<SafeTx> { entry ->
  entry.toRoute<SafeTx>() }`, `navController.navigate(SafeTx(...))`. That
  deletes the `Destination` object, the twenty `const val` argument names, the
  regex validation, the `savedStateHandle.get<String>()` re-parsing and the ten
  `else { LaunchedEffect(Unit) { popBackStack } }` copies, and turns a mistyped
  route into a compile error. Short of that, one `withParsed(route, parse) { }`
  helper as `withAccount` already is (`App.kt:897-927`).

### C-02 Empty-string sentinels stand in for "absent" on non-null UiState leaves — High

- The simple-mode wrappers blank non-null leaves: `safetx/SimpleSafeTxScreen.kt:47-48`
  (`feeDetail = ""`, `balance.copy(verifiedAt = "")`),
  `createsafe/SimpleCreateSafeScreen.kt:46-47`, `coworder/SimpleCowOrderScreen.kt:66-67`,
  `safepropose/SimpleSafeProposeScreen.kt:48`, `safeswap/SimpleSafeSwapScreen.kt:45`;
  the screens then guard with `takeIf { it.isNotEmpty() }`:
  `safetx/SafeTxScreen.kt:622, 627`, `createsafe/CreateSafeScreen.kt:516, 521`,
  `coworder/CowOrderScreen.kt:338, 343`, `safepropose/SafeProposeScreen.kt:327`,
  `safeswap/SafeSwapScreen.kt:310`, each with the comment "Empty in simple
  mode". The leaf is declared non-null (`components/TransferItems.kt:96`).
  Send does not take part: its simple view draws a separate
  `SimpleReviewContent` (`send/SendScreen.kt:262-267, 400`) and blanks nothing,
  so its `Note(change.verifiedAt)` at `:369` always has text.
- Idiomatic form: `verifiedAt: String?` and `feeDetail: String?` with `?.let {
  Note(it) }`, as the same files do for every other optional line, or Send's
  shape, a simple composable of its own over the unchanged state. A `String`
  whose emptiness means "do not draw" is not a display-ready leaf, which is
  the project's own UiState rule.

### C-03 18 to 22-parameter screens, forwarded twice, beside screens that use an actions holder — Medium

- `accountdetail/AccountDetailScreen.kt:252-276` (22 parameters), `:141-162`
  (the Route, 19), `:457-474` (15); `accountdetail/SimpleAccountDetailScreen.kt:17-41`
  (21, forwards all); `swap/SwapScreen.kt:214-236` (21) and
  `swap/SimpleSwapScreen.kt:18-62` (forwards 20 verbatim);
  `send/SendScreen.kt:173-193` (18) and `send/SimpleSendScreen.kt:16-56`.
  Create Safe, Safe propose, Safe settings, Safe swap and Safe tx already use
  a remembered `XxxActions` holder (`createsafe/CreateSafeScreen.kt:141-209`,
  `safetx/SafeTxScreen.kt:151-223`).
- Idiomatic form: apply the project's own `remember(viewModel) {
  SendActions(...) }` to Send, Swap and Account detail. It halves the three
  Simple wrappers, which exist only to re-list every parameter.

### C-05 Layout-emitting composables without a `modifier` parameter — Medium

- No modifier: `components/TransferFormFields.kt:197` (`RecipientCard`,
  hard-codes `Modifier.fillMaxWidth()`), `:255, 271, 277, 284, 135`,
  `components/SwapItems.kt:25, 38, 46`, `components/OwnerChoices.kt:19`,
  `components/NetworkChecklist.kt:81`, `components/AssetDropdown.kt:48`,
  `components/RequestShare.kt:57`, `components/StaleAnchorWarning.kt:82`,
  `components/Unlock.kt:127`; a required `modifier: Modifier` with no default
  at `accountdetail/AccountDetailScreen.kt:604, 664, 748, 998`. About 20, all
  `internal` or `private`.
- Idiomatic form: `modifier: Modifier = Modifier` as the first optional
  parameter, applied once to the root. Fragments that deliberately emit
  several siblings into the caller's column are the legitimate exception and
  should say so in KDoc.

### C-07 Positional callbacks and positional construction at call sites — Medium

- Route to Simple-screen calls with three to six positional lambdas:
  `addtoken/AddTokenScreen.kt:73-93`, `safeimport/SafeImportScreen.kt:76-92`,
  `watch/WatchScreen.kt:103-129`, `activitydetail/ActivityDetailScreen.kt:75-83`,
  `settings/SettingsScreen.kt:66-93` (12 positional, six of them lambdas),
  `createsafe/CreateSafeScreen.kt:165-171`; bare `null` and `Boolean`
  positionals `LightClientItem("...", null, true, NOT_VERIFIED)` 15 times in
  `components/LightClientItem.kt:72-129` and `components/LightClientStripSamples.kt:9-26`;
  `NetworkSection(ethereum.chainId, "Ethereum", false, true, strip, ...)` at
  `accounts/SimpleAccountsScreenPreviews.kt:84-86`. 30 positional-lambda sites;
  14 of 17 Simple wrappers forward by name, 3 do not.
- Idiomatic form: name every lambda and every `Boolean`/`null` argument once
  a call has more than two or three parameters. Swapping `onToggle` and
  `onTurnOn` at `addnetwork/AddOnNetworksDialog.kt:26` would compile.

### C-08 Oversized composables with deep nesting — Medium

- `App.kt:226-682` (457), `accountdetail/AccountDetailScreen.kt:252-450`
  (199; the `TopAppBar.actions` lambda at `:341-404` builds the whole menu six
  levels deep), `swap/SwapScreen.kt:214-412` (199), `settings/SettingsScreen.kt:110-269`
  (160), `safetx/SafeTxScreen.kt:324-447` (124), `safesettings/SafeSettingsScreen.kt:311-430`
  (120), `watch/WatchScreen.kt:145-266` (122), `transferstatus/TransferStatusScreen.kt:233-348`
  (116). The files already extract `ComposeContent`/`ReviewContent`; the
  top-bar menus and the per-section rows are the next extractions.

### C-11 Elvis and `?.let` used for control flow with side effects — Low

- `safesettings/SafeSettingsScreen.kt:315-317` (`reason?.let { unavailable =
  ... } ?: run()`), `:397-407` (a composable call in an elvis). `if (reason ==
  null) run() else ...`.

### C-12 Two naming styles for private dp constants — Low

- PascalCase `ActionLabels`, `ActionGap`, `ActionPadding`
  (`accountdetail/AccountDetailScreen.kt:642-652`, `components/ControlBadge.kt:141-147`)
  beside `TRAILING_MAX_WIDTH` (`:1013`), `WIDE_WINDOW` (`App.kt:1045`),
  `TRACK_ALPHA`, `DIMMED`. Either is fine; mixed in one file is not.

### C-13 Composable style, with ktlint (X-01) — Low

- Seven lines over 120 columns (`accountdetail/AccountDetailScreen.kt:584`,
  `components/TransferItems.kt:14`, `safesettings/SafeSettingsScreen.kt:456`,
  `ratewidget/RateWidgetContent.kt:16`, `safesettings/SafeSettingsScreenPreviews.kt:140-141`,
  `safesettings/SimpleSafeSettingsScreenPreviews.kt:119`); import order in
  `App.kt:48-49`, `AccountDetailScreen.kt:70-73`, `activity/ActivityList.kt:28-30`,
  `components/TransferInputs.kt:24-25`, `send/SendScreenPreviews.kt:10-11`;
  an `else` with its expression on the next line without braces
  (`SafeSettingsScreen.kt:600-601`); an empty KDoc (`watch/WatchScreen.kt:81`);
  `kotlin.math.floor` fully qualified at use (`components/Blockie.kt:192, 229`).

### C-14 Platform sets: identical JVM actuals twice, duplicated helpers, module-global state — Low

- `ui/src/androidMain/.../DateTimeFormat.android.kt` and
  `ui/src/desktopMain/.../DateTimeFormat.desktop.kt` are byte-identical;
  `Context.activity()` (`ConfigurationChange.android.kt:18-22`) and
  `Context.findActivity()` (`ScreenCapture.android.kt:70-74`) are the same
  `tailrec` function; five top-level `private var`s in
  `iosMain/.../ScreenCapture.ios.kt:69-76` and two in `ScreenOn.ios.kt:28-29`;
  `TextSharing.ios.kt:32` (`while (true) top = ...?.takeUnless { } ?: break`).
- Idiomatic form: a shared JVM intermediate source set in `:ui` (`:data` has
  `jvmSharedMain`) holding one `DateTimeFormat.jvm.kt`, which is what "the
  smallest source set that needs it" means here; one `Context.findActivity()`;
  the iOS globals in a `private object` with the main-thread invariant;
  `generateSequence(controller) { it.presentedViewController?.takeUnless {
  it.beingDismissed } }.last()`.

### C-15 Minor Compose effect hygiene — Low

- `createaccount/CreateAccountScreen.kt:100-103` and
  `mnemonicimport/MnemonicImportScreen.kt:95-98` call a callback from a
  conditional `LaunchedEffect(Unit)` without `rememberUpdatedState`, unlike
  the 20 other Routes; `platform/ScreenCapture.kt:23` is the one
  `collectAsState()`; `App.kt:133, 231, 255` models "not read yet" as
  `debugShown: Boolean?` compared with `== true`/`== false`.

### C-16 Small algorithmic polish in `components/` — Low

- `components/QrMatrix.kt:439` (`ArrayList<Boolean>` bit buffer, every bit
  boxed; `BooleanArray`), `components/AccountAvatar.kt:48` (nested `if/else
  if`, `when`), `components/SwapText.kt:25-31` (a `val` lambda where a local
  `fun` is idiomatic), `createaccount/CreateAccountScreen.kt:308-312` (`when
  (step) { ... else -> "" }` on a sealed type, duplicating the private
  extension at `mnemonicimport/MnemonicImportScreen.kt:308-313`).

## Tests (311 files, 50.5k lines, 2,074 tests)

Good, consistent, modern test code, and the one area with no High finding.
Hand-rolled fakes of the domain ports and no mock library; `runTest`
everywhere a coroutine is involved; `Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))`
with a `backgroundScope` collector and `runCurrent()` in the ViewModel tests,
`resetMain` in all 30 files that set it; `StandardTestDispatcher(testScheduler)`
injected into the adapters; `TestTimeSource` and the scheduler's own time
source for clocks; `CompletableDeferred` gates on fakes instead of sleeps; Ktor
`MockEngine` for the one HTTP adapter; kotlinx-io temp files in `commonTest`;
headless `createComposeRule` tests of stateless screens that record their
callbacks; `assertIs<>` as the dominant narrowing (480 uses) with extension
getters such as `val SafeSwapViewModel.review get() = assertIs<Review>(uiState.value)`;
the `SafeWorld` fixture shared by every Safe test; sentence-style camelCase
names (backticks are not allowed on the Android and iOS targets) with the
rationale repeated as the assertion message; test vectors with their
provenance in KDoc; one `renderEach` that renders every preview list. The
weaknesses are drift and duplication.

### T-01 `assertEquals(true|false|null, x)` where `assertTrue`, `assertFalse` and `assertNull` exist — Medium

- 158 plus 83 occurrences across every module:
  `ui/src/commonTest/.../safeswap/SafeSwapViewModelTest.kt:213` (`assertEquals(false,
  form.fixed)`), `data/src/commonTest/.../engine/VerifiedAddressKindsTest.kt:322`
  (`assertEquals(true, reading.kind is AddressKind.Safe)`, also `:398, 460, 576`),
  `ui/.../deriveaccount/DeriveAccountViewModelTest.kt:130` (`assertEquals(true,
  state.error != null)`), `ui/.../safepropose/SafeProposeViewModelTest.kt:177,
  215, 395` (`assertEquals(null, review.queued)`),
  `domain/src/commonTest/.../usecase/SafeTxOnChainTest.kt:334-335, 375-376, 397`.
- The same files use the right forms elsewhere (`SafeSwapViewModelTest.kt:214`,
  `SendViewModelTest.kt:295`), so this is drift. `assertIs` and `assertNotNull`
  also return the narrowed value for the next line and fail naming what was
  expected rather than "expected:<true> but was:<false>".

### T-02 Unchecked `as Sub` casts and `!!` beside the `assertIs` house style — Medium

- 173 `as X.Y` casts and 83 `!!`:
  `ui/.../accountdetail/AccountDetailViewModelTest.kt:248, 284, 307, 347, 369, 399`
  (37 in the file, and the file's own `ready()` helper at `:1069` is a cast
  too), `ui/.../accounts/AccountsViewModelTest.kt:221, 235, 257, 276, 292` (34;
  `:487` compares `::class` values), `ui/.../safepropose/SafeProposeViewModelTest.kt:347`
  (`review.error!!.startsWith`), `:413`,
  `domain/.../usecase/ExchangeRateTest.kt:53, 56, 59, 70, 119, 150`,
  `ui/.../watch/WatchViewModelTest.kt:123, 133, 138`.
- A failed cast throws `ClassCastException` from the middle of a test with no
  message; `val ready = assertIs<Ready>(viewModel.uiState.value)` and
  `assertNotNull(review.error).startsWith(...)` fail as assertions and narrow
  through their contracts.

### T-03 Port fakes duplicated instead of shared in the `*Fakes.kt` files — Medium

- Seven copies of `object : KeyStore` whose unused methods `error("not used
  by these tests")` (`ui/.../safeswap/SafeSwapViewModelTest.kt:83-100`,
  `safepropose/SafeProposeViewModelTest.kt:77-94`, `send/SendViewModelTest.kt:146-163`,
  `createsafe/CreateSafeViewModelTest.kt:139-156`, `safetx/SafeTxViewModelTest.kt:88-105`,
  `swap/SwapViewModelTest.kt:189-206`, `safesettings/SafeSettingsViewModelTest.kt:57-75`)
  and three more `private class FakeKeyStore`, beside the shared `FakeKeys` in
  `ui/src/commonTest/.../Fakes.kt:77`; 16 anonymous `LightClient`s and 17
  `AddressKinds` (`WatchViewModelTest.kt:70-72, 166-171, 237-239` alone has
  three); in domain four `UniswapV3Chain` fakes and six `TransactionInputs`
  fakes (`FindUniswapRouteTest.kt:253, 286`, `SwapsTest.kt:461, 481`,
  `ProposeSafeSwapTest.kt:475, 497`, `CowSwapsTest.kt:522, 538`);
  `ObserveAccountsTest.kt:40-48` and `ImportMnemonicAccountTest.kt:275-341`
  redefine fakes that `AccountFakes.kt:27, 70` has. 66 `error("not used")`
  stubs in all.
- Idiomatic form: one configurable fake per port in the module's `*Fakes.kt`
  (`FakeKeys(biometrics = ...)`, `FakeLightClient(status)`), as `SafeWorld`,
  `FakeSigner` and `FakeTransactionInputs` already are. A port change then
  touches one class, and the stubs stop hiding which methods a ViewModel calls.

### T-04 Setup duplicated inside and across the ViewModel tests — Medium

- `ui/.../accountdetail/AccountDetailViewModelTest.kt:216, 240, 278, 296, 323`:
  34 tests each repeat `Dispatchers.setMain(...)` and the collector;
  `accounts/AccountsViewModelTest.kt` 27 times; `receive`, `removeaccount`,
  `renameaccount` and `debug` 4 each, while `SafeTxViewModelTest.kt:118`,
  `SwapViewModelTest.kt:217`, `SafeSwapViewModelTest.kt:131-170` and
  `SendViewModelTest.kt:174-202` do it once in a `TestScope.viewModel()`
  builder. `watch/WatchViewModelTest.kt:164-181, 240-251` re-spell the whole
  constructor twice though `:78-91` has the builder. The test
  `eachBalanceSaysHowLongAgoItWasProvedAndIsDatedAnew` is cloned in four
  files with the same clock and feed scaffolding (`SafeSwapViewModelTest.kt:497-509`,
  `SafeProposeViewModelTest.kt:257-269`, `SendViewModelTest.kt:236-247`).
  `data/.../engine/VerifiedPriceFeedsTest.kt:110-120, 139-149, 199-209, 223-233`
  construct the same adapter four times.
- Idiomatic form: the per-file builder with parameters for what varies, and
  the shared age-dating behaviour tested once at the component that produces
  it.

### T-05 Network and device tests use `assumeTrue` + `runBlocking` instead of the rule's `@Ignore` + `runTest` — Medium

- The rule (`platform.md`): "A test that needs a device or the network is
  `@Ignore`d by default and documented." `data/src/desktopTest/.../engine/MyotisHostSepoliaTest.kt:36-37`
  (`runBlocking { assumeTrue(System.getProperty(...) != null)`; `:41, 44, 78,
  85` time with `System.nanoTime()`), `MyotisHostGnosisTest.kt:47-53`,
  `MyotisHostEthereumTest.kt:51-57`, `engine/CowOrderEndSepoliaTest.kt:52-57`,
  `cow/CowApiSepoliaTest.kt:31-41`: 8 files gate with `assumeTrue`, 7 use
  `runBlocking`, 12 `System.nanoTime()`. `keys/MacBiometricKeyTest.kt:108, 121,
  137` is the one file that follows the rule. The same source set mixes
  `org.junit.Test` and `kotlin.test.Test`.
- Each skip is well documented in KDoc, so this is the rule's letter, not its
  spirit. Either follow the rule, or amend it to name the `assumeTrue`
  pattern; `runTest` works for real I/O; `TimeSource.Monotonic.markNow()` or
  `measureTime` for elapsed time; one `Test` annotation per source set.

### T-06 Oversized test files and many-behaviour tests — Medium

- Nine files over 800 lines: `SafeTxViewModelTest.kt` 1,261, `SwapViewModelTest.kt`
  1,233, `AppNavigationTest.kt` 1,198, `AccountDetailViewModelTest.kt` 1,075,
  `SendViewModelTest.kt` 999, `WatchViewModelTest.kt` 939, `AccountsViewModelTest.kt`
  929, `TransfersTest.kt` 875, `AccountActivityTest.kt` 865. Multi-phase tests
  with comment headings: `ui/src/desktopTest/.../AppNavigationTest.kt:567-617`
  (six scenarios in one test), `domain/.../usecase/SafeTxSigningTest.kt:525-564`,
  `TransfersTest.kt:600-640`, `ui/.../settings/SettingsViewModelTest.kt:119-176`
  (networks, version, tokens, currency, an unreadable list, EUR and an
  unreadable choice in one test).
- Idiomatic form: one behaviour per test so a failure names it; the phase
  comments are the test names waiting to be split out. The cost is
  diagnosability, not correctness; `AppNavigationTest` itself is one suite
  over one fake graph and is fine at its size.

### T-07 Small helpers re-implemented per file — Medium

- `private fun word(...)`/`topic(...)` hex helpers in 16 files
  (`domain/.../model/SafeSettingsCallsTest.kt:14-16`, `SafeEventsTest.kt:14-16`,
  `TokenTransfersTest.kt:14-15`, `usecase/SwapsTest.kt:93-94`,
  `data/.../engine/VerifiedAddressKindsTest.kt:156-157`); `ByteArray.hex()`
  defined four times and inlined nine more (`ui/.../SafeTxFakes.kt:215, 256-258`);
  the temp-directory boilerplate in 18 data tests
  (`settings/FileDebugScreenTest.kt:22-33`, `networks/FileNetworkSettingsTest.kt:25-44`,
  `safe/FileSafeTxQueueTest.kt:35-71`, `transfers/FileSentTransfersTest.kt:39-66`);
  `SemanticsMatcher.expectValue(StateDescription, "Unavailable")` spelled out
  13 times (`ui/src/desktopTest/.../accounts/AccountsScreenTest.kt:176, 204,
  235, 261, 277`, `accountdetail/AccountDetailScreenTest.kt:306, 322, 350, 375`).
- Idiomatic form: one `ByteArray.hex()` per test source set (or the stdlib's
  `toHexString()`/`hexToByteArray()` under `ExperimentalStdlibApi`), a
  `tempFile(name)` helper that cleans up, and an `isUnavailable()` matcher
  that names the owner ruling once.

### T-08 Fakes inside test classes reused from other files; a test class as a fixture — Medium

- `data/.../engine/AddressActivityTest.kt:32` (`internal class FakeActivity`
  nested in the test class) used from `VerifiedPriceFeedsTest.kt:90, 96, 110,
  139, 199, 223` as `AddressActivityTest.FakeActivity()`;
  `LoggedChainTest.kt:138` and `EngineLogIndexTest.kt:228` append fakes to
  another test's file; `domain/.../usecase/CowSwapsTest.kt:272-295`
  instantiates the test class (`CowSwapsTest().apply(refuse)`) as a fixture.
- Idiomatic form: a shared fake goes in the module's `*Fakes.kt`; a fixture
  several tests need is a class of its own, as `SafeWorld` is.

### T-09 to T-11 Test style, with ktlint (X-01) — Low

- Import order: 78 of 128 files with both put `kotlin.test.*` before
  `kotlinx.*` and 50 follow the official layout; in `ui/src/desktopTest` 6
  files put `kotlin.test` before `org.junit` and 40 the other way; a
  duplicate import at `ui/.../TransferFakes.kt:54, 62`. 24 lines over 120
  columns outside the BIP-39 vectors
  (`data/.../engine/AddressActivityTest.kt:103, 190, 333, 342, 351, 404, 411,
  434, 438`, `domain/.../usecase/SafeSettingsChangesTest.kt:71-73, 79, 81`);
  mis-indented blocks (`ui/.../mnemonicimport/MnemonicImportViewModelTest.kt:130-132,
  186-188`, `domain/.../usecase/ImportMnemonicAccountTest.kt:134-136, 146-148`);
  double blank lines (`ui/.../SafeTxFakes.kt:231`, `TransfersTest.kt:851`,
  `VerifiedPriceFeedsTest.kt:85`). Member order drifts in a few classes:
  helpers and companions between tests (`ui/.../components/LightClientItemTest.kt:65-74`,
  `domain/.../usecase/PricesTest.kt:130-156`, `data/.../engine/EngineLightClientTest.kt:182-186`).

### T-12 Small assertion and typing oddities — Low

- `assertTrue(!x)` (`ui/.../coworder/CowOrderViewModelTest.kt:202, 246, 264`,
  `data/src/envelopeTest/.../EnvelopeCryptoTest.kt:106`), `assertTrue(a != b)`
  (`MacBiometricKeyTest.kt:81`, `EnvelopeKeyStoreTest.kt:552`),
  `assertTrue(simple.size == 2 && ...)` (`SafeSwapViewModelTest.kt:277`),
  `assertEquals(2, ...fetchSemanticsNodes().size)` beside `.assertCountEquals(2)`
  elsewhere; `mutableListOf<List<Any>>()` (`domain/.../usecase/CowFakes.kt:73,
  84`); fully qualified names instead of imports
  (`data/.../engine/VerifiedPoolPricesTest.kt:141, 304`,
  `ui/.../safetx/SafeTxViewModelTest.kt:581, 723, 740`); a bare cast as a
  statement (`CowOrderEndSepoliaTest.kt:66`).

### T-13 Positional empty lambdas and re-spelled screen calls in the Compose tests — Low

- `ui/src/desktopTest/.../watch/WatchScreenTest.kt:34` (`WatchScreen(state,
  strip, {}, {}, onAdd = { adds++ }, onBack = {})`), `:89`, `:182`,
  `addtoken/AddTokenScreenTest.kt:22`; `accountdetail/AccountDetailScreenTest.kt:166-191`
  re-spells the screen with fifteen `{}` callbacks though the file's `Screen()`
  helper at `:84-107` records every event; `accounts/AccountsScreenTest.kt:220-231,
  246-257`; positional domain constructors in nearly every fixture
  (`Account(Network.SEPOLIA, Address.of(...), AccountKind.EOA, "Daily",
  AccountCustody.InKeyStore)`, `SafeTx(...)` with ten positional arguments at
  `domain/.../model/SafeTxActionTest.kt:78-81`).
- Idiomatic form: name lambda arguments past the first, give the helper the
  one parameter the test varies, and a small `account(label, byte)` builder as
  `settings/SettingsViewModelTest.kt:86-92` has.

### T-14 Small inconsistencies in dispatcher setup — Low

- `ui/.../mnemonicimport/MnemonicImportViewModelTest.kt:102-103` sets Main to
  an `UnconfinedTestDispatcher()` without `testScheduler`, the one ViewModel
  test that does not share `runTest`'s clock (it works because Unconfined runs
  eagerly); `domain/.../usecase/AccountChangesTest.kt:42-47` calls
  `testScheduler.runCurrent()` where every other file calls `runCurrent()`.

## Hosts (`android-app`, `desktop-app`, `ios-app`; 3.0k lines)

Each host is one real composition root with manual constructor injection and
no service locator. `suspendCancellableCoroutine` with `invokeOnCancellation {
signal.cancel() }` bridges the biometric prompt (`PlatformBiometricPrompter.kt:32-84`);
`Reference.reachabilityFence(instance)` keeps the instance lock alive with the
reason stated (`Main.kt:94-97`); `Mutex` plus `NonCancellable` implement the
last-waiter cancel of the widget's shared read (`SerotineApplication.kt:665-699`);
`JobService` runs are small private classes with identity checks against stale
callbacks. H-01 is part of X-03, H-04 of X-07, H-07 of X-10, H-09 of X-04.

### H-01 The failure-reporting block is inlined in two hosts and a helper in the third — Medium

- `android-app/.../SerotineApplication.kt:146-154, 160-168, 554-568, 704-712,
  803-816, 826-846, 855-863, 870-883` and `desktop-app/.../Main.kt:186-197,
  198-207, 211-221, 223-231, 233-248, 250-259, 264-282, 288-298` each repeat `try {
  Follow...()() } catch (e: CancellationException) { throw e } catch (e:
  Exception) { System.err.println("Serotine: ... stopped: ...") }`;
  `ios-app/.../SerotineWallet.kt:380-388` defines `reportingFailure(what) { }`
  and uses it for all of them.
- Idiomatic form: the iOS helper, shared, beside the `Follow*` use cases.

### H-02 `SerotineApplication` is a composition root, an engine hold counter and the widget-refresh orchestration in one class — Medium

- `android-app/.../SerotineApplication.kt` (968 lines): six `private var`
  `Job`s, three counters and two `Runnable`s (`:430-451`); the shared-read
  machinery `manualRefresh: AtomicReference`, `SharedRead`,
  `refreshRateInBackground`, `joinRead` (`:584-712`); `refreshRateWidgetsNow`,
  `giveUpManualRefresh` (`:722-762`); `Handler`/`Runnable` and coroutines
  mixed in one class; member order interleaves properties, lazies and
  functions (`node` at `:131` uses `scope` declared at `:414`, correct only
  because both are lazy).
- Idiomatic form: an `EngineHolds` (counting and the delayed pause) and a
  `WidgetRateRefresher`, each with one reason to change; the Application keeps
  the wiring. `Dispatchers.Main.immediate` with `delay` replaces
  `handler.postDelayed`.

### H-03 The desktop runs process-wide work in `LaunchedEffect(Unit)` blocks inside `application {}` — Medium

- `desktop-app/.../Main.kt:185-299`: nine `LaunchedEffect`s run the
  followers, so Compose owns work no screen owns, and `openWallet` is 248
  lines. Android and iOS give that work a `CoroutineScope(SupervisorJob() +
  Dispatchers.Default + CoroutineExceptionHandler)`
  (`SerotineApplication.kt:414-418`, `SerotineWallet.kt:251-255`).
- Idiomatic form: the same scope in `main()`, cancelled after `application`
  returns.

### H-05 `(context.applicationContext as SerotineApplication)` eleven times — Low

- `widget/RateWidget.kt:38, 45, 53, 59, 63, 67, 103`,
  `upgrade/UpgradeReminderReceiver.kt:22`, `upgrade/UpgradeCheckJob.kt:41, 47, 68`.
  One `private val Context.serotine: SerotineApplication get() = ...`.

### H-06 Validation by exception at the boundary — Low

- `widget/RateWidgetPairs.kt:28, 33`, `widget/RateWidgetConfigureActivity.kt:65, 114`:
  `runCatching { PriceId(it) }.getOrNull()`, `runCatching { RatePair(...)
  }.getOrElse { }`. A `parse` factory on `PriceId` (D-12) and `RatePair`
  removes the catch.

### H-08 The one screen without a ViewModel — Low

- `widget/RateWidgetConfigureActivity.kt:58-104` keeps its three-step state in
  `rememberSaveable` and calls the Application directly. It lives in the host,
  so the `:ui` rule does not bind it; a small ViewModel would make it testable
  like every other screen.

## Gradle scripts (8 files, 1,073 lines)

Well-written Kotlin DSL: configuration-cache discipline throughout (a
`ValueSource` for the git-derived version, `ui/build.gradle.kts:40-95`; locals
captured in `doLast`; `jvmArgumentProviders`), every non-obvious line with its
reason, content filters on every extra repository, the version catalog
explaining each pin.

### G-01 No convention plugin — Low

- `jvmToolchain(21)`, the Android block (namespace, `compileSdk 35`, `minSdk
  29`, Java 17 `compileOptions`) and `jvmTarget.set(JvmTarget.JVM_17)` are
  repeated in `data/build.gradle.kts:22-28, 149-158`, `ui/build.gradle.kts:128-134,
  197-207`, `android-app/build.gradle.kts:45-48, 124-127`. For three or more
  modules the idiomatic shape is a `build-logic` included build with
  `serotine.kmp-library` and `serotine.android` convention plugins. Six modules
  is near the threshold; a judgement call.

### G-02 to G-04 Small DSL inconsistencies — Low

- `.set(...)` where Gradle 8.14 supports assignment (`jvmTarget.set(...)` in
  three files, `repositoriesMode.set(...)` at `settings.gradle.kts:27`,
  `languageVersion.set(...)` four times in `desktop-app` and `ios-app`);
  `tasks.matching { it.name == "desktopTest" }.configureEach { (this as
  Test)... }` (`data/build.gradle.kts:164-165, 172-173`) where
  `ui/build.gradle.kts:213` does it typed with `tasks.named<Test>(...)`;
  `tasks.configureEach { if (name == "prepareAppResources") dependsOn(...) }`
  (`desktop-app/build.gradle.kts:142`); `org.jetbrains.kotlin.gradle.dsl.JvmTarget`
  fully qualified at `data/build.gradle.kts:27` while the other scripts import it.

## What is done well

Patterns a reviewer would hold up as examples, so they are kept.

- Sealed results end to end, with `require` reserved for caller errors and
  every port's KDoc saying what it never does.
- Value objects that cannot be invalid: private constructor plus `parse`/`of`
  (`Address`, `Wei`, `Digest`); `@ConsistentCopyVisibility` with `internal
  constructor` on every quote type.
- `ByteArray` holders with copied-in state, hand-written `contentEquals`
  equality and a comment saying why they are not data classes (`SafeTx.kt:32-57`).
- Secrets as plain classes with identity equality and a redacted `toString`
  (`Mnemonic`, `WalletPassword`, `Bip32.ExtendedKey`), zeroed in `finally`.
- `UInt256` with `...OrNull` arithmetic and an argued bound wherever an
  operator is used instead.
- Cancellation discipline: the rethrow before every broad catch,
  `ensureActive()` after `catch (e: Throwable)` around native calls,
  `NonCancellable` only around sign-and-record steps with the reason stated.
- `stateIn(viewModelScope, WhileSubscribed(5_000, replayExpirationMillis = 0))`
  at 25 sites, each with a comment saying why no old balance may stand as
  current.
- Injected seams: `TimeSource`, `clock`, `dispatcher`, `Random`, `formatTime`
  as constructor defaults; `fun interface PasswordStretch` as the test seam.
- Flow shape: cold polls ending in `distinctUntilChanged().flowOn(dispatcher)`,
  `transformLatest`/`mapLatest` for re-reads, `shareIn(replay = 1)` to collect
  once for two consumers, `withTimeoutOrNull(poll) { refreshes.first { } }` to
  cut a poll short.
- Interface delegation for decorators (`HistoryNotingAccounts :
  AccountRepository by accounts`).
- Compose: `rememberUpdatedState` + `LaunchedEffect(key)` for one-shot
  callbacks, `LifecycleEventEffect` for hide/show, a remembered actions holder
  per ViewModel, the `Unavailable` semantics extension that gives the
  greyed-out rule one implementation, named lambda parameter types
  (`onTurnOnNetwork: (chainId: Long) -> Unit`).
- Serialization: private DTOs per adapter, a lenient version read before a
  strict decode, entries kept as `List<JsonElement>` so one bad entry is set
  aside rather than the file lost, string codes instead of enum names.
- KDoc on everything public, with the ruling behind each invariant cited.
- Gradle: configuration-cache-safe scripts with reasons on every line that
  needs one.

## Checked and accepted

Patterns that look suspicious in a grep and are fine in context, so a later
review does not re-flag them.

- `x?.flag == true` and `!= true` on nullable receivers (107 sites): the
  idiomatic null-safe test. Only the two `!= false` double negatives (V-17)
  and the `debugShown: Boolean?` tri-state (C-15) are listed.
- `while (true) { ...; delay() }` polling inside `flow {}` or `collectLatest`
  (domain, data, `pointerInput`): the engine exposes no push API; the polls
  are cold, cancellable and deduplicated.
- `catch (e: Throwable)` around JNA and cinterop calls: those fail with
  `Error`s; every one is paired with `ensureActive()` or a rethrow.
- `Dispatchers.IO` defaults on the `File*` adapters and `EnvelopeKeyStore`:
  constructor parameters, injectable. `Dispatchers.Main.immediate` in
  `collectAsStateWithLifecycle` in nine Routes: the documented text-field fix.
- `runBlocking` in the iOS `MyotisHost.shutdown()` and at app termination
  (`SerotineWallet.kt:291`): blocking by contract, bounded, documented.
- The single-writer `@Volatile var state` in `ChainNode`.
- `withContext(NonCancellable)` around "sign and record" and the `safely`
  wrappers: a signed transaction must be recorded even if the screen is popped.
- `var` locals inside `flow {}` builders and `collect` lambdas: per-collector
  state, never shared.
- Positional arguments to the engine's Java constructors (`EngineConfig`,
  `EnginePorts`), each commented, and to the data tables (`BuiltInTokens`,
  `ChainlinkFeeds`).
- `Mnemonic` and `WalletPassword` deliberately not data classes.
- Topic-named multi-declaration files follow the Kotlin style guide; X-11 is
  only about their size.
- `object`s as namespaces for ABI encoders (`Erc20`, `SafeCalls`,
  `SwapRouter02`): they pin selectors beside the functions that use them.
- The `;` on its own line in `ControlBadge.kt:41`, `Network.kt:67`,
  `SafeTx.kt:16`, `UniswapV3.kt:38`: the enum-body separator, required syntax.
- Plain classes holding lambdas (`MenuAction`, `SpeedDialAction`,
  `CreateSafeActions`): data-class equality over lambdas is meaningless.
- One public intent function per intent on a ViewModel rather than a sealed
  `Intent` type: a legitimate, consistently applied shape.
- User-facing strings in private companions of ViewModels: by project design
  (display-ready leaves, English only).
- `AppGraph`'s 40-parameter constructor: the documented manual-DI design; the
  file reads as a graph should.
- The 13 parallel `*Reading` families with `Syncing`/`Verified`/`Unavailable`
  leaves: each carries a different payload, and the interface-segregation rule
  wants one named result per port.
- `NSRecursiveLock` and a hand-rolled `locked {}` on iOS: Kotlin/Native has
  no `@Synchronized`; the duplication is noted under A-07.
- `@Suppress("UNCHECKED_CAST")` at `IndexedHistories.kt:100` and the ten
  other suppressions: each on a platform name or a justified cast.
- `fun import()` on `SafeImportViewModel`: a soft keyword, legal and clear.

## If the owner wants the findings worked

Suggested order, by value against risk. Each is its own PR; none is started
by this review.

1. X-01: one formatting pass and `ktlintCheck` in the build. Mechanical;
   every "ktlint" item above disappears.
2. X-06 and H-01: the `backstop`/`reportingFailure` helpers. Small, removes
   about 90 copies, and settles the backstop policy.
3. A-02, V-01, V-07: the read-modify-write on `@Volatile` fields and the
   side effects inside flow pipelines. Small changes in trust-critical code.
4. X-02: `TxHash`, `Topic`, `Selector` value classes. A wide but mechanical
   change; the compiler finds every site.
5. V-02: the shared review/sign collaborator for the eight ViewModels, then
   X-05's long bodies. The largest change and the largest payoff; it should
   follow a feature-branch plan.
6. A-01: the common `EngineHost` core. Needs a Mac to build the iOS half.
7. C-01 and C-02: typed routes, nullable leaves instead of `""`.
8. The rest, as files are touched.

## Aside, outside this review's scope

Found while reading, not a Kotlin matter, recorded for the owner.

- The trust rules (`CLAUDE.md`, *Trust*; `.claude/rules/trust.md`, *A Safe's
  fallback handler is set only to a pinned one*) say a Safe transaction that
  sets the fallback handler to none, the zero address, "is shown and never
  signed, approved or executed", citing the owner ruling of 2026-10-18, #63.
  `domain/.../model/PinnedFallbackHandlers.kt:31-32` returns `allowed` for
  `null` and for `Address.ZERO`, and its KDoc says the same ruling allows
  none; `SafeTxAction.kt:114, 119` maps the zero address to `null`, and
  `SigningRequest.unsignable` (`usecase/SafeTxSigning.kt:243-246`) therefore
  does not refuse a removal. The code and its KDoc agree with each other; the
  rule text says the opposite. Which is right is the owner's to say.

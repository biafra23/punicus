<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/serotine_logo_dark.svg">
    <img src="assets/serotine_logo.svg" alt="Punicus logo" width="200">
  </picture>
</p>

# Punicus

A Compose Multiplatform wallet for Ethereum accounts (EOAs) and
[Safe{Wallet}](https://safe.global) smart accounts, built on an embedded
[Myotis](https://github.com/biafra23/myotis) engine: every chain read is
verified against Ethereum's sync committee by a light client running inside
the app, with no RPC provider in the loop. Android, desktop (Linux, macOS,
Windows) and iOS (a development build for now). It works on Ethereum and
Gnosis Chain, with real funds, and on the Sepolia testnet; each account lives
on one chain. A release build starts with Ethereum and Gnosis Chain on and
Sepolia off, a debug build with Sepolia alone, and each network can be turned
on or off in Settings.

## Status

Early, with pre-releases only (the version is `0.4.0-SNAPSHOT`; every `v*` tag
publishes a pre-release).
What the app does today:

- **Simple mode** is the default view in both builds; Settings → Advanced →
  *Detailed view* shows the screens that say how the wallet checks the chain
  (docs/simple-ui.md).
- **Accounts.** Create an account from a new recovery phrase, import one from
  a phrase, add further accounts from a phrase already held, or watch any
  address, which is told apart from verified code and
  storage as an EOA (delegated or not), a Safe or another contract. Accounts
  are kept across restarts and can be renamed and removed.
- **Keys.** An imported or created seed is held in an envelope that opens only
  with the Argon2id-stretched wallet password AND, where the device has one,
  a secret of its secure hardware (the Android Keystore, the Secure Enclave
  of an iPhone or a Mac); Windows and Linux stand on the password alone for
  now, and the app says so. Biometric unlock (Android, Face ID or Touch
  ID on iOS, and Touch ID on a Mac) is an opt-in, bound to a hardware key (docs/keys-and-signing.md).
- **Verified balances.** ETH and token balances, token metadata and prices are
  read through the embedded light client and labelled with the block they were
  proven at; while no fresh verified head exists, the last proven balance stays
  on screen, dated and marked as possibly changed. A built-in token list with
  bundled icons, tokens added by address, and a display currency priced by
  Chainlink feeds read on chain; where no feed is pinned, a pinned Uniswap
  pool's 30-minute average, labelled as such. Sepolia's prices are labelled
  "testnet prices", and its balances never count in a total with real funds. An Android
  home-screen widget shows one verified exchange rate.
- **Send and receive.** ETH and ERC-20 transfers with a verified nonce, fee and
  gas estimate, a review step, and a status screen that follows the verified
  receipt to finalization; a recipient may be an ENS name, resolved with a
  verified read. A QR code to receive.
- **Safe{Wallet}.** Watch an existing Safe (recognised only when its proxy
  and singleton match the pinned 1.3.0, 1.4.1 or 1.5.0 deployments) or
  create a Safe 1.4.1 (its address predicted locally, the deployment paid by
  an account held here). Propose, sign, co-sign and execute Safe
  transactions: a signing request travels between devices as a QR code or
  text, never through the Safe Transaction Service, and each co-signer
  decodes and checks it against the chain; an owner may approve on chain
  instead. A transaction that would set the fallback handler to anything but
  the pinned one or none (the zero address removes it), or enable a module,
  is shown and never signed, approved or executed here.
- **Swaps on Uniswap v3** from an account or a Safe: verified quotes from the
  pinned contracts, an exact-amount approval, a slippage tolerance (0.5 % by
  default) and the price impact, with the amount received read from the
  verified receipt. Or through **CoW Protocol**, when the user chooses it: its
  order API is used only for an order sent to CoW (docs/swaps.md).
- **Activity.** Every transaction this device sent, kept for good with how it
  ended as the device saw it, and, from the engine's verified log index, an
  account's token transfers and a Safe's own events on chain, CoW orders
  among them.
- **Network upgrades.** When peers announce a network upgrade this version
  does not support, the app warns ahead of it, notifies (on Android also while
  it is closed), and once the upgrade has cut it off shows nothing but the way
  to the update.
- **dApp connections**, on Sepolia, over WalletConnect v2. A dApp connects
  with a pasted `wc:` URI (or a `wc:` link on Android), and sees only the
  accounts the user chooses. It can ask to sign in, to sign typed data, and
  to send a transaction: from an account held here, or proposed from a Safe
  as a Safe transaction. Each is reviewed from verified state before
  anything is signed, and `eth_sign` is refused.

Not built yet: ETH an EOA received and transactions sent from it elsewhere
in Activity, a Safe owned by a device's hardware P-256 key, recovery modules,
an external wallet as a Safe owner's signer, and an iOS build for the App
Store.
docs/wallet-ux.md describes every screen, with what is built and what is not.

**What leaves the device.** The wallet never takes chain data from a server:
no RPC provider, no indexer. Two optional features use a third-party
service. A swap the user sends to CoW Protocol goes to CoW's order API
(docs/swaps.md). dApp connections go through WalletConnect's relay: today a
centralised service, run by Reown and a selected group of operators. It
carries end-to-end encrypted messages only. It sees the device's IP address
and when it is connected, never what is signed, and it cannot sign anything
(docs/walletconnect.md). The embedded Myotis engine itself uses neither.

## Build & run

```bash
./gradlew build                          # every module: compile, unit + UI tests, Android lint
./gradlew :desktop-app:run               # the desktop app, in-process
./gradlew :android-app:installDebug      # the debug app (eth.serotine.debug) on a connected device
./gradlew :desktop-app:packageDeb        # the Linux installer   (on Linux)
./gradlew :desktop-app:packageDmg        # the macOS installer   (on macOS)
./gradlew :desktop-app:packageMsi        # the Windows installer (on Windows, WiX 3 on the PATH)
./gradlew :ui:desktopTest --tests '*PreviewsTest' --rerun -Pscreenshots=build/screenshots
                                         # every screen's previews as PNGs

# The iOS app (on macOS, with Xcode): generate its Xcode project, then build and run it from Xcode
brew install xcodegen
cd ios-app/xcode && SEROTINE_DEVELOPMENT_TEAM=<team id> xcodegen generate && open Serotine.xcodeproj
```

Needs JDK 21 and, for the Android modules, an Android SDK (`ANDROID_HOME`, or
`sdk.dir` in a git-ignored `local.properties`). The iOS targets of the shared
modules and the iOS app build on macOS only and are skipped elsewhere. The
team id (Xcode > Settings > Accounts) signs the iOS app for a device; the
simulator needs none.

## Layout

| module | role |
|---|---|
| `domain` | entities, value objects, ports (repository interfaces) and use cases — pure Kotlin |
| `data` | the adapters behind the ports: the files the wallet keeps (accounts, tokens, the Safe queue, sent transactions, the known network upgrade, settings), the key store, the pinned Safe, Uniswap and Chainlink deployments, and the Myotis engine adapter |
| `ui` | the shared Compose Multiplatform UI: screens, ViewModels, UI state, `AppGraph` |
| `android-app` | the Android host (package `eth.serotine`; the debug build is `eth.serotine.debug`) |
| `desktop-app` | the desktop JVM host and the jpackage configuration for the three installers |
| `ios-app` | the iOS host: the SerotineKit framework (its composition root) and the Xcode shell's XcodeGen spec (`xcode/`) |

The dependency rule, the MVVM contract and how to add a feature:
[docs/architecture.md](docs/architecture.md). How keys are held, what the
user chooses between, and what a hardware key can and cannot guarantee:
[docs/keys-and-signing.md](docs/keys-and-signing.md). The rules every change
goes through (review, PR, CI, release): [CLAUDE.md](CLAUDE.md), the short
form, with the full text under [`.claude/`](.claude/). The screens
and the rules they follow: [docs/wallet-ux.md](docs/wallet-ux.md); swaps:
[docs/swaps.md](docs/swaps.md); dApp connections:
[docs/walletconnect.md](docs/walletconnect.md); the light client inside the app:
[docs/embedding-myotis.md](docs/embedding-myotis.md).

## Releases

Every `v*` tag publishes a pre-release with the Android APK signed with
Punicus's release key, the debug APK, the
macOS dmgs (arm64 + x64) and the Linux debs (x64, and arm64 while the
repository is public). The Windows msi joins them once Myotis ships a Windows
engine library; until then it is built on `main` but not published. The
tag's version must match the `version` in `build.gradle.kts`
(release-version-guard.yml refuses otherwise), and the Android release key
lives only in the `android-release` environment
([docs/android-release-signing.md](docs/android-release-signing.md)). Its
certificate's SHA-256 is pinned in `android-app/release-signing-cert.sha256`;
`apksigner verify --print-certs` on a downloaded `Punicus-<tag>-android.apk`
must print the same digest.

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).

# Changelog

Every release of Punicus (called Serotine before 0.4.0), newest first. The
format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
versions follow [Semantic Versioning](https://semver.org/). Each release's
installers are on its [GitHub release](https://github.com/biafra23/punicus/releases);
every release so far is a pre-release. The release PR adds its entry here
(`.claude/skills/release/SKILL.md`); numbers in parentheses are pull requests
and issues of this repository.

## [Unreleased]

### Added

- Funding: a Sponsor button and a Funding section in the README (#280, #296).
- A privacy policy, `docs/privacy-policy.md`, linked from the README (#296).
- Gradle dependency locking and checksum verification: lockfiles for every
  project and the build logic, `gradle/verification-metadata.xml`, the Gradle
  distribution's SHA-256 in the wrapper, and the `gradle pins` check that
  fails a build on a dependency that is not pinned (#281).
- This changelog (#281).

### Fixed

- Watch address: an address already in the wallet shows that account on an
  "already in your wallet" card (#273, #296).
- An empty wallet runs its light clients from the start and shows them, so
  the first account added is not left waiting for a sync (#66, #296).

## [0.4.0] - 2026-11-10

The first release under the name Punicus.

### Added

- Lenient mode, off by default: prepare and sign a transaction while the
  light client syncs; nothing is sent before a check at a fresh head (#258, #269).
- NFTs: an account's NFTs below its tokens, found in its verified log index,
  with ENS names decoded (#246, #263).
- WalletConnect on every network: Ethereum and Gnosis Chain together, or
  Sepolia alone (#259).
- Android: scan a dApp's WalletConnect QR code, and open `serotine://wc` deep
  links (#245).
- Send from another wallet: hand a transfer to MetaMask or another wallet app
  as an `ethereum:` payment link (#255, #257).
- Settings: heavy downloads on a metered connection are opt-in; the history
  walk waits for them (#85, #272).
- Swap: the amount to receive and the rate are shown while composing (#237).
- Activity: every row is dated and ordered by date, the order can be turned
  round from the menu, and Safe transactions are decoded (#235).
- Settings shows a pre-release's version (#236).

### Changed

- The displayed name is Punicus, no longer Serotine (#276).
- Every known Safe release and proxy build is recognised on Ethereum, Gnosis
  Chain and Sepolia (#277).
- Swap: ETH for WETH and back is a wrap through WETH9, not a refusal (#238).
- The network stays on briefly after the app leaves the foreground (Android
  60 s, iOS 25 s) (#239).
- One WalletConnect project ID for debug and release builds (#244).
- Myotis engine 0.1.20 (#270).

### Fixed

- Activity: the network's log of an ETH transfer is shown as ETH, not as an
  unlisted token (#271).

## [0.3.0-rc1] - 2026-11-04

### Added

- dApps connect over WalletConnect v2, on Sepolia (#22, #231).
- Hide amounts: a closed eye at the top left masks every amount (#215).
- The Android release certificate is pinned (#214).
- A security-invariants check in CI (#217).

### Changed

- Send: the asset is chosen from a dropdown, not a radio list (#232).
- Myotis engine 0.1.20-rc1; account answers carried by block access lists
  are anchored (#233).

## [0.2.0] - 2026-10-31

### Added

- Networks: Ethereum and Gnosis Chain beside Sepolia, one chain per account
  (#145, #173).
- CoW Protocol swaps beside Uniswap; the user chooses the venue (#144, #156).
- Safe settings: add, replace or remove an owner, change the threshold,
  disable a module, remove the guard (#185).
- Accounts: add further accounts of a stored recovery phrase (#211).
- Touch ID unlock on a Mac's Secure Enclave (#157).
- Simple mode by default, with the detailed view opt-in (#210).
- GNO, COW and SAFE on every network they exist on, priced by a pinned
  Uniswap pool's 30-minute average where no Chainlink feed is pinned (#199,
  #205, #208).
- Built-in Sepolia tokens: EURC, PYUSD, WETH, GHO and Aave's DAI, USDT, WBTC
  and AAVE, each with an icon (#143).
- Rate widget: choose the network, the price and the currency; the watched
  asset's icon in its corner; a filter in its setup (#187, #200, #212).
- Account detail: Activity as a tab (Assets, Activity, Owners, Queue), the
  assets sorted from the top bar's menu, and Send, Swap and Receive on one
  line (#139, #142, #149).
- A watched account is named by the ENS name it was added by, and the name is
  kept checked (#138).
- Debug tab: a log of every Myotis API request, turned on in Settings (#146).
- Punicus's logo and app icons (#140).

### Changed

- Past the weak-subjectivity window, an answer is used only on the user's
  word, on every network (#175, #176).
- A balance or price is proved again only when a verified block may have
  changed it (#163).
- Safes deployed before 1.3.0 (1.0.0, 1.1.1, 1.2.0) are recognised (#198).
- The swapped assets are chosen from a dropdown menu (#159).
- Ghost rows hold a list's room while its rows are read (#152).
- Widget: a smaller refresh button beside the 24h change (#137).

### Fixed

- Debug filter: the cursor stays while typing, and the text across tab
  switches (#184).

## [0.1.0] - 2026-10-21

The first pre-release: a wallet for Ethereum accounts and Safe{Wallet} smart
accounts on Sepolia, on the embedded Myotis light client, for Android, desktop
(macOS, Linux) and iOS.

### Added

- Wallet UX for EOAs and Safe{Wallet} accounts (#77).
- In-app swaps on Uniswap v3, from an account and from a Safe (#99).
- Transaction history from the verified log index: a Safe's events and every
  token transfer (#117), and the transactions this device sent, kept with how
  each ended (#102).
- Activity: a row opens its detail, with every address and hash to copy, and
  Add token for an unlisted ERC-20 (#136).
- The last verified balance is shown, dated and kept across restarts, and the
  light-client strip is coloured by what it can verify (#84); the strip is on
  every screen that depends on it (#119).
- Watch address: an ENS name is accepted, resolved with verified calls (#121).
- Warnings before a network upgrade this version does not support (#23, #30).
- Android: a home-screen widget with the ETH rate and its 24h change (#135).
- The iOS app, on the embedded Myotis engine (#125, #131, #134).
- Settings: a debug-build switch that allows screen recordings (#132); the
  engine's own log in the debug builds (#129).
- Safe transactions that remove the fallback handler carry a warning (#113).

### Changed

- The Assets screen is named Accounts; a tap on an asset row opens Send (#124).
- A Safe's check is retried within a budget and made before a key is asked
  for (#81, #82).
- Form buttons stay reachable above the on-screen keyboard (#127).
- Control badges: the icon beside the text, the icon alone where the text
  would wrap (#128).

[Unreleased]: https://github.com/biafra23/punicus/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/biafra23/punicus/compare/v0.3.0-rc1...v0.4.0
[0.3.0-rc1]: https://github.com/biafra23/punicus/compare/v0.2.0...v0.3.0-rc1
[0.2.0]: https://github.com/biafra23/punicus/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/biafra23/punicus/releases/tag/v0.1.0

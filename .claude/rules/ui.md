---
description: "The rules of the shared Compose UI: a screen's files, the greyed-out rule, previews, what a screen may say about the chain, secrets on screen, screenshots for a PR."
paths:
  - "ui/**/*"
  - "docs/wallet-ux.md"
  - "docs/networks.md"
  - "docs/swaps.md"
  - "docs/walletconnect.md"
---

# UI rules

Loaded when you work in `ui/` or on docs/wallet-ux.md. **docs/wallet-ux.md is
the contract for the screens**: what each screen shows, and the rules every
screen follows. Where that page and the rules under `.claude/` disagree, the
rules win and the page is fixed. The architecture rules (MVVM, the dependency
rule), the trust rules and the data-source rules load beside this file.

## A screen's files

One package per screen, `eth.serotine.ui.<feature>`:

| File | Holds |
|---|---|
| `<Feature>UiState.kt` | the immutable, display-ready state: a sealed interface or a data class |
| `<Feature>ViewModel.kt` | `StateFlow<UiState>` from use cases; intents call use cases; no platform reference |
| `<Feature>Screen.kt` | the stateless screen, a pure function of the UiState |
| `<Feature>Route.kt` | the one composable that knows the ViewModel; binds state and lifecycle to the screen |
| `<Feature>ScreenPreviews.kt` | one preview per state plus one in the dark theme (below) |

`components` holds what several screens draw; it has no ViewModel and no use
case. `App` holds the one `NavHost`, and only `App` holds the `NavController`.

## A missing prerequisite greys an action out, never hides it

(Owner ruling, 2026-10-18, #55.)

- An action that is impossible because something it needs is missing is
  shown disabled.
- Selecting it still works: a tap on the greyed item tells the user what is
  missing and how to get it (`components/Unavailable.kt`:
  `UnavailableDialog`, with the ways to get it where there are some).
- The UiState carries the enabled state and a display-ready reason.
- Only an action that does not apply at all (to that kind of account, in that
  step) is left out.
- The actions issue #74 lists are known not to follow this yet. That list,
  not this file, says which.

## Every screen has previews

(Owner's request, 2026-10-17.) Android Studio renders them, and a test
renders them in the build.

- `<Feature>ScreenPreviews.kt` sits next to the screen.
- One private `@Preview` per state the screen draws differently, plus one in
  the dark theme.
- Each preview wraps the screen in `SerotineTheme` and hands it a hand-made,
  display-ready UiState, as the screen tests do. Never a ViewModel, a use
  case or an adapter.
- The file ends with the internal list of its previews
  (`<feature>ScreenPreviews`). A new preview goes in that list too.
  `<Feature>ScreenPreviewsTest` (`ui/src/desktopTest`) renders the list, so a
  broken preview fails the build.
- The annotation is the multiplatform
  `org.jetbrains.compose.ui.tooling.preview.Preview`. androidx's does not
  compile in `commonMain` on Compose Multiplatform 1.9.
- Sample data is made up: never a real or published seed phrase, key or
  password.
- Routes and `App` get no previews: they need a ViewModel or an `AppGraph`.
- A screen without previews is a finding.
- The ui-tooling that renders them lives in `:ui`'s debug variant only.
  `:android-app` keeps it out of every APK (its exported `PreviewActivity`,
  android-app/build.gradle.kts), which the APK workflow checks in the built
  manifests.

## What a screen may say about the chain

The trust and data-source rules decide this. The short form:

- A chain value is verified by the light client, or shown as "not available".
  Never a guess, never a value from another source.
- The one exception is a balance proved earlier: shown dimmed, dated, marked
  as possibly changed since, left out of totals, and never acted on. In
  simple mode a balance read a while ago on a network without block access
  lists shows its age and counts in totals for up to an hour
  (`.claude/rules/trust.md`).
- A network-upgrade warning says that peers report it.
- Fiat values are display-only. On Sepolia every fiat value says "testnet
  prices". A token without a feed shows "no price", unless a pool's average
  prices it, labelled (owner ruling, 2026-10-29, #205).
- Sepolia keeps every testnet warning and is never added to a total with real
  funds.
- A screen takes its network from the account it shows. There is no current
  network.

## Two views: simple mode and Detailed view

(Owner's decisions, 2026-10-26, mockups approved 2026-10-27;
docs/simple-ui.md.)

- Simple mode is the default in every build. Settings → Advanced → *Detailed
  view* brings back the detailed screens. It is opt-in.
- Both views draw the same UiState from the same ViewModel. The Route picks
  `Simple<Feature>Screen` or `<Feature>Screen`. A screen that has no simple
  version yet draws the detailed one.
- Where simple mode says something else, the UiState carries a display-ready
  leaf for it. A screen never interprets a mode flag.
- Simple mode shows no block numbers on its screens. A value the user acts
  on names its age ("Updated 2 min ago") instead of its block. The block
  stays one tap away, under *Details*.
- Simple mode replaces the light client's status strip with a freshness line,
  a banner when a network cannot be checked, and each network's state in
  Settings. Detailed view keeps the strip (owner's request, 2026-10-20).
- In simple mode the sync status is visible on every screen where it decides
  what the user can see or do: a sync line while the network's light client
  cannot serve verified reads. A read refused because it is still syncing
  says so, never "this time" (owner's requests, 2026-10-29).
- In simple mode an account's blockie carries its network's icon, and a
  Safe's keeps its `t/n` threshold (owner's request, 2026-10-27). The network
  is named in text too, so the icon is never the only signal.
- A simple screen has its own previews file and previews test, like any
  screen.

## Secrets on screen

The UI never touches a key. Signing is a port: a request in, a signature out.
The password field and the recovery phrase are the only secrets a screen
holds, under the mnemonic rules in `.claude/rules/trust.md`, and the screens
that show them call `protectFromScreenCapture()`.

## A PR that changes the UI needs screenshots

Follow `.claude/skills/screenshots/SKILL.md`. A PR with no visible change says
"No UI change" in its description, so a reader can tell an omission from a
decision.

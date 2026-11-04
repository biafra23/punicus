---
description: "The host apps: one composition root each, the app-wide work, the platform-output ports, the two Android apps and their signing, the desktop instance lock, the iOS project."
paths:
  - "android-app/**/*"
  - "desktop-app/**/*"
  - "ios-app/**/*"
---

# The hosts

Loaded when you work in `android-app/`, `desktop-app/` or `ios-app/`. The
architecture rules load beside this file.

## One composition root per host

- `SerotineApplication` (Android), `main()` (desktop), `SerotineWallet` (iOS,
  in the SerotineKit framework).
- It builds the `AppGraph` from `:data`'s adapters and renders `App` (on iOS
  through `MainViewController(wallet)`). It is the only place in its app that
  knows which adapter implements which port.
- It owns the engine's lifecycle: one Myotis engine per process.
- It runs the app-wide work no screen owns, beside the `AppGraph`:
  `FollowUpgradeReports`, `FollowSafeDeployments`, and on Android the
  exchange-rate widget's `FollowExchangeRate`. Never from `App` or
  `AppGraph`.
- A port whose adapter is the platform's own output is implemented in the
  host: `UpgradeNotifier` (Android's notifications and alarm, the desktop's
  tray, iOS's local notifications). So is a home-screen widget: Android's
  `RateWidget` draws `:ui`'s display-ready `rateWidgetContent`.

## Android

- Two apps come out of `:android-app` on purpose. The release one
  (`eth.serotine`) is signed with the release key, which never enters the
  repository. The debug one (`eth.serotine.debug`, "Serotine Debug", amber
  icon) is signed with the committed, public debug keystore.
- **Never give the release build type a `signingConfig`**
  (docs/android-release-signing.md). The release APK is signed on a tag by
  the `sign-release` job of android-apk.yml, with the key in the
  `android-release` environment.
- minSdk 29. The JDK-API budget and the Java 17 rule are in
  `.claude/rules/platform.md`.
- The preview tooling stays out of every APK: `:android-app` keeps its
  exported `PreviewActivity` out (`.claude/rules/ui.md`, *Every screen has
  previews*).
- `versionName` and `versionCode` derive from the one version pin in the root
  `build.gradle.kts` (`releaseBuildNumber`: `X*1_000_000 + Y*1_000 + Z`).

## Desktop

- The desktop app runs once per user: it holds an `InstanceLock` on its data
  directory for the process's lifetime.
- jpackage is host-OS-bound: `packageDeb` on Linux, `packageDmg` on macOS,
  `packageMsi` on Windows (WiX 3 on the PATH). The deb and msi versions and
  the dmg's `+1`-major version derive from the version pin.
- A desktop without a hardware key store stands on the password path alone
  (`.claude/rules/trust.md`, *How keys are held*).

## iOS

- The SerotineKit framework holds the composition root. `xcode/` holds the
  SwiftUI shell's XcodeGen spec (`project.yml`); the Xcode project is
  generated, not committed:
  `cd ios-app/xcode && SEROTINE_DEVELOPMENT_TEAM=<team id> xcodegen generate`.
- The Xcode build links SerotineKit through Gradle and stamps the app's
  version and build number from `:printReleaseVersion`.
- The team id signs for a device; the simulator needs none.
- iOS's key envelope waits for the iOS app (owner ruling, 2026-10-16).

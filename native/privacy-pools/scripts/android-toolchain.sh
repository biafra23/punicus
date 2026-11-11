#!/usr/bin/env bash
# Installs what :data's buildPrivacyPoolsAndroid needs to build this library for Android (docs/privacy-pools.md,
# Part 2c): the NDK gradle/libs.versions.toml pins, into the Android SDK at $ANDROID_HOME (sdkmanager does
# nothing if it is there), and rustup's targets for the four ABIs the app ships, for the toolchain this
# crate's rust-toolchain.toml names. The one place both lists live outside the build: CI runs it
# (ci.yml, android-apk.yml), and so can a developer.
#
#   ANDROID_HOME=<sdk> native/privacy-pools/scripts/android-toolchain.sh
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
root="$(cd "$here/../.." && pwd)"

ndk=$(sed -n 's/^ndk = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")
[ -n "$ndk" ] || { echo "no ndk pin in gradle/libs.versions.toml" >&2; exit 1; }
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
# sdkmanager only when the NDK is missing: an SDK from Android Studio may have no cmdline-tools, and
# the targets below still need adding there.
if [ ! -f "$ANDROID_HOME/ndk/$ndk/source.properties" ]; then
  sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  [ -x "$sdkmanager" ] || {
    echo "NDK $ndk is not installed, and there is no sdkmanager at $sdkmanager to install it" >&2
    echo "(install the SDK's command-line tools, or the NDK from Android Studio's SDK Manager)" >&2
    exit 1
  }
  # Its output only when it fails, so that a refusal (a licence not yet accepted) says why. stdin is
  # closed: a licence prompt ends the install instead of waiting for an answer nobody sees.
  log=$(mktemp)
  if ! "$sdkmanager" --install "ndk;$ndk" < /dev/null > "$log" 2>&1 \
      || [ ! -f "$ANDROID_HOME/ndk/$ndk/source.properties" ]; then
    cat "$log" >&2
    rm -f "$log"
    echo "NDK $ndk did not install (accept the SDK's licences with \`sdkmanager --licenses\` first?)" >&2
    exit 1
  fi
  rm -f "$log"
fi
echo "NDK $ndk at $ANDROID_HOME/ndk/$ndk"

# The Rust targets of data/build.gradle.kts's privacyPoolsAndroidAbis, in the same order.
cd "$here"
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android

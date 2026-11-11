#!/usr/bin/env bash
# Builds the iOS static library (docs/privacy-pools.md, Part 2e) for one Rust target, as :data's
# buildPrivacyPoolsIos* tasks call it on a Mac:
#
#   scripts/build-ios.sh <aarch64-apple-ios|aarch64-apple-ios-sim> <cargo target dir> <out dir>
#
# The Myotis engine's libmyotis_engine.a, linked into the same framework, is a Rust static library
# too, and exports _rust_eh_personality and compiler-rt's builtins unmangled; each carries its own
# standard library. Linked as cargo makes it, this library would define the same symbols, and the
# link refuses duplicates. So the archive cargo makes is linked relocatably (ld -r) with only the six
# functions of ../ios-exports.txt exported: every other symbol becomes local to this one object,
# which nothing else can see or collide with, the standard library, the allocator and the panic
# machinery included. The object is archived as <out dir>/libprivacy_pools.a, and the script fails
# unless its defined global symbols are exactly those six.
#
# IPHONEOS_DEPLOYMENT_TARGET (the app's, from :data) sets the minimum iOS for rustc and for the C
# that zstd-sys and blake3 compile.
set -euo pipefail

triple=${1:?Rust target}
target_dir=${2:?cargo target dir}
out=${3:?out dir}
case "$triple" in
  aarch64-apple-ios) sdk=iphoneos ;;
  aarch64-apple-ios-sim) sdk=iphonesimulator ;;
  *) echo "unsupported target $triple" >&2; exit 1 ;;
esac
: "${IPHONEOS_DEPLOYMENT_TARGET:?set IPHONEOS_DEPLOYMENT_TARGET to the minimum iOS of the app}"

here="$(cd "$(dirname "$0")/.." && pwd)"
cd "$here"
# The target first, with the command that adds it: cargo's own error names neither.
rustup target list --installed | grep -qx "$triple" || {
  echo "rustup lacks the target $triple: run \`rustup target add aarch64-apple-ios aarch64-apple-ios-sim\`" >&2
  echo "in native/privacy-pools (the iOS build of :data needs both)" >&2
  exit 1
}
cargo rustc --release --lib --locked --crate-type staticlib --target "$triple" --target-dir "$target_dir"
built="$target_dir/$triple/release/libprivacy_pools.a"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# Every member of cargo's archive, merged into one object; only the listed symbols stay global.
xcrun --sdk "$sdk" ld -r -arch arm64 -exported_symbols_list "$here/ios-exports.txt" \
  -all_load "$built" -o "$work/privacy_pools.o"
mkdir -p "$out"
rm -f "$out/libprivacy_pools.a"
xcrun --sdk "$sdk" libtool -static -o "$out/libprivacy_pools.a" "$work/privacy_pools.o"

# The guarantee itself: the archive defines exactly the six functions as globals.
got=$(xcrun --sdk "$sdk" nm -gU "$out/libprivacy_pools.a" | awk 'NF == 3 { print $3 }' | sort)
want=$(sort "$here/ios-exports.txt")
if [ "$got" != "$want" ]; then
  echo "libprivacy_pools.a ($triple) defines other globals than ios-exports.txt:" >&2
  diff <(echo "$want") <(echo "$got") >&2 || true
  exit 1
fi
echo "built $out/libprivacy_pools.a ($triple): globals $(echo "$got" | tr '\n' ' ')"

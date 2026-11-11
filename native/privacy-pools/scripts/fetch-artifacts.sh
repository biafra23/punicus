#!/usr/bin/env bash
# Fetches the Privacy Pools circuits' proving and verification keys into native/privacy-pools/artifacts/
# (git-ignored), from the npm package they are published in, and refuses them unless every hash
# matches its pin (owner ruling, 2026-11-11, D14: bundled and pinned, never fetched by the app;
# where the build takes them from is D14a, still open: this script is Part 2a's proposal).
#
# The package's tarball is checked against npm's sha512 integrity, and each file against the
# sha256 the library pins (src/artifacts.rs). The files are byte-identical to 0xbow
# privacy-pools-core df4bf730, packages/circuits/trusted-setup/final-keys/.
#
# Runs on bash 3.2 (macOS's /bin/bash) and on GNU or BSD tools.
#
# Usage: native/privacy-pools/scripts/fetch-artifacts.sh [out-dir]
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="${1:-$here/artifacts}"

tarball_url="https://registry.npmjs.org/@fatsolutions/privacy-pools-core-circuits/-/privacy-pools-core-circuits-1.0.5.tgz"
tarball_sha512="d04d79a7dc265a45171293cbbb0edcae9a77d35c4d84bcc29e3aef233a88cbfc44f39148b1d6d37605865b463d4f0c23a711ac2e36828a6c93ba361bb736b864"

# name sha256, as src/artifacts.rs pins them
pins="commitment.zkey 494ae92d64098fda2a5649690ddc5821fcd7449ca5fe8ef99ee7447544d7e1f3
commitment.vkey 7d48b4eb3dedc12fb774348287b587f0c18c3c7254cd60e9cf0f8b3636a570d8
withdraw.zkey 2a893b42174c813566e5c40c715a8b90cd49fc4ecf384e3a6024158c3d6de677
withdraw.vkey 666bd0983b20c1611543b04f7712e067fbe8cad69f07ada8a310837ff398d21e"

digest() { # bits file: the hex digest, with GNU coreutils or macOS's shasum
  if command -v "sha$1sum" >/dev/null 2>&1; then
    "sha$1sum" "$2" | cut -d' ' -f1
  else
    shasum -a "$1" "$2" | cut -d' ' -f1
  fi
}

check() { # file expected-sha256
  local actual
  actual="$(digest 256 "$1")"
  [ "$actual" = "$2" ] || { echo "sha256 mismatch for $1: $actual" >&2; return 1; }
}

all_present() {
  local name sha
  while read -r name sha; do
    [ -f "$out/$name" ] && check "$out/$name" "$sha" 2>/dev/null || return 1
  done <<EOF
$pins
EOF
}

mkdir -p "$out"
if all_present; then
  echo "artifacts present and pinned in $out"
  exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "${work:?}"' EXIT
curl --fail --silent --show-error --location --retry 4 --retry-delay 3 -o "$work/package.tgz" "$tarball_url"
actual="$(digest 512 "$work/package.tgz")"
[ "$actual" = "$tarball_sha512" ] || { echo "tarball sha512 mismatch: $actual" >&2; exit 1; }

mkdir "$work/x"
while read -r name sha; do
  tar -xzf "$work/package.tgz" -C "$work/x" "package/artifacts/$name"
  check "$work/x/package/artifacts/$name" "$sha"
  mv "$work/x/package/artifacts/$name" "$out/$name"
done <<EOF
$pins
EOF
echo "artifacts fetched and pinned in $out"

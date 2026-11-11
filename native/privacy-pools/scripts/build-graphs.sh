#!/usr/bin/env bash
# Rebuilds the witness graphs in native/privacy-pools/graphs/ from pinned sources, so the committed
# graphs can be checked byte for byte (graphs/README.md).
#
# - The builder: circom-witness-rs's `circom-witness-graph-builder` at v0.5.1 (5121bdc6), which
#   embeds circom 2.2.2. It is GPL-3.0 and runs here only; the app ships its output. Its repository
#   commits no Cargo.lock, so its own dependencies resolve afresh: the hash check below is the gate.
# - The circuits: 0xbow privacy-pools-core df4bf730, packages/circuits/circuits/, the commit whose
#   trusted-setup keys the npm package carries; circomlib 2.0.5 from npm; graphs/circuits/*.circom
#   (the two `main` components, with the public signals the keys were made for).
# - `--O1`: the level the keys were made at. `--O2` gives 17,513 signals for `withdraw`, which do not
#   fit its .zkey (docs/privacy-pools.md, Part 0).
#
# Usage: native/privacy-pools/scripts/build-graphs.sh [out-dir]   (default: ./graphs-rebuilt)
# Then compare: sha256sum <out-dir>/*.bin native/privacy-pools/graphs/*.bin
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$(mkdir -p "${1:-graphs-rebuilt}" && cd "${1:-graphs-rebuilt}" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "${work:?}"' EXIT

builder_repo="https://github.com/philsippl/circom-witness-rs"
builder_commit="5121bdc66d25052e58bf92c228644d0b29132e47"
circuits_repo="https://github.com/0xbow-io/privacy-pools-core"
circuits_commit="df4bf730214bf9d0c717a9258f7a74861758c269"
circomlib_version="2.0.5"

git -c advice.detachedHead=false clone --quiet "$builder_repo" "$work/builder"
git -C "$work/builder" checkout --quiet "$builder_commit"
(cd "$work/builder" && cargo build --quiet --release -p circom-witness-graph-builder)
builder="$work/builder/target/release/circom-witness-graph-builder"

git init --quiet "$work/src"
git -C "$work/src" fetch --quiet --depth 1 "$circuits_repo" "$circuits_commit"
git -C "$work/src" -c advice.detachedHead=false checkout --quiet FETCH_HEAD -- packages/circuits/circuits
mkdir -p "$work/src/packages/circuits/circuits/main"
cp "$here"/graphs/circuits/*.circom "$work/src/packages/circuits/circuits/main/"

# The circuits include circomlib as ../../../node_modules/circomlib, relative to circuits/.
(cd "$work/src" && npm pack --silent "circomlib@$circomlib_version" >/dev/null \
  && mkdir -p node_modules/circomlib && tar -xzf "circomlib-$circomlib_version.tgz" -C node_modules/circomlib --strip-components 1)

for circuit in commitment withdraw; do
  "$builder" --O1 "$work/src/packages/circuits/circuits/main/$circuit.circom" "$out/$circuit.O1.graph.bin"
done
sha256sum "$out"/*.bin
echo "built into $out"

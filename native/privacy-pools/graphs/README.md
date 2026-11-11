# The witness graphs

`commitment.O1.graph.bin` and `withdraw.O1.graph.bin` compute the witnesses of Privacy Pools'
two circuits. The library loads them with `circom-witness-rs` 0.5.1's runtime (MIT) and refuses
either unless its sha256 is the one pinned in `src/artifacts.rs`.

| File | Bytes | sha256 |
|---|---|---|
| `commitment.O1.graph.bin` | 43,180 | `418086d784c4a55d18ba33bcc5638aae8d8e40b23a45b5737c1eab9e189627de` |
| `withdraw.O1.graph.bin` | 233,384 | `714b27bc92e1cb365dcb46d5e2252c9224a03aafa1b810a125650c8c0477d0b2` |

## Where they come from

`scripts/build-graphs.sh` rebuilds them, and its output equals these files byte for byte
(checked 2026-11-11):

- the builder: `circom-witness-rs`'s `circom-witness-graph-builder` at v0.5.1 (`5121bdc6`),
  embedding circom 2.2.2, GPL-3.0, run at build time only;
- the circuits: 0xbow `privacy-pools-core` at `df4bf730`, `packages/circuits/circuits/`, the
  commit whose trusted-setup keys `@fatsolutions/privacy-pools-core-circuits` 1.0.5 carries;
- circomlib 2.0.5, from npm;
- `circuits/*.circom` here: the two `main` components, with the public signals the keys were made
  for;
- `--O1`, the level the keys were made at. `--O2` does not fit the `.zkey`.

## Why a graph matches the keys

A graph built from source could differ from what the `.zkey` was made from. Part 0 of
docs/privacy-pools.md compared each graph's witness with snarkjs's, from the published `.wasm`,
element by element: 1,542 and 36,901 signals, all equal, which is the `.zkey`'s count. The
library's tests prove both circuits with these graphs and verify every proof with the pinned keys.

A graph does not check the circuit's assertions. That is why the library verifies every proof
before it returns it.

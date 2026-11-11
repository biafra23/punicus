# privacy-pools-native

Punicus's native Privacy Pools library, in Rust (owner ruling, 2026-11-11, docs/privacy-pools.md
D9; docs/architecture.md, *Native code*). It computes what the Kotlin side must not: circomlib's
Poseidon, zk-kit's Lean IMT, and Groth16 proofs of the `withdraw` and `commitment` circuits.
Only `:data`'s adapters load it, through its C ABI (`src/ffi.rs`).

## What it guarantees

- **Field elements** cross the ABI as 32 big-endian bytes, refused unless below `p`.
- **Artifacts** are pinned by sha256 (`src/artifacts.rs`): a `.zkey` or a graph with another hash
  is refused, never loaded.
- **Every proof is verified** against the circuit's verifying key before it is returned. The
  witness graph does not check the circuit's assertions, so a witness of a broken input proves
  nothing this library hands out.
- **Secrets** (a note's nullifier and secret) reach it as Poseidon's inputs and as a witness. It
  zeroes every buffer of its own that held one and never logs an input. The third-party witness and
  prover code keep copies of their own; Part 2 of docs/privacy-pools.md records what they are.
- **Panics** never cross the ABI: each call answers with an error number instead.

## The ABI

Every function returns 0, or an `Error`'s number (`src/lib.rs`), and writes its outputs only on
success.

| Function | Does |
|---|---|
| `pp_poseidon(inputs, count, out)` | Poseidon of 1 to 3 field elements |
| `pp_lean_imt_root(leaves, count, out_root, out_depth)` | a tree's root and depth |
| `pp_lean_imt_proof(leaves, count, index, out_root, out_siblings, out_index)` | an inclusion proof as the circuits take it: 32 siblings, zero-padded, and the compressed index |
| `pp_prover_new(circuit, zkey, zkey_len, graph, graph_len, out_status)` | loads a circuit (0 commitment, 1 withdraw) from its pinned artifacts |
| `pp_prove(prover, inputs, count, out_proof, out_public, public_count)` | proves, verifies, and writes the proof in the Solidity verifier's order and the public signals |
| `pp_prover_free(prover)` | frees a prover |

The inputs of `pp_prove`, packed in this order:

- `commitment` (4): `value, label, nullifier, secret`;
- `withdraw` (78): `withdrawnValue, stateRoot, stateTreeDepth, ASPRoot, ASPTreeDepth, context,
  label, existingValue, existingNullifier, existingSecret, newNullifier, newSecret,
  stateSiblings[32], stateIndex, ASPSiblings[32], ASPIndex`.

## Building and testing

```bash
cd native/privacy-pools
scripts/fetch-artifacts.sh            # the pinned proving keys, into artifacts/ (git-ignored)
cargo test --release --locked         # release: a proof in debug mode takes minutes
cargo clippy --release --all-targets --locked -- -D warnings
cargo fmt --check
scripts/build-graphs.sh               # rebuilds graphs/ from pinned sources, to compare
```

CI runs the first four (`.github/workflows/ci.yml`, *Native library (Rust)*). Where the build
takes the proving keys from is still open (docs/privacy-pools.md, D14a): `fetch-artifacts.sh`
downloads the npm package the keys are published in and refuses any file whose hash is not
pinned. The app never fetches them (D14).

## Licences

This crate is GPL-3.0, as Punicus is. The graphs are the output of the GPL-3.0 builder over
0xbow's Apache-2.0 circuits and GPL-3.0 circomlib; the runtime that reads them is MIT.
Dependencies: arkworks (MIT/Apache-2.0), `light-poseidon` (Apache-2.0), TACEO's
`taceo-circom-types` and `taceo-groth16` (MIT/Apache-2.0), `circom-witness-rs` (MIT).

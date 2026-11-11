//! The library against the reference vectors of Part 0's spike (docs/privacy-pools.md): inputs
//! built as Kohaku's `withdrawThunk` builds them, with `@zk-kit/lean-imt` 2.2.2 and
//! `poseidon-lite` 0.3.0, and the public signals snarkjs 0.7.5 computed and verified from them.
//!
//! The proving keys are fetched by `scripts/fetch-artifacts.sh` into `artifacts/` (or the
//! directory in `PP_ARTIFACTS_DIR`). Without them the proving tests fail and say how to get them.

use std::{fs, path::PathBuf, str::FromStr};

use ark_bn254::{Bn254, Fq, Fq2, Fr, G1Affine, G2Affine};
use ark_ff::PrimeField;
use ark_groth16::{Proof, VerifyingKey};
use privacy_pools::{
    artifacts::Circuit,
    ffi::{self, COMMITMENT_INPUTS, PROOF_BYTES, WITHDRAW_INPUTS},
    field,
    hash::poseidon,
    lean_imt::{Duplicates, LeanImt, MAX_DEPTH},
    Error,
};
use serde_json::Value;

fn here() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn vector(name: &str) -> Value {
    let path = here().join("tests/vectors").join(name);
    serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap()
}

fn fr(value: &Value) -> Fr {
    Fr::from_str(value.as_str().unwrap()).unwrap()
}

fn artifact(name: &str) -> Vec<u8> {
    let dir = std::env::var("PP_ARTIFACTS_DIR").map(PathBuf::from).unwrap_or_else(|_| here().join("artifacts"));
    fs::read(dir.join(name)).unwrap_or_else(|_| {
        panic!("{name} is missing in {}: run native/privacy-pools/scripts/fetch-artifacts.sh", dir.display())
    })
}

fn graph(name: &str) -> Vec<u8> {
    fs::read(here().join("graphs").join(format!("{name}.O1.graph.bin"))).unwrap()
}

fn p1(n: u64) -> Fr {
    poseidon(&[Fr::from(n)]).unwrap()
}

/// The commitment the vectors' note makes: Poseidon(value, label, Poseidon(nullifier, secret)).
#[test]
fn hashes_match_the_reference() {
    let input = vector("commitment.input.json");
    let expected = vector("expected-outputs.json");
    let precommitment = poseidon(&[fr(&input["nullifier"]), fr(&input["secret"])]).unwrap();
    let commitment = poseidon(&[fr(&input["value"]), fr(&input["label"]), precommitment]).unwrap();
    assert_eq!(commitment, fr(&expected["commitment"]["commitment"]));
    assert_eq!(poseidon(&[fr(&input["nullifier"])]).unwrap(), fr(&expected["commitment"]["nullifierHash"]));
}

/// The trees of the vectors, rebuilt from their leaves: zk-kit's roots, siblings and indices.
#[test]
fn trees_match_zk_kit() {
    let commitment_input = vector("commitment.input.json");
    let expected = vector("expected-outputs.json");
    let withdraw = vector("withdraw.input.json");

    let mut state: Vec<Fr> = (1..=6).map(|i| p1(1000 + i)).collect();
    state.push(fr(&expected["commitment"]["commitment"]));
    state.push(p1(2000));
    let state = LeanImt::new(state, Duplicates::Refused).unwrap();
    let proof = state.proof(6).unwrap();
    assert_eq!(proof.root, fr(&withdraw["stateRoot"]));
    assert_eq!(proof.index, withdraw["stateIndex"].as_str().unwrap().parse::<u64>().unwrap());
    let siblings: Vec<Fr> = withdraw["stateSiblings"].as_array().unwrap().iter().map(fr).collect();
    assert_eq!(proof.siblings.to_vec(), siblings);

    let mut asp: Vec<Fr> = (1..=4).map(|i| p1(3000 + i)).collect();
    asp.push(fr(&commitment_input["label"]));
    let asp = LeanImt::new(asp, Duplicates::Allowed).unwrap();
    let proof = asp.proof(4).unwrap();
    assert_eq!(proof.root, fr(&withdraw["ASPRoot"]));
    assert_eq!(proof.index, withdraw["ASPIndex"].as_str().unwrap().parse::<u64>().unwrap());
    let siblings: Vec<Fr> = withdraw["ASPSiblings"].as_array().unwrap().iter().map(fr).collect();
    assert_eq!(proof.siblings.to_vec(), siblings);
}

fn pack(values: &[Fr]) -> Vec<u8> {
    values.iter().flat_map(field::to_be).collect()
}

fn commitment_inputs() -> Vec<Fr> {
    let input = vector("commitment.input.json");
    ["value", "label", "nullifier", "secret"].iter().map(|k| fr(&input[*k])).collect()
}

fn withdraw_inputs() -> Vec<Fr> {
    let input = vector("withdraw.input.json");
    let mut out: Vec<Fr> = [
        "withdrawnValue",
        "stateRoot",
        "stateTreeDepth",
        "ASPRoot",
        "ASPTreeDepth",
        "context",
        "label",
        "existingValue",
        "existingNullifier",
        "existingSecret",
        "newNullifier",
        "newSecret",
    ]
    .iter()
    .map(|k| fr(&input[*k]))
    .collect();
    out.extend(input["stateSiblings"].as_array().unwrap().iter().map(fr));
    out.push(fr(&input["stateIndex"]));
    out.extend(input["ASPSiblings"].as_array().unwrap().iter().map(fr));
    out.push(fr(&input["ASPIndex"]));
    out
}

struct Loaded(*mut ffi::PpProver);

impl Drop for Loaded {
    fn drop(&mut self) {
        unsafe { ffi::pp_prover_free(self.0) }
    }
}

fn load(circuit: Circuit, name: &str) -> Loaded {
    let zkey = artifact(&format!("{name}.zkey"));
    let graph = graph(name);
    let mut status = -1;
    let prover = unsafe {
        ffi::pp_prover_new(circuit as u32, zkey.as_ptr(), zkey.len(), graph.as_ptr(), graph.len(), &mut status)
    };
    assert_eq!(status, 0);
    assert!(!prover.is_null());
    Loaded(prover)
}

/// Proves through the ABI; answers the public signals and the proof's 256 bytes.
fn prove(prover: &Loaded, inputs: &[Fr], public_count: usize) -> Result<(Vec<Fr>, Vec<u8>), i32> {
    let packed = pack(inputs);
    let mut proof = vec![0u8; PROOF_BYTES];
    let mut public = vec![0u8; public_count * 32];
    let status = unsafe {
        ffi::pp_prove(prover.0, packed.as_ptr(), inputs.len(), proof.as_mut_ptr(), public.as_mut_ptr(), public_count)
    };
    if status != 0 {
        return Err(status);
    }
    Ok((public.chunks(32).map(|c| field::from_be(c).unwrap()).collect(), proof))
}

/// The published verification key, from its pinned `.vkey` file.
fn published_vkey(circuit: Circuit, name: &str) -> VerifyingKey<Bn254> {
    let bytes = artifact(&format!("{name}.vkey"));
    privacy_pools::artifacts::check(&bytes, circuit.vkey_sha256()).unwrap();
    taceo_circom_types::groth16::VerificationKey::<Bn254>::from_reader(bytes.as_slice()).unwrap().into()
}

/// Decodes the ABI's proof bytes (the Solidity verifier's order, `pB`'s pairs swapped) and
/// verifies them with the published key: a check that rests on nothing of the library's own.
fn verifies_with_the_published_key(circuit: Circuit, name: &str, proof: &[u8], public: &[Fr]) {
    let q: Vec<Fq> = proof.chunks(32).map(Fq::from_be_bytes_mod_order).collect();
    let a = G1Affine::new_unchecked(q[0], q[1]);
    let b = G2Affine::new_unchecked(Fq2::new(q[3], q[2]), Fq2::new(q[5], q[4]));
    let c = G1Affine::new_unchecked(q[6], q[7]);
    assert!(a.is_on_curve() && b.is_on_curve() && c.is_on_curve());
    let key = ark_groth16::prepare_verifying_key(&published_vkey(circuit, name));
    let verified = ark_groth16::Groth16::<Bn254>::verify_proof(&key, &Proof { a, b, c }, public).unwrap();
    assert!(verified, "the proof bytes do not verify with the published {name}.vkey");
}

fn expected_public(name: &str) -> Vec<Fr> {
    vector(name).as_array().unwrap().iter().map(fr).collect()
}

#[test]
fn proves_the_commitment_circuit() {
    let prover = load(Circuit::Commitment, "commitment");
    let inputs = commitment_inputs();
    assert_eq!(inputs.len(), COMMITMENT_INPUTS);
    let (public, proof) = prove(&prover, &inputs, 4).unwrap();
    assert_eq!(public, expected_public("commitment.public.json"));
    verifies_with_the_published_key(Circuit::Commitment, "commitment", &proof, &public);
}

#[test]
fn proves_the_withdraw_circuit() {
    let prover = load(Circuit::Withdraw, "withdraw");
    let inputs = withdraw_inputs();
    assert_eq!(inputs.len(), WITHDRAW_INPUTS);
    let (public, proof) = prove(&prover, &inputs, 8).unwrap();
    assert_eq!(public, expected_public("withdraw.public.json"));
    verifies_with_the_published_key(Circuit::Withdraw, "withdraw", &proof, &public);
}

/// Part 0's finding: the witness graph does not check the circuit's assertions, so a wrong state
/// root still gives a witness. The library must refuse the proof it makes.
#[test]
fn refuses_a_withdrawal_whose_state_root_is_wrong() {
    let prover = load(Circuit::Withdraw, "withdraw");
    let mut inputs = withdraw_inputs();
    inputs[1] += Fr::from(1u64);
    assert_eq!(prove(&prover, &inputs, 8).map(|_| ()), Err(Error::ProofRejected as i32));
}

#[test]
fn refuses_an_artifact_that_is_not_pinned() {
    let mut zkey = artifact("commitment.zkey");
    zkey[100] ^= 1;
    let graph = graph("commitment");
    let mut status = 0;
    let prover = unsafe {
        ffi::pp_prover_new(
            Circuit::Commitment as u32,
            zkey.as_ptr(),
            zkey.len(),
            graph.as_ptr(),
            graph.len(),
            &mut status,
        )
    };
    assert!(prover.is_null());
    assert_eq!(status, Error::ArtifactMismatch as i32);
}

#[test]
fn refuses_inputs_of_the_other_circuit() {
    let prover = load(Circuit::Commitment, "commitment");
    assert_eq!(prove(&prover, &withdraw_inputs(), 4).map(|_| ()), Err(Error::BadLength as i32));
}

#[test]
fn an_empty_tree_has_no_root() {
    let mut root = [0u8; 32];
    let mut depth = 0u32;
    let status = unsafe { ffi::pp_lean_imt_root(std::ptr::null(), 0, 0, root.as_mut_ptr(), &mut depth) };
    assert_eq!(status, Error::EmptyTree as i32);
}

#[test]
fn refuses_null_outputs_before_proving() {
    let prover = load(Circuit::Commitment, "commitment");
    let packed = pack(&commitment_inputs());
    let mut public = vec![0u8; 4 * 32];
    let status = unsafe {
        ffi::pp_prove(prover.0, packed.as_ptr(), COMMITMENT_INPUTS, std::ptr::null_mut(), public.as_mut_ptr(), 4)
    };
    assert_eq!(status, Error::NullPointer as i32);
}

/// The library verifies with the key inside the pinned `.zkey`; it must be the published one.
#[test]
fn the_zkeys_verifying_keys_are_the_published_ones() {
    for (circuit, name) in [(Circuit::Commitment, "commitment"), (Circuit::Withdraw, "withdraw")] {
        let prover =
            privacy_pools::prover::Prover::new(circuit, &artifact(&format!("{name}.zkey")), &graph(name)).unwrap();
        assert_eq!(prover.verifying_key(), &published_vkey(circuit, name), "{name}");
    }
}

#[test]
fn hashes_through_the_abi() {
    let input = vector("commitment.input.json");
    let expected = vector("expected-outputs.json");
    let packed = pack(&[fr(&input["nullifier"])]);
    let mut out = [0u8; 32];
    assert_eq!(unsafe { ffi::pp_poseidon(packed.as_ptr(), 1, out.as_mut_ptr()) }, 0);
    assert_eq!(field::from_be(&out).unwrap(), fr(&expected["commitment"]["nullifierHash"]));
    assert_eq!(unsafe { ffi::pp_poseidon(packed.as_ptr(), 0, out.as_mut_ptr()) }, Error::BadArity as i32);
}

/// The state tree of the vectors through the ABI: its root, depth, siblings and index.
#[test]
fn builds_trees_through_the_abi() {
    let expected = vector("expected-outputs.json");
    let withdraw = vector("withdraw.input.json");
    let mut leaves: Vec<Fr> = (1..=6).map(|i| p1(1000 + i)).collect();
    leaves.push(fr(&expected["commitment"]["commitment"]));
    leaves.push(p1(2000));
    let packed = pack(&leaves);

    let (mut root, mut depth) = ([0u8; 32], 0u32);
    let status = unsafe { ffi::pp_lean_imt_root(packed.as_ptr(), leaves.len(), 0, root.as_mut_ptr(), &mut depth) };
    assert_eq!(status, 0);
    assert_eq!(field::from_be(&root).unwrap(), fr(&withdraw["stateRoot"]));
    assert_eq!(depth, 3);

    let (mut siblings, mut index) = (vec![0u8; MAX_DEPTH * 32], 0u64);
    let status = unsafe {
        ffi::pp_lean_imt_proof(
            packed.as_ptr(),
            leaves.len(),
            0,
            6,
            root.as_mut_ptr(),
            siblings.as_mut_ptr(),
            &mut index,
        )
    };
    assert_eq!(status, 0);
    let expected_siblings: Vec<Fr> = withdraw["stateSiblings"].as_array().unwrap().iter().map(fr).collect();
    let got: Vec<Fr> = siblings.chunks(32).map(|c| field::from_be(c).unwrap()).collect();
    assert_eq!(got, expected_siblings);
    assert_eq!(index, withdraw["stateIndex"].as_str().unwrap().parse::<u64>().unwrap());
}

/// A repeated leaf: refused for a pool's state tree, allowed for the association set.
#[test]
fn repeated_leaves_follow_the_flag() {
    let packed = pack(&[p1(1), p1(1)]);
    let (mut root, mut depth) = ([0u8; 32], 0u32);
    let refused = unsafe { ffi::pp_lean_imt_root(packed.as_ptr(), 2, 0, root.as_mut_ptr(), &mut depth) };
    assert_eq!(refused, Error::DuplicateLeaf as i32);
    let allowed =
        unsafe { ffi::pp_lean_imt_root(packed.as_ptr(), 2, ffi::TREE_ALLOW_DUPLICATES, root.as_mut_ptr(), &mut depth) };
    assert_eq!(allowed, 0);
    let unknown = unsafe { ffi::pp_lean_imt_root(packed.as_ptr(), 2, 2, root.as_mut_ptr(), &mut depth) };
    assert_eq!(unknown, Error::BadFlags as i32);
}

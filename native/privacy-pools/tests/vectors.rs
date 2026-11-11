//! The library against the reference vectors of Part 0's spike (docs/privacy-pools.md): inputs
//! built as Kohaku's `withdrawThunk` builds them, with `@zk-kit/lean-imt` 2.2.2 and
//! `poseidon-lite` 0.3.0, and the public signals snarkjs 0.7.5 computed and verified from them.
//!
//! The proving keys are fetched by `scripts/fetch-artifacts.sh` into `artifacts/` (or the
//! directory in `PP_ARTIFACTS_DIR`). Without them the proving tests fail and say how to get them.

use std::{fs, path::PathBuf, str::FromStr};

use ark_bn254::Fr;
use privacy_pools::{
    artifacts::Circuit,
    ffi::{self, COMMITMENT_INPUTS, PROOF_BYTES, WITHDRAW_INPUTS},
    field,
    hash::poseidon,
    lean_imt::LeanImt,
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
    let state = LeanImt::new(state).unwrap();
    let proof = state.proof(6).unwrap();
    assert_eq!(proof.root, fr(&withdraw["stateRoot"]));
    assert_eq!(proof.index, withdraw["stateIndex"].as_str().unwrap().parse::<u64>().unwrap());
    let siblings: Vec<Fr> = withdraw["stateSiblings"].as_array().unwrap().iter().map(fr).collect();
    assert_eq!(proof.siblings.to_vec(), siblings);

    let mut asp: Vec<Fr> = (1..=4).map(|i| p1(3000 + i)).collect();
    asp.push(fr(&commitment_input["label"]));
    let asp = LeanImt::new(asp).unwrap();
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

fn prove(prover: &Loaded, inputs: &[Fr], public_count: usize) -> Result<Vec<Fr>, i32> {
    let packed = pack(inputs);
    let mut proof = vec![0u8; PROOF_BYTES];
    let mut public = vec![0u8; public_count * 32];
    let status = unsafe {
        ffi::pp_prove(prover.0, packed.as_ptr(), inputs.len(), proof.as_mut_ptr(), public.as_mut_ptr(), public_count)
    };
    if status != 0 {
        return Err(status);
    }
    assert_ne!(proof, vec![0u8; PROOF_BYTES]);
    Ok(public.chunks(32).map(|c| field::from_be(c).unwrap()).collect())
}

fn expected_public(name: &str) -> Vec<Fr> {
    vector(name).as_array().unwrap().iter().map(fr).collect()
}

#[test]
fn proves_the_commitment_circuit() {
    let prover = load(Circuit::Commitment, "commitment");
    let inputs = commitment_inputs();
    assert_eq!(inputs.len(), COMMITMENT_INPUTS);
    assert_eq!(prove(&prover, &inputs, 4).unwrap(), expected_public("commitment.public.json"));
}

#[test]
fn proves_the_withdraw_circuit() {
    let prover = load(Circuit::Withdraw, "withdraw");
    let inputs = withdraw_inputs();
    assert_eq!(inputs.len(), WITHDRAW_INPUTS);
    assert_eq!(prove(&prover, &inputs, 8).unwrap(), expected_public("withdraw.public.json"));
}

/// Part 0's finding: the witness graph does not check the circuit's assertions, so a wrong state
/// root still gives a witness. The library must refuse the proof it makes.
#[test]
fn refuses_a_withdrawal_whose_state_root_is_wrong() {
    let prover = load(Circuit::Withdraw, "withdraw");
    let mut inputs = withdraw_inputs();
    inputs[1] += Fr::from(1u64);
    assert_eq!(prove(&prover, &inputs, 8), Err(Error::ProofRejected as i32));
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
    assert_eq!(prove(&prover, &withdraw_inputs(), 4), Err(Error::BadLength as i32));
}

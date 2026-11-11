//! Groth16 proofs of the `commitment` and `withdraw` circuits.
//!
//! - The witness comes from the circuit's pinned witness graph (`circom-witness-rs`).
//! - The proof comes from the pinned `.zkey`, with circom's reduction (`taceo-groth16`).
//! - **Every proof is verified before it is returned.** The graph does not check the circuit's
//!   assertions (Part 0's finding, docs/privacy-pools.md): a witness that breaks one gives a proof
//!   that only this verification refuses.
//! - Every buffer of this crate that held a secret is zeroed before it is dropped
//!   (docs/keys-and-signing.md, *Privacy Pools note secrets*). Copies the third-party witness and
//!   prover code keeps are outside this crate's reach; Part 2 records what they are.

use std::collections::HashMap;

use ark_bn254::{Bn254, Fq, Fr};
use ark_ff::{BigInteger, PrimeField, UniformRand};
use ark_groth16::{Proof, ProvingKey};
use rand::rngs::OsRng;
use ruint::aliases::U256;
use taceo_circom_types::{groth16::Zkey, CheckElement};
use taceo_groth16::{CircomReduction, ConstraintMatrices, Groth16};
use zeroize::Zeroize;

use crate::{
    artifacts::{self, Circuit},
    lean_imt::MAX_DEPTH,
    Error,
};

/// A loaded circuit: its constraint matrices, its proving key and its witness graph, each checked
/// against its pinned hash.
pub struct Prover {
    circuit: Circuit,
    matrices: ConstraintMatrices<Fr>,
    key: ProvingKey<Bn254>,
    graph: circom_witness_rs::Graph,
}

/// A proof in the order the Solidity verifier takes it: `pA`, `pB` (each pair of `Fq2`
/// coordinates swapped, as snarkjs's `exportSolidityCallData` does), `pC`. Eight base-field
/// elements.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SolidityProof(pub [Fq; 8]);

/// What a proof proves: the proof and the circuit's public signals, outputs first.
#[derive(Debug, Clone)]
pub struct Proved {
    pub proof: SolidityProof,
    pub public_signals: Vec<Fr>,
}

/// The `commitment` circuit's inputs: public `value` and `label`, private `nullifier` and `secret`.
pub struct CommitmentInputs {
    pub value: Fr,
    pub label: Fr,
    pub nullifier: Fr,
    pub secret: Fr,
}

/// The `withdraw` circuit's inputs, by its signal names (`withdraw.circom`).
pub struct WithdrawInputs {
    pub withdrawn_value: Fr,
    pub state_root: Fr,
    pub state_tree_depth: Fr,
    pub asp_root: Fr,
    pub asp_tree_depth: Fr,
    pub context: Fr,
    pub label: Fr,
    pub existing_value: Fr,
    pub existing_nullifier: Fr,
    pub existing_secret: Fr,
    pub new_nullifier: Fr,
    pub new_secret: Fr,
    pub state_siblings: [Fr; MAX_DEPTH],
    pub state_index: Fr,
    pub asp_siblings: [Fr; MAX_DEPTH],
    pub asp_index: Fr,
}

impl Drop for CommitmentInputs {
    fn drop(&mut self) {
        self.nullifier.zeroize();
        self.secret.zeroize();
    }
}

impl Drop for WithdrawInputs {
    fn drop(&mut self) {
        self.existing_nullifier.zeroize();
        self.existing_secret.zeroize();
        self.new_nullifier.zeroize();
        self.new_secret.zeroize();
    }
}

impl Prover {
    /// Loads a circuit from its `.zkey` and its witness graph, refusing either when its sha256 is
    /// not the pinned one. The curve points are not checked one by one: the pinned hash covers
    /// every byte, and checking them takes ten times as long (Part 0).
    pub fn new(circuit: Circuit, zkey: &[u8], graph: &[u8]) -> Result<Self, Error> {
        artifacts::check(zkey, circuit.zkey_sha256())?;
        artifacts::check(graph, circuit.graph_sha256())?;
        let parsed = Zkey::<Bn254>::from_reader(zkey, CheckElement::No).map_err(|_| Error::BadArtifact)?;
        let (matrices, key): (ConstraintMatrices<Fr>, ProvingKey<Bn254>) = parsed.into();
        if matrices.num_instance_variables != circuit.public_signals() + 1
            || matrices.num_instance_variables + matrices.num_witness_variables != circuit.witness_len()
        {
            return Err(Error::BadArtifact);
        }
        let graph = circom_witness_rs::init_graph(graph).map_err(|_| Error::BadArtifact)?;
        Ok(Self { circuit, matrices, key, graph })
    }

    /// The circuit this prover proves.
    pub fn circuit(&self) -> Circuit {
        self.circuit
    }

    /// Proves the `commitment` circuit (a ragequit).
    pub fn prove_commitment(&self, inputs: &CommitmentInputs) -> Result<Proved, Error> {
        if self.circuit != Circuit::Commitment {
            return Err(Error::WrongCircuit);
        }
        let mut signals = Signals::default();
        signals.one("value", &inputs.value);
        signals.one("label", &inputs.label);
        signals.one("nullifier", &inputs.nullifier);
        signals.one("secret", &inputs.secret);
        self.prove(signals)
    }

    /// Proves the `withdraw` circuit.
    pub fn prove_withdraw(&self, inputs: &WithdrawInputs) -> Result<Proved, Error> {
        if self.circuit != Circuit::Withdraw {
            return Err(Error::WrongCircuit);
        }
        let mut signals = Signals::default();
        signals.one("withdrawnValue", &inputs.withdrawn_value);
        signals.one("stateRoot", &inputs.state_root);
        signals.one("stateTreeDepth", &inputs.state_tree_depth);
        signals.one("ASPRoot", &inputs.asp_root);
        signals.one("ASPTreeDepth", &inputs.asp_tree_depth);
        signals.one("context", &inputs.context);
        signals.one("label", &inputs.label);
        signals.one("existingValue", &inputs.existing_value);
        signals.one("existingNullifier", &inputs.existing_nullifier);
        signals.one("existingSecret", &inputs.existing_secret);
        signals.one("newNullifier", &inputs.new_nullifier);
        signals.one("newSecret", &inputs.new_secret);
        signals.many("stateSiblings", &inputs.state_siblings);
        signals.one("stateIndex", &inputs.state_index);
        signals.many("ASPSiblings", &inputs.asp_siblings);
        signals.one("ASPIndex", &inputs.asp_index);
        self.prove(signals)
    }

    fn prove(&self, mut signals: Signals) -> Result<Proved, Error> {
        let inputs = std::mem::take(&mut signals.0);
        let computed = circom_witness_rs::calculate_witness(inputs, &self.graph, None);
        let mut values = computed.map_err(|_| Error::Witness)?;
        let mut witness: Vec<Fr> = values.iter().map(u256_to_fr).collect();
        values.iter_mut().for_each(|v| *v = U256::ZERO);
        drop(values);
        let result = self.prove_witness(&witness);
        witness.zeroize();
        result
    }

    fn prove_witness(&self, witness: &[Fr]) -> Result<Proved, Error> {
        if witness.len() != self.circuit.witness_len() {
            return Err(Error::Witness);
        }
        let mut r = Fr::rand(&mut OsRng);
        let mut s = Fr::rand(&mut OsRng);
        let proved = Groth16::<Bn254>::prove::<CircomReduction>(&self.key, r, s, &self.matrices, witness);
        r.zeroize();
        s.zeroize();
        let proof = proved.map_err(|_| Error::Proof)?;
        let public_signals = witness[1..self.matrices.num_instance_variables].to_vec();
        Groth16::<Bn254>::verify(&self.key.vk, &proof, &public_signals).map_err(|_| Error::ProofRejected)?;
        Ok(Proved { proof: solidity(&proof), public_signals })
    }
}

/// The circuit's named inputs, zeroed when dropped.
#[derive(Default)]
struct Signals(HashMap<String, Vec<U256>>);

impl Signals {
    fn one(&mut self, name: &str, value: &Fr) {
        self.0.insert(name.to_owned(), vec![fr_to_u256(value)]);
    }

    fn many(&mut self, name: &str, values: &[Fr]) {
        self.0.insert(name.to_owned(), values.iter().map(fr_to_u256).collect());
    }
}

impl Drop for Signals {
    fn drop(&mut self) {
        for values in self.0.values_mut() {
            values.iter_mut().for_each(|v| *v = U256::ZERO);
        }
    }
}

fn fr_to_u256(value: &Fr) -> U256 {
    let mut bytes = value.into_bigint().to_bytes_le();
    let out = U256::from_le_slice(&bytes);
    bytes.zeroize();
    out
}

fn u256_to_fr(value: &U256) -> Fr {
    let mut bytes = value.to_le_bytes::<32>();
    let out = Fr::from_le_bytes_mod_order(&bytes);
    bytes.zeroize();
    out
}

fn solidity(proof: &Proof<Bn254>) -> SolidityProof {
    SolidityProof([proof.a.x, proof.a.y, proof.b.x.c1, proof.b.x.c0, proof.b.y.c1, proof.b.y.c0, proof.c.x, proof.c.y])
}

/// A base-field element as 32 big-endian bytes, for the proof's coordinates.
pub fn fq_to_be(value: &Fq) -> [u8; 32] {
    let mut out = [0u8; 32];
    let bytes = value.into_bigint().to_bytes_be();
    out[32 - bytes.len()..].copy_from_slice(&bytes);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::str::FromStr;

    /// A snarkjs proof of the vectors, encoded as Kohaku encodes it for the Solidity verifier
    /// (`encoding.utils.ts`): `pA = pi_a[0..2]`, `pB = [[pi_b[0][1], pi_b[0][0]], [pi_b[1][1],
    /// pi_b[1][0]]]`, `pC = pi_c[0..2]`.
    #[test]
    fn encodes_a_proof_as_the_solidity_verifier_takes_it() {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/vectors/withdraw.proof.json");
        let json: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        let parsed: taceo_circom_types::groth16::Proof<Bn254> = serde_json::from_value(json.clone()).unwrap();
        let encoded = solidity(&parsed.into());
        let at = |k: &str, i: usize, j: Option<usize>| {
            let v = &json[k][i];
            let s = match j {
                Some(j) => v[j].as_str().unwrap(),
                None => v.as_str().unwrap(),
            };
            Fq::from_str(s).unwrap()
        };
        let expected = [
            at("pi_a", 0, None),
            at("pi_a", 1, None),
            at("pi_b", 0, Some(1)),
            at("pi_b", 0, Some(0)),
            at("pi_b", 1, Some(1)),
            at("pi_b", 1, Some(0)),
            at("pi_c", 0, None),
            at("pi_c", 1, None),
        ];
        assert_eq!(encoded, SolidityProof(expected));
    }
}

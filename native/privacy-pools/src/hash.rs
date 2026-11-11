//! Poseidon over BN254 as circomlib computes it: the hash of every Privacy Pools commitment,
//! precommitment, nullifier hash and tree node.

use ark_bn254::Fr;
use light_poseidon::{Poseidon, PoseidonHasher};

use crate::Error;

/// circomlib's Poseidon of 1 to 3 inputs, the arities Privacy Pools uses: 1 for a nullifier
/// hash, 2 for a precommitment and a tree node, 3 for a commitment. Any other count is refused.
pub fn poseidon(inputs: &[Fr]) -> Result<Fr, Error> {
    if !(1..=3).contains(&inputs.len()) {
        return Err(Error::BadArity);
    }
    let mut hasher = Poseidon::<Fr>::new_circom(inputs.len()).map_err(|_| Error::BadArity)?;
    hasher.hash(inputs).map_err(|_| Error::BadArity)
}

/// A Lean IMT node: Poseidon of its two children.
pub fn node(left: Fr, right: Fr) -> Fr {
    poseidon(&[left, right]).expect("arity 2 is supported")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::str::FromStr;

    fn fr(s: &str) -> Fr {
        Fr::from_str(s).unwrap()
    }

    /// circomlib's published vectors: Poseidon([1, 2]) and Poseidon([1]).
    #[test]
    fn matches_circomlib() {
        assert_eq!(
            poseidon(&[fr("1"), fr("2")]).unwrap(),
            fr("7853200120776062878684798364095072458815029376092732009249414926327459813530")
        );
        assert_eq!(
            poseidon(&[fr("1")]).unwrap(),
            fr("18586133768512220936620570745912940619677854269274689475585506675881198879027")
        );
    }

    #[test]
    fn refuses_other_arities() {
        assert_eq!(poseidon(&[]), Err(Error::BadArity));
        assert_eq!(poseidon(&[fr("1"); 4]), Err(Error::BadArity));
    }
}

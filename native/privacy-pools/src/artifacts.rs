//! The circuits' artifacts, each pinned by its sha256 (owner ruling, 2026-11-11,
//! `.claude/rules/data-sources.md`, *Privacy Pools' circuit artifacts are bundled*).
//!
//! - The proving keys are `@fatsolutions/privacy-pools-core-circuits` 1.0.5's, byte-identical to
//!   0xbow `privacy-pools-core` `df4bf730`, `packages/circuits/trusted-setup/final-keys/`.
//! - The witness graphs are built from 0xbow's circuits at that commit, with circomlib 2.0.5, by
//!   `circom-witness-rs` 0.5.1's builder at `--O1` (`graphs/README.md`).
//!
//! A file whose hash does not match is no artifact: proving is refused.

use sha2::{Digest, Sha256};

use crate::Error;

/// The two circuits.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
pub enum Circuit {
    /// `CommitmentHasher`: the ragequit proof.
    Commitment = 0,
    /// `Withdraw(32)`: the withdrawal proof.
    Withdraw = 1,
}

impl Circuit {
    /// From the ABI's number.
    pub fn from_u32(n: u32) -> Result<Self, Error> {
        match n {
            0 => Ok(Self::Commitment),
            1 => Ok(Self::Withdraw),
            _ => Err(Error::UnknownCircuit),
        }
    }

    /// The pinned sha256 of the circuit's `.zkey`.
    pub fn zkey_sha256(self) -> &'static str {
        match self {
            Self::Commitment => "494ae92d64098fda2a5649690ddc5821fcd7449ca5fe8ef99ee7447544d7e1f3",
            Self::Withdraw => "2a893b42174c813566e5c40c715a8b90cd49fc4ecf384e3a6024158c3d6de677",
        }
    }

    /// The pinned sha256 of the circuit's witness graph.
    pub fn graph_sha256(self) -> &'static str {
        match self {
            Self::Commitment => "418086d784c4a55d18ba33bcc5638aae8d8e40b23a45b5737c1eab9e189627de",
            Self::Withdraw => "714b27bc92e1cb365dcb46d5e2252c9224a03aafa1b810a125650c8c0477d0b2",
        }
    }

    /// The pinned sha256 of the circuit's `.vkey`, the published verification key. The library
    /// verifies with the key inside the pinned `.zkey`; the tests check that the two are equal.
    pub fn vkey_sha256(self) -> &'static str {
        match self {
            Self::Commitment => "7d48b4eb3dedc12fb774348287b587f0c18c3c7254cd60e9cf0f8b3636a570d8",
            Self::Withdraw => "666bd0983b20c1611543b04f7712e067fbe8cad69f07ada8a310837ff398d21e",
        }
    }

    /// The number of public signals, outputs first.
    pub fn public_signals(self) -> usize {
        match self {
            Self::Commitment => 4,
            Self::Withdraw => 8,
        }
    }

    /// The number of signals the witness holds, the `.zkey`'s `nVars`.
    pub fn witness_len(self) -> usize {
        match self {
            Self::Commitment => 1_542,
            Self::Withdraw => 36_901,
        }
    }
}

/// Refuses bytes whose sha256 is not [expected].
pub fn check(bytes: &[u8], expected: &str) -> Result<(), Error> {
    let digest = Sha256::digest(bytes);
    let hex: String = digest.iter().map(|b| format!("{b:02x}")).collect();
    if hex == expected {
        Ok(())
    } else {
        Err(Error::ArtifactMismatch)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_committed_graphs_are_the_pinned_ones() {
        let dir = concat!(env!("CARGO_MANIFEST_DIR"), "/graphs");
        for (circuit, name) in [(Circuit::Commitment, "commitment"), (Circuit::Withdraw, "withdraw")] {
            let bytes = std::fs::read(format!("{dir}/{name}.O1.graph.bin")).unwrap();
            check(&bytes, circuit.graph_sha256()).unwrap();
        }
    }

    #[test]
    fn refuses_other_bytes() {
        assert_eq!(check(b"not a zkey", Circuit::Withdraw.zkey_sha256()), Err(Error::ArtifactMismatch));
    }
}

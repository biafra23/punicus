//! Punicus's native Privacy Pools library (docs/privacy-pools.md, Part 2; docs/architecture.md,
//! *Native code*).
//!
//! - [hash]: circomlib's Poseidon, for commitments, precommitments, nullifier hashes and nodes.
//! - [lean_imt]: zk-kit's Lean IMT, for a pool's state tree and the association set.
//! - [prover]: Groth16 proofs of the `commitment` and `withdraw` circuits, every one verified
//!   before it is returned.
//! - [ffi]: the C ABI the Kotlin adapters load (JNA on Android and the desktop, cinterop on iOS).
//!
//! Field elements cross the ABI as 32 big-endian bytes below `p` ([field]).
//!
//! The note secrets reach this library on both of its paths, as Poseidon's inputs and as a
//! witness. It zeroes every buffer of its own that held one, and never logs an input
//! (docs/keys-and-signing.md, *Privacy Pools note secrets*).

pub mod artifacts;
pub mod ffi;
pub mod field;
pub mod hash;
pub mod lean_imt;
pub mod prover;

/// Why a call was refused. The ABI returns its number; 0 is success.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum Error {
    /// A buffer's length does not fit what the call takes.
    BadLength = 1,
    /// A field element is not below `p`.
    NotInField = 2,
    /// Poseidon of another number of inputs than 1 to 3.
    BadArity = 3,
    /// A tree leaf is zero.
    ZeroLeaf = 4,
    /// A tree leaf appears twice.
    DuplicateLeaf = 5,
    /// A tree deeper than 32 levels.
    TreeTooDeep = 6,
    /// No leaf at that index.
    NoSuchLeaf = 7,
    /// No circuit by that number.
    UnknownCircuit = 8,
    /// A `.zkey` or a witness graph whose sha256 is not the pinned one.
    ArtifactMismatch = 9,
    /// A pinned artifact that does not parse, or does not fit its circuit.
    BadArtifact = 10,
    /// A prover asked to prove another circuit than its own.
    WrongCircuit = 11,
    /// The witness could not be computed.
    Witness = 12,
    /// The proof could not be computed.
    Proof = 13,
    /// The proof does not verify: the inputs break one of the circuit's constraints.
    ProofRejected = 14,
    /// A null pointer where a buffer was required.
    NullPointer = 15,
    /// The library panicked; nothing it returned is to be used.
    Panic = 16,
}

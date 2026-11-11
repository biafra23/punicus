//! The C ABI. Every function returns 0 on success or an [Error]'s number, writes its answers only
//! on success, and never panics across the boundary: a panic is caught and answered with
//! [Error::Panic].
//!
//! Field elements are 32 big-endian bytes below `p`, packed one after another.
//!
//! The input buffers are the caller's. This library zeroes its own copies of them; the caller
//! zeroes its buffer of secrets after the call.

use std::{panic, ptr, slice};

use ark_bn254::Fr;

use crate::{
    artifacts::Circuit,
    field::{self, FIELD_BYTES},
    hash,
    lean_imt::{Duplicates, LeanImt, MAX_DEPTH},
    prover::{CommitmentInputs, Proved, Prover, WithdrawInputs},
    Error,
};

/// The number of field elements of a `commitment` proof's inputs: value, label, nullifier,
/// secret.
pub const COMMITMENT_INPUTS: usize = 4;

/// The number of field elements of a `withdraw` proof's inputs, in this order: withdrawnValue,
/// stateRoot, stateTreeDepth, ASPRoot, ASPTreeDepth, context, label, existingValue,
/// existingNullifier, existingSecret, newNullifier, newSecret, stateSiblings[32], stateIndex,
/// ASPSiblings[32], ASPIndex.
pub const WITHDRAW_INPUTS: usize = 12 + MAX_DEPTH + 1 + MAX_DEPTH + 1;

/// A proof's size: eight base-field elements in the Solidity verifier's order.
pub const PROOF_BYTES: usize = 8 * 32;

/// A tree flag: allow repeated leaves, for the association set. Without it a repeated leaf is
/// refused, as a pool's state tree does ([crate::lean_imt::Duplicates]).
pub const TREE_ALLOW_DUPLICATES: u32 = 1;

/// A loaded circuit, owned by the caller between [pp_prover_new] and [pp_prover_free].
pub struct PpProver(Prover);

fn guard(f: impl FnOnce() -> Result<(), Error>) -> i32 {
    match panic::catch_unwind(panic::AssertUnwindSafe(f)) {
        Ok(Ok(())) => 0,
        Ok(Err(error)) => error as i32,
        Err(_) => Error::Panic as i32,
    }
}

/// # Safety
/// `pointer` is null or valid for `len` bytes.
unsafe fn input<'a>(pointer: *const u8, len: usize) -> Result<&'a [u8], Error> {
    if len == 0 {
        return Ok(&[]);
    }
    if pointer.is_null() {
        return Err(Error::NullPointer);
    }
    Ok(slice::from_raw_parts(pointer, len))
}

/// # Safety
/// `pointer` is null or valid for writing `len` bytes.
unsafe fn output<'a>(pointer: *mut u8, len: usize) -> Result<&'a mut [u8], Error> {
    if pointer.is_null() {
        return Err(Error::NullPointer);
    }
    Ok(slice::from_raw_parts_mut(pointer, len))
}

/// circomlib's Poseidon of `count` (1 to 3) field elements, into 32 bytes at `out`.
///
/// # Safety
/// `inputs` is valid for `count * 32` bytes, `out` for writing 32.
#[no_mangle]
pub unsafe extern "C" fn pp_poseidon(inputs: *const u8, count: usize, out: *mut u8) -> i32 {
    guard(|| {
        let bytes = input(inputs, count.checked_mul(FIELD_BYTES).ok_or(Error::BadLength)?)?;
        let values = field::many_from_be(bytes, count)?; // wiped when dropped
        let hashed = hash::poseidon(&values);
        output(out, FIELD_BYTES)?.copy_from_slice(&field::to_be(&hashed?));
        Ok(())
    })
}

/// The root and depth of the Lean IMT of `count` leaves; `flags` is 0 or
/// [TREE_ALLOW_DUPLICATES].
///
/// # Safety
/// `leaves` is valid for `count * 32` bytes, `out_root` for writing 32, `out_depth` for one u32.
#[no_mangle]
pub unsafe extern "C" fn pp_lean_imt_root(
    leaves: *const u8,
    count: usize,
    flags: u32,
    out_root: *mut u8,
    out_depth: *mut u32,
) -> i32 {
    guard(|| {
        let tree = tree(leaves, count, flags)?;
        let root = tree.root().ok_or(Error::EmptyTree)?;
        if out_depth.is_null() {
            return Err(Error::NullPointer);
        }
        output(out_root, FIELD_BYTES)?.copy_from_slice(&field::to_be(&root));
        *out_depth = tree.depth() as u32;
        Ok(())
    })
}

/// The inclusion proof of the leaf at `index` in the Lean IMT of `count` leaves: the root, 32
/// siblings padded with zeros, and the compressed index the circuits take.
///
/// `flags` is 0 or [TREE_ALLOW_DUPLICATES].
///
/// # Safety
/// `leaves` is valid for `count * 32` bytes, `out_root` for writing 32, `out_siblings` for
/// writing 32 * 32, `out_index` for one u64.
#[no_mangle]
pub unsafe extern "C" fn pp_lean_imt_proof(
    leaves: *const u8,
    count: usize,
    flags: u32,
    index: usize,
    out_root: *mut u8,
    out_siblings: *mut u8,
    out_index: *mut u64,
) -> i32 {
    guard(|| {
        let proof = tree(leaves, count, flags)?.proof(index)?;
        if out_index.is_null() {
            return Err(Error::NullPointer);
        }
        let root = output(out_root, FIELD_BYTES)?;
        let siblings = output(out_siblings, MAX_DEPTH * FIELD_BYTES)?;
        root.copy_from_slice(&field::to_be(&proof.root));
        for (chunk, sibling) in siblings.chunks_exact_mut(FIELD_BYTES).zip(proof.siblings.iter()) {
            chunk.copy_from_slice(&field::to_be(sibling));
        }
        *out_index = proof.index;
        Ok(())
    })
}

unsafe fn tree(leaves: *const u8, count: usize, flags: u32) -> Result<LeanImt, Error> {
    let duplicates = match flags {
        0 => Duplicates::Refused,
        TREE_ALLOW_DUPLICATES => Duplicates::Allowed,
        _ => return Err(Error::BadFlags),
    };
    let bytes = input(leaves, count.checked_mul(FIELD_BYTES).ok_or(Error::BadLength)?)?;
    let parsed = field::many_from_be(bytes, count)?;
    LeanImt::new(parsed.to_vec(), duplicates)
}

/// Loads `circuit` (0: commitment, 1: withdraw) from its `.zkey` and its witness graph, each
/// refused unless its sha256 is the pinned one. Returns null and sets `out_status` when refused.
///
/// # Safety
/// `zkey` and `graph` are valid for their lengths, `out_status` for one i32.
#[no_mangle]
pub unsafe extern "C" fn pp_prover_new(
    circuit: u32,
    zkey: *const u8,
    zkey_len: usize,
    graph: *const u8,
    graph_len: usize,
    out_status: *mut i32,
) -> *mut PpProver {
    let mut made = ptr::null_mut();
    let status = guard(|| {
        let circuit = Circuit::from_u32(circuit)?;
        let prover = Prover::new(circuit, input(zkey, zkey_len)?, input(graph, graph_len)?)?;
        made = Box::into_raw(Box::new(PpProver(prover)));
        Ok(())
    });
    if !out_status.is_null() {
        *out_status = status;
    }
    made
}

/// Frees a prover from [pp_prover_new]. Null is ignored.
///
/// # Safety
/// `prover` is null or came from [pp_prover_new] and was not freed before.
#[no_mangle]
pub unsafe extern "C" fn pp_prover_free(prover: *mut PpProver) {
    if !prover.is_null() {
        drop(Box::from_raw(prover));
    }
}

/// Proves the prover's circuit from `count` packed inputs ([COMMITMENT_INPUTS] or
/// [WITHDRAW_INPUTS], in their documented order), verifies the proof, and writes it
/// ([PROOF_BYTES]) and the public signals (`public_count` * 32: 4 for commitment, 8 for
/// withdraw).
///
/// # Safety
/// `prover` came from [pp_prover_new]; `inputs` is valid for `count * 32` bytes; `out_proof`
/// for writing [PROOF_BYTES]; `out_public` for writing `public_count * 32`.
#[no_mangle]
pub unsafe extern "C" fn pp_prove(
    prover: *const PpProver,
    inputs: *const u8,
    count: usize,
    out_proof: *mut u8,
    out_public: *mut u8,
    public_count: usize,
) -> i32 {
    guard(|| {
        let prover = prover.as_ref().ok_or(Error::NullPointer)?;
        let prover = &prover.0;
        let circuit = prover.circuit();
        if public_count != circuit.public_signals() {
            return Err(Error::BadLength);
        }
        // Every refusal that can be made from the arguments alone comes before the inputs are read:
        // it costs no proof and makes no copy of the secrets.
        let expected = match circuit {
            Circuit::Commitment => COMMITMENT_INPUTS,
            Circuit::Withdraw => WITHDRAW_INPUTS,
        };
        if count != expected {
            return Err(Error::BadLength);
        }
        let proof = output(out_proof, PROOF_BYTES)?;
        let public = output(out_public, public_count * FIELD_BYTES)?;
        let bytes = input(inputs, count * FIELD_BYTES)?;
        let values = field::many_from_be(bytes, count)?; // wiped when dropped, on every path
        let proved = prove(prover, circuit, &values)?;
        for (chunk, coordinate) in proof.chunks_exact_mut(32).zip(proved.proof.0.iter()) {
            chunk.copy_from_slice(&field::to_be(coordinate));
        }
        for (chunk, signal) in public.chunks_exact_mut(FIELD_BYTES).zip(proved.public_signals.iter()) {
            chunk.copy_from_slice(&field::to_be(signal));
        }
        Ok(())
    })
}

fn prove(prover: &Prover, circuit: Circuit, v: &[Fr]) -> Result<Proved, Error> {
    match circuit {
        Circuit::Commitment => {
            if v.len() != COMMITMENT_INPUTS {
                return Err(Error::BadLength);
            }
            prover.prove_commitment(&CommitmentInputs { value: v[0], label: v[1], nullifier: v[2], secret: v[3] })
        }
        Circuit::Withdraw => {
            if v.len() != WITHDRAW_INPUTS {
                return Err(Error::BadLength);
            }
            let siblings = |from: usize| -> [Fr; MAX_DEPTH] { v[from..from + MAX_DEPTH].try_into().expect("32") };
            prover.prove_withdraw(&WithdrawInputs {
                withdrawn_value: v[0],
                state_root: v[1],
                state_tree_depth: v[2],
                asp_root: v[3],
                asp_tree_depth: v[4],
                context: v[5],
                label: v[6],
                existing_value: v[7],
                existing_nullifier: v[8],
                existing_secret: v[9],
                new_nullifier: v[10],
                new_secret: v[11],
                state_siblings: siblings(12),
                state_index: v[12 + MAX_DEPTH],
                asp_siblings: siblings(13 + MAX_DEPTH),
                asp_index: v[13 + 2 * MAX_DEPTH],
            })
        }
    }
}

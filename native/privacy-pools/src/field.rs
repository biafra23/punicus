//! Field elements of BN254's scalar field as they cross the C ABI: 32 bytes, big-endian, below `p`.

use ark_bn254::Fr;
use ark_ff::{BigInt, BigInteger, PrimeField};
use zeroize::{Zeroize, Zeroizing};

use crate::Error;

/// One field element's width at the ABI.
pub const FIELD_BYTES: usize = 32;

/// Reads a big-endian field element, refusing one that is not below `p` (never reduced: a value
/// at or above `p` is a caller's error, and reducing it would answer for another value).
///
/// The bytes may be a secret: they are read into limbs that are wiped, with no heap copy.
pub fn from_be(bytes: &[u8]) -> Result<Fr, Error> {
    if bytes.len() != FIELD_BYTES {
        return Err(Error::BadLength);
    }
    let mut limbs = [0u64; 4];
    for (limb, chunk) in limbs.iter_mut().rev().zip(bytes.chunks_exact(8)) {
        *limb = u64::from_be_bytes(chunk.try_into().expect("8 bytes"));
    }
    let value = Fr::from_bigint(BigInt::new(limbs)); // None at or above p
    limbs.zeroize();
    value.ok_or(Error::NotInField)
}

/// Writes a field element of BN254's scalar or base field as 32 big-endian bytes.
pub fn to_be<F: PrimeField>(value: &F) -> [u8; FIELD_BYTES] {
    let mut out = [0u8; FIELD_BYTES];
    let bytes = value.into_bigint().to_bytes_be();
    out[FIELD_BYTES - bytes.len()..].copy_from_slice(&bytes);
    out
}

/// Reads `n` consecutive field elements into a vector that never reallocates and is wiped when
/// dropped, on a refusal too: the inputs of a proof hold the note's secrets.
pub fn many_from_be(bytes: &[u8], n: usize) -> Result<Zeroizing<Vec<Fr>>, Error> {
    if bytes.len() != n * FIELD_BYTES {
        return Err(Error::BadLength);
    }
    let mut out = Zeroizing::new(Vec::with_capacity(n));
    for chunk in bytes.chunks_exact(FIELD_BYTES) {
        out.push(from_be(chunk)?);
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_and_refuses_p() {
        let one = Fr::from(1u64);
        assert_eq!(from_be(&to_be(&one)).unwrap(), one);
        let p_minus_one = -Fr::from(1u64);
        assert_eq!(from_be(&to_be(&p_minus_one)).unwrap(), p_minus_one);
        let mut p = to_be(&p_minus_one);
        p[31] += 1; // p - 1 ends in ...00, so p is one more in the last byte
        assert_eq!(from_be(&p), Err(Error::NotInField));
        assert_eq!(from_be(&[0u8; 31]), Err(Error::BadLength));
    }
}

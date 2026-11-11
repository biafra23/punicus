//! Field elements of BN254's scalar field as they cross the C ABI: 32 bytes, big-endian, below `p`.

use ark_bn254::Fr;
use ark_ff::{BigInteger, PrimeField};

use crate::Error;

/// One field element's width at the ABI.
pub const FIELD_BYTES: usize = 32;

/// Reads a big-endian field element, refusing one that is not below `p` (never reduced: a value
/// at or above `p` is a caller's error, and reducing it would answer for another value).
pub fn from_be(bytes: &[u8]) -> Result<Fr, Error> {
    if bytes.len() != FIELD_BYTES {
        return Err(Error::BadLength);
    }
    let value = Fr::from_be_bytes_mod_order(bytes);
    if to_be(&value).as_slice() != bytes {
        return Err(Error::NotInField);
    }
    Ok(value)
}

/// Writes a field element of BN254's scalar or base field as 32 big-endian bytes.
pub fn to_be<F: PrimeField>(value: &F) -> [u8; FIELD_BYTES] {
    let mut out = [0u8; FIELD_BYTES];
    let bytes = value.into_bigint().to_bytes_be();
    out[FIELD_BYTES - bytes.len()..].copy_from_slice(&bytes);
    out
}

/// Reads `n` consecutive field elements.
pub fn many_from_be(bytes: &[u8], n: usize) -> Result<Vec<Fr>, Error> {
    if bytes.len() != n * FIELD_BYTES {
        return Err(Error::BadLength);
    }
    bytes.chunks_exact(FIELD_BYTES).map(from_be).collect()
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

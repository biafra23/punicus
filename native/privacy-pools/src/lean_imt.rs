//! zk-kit's Lean IMT over circomlib's Poseidon, as Privacy Pools' pools and its association set
//! build it (`@zk-kit/lean-imt.sol` 2.0.0, `InternalLeanIMT.sol`).
//!
//! - A node with two children is Poseidon(left, right); a node with one child is the child.
//! - The depth is ⌈log₂ size⌉: one leaf is depth 0, and its root is the leaf.
//! - A leaf is non-zero, below `p` (by its type) and unique.

use std::collections::HashSet;

use ark_bn254::Fr;
use ark_ff::{BigInteger, PrimeField, Zero};

use crate::{hash, Error};

/// The deepest tree the circuits take (`maxTreeDepth = 32`).
pub const MAX_DEPTH: usize = 32;

/// A Lean IMT built from its leaves, every level kept.
pub struct LeanImt {
    levels: Vec<Vec<Fr>>,
}

/// An inclusion proof as the circuits take it: the siblings padded with zeros to
/// [MAX_DEPTH], and the index with a bit only for the levels that have a sibling.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InclusionProof {
    pub root: Fr,
    pub siblings: [Fr; MAX_DEPTH],
    pub index: u64,
}

impl LeanImt {
    /// Builds the tree. Refuses a zero leaf, a repeated leaf, and a tree deeper than [MAX_DEPTH].
    pub fn new(leaves: Vec<Fr>) -> Result<Self, Error> {
        if leaves.len() > (1usize << MAX_DEPTH) {
            return Err(Error::TreeTooDeep);
        }
        let mut seen = HashSet::with_capacity(leaves.len());
        for leaf in &leaves {
            if leaf.is_zero() {
                return Err(Error::ZeroLeaf);
            }
            if !seen.insert(leaf.into_bigint().to_bytes_be()) {
                return Err(Error::DuplicateLeaf);
            }
        }
        let mut levels = vec![leaves];
        while levels.last().map_or(0, Vec::len) > 1 {
            let below = levels.last().expect("non-empty");
            let above = below
                .chunks(2)
                .map(|pair| if pair.len() == 2 { hash::node(pair[0], pair[1]) } else { pair[0] })
                .collect();
            levels.push(above);
        }
        Ok(Self { levels })
    }

    /// The number of leaves.
    pub fn size(&self) -> usize {
        self.levels[0].len()
    }

    /// ⌈log₂ size⌉, the contract's `depth`.
    pub fn depth(&self) -> usize {
        self.levels.len() - 1
    }

    /// The root; None for an empty tree, whose root the contract has no value for.
    pub fn root(&self) -> Option<Fr> {
        self.levels.last().and_then(|top| top.first().copied())
    }

    /// The proof that the leaf at [index] is in the tree.
    pub fn proof(&self, index: usize) -> Result<InclusionProof, Error> {
        if index >= self.size() {
            return Err(Error::NoSuchLeaf);
        }
        let mut siblings = [Fr::zero(); MAX_DEPTH];
        let mut path = 0u64;
        let mut used = 0usize;
        let mut at = index;
        for level in &self.levels[..self.depth()] {
            let sibling = at ^ 1;
            if sibling < level.len() {
                path |= ((at & 1) as u64) << used;
                siblings[used] = level[sibling];
                used += 1;
            }
            at >>= 1;
        }
        Ok(InclusionProof { root: self.root().expect("non-empty"), siblings, index: path })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hash::poseidon;

    fn p1(n: u64) -> Fr {
        poseidon(&[Fr::from(n)]).unwrap()
    }

    #[test]
    fn one_leaf_is_its_own_root() {
        let tree = LeanImt::new(vec![p1(1)]).unwrap();
        assert_eq!(tree.depth(), 0);
        assert_eq!(tree.root(), Some(p1(1)));
        assert_eq!(tree.proof(0).unwrap().siblings, [Fr::zero(); MAX_DEPTH]);
    }

    #[test]
    fn a_lone_node_is_promoted() {
        let tree = LeanImt::new(vec![p1(1), p1(2), p1(3)]).unwrap();
        assert_eq!(tree.depth(), 2);
        assert_eq!(tree.root(), Some(hash::node(hash::node(p1(1), p1(2)), p1(3))));
        // Leaf 2 has no sibling at level 0: one sibling, at level 1, and its bit says "right".
        let proof = tree.proof(2).unwrap();
        assert_eq!(proof.siblings[0], hash::node(p1(1), p1(2)));
        assert_eq!(proof.siblings[1], Fr::zero());
        assert_eq!(proof.index, 1);
    }

    #[test]
    fn refuses_zero_and_repeated_leaves() {
        assert!(matches!(LeanImt::new(vec![Fr::zero()]), Err(Error::ZeroLeaf)));
        assert!(matches!(LeanImt::new(vec![p1(1), p1(1)]), Err(Error::DuplicateLeaf)));
    }
}

//! zk-kit's Lean IMT over circomlib's Poseidon, as Privacy Pools' pools and its association set
//! build it (`@zk-kit/lean-imt.sol` 2.0.0, `InternalLeanIMT.sol`).
//!
//! - A node with two children is Poseidon(left, right); a node with one child is the child.
//! - The depth is ⌈log₂ size⌉: one leaf is depth 0, and its root is the leaf.
//! - A leaf is non-zero (the circuits read a zero sibling as "promote") and below `p` (by its
//!   type).
//! - A pool's state tree refuses a repeated leaf, as `InternalLeanIMT._insert` does. The
//!   association set is not built by that contract: its leaves come from IPFS or 0xbow's API, and
//!   its check is the rebuilt root against the verified `latestRoot()` (docs/privacy-pools.md,
//!   Part 5). So it may hold a repeated leaf, as zk-kit's TypeScript tree allows ([Duplicates]).

use std::collections::HashSet;

use ark_bn254::Fr;
use ark_ff::Zero;

use crate::{hash, Error};

/// The deepest tree the circuits take (`maxTreeDepth = 32`).
pub const MAX_DEPTH: usize = 32;

/// A Lean IMT built from its leaves, every level kept.
pub struct LeanImt {
    levels: Vec<Vec<Fr>>,
}

/// Whether a tree refuses a repeated leaf.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Duplicates {
    /// A pool's state tree: the contract refuses a repeated leaf, so a list with one is wrong.
    Refused,
    /// The association set: built off chain, held to its root alone.
    Allowed,
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
    /// Builds the tree. Refuses a zero leaf, a tree deeper than [MAX_DEPTH], and a repeated leaf
    /// unless [duplicates] allows one.
    pub fn new(leaves: Vec<Fr>, duplicates: Duplicates) -> Result<Self, Error> {
        if leaves.len() as u64 > 1u64 << MAX_DEPTH {
            return Err(Error::TreeTooDeep);
        }
        let mut seen = HashSet::with_capacity(leaves.len());
        for leaf in &leaves {
            if leaf.is_zero() {
                return Err(Error::ZeroLeaf);
            }
            if duplicates == Duplicates::Refused && !seen.insert(*leaf) {
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
        let tree = LeanImt::new(vec![p1(1)], Duplicates::Refused).unwrap();
        assert_eq!(tree.depth(), 0);
        assert_eq!(tree.root(), Some(p1(1)));
        assert_eq!(tree.proof(0).unwrap().siblings, [Fr::zero(); MAX_DEPTH]);
    }

    #[test]
    fn a_lone_node_is_promoted() {
        let tree = LeanImt::new(vec![p1(1), p1(2), p1(3)], Duplicates::Refused).unwrap();
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
        assert!(matches!(LeanImt::new(vec![Fr::zero()], Duplicates::Allowed), Err(Error::ZeroLeaf)));
        assert!(matches!(LeanImt::new(vec![p1(1), p1(1)], Duplicates::Refused), Err(Error::DuplicateLeaf)));
        let allowed = LeanImt::new(vec![p1(1), p1(1)], Duplicates::Allowed).unwrap();
        assert_eq!(allowed.root(), Some(hash::node(p1(1), p1(1))));
    }
}

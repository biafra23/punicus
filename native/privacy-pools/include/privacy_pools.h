/*
 * The C ABI of the Privacy Pools library (src/ffi.rs, which is the reference; README.md, *The ABI*).
 * Every function returns 0 or an Error's number (src/lib.rs) and writes its outputs only on success.
 * Field elements are 32 big-endian bytes below p. iOS reads this header through cinterop
 * (data/src/nativeInterop/cinterop/privacyPools.def); the JVM binding declares the same functions
 * through JNA.
 */
#ifndef PRIVACY_POOLS_H
#define PRIVACY_POOLS_H

#include <stddef.h>
#include <stdint.h>

/** A loaded circuit: its proving key, witness graph and prepared verifying key. */
typedef struct PpProver PpProver;

/** Poseidon of `count` (1 to 3) field elements, into the 32 bytes at `out`. */
int32_t pp_poseidon(const uint8_t *inputs, size_t count, uint8_t *out);

/** The root and depth of the Lean IMT of `count` leaves; `flags` 1 allows repeated leaves. */
int32_t pp_lean_imt_root(const uint8_t *leaves, size_t count, uint32_t flags, uint8_t *out_root,
                         uint32_t *out_depth);

/** The inclusion proof of the leaf at `index`: the root, 32 zero-padded siblings, the compressed index. */
int32_t pp_lean_imt_proof(const uint8_t *leaves, size_t count, uint32_t flags, size_t index,
                          uint8_t *out_root, uint8_t *out_siblings, uint64_t *out_index);

/** Loads `circuit` (0 commitment, 1 withdraw) from its pinned artifacts; NULL, with `out_status` set, on refusal. */
PpProver *pp_prover_new(uint32_t circuit, const uint8_t *zkey, size_t zkey_len, const uint8_t *graph,
                        size_t graph_len, int32_t *out_status);

/** Frees a prover; NULL is a no-op. */
void pp_prover_free(PpProver *prover);

/** Proves, verifies, and writes the proof (256 bytes) and `public_count` public signals. */
int32_t pp_prove(const PpProver *prover, const uint8_t *inputs, size_t count, uint8_t *out_proof,
                 uint8_t *out_public, size_t public_count);

#endif

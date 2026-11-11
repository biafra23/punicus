# Privacy Pools

How Punicus holds private notes in 0xbow's Privacy Pools (v1): a deposit
from an account held here, the private balance found from the recovery
phrase, a private withdrawal, and the public exit (ragequit). This page is
a plan. Nothing of it is built, and every decision it names is open until
the owner takes it (*Open decisions*). It builds on docs/kohaku.md, which
is the reason for the protocol choices and the source of D1 to D10. Where
this page and the rules under `.claude/` (their short form in CLAUDE.md)
disagree, the rules win and this page is fixed.

Read on 2026-11-10: 0xbow's `privacy-pools-core` at `df4bf730`
(contracts, circuits, relayer, SDK, audits), Kohaku `886f72d`
(`@kohaku-eth/privacy-pools` 0.1.0-alpha.38), the circuits package
`@fatsolutions/privacy-pools-core-circuits` 1.0.5, `@zk-kit/lean-imt.sol`
2.0.0, Myotis `dc3a1c0` and Punicus `b5c8eab9`. Facts marked *[chain]*
were read from a public RPC endpoint and an explorer while planning. They
are not verified, and each is proven by the PR that pins it.

## The owner's direction

- **Privacy Pools first** (2026-11-10, in the session that wrote this page).
  It reverses docs/kohaku.md's "Railgun first, then Privacy Pools" (the
  direction given for that plan, 2026-11-03, on PR #220). Recorded as D3.
- Why, from docs/kohaku.md: the shortest history, the only protocol whose
  Sepolia history a walk may cover, a full reference client in Kohaku,
  secrets that stay in the key-store adapter, and a ragequit that needs no
  service. Every node method its client calls is one Myotis serves.

## What the user gets

- **A private balance per recovery phrase and network**, found from the
  phrase alone, on restore too. It shows approved funds and funds still
  awaiting the association set apart.
- **Deposit** (shield) of ETH, then of an ERC-20, from an account whose key
  is held here.
- **Withdrawal** (unshield) to any address: private for an approved deposit,
  through a relayer or self-paid (D4).
- **Ragequit**: the public exit, back to the account that deposited, for an
  approved deposit or one never approved.
- Sepolia first. Ethereum once its history can be had (D6). Not on Gnosis
  Chain: no deployment there, so the actions are greyed out and say why.
- What it never offers: a transfer inside the pool (the protocol has none,
  docs/kohaku.md *What "no private transfer" means*), a note held by a Safe
  (docs/kohaku.md *A Safe*), a watched account's notes.

## The protocol, as a client must implement it

### Contracts

- **Entrypoint**: a UUPS proxy (`Entrypoint.sol:36`). Roles `OWNER_ROLE`
  (upgrades, pool registry, fees) and `ASP_POSTMAN` (association-set
  roots).
  - *[chain]* On Ethereum the owner is a Safe 1.4.1, threshold 2 of 4
    (`0xad7f9a19…7159`). It is also a postman, with one EOA. The
    implementation was upgraded at block 22,540,812.
  - *[chain]* On Sepolia owner and postman are one EOA.
- **Pools**: immutable. `PrivacyPoolSimple` (native) and
  `PrivacyPoolComplex` (ERC-20). `ROOT_HISTORY_SIZE = 64`,
  `MAX_TREE_DEPTH = 32`. Deposits can be stopped for good (`windDown`);
  withdrawals and ragequits never can.
- **Calls a wallet makes**:
  - `Entrypoint.deposit(uint256 precommitment)` payable, for ETH
    (`0xb6b55f25`); `deposit(address asset, uint256 value, uint256
    precommitment)` for an ERC-20 (`0x0efe6a8b`), after an `approve`.
  - `Entrypoint.relay((address processooor, bytes data), proof, uint256
    scope)` (`0x8a44121e`): anyone may send it. `data` is
    `abi.encode(RelayData{recipient, feeRecipient, relayFeeBPS})`.
  - `pool.withdraw((address processooor, bytes data), proof)`
    (`0x30c0766d`): `msg.sender` must be the processooor.
  - `pool.ragequit(proof)` (`0x71235b34`): only the original depositor.
- **Views the checks read**: `Entrypoint.assetConfig(asset)` (pool, minimum
  deposit, vetting fee, maximum relay fee), `latestRoot()`,
  `associationSets(i)`, `usedPrecommitments(p)`; `pool.SCOPE()`,
  `ASSET()`, `currentRoot()`, `currentRootIndex()`, `roots(i)`,
  `nullifierHashes(h)`, `depositors(label)`, `dead()`.
- **A deposit** (`Entrypoint.sol:317-339`): a used precommitment reverts;
  the minimum is checked on the gross amount; the vetting fee
  (`value × vettingFeeBPS / 10000`) stays in the Entrypoint; the pool
  records `msg.sender` as the label's depositor.
- **A withdrawal** (`PrivacyPool.sol:43-61`) requires, in order: the
  processooor is the sender; `context = keccak256(abi.encode(withdrawal,
  scope)) mod p`; both depths ≤ 32; the state root among the last 64; **the
  association-set root equal to `latestRoot()` exactly**. It spends the
  nullifier hash and always inserts the new commitment, a zero one too.
- Fees and minimums are the owner's to change and have changed
  (*[chain]* sixteen `PoolConfigurationUpdated`). They are read live,
  never pinned.

### Deployments

| Network | Entrypoint | From block | Pools in this plan |
|---|---|---|---|
| Sepolia | `0x34A2068192b1297f2a7f85D7D8CdE66F8F0921cB` | 8,461,453 | ETH `0x644d5a25…861f` (8,461,455); USDC `0x0b062fe3…4c0f` (8,591,338); USDT `0x6709277e…ff54` (8,591,214) |
| Ethereum | `0x6818809EefCe719E480a7526D76bD3e561526b46` | 22,153,713 | ETH `0xf241d57c…c9fb` (22,153,715); USDC `0xb419c286…ce86` and USDT `0xe859c0bd…4572` (22,989,008) |

- The Entrypoints and their blocks are Kohaku's (`config.ts`). The pools
  are *[chain]* `PoolRegistered` logs. Ethereum has eleven more pools
  (USDS, sUSDS, DAI, wstETH, WBTC, USDe, USD1, frxUSD, wOETH, fxUSD, BOLD);
  which ones Punicus offers is D13.
- Pools are found from the Entrypoint's verified `PoolRegistered` logs and
  each is checked by verified calls (`SCOPE`, `ASSET`, and `assetConfig`
  naming it back). A pool is pinned by the PR that adds it, as a swap venue
  is (docs/swaps.md, *Pins*).

### Events

| Contract | Event | topic0 |
|---|---|---|
| Pool | `Deposited(address indexed depositor, uint256 commitment, uint256 label, uint256 value, uint256 precommitmentHash)` | `0xe3b53cd1…6549` |
| Pool | `Withdrawn(address indexed processooor, uint256 value, uint256 spentNullifier, uint256 newCommitment)` | `0x75e161b3…6a21` |
| Pool | `Ragequit(address indexed ragequitter, uint256 commitment, uint256 label, uint256 value)` | `0xd2b3e868…2f13` |
| Pool | `LeafInserted(uint256 index, uint256 leaf, uint256 root)` | `0xcb249c82…d221` |
| Entrypoint | `RootUpdated(uint256 root, string ipfsCID, uint256 timestamp)` | `0xf4364137…ac02` |
| Entrypoint | `PoolRegistered(address pool, address asset, uint256 scope)` | `0xf9db649a…8f77` |
| Entrypoint | `PoolWindDown(address pool)` | `0x553cc33d…2ec6` |

- `Deposited.value` is after the vetting fee. `Withdrawn.spentNullifier` is
  the nullifier **hash**. `LeafInserted.index` is the size after the
  insert, one-based.
- Unlike Kohaku, Punicus also reads `PoolRemoved` and
  `PoolConfigurationUpdated`: a removed pool takes no deposit, and a
  changed fee is re-read.
- The full topics are pinned in code and checked by a test against the
  event signatures.

### Hashes

All over BN254, `p` the scalar field; Poseidon as circomlib computes it.

- `scope = keccak256(pool ‖ chainId ‖ asset) mod p` (packed: 20 + 32 + 20
  bytes).
- `label = keccak256(scope ‖ nonce) mod p`, the pool's nonce counted from 1.
  A deposit and all its change notes share it.
- `precommitment = Poseidon(nullifier, secret)`;
  `commitment = Poseidon(value, label, precommitment)`;
  `nullifierHash = Poseidon(nullifier)`.
- `context = keccak256(abi.encode((processooor, data), scope)) mod p`.
- Values are 128-bit in the circuit; a deposit must be below 2¹²⁸.
- **The tree** is zk-kit's Lean IMT over Poseidon(2): a node with one child
  is the child; depth = ⌈log₂ size⌉; a leaf is non-zero, below `p` and
  unique. The root is `sideNodes[depth]`.

### Note secrets: Kohaku's scheme

docs/kohaku.md *Privacy Pools note secrets* pins it:

- the key at `m/28784'/1'/<accountIndex>'`;
- an RFC 6979 signature over EIP-712 `PrivacyPoolNote(uint64 depositIndex,
  uint64 secretIndex)`, domain "PrivacyPools Keychain", "1", the chain id,
  the Entrypoint, `salt = keccak256("privacypools")`;
- `rs = r ‖ low-s`; `nullifier = keccak256("nullifier" ‖ rs)[0..31]`;
  `secret = keccak256("salt" ‖ rs)[0..31]`;
- the deposit note at `secretIndex` 0, the k-th change note at k;
- the deposit index counted **per Entrypoint**, across all its pools.

**It is not 0xbow's.** 0xbow's SDK and privacypools.com derive
`Poseidon(master, scope, index)` from two HD keys (`sdk/src/crypto.ts:39-107`).
A phrase used there finds nothing here, and the other way round. Punicus
follows Kohaku's (D11, owner ruling, 2026-11-11).

### Discovery and balance

- **Deposits**: index 0, 1, 2… until the first precommitment no `Deposited`
  carries. Gap limit 0.
- **Change notes**: for each deposit, while a `Withdrawn` carries the newest
  note's nullifier hash, the next note.
- **Balance** of a deposit: its value less every withdrawal of its lineage;
  zero once a `Ragequit` carries its label.
- **Approved**: its label is a leaf of the association set. Otherwise it
  counts as awaiting, withdrawable only by ragequit.
- Only over complete coverage of every pool and the Entrypoint, read at the
  verified head (docs/kohaku.md, *Discovery on restore*).

### Circuits

| Circuit | Public signals, in order | Private inputs |
|---|---|---|
| `withdraw` (`Withdraw(32)`) | `newCommitmentHash, existingNullifierHash, withdrawnValue, stateRoot, stateTreeDepth, ASPRoot, ASPTreeDepth, context` | `label, existingValue, existingNullifier, existingSecret, newNullifier, newSecret, stateSiblings[32], stateIndex, ASPSiblings[32], ASPIndex` |
| `commitment` (ragequit) | `commitment, nullifierHash, value, label` | `nullifier, secret` |

- Siblings are padded with zeros to 32; a zero sibling means "promote".
  The index carries a bit only for a level that has a sibling.
- Kohaku always passes depth 32.
- For Solidity, `pB`'s inner pairs are swapped.
- **Artifacts** (sha256), identical to 0xbow's trusted-setup outputs and to
  the hashes its SDK pins:

  | File | Bytes | sha256 |
  |---|---|---|
  | `withdraw.wasm` | 2,607,967 | `36cda227…5c10` |
  | `withdraw.zkey` | 17,793,015 | `2a893b42…e677` |
  | `withdraw.vkey` | 4,208 | `666bd098…d21e` |
  | `commitment.wasm` | 2,380,442 | `254d2130…28d8` |
  | `commitment.zkey` | 901,233 | `494ae92d…e1f3` |
  | `commitment.vkey` | 3,477 | `7d48b4eb…70d8` |

- *[chain]* Every constant of the repository's verifiers is in the deployed
  verifiers' bytecode, on both networks.

### The association set

- One set per Entrypoint, shared by all pools; its leaves are labels.
- The newest `RootUpdated` gives the root and an IPFS CID. The file is the
  tree's levels as decimal strings, leaves first. 0xbow's API serves the
  leaves too.
- **Untrusted.** The leaves are rebuilt into a root that must equal both
  the event's and the verified `latestRoot()`. Anything else is no answer
  (docs/kohaku.md, *The root checks*).
- **It goes stale.** A withdrawal must name `latestRoot()` exactly, and
  *[chain]* it changed about 2.5 times a day. A proof made before an update
  reverts: the client checks the root again right before sending, and makes
  the proof again when it moved.
- The state root survives 64 inserts of its pool.

### The relayer and the paymaster

- **0xbow's relayer** (`/relayer/quote`, `/relayer/request`,
  `/relayer/details`): a quote returns a fee commitment for 60 s, signed
  with EIP-712 (`RelayerCommitment`). The client checks the commitment's
  `RelayData` (recipient, fee recipient, fee ≤ quote, fee ≤
  `maxRelayFeeBPS`) before proving. It learns the recipient, the asset,
  the amount, the public signals, the timing and the IP address.
- **Self-paid**: `pool.withdraw` with the sender as processooor, or `relay`
  sent by anyone with a zero fee. Private only if the paying account is
  not linked to the user, which is the gas leak.
- **ERC-4337 paymaster**: Kohaku's, through Pimlico's bundler. On Sepolia
  its paymaster is a zero-address placeholder; its contracts' sources are
  not public (*unverified*). Out of this plan unless D4 brings it in.
- No relayer URL ships in Kohaku; the integrator supplies one.

## How it fits Punicus

### Trust

- Everything a balance rests on is verified: the logs from the log index,
  every root, nullifier, depositor and configuration from verified calls at
  the head.
- Untrusted inputs, each checked before use: the association-set leaves
  (rebuilt to `latestRoot()`), a relayer's quote (re-encoded and compared),
  the circuit artifacts (sha256 pinned in code).
- **The upgradeable Entrypoint.** A new implementation could change what a
  deposit or a relay does. Punicus pins the implementation it was reviewed
  against: the ERC-1967 slot read by a verified `storageAt`, and the
  implementation's code hash by a verified `proveCode`. A mismatch greys
  deposit and relayed withdrawal out and says why; ragequit and a direct
  `pool.withdraw` stay, as they do not pass through the Entrypoint. D12.
- Kohaku's prover downloads its artifacts from GitHub at every proof,
  unchecked. Punicus never does: the artifacts are pinned (D14).

### Keys

- docs/kohaku.md *Key material* holds: the secrets stay in the key-store
  adapter; what leaves it is public: a precommitment, a nullifier hash, a
  commitment, a finished proof. D2.
- **What the key store lacks today.** It derives only `m/44'/60'/0'/0/i`
  (`Address.kt`), and signs only a 32-byte digest (`Signer`). No port
  signs typed data inside the adapter or returns a derived value.
- **New in the adapter**: a `PoolKeychain` that, inside one unlock,
  derives `m/28784'/1'/i'`, signs each note's EIP-712 digest, hashes the
  signature into the secrets, and either returns the note's public values or
  hands the secrets to the prover. The key, the signature and the secrets
  are byte arrays zeroed before the call returns (rule 7).
- **Determinism**: libsecp256k1 signs with RFC 6979. The first note of each
  unlock is signed twice, and a mismatch refuses everything.
- **Discovery without asking for the password at every start.** Kohaku signs
  every note again each session. Punicus unlocks per signature, so that
  would ask again and again. Proposal: at an unlock, the adapter derives and
  caches the **public** values of the next N deposits and of each known
  deposit's next change notes (N = 8 proposed). Discovery runs on the
  cache. It asks for the unlock only when the cache runs out, and says
  why. Public values are not key material (*Key material*). D2.
- Signing a withdrawal or a ragequit unlocks as any signature does.

### Code: where each part lives

- **`:domain`** (Kotlin, common):
  - the model: `PoolId(network, address)`, `Scope`, `Label`, `Precommitment`,
    `NullifierHash`, `Commitment` (field elements as hex, checked below
    `p`), `PoolDeposit`, `PrivateBalance` (approved, awaiting), `PoolEvent`;
  - the ports: `PrivacyPoolChain` (pinned contracts and their verified views,
    as `CowChain` and `UniswapV3Chain` are; `VerifiedChain` is internal to
    `:data`); `PoolKeychain` (public note values; proofs); `PoolState` (the
    rebuilt trees and roots); `AssociationSetSource` (untrusted leaves);
    `PoolRelayer` (off-chain, D4);
  - the use cases: `FollowPools`, `ObservePrivateBalance` (discovery, root
    checks, balance), `QuoteDeposit` and `SendDeposit`, `QuoteRagequit` and
    `SendRagequit`, `QuoteWithdrawal` and `SendWithdrawal`.
  - No Poseidon, no tree: `:domain` sees public values and compares roots.
    Keccak for `scope`, `label` and `context` is allowed there
    (owner rulings, 2026-10-18, #50 and 2026-11-03, #218).
- **A Rust library** in this repository, `native/privacy-pools/` (D9, owner
  ruling, 2026-11-11): Poseidon, the Lean IMT,
  the witness and the Groth16 prover, behind a small C ABI. Loaded through
  JNA on Android and the desktop (as `Sodium.kt` loads libsodium) and
  through cinterop on iOS (as `sodium.def`).
- **`:data`**: the adapters. `EnginePrivacyPoolChain` over `EngineChain`;
  `NativePoolState` over the library; the keychain inside
  `EnvelopeKeyStore`; `IpfsAssociationSetSource` and `HttpPoolRelayer` over
  Ktor, as `HttpCowApi`.
- **`:ui`**: the private balance section, the deposit, withdrawal and
  ragequit flows. docs/wallet-ux.md is amended first (*The screens*).
- **The hosts**: wire the ports in each composition root, and run
  `FollowPools` beside `FollowAccountLogs`.

### The prover

From the research on the options (2026-11-10):

- **Recommended**: a witness graph built from the circom source at build
  time (`circom-witness-rs`, as TACEO's `taceo-groth16-material`, or iden3's
  `circom-witnesscalc`), proved with pure-Rust Groth16 (`taceo-groth16` or
  `ark-groth16` 0.6) from the published `.zkey`. No JIT, every target. One
  C dependency after all: `circom-witness-rs` needs `zstd-sys`, so each
  platform's C compiler builds it (found in Part 2a; the NDK's clang on
  Android, Xcode's on iOS). It only ever reads a graph whose hash was
  checked first. It is World ID's stack on phones. Nethereum proves these
  very circuits with a `circom-witnesscalc` graph built at `--O1`.
- **Fallback and cross-check**: `rust-witness`, which turns the published
  `.wasm` into C at build time, so it is the very artifact the `.zkey` was
  made with.
- **Rejected**: `ark-circom` with wasmer (a JIT, which iOS forbids; kohaku-rs's
  Railgun branch has this problem too); the C++ `witnesscalc` and
  rapidsnark (no armeabi-v7a, no Windows).
- Scale: `withdraw` has about 37,000 signals, `commitment` about 1,500.
  Published benchmarks of that class prove in well under a second on a
  current phone.
- **The graph's risk**: it is built from source, so it must match what the
  `.zkey` was made from. The source package's repository could not be
  read, so 0xbow's `packages/circuits` at the commit its artifacts came
  from is the candidate. Its witnesses are compared with the `.wasm`'s on
  many inputs before it is used.
- The witness holds the note's secrets: the library zeroes its buffers and
  never logs an input.
- **Part 0, the spike**, ran this on the desktop: every step passed, and a
  `withdraw` proof takes about 0.4 s (*The plan*).

### The log index

- `Emitter` entries for the Entrypoint and each pool, from its deployment
  or registration block (D7). Kohaku filters by address with no topic, so
  an `Emitter` entry is exactly what it reads.
- `walkPaused` is per network, so `FollowPools` and `FollowAccountLogs`
  push the same value from `ObserveHeavyDownloads`.
- `IndexedReader` takes its start from `HistoryStarts` by account. A pool
  has no account, so the pool reader takes its start from its pin.
- **Sepolia**: about 3.6M blocks for the Entrypoint, a little less for the
  pools. Walked, its time and download measured (Part 4).
- **Ethereum**: about 4.3M blocks. Whether a walk is bearable there, or a
  seed is needed, is D6 (docs/kohaku.md, *Why a seed at all*).
- The history is dated by block (docs/embedding-myotis.md, *History*):
  Kohaku's header grid reaches beyond Myotis's 512 blocks.

### The screens

To be designed with the `ux` skill and written into docs/wallet-ux.md
before Part 7 is coded. What they must cover:

- **Where the private balance lives**: per recovery phrase and network,
  not per address. A "Private" section in Accounts under each phrase,
  each network's balance by asset, approved and awaiting apart.
- **Deposit**: from an account held here; the pool's minimum and the vetting
  fee shown before review; ERC-20 approval as its own step; the deposit
  index the note will take; a warning that the deposit is public and linked
  to the account.
- **Awaiting approval**: what it means, that ragequit is the way out, that
  approval is the association set's decision.
- **Withdrawal**: the recipient, the amount, the relayer and its fee, what
  the relayer learns; the warnings of docs/kohaku.md *What "no private
  transfer" means*: gas, amount and timing, what follows, the IP address,
  and a warning against operations in quick succession (walletbeat's
  check).
- **Ragequit**: only from the account that deposited, said before signing;
  public and linked by design.
- **Syncing** is never "no notes" (docs/kohaku.md, *Discovery on restore*).
- Greyed out, never hidden (owner ruling, 2026-10-18, #55): on Gnosis
  Chain, on a watched account, on a Safe for withdrawal, while syncing, on
  an Entrypoint whose implementation moved.
- Sepolia keeps every testnet warning and never adds to a real total.
- Previews per state, screenshots in each PR that changes them.

## The plan

A multi-PR plan through `feature/privacy-pools`, part by part, reaching
`main` in one final PR (`.claude/skills/feature-branch/SKILL.md`). Each
part follows docs/architecture.md's checklist and is releasable on its
own: nothing is shown to the user before Part 7.

| Part | What | Needs | Cloud |
|---|---|---|---|
| 0 | Prover spike: done on the desktop | — | yes (desktop) |
| 1 | This page, the decisions as rulings | the owner | yes |
| 2a | The native library (done, #289) | D9, D14 | yes |
| 2b | Its Kotlin binding and the desktop build | D9 | yes |
| 2c | The Android build (NDK) | D9 | CI only |
| 2d | The iOS build and Windows | D9 | CI only |
| 3 | Test vectors from Kohaku | D1, D11 | yes |
| 4 | Pools in the log index | D7 | yes, unit tests |
| 5 | Pool state and root checks | D5 | yes, unit tests |
| 6 | The keychain | D2, D8, D11 | yes, unit tests |
| 7 | The private balance | the screens | yes, previews |
| 8 | Deposit | D12, D13 | yes, unit tests |
| 9 | Ragequit | — | yes, unit tests |
| 10 | Withdrawal | D4, D5 | yes, unit tests |
| 11 | Ethereum | D6 | partly |
| 12 | Final PR into `main` | the owner merges | — |

### Part 0: the prover spike (approved and run, 2026-11-10)

Run on the desktop (Linux x86_64, 4 cores), in a scratch directory outside
the repository. Every step passed for both circuits.

- **The reference**: snarkjs 0.7.5 proved and verified both circuits, from
  inputs built as Kohaku's `withdrawThunk` builds them (Lean IMT, Poseidon,
  depth 32). Its witnesses, proofs and public signals are the first
  vectors.
- **The source**: the published `.zkey`, `.vkey` and `.wasm` are
  byte-identical to 0xbow `privacy-pools-core` at `df4bf730`
  (`packages/circuits/trusted-setup/final-keys/`, `build/`). The package's
  own repository could not be read; it was not needed.
- **The prover**: `taceo-circom-types` 0.3.4 and `taceo-groth16` 0.2.3
  (`CircomReduction`), over `ark-groth16` 0.6, proved from snarkjs's
  witness and the published `.zkey`. snarkjs verified each proof with the
  published `.vkey`; the public signals were identical.
- **The witness**, two ways, each equal to snarkjs's element by element
  (1,542 for `commitment`, 36,901 for `withdraw`, the `.zkey`'s count):
  - `rust-witness` 0.1.6 from the published `.wasm`;
  - a `circom-witness-rs` 0.5.1 graph, built from 0xbow's source at
    `df4bf730` with circomlib 2.0.5 at **`--O1`**. `--O2` gives 17,513
    signals and does not fit the `.zkey`.
- **From inputs to a verified proof in Rust**, both ways:

  | | `commitment` | `withdraw` |
  |---|---|---|
  | `.zkey` load (curve checks off; pinned by hash instead) | 3 ms | 35–45 ms |
  | witness, graph / `.wasm` | 0.07 ms / 2.1 ms | 2.6 ms / 45 ms |
  | proof | 22 ms | 330 ms (1.23 s on one thread) |
  | peak memory | 13 MiB | 67–72 MiB |

  snarkjs needs 2.6 s and 413 MiB for the same `withdraw`.
- **Size**, stripped, x86_64: 1.8 MB with the graphs (230 KB and 43 KB,
  loaded at run time); 6.9 MB with both `.wasm` compiled in.
- **A finding the design must carry**: neither Rust witness checks the
  circuit's assertions. With a wrong state root, snarkjs refuses ("Assert
  Failed"); both Rust routes return a witness, and the prover a proof that
  only a verification catches. **The library verifies every proof against
  the verifying key in the pinned `.zkey` before it returns it** (about 2
  ms). A test checks that key equals the published, pinned `.vkey`.
- **The stack chosen for Part 2**, subject to D9: the `circom-witness-rs`
  graphs at `--O1` with the TACEO prover. The graphs are built once,
  offline, by the GPL builder pinned to its commit (`5121bdc6`, embedding
  circom 2.2.2), from 0xbow's `df4bf730`; only the graphs, under the MIT
  runtime, ship. Their sha256 is pinned beside the `.zkey`'s. A workflow
  rebuilds them on demand and weekly and fails unless they equal the
  committed ones byte for byte (`native-graphs.yml`); the library's tests
  prove with them and verify each proof with the published `.vkey`.
  `rust-witness` stays as a cross-check only.
- To vet before Part 2: the TACEO releases used are four days old (an
  August pair, 0.3.2 and 0.2.1, also exists); `rust-witness` fetches an
  unpinned `w2c2`; what the GPL builder's licence means for the graphs it
  writes (D15).
- **Next, on devices** (not in the cloud): Android arm64-v8a, armeabi-v7a
  and x86_64, an iPhone and the simulator, Windows. Success: every proof
  verifies, `withdraw` under 2 s on a mid-range arm64 phone and under 5 s on
  an armv7 one, under 300 MB of memory, under about 5 MB of code per ABI.

### Part 1: this page and the rulings

- This page into `docs/`, linked from docs/kohaku.md, whose Privacy Pools
  plan it replaces.
- Each decision the owner takes is recorded where it belongs
  (docs/kohaku.md, *Open decisions*): D2 in `trust.md` and
  docs/keys-and-signing.md; D4, D5, D6 and D14 in `data-sources.md`; D12 in
  `trust.md`.

### Part 2: the native library and its build

In four parts: 2a the crate and its tests (#289); 2b its Kotlin binding
through JNA and the desktop build, Gradle building the library with cargo
and the binding's tests running on the desktop JVM; 2c the four Android
ABIs with the NDK's clang (`zstd-sys` compiles C); 2d the iOS static
library through cinterop, and Windows.

- The crate: Poseidon (circomlib's constants), the Lean IMT, the witness, the
  prover, the artifacts' hashes; a C ABI of a few functions
  (`pp_tree_*`, `pp_prove_withdraw`, `pp_prove_commitment`,
  `pp_poseidon`), each taking and returning bytes, never logging.
- Where it lives (D9, owner ruling, 2026-11-11): in this repository,
  `native/privacy-pools/`, built by Gradle through cargo.
- **The build**: `cargo-ndk` for the four Android ABIs, the host library for
  the desktop (Linux, macOS, Windows), a static library for iOS (device and
  simulator) linked into `SerotineKit`.
- **CI**: Rust and the NDK on `ubuntu-latest` (`ci.yml`, `android-apk.yml`),
  Rust on `macos-15` (`ios.yml`, `desktop-dmg.yml`), Windows
  (`desktop-windows-msi.yml`). `check_apk_min_api.py` must stay green.
  The desktop installers run the binding's tests in each leg they build
  (`desktop-dmg.yml`, `desktop-linux-deb.yml`); `ci.yml` runs them on Linux
  x86-64.
- **2c, R8**: once R8 runs on the APK, `PrivacyPoolsNative.Library` needs a
  keep rule beside `Sodium`'s (`android-app/build.gradle.kts`). A renamed
  method fails at the call with an `UnsatisfiedLinkError`, not as
  `UNAVAILABLE`.
- **The artifacts** (the `.zkey`s about 19 MB, the graphs and `.vkey`s
  under 0.3 MB): bundled in every build, pinned by sha256, never fetched (D14,
  owner ruling, 2026-11-11). Converting the `.zkey` to arkworks' form
  loads faster, but makes a new artifact with its own hash.
- Tests: `cargo test` with Part 3's vectors; Kotlin tests over JNA on the
  desktop.

### Part 3: test vectors

- A script in `scripts/` runs Kohaku's TypeScript at its pinned commit, with
  Node, on a mnemonic generated for the purpose and never funded
  (docs/kohaku.md, *Following Kohaku*).
- It writes: the signing key and the note signatures; nullifiers, secrets,
  precommitments, commitments and nullifier hashes for a grid of deposit
  and secret indices; scopes, labels and contexts; trees and roots for
  fixed leaf sets; discovery results over a fixed log set; and snarkjs's
  witnesses and public signals for both circuits.
- `:data`'s and the crate's tests check against it, and that it names the
  pins, so a mismatch fails `./gradlew build`.

### Part 4: pools in the log index

- `PrivacyPoolChain` and its adapter: the pinned Entrypoint and pools per
  network, their views by verified call; pool discovery from
  `PoolRegistered`, each checked.
- `FollowPools`: `Emitter` entries from the pinned blocks; the shared
  `walkPaused`; unwatch when the user turns privacy off on that network
  (D7).
- A pool reader on `IndexedReader`'s pattern, keyed by the pool's pin.
- The event decoders, tested against the pinned topics.
- Measured on a device on Sepolia: the walk's time and download. The
  measurement is in the PR.

### Part 5: pool state and root checks

- `PoolState` over the native library: each pool's tree rebuilt from
  `LeafInserted`, checked against `currentRoot()` or one of the last 64
  roots, read at the head the logs reach.
- `AssociationSetSource`: the IPFS file by the CID of the newest verified
  `RootUpdated`, over Ktor, from a pinned gateway, or 0xbow's API (D5); the
  leaves rebuilt and checked against the event and `latestRoot()`.
- A rejected or unplaceable leaf is an error, never a place to stop
  (docs/kohaku.md, *The root checks*).

### Part 6: the keychain

- `PoolKeychain` in `EnvelopeKeyStore`: the path, the EIP-712 digest (from
  `:domain`'s `Eip712`), the signature, the secrets, all in one call, all
  zeroed.
- The determinism check. The cache of public values (D2).
- As docs/keys-and-signing.md, *Privacy Pools note secrets*, sets it out
  (D2, owner ruling, 2026-11-11).
- Tests against Part 3's vectors, and that no secret leaves the adapter (as
  the existing key-store tests do for keys).

### Part 7: the private balance

- `ObservePrivateBalance`: discovery over the cache and the verified logs;
  the balance; approved and awaiting; "syncing" until coverage is complete.
- The Accounts section, the private balance screen, previews per state.
- Nothing can be deposited or spent yet. So the section stays behind a
  debug-build flag until Part 8, unless the owner wants a balance shown
  before deposits exist: the flag keeps `main` releasable.

### Part 8: deposit

- ETH first: `QuoteDeposit` reads `assetConfig` (minimum, vetting fee), the
  next deposit index and its precommitment; estimate and simulate through
  the existing transaction ports; review; sign; send.
- Two deposits from one phrase are never prepared at once: the second would
  reuse the precommitment and revert.
- ERC-20 next: `approve` as its own reviewed transaction, then the deposit.
- D12's implementation check gates it.

### Part 9: ragequit

- From the depositor account only (`depositors(label)` by verified call),
  for one deposit's current note.
- The `commitment` proof, made where the secrets are (D2).
- Review says it is public and linked to the deposit.

### Part 10: withdrawal

- The `withdraw` proof: the state tree's and the association set's
  inclusion proofs, the change note's secrets, the context.
- Relayed (D4): quote, check the fee commitment, prove, check the roots
  again, send; on `IncorrectASPRoot` or a stale state root, prove again.
- Self-paid, if D4 allows it: from an account the user names, with the gas
  warning.
- The warnings of *The screens*.

### Part 11: Ethereum

- The pins proven on Ethereum; the history by the walk or a seed (D6).
- The real-funds network step and the totals.

### Part 12: the final PR

- `feature/privacy-pools` into `main`, merged by the owner. The artifact
  workflows (APK, dmg, msi) run on it, `check_apk_min_api.py` included.

## Building and testing in the cloud

Measured in the claude.ai/code container on 2026-11-10:

| | In the cloud | Where else |
|---|---|---|
| `:domain` and `:data` JVM tests | **yes**: 793 and 710 tests, all green, once Gradle got past Maven Central's rate limit (HTTP 429) on a cold cache | CI |
| `:ui` previews and screenshots | yes (headless desktop) | CI commits them |
| Rust crate, `cargo test`, desktop Linux build | yes: Rust 1.97, gcc, clang, cmake | CI |
| Test vectors (Node, Kohaku, snarkjs) | yes: Node 22, npm | CI |
| Prover spike on the desktop | yes (Part 0) | — |
| Android build, APK, `check_apk_min_api.py` | **no**: no Android SDK or NDK here | CI (`ubuntu-latest`); a device |
| iOS build and simulator | **no**: needs macOS | CI (`macos-15`) |
| Windows desktop library | no | CI (`windows-latest`) |
| Live Sepolia: walk, deposits, proofs on chain | **no**: peer-to-peer connections (port 30303) are blocked, so the light client cannot sync | a device or a desktop |
| Proving on phones, measured | no | devices |

So every part can be coded, unit-tested and reviewed in the cloud, and CI
builds the platforms. What needs a device is the measurement of the
Sepolia walk (Part 4), the proving times (Part 0's second half), and an
end-to-end deposit, ragequit and withdrawal on Sepolia with test funds,
which the owner runs or hands to a device session.

## Open decisions

All the owner's. D1 to D10 are docs/kohaku.md's; this page adds D11 to D15.
Each is recorded as decided before its code lands.

**Decided** (owner rulings, 2026-11-11, recorded in the rule files, and
D3's direction of 2026-11-10):

- **D2: yes.** The secrets and the proving stay inside the key-store
  adapter; only public values leave it; the public values may be cached
  (`.claude/rules/trust.md`, *Privacy Pools note secrets are key material*;
  docs/keys-and-signing.md, *Privacy Pools note secrets*).
- **D9: in this repository.** The native library lives under
  `native/privacy-pools/`, built by Gradle and CI (docs/architecture.md and
  `.claude/rules/architecture.md`, *Native code*). The prover stack is Part
  0's. If Part 2 finds it does not hold, the owner decides the change on
  Part 2's PR.
- **D11: Kohaku's derivation.** Not 0xbow's (`.claude/rules/trust.md`;
  docs/keys-and-signing.md).
- **D14: bundled.** In every build, pinned by sha256, never fetched
  (`.claude/rules/data-sources.md`, *Privacy Pools' circuit artifacts are
  bundled*).
- **D10: yes.** The parts are merged into `feature/privacy-pools` by their
  author, once every comment is addressed and CI is green (owner's answer,
  2026-11-11, in the session that wrote this page; the feature-branch
  skill, step 3). The final PR into `main` stays the owner's.
- **D3, in part: Privacy Pools first** (the owner's direction, 2026-11-10,
  recorded on this page and in docs/kohaku.md). Tornado Cash stays out of
  this plan.

**Open:**

- **D14a. Where the build takes the artifacts from**: committed in this
  repository (about 19 MB, mostly the `.zkey`s), or downloaded by Gradle
  and CI from a pinned source and checked against the pinned hashes. Either
  way the app never fetches them (D14). Part 2 proposes.
- **D2a. The cache's file**: an app-private file, as now, or sealed with a
  hardware key as the outbox's file is. It ties withdrawals to deposits
  (docs/keys-and-signing.md, *The cache of public values*).

- **D1. The definition** of "Kohaku compliant" (docs/kohaku.md).
- **D4. How a withdrawal is paid for**: 0xbow's relayer (which one, on what
  terms), several relayers, self-paid from an account the user names, or
  the ERC-4337 paymaster. And whether a relayer is reached over Tor later.
- **D5. The association set's source**: an IPFS gateway (which), 0xbow's
  API, or both with a fallback. Used only when its root is `latestRoot()`.
- **D6. Ethereum's history**: the walk alone, or a seed.
- **D7. Pools read from their deployment**, outside "since added"; and
  whether following them is a per-network opt-in, given the download.
- **D8. The account index**: 0, one private account per phrase and network.
- **D12. The upgradeable Entrypoint**: pin its implementation and grey out
  deposit and relayed withdrawal when it moves, as proposed.
- **D13. Which assets**: ETH first, then USDC and USDT (Kohaku's three), or
  more of Ethereum's fourteen pools.
- **D15. Licences**: the verifiers and circomlib are GPL-3.0, the rest of
  0xbow's code Apache-2.0, Kohaku MIT; Punicus is GPL-3.0. Compatible as
  far as this plan reads them; the App Store question for GPL code is the
  owner's, and older than this plan.

## Risks

- **The graph does not match the `.zkey`.** The spike's `--O1` graph does;
  a rebuild that does not fails the graph workflow. Then `rust-witness`
  from the `.wasm`, which matches by construction.
- **A proof of a witness that breaks an assertion.** The Rust witnesses do
  not check them; the library verifies every proof before returning it.
- **The association set's root moves under a withdrawal.** Proving is fast
  enough to redo; the send checks the root first.
- **The Entrypoint is upgraded.** D12.
- **The Sepolia walk is too slow.** Then D6 for Sepolia too.
- **Kohaku is alpha and may change its derivation.** The pins move only in
  a PR that says so (docs/kohaku.md, *Following Kohaku*).
- **0xbow changes the relayer's API.** It is an off-chain service: a change
  breaks withdrawals until an update, never balances or ragequit.
- **Discovery asks for the password.** The cache of public values (D2).
- **Two deposits at once reuse a precommitment.** One deposit in flight per
  phrase and network.

## Sources

- 0xbow `privacy-pools-core` `df4bf730`: `packages/contracts/src/contracts/`
  (`Entrypoint.sol`, `PrivacyPool.sol`, `State.sol`, `lib/ProofLib.sol`,
  `interfaces/`), `packages/circuits/circuits/`, `packages/relayer/src/`,
  `packages/sdk/src/crypto.ts`, `audit/`.
- Kohaku `886f72d`: `packages/privacy-pools/src/` (`account/`, `config.ts`,
  `data/`, `state/thunks/`, `plugin/`, `relayer/`, `paymaster/`),
  `packages/poseidon-lean-imt/`, `packages/plugins/src/`.
- `@fatsolutions/privacy-pools-core-circuits` 1.0.5; `@zk-kit/lean-imt.sol`
  2.0.0.
- The prover options: `circom-witness-rs`, `circom-witnesscalc`,
  `rust-witness`, `taceo-groth16`, `ark-groth16`, `ark-circom`, mopro's
  benchmarks; Nethereum's Privacy Pools circuits.
- Punicus: `LogIndex.kt`, `VerifiedChain.kt`, `Signer.kt`, `KeyStore.kt`,
  `EnvelopeKeyStore.kt`, `Sodium.kt`, `CowChain.kt`, `HttpCowApi.kt`,
  `AccountLogs.kt`, `IndexedHistories.kt`, docs/architecture.md,
  docs/keys-and-signing.md, `.github/workflows/`.

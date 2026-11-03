# Kohaku compliance

How Serotine becomes a Kohaku-compliant wallet: it holds privacy-pool notes
that any Kohaku wallet derives from the same recovery phrase, and it finds
the same deposits on restore. This page is a plan. Nothing of it is built,
and every decision it names is open until the owner takes it (*Open
decisions*). The issue that tracks it links here. Where this page and the
rules under `.claude/` (their short form in CLAUDE.md) disagree, the rules
win and this page is fixed.

Read at Kohaku `8c83d64` (2026-11-02), kohaku-rs's `main` of the same week
and Myotis `5578e75` (2026-11-03). The facts below are from those commits.

## What "compliant" means here

Kohaku ([ethereum/kohaku](https://github.com/ethereum/kohaku)) is the
Ethereum Foundation's privacy SDK for wallets, and a roadmap. It publishes no
certification and no checklist. Serotine uses it as a **specification, not
as a runtime**:

- Serotine copies, exactly, what decides which notes a recovery phrase
  holds: the derivation paths, the note-secret scheme, the commitment
  formats, the discovery rule on restore and the root checks.
- Serotine's port has the semantics of Kohaku's plugin interface, so every
  protocol looks the same to the use cases and the screens.
- Serotine follows Kohaku's releases the way it follows Myotis's: a pinned
  commit, and a move of the pin that is reviewed (*Following Kohaku*).
- Serotine does not run Kohaku's TypeScript. The code is Kotlin in `:data`,
  or a Rust crate linked the way the Myotis natives are.

Why not the runtime:

- Serotine has no JS engine. Embedding one, with zero-knowledge proving in
  JS or WASM, is heavy on a phone.
- Kohaku's host gives a plugin `keystore.deriveAt(path)`, a raw private key.
  Running npm code with that would break *Key material* in
  `.claude/rules/trust.md`.
- Its provider is plain JSON-RPC. The log index's typed refusals
  (`LogsReading.NotCovered`, `BeforeStart`) would be flattened into errors
  the plugin interprets.
- Its packages are alpha and say that later versions may break them.

## What the Kohaku spec pins

### The seed

- Kohaku's reference keystore (`packages/plugins/src/host/mnemonic-keystore.ts`)
  takes the BIP-39 seed with the empty passphrase and derives BIP-32
  secp256k1 keys. Under Railgun's coin type 1984 it uses Railgun's own hash
  chain instead.
- Serotine fits: `Bip39.seed` uses the empty passphrase, and a phrase's
  envelope keeps its seed (owner ruling, 2026-10-15). Create account stores
  its words through the same import.
- An account whose key was imported alone has no seed. It holds no notes.
  Its privacy actions are greyed out and say why (owner ruling, 2026-10-18,
  #55); Kohaku's own refusal is `UnsupportedAccountError`.

### Privacy Pools note secrets

Kohaku's default is the signature scheme
(`packages/privacy-pools/src/account/signature-keys.ts`). The older BIP-32
scheme in `keys.ts` is deprecated, derives other notes and is not built.

- The signing key is at `m/28784'/1'/<accountIndex>'`.
- It signs the EIP-712 message `PrivacyPoolNote(uint64 depositIndex, uint64
  secretIndex)`. The domain is `name "PrivacyPools Keychain"`, `version
  "1"`, the chain id, `verifyingContract` = the Entrypoint and `salt` =
  `keccak256("privacypools")`.
- `rs` = `r` (32 bytes) ‖ low-s `s` (32 bytes), both big-endian; `v` is
  dropped.
- `nullifier` = the first 31 bytes of `keccak256("nullifier" ‖ rs)`;
  `salt` = the same with `"salt"`.
- `precommitment` = `poseidon(nullifier, salt)`; `nullifierHash` =
  `poseidon(nullifier)`; a note's commitment = `poseidon(value, label,
  precommitment)` (`utils.ts`).
- The sender key of a sponsored withdrawal = the first 31 bytes of
  `keccak256("signer" ‖ rs)`.
- Signatures are RFC 6979-deterministic. The first note of a session is
  signed twice, and a mismatch refuses everything, since note secrets are
  derived again after every restart and never stored.

### Tornado Cash note secrets

The same construction (`packages/tornado-cash/src/account/signature-keys.ts`),
only if the owner wants Tornado Cash at all (D3):

- key `m/29795'/1'/<accountIndex>'`; message `TornadoCashNote(uint64
  nonce)`; domain salt `keccak256("tornado")`, the same as kohaku-rs's
  `DOMAIN_SALT`;
- `nullifier` = the first 31 bytes of `keccak256("nullifier" ‖ pool ‖ rs)`,
  `secret` the same with `"secret"`.

### Discovery on restore

Privacy Pools (`state/thunks/discoverUserSecretsThunk.ts`):

1. Try deposit index 0, 1, 2… Stop at the first whose precommitment no
   `Deposited` event carries. The gap limit is zero.
2. For each deposit found, follow its change notes, withdraw index 1, 2…,
   while the newest one's nullifier hash has been spent.

Serotine uses the same rule. Another one finds fewer notes, or notes Kohaku
does not.

### The plugin semantics

The port mirrors `packages/plugins/src/base.ts`:

- `balance(assets)`, and `prepareShield`, `prepareTransfer`,
  `prepareUnshield` and their multi-asset forms, each behind a feature flag.
  Privacy Pools has shield, unshield and its own `ragequit`, and no private
  transfer. A feature a protocol lacks is greyed out, never hidden.
- Kohaku's errors map onto display-ready UiState variants: unsupported
  asset, chain or account; invalid address; insufficient balance;
  multi-asset not supported; transfer not supported.

### The root checks

- Privacy Pools: the rebuilt state root is the pool's `currentRoot()` or one
  of its 64 most recent roots. The association set's root equals the
  Entrypoint's `latestRoot()` exactly (`verification/root-verification.ts`).
- Tornado Cash: `isKnownRoot(root)`. Kohaku leaves the check off by default
  (`verify = false`). Serotine always runs it: a stricter check derives the
  same notes.
- Each root is read with a verified `eth_call` at the head the logs were
  read to.

## How it fits Serotine's rules

### Key material

- The note signature, the nullifier, the salt and the sender key are spending
  secrets. They stay inside the key-store adapter.
- The adapter derives the signing key from the envelope's seed, signs the
  note message in native code (docs/keys-and-signing.md, rule 7), derives
  the secrets, and computes the proof's witness and the proof there.
- What leaves it is public: a precommitment, a commitment, a nullifier hash,
  a finished proof.
- This needs no new exception to *Key material*. It does need an amendment
  to docs/keys-and-signing.md, which is the owner's (D2).
- None of this hashing is in `:domain`. Keccak-256 there is for transaction
  encoding and hashing alone (owner ruling, 2026-10-18, #50), and Poseidon
  is no library `:domain` may take. The note scheme and the tree live in
  `:data`; `:domain` sees the port and public values only.

### Chain data

- Everything a note's balance rests on is a verified read: the pool's logs
  from the log index, the roots from verified calls.
- The association set's tree is the one thing from outside. It is fetched
  by the IPFS CID in the Entrypoint's verified `RootUpdated` log, and used
  only when its root equals `latestRoot()` (D5).
- A withdrawal goes out through a relayer or an ERC-4337 bundler, off-chain
  services (D4). Kohaku's withdrawal (`prepareUnshield`) has no direct path.
  Ragequit is an ordinary transaction and needs neither.

### History

A note's tree needs every pool log since the pool was deployed, whenever the
user's own deposits were made. The "since added" rule (owner rulings,
2026-10-20, #85) is about accounts. Pool contracts are walked from their
deployment (D7).

### Networks

- Kohaku pins Privacy Pools on Ethereum (Entrypoint
  `0x6818809EefCe719E480a7526D76bD3e561526b46`, from block 22,153,713) and
  Sepolia (`0x34A2068192b1297f2a7f85D7D8CdE66F8F0921cB`, from 8,461,453),
  `PrivacyPoolsV1_0xBow` in `config.ts`.
- Gnosis Chain has none: its actions are greyed out there.
- The pools are found from the Entrypoint's own events. Each pin is proven
  on chain by the PR that adds it, as for swaps.

## The log index

### What it must hold

- For Privacy Pools: an `Emitter` entry (an address entry) for the
  Entrypoint and for each pool, unrestricted, from the deployment block.
- The tree is built from the pools' `LeafInserted` events; deposits,
  withdrawals and ragequits from `Deposited`, `Withdrawn` and `Ragequit`;
  the association set's updates from the Entrypoint's `RootUpdated`.

### What Serotine can do today

- **Watch:** yes. `LogIndex.configure(network, listOf(LogWatch(
  LogWatchKey.Emitter(pool), deployBlock)), emptyList())` is exactly the
  engine's address entry. `EngineLogIndex.logs` serves it and refuses a range
  below its start (`BeforeStart`).
- `FollowAccountLogs` unwatches only accounts that left the wallet
  (`AccountLogs.kt`, `gone`). It leaves pool entries alone.
- **Import:** no. Myotis's `ChainHandle.importLogIndexFiles` and
  `exportLogIndex` exist on the JVM API (ABI v24). Serotine's `LogIndexChain`
  wraps only `setLogIndexConfig`, `logIndexStatusJson` and `getLogs`, and the
  `LogIndex` port has no import. On iOS, Myotis's typed `ChainHandle` has no
  import either; only `RustEngine.importLogIndexFiles` does.

### Why walking is not enough on Ethereum

- The Entrypoint's history is about 4 million blocks deep today.
- Mainnet's `logsBloom` is so full that 85–95 % of blocks look like
  candidates (Myotis `docs/seeded-log-histories.md`). The walk then fetches
  nearly every block's receipts. Myotis calls such a walk a multi-day
  download.
- So Sepolia is walked (part 3). Ethereum waits for an imported seed (part
  10).

### What an import must respect

From Myotis's ruling on seeded histories (2026-09-25,
`docs/seeded-log-histories.md`) and its import design
(`docs/eth-getlogs-design.md`, *Import*):

- A seed is served on the word of the protocol's maintainers, and only to a
  client that checks its rebuilt root on chain. Privacy Pools' client does.
  Serotine's trust rules do not carry this exception today (D6).
- Myotis does not yet tell seeded coverage from walked coverage. That marker
  is its own precondition, unmet.
- A seed must reach within 500,000 blocks of finality (about 69 days), or it
  imports and never catches up. A published seed needs a refresh inside that.
- A seed arrives bundled at build time or picked by the user, never fetched
  by the wallet.
- An import is merged by clamping every entry's coverage to the lowest top
  among the sources. A seed that ends below the head pulls the accounts'
  history coverage down with it until the gap is walked again.
- No Privacy Pools seed in Myotis's MLIX format exists. Kohaku's own fast
  sync, saga-sync, is a CDN fetch.

## Following Kohaku

- **The pin.** The Kohaku commit this page matches, `8c83d64`, is the pin.
  It moves only in a PR that says so.
- **Vectors.** A script runs Kohaku's own code at the pinned commit on a
  mnemonic generated for the purpose and never funded. It writes the
  signatures, secrets, precommitments, commitments, nullifier hashes, the
  discovery results on a fixed log set and the roots. The file names the
  commit it came from. `:data`'s tests check Serotine against it, and that
  the commit is the pin, so a mismatch fails `./gradlew build`.
- **A move of the pin** regenerates the vectors. A changed derivation or note
  format is a breaking change for the user's funds: the PR says so, and the
  owner decides whether Serotine follows.

## The plan

A multi-PR plan. It lands through `feature/kohaku`, part by part, and
reaches `main` in one final PR (`.claude/skills/feature-branch/SKILL.md`).
Each part follows docs/architecture.md's checklist. The parts in order:

1. **The spec, the vectors and the port.** The vectors script and file. The
   `ShieldedPool` port in `:domain` with Kohaku's semantics, its value
   objects, fakes and use-case tests. No adapter yet. Needs D1.
2. **The keychain in the key-store adapter.** The signing key at
   `m/28784'/1'/<accountIndex>'`, the note message signed in native code,
   the determinism check, the secrets derived and kept inside. Tested against
   the vectors. docs/keys-and-signing.md amended. Needs D2 and D8.
3. **Pool logs.** A use case that keeps `Emitter` entries for the Entrypoint
   and its pools, found from the Entrypoint's events, each from its
   deployment block. The query bridge, with both ends of a range explicit.
   Coverage shown as syncing. Sepolia only. It measures the walk's time and
   download. Needs D7.
4. **Pool state.** In `:data`: Poseidon over BN254 and the pool's tree,
   rebuilt from `LeafInserted`. The root checks over verified calls. Discovery on
   restore. The balance. Tested against the vectors. Needs D3.
5. **The prover.** Native Groth16 witness and proof for the pool's circuits,
   inside the key-store adapter, on Android, the desktop and iOS. The circuit
   files pinned by hash. Kept inside the minSdk 29 budget. The library is
   chosen in this part.
6. **Deposit.** `Entrypoint.deposit` from an account held here, its review
   and signature through the existing send path.
7. **Ragequit.** The public exit of a deposit. It needs no service and no
   association set.
8. **The screens.** Where a privacy balance lives (per recovery phrase and
   network), the greyed-out actions, simple mode and Detailed view, a
   preview per state. docs/wallet-ux.md amended. Screenshots in the PR.
9. **Withdrawal.** The association set from IPFS, over Ktor, checked
   against `latestRoot()`. The relayer or bundler. Needs D4 and D5.
10. **Ethereum.** `LogIndex` gains an import; the JVM and iOS adapters; the
    seed bundled or picked. Needs D6, Myotis's provenance marker, and an
    import on Myotis's typed iOS `ChainHandle`.

Later, each a plan of its own:

- **Tornado Cash**, if D3 says yes: kohaku-rs's crate, linked as a native
  library.
- **Railgun**, once Kohaku has code for it: `@kohaku-eth/railgun` is a
  placeholder today ("Do not depend on this stub").
- **Tor** for the engine's reads and sends: Myotis has it, behind
  `-PtorEngine`. Serotine does not wire it.

## Open decisions

All the owner's. Each is recorded here as decided before its code lands.

- **D1. The definition.** Is "Kohaku compliant" what *What "compliant"
  means here* says: Kohaku as the spec at a pinned commit, checked by
  vectors?
- **D2. Secrets in the key-store adapter.** May the note signature, the
  secrets derived from it and the proving run inside the key-store adapter,
  with only public values leaving it, as *Key material* above sets out?
- **D3. Which protocols.** Privacy Pools first is proposed. Tornado Cash:
  yes or no, given its sanctions history. Railgun: when Kohaku has code.
- **D4. The withdrawal service.** A relayer (it learns the recipient, the
  amount and the device's IP address), or an ERC-4337 bundler and paymaster
  (Kohaku's default is Pimlico's public bundler). Which, and on what terms,
  as for CoW (owner ruling, 2026-10-22).
- **D5. The association set's tree.** From which IPFS gateway, or 0xbow's
  API instead. It is used only when its root is the Entrypoint's
  `latestRoot()`.
- **D6. Seeded histories.** Does Serotine adopt Myotis's ruling of
  2026-09-25, for Privacy Pools only, once Myotis meets its preconditions?
  Bundled at build time, or imported by the user?
- **D7. Pool contracts from their deployment.** Are they walked from their
  deployment, outside the "since added" rule? Is the walk a per-network
  opt-in, given its download?
- **D8. Which account index.** Kohaku's `accountIndex` belongs to the
  recovery phrase, not to an address. One privacy account per phrase and
  network, index 0, is proposed. Which index other Kohaku wallets use is to
  be checked first.
- **D9. Merging the parts.** May the parts be merged into `feature/kohaku`
  by their author (the feature-branch skill, step 3)?

## Sources

- Kohaku: `packages/plugins/src/base.ts`, `src/host/index.ts`,
  `src/host/mnemonic-keystore.ts`; `packages/privacy-pools/src/account/`,
  `config.ts`, `utils.ts`, `verification/root-verification.ts`,
  `state/thunks/discoverUserSecretsThunk.ts`, `data/ipfsAsp.service.ts`,
  `data/saga-log-source.ts`; `packages/tornado-cash/src/account/`;
  `packages/railgun/README.md`.
- kohaku-rs: `README.md`, `crates/tornadocash/`.
- Kohaku's roadmap: <https://notes.ethereum.org/@niard/KohakuRoadmap>.
- Myotis: `docs/eth-getlogs-design.md`, `docs/seeded-log-histories.md`,
  `docs/railgun-android.md`, `docs/privacy-and-tor.md`,
  `myotis-api/src/main/java/io/myotis/api/ChainHandle.java`.

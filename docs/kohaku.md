# Kohaku compliance

How Punicus becomes a Kohaku-compliant wallet: it holds the private notes
that any Kohaku wallet derives from the same recovery phrase, and it finds
the same notes on restore. Railgun comes first, then Privacy Pools (*The
plan*). This page is a plan. Nothing of it is built, and every decision it
names is open until the owner takes it (*Open decisions*). Issue #219 tracks
it; issue #101 is Railgun's own design, which this page builds on. Where this
page and the rules under `.claude/` (their short form in CLAUDE.md) disagree,
the rules win and this page is fixed.

Read at Kohaku `8c83d64` (2026-11-02), kohaku-rs's `main` of the same week
and Myotis `5578e75` (2026-11-03). The facts below are from those commits.

## What "compliant" means here

Kohaku ([ethereum/kohaku](https://github.com/ethereum/kohaku)) is the
Ethereum Foundation's privacy SDK for wallets, and a roadmap. It publishes no
certification and no checklist. Punicus uses it as a **specification, not
as a runtime**:

- Punicus copies, exactly, what decides which notes a recovery phrase
  holds: the derivation paths, the note-secret scheme, the commitment
  formats, the discovery rule on restore and the root checks.
- Punicus's port has the semantics of Kohaku's plugin interface, so every
  protocol looks the same to the use cases and the screens.
- Punicus follows Kohaku's releases the way it follows Myotis's: a pinned
  commit, and a move of the pin that is reviewed (*Following Kohaku*).
- Punicus does not run Kohaku's TypeScript. The code is Kotlin in `:data`,
  or Rust linked the way the Myotis natives are (D9).

Why not the runtime:

- Punicus has no JS engine. Embedding one, with zero-knowledge proving in
  JS or WASM, is heavy on a phone.
- Kohaku's host gives a plugin `keystore.deriveAt(path)`, a raw private key.
  Running npm code with that would break *Key material* in
  `.claude/rules/trust.md`.
- Its provider is plain JSON-RPC. The log index's typed refusals
  (`LogsReading.NotCovered`, `BeforeStart`) would be flattened into errors
  the plugin interprets.
- Its packages are alpha and say that later versions may break them.

### For Railgun, Kohaku's keystore and Railgun's engine

Kohaku has no Railgun code today: `@kohaku-eth/railgun` is a placeholder
("Do not depend on this stub"). What Kohaku does define for Railgun is how
its keys come from the recovery phrase, in its reference keystore, and it
defines them as `@railgun-community/engine` does (`key-derivation/bip32.ts`).
So for Railgun, compliance is:

- the keys exactly as Kohaku's keystore derives them (*Railgun keys*);
- everything else, the 0zk address, the note encryption, the commitments,
  the nullifiers and the tree, exactly as Railgun's engine at a pinned
  version computes it. #101 read engine 9.9.1 and wallet 10.8.6.

The same words then open the same 0zk wallet in Railway, the Terminal Wallet
and any Kohaku wallet. Once Kohaku brings its Railgun plugin back, its code
becomes the source of the vectors and the pin moves to it.

## What the spec pins

### The seed

- Kohaku's reference keystore (`packages/plugins/src/host/mnemonic-keystore.ts`)
  takes the BIP-39 seed with the empty passphrase. It derives BIP-32
  secp256k1 keys, except under Railgun's coin type 1984.
- Punicus fits: `Bip39.seed` uses the empty passphrase, and a phrase's
  envelope keeps its seed (owner ruling, 2026-10-15). Create account stores
  its words through the same import.
- An account with no seed here holds no notes: a watched account, which has
  no signing path at all, and a Safe, which has no seed of its own (it may
  still shield, *A Safe*). The actions that need notes are greyed out and
  say why (owner ruling, 2026-10-18, #55); Kohaku's own refusal is
  `UnsupportedAccountError`. Importing a raw private key is not offered
  (docs/wallet-ux.md, still the owner's, #11); an account imported that way
  would be the same case.

### Railgun keys

- A path whose second index is 1984' takes Railgun's hash chain, not BIP-32
  secp256k1. The master is `HMAC-SHA512(key = "babyjubjub seed", data =
  seed)`. Each child is `HMAC-SHA512(chainCode, 0x00 ‖ chainKey ‖
  ser32(index))`. Only hardened indices exist (`deriveRailgunEngineKey`).
- The spending key (BabyJubJub) is at `m/44'/1984'/0'/0'/<i>'`, the viewing
  key (ed25519) at `m/420'/1984'/0'/0'/<i>'` (#101).
- Only an account with a seed gets a 0zk wallet. A shield to a pasted 0zk
  address needs no key of the user's at all.

### Railgun notes and the tree

As Railgun's engine computes them, and as #101 sets out: the `0zk1…` address
(bech32: the master public key and the viewing public key), the shield's
encrypted note (the ed25519-based shared key, AES-256-GCM and AES-CTR), the
note public key `npk = poseidon(masterPublicKey, random)`, the commitments,
and the nullifiers. Scanning reads the proxy's `Shield`, `Transact`,
`Nullified` and `Unshield` events from its deployment block.

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

- **Railgun:** a note is the wallet's when its viewing key decrypts it and
  it re-hashes to its leaf. Every note of the proxy's history is tried.
- **Privacy Pools** (`state/thunks/discoverUserSecretsThunk.ts`): try
  deposit index 0, 1, 2… and stop at the first whose precommitment no
  `Deposited` event carries; the gap limit is zero. For each deposit, follow
  its change notes while the newest one's nullifier hash has been spent.

Punicus uses the same rules. Another one finds fewer notes, or notes the
other wallets do not.

**Only over complete coverage.** Discovery and the balance run only once the
pool's entry is covered from its deployment block to the head the roots are
read at (*The root checks*). Over partial coverage, "no event carries this
precommitment" cannot be told from "not indexed yet": with the gap limit at
zero, discovery would answer "no notes" or stop early, and a note whose
`Withdrawn` or `Nullified` log is not covered yet would count as unspent. That
is a wrong balance, not a late one. Until coverage is complete the state is
"syncing", never "no notes". The port already enforces the reads:
`LogIndex.logs` answers only a range it covers in full, and `NotCovered`
otherwise. An implementation never acts on the part of a range it has.

### The plugin semantics

The port mirrors `packages/plugins/src/base.ts`:

- `balance(assets)`, and `prepareShield`, `prepareTransfer`,
  `prepareUnshield` and their multi-asset forms, each behind a feature flag.
  Railgun has all three, private transfers between 0zk addresses included.
  Privacy Pools has shield, unshield and its own `ragequit`, and no private
  transfer. A feature a protocol lacks is greyed out, never hidden.
- Kohaku's errors map onto display-ready UiState variants: unsupported
  asset, chain or account; invalid address; insufficient balance;
  multi-asset not supported; transfer not supported.

### What "no private transfer" means

- A private transfer moves value from one shielded note to another inside
  the pool. On chain, the amount, the sender and the recipient stay hidden.
  Railgun has it: a payment to a `0zk…` address.
- Privacy Pools and Tornado Cash have none. Funds leave the pool only by a
  withdrawal to a public address, and its amount and recipient are public.
- The recipient need not be the user's own. A withdrawal straight to a payee
  is a payment whose sender stays private.
- The link between a deposit and its withdrawal is broken cryptographically,
  not hoped away. The proof shows that some deposit of the set is spent,
  never which one. On Privacy Pools the set is the deposits the association
  set approved.
- What links them again is the user's own behaviour:
  - **Gas.** A fresh address holds no ETH. Funding it from a known account
    links the two. That is what the relayer or bundler is for (D4).
  - **Amount and timing.** Privacy Pools takes any amount, so the same odd
    amount out soon after it went in is easy to match. A partial withdrawal
    with change left in the pool, a round amount and time help. Tornado
    Cash's fixed denominations avoid this by design.
  - **What follows.** Sending the funds back to a known account, or to an
    exchange account in the user's name, links them again.
  - **The IP address**, which the relayer sees (D4).

### The root checks

- **Railgun:** the contract records every root a tree has ever had
  (`rootHistory`), so a tree cut short still has a valid root, and the stock
  client reports such a tree as complete. Punicus runs the checks Myotis's
  `docs/seeded-log-histories.md` (*Closing the gap on the client*) and #101
  list, and fails loudly on each:
  - a rejected batch, or a commitment it cannot place, is an error, never a
    place to stop;
  - the rebuilt root of the open tree equals `merkleRoot()` at the head;
  - each note held is unspent by `nullifiers(tree, nullifier)`;
  - each Shield note's `npk` is recomputed from the wallet's own key, and a
    mismatch drops the note.
- **Privacy Pools:** the rebuilt state root is the pool's `currentRoot()` or
  one of its 64 most recent roots. The association set's root equals the
  Entrypoint's `latestRoot()` exactly (`verification/root-verification.ts`).
- **Tornado Cash:** `isKnownRoot(root)`. Kohaku leaves the check off by
  default (`verify = false`). Punicus always runs it: a stricter check
  derives the same notes.
- **The head they are read at.** Punicus's verified call
  (`VerifiedChain.call`) reads at the verified head (`latest`) only. So each
  root is read at the head, and the pool's logs must be covered up to that
  same head. Where a leaf landed between the top the logs reach and the
  head, the check runs again once coverage has caught up; Privacy Pools'
  64-root window absorbs a few such leaves, and Railgun's open-tree check
  waits for them.

## How it fits Punicus's rules

### Key material

- Spending secrets stay inside the key-store adapter: Railgun's spending
  key; Privacy Pools' note signature, nullifier, salt and sender key.
- The adapter derives them from the envelope's seed, in native code where it
  signs (docs/keys-and-signing.md, rule 7), and calls the prover with them
  (D9). What leaves it is public: a commitment, a nullifier, a finished
  proof, a signature over a transaction.
- **Railgun's viewing key is the exception to settle.** Scanning decrypts
  every note of the proxy's history with it. Either the scan runs inside the
  adapter, or the viewing key leaves it, which would add an entry to the
  closed list of exceptions (#101's question 4; D2).
- This needs an amendment to docs/keys-and-signing.md, which is the owner's
  (D2).
- None of this hashing is in `:domain`. Keccak-256 there is for transaction
  encoding and hashing alone (owner ruling, 2026-10-18, #50), and Poseidon
  is no library `:domain` may take. The note schemes and the trees live in
  `:data` or in Rust (D9); `:domain` sees the port and public values only.

### Chain data

- Everything a balance rests on is a verified read: the pool's logs from the
  log index, the roots and nullifiers from verified calls.
- From outside come:
  - **Railgun:** the PPOI (Private Proofs of Innocence) aggregator nodes, which
    decide what can be spent and receive a proof with every spend; the
    broadcasters, reached over Waku, which pay a spend's gas so that no
    public account of the user's pays it. Both are #101's question 2 (D4).
  - **Privacy Pools:** the association set's tree, fetched by the IPFS CID
    in the Entrypoint's verified `RootUpdated` log and used only when its
    root equals `latestRoot()` (D5); and a relayer or an ERC-4337 bundler,
    since Kohaku's withdrawal (`prepareUnshield`) has no direct path (D4).
    Its ragequit is an ordinary transaction and needs neither.
- A shield needs nothing from outside on either protocol.

### A Safe

- **Railgun:** a Safe can shield (`shield` has no caller check). Shielded
  funds are spent with one 0zk spending key: Railgun has no shielded
  multisig, so the Safe's threshold stops at the shield. The review says so
  before the owners sign, and a co-owner checks the encrypted recipient
  instead of trusting it (#101, *Custody stops at the shield*; its
  question 3).
- **Privacy Pools:** a note's secrets come from a recovery phrase's
  signature. A Safe may send the deposit, but the note then belongs to that
  phrase, not to the Safe's owners together.

### History

A tree needs every log of the pool since its deployment, whenever the
user's own notes were made. The "since added" rule (owner rulings,
2026-10-20, #85) is about accounts. Pool contracts are read from their
deployment (D7).

### Networks

- **Railgun:** the RailgunSmartWallet proxy,
  `0xfa7093cdd9ee6932b4eb2c9e1cde7ce00b1fa4b9` on Ethereum, deployed at
  block 14,737,691, and `0xecfcf3b4ec647c4ca6d49108b311b7a7c9543fea` on
  Sepolia, created at block 5,784,774 (Myotis `docs/railgun-poc.md`, which
  explains why the numbers in circulation, 14,693,013 and 5,784,866, are
  wrong as a watch floor). One entry per network covers what the wallet
  reads.
- **Privacy Pools:** Kohaku pins the Entrypoint on Ethereum
  (`0x6818809EefCe719E480a7526D76bD3e561526b46`, from block 22,153,713) and
  Sepolia (`0x34A2068192b1297f2a7f85D7D8CdE66F8F0921cB`, from 8,461,453),
  `PrivacyPoolsV1_0xBow` in `config.ts`. The pools are found from the
  Entrypoint's own events.
- Neither is pinned on Gnosis Chain here: the actions are greyed out there.
- Each pin is proven on chain by the PR that adds it, as for swaps.

## The log index

### What it must hold

- **Railgun:** one `Emitter` entry (an address entry) for the proxy,
  unrestricted, from its deployment block. Today's demo seeds hold about
  438,000 logs over 11.4M blocks on Ethereum (261 MB) and 14,800 over 6M
  blocks on Sepolia (9 MB).
- **Privacy Pools:** an `Emitter` entry for the Entrypoint and for each
  pool, from the deployment block.

### What Punicus can do today

- **Watch:** yes. `LogIndex.configure(network, listOf(LogWatch(
  LogWatchKey.Emitter(contract), deployBlock)), emptyList())` is exactly the
  engine's address entry. `EngineLogIndex.logs` serves it and refuses a range
  below its start (`BeforeStart`).
- `FollowAccountLogs` unwatches only accounts that left the wallet
  (`AccountLogs.kt`, `gone`). It leaves a pool's entry alone.
- **Import:** no. Myotis's `ChainHandle.importLogIndexFiles` and
  `exportLogIndex` exist on the JVM API (ABI v24). Punicus's `LogIndexChain`
  wraps only `setLogIndexConfig`, `logIndexStatusJson` and `getLogs`, and the
  `LogIndex` port has no import. On iOS, Myotis's typed `ChainHandle` has no
  import either; only `RustEngine.importLogIndexFiles` does.

### Why a seed at all: the walk is possible, but not affordable

The walk back to a contract's deployment is always possible in principle,
and it is verified all the way. A seed does not make anything more correct:
it adds trust in its publisher. It is there for cost and for availability.
Read at Myotis `dc3a1c0` (2026-11-10).

- **The logs are small; proving none is missing is not.** Railgun's whole
  mainnet history is about 426,000 logs, an index of about 243 MB
  (Myotis `docs/railgun-poc.md`).
- A block is skipped only when its `logsBloom` rules the contract out.
  Mainnet blocks carry 2,000 or more bloom elements, each setting 3 of 2,048
  bits. So 85–95 % of blocks match even an address that never appears, and
  each match is settled only by the block's complete receipts.
- Myotis estimates those receipts, at 100–200 KB a block, at about 1 TB for
  a range of about 9M blocks, unmeasured
  (`docs/logindex-verified-bundle-design.md`). It calls Railgun's walk a
  multi-day download.
- Every header back to the deployment is fetched as well. At roughly 600
  bytes a header, an estimate, that is about 7 GB for Railgun's 11.4M blocks
  on Ethereum and about 2 GB for Privacy Pools' 3.6M on Sepolia.
- **Peers may no longer hold the data.** Under EIP-4444's history expiry,
  execution clients may drop old block bodies and receipts over the `eth`
  protocol, pre-Merge history first, and many nodes do.
  - Railgun (block 14,737,691) and Tornado Cash (from about 9.1M) were
    deployed before the Merge (block 15,537,394). Myotis's own seed generator
    needs a node that still holds pre-Merge receipts (`docs/railgun-poc.md`).
  - Myotis has no fallback for expired history, and the Portal Network is
    abandoned. The walk then stalls rather than fails, and that range is
    never covered (`docs/eth-getlogs-design.md`).
  - Privacy Pools (from block 22,153,713) is after the Merge and not affected
    today. A rolling expiry of about a year would reach it too.
- **Sepolia has neither problem to that degree.** Its blooms are sparse and
  its history is served. There a walk costs time and download, not
  feasibility. Walking only is one of D6's answers; under the heavy-download
  rule it waits for an unmetered connection and takes days, not forever.

### Why Railgun needs an import, even on Sepolia

- Mainnet's `logsBloom` is so full that 85–95 % of blocks look like
  candidates (Myotis `docs/seeded-log-histories.md`). The walk then fetches
  nearly every block's receipts, and Myotis calls Railgun's walk a
  multi-day download.
- Railgun's history is 6M blocks deep even on Sepolia. Its balance is shown
  from an imported seed, with Myotis walking and verifying everything above
  the seed's top (Railgun part 4).
- An import must not be followed by a push of Punicus's own entry for the
  proxy from a lower block: the engine keeps the lower block, and the band
  below the seed would be refused until walked (#101).
- Privacy Pools' Sepolia history is about 3.6M blocks; whether its walk is
  bearable is measured by its part 3.

### What an import must respect

From Myotis's ruling on seeded histories (2026-09-25,
`docs/seeded-log-histories.md`) and its import design
(`docs/eth-getlogs-design.md`, *Import*):

- A seed is served on the word of the protocol's maintainers, and only to a
  client that checks its rebuilt root on chain. Punicus's trust rules do not
  carry this exception today (D6; #101's question 1).
- Today's seeds do not qualify: they are Myotis's own fetch from a node over
  JSON-RPC, a DEBUG / DEMO artefact. A production seed is published or
  vouched for by Railgun's maintainers.
- Myotis does not yet tell seeded coverage from walked coverage. That marker
  is its own precondition, unmet. Until it exists, Punicus labels a seeded
  span as seeded.
- A seed must reach within 500,000 blocks of finality (about 69 days), or it
  imports and never catches up. Today's seeds are usable to block 26,597,965
  on Ethereum and 12,324,017 on Sepolia, both about early December 2026. A
  published seed needs a refresh inside that (#101's question 6).
- A seed arrives bundled at build time or picked by the user, never fetched
  by the wallet.
- An import is merged by clamping every entry's coverage to the lowest top
  among the sources. A seed that ends below the head pulls the accounts'
  history coverage down with it until the gap is walked again.

## How walletbeat rates it

[Walletbeat](https://github.com/walletbeat/walletbeat) rates wallets, and
its *Private token transfers* attribute is where this plan shows. Read at
walletbeat `dc05991` (2026-11-10):
`src/schema/attributes/privacy/private-transfers.ts` and
`src/schema/features/privacy/transaction-privacy.ts`. What it rates is
walletbeat's, not a rule here.

### What it recognizes

- Four technologies: ERC-5564 stealth addresses, Tornado Cash Nova,
  Privacy Pools and Railgun.
- **Tornado Cash classic is not among them.** Nova, the variant with
  transfers inside the pool, is. The Tornado Cash of D3 is classic, so it
  would earn nothing there.
- **The default send decides first.** A wallet whose default transfer of
  ETH or an ERC-20 is public fails ("not the default"), whatever it
  integrates. A private technology as the default, or the user's explicit
  choice on every send (`EXPLICIT_CHOICE`), avoids that.
- **The worst integration sets the rating.** With several technologies
  supported, the ratings merge to the worst one. A half-built protocol
  pulls a complete one down.
- None of the wallets walletbeat lists is marked as supporting Privacy Pools
  today.

### What a pass needs, for Privacy Pools

Each missing item lowers the rating, in this order:

- the Ether pool and the USDC pool on Ethereum;
- ragequit;
- deposit data that can be exported and imported, and no custodian that can
  read it or that an export depends on. Punicus derives the secrets from the
  recovery phrase and stores none, so the same words restore them in any
  Kohaku wallet (*Privacy Pools note secrets*). Whether walletbeat counts
  that as export and import is its reviewers' call;
- a withdrawal through a relayer. A withdrawal without one is rated not
  private: the account that pays the gas is public. A default relayer that
  learns the IP address is rated not private too (D4);
- a warning against operations in quick succession;
- encrypted deposit data held by a custodian who learns the IP address
  leaves the integration "relying on an external provider".

### What a pass needs, for Railgun

Private transfers between 0zk addresses (part 5); a warning about the
correlation of a shield, of an unshield to the user's own addresses, and of
operations in quick succession; the tree synced and decrypted on the
device, as Punicus plans it; a broadcaster that does not learn the IP
address, broadcasting being the default over self-relay (D4). A warning
about sharing a viewing key is noted, not required.

### What it means for this plan

- A Railgun that shields only (parts 1 to 4) or a Privacy Pools without
  relayed withdrawals is an incomplete integration to walletbeat, and,
  under the worst-rating merge, lowers a complete one beside it.
- Punicus's send is public by default today, so the attribute fails until
  the send screen makes the choice explicit or a private send the default.
  That is a screen decision, docs/wallet-ux.md's and the owner's.

## Following Kohaku

- **The pins.** The Kohaku commit this page matches, `8c83d64`, and, for
  Railgun, the engine version the vectors are made with. Each moves only in
  a PR that says so.
- **Vectors.** A script runs the pinned code on a mnemonic generated for the
  purpose and never funded. It writes, per protocol, the keys or note
  secrets, the addresses, the commitments, the nullifiers, the discovery
  results on a fixed log set and the roots. The file names the commits and
  versions it came from. `:data`'s tests check Punicus against it, and that
  those are the pins, so a mismatch fails `./gradlew build`.
- **A move of a pin** regenerates the vectors. A changed derivation or note
  format is a breaking change for the user's funds: the PR says so, and the
  owner decides whether Punicus follows.

## The plan

Railgun first, then Privacy Pools (the direction given for this plan,
2026-11-03, on PR #220). Each is a multi-PR plan of its own, landing part by
part through its feature branch and reaching `main` in one final PR
(`.claude/skills/feature-branch/SKILL.md`). Each part follows
docs/architecture.md's checklist.

### Railgun, through `feature/railgun`

#101's parts, with the port and the vectors added where they are first
needed:

1. **Shield from an EOA to a pasted 0zk address.** The `ShieldedPool` port
   in `:domain` with Kohaku's semantics, its fakes and use-case tests. The
   `0zk1…` decoding, the encrypted note and the `shield` calldata; ERC-20,
   and ETH through RelayAdapt. The vectors script and file for what this
   part computes. No log index, no proof, no key of the user's. Needs D1 and
   D9.
2. **Shield from a Safe.** The WETH path, never RelayAdapt, and the recipient
   check in the signing request; one MultiSendCallOnly batch once #57 lands.
   The review says the Safe's threshold stops at the shield. Needs #101's
   question 3.
3. **The 0zk keys.** The spending and viewing keys in the key-store adapter,
   on Railgun's hash chain; the account's own 0zk address; shielding to it.
   Tested against the vectors. docs/keys-and-signing.md amended. Needs D2
   and D8.
4. **The private balance.** `LogIndex` gains an import; the JVM and iOS
   adapters (iOS through `RustEngine.importLogIndexFiles` until Myotis's
   typed handle has one); the seed bundled or picked. The scan, the tree,
   the client-side checks of *The root checks*, the balance, labelled as
   seeded. Sepolia first. Needs D6, D7, and the viewing key's place (D2).
5. **Spending.** Private transfer and unshield: the prover and Railgun's
   circuit files pinned by hash, PPOI, gas through a broadcaster. Needs D4
   and D9, and a Myotis issue if D9 puts the client there.

### Privacy Pools, through `feature/privacy-pools`, after Railgun

1. **The keychain** in the key-store adapter: the signing key at
   `m/28784'/1'/<accountIndex>'`, the note message signed in native code,
   the determinism check, the secrets derived and kept inside. Its vectors.
   Needs D3 and D8.
2. **Pool logs.** `Emitter` entries for the Entrypoint and its pools, found
   from the Entrypoint's events, each from its deployment block. Walked on
   Sepolia, its time and download measured. Needs D7.
3. **Pool state.** Poseidon over BN254 and the tree, rebuilt from
   `LeafInserted`; the root checks; discovery on restore; the balance.
4. **The prover**, for the pool's circuits, with the circuit files pinned by
   hash. Railgun's part 5 chooses the library first.
5. **Deposit**, from an account held here.
6. **Ragequit**, the public exit. It needs no service and no association
   set.
7. **Withdrawal.** The association set from IPFS, over Ktor, checked
   against `latestRoot()`; the relayer or bundler. Needs D4 and D5.
8. **Ethereum**, from an imported seed, as Railgun's part 4 built it. Needs
   D6.

### Across both

- **The screens**: where a private balance lives (per recovery phrase and
  network), the greyed-out actions, simple mode and Detailed view, a preview
  per state, docs/wallet-ux.md amended, screenshots in each PR that changes
  them. Each part that shows something brings its own screens.
- **Later**, each a plan of its own: Tornado Cash if D3 says yes
  (kohaku-rs's crate, linked as a native library); Tor for the engine's
  reads and sends (Myotis has it behind `-PtorEngine`; Punicus does not
  wire it).

## Open decisions

All the owner's. Each is recorded here as decided before its code lands.
#101's own open questions stand for Railgun; each is named where it belongs.

Where a decision is a rule, it lands in the rule file of its area, as every
ruling does (`.claude/rules/instructions.md`), with this page updated to
match:

- the off-chain services and sources, D4 (PPOI, broadcasters, a relayer or
  bundler), D5 (the IPFS gateway or 0xbow's API) and D6 (seeded histories),
  in `.claude/rules/data-sources.md`, whose *Open decisions* records each
  before its code lands; D6 in `.claude/rules/trust.md` as well, beside
  *Verified, or not available*;
- that the Railgun proxy and the Privacy Pools contracts are pinned and read
  as chain data like any other, in the same file, as it says of Uniswap's
  contracts;
- D2 in `.claude/rules/trust.md` and docs/keys-and-signing.md.

- **D1. The definition.** Is "Kohaku compliant" what *What "compliant"
  means here* says: Kohaku as the spec at a pinned commit, Railgun's engine
  where Kohaku has no code, checked by vectors?
- **D2. Secrets in the key-store adapter.** May the spending secrets, and
  the proving with them, stay inside the key-store adapter with only public
  values leaving it, as *Key material* above sets out? And Railgun's viewing
  key: does the scan run inside the adapter, or may the key leave it (#101's
  question 4)? Scanning without an unlock depends on that answer.
- **D3. Which protocols.** Railgun, then Privacy Pools. Tornado Cash: yes or
  no, given its sanctions history.
- **D4. The off-chain services of a spend.** Railgun: the PPOI nodes and the
  broadcasters, over HTTP with Ktor and over Waku (#101's question 2).
  Privacy Pools: a relayer (it learns the recipient, the amount and the
  device's IP address), or an ERC-4337 bundler and paymaster (Kohaku's
  default is Pimlico's public bundler). Which, and on what terms, as for CoW
  (owner ruling, 2026-10-22).
- **D5. The association set's tree** (Privacy Pools). From which IPFS
  gateway, or 0xbow's API instead. It is used only when its root is the
  Entrypoint's `latestRoot()`.
- **D6. Seeded histories.** Does Punicus adopt Myotis's ruling of
  2026-09-25, for clients that run the checks of *The root checks*? #101's
  question 1 asks the same for Railgun: only once Myotis's preconditions are
  met, on Sepolia now labelled as seeded (#101's recommendation), or only
  from the walk. Bundled at build time, or imported by the user?
- **D7. Pool contracts from their deployment.** Are they read from their
  deployment, outside the "since added" rule? Is following a pool a
  per-network opt-in, given its download?
- **D8. Which account index.** Railgun's index `i` and Kohaku's
  `accountIndex` belong to the recovery phrase, not to an address. One
  private account per phrase and network, index 0, is proposed. Which
  index other wallets use is to be checked first.
- **D9. Where a pool's client lives.** Kotlin in `:data`, or Rust linked
  as a native library: a kohaku-rs crate, or a client in the Myotis engine
  beside the index, which #101 recommends for Railgun (its option B; its
  question 5). Poseidon, the tree and the prover follow the choice;
  wherever they are, the secrets reach only the prover the key-store adapter
  calls (D2).
- **D10. Merging the parts.** May the parts be merged into their feature
  branch by their author (the feature-branch skill, step 3)? Asked once per
  plan.

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
  `docs/railgun-poc.md`, `docs/railgun-android.md`,
  `docs/privacy-and-tor.md`,
  `myotis-api/src/main/java/io/myotis/api/ChainHandle.java`.
- Punicus issue #101 (Railgun), which read `Railgun-Privacy/contract` and
  `@railgun-community/engine` 9.9.1 and `wallet` 10.8.6.

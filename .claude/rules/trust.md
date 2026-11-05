---
description: "Trust. Everything about the chain is verified by the embedded Myotis engine or shown as not available; the Safe refusals; network-upgrade warnings; a parameter is applied or refused; what a dApp sends; key material and how keys are held."
paths:
  - "domain/**/*"
  - "data/**/*"
  - "ui/**/*"
  - "android-app/**/*"
  - "desktop-app/**/*"
  - "ios-app/**/*"
  - "*.kts"
  - "docs/**/*"
---

# Trust

Loaded for every code module and for `docs/`. These rules bind every change.
docs/wallet-ux.md applies them to the screens. docs/keys-and-signing.md is
the contract for keys.

## Verified, or not available

- **Peer-trusted is never an option.** Everything the wallet shows about the
  chain is cryptographically verified by the embedded Myotis engine, or shown
  as not available. The engine's only trust anchors are sync-committee
  signatures and its embedded accumulators.
- No JSON-RPC provider, no indexer API and no "temporary" fallback supplies
  chain state, in any build type.
- **One older answer is shown, labelled as one** (owner's request,
  2026-10-19; docs/wallet-ux.md). While no verified head is fresh enough to
  read at, a balance the light client proved earlier (in this run, or kept
  across a restart) stays on screen: dated, marked as possibly changed since,
  left out of totals and never acted on.
- **In simple mode a value may be read a while ago, and says how long**
  (owner's decisions, 2026-10-26; docs/simple-ui.md). On a network without
  block access lists (EIP-7928), simple mode proves a balance, and the price
  it is valued at, when its screen opens and on pull-to-refresh, not at every
  head. While the light client verifies, such a balance is shown with its age
  and counts in totals, at the price read with it, for up to an hour. Older,
  it is dimmed and left out of totals, as above.
  Detailed view reads at every head, as before. A quote, a review and a
  signature always read fresh state, in either view.

## Past the weak-subjectivity window only on the user's word

(Owner's request, 2026-10-24.)

- A light client whose last trusted checkpoint is older than its network's
  weak-subjectivity bound refuses to sync. The bound is the engine's
  default (Myotis's `NetworkConfig.wsBoundPeriods()`), in sync-committee periods:
  13 on Ethereum and Sepolia, about 15 days of 27.3-hour periods; 3 on
  Gnosis Chain, about 34 hours of 11.4-hour periods.
- Accounts warns of it in that network's section, in plain words: what
  catching up has to trust, and what a forged chain could and could not do.
- Only the user's confirmation lets it catch up (the engine's
  `acceptStaleAnchor`). Per network, and for that run of the app alone:
  never automatically and never stored. A restart that still finds the
  checkpoint too old asks again.
- The bound itself is never raised: `setWsBoundPeriods` is not called.

## A Safe's fallback handler is set only to a pinned one

(Owner ruling, 2026-10-18, #63.)

- A Safe transaction that sets a fallback handler other than the pinned
  canonical CompatibilityFallbackHandler 1.4.1 of its network, or sets none
  (the zero address, which removes it), is shown and never signed, approved
  or executed here.
- Why: the handler answers EIP-1271 signature checks for the Safe. One that
  accepts every signature lets anyone spend what the Safe has approved.
- A guard removed and a module disabled are signed with a warning, not
  refused.
- A call of the Safe itself that is not decoded exactly (a settings change,
  or the empty zero-value rejection) is refused whatever its selector, since
  on chain it could still set the handler.

## Enabling a Safe module is refused

(Owner ruling, 2026-10-18, on #54: play it safe.)

- A Safe transaction that enables a module, whatever the module, is shown
  and never signed, approved or executed here. A module moves the Safe's
  funds without its owners.
- Disabling a module keeps its warning and is not refused.
- Which modules may be enabled is still open (#54). A temporary, warned
  "dangerous mode" past it is planned in #78 and not built.

## Network-upgrade warnings are peer reports, and say so

(Owner's request, 2026-10-17: warn ahead of a breaking hard fork, notify even
while the app is closed, and after it do nothing but ask for the update.)

- The engine's upgrade advisory is derived from what peers announce and is
  not verified. It is the one thing the wallet shows about the network that
  the light client does not prove.
- It is never chain state: no balance, nonce or transaction depends on it,
  and nothing in verification reads it. Every text showing it says peers
  report it.
- Only the light client's own state may make the app claim it cannot follow
  the network. The update screen, which replaces every other, needs BOTH an
  activation peers confirmed (not merely an announced time passing) AND a
  light client that is not verifying (not synced, or a stale head). That is
  Myotis's rule for its hosts.
- On peers' word alone the app shows a banner and notifications, never that
  screen (docs/embedding-myotis.md, *Network upgrades*).

## A parameter that can change the answer is applied or refused

(Myotis rule, learned the hard way in its #314.) Never accept a parameter and
silently ignore it. A well-formed result computed against something other
than what the caller asked for cannot be told from a correct one. Every
port's KDoc says what each argument does. An adapter that cannot honour one
fails the call.

## What a dApp sends is untrusted input

(Owner rulings, 2026-11-03, #22; docs/walletconnect.md.)

- **A closed list of methods:** `eth_sendTransaction`, `personal_sign`,
  `eth_signTypedData_v4`, and `wallet_switchEthereumChain` to a network the
  session holds. Every other method gets an unsupported-method error. A
  switch to a network outside the session, from testnet to real funds above
  all, gets the unrecognized-chain error (owner ruling, 2026-11-05, on #22).
- **`eth_sign` is refused in every case.** It signs a raw digest the dApp
  chose, which is what phishing asks for. A digest Serotine signs for a dApp
  is one it computed itself: EIP-191 for `personal_sign`, EIP-712 for typed
  data.
- A request is parsed strictly, with size limits. One that names another
  chain, or a `from` or an address that is not one of the session's accounts,
  is refused, never ignored.
- **The review shows only what Serotine works out itself:** the calldata
  decoded for the ABIs it knows, anything else raw and marked as not
  decoded, and a simulation through the verified `call` and gas estimate.
- **A dApp's `eth_sendTransaction` is answered only with an Ethereum
  transaction hash.** For a Safe, the hash of the `execTransaction` sent,
  never the `safeTxHash`. A request that cannot be met before it expires gets
  an error.
- A dApp's Safe transaction keeps every Safe refusal above.
- **No key reaches the WalletConnect layer.** A local signature goes through
  the signer port.
- **Session secrets are not key material**, since they cannot move funds:
  an app-private file, out of backups and device transfers, never logged,
  and not under the wallet password.

## Key material

An EOA's private key, a seed, a Safe owner key: it lives in the key-store
adapter and nowhere else. Never in a UiState, a log line, a `toString()`, a
crash report, or any serialized form that leaves that adapter. Signing is a
port that takes a request and returns a signature. The UI never touches a key.

### The exceptions are a closed list

Each exception is an owner ruling, dated here, with its conditions in
docs/keys-and-signing.md. A case not on the list follows the rule. Adding one
is the owner's decision, never a PR author's. Today the list has one entry:

- **The mnemonic screens** (owner ruling, 2026-10-15): the backup words shown
  at creation, and the words typed at restore.
  - They travel only as a dedicated mnemonic type with a redacted
    `toString()`, along one path: from the adapter through a use case and
    the ViewModel into the seed UiState at creation, and back the same way
    at restore.
  - Nothing on that path stores, logs or caches them.
  - The UiState is cleared when its screen is left. "Left" means: the app
    goes to the background, the screen turns off, or the screen is popped.
    A configuration change (a rotation, a fold, a resize, a theme or font
    change) is not a leaving: the words stay in the ViewModel's memory
    across it, never in a saved state or a Bundle (owner ruling,
    2026-10-18, #39).
  - Everything the adapter derives from the words, the seed and the keys,
    stays under the rule above.
  - Open, the owner's to decide: whether Create account, one screen using
    both legs of the path, is within the entry (#40).

## How keys are held

**docs/keys-and-signing.md is the contract** (owner decisions, 2026-10-15).
This is the summary a reviewer holds a diff against.

- **Two factors, both necessary.** No platform holds secp256k1 in hardware,
  so an EOA key or a mnemonic-derived owner key is a software key wrapped at
  rest. On the always-present password path BOTH factors must be
  cryptographically necessary to unwrap it: the Argon2id-stretched password
  AND a secret only this device's hardware key store yields. A password that
  is merely checked, or a wrap that opens without the hardware, is a finding,
  whatever it saves. The desktop cases below, by dated rulings, stand on the
  password path alone.
- **Biometric unlock** of that envelope is the user's opt-in (owner ruling,
  2026-10-15), bound to a key and never to the UI: a second hardware key with
  per-use Class 3 biometric authorization. Disabling it deletes that key.
  Weak biometrics never qualify.
- **The plaintext key exists only inside the key-store adapter**, creation
  and import included (the mnemonic words leave it only under the exception
  above). In signing it exists only inside the call, in native code, as a
  byte array zeroed afterwards. The ciphertext is excluded from cloud backup
  AND from device-to-device transfer.
- **A Safe may be owned by a hardware P-256 key directly.** Such a key is
  never the sole owner of a Safe holding value: a recovery owner or a
  recovery module is part of creating the account. EOAs are secp256k1 only.
- **Key storage closes the theft path only.** The hardware signs whatever
  digest the app hands it. For a Safe holding value the app's own preview is
  never the sole check; the policy lives on chain and in an independent
  co-signer, as the doc sets out. No dApp browser runs in the signing
  process.
- **An imported mnemonic's envelope holds its BIP-39 seed**, so further
  accounts can be derived without the words (owner ruling, 2026-10-15).
- **Argon2id's cost, where the password stands alone**, is sized by the
  device's memory (owner ruling, 2026-10-18, #36): 256 MiB × 4, 128 MiB × 8
  or 64 MiB × 16, about the same time each. Android starts at the tier of
  its total memory and low-RAM flag; the desktop starts at 256 MiB × 4. A
  seal whose memory cannot be had steps down a tier, on any device, never
  below the 64 MiB floor. The user is told when a lighter tier is used: a
  device's lighter tier with its protection, and a seal that stepped down
  when the key is stored. An envelope keeps the tier it was sealed at.
  Argon2id short of memory is its own outcome (sealing, opening, signing),
  never "the key store does not work", and a failed seal stores nothing.
  The hardware path's 64 MiB × 3 and stored envelopes are unchanged
  (docs/keys-and-signing.md, *Costs*).
- **A desktop without a hardware key store** stands on the password path
  alone, with Argon2id run harder, and the app tells the user so.
- **A Mac with a Secure Enclave** uses it as iOS does (owner ruling,
  2026-10-15), the app signed or not (owner ruling, 2026-10-16). Its key is
  then bound to the Mac but not to the app. Binding it to the app takes a
  different design: the key in a signed app's keychain access group.
- **iOS's envelope waits for the iOS app** (owner ruling, 2026-10-16).
- **Windows and Linux stand on the password path alone for now**, a TPM
  present or not. The TPM path of docs/keys-and-signing.md's rule 9 comes
  later. Until then the app tells the user that it does not use the TPM yet
  (owner ruling, 2026-10-16).
- **Deleting an account's key needs no wallet password** (owner ruling,
  2026-10-18, #17): removing the account deletes its envelope, and the app
  asks the user to confirm the removal first (`RemoveAccountDialog`).
- **A Safe owner's key may live in an external wallet** (owner ruling,
  2026-11-03, #22), reached over WalletConnect. Its backup and its unlock
  are that wallet's; Serotine keeps only the address and the session. Safe
  owners only; an EOA's key in another wallet comes later or never. It is
  asked for `eth_signTypedData_v4` of the `SafeTx` alone, never a bare hash
  to sign blind, and its signature is kept only when it recovers, locally,
  to the session's account and that account is a current owner.
- **Open, the owner decides**, recorded here when made: the synced-passkey
  owner tier, hardware external signers (#42), and threshold signing with
  Myotis.

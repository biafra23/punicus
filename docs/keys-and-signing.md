# Keys and signing

Serotine holds private keys; Myotis never does. This page is the design for
how they are held, what the user chooses between, what a hardware-backed key
can and cannot do for a wallet, and the recommendations that came out of the
owner's key-storage discussion of 2026-10-15. **This page is the contract**
for the key-store and signing adapters; the *Trust* section of CLAUDE.md is
the summary a reviewer holds a diff against. Platform facts below were
checked in October 2026 and carry their sources at the end; re-check them
before relying on one in code.

## What the platforms can hold

| platform | hardware key store | curves and algorithms | binding a key to the user |
|---|---|---|---|
| Android (minSdk 29) | Keystore in the TEE; StrongBox on devices with a secure element | TEE: P-256/384/521, Curve25519 from API 33, RSA, AES, HMAC. StrongBox: P-256, RSA, AES, HMAC only | per-use or timed authorization, strong biometrics and/or device credential; invalidation on biometric re-enrollment |
| iOS, iPadOS | Secure Enclave | P-256 only (ECDSA, ECDH); ML-KEM / ML-DSA from iOS 26 | current biometric set, passcode, application password; this-device-only accessibility classes |
| macOS | Secure Enclave on Apple silicon and T2 Macs, same API | as iOS | as iOS |
| Windows | TPM 2.0 through CNG's Platform Crypto Provider | RSA, ECDSA P-256/P-384 | Windows Hello |
| Linux | TPM 2.0 through tpm2-tss where one exists; often nothing | per TPM | nothing an app can rely on |

**No platform holds a secp256k1 key in hardware, and none exports a key it
generated.** Android's secure key import only goes inward; the Secure Enclave
has no import at all. Samsung's Blockchain Keystore does hold secp256k1 inside
Knox, but it is Galaxy-only, partner-gated and last released in December 2023;
it is not a basis for a cross-platform wallet. Two consequences follow:

- An **EOA key**, and a Safe owner key derived from a mnemonic, is a software
  key. The most the hardware can do for it is wrap it at rest and gate its
  use. That is the envelope below, and there is no more secure way to hold a
  secp256k1 key on these platforms; the security gains are in the details.
- A **P-256 key born in the Secure Enclave or StrongBox can own a Safe
  directly**, through Safe's WebAuthn signer contracts. For such an account
  no secp256k1 key exists anywhere.

## The account tiers

| tier | key | backup | daily unlock | status |
|---|---|---|---|---|
| **Hardware-bound Safe owner** | P-256 in the Secure Enclave / StrongBox | none: it is a device credential | the OS's own gate, bound to the key and chosen at creation: current biometrics only, or biometrics or device credential. This tier has no password factor | decided (2026-10-15); the key's policy is under *A Safe owned by a hardware P-256 key*, and it needs a recovery owner, see *Backup and recovery* |
| **Mnemonic key** | secp256k1 from a BIP-39 seed, in the envelope | the mnemonic, written down once at creation | password; biometrics if the user enables them | decided (2026-10-15); every EOA, and a Safe owner the user wants to be able to restore |
| **Synced-passkey Safe owner** | P-256 passkey through the platform WebAuthn API | synced end-to-end encrypted by iCloud Keychain / Google Password Manager | biometrics | recommended as an option; owner's decision open |
| **Owner key in an external wallet** | secp256k1 in another wallet (MetaMask, say), reached over WalletConnect; Serotine keeps only its address and the session | the other wallet's | the other wallet's | decided (2026-11-03, #22); Safe owners only, an EOA later or never. It is asked for `eth_signTypedData_v4` of the `SafeTx` alone, and its signature is checked here (docs/walletconnect.md, *Direction 2*) |

Which tier an account uses is the user's decision. The app explains the
trade when the account is created and never downgrades a tier on its own.

## The envelope: a secp256k1 key at rest

```
DEK          random 32 bytes; exists only in memory, only while unwrapping
blob         AES-256-GCM(DEK, the secp256k1 key or the BIP-39 seed)

password path (always present)
  HW_pw      a hardware key with no user-auth requirement
             (Android: an HMAC-SHA256 key in the Keystore, StrongBox when
              available; iOS: a Secure Enclave P-256 key used through ECIES
              or ECDH, the enclave has neither HMAC nor AES)
  P          Argon2id(password, salt, params)
  S_hw       Android: HMAC(HW_pw, P), computed per unlock, never stored
             iOS:     random 32 bytes, stored wrapped by HW_pw
  KEK_pw     HKDF( P || S_hw )
  stored     AES-256-GCM(KEK_pw, DEK) with salt and params as its AAD;
             the salt; the Argon2id parameters

biometric path (optional, the user's choice)
  HW_bio     a second hardware key that requires per-use strong-biometric
             authorization
  stored     wrap(HW_bio, DEK)
  disable    delete HW_bio and this wrapped copy; the password path is untouched
```

The rules a reviewer holds the adapter against:

1. **Both factors are necessary, cryptographically.** The password is mixed
   into the key-encryption key, never merely checked. A password that is only
   compared, with the hardware key doing the real decryption, is bypassed by
   anything running inside the app's process that can call the key store. And
   the hardware secret `S_hw` is mixed in too, so a blob copied without the
   device is useless and cannot be brute-forced at all.
2. **The password is stretched with Argon2id** (scrypt is the acceptable
   fallback where no Argon2 is available), and the threat it answers is
   precise. A blob copied without the device — a backup, a file-system dump —
   cannot be attacked at all, because `S_hw` is missing. A compromise inside
   the app's process can call `HW_pw`; on iOS that yields the static `S_hw`
   once, and with the blob the password is then brute-forced *offline*, on
   GPUs. Argon2id's memory-hardness is what makes that expensive, so its
   parameters are chosen against that attacker, not for feel: the largest
   memory the slowest supported phone tolerates, never below 64 MiB, and the
   time that costs, up to about a second. On Android the hardware is made
   necessary for every guess instead: `S_hw` is `HMAC(HW_pw, P)` with `HW_pw`
   a Keystore HMAC key, so each guess needs a hardware round trip and the
   search cannot leave the device. The Secure Enclave has no HMAC, so on iOS
   the KDF carries the load; the enclave's own answer is the
   application-password flag of rule 6. The salt and the Argon2id parameters
   are stored next to the blob and bound as the additional authenticated data
   of the AES-GCM wrap of `DEK`, and the floor is re-checked when unwrapping:
   a parameter block below it is refused, not honoured, so tampering with it
   cannot downgrade the KDF. Where the password must stand alone, the cost is
   sized by the device's memory (owner ruling, 2026-10-18, issue #36): three
   tiers of about the same wall time, 256 MiB × 4 passes, 128 MiB × 8 and
   64 MiB × 16, the device's own tier first and a lighter one when Argon2id
   cannot have the memory at that moment, never below the 64 MiB floor; the
   user is told when a lighter tier is used. Argon2id short of memory is its
   own outcome, never a key store that does not work (*Costs*).
3. **The envelope's biometric path binds to a key, not to the UI.** On
   Android: a key with `setUserAuthenticationRequired(true)` and per-use authorization
   (`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` from API 30;
   on API 29 the `-1` validity-duration form), `setInvalidatedByBiometricEnrollment(true)`,
   `setUnlockedDeviceRequired(true)`, used only through a `BiometricPrompt`
   with a `CryptoObject`. Class 3 (`BIOMETRIC_STRONG`) only; a device with
   only weak biometrics gets no biometric path and is told why. On iOS:
   `SecAccessControlCreateWithFlags(kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
   [.privateKeyUsage, .biometryCurrentSet])` on a Secure Enclave key.
   `.userPresence` allows passcode fallback and is a different, weaker policy;
   if offered, it is offered by name. This is the envelope's second key,
   `HW_bio`; the hardware-bound Safe owner key has its own policy, below.
4. **Each path has its own hardware key.** Enabling biometrics never weakens
   the password path; disabling them is a deletion, not a flag.
5. **Verify hardware backing on Android.** `KeyInfo.getSecurityLevel()` (API
   31) or `isInsideSecureHardware()` before it, or the attestation chain.
   Emulators and some devices give a software key store; the user is told,
   and the app does not pretend otherwise. Prefer StrongBox
   (`setIsStrongBoxBacked(true)`) and fall back to the TEE on
   `StrongBoxUnavailableException`.
6. **Apple's application-password flag** (`.applicationPassword`, fed through
   `LAContext.setCredential`) is described by Apple as an additional
   encryption mechanism inside the enclave, not an access constraint. Developer
   reports show it misbehaving on Secure Enclave keys in some OS versions.
   It may be added as a further layer after testing on current iOS; it is
   never the foundation and never replaces rule 1.
7. **The plaintext window is tiny.** The secp256k1 operations run in native
   code (libsecp256k1 through JNI on Android and the JVM, cinterop on iOS),
   take the key as a byte array and zero it afterwards. The key never
   becomes a `String`, is never cached, never logged, never crosses a
   suspension point the UI owns. The password is different: Compose text
   input holds it as `String` state that cannot be zeroed, so the rule for it
   is scoped to what the adapter controls. It is converted to bytes at the
   port boundary, the bytes are zeroed after the KDF, and it is never
   retained, logged or cached beyond the field's transient state, with the
   field under rule 8's screen protections. Garbage-collected runtimes can
   copy memory; short exposure is the real mitigation, so the sign call is
   one call, not a session.
8. **The ciphertext never leaves the device through a backup or a transfer.**
   Android: `android:allowBackup="false"` AND `android:dataExtractionRules`
   with both a `<cloud-backup>` and a `<device-transfer>` exclusion for the
   key store's files — on Android 12 and higher `allowBackup="false"` alone
   still lets a device-to-device migration copy them — plus
   `fullBackupContent` for the API 29/30 path. The host's manifest has all
   three (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`), excluding
   the whole app's data. iOS: the
   this-device-only keychain classes, which neither sync nor migrate, and the
   blob in a file marked excluded from backup. Screens that show a seed or
   take the password: `FLAG_SECURE` on Android; on iOS, where no API blocks a
   screenshot, the window is covered when the app resigns active so the app
   switcher's snapshot holds nothing, and `UIScreen.isCaptured` plus the
   screenshot notification raise a warning; on desktop nothing comparable
   exists and the seed screen says so. The debug Android app alone may lift the
   capture block for a screen recording (owner's request, 2026-10-21): a
   switch in Settings, held in memory and off at every start, under which
   those screens say capture cannot be blocked, as on desktop. The release
   app has no such switch.
9. **Desktop stands on the password path alone where no hardware exists**
   (the owner's ruling of 2026-10-15, CLAUDE.md *Trust*).
   macOS on Apple silicon or T2 uses the Secure Enclave as iOS does, signed
   or not (the owner's decision of 2026-10-16); Windows
   wraps `S_hw` with a TPM key through CNG; Linux uses a TPM where present.
   That TPM path is not built yet: until it is, Windows and Linux stand on
   the password alone, a TPM present or not, and the app tells the user it
   does not use the TPM yet (the owner's ruling of 2026-10-16).
   Without hardware, `S_hw` is absent, the blob is brute-forceable offline,
   and the KDF cost goes up to compensate. The adapter reports which case the
   user is in.

### Where it lives in the architecture

Per docs/architecture.md: the key store is a data source, so the adapter is
`eth.serotine.data.keys`, with `expect`/`actual` for the platform seam in the
smallest source set that needs it. It implements small ports in `:domain`,
named for one concern each: a key-store port for the lifecycle (create,
import a mnemonic, enable or disable the biometric path, delete) and a signer
port for one thing, a signing request in and a signature out. `:ui` sees use
cases, never a key, as CLAUDE.md's *Trust* section already requires. A port
that can fail (locked, biometric invalidated, hardware missing) says so in
its KDoc and returns it as a result the UI shows as state.

**What exists today**: the key-store port
`KeyStore` and the signer port `Signer` in `:domain`, both implemented by
`EnvelopeKeyStore` in `eth.serotine.data.keys`.

- **Create.** `newMnemonic()` makes a 12-word BIP-39 mnemonic from 128 bits of
  the platform's secure randomness (`EnvelopeCrypto.randomBytes`), zeroing the
  entropy. Nothing is stored: the words travel to Create account's screen under
  the mnemonic exception, are shown there alone, three of them are asked back,
  and they are stored by the import below.
- **Sign.** `sign(account, digest, unlock)` opens the account's envelope
  (`<address>.seed`), with the password and the hardware secret
  (`Unlock.WithPassword`) or with the DEK the biometric path unwraps
  (`Unlock.WithBiometrics`, below), derives the key of the account the envelope
  names (its index on the BIP-44 path, the first for an imported phrase) and
  refuses a key that does not derive the account it is filed under. It signs the
  32-byte digest as given, never hashed again, with libsecp256k1 (a low s, RFC
  6979's nonce) and finds the recovery id by recovering the public key. The seed, the key and the password's bytes are
  zeroed before it returns (rule 7); a wrong password, a missing key and an
  envelope this device cannot open are results, never exceptions. Send
  (docs/wallet-ux.md, screens 7 to 9) is its first consumer: the review takes
  the password under rule 8's protections, or offers the biometric prompt where
  the path is on, and the signer signs the quoted transaction's EIP-1559 digest.
  A Safe transaction's approval or execution is such a transaction too.
- **What a Safe owner signs** (`SignSafeTx`; docs/wallet-ux.md, *Co-signing a
  Safe transaction*). The same `sign(owner, digest, unlock)`, with the Safe
  transaction's EIP-712 hash as the digest: computed here from the raw
  `SafeTx` fields, the Safe's address and the chain id (the domain of Safe
  1.3.0 on, the only versions the app recognises), and signed as it is, with
  no `eth_sign` prefix. The signature travels as `r ‖ s ‖ v`, `v` = 27 + the
  recovery id, which is what the Safe's `checkSignatures` and an EIP-712
  signer elsewhere produce for that hash. Before anything signs:
  - the owner is a current owner of the Safe, as its verified storage lists
    them, and the nonce is not one the Safe has passed;
  - a delegate call is refused: the Safe would run the target's code as
    itself, and no preview can say what that code does;
  - so are gas and refund terms (`safeTxGas`, `baseGas`, `gasPrice`,
    `gasToken`, `refundReceiver` other than zero): a refund can pay the Safe's
    funds to whoever it names, bounded only by what the Safe holds, and a
    `safeTxGas` lets a failing call use up the nonce;
  - the key store's signature is recovered (`SignatureRecovery`, libsecp256k1)
    and kept only when it recovers to the owner, as a co-signer's is.

  Nothing about what is signed comes from the request's sender: the screen
  shows what the fields decode to (`SafeTxAction`), and the hash is this
  device's own. That is the independent second view of *What key storage does
  not do* below; it is still the app's view, and for a Safe holding value not
  the sole check. An owner may approve the hash on chain instead
  (`approveHash`, an ordinary transaction from its account), which needs no
  signature to travel at all.
- **Biometric unlock** (rules 3 to 5), the user's opt-in in Settings → Security,
  on Android:
  - `HW_bio` is `AndroidBiometricKey`: an RSA-2048 pair in the Keystore,
    StrongBox first and the TEE otherwise. Its private half decrypts only after
    a per-use strong biometric (`setUserAuthenticationParameters(0,
    AUTH_BIOMETRIC_STRONG)`, the `-1` duration on API 29), only on an unlocked
    device (`setUnlockedDeviceRequired`), and it is invalidated when the enrolled
    biometrics change. A key the Keystore does not keep in secure hardware is
    deleted, and the path is not offered (rule 5).
  - `enableBiometrics(password, confirmation)` opens each envelope with the
    wallet password to reach its DEK, makes the key anew and files each DEK
    wrapped by its public half (RSA-OAEP, SHA-256 with MGF1-SHA-1) as
    `<address>.bio`. Wrapping needs no biometric, so the path is then proven
    once: one wrap opened through the prompt must give back its DEK. Only then
    is it on; a dismissed or failed proof turns it off again. The key and the
    wraps are made as one step and undone together; a crash in between leaves
    wraps the next enable or disable sweeps away. A key imported while the path
    is on is wrapped too. The password path is untouched.
  - What the device cannot offer is said before the password is asked: no
    strong sensor, nothing enrolled, only weak biometrics, or a key store
    outside secure hardware (the password path's key tells, rule 5). While the
    biometric key exists the path reads as on, usable right now or not, so
    that it can always be turned off.
  - `sign(…, Unlock.WithBiometrics(reason))` decrypts the wrap through the
    platform's `BiometricPrompt` with the cipher as its `CryptoObject`, so only a
    match the hardware accepts unlocks it. The prompt is the host's
    (`PlatformBiometricPrompter`, on the activity in front), says the caller's
    reason, and offers "Use the password" as the way out.
  - `disableBiometrics()` deletes the key, then the wraps (rule 4). The last key
    deleted takes the path with it, as it takes the wallet password.
  - A dismissed or timed-out prompt, a key invalidated by re-enrolment, a
    locked-out or busy sensor and an account stored before the path was on are
    results the signing screen turns into "use the password".
  - The key store's calls take turns, `describe()` included: the biometric
    key's check opens a Keystore operation, which must not compete with the one
    a pending prompt holds (StrongBox has few).
  - Windows and Linux have no biometric path yet, and their Security screen
    says so. A Mac with Touch ID has one, below.
- **Biometric unlock on iOS** works the same way, with `IosBiometricKey` as
  `HW_bio`:
  - a P-256 key made inside the Secure Enclave under
    `[.privateKeyUsage, .biometryCurrentSet]` and
    `WhenPasscodeSetThisDeviceOnly` (rule 3), with no passcode fallback. It is
    kept only as the enclave's encrypted handle, in `biometric-key.json` next
    to the envelopes, so deleting that file deletes the key (rule 4);
  - beside the handle, the enrolled set's fingerprint
    (`evaluatedPolicyDomainState`) at creation: a set that changed since, or
    none left, reads as invalidated, as the enclave enforces too;
  - each DEK wrapped by ECIES to the public half (X9.63 KDF, AES-GCM), which
    needs no biometric, as `<address>.bio`;
  - `sign(…, Unlock.WithBiometrics(reason))` shows the system's Face ID or
    Touch ID prompt (`LAContext`, "Use the password" as its way out) and
    hands the context the user passed to the enclave for that one
    decryption;
  - the simulator has no enclave, so the path is not offered there (rule 5);
    where it is not offered, the Security screen says why and how to get it:
    no face or finger enrolled, no passcode, Face ID turned off for Serotine,
    or biometrics locked out until the passcode is entered.
- **Biometric unlock on a Mac** with Touch ID works as on iOS, with
  `MacBiometricKey` as `HW_bio`:
  - the same key: P-256, made inside the Secure Enclave under
    `[.privateKeyUsage, .biometryCurrentSet]` and
    `WhenPasscodeSetThisDeviceOnly`, with no password fallback, kept only as
    the enclave's handle in `biometric-key.json` beside the enrolled set's
    fingerprint, and each DEK wrapped to it by ECIES as `<address>.bio`.
    The calls are Security.framework's C API through JNA, as for
    `MacSecureEnclaveSecret`; the key is not permanent, so it needs no
    keychain entitlement, and the app signed or not makes it the same way;
  - `sign(…, Unlock.WithBiometrics(reason))` has the enclave decrypt through
    an `LAContext` that carries the reason (`MacLocalAuthentication`,
    messages sent through the Objective-C runtime, since LocalAuthentication
    has no C API): the system shows its Touch ID prompt for that use, and the
    decryption waits for it. On macOS 15 the prompt's one way out is Cancel,
    with no password button, and the signing screen then offers the wallet
    password; the context still names "Use the password" as its fallback,
    whose press, where macOS shows it, reads as a dismissal. A prompt that does
    not pass fails the decryption with LocalAuthentication's error, at the
    top of the error or underneath it, which reads as on iOS (Security's own
    "cancelled by the user" reads as a dismissal too); a cancelled caller
    invalidates the context, which dismisses the prompt;
  - the handle carries no caller identity, as `MacSecureEnclaveSecret`'s does
    not: another program running as the same user could use the key, but
    only with the user's finger, in a prompt that names that program;
  - it works whichever factor the password path stands on, the enclave or the
    password alone;
  - where it is not offered, the Security screen says why: no sensor it can
    use (it says no more than that, whether the Mac has no Touch ID, its
    Touch ID keyboard is not paired or not connected, or its lid is closed;
    a Mac has no setting that turns Touch ID off for one app), no finger
    enrolled, or Touch ID locked out until the login password is entered.

- **Import.** `importMnemonic(mnemonic, password)` checks the restore
  screen's words against the BIP-39 English wordlist and their checksum. It
  derives the first account, `m/44'/60'/0'/0/0` with the empty passphrase, on
  libsecp256k1 (ACINQ's secp256k1-kmp). The use case stores the key and then
  adds the account as one step that a cancelled caller cannot cut in half.
- **Further accounts of a phrase.** `deriveAccount(from, password, held)` adds
  a further account of the phrase an account held here comes from, without the
  words: the envelope holds the BIP-39 seed for this (owner ruling,
  2026-10-15). It opens `from`'s envelope with the wallet password, checks
  that its seed derives `from`, and takes `m/44'/60'/0'/0/i`, `i` the lowest
  index whose address is not in `held`: the use case passes the addresses held
  on the network the account is added on, so a phrase's accounts there are the
  ones other wallets list for it, in their order, and one removed comes back
  first. The seed is sealed again in an envelope of its own, under that
  account's address, as an import seals one (the device's cost, stepping down
  when the memory is short, joining the biometric path while it is on). An
  envelope already stored under that address (its account never reached the
  list) is kept when it opens under the password, and sealed again otherwise.
  Each envelope names its index (`index`, left out for the first, so envelopes
  stored before are unchanged). The index is not bound as additional data:
  signing derives the key at that index and refuses one that is not the
  account the envelope is filed under, so an index edited on disk signs
  nothing. The use case (`DeriveAccount`) adds the account on the network of
  the one it comes from, as one step under the app's `WalletChanges`; Add on
  another network adds it elsewhere.
- **Storage.** The BIP-39 seed goes into the envelope above, one file per
  account (`<address>.seed`, JSON) in a directory of the app's own.
  - Writes go through a temporary file readable by this user alone, an
    fsync, an atomic move and, where the platform allows, an fsync of the
    directory. A temporary file a crash left behind is deleted on the next
    import.
  - Android keeps the directory out of backups and device transfers (rule 8).
    On macOS the desktop host excludes it from Time Machine. On Linux it
    marks it for GNOME's Backups (Déjà Dup), whose default takes the whole
    home directory, with a `.deja-dup-ignore` file. Windows' File History
    leaves application data out unless the user adds it. Other backup tools
    the user configures may still take it.
  - A file that cannot be read, that names another kind of hardware secret
    than this device's, that has parameters outside the floor and ceiling, or
    that is not under its own address's name, is reported and passed over,
    never trusted. (The tag names the kind, not the device: another phone's
    envelope passes this check and then fails as a wrong password.) A
    damaged envelope of the account being imported is sealed again.
- **Deletion.** `delete(address)` deletes the account's envelope when the
  account is removed from the wallet; the use case removes the account from
  the list first, so a failure in between leaves a key without an account
  (importing the phrase again finds it), never an account whose key is gone.
  It asks for no password: it takes nothing from someone who could clear the
  app's data anyway. It is a plain file delete, so the ciphertext may stay on
  the storage until the blocks are reused. With the last envelope the wallet
  password goes too, and the next import sets a new one. That deleting a key
  needs no password is the owner's ruling (2026-10-18, #17, recorded in
  CLAUDE.md's *Trust*): the app asks the user to confirm the removal first
  (`RemoveAccountDialog`), and nothing more.
  - What a deletion that does not finish leaves is the owner's ruling
    (2026-10-18, #18), decided and not built yet (#73). A key whose deletion
    the user confirmed but which did not finish is deleted again at the next
    start: a pending-deletion marker is written before the list entry is
    removed. Keys left over from a damaged account list are never deleted
    automatically: the user is offered to restore their accounts with the
    wallet password. A "start over" reset comes later.
- **The password.**
  - The first import sets the wallet password: at least eight characters,
    refused by the adapter otherwise. Every later import must give the same
    one, which is checked by unwrapping a stored envelope's DEK, never by a
    comparison. The account's own envelope is tried first, at most three
    are tried in all, and the seed stays sealed during the check. A GCM tag cannot tell a wrong password
    from a damaged file, so one envelope's refusal does not end the check
    while another may open. The password is wrong only when none opens and
    one refused it, and then nothing is sealed.
  - It is stretched from its NFKC form, so the same password typed on
    keyboards that compose characters differently opens the same envelopes.
- **Argon2id** is libsodium's `crypto_pwhash`, through a JNA mapping of its
  own (`Sodium`) over native buffers that are zeroed after the call. The
  bindings' String API would truncate a non-ASCII password and could not zero
  it. The mapping is checked against libsodium's known answers.
- **Costs.**
  - When every guess needs secure hardware (Android's Keystore HMAC): 64 MiB
    and three passes.
  - Otherwise a password-only tier (`PasswordOnlyTier`). That covers a
    software key store, as on an emulator, whose key a copy of the device's
    storage yields. It covers the Mac's Secure Enclave, whose secret is
    unwrapped once and is then the same for every guess. And it covers the
    password alone. Each must hold up offline like a password-only envelope.
  - The tiers, sized by memory (owner ruling, 2026-10-18, issue #36), each
    about the same wall time: 256 MiB × 4 passes, 128 MiB × 8, 64 MiB × 16.
    On Android the host picks the device's tier from
    `ActivityManager.MemoryInfo.totalMem` and `isLowRamDevice`: about 4 GB or
    more and not low-RAM is 256 MiB, about 2 GB or more is 128 MiB, less is
    64 MiB (`totalMem` leaves out what the kernel reserves, so the thresholds
    sit at 3.4 and 1.7 GB). The desktop starts at 256 MiB.
  - Sealing starts at the device's tier. When libsodium cannot have the
    memory (its -1), it tries the next lighter tier, never below 64 MiB;
    short at that too, nothing is stored and the user reads "Not enough free
    memory to protect the key: close other apps and try again." The password
    is stretched before anything else is made, so a failed seal leaves
    nothing behind. Opening an envelope (signing, the password check of a
    later import, turning biometric unlock on) short of memory says so for
    that step ("Not enough free memory to check the password: …" at an
    import), never that the key store does not work.
  - A device that seals below 256 MiB says so with its protection, on the
    seed screens and in Security: "This device has little memory, so the
    password protection uses less memory and more passes." A seal that
    stepped down below the device's own tier says so when the key is stored
    (`KeyImport.Stored.steppedDown`): "There was not enough free memory just
    now, so the password protection of this key uses less memory and more
    passes."
  - An envelope keeps the tier it was sealed at: it is never sealed again
    at a costlier one later, even once the memory is there.
  - The tiers are chosen for about the same time, not measured on devices
    yet; measuring them is issue #75.
  - The hardware path's 64 MiB × 3 is no tier: it neither steps down nor
    changes, and envelopes already stored keep the parameters they were
    sealed with.
  - Opening checks the 64 MiB, two-pass floor and a ceiling that takes every
    tier: at most 256 MiB, at most 16 passes, and memory × passes at most
    256 MiB × 4, so the most passes come only with the least memory.
- **AES-256-GCM** comes from the JDK, and on iOS from libsodium (its
  `crypto_aead_aes256gcm`, on the ARMv8 crypto extensions), whose output has
  the JDK's shape: the ciphertext, then the 16-byte tag.
- **The hardware secret.** On Android it is `AndroidKeystoreSecret`, an
  HMAC-SHA256 key in StrongBox, or in the TEE when StrongBox is missing or
  failing. The Keystore reports where the key lives (rule 5), and the screen
  says which case applies: hardware, or a software key store on an emulator.
  A key store that fails is shown as such, and nothing can be added.
- **A Mac with a Secure Enclave** uses `MacSecureEnclaveSecret`, built as the
  iOS design above (owner's decision, 2026-10-16).
  - The key and the secret: `HW_pw` is a P-256 key made in the enclave, not
    permanent, with no user-authentication requirement. `S_hw` is 32 random
    bytes encrypted to it with ECIES.
  - The state file: `secure-enclave.json` beside the envelopes holds the key
    as the enclave's own encrypted handle, plus the encrypted `S_hw`.
    Neither is any use off this Mac.
  - The calls are Security.framework's C API through JNA, with no native
    build step. The handle is the key's token object id (`"toid"`), the same
    blob CryptoKit's `dataRepresentation` is, though the SDK does not name
    the attribute.
  - The handle carries no caller identity, so it is bound to the Mac but not
    to the app. Any program running as the same user can read the state file
    and use the enclave with it, with no permission prompt. Signing alone
    does not change that. Binding the key to the app means keeping it in the
    app's keychain access group, which takes a signed app with a
    provisioning profile, and is a different design.
  - The state file is made once, for a wallet with no envelope on the
    enclave yet, and never replaced. If it is lost while such envelopes
    exist, the key store reports itself unavailable instead of sealing under
    a new secret. A broken enclave or a damaged state shows as unavailable
    before any attempt, since describing the key store unwraps the secret
    once.
  - The tests run against the real enclave on a Mac and are skipped
    elsewhere.
- **The desktop's factor sticks to the wallet** (`desktopHardwareSecret`).
  - A wallet with enclave state or enclave envelopes keeps using the
    enclave. One that fails later makes the key store unavailable, never
    quietly password-only.
  - A wallet begun on the password alone stays so.
  - Only a wallet without envelopes asks whether the enclave works.
  - Macs without an enclave, Windows and Linux stand on the password alone.
    Rule 9's TPM path for Windows and Linux is not built. Until it is, those
    computers are password-only, and the seed screen says that Serotine does
    not use the computer's TPM yet (`KeyProtection.PASSWORD_ONLY_TPM_NOT_USED`,
    the owner's ruling of 2026-10-16).
- **One Serotine runs per user** on the desktop.
  - The host holds an exclusive lock on a file in its data directory, so a
    second instance does not write the account list or the envelopes. It
    says so and ends.
  - The OS drops the lock when the holder dies. A user who deletes the lock
    file while Serotine runs defeats the guard.
  - On Windows the data directory is `%LOCALAPPDATA%\eth.serotine`, apart
    from the directory the per-user installer owns.
- **A lost hardware key is said, not guessed around.** The key store asks the
  hardware secret whether its key exists before anything could create one.
  When envelopes sealed with that kind of secret exist but the key does not,
  the key store reports itself unavailable instead of making a new key, whose
  secret would only answer "wrong password"; signing with the password answers
  unavailable for the same reason, before the envelope is touched. Signing with
  a biometric does not need that key (its own key unwraps the DEK), so it still
  works. This covers a Keystore key lost in an OS upgrade or a cleared key
  store, and a deleted enclave state. The way back is the reset and recovery
  flows.
- **iOS** gets its envelope with the iOS app (owner's decision, 2026-10-16),
  built as the Mac's: `IosSecureEnclaveSecret` is `MacSecureEnclaveSecret`'s
  twin, the same key, ECIES-wrapped `S_hw` and `secure-enclave.json` state
  file, through Kotlin/Native's Security.framework bindings.
  - The enclave key is made with the access control CryptoKit gives one by
    default: usable while the device is unlocked, on this device only, for
    private-key operations. The app's sandbox keeps other apps from the
    state file, which the Mac's design cannot.
  - Argon2id and AES-256-GCM are libsodium's, from the bindings'
    `libsodium.a`. Every call goes through Serotine's own cinterop
    declarations (`sodium.def`), not the bindings': theirs are published per
    target only, which the shared iOS code cannot see, and theirs of
    `crypto_pwhash` takes a `const char *`, which Kotlin/Native makes a
    String; Serotine's takes the password as bytes.
  - The key directory is excluded from iCloud and computer backups.
  - The factor (`iosHardwareSecret`): on an iPhone it is always the
    enclave, chosen without asking whether it works, so a locked device
    decides nothing; an enclave that fails makes sealing fail and the key
    store unavailable. The simulator has no enclave, and only a simulator
    build (a source set of its own, not a runtime check) lets a wallet stand
    on the password alone, for development; its factor sticks to the wallet
    as on the desktop.
  - Biometric unlock: Face ID or Touch ID, see *Biometric unlock on iOS*
    above. The simulator has none.
- **What never leaves a call.**
  - The seed, the keys and the password's bytes exist only inside one call,
    in byte arrays and native buffers this code zeroes before it returns.
  - The JDK's AES keeps its own copy of a key until the garbage collector
    takes it: the GC caveat of rule 7.
  - The words and the password reach the adapter along the mnemonic
    exception's one path. The restore and Create account screens'
    ViewModels drop both from their state when the screen is left: an
    `ON_STOP` that is not a configuration change, so the app going to the
    background, the screen turning off and a pop, but not a rotation, a fold,
    a resize or a theme or font change, across which the words stay in the
    ViewModel's memory and nowhere else (owner ruling, 2026-10-18, #39;
    `OnStopUnlessConfigurationChange`, Android's
    `Activity.isChangingConfigurations`); an import already running
    finishes without them.
  - Create account's phrase is not stored until its check and password are
    done, so leaving the screen before then voids it: the screen tells the
    user to cross out what they wrote, and the next attempt gets a new
    phrase. A phrase the key adapter returns after the screen was left is
    dropped the same way.
- **Screen protections.** Every screen that takes the wallet password or
  shows the words carries rule 8's protections through
  `protectFromScreenCapture()` in `eth.serotine.ui.platform`: the restore and
  Create account screens, Security (21) while it asks for the password, and,
  through the unlock they share (`UnlockFields`), Send's review (8), Send
  from a Safe's review (16), the Safe transaction's Sign, Approve on chain
  and Execute steps (17) and Create Safe's review (15). That is
  `FLAG_SECURE` and an autofill exclusion on Android. On desktop and on iOS,
  where no capture can be blocked, the screen says so. iOS also covers the
  window while the app is not active, so the app switcher's snapshot holds
  nothing, and warns (`ScreenCaptureWarningDialog`) when a screenshot is
  taken and while the screen is recorded, mirrored or shared. The debug
  Android app's Settings → *Allow screen recording*
  (`ScreenCaptureDebugSwitch`, which only a debuggable Android host builds)
  drops `FLAG_SECURE` and keeps the autofill exclusion, and the screens then
  say that capture is not blocked; it is off again when the process
  restarts. Create account also keeps the display on while its phrase is shown and checked
  (`KeepScreenOn()`; Android's `keepScreenOn`, iOS's idle timer), because a
  display that times out stops the screen, and that stop is a leaving
  (owner ruling, 2026-10-18, #39).
- **Not yet.**
  - The biometric opt-in on the seed screens at the first key (Settings →
    Security is where it is turned on today).
  - Changing the wallet password, which re-wraps every envelope's DEK as one
    step a crash cannot leave half done.
  - A way back when no envelope opens any more (a lost Keystore key, a
    forgotten password) comes with the wallet's reset and recovery flows.
  - The ruling on a deletion that does not finish (#18, *Deletion* above),
    not built yet (#73): the pending-deletion marker and the deletion again
    at the next start, the offer to restore the accounts of leftover keys
    with the wallet password, and later a "start over" reset.

## The outbox's seal key (lenient mode)

(Owner rulings, 2026-11-06, on #258, accepting docs/lenient-mode.md,
decision 5.)

In lenient mode a transaction may be signed before the light client has
caught up, and waits in the outbox until a check at a fresh head sends it.
Its signed bytes are not key material: they sign nothing else, and they move
only what the user signed, once. They are not public data either, since
nothing has broadcast them, and whoever has them can. So they are sealed.

- **What it seals.** `outbox.json`, the outbox's file, and nothing else. It
  never touches an envelope, and no envelope's key touches it. The file
  holds the queued transactions' signed bytes and, beside them, the Safe
  signatures held in lenient mode until their check (docs/lenient-mode.md,
  *Safe transactions in lenient mode*): an owner's signature of a Safe
  transaction nobody has checked against the chain yet, which no other
  owner may see before it is. They are sealed, lost and kept in memory
  only exactly as the queued bytes are.
- **A hardware key of its own** (rule 4's spirit: each use its own key).
  Android: a Keystore key that `KeyInfo` reports as hardware-backed (rule
  5). iOS and a Mac with a Secure Enclave: an enclave key, made as the
  envelope's hardware factor is (`HardwareSecret`). The outbox makes its own
  key wherever the hardware is there, whether or not the wallet's envelopes
  use it.
- **No authorization.** The key needs no password and no biometric prompt,
  so the outbox sends by itself once the check passes. It closes the copied
  file only: code running inside the app can still open it, and on a Mac so
  can any program of the same user (*A Mac with a Secure Enclave*).
- **No hardware key, no file.** Where there is no hardware key to seal
  with, the queued bytes stay in memory only, and a restart asks for the
  signature again: an Android device whose Keystore is software only (an
  emulator, some devices), Windows and Linux (a TPM present or not), a Mac
  without a Secure Enclave, the iOS simulator. A software key would be in
  the same copy of the device's storage as the file, so it would seal
  nothing.
- **Kept out of backups and transfers where the platform allows**, as the
  envelopes are (rule 8). Android's exclusions cover the whole app's data.
  iOS marks the file excluded from backup. A Mac keeps it beside the key
  directory, excluded from Time Machine; another backup tool may still take
  a copy, and that copy opens on no other Mac.
- **A lost key is said, not guessed around.** When the file is there and
  its key is not (the key store reset, or a copy restored on another
  device), the queued transaction is said as lost, the file is deleted, and
  the user is asked to sign again. So it is on a Mac whose enclave works but
  does not open the outbox's enclave state (copied from another Mac by a
  migration or a restore, or damaged), or that has no enclave left to open
  it: the state is deleted with the file. A new key is made at the
  outbox's next use: on Android when it is next read, on a Mac and iOS when
  the next transaction is queued. A key store that only fails this time is
  no lost key: the file is left as it is, unreadable, and asked about again
  a minute later.

## A Safe owned by a hardware P-256 key

Safe has supported secp256r1 owners since Safe 1.3.0 through its passkey
signer contracts (the `passkey` module of safe-modules): a signer proxy per
key, forwarding to a singleton that verifies a WebAuthn-formatted signature
over `authenticatorData || sha256(clientDataJSON)`. The singleton tries the
secp256r1 precompile at `0x100` first and falls back to a Solidity verifier
when the call returns empty, so one signer works on every chain Safe is
deployed on and gets cheap by itself when a chain gains the precompile.

Recommendations:

- **Use a raw Secure Enclave / StrongBox key, not a platform passkey**, for
  the hardware-bound tier. A raw key can sign an arbitrary digest
  (`.ecdsaSignatureDigestX962SHA256`; `NONEwithECDSA` on Android), so the app
  builds the WebAuthn envelope around it itself and the key stays
  device-bound instead of synced. Verify the constructed envelope against the
  signer contract's checks before shipping.
- **The owner key's own policy.** It is created with `.privateKeyUsage`, the
  passcode-set this-device-only accessibility class and, chosen by the user
  at creation, either `.biometryCurrentSet` alone (a biometric re-enrollment
  then invalidates it, which is what the recovery owner is for) or current
  biometrics or the device passcode. On Android the same two choices go
  through `setUserAuthenticationParameters` with per-use authorization and
  `BIOMETRIC_STRONG`, optionally with `DEVICE_CREDENTIAL`, plus
  `setUnlockedDeviceRequired(true)`. This tier has no password factor, so
  the envelope's biometric opt-in and its "disabling deletes the key" rule do
  not apply here: deleting this key removes the account's device owner, and
  the UI treats it as exactly that.
- **Expect gas to vary by chain.** With the precompile a verification costs
  thousands of gas; the fallback costs roughly 200k to 400k.
- **Such a key is never the sole owner of a Safe holding value.** See the
  next section.

### Where the secp256r1 precompile exists (checked October 2026)

| chain | precompile at `0x100` | since | via |
|---|---|---|---|
| Ethereum mainnet | yes | 2025-12-03 | Fusaka, EIP-7951 |
| Sepolia | yes | 2025-10-14 | Fusaka |
| Hoodi | yes | 2025-10-28 | Fusaka |
| Holesky | deprecated | | stopped finalizing 2025-10-27 |
| Gnosis Chain | yes | 2026-04-14 | Gnosis Fusaka |
| Chiado | assumed, not verified | | forks ahead of Gnosis mainnet as a rule |
| OP Mainnet, Base, other OP Stack chains | yes | 2024-07-10 | Fjord, RIP-7212; repriced to EIP-7951's 6900 gas in Azul |
| Arbitrum One and Nova | yes | 2024-09 | ArbOS 31 "Bianca" |
| Polygon PoS | yes | 2024-03 | Napoli |
| zkSync Era | yes | | `p256Verify` system contract at `0x100` |
| Scroll | yes | 2025-04 | Euclid |
| Linea | not verified | | |

EIP-7951 and RIP-7212 share the address and the calldata layout. EIP-7951
tightens input validation (a point-at-infinity check, modular comparison of
`r`) and doubles the gas to 6900; the L2s that started on RIP-7212 are
converging on it. A signer contract does not care which one it hits.

## Backup and recovery

A key generated inside the Secure Enclave or StrongBox never exists outside
the chip. Device backups, iCloud Keychain and Android device-to-device
transfer all skip it; a factory reset or wipe destroys it; a biometric
re-enrollment makes it unusable under the current-set flags above. **Hardware
isolation and backup are mutually exclusive on these platforms**: a hardware
key is a device credential, not a secret the user can own.

That is fine for a Safe, because a Safe needs owner rotation, not key backup:
losing the device is recovered by another owner calling `swapOwner` to
install the new device's key. So the account setup, not an extra step, is
one of:

- **Device key plus recovery key, threshold 1.** The hardware key signs daily.
  A secp256k1 owner from a mnemonic the user wrote down exists for recovery
  only and is, ideally, never stored on the phone at all.
- **Device key plus a recovery module.** A Safe recovery module lets a
  designated recoverer rotate owners after a delay, so the user keeps a veto
  window.
- **Two devices, threshold 1.** A second phone, the desktop app, or a
  hardware wallet as co-owner; either one rotates the other out.
- **Threshold 2 only with a third owner or a recovery module.** Two owners at
  threshold 2 is a brick the moment one device is lost: `swapOwner` is itself
  a Safe transaction that needs threshold signatures, the remaining owner can
  never reach two, and the lost device's key has no backup by design. So
  threshold 2 removes the single-device risk only as 2-of-3, or as 2-of-2
  with a recovery module alongside it.
- **Every n-of-n above one only with a recovery module** (owner ruling,
  2026-10-18, #54). The same reasoning holds at any threshold equal to the
  owner count: with one owner lost the rest can never reach it, not even to
  swap the lost one out. So 2 of 2, 3 of 3 and every n of n with n > 1 are
  created only alongside a recovery module (`SafePlanRefusal.NeedsRecoveryModule`
  otherwise). The ruling covers changing a Safe too: a threshold change, an
  owner removed or an owner added that leaves an n-of-n above one is not
  signed, approved or executed here, checked against the Safe's verified
  owners and threshold (`SafeTxRefusal.NeedsRecoveryModule`); no recovery
  module is recognised yet, so every such change is refused. Which recovery
  modules come, and how, stays open (#54, #72).

For an **EOA** none of this exists: the mnemonic is the only backup, which is
the other reason an EOA key is a secp256k1 key from a mnemonic in the
envelope. The mnemonic is shown once at creation, written down by the user,
and re-entered only to restore on a new device.

Those two screens are an exception to the Trust rule that key material never
leaves the key-store adapter: the one entry, today, in the closed list of
exceptions in CLAUDE.md's *Trust* section (owner ruling, 2026-10-15). Its
conditions, which a reviewer holds those two flows to:

- **One type.** The words travel only as a dedicated mnemonic type whose
  `toString()` is redacted, so every place they flow is a place that type
  appears, and one search finds the whole path.
- **One path.** At creation, from the adapter through a use case and the
  ViewModel into a dedicated seed UiState; at restore, from the seed UiState
  through the ViewModel and a use case into the adapter. No other port, use
  case, ViewModel or screen sees the type.
- **Transient.** The seed UiState exists only while its screen is shown and
  the ViewModel clears it on leaving. Nothing on the path stores, logs,
  caches, snapshots or serializes the words; the input field's own text
  state is under the same limits as the password's in rule 7.
- **Protected.** Both screens are under rule 8's screen protections.
- **Narrow.** Everything the adapter derives from the words, the seed and the
  keys, stays under the absolute rule and never comes back up.

Create account uses both legs on one screen: the words go out to its UiState
as at creation and come back to the adapter as at restore. Whether the entry,
worded for two screens, covers that is the owner's open question (issue #40);
the code is built as if it does. A configuration change does not leave a seed
screen, and the app going to the background does (owner ruling, 2026-10-18,
#39): the words stay in the ViewModel's memory across a rotation, never in a
saved state or a Bundle.

Every other screen and flow is held to the rule itself. A new exception is
the owner's ruling, added to that list first.

The **synced-passkey** tier trades hardware isolation for a backup held by
Apple or Google; it is listed above as the owner's open decision.

## What key storage does not do: signing intent

A hardware key gives three things: the key cannot be extracted, it cannot be
used without the device, and a human was present when it was used. It gives
nothing about *what* was signed. The Secure Enclave and StrongBox sign
whatever 32 bytes the app hands them; the biometric prompt proves the user
approved a signing operation, not this transaction with these contents. A
defective or compromised app can show one thing and sign another, and the
hardware cannot tell.

The only on-device fix would be a trusted display owned by the secure
hardware, which hardware wallets with screens have and phones do not:

- **Android Protected Confirmation** was built for exactly this. It shipped on
  Pixel phones only; Google moved to deprecate it in 2024 for lack of
  adoption, and the later partial revert does not make it something to build
  on. Its signed format would not match Safe's signer either.
- **iOS** exposes nothing comparable to third-party apps.

So the deception path is closed *outside* the signing app, in this order of
value:

1. **Policy on chain, where the app cannot touch it.** A Safe transaction
   guard or module enforcing spending limits, recipient allowlists, a delay
   for large or unusual transactions, no `delegatecall`, and owner changes
   only behind the delay. (This wallet itself co-signs a delegate call only
   to the pinned MultiSendCallOnly 1.4.1, each inner call decoded and shown:
   owner ruling, 2026-10-18, #57. Not built yet, so it refuses every one.) A defective app can then do only what the policy
   permits, and anything beyond it enters a window where another device can
   veto. This exists because a Safe is a contract; for an EOA the equivalent
   is an EIP-7702 delegation to such an account, and the delegation itself is
   still signed by the secp256k1 key.
2. **An independent second view.** A threshold-2 Safe whose second signer runs
   on a different device and decodes the transaction itself, never from the
   description the first device sent. The request reaches it as a QR code or
   as text with no off-chain service in between (owner ruling, 2026-10-16,
   under *Data sources* in CLAUDE.md): it carries the raw `SafeTx` fields,
   which the co-signer decodes, hashes and checks against the Safe's
   verified state. A hardware wallet co-signer does this with its own screen.
   Myotis can be that independent decoder for a desktop confirmation, as long
   as it never trusts the phone's description of the transaction.
3. **A smaller chance of the defect.** The signing path is one small,
   separately reviewed module with no feature flags or remote configuration
   on it. No dApp browser runs in the signing process; requests arrive
   through a connection protocol that runs outside it. That protocol is
   WalletConnect v2 (owner rulings, 2026-11-03, #22; docs/walletconnect.md).
   Its relay is an off-chain service under *Data sources* in CLAUDE.md, what
   arrives through it is untrusted input, a dApp may call a closed list of
   methods, and `eth_sign`, which would sign a digest the dApp chose, is
   refused in every case. Reproducible builds let others verify the binary. Calldata
   decoding and transaction simulation protect against malicious dApps, which
   is the common case, but not against the app itself.

For a Safe holding value, the app's own preview is therefore never the sole
check. Hardware keys close the theft path, Safe policies close most of the
deception path, and nothing on a phone closes it completely.

## Options considered and not taken, or deferred

- **External signers** (Ledger over Bluetooth, Keystone over QR, Keycard and
  Tangem over NFC) keep the secp256k1 key off the phone entirely and bring a
  trusted screen. For a Safe wallet this is the natural top tier, and a
  natural co-owner in the shapes above. Deferred: the owner decides when
  (#42). A software wallet that holds an owner key, reached over
  WalletConnect, is a tier of its own (*The account tiers*).
- **Two-party threshold ECDSA with Myotis**, where neither the device share
  nor the server share can sign alone. Real protocol complexity, an
  availability dependency, and the device share still needs the envelope.
  Deferred; the owner's decision.
- **Deriving the secp256k1 key on demand from a hardware key** (an HMAC key on
  Android, a fixed-peer ECDH on iOS) so that nothing is stored. The plaintext
  still exists in the app at signing time, the password cannot be bound in,
  and the key becomes unrecoverable with the device while a mnemonic backup
  is needed anyway. No gain over the envelope; rejected.
- **Samsung Blockchain Keystore.** Galaxy-only; rejected.

## Sources

- [Android Keystore system](https://developer.android.com/privacy-and-security/keystore?hl=en)
- [Android: backup leaks, allowBackup and device-to-device transfer](https://developer.android.com/topic/security/risks/backup-leaks)
- [Apple CryptoKit SecureEnclave](https://developer.apple.com/documentation/cryptokit/secureenclave.md)
- [Apple SecAccessControl.h, the application-password flag](https://opensource.apple.com/source/Security/Security-58286.51.6/keychain/SecAccessControl.h.auto.html)
- [Apple developer forum: application password with a Secure Enclave key](https://developer.apple.com/forums/thread/118746)
- [Samsung Blockchain Keystore SDK](https://developer.samsung.com/blockchain/keystore)
- [Safe passkeys](https://docs.safe.global/home/passkeys-safe)
- [RIP-7212](https://github.com/ethereum/RIPs/blob/master/RIPS/rip-7212.md) and [EIP-7951](https://eips.ethereum.org/EIPS/eip-7951)
- [Fusaka mainnet announcement](https://blog.ethereum.org/2025/11/06/fusaka-mainnet-announcement)
- [Fusaka testnet dates](https://status.quicknode.com/incidents/05cv4z7mkqns) and [Holesky deprecation, ACDT #59](https://ethereum-magicians.org/t/all-core-devs-testing-acdt-59-oct-27-2025/25914)
- [Gnosis Chain Fusaka upgrade](https://status.quicknode.com/incidents/qzc59nbc51rb)
- [Base Fjord](https://docs.base.org/upgrades/fjord/overview.md) and [Base precompiles, Azul repricing](https://docs.base.org/specifications/base-protocol/execution/precompiles.md)
- [OP Stack Fjord](https://docs.optimism.io/op-stack/protocol/hardforks/fjord.md)
- [ArbOS 31 Bianca](https://docs.arbitrum.io/run-arbitrum-node/arbos-releases/arbos31)
- [zkSync Era precompiles](https://docs.zksync.io/zksync-protocol/differences/pre-compiles)
- [Scroll Euclid](https://www.theblock.co/post/351601/ethereum-layer-2-scroll-euclid-upgrade-improves-network-throughput-security)
- [Android Protected Confirmation: deprecation](https://gerrit.omnirom.org/plugins/gitiles/android_frameworks_base/+/3d9d17788fb713787bda740a335304d1b346e19b) and [its partial revert](https://gerrit.omnirom.org/plugins/gitiles/android_system_security/+/9535b1b44373844b239201b18c535829d6d52eba%5E%21)

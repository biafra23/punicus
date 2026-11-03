# dApp connections (WalletConnect)

How a dApp connects to Serotine and asks it to sign or send for one of its
accounts, and how Serotine uses another wallet (MetaMask, say) as the signer
of a Safe owner. Both go over WalletConnect v2. This page is a design: none of
it is built. Issue #22 tracks it. A dApp-connection PR is held to this page
together with docs/wallet-ux.md (the screens and their rules),
docs/keys-and-signing.md (what is signed, and where keys live) and
docs/embedding-myotis.md (what the engine reads and sends). Where this page
and the rules under `.claude/` (their short form in CLAUDE.md) disagree, the
rules win and this page is fixed.

## The owner's rulings (2026-11-03, #22)

The owner accepted issue #22's recommendations as they stand, and decided its
two open points, the chains and the order.

1. **WalletConnect v2 is the dApp connection protocol.** Its relay is an
   off-chain service, recorded under *Data sources* (*What the relay learns*,
   below). Serotine never takes chain state from it.
2. **The Reown project ID is not committed.** A project on
   dashboard.reown.com under the owner's account. A Gradle property supplies
   it at build time, from a CI secret, with separate IDs for debug and
   release. A build without an ID has WalletConnect switched off and says so.
3. **A new key tier: an owner key in an external wallet.** The key lives in
   the other wallet, and so do its backup and its unlock. Serotine keeps only
   the address and the session. Safe owners first. An EOA whose key is in
   another wallet comes later or never: for an EOA, Serotine would only relay
   to that wallet.
4. **Either executes a Safe transaction once it has its signatures:** the
   external wallet, through `eth_sendTransaction`, or a local EOA, through
   the engine's `sendRawTransactionDetailed`. The external wallet is the
   default when this device holds no key. Inclusion is confirmed from a
   verified receipt either way.
5. **Serotine asks an external wallet for `eth_signTypedData_v4` of the
   `SafeTx` only**, so that the wallet shows the struct it signs.
   `personal_sign` over the `safeTxHash` is not offered: that is blind
   signing.
6. **A dApp may call a closed list of methods:** `eth_sendTransaction`,
   `personal_sign`, `eth_signTypedData_v4`, and `wallet_switchEthereumChain`
   to Sepolia only. Every other method gets an
   unsupported-method error. `eth_sign` is refused in every case: it signs a
   raw digest, which is what phishing asks for.
7. **WalletConnect's optional services stay off:** the Verify API, fetching
   dApp icons, the push (echo) server and Link Mode. The dApp's name and URL
   are shown as "stated by the dApp, not verified".
8. **Session secrets live in an app-private file.** The relay-auth Ed25519
   key and the pairing and session symmetric keys are not account keys: they
   cannot move funds, though whoever holds them can read and inject messages
   on that session. The file is kept out of backups and device transfers like
   the rest of the app's data, is never logged, and is not under the wallet
   password, so sessions resume without an unlock.
9. **Sepolia first.** A dApp session names Sepolia (`eip155:11155111`) alone.
   Ethereum and Gnosis Chain come by a later ruling, once dApp connections
   have been tried against real dApps. #22 was written when Sepolia was the
   only network; this keeps its scope after the networks ruling of
   2026-10-22.
10. **dApps connecting to Serotine come first** (direction 1, below):
    signing in and requests for EOAs first. An external wallet as a Safe
    owner's signer (direction 2) follows. #22 had them the other way round.

## The two directions

1. **Serotine as a wallet.** A dApp connects to Serotine and asks it to sign
   or send for one of its accounts: to sign in (EIP-4361), to sign typed data,
   or to send a transaction.
2. **Serotine as a dApp.** Serotine connects to another wallet and uses it as
   the signer of a Safe owner. A user who does not want to keep a key in
   Serotine keeps it there and signs there.

They combine: a dApp asks Serotine's Safe for a transaction, Serotine gets an
owner's signature from the external wallet, and executes the Safe
transaction.

The engine needs no change for either. `io.myotis.api` already has
everything on the chain side: the verified `call`, `estimateGasDetailed`,
`getStorageAt`, `getCode`, `getTransactionCount` with its pending overlay,
`sendRawTransactionDetailed`, the receipt checked against `receiptsRoot`,
`getLogs`, `maxPriorityFeePerGas` and `feeHistory`.

## What the relay learns, and what it can do

(Ruling 1.)

- **Who runs it.** Today a centralised service: its gateway nodes are run by
  Reown (formerly WalletConnect Inc.), the nodes behind them by a selected
  group of operators. A permissionless network is on WalletConnect's
  roadmap, not known to be live when this was written (November 2026).
  Serotine does not call the relay decentralised.
- It sees the device's IP address, the topics it subscribes to, and the
  timing and size of its messages, and the project ID. The dApp's side
  connects to the same relay, so it can link the two ends of a session.
- The payloads are end-to-end encrypted: it cannot read or forge one.
- It can withhold or delay a message. A request it holds back expires, and
  the dApp gets nothing; a response it holds back reaches the dApp late or
  not at all. Neither signs anything.
- It can replay a message it recorded: the ciphertext still decrypts. So
  the client keeps the JSON-RPC ids it has seen per topic, with the session
  state of ruling 8 so that a restart does not forget them, and drops a
  request it has seen. It keeps the last 2,000 per topic. Once that many
  are kept, an id below the smallest of them counts as seen too: a Sign id
  is the time in milliseconds times 1,000 plus a random part, so a new
  request's is above every older one. A replayed `eth_sendTransaction`
  never prompts the user a second time.
- Nothing about the chain is taken from it, or from the dApp. Everything a
  request screen says about the chain is read through the light client.

## Direction 1: a dApp connects to Serotine

### Pairing and the session proposal

- The user pastes a `wc:` URI, or opens one from a `wc:` link on Android.
  Scanning a QR code needs a camera library, chosen later.
- A proposal whose required namespaces name any chain but Sepolia is
  rejected. Optional namespaces are approved with Sepolia alone (ruling 9):
  an optional namespace that does not name Sepolia adds no method or event
  to the session.
- A pairing has at most 5 proposals waiting, as a session has at most 20
  requests. One more is answered with -32005, so no peer holding a
  pairing's key can grow the stored state without bound.
- The proposal screen lists every account, and the user chooses which the
  dApp sees. An account that cannot be offered is greyed out, never hidden
  (owner ruling, 2026-10-18, #55), and a tap says why:
  - an account on Ethereum or Gnosis Chain: dApp connections are on Sepolia
    only for now;
  - a Sepolia account while Sepolia is off: "Turn on" Sepolia (Settings →
    Networks);
  - an EOA watched with no key on this device: it can sign nothing here. A
    watched Safe is offered, since its owners sign for it; until Safe
    messages come, it answers transactions alone (*The methods*).
- The dApp's name, URL and description are its own claim. The screen says
  so, and shows no icon (ruling 7).
- `wc_sessionAuthenticate` (one-click sign-in) is not answered in v1. Signing
  in then comes as a `personal_sign` request inside the session, the case
  designed below. Whether the dApps on the test list still fall back to that
  is part 1.1's spike.

### The methods

(Ruling 6.)

| method | an EOA | a Safe | answered with |
|---|---|---|---|
| `personal_sign` | signed, after review | refused until Safe messages (EIP-1271) come | the signature |
| `eth_signTypedData_v4` | signed, after review | refused until Safe messages come | the signature |
| `eth_sendTransaction` | signed and sent, after review | a `SafeTx`, signed by its owners and executed | the hash of the transaction sent |
| `wallet_switchEthereumChain` | to Sepolia only, which the session already is | the same | null, or an error for any other chain |
| `eth_sign` | refused | refused | an error |
| anything else | unsupported method | unsupported method | an error |

- A user who declines gets the "user rejected" error (EIP-1193 4001), and an
  unsupported method the 4200 one. A request is never left unanswered while
  the session lives.
- Every request must name `eip155:11155111`. A request for another chain, or
  a `from` or an address that is not one of the session's accounts, is
  refused.
- The errors: 4001 when the user declines; 4100 for an account or a chain
  the session does not give the dApp, or an account that cannot sign here;
  4200 for a method not answered; 4902 for a switch to a chain other than
  Sepolia (EIP-3326); -32602 for parameters that are not the method's.
- **What needs no one's decision is answered at once**, by the app while it
  runs: `wallet_switchEthereumChain`, and parameters out of shape.
- JSON from outside the device (the relay's frames, a dApp's messages, typed
  data) is read only when it nests at most 64 deep, measured without
  recursion: a deeper document would overflow the reader's stack. A
  request's parameters are kept only when they nest at most 32 deep.
- Parameters are read strictly: `personal_sign` takes exactly
  `[message, address]`. The message is the bytes its `0x` hex spells, or, when
  it is not `0x` and hex, the text's UTF-8 bytes, as wallets read it. Either
  way, the bytes shown are the bytes signed. At most 16 KiB.

### Signing in (`personal_sign`, EIP-4361)

- **The digest is computed here**: Keccak-256 of
  `"\x19Ethereum Signed Message:\n" ‖ the message's length in bytes ‖ the
  message` (EIP-191, version `0x45`). The signer port signs it as given,
  under the account's unlock, as Send does. `eth_sign`, which would sign a
  digest the dApp chose, stays refused.
- **The message is shown as the dApp sent it.** Bytes that decode as UTF-8
  are shown as text; others as hex, marked "not text". So is text holding a
  control character other than a line break or a tab, a formatting one (a
  right-to-left override, a zero-width space), a line or paragraph
  separator, or any other character Unicode says draws nothing (its
  default-ignorable code points, the tag characters and the variation
  selectors among them), which could make it read as something it does not
  say.
- **A message that parses as EIP-4361** is shown field by field: the domain,
  the address, the statement, the URI, the chain id, the nonce, when it was
  issued, and its expiry and not-before times where it has them. It parses
  only when its domain is a plain host (ASCII, or an IPv6 address) with an
  optional port and no user part, and its URI, resources and request ID are
  printable ASCII, as RFC 3986 has them, so that none reads as one site and
  is another.
  - The address must be the account asked to sign, and the chain id that
    account's network. Otherwise the request is refused.
  - A message past its expiry, or before its not-before time, is refused.
  - A domain that is not the host of the URL the dApp stated for the session
    is warned about in plain words. Both are the dApp's own claim, so the
    warning says it may be a site posing as another, and cannot say which
    site it is.
- **A message that does not parse as EIP-4361** is signed only after a
  warning that it is not a sign-in and that the app cannot say what it
  commits to.
- Signing in reads nothing from the chain. The screen says nothing about the
  account's balance or state beyond what the account screens already show.
- **A request opens its review (D4) as it arrives**, in the Settings tab,
  once per run of the app: one left unanswered waits in Connected dApps. It
  waits while a flow covers the tab bar (a key operation, a review being
  signed), as a `wc:` link does.
- A request that cannot be signed (a check above, a watched account, a
  Safe) is shown with the reason, its Sign greyed out, and can only be
  refused; Refuse answers with the error that reason has.

### Typed data (`eth_signTypedData_v4`)

- The JSON is parsed strictly, with size limits. The EIP-712 encoding and
  hash are computed here, never taken from the dApp.
  - `[address, typedData]`, exactly two; the typed data a JSON string (or an
    object), at most 64 KiB, exactly `{types, primaryType, domain, message}`,
    each type's member exactly `{name, type}`, every number an integer.
  - Every member of a struct present and nothing else: a value the type does
    not have would be shown and not signed. Each value inside its type's
    range; arrays at most 1,024 items, nesting at most 16 deep.
  - The domain's members are EIP-712's own, each of its own type: `string
    name`, `string version`, `uint256 chainId`, `address
    verifyingContract`, `bytes32 salt`.
  - Anything else is answered as invalid at once (-32602), never shown.
- The domain's `chainId`, where it has one, must be the account's network.
  A domain without one is shown with a warning.
- Structs this app knows are decoded and shown for what they let someone do:
  an ERC-2612 `Permit` and Permit2's permits as an approval of the spender,
  for the amount and until the deadline; a GPv2 order as a swap order. An
  approval moves no funds itself but lets the spender take them, and the
  screen says so.
  - A struct is known only when its type is exactly the standard's, member
    by member. Permit2's (`PermitSingle`, `PermitTransferFrom`) and GPv2's
    `Order` also need the domain to name the contract that verifies them,
    the same address on every chain: Permit2 at
    `0x000000000022D473030F116dDEE9F6B43aC78BA3`, CoW's settlement at
    `0x9008D19f58AAbD9eD0D60971565AA8510560ab41`. The same struct for another
    contract can mean anything, so it is not decoded.
  - A GPv2 order is known only with `kind` sell or buy, ERC-20 balances and
    no `feeAmount`: a fee is taken on top of the sell amount, and this app's
    own orders carry none.
  - A permit's `owner` must be the account asked to sign it; otherwise the
    request is refused.
  - A token is named by the built-in list (its pinned symbol and decimals);
    any other is shown by its address, its amount in raw units.
- Any other struct is shown field by field and marked as not decoded, with a
  warning that the app cannot say what signing it lets someone do.
- The domain is shown too, and every field of the message, decoded or not.
  Data with more than 200 values is refused (EIP-1474's -32005), never shown
  cut. A string that holds a character drawing nothing, or a line break, has
  it spelled out (`⟨U+202E⟩`, `⟨LF⟩`).

### Transactions (`eth_sendTransaction`)

- **The request's fields are applied or refused, field by field, never
  accepted and ignored** (Myotis #314). `from`, `to`, `value` and `data` are
  applied. For the others (part 1.4b):
  - `input` stands for `data`, `gasLimit` for `gas`; given both, they must be
    the same.
  - `gas` is applied as the gas limit when it covers the verified estimate;
    below it the transaction would run out of gas, so it is refused (-32602),
    never raised.
  - `chainId` must be Sepolia's, `type` `0x2` (EIP-1559, which every
    transaction here is), and an `accessList` empty.
  - Refused: `nonce` (the account's next one is the wallet's, behind its own
    transactions not mined yet), `gasPrice`, `maxFeePerGas` and
    `maxPriorityFeePerGas` (the fees are the wallet's, from the verified fee
    history, as Send's), a missing `to` (no contract is created for a dApp),
    and any other field. Refused fields are answered -32602 at once.
  - A transaction to the zero address is refused, as Send refuses one.
- **The review shows only what Serotine works out itself:**
  - the recipient, the value, and the calldata decoded for the ABIs the app
    knows (ERC-20 `transfer` and `approve`, Safe's own methods, the Uniswap
    calls it already builds). Anything else is shown raw, marked as not
    decoded;
  - a simulation: the verified `call` and `estimateGasDetailed` at the
    verified head. A call that would revert is shown so and not sent;
  - a token's symbol and decimals as the built-in list pins them, or as the
    contract states them in a verified call, marked as its claim;
  - the fee, as Send works it out: the verified nonce, `feeHistory`, the
    verified gas estimate, EIP-1559.
- **An EOA's transaction** is signed by the signer port, sent through
  `sendRawTransactionDetailed`, and the dApp is answered with its hash. Its
  inclusion comes from a verified receipt.
  - It is quoted as Send quotes a transfer: the fees at the normal level, the
    call run as a verified call at the head, the balance it leaves. A quote
    over a minute old is made again before it is signed.
  - The dApp is answered once a peer took it. One no peer took is kept, its
    signed bytes sent again by Try again, never signed anew, and the dApp is
    answered when that sends it.
  - The record names the request it answers, and the history keeps it once
    the record lets it go. So a request is signed once, whatever happens to
    the screen and whatever is reviewed now: opened again, it shows the
    transaction signed for it, never a new quote. Whenever a peer takes that
    transaction, or a verified block includes it (from D6, a retry from its
    status, or mined anyway), the dApp is answered with its hash, by the app
    while it runs. One whose nonce another transaction took can never be
    mined: the request can then only be refused.
  - It is reviewed again just before it is signed: an account removed, a key
    gone or Sepolia turned off since the quote refuses it.
  - While a transaction signed for it may be on its way, Refuse is greyed
    out, and a tap says why: refusing would tell the dApp no while it may
    still be mined.
  - It is recorded as Send's are, which keeps its nonce and shows its status
    in Activity: "Sent for a dApp stating it is …", the name the dApp's own
    claim.
- **A Safe's transaction** becomes a `SafeTx` with operation `CALL`, and
  every Safe rule applies as for a transaction the user builds: the
  fallback-handler and module refusals (owner rulings, 2026-10-18, #63 and on
  #54), no delegate call outside the MultiSendCallOnly rule (owner ruling,
  2026-10-18, #57), no gas refund. Its owners sign it: keys on this device,
  co-signers through a QR code or text (owner ruling, 2026-10-16), and, once
  direction 2 lands, an external wallet. A local EOA executes it until then.
  Part 1.5 builds it:
  - It is proposed as a Safe transfer is: a plain call of the request's
    recipient, value and data, no refund, at the Safe's next nonce behind
    its queued transactions. The Safe is verified as one first, and the
    value must be in its verified ether, less what its queued transactions
    may move.
  - The call is run as a verified call from the Safe at the head. One that
    reverts, or a token's `transfer` or `approve` that returns false, is
    shown so and not proposed.
  - A `gas` field is refused (-32602): a Safe transaction's gas is set by
    the account that executes it, so the dApp's limit could not be applied.
  - A call of the Safe itself is refused (4100), whatever it changes: D6
    decodes no settings change, so none is signed from a dApp. The Safe's
    owners, threshold, guard, modules and fallback handler change from its
    own screens alone.
  - It is signed on D6 by the first of the Safe's owners whose key is on
    this device, and queued. With none, D6's Sign is greyed out and says
    why. A proposal over a minute old is made again before it is signed.
  - Once queued it is an ordinary Safe transaction: its screen opens in D6's
    place, and there the other owners' signatures are collected (here, or
    through a QR code or text) and an account here executes it.
  - A request signed for is that Safe transaction, never another. Once an
    owner's signature is made, its `safeTxHash` is recorded with the
    request before it is queued, and nothing is queued unless that record is
    saved; a signature not made records nothing. Opened again, D6 offers to
    open it. One no longer queued here is proposed again as itself while its
    nonce is free; once another transaction takes that nonce, or the Safe's
    nonce moves on, it is never proposed as another, and the request can only
    be refused. The same call queued or executed by hand is never taken for
    it.
  - Refuse stays offered while it is queued: it tells the dApp no and
    leaves the Safe transaction queued, for rejecting on its screen.
- **A dApp is answered only with an Ethereum transaction hash.** For a Safe,
  that is the hash of the `execTransaction` sent, never the `safeTxHash`. The
  app answers it while it runs, once a peer takes an execution of the Safe
  transaction recorded for it, sent from this device on Sepolia, or a
  verified block includes one. An execution a co-signer sends from another
  device does not answer it. If the request expires first (the threshold
  not met in time, or executed elsewhere), the dApp gets an error, and the
  Safe transaction stays queued. It is never left hanging, and never given
  a hash that is not a transaction.

### The session's life

- Sessions are listed in Connections, each with its dApp's stated name, its
  accounts and its expiry, and each can be disconnected.
- Expiry, extension, ping and delete follow the protocol. When the user
  changes which accounts a session sees, the dApp gets `accountsChanged`.
- An account removed from the wallet, whose key was removed, or whose
  network was turned off leaves every session that sees it, and a session
  left with none ends. A network counts as turned off only once the setting
  has answered it as on while the app runs, a default included. A network
  setting that cannot be read is answered with the build's defaults, and a
  release build's has Sepolia off, so that alone ends no session. Its
  requests are refused all the same.
- Sessions survive a restart (ruling 8). A request that arrived while the
  app was closed is answered once it opens, while it has not expired.

## Direction 2: an external wallet signs for a Safe owner

- **The tier** (ruling 3; docs/keys-and-signing.md, *The account tiers*).
  Serotine starts a pairing and shows its `wc:` URI as a QR code, with "Open
  in wallet" for a wallet on the same device (`wc:`, `metamask://wc?uri=`).
  The wallet's accounts are matched against the Safe's owners as its verified
  storage lists them (#10). An account that is not an owner is shown, and
  greyed out with the reason.
- **What it is asked to sign** (ruling 5): `eth_signTypedData_v4` of the
  `SafeTx`, with Safe's domain `{chainId, verifyingContract}` and the nonce
  from a verified storage read.
- **The signature is checked before it is used.** The `safeTxHash` is
  computed here. The signer is recovered locally with libsecp256k1, and the
  signature is kept only when it recovers to the session's account and that
  account is a current owner. `v` is normalised: 0 or 1 becomes 27 or 28.
- **The request carries the `SafeTx`, never a bare digest**, since the
  external wallet must have the struct to show it. The signer port of
  docs/keys-and-signing.md takes a digest and stays as it is; the external
  wallet is a port of its own, `ExternalWallets`, whose outcomes are sealed
  results: signed, rejected in the wallet, expired, disconnected, or not the
  expected owner.
- **Who executes** (ruling 4): the external wallet by default when this
  device holds no key. Its `eth_sendTransaction` goes out through the
  wallet's own RPC provider: that is a write, not a chain read. Serotine
  confirms inclusion from a verified receipt and an `ExecutionSuccess` or
  `ExecutionFailure` log.
- **The independent second view.** On another device, the external wallet is
  the independent view of docs/keys-and-signing.md (*What key storage does
  not do*): it shows the `SafeTx` struct on its own screen, though `data` as
  hex. On the same phone it is less independent, and the screens say so.

## Architecture

(docs/architecture.md.)

- **A WalletConnect v2 Sign client of our own, in common Kotlin, in `:data`**
  (`eth.serotine.data.walletconnect`).
  - Not Reown's SDK: its Kotlin SDK is Android-only, so the desktop and iOS
    would need a second client anyway, and it brings its own network, DI and
    database stack, where CLAUDE.md names Ktor.
  - The crypto is in the app already: libsodium (X25519, ChaCha20-Poly1305
    IETF, Ed25519) on all three targets, KotlinCrypto's SHA-256 and HMAC, and
    `Hkdf` in `data.keys`. kotlinx-serialization reads the JSON.
  - No new dependency: Ktor's WebSocket client is part of
    `ktor-client-core` at the Ktor pin (3.3.3), which the app ships already,
    on the engines Ktor already uses (CIO on Android and the desktop, Darwin
    on iOS). The final PR's APK check (`scripts/check_apk_min_api.py`)
    covers it against the minSdk 29 budget.
  - It covers: typed envelopes 0 and 1; X25519 key agreement and HKDF to the
    symmetric key, the topic being `sha256(symKey)`; `wc:` pairing URIs; relay
    auth, a JWT signed with an Ed25519 `did:key`; the `irn_*` JSON-RPC calls,
    with acks, and reconnect with backoff; the session lifecycle in both
    roles (propose and settle, request and response, update, extend, ping,
    delete, expiry); a request seen before on its topic dropped, the ids
    kept with the session (*What the relay learns*); persistence (ruling 8).
- **Ports in `:domain`, one concern each:**
  - `DappSessions`: pair from a `wc:` URI; observe proposals and sessions;
    approve with the chosen accounts; reject; disconnect.
  - `DappRequests`: observe incoming requests, and answer each with a result
    or an error.
  - `ExternalWallets` (direction 2): start a pairing, which returns its `wc:`
    URI; observe the connected wallets and their accounts; ask one to sign a
    `SafeTx`; disconnect.
- **In `:domain`'s model, pure Kotlin:** the EIP-4361 parser, the EIP-191
  digest of a message, and the EIP-712 encoder and hash for arbitrary typed
  data. Keccak-256 may do any of Ethereum's hashing there (owner ruling,
  2026-10-18, #50, widened 2026-11-03 on #218; docs/architecture.md), so the
  digest the user approves is computed where the use case can show what it
  is the hash of.
- **The typed data's JSON is read in `:data`**, by the adapter that decodes
  the request, since `:domain` reaches JSON only through a port. It parses
  strictly, with size limits, into `:domain`'s typed-data model: the types,
  the primary type, the domain and the message. Anything that is not exactly
  EIP-712 JSON is refused, never repaired. Direction 2, which writes typed
  data for an external wallet, adds the other way when it needs it.
- **No key reaches the WalletConnect layer.** A local signature goes through
  the signer port. Nothing is imported from an external wallet, and nothing
  about it is kept but its address and its session.
- **The hosts own the relay connection's lifecycle**, like the engine's. On
  Android, opening another wallet sends Serotine to the background, where the
  socket may drop and the engine pauses after 30 s. On resume Serotine
  resubscribes and picks up the answer, which the relay holds for its TTL.
  The relay connection runs while the app is in front (Android, iOS) or open
  (the desktop), beside `FollowDappAccounts`, which keeps each session's
  accounts to those still in the wallet. The project ID comes from the build
  (ruling 2); without one, Connected dApps is greyed out, and a tap says this
  build has WalletConnect switched off. The state is in a directory of its
  own, `walletconnect/`, owner-only and, on iOS, out of iCloud and computer
  backups; Android keeps all its files out of backups and device transfers.
- Use cases and screens follow the five-step checklist at the end of
  docs/architecture.md.

## Setting up the project ID

(Ruling 2.) The relay serves only an app that sends a project ID. The owner
sets it up outside the app, once. Everything else in this plan, from the
code to the tests against a fake relay, needs no ID. Part 1.1's live test is
the first thing that does.

1. **An account on the WalletConnect (Reown) dashboard**, under the owner's
   own account: the project is the owner's.
2. **Two projects of type Wallet (WalletKit)**, one for the debug build and
   one for the release build. They are separate because ruling 2 asks for
   separate IDs, and because the two Android apps have different package
   names.
3. **App ID on each project**: the Android package name and the iOS bundle
   ID of that build. With those set, another app cannot use the project's ID
   to pass itself off as Serotine.
4. **The two IDs as GitHub Actions secrets** on the repository, which the
   workflows pass to Gradle as the properties `walletConnectProjectIdDebug`
   and `walletConnectProjectIdRelease` (as the environment variables
   `ORG_GRADLE_PROJECT_walletConnectProjectIdDebug` and
   `ORG_GRADLE_PROJECT_walletConnectProjectIdRelease`). `:data` writes them
   into a generated, uncommitted source file; an ID that is not letters and
   digits fails the build. The workflows pass them once the secrets exist; a
   pull request from a fork gets none, and builds with WalletConnect
   switched off.
5. **For a local build**, the debug ID goes in `~/.gradle/gradle.properties`
   as `walletConnectProjectIdDebug=<id>`, never in a file of the repository.
   Which ID a build uses: the Android debug app, a desktop development run
   and the iOS debug binary the debug one; the release app, the desktop
   installers and the iOS release binary the release one. Without one, the
   build has WalletConnect switched off, and Settings shows Connected dApps
   greyed out, a tap saying why.
6. **The plan and its terms.** Reown has a free tier and paid plans. Before
   release, the owner checks the free tier's limits against the expected
   number of sessions, and reads the terms (*Risks to check early*).

Not needed, since rulings 7 and 9 leave them out: the Verify API, push and
Link Mode need no registration. The `wc:` scheme on Android is a manifest
entry, with no domain to verify. A listing in WalletGuide, WalletConnect's
directory of wallets, is what puts Serotine in the wallet pickers dApps
show. It is optional, reviewed by WalletConnect, and asked for by the owner
when the time comes, likely not before dApp sessions reach Ethereum. Until
then a user connects with a `wc:` link.

## The screens

Numbered D1 to D8. Each gets a preview per state and one in the dark theme;
their mockups come with the PR that builds each.

- **D1 Connections** (Settings): the dApp sessions, and in direction 2 the
  external wallets; each can be disconnected.
- **D2 Pair**: paste a `wc:` URI, or arrive from a `wc:` link.
- **D3 Session proposal**: the dApp's stated name and URL, marked as not
  verified; the chain; the methods it asks for, those outside the closed list
  marked as refused; the accounts to choose from; Approve and Reject.
- **D4 Sign-in request**: the dApp's stated name and URL, marked as not
  verified; the account asked to sign, in full; the EIP-4361 fields, or the
  message as text or hex; the domain warning or the not-a-sign-in warning;
  how long the dApp waits; the unlock, and Refuse. D1 lists the requests
  waiting, each opening D4.
- **D5 Typed-data request**: the decoded struct or its fields; the domain;
  the unlock. Drawn by D4's screen, which shows typed data in place of the
  message.
- **D6 Transaction request**: the decoded call, the simulation, the fee; for
  a Safe, its signatures so far and who executes. The EOA's half comes with
  part 1.4b: who asks, the account and the recipient in full, the value, the
  call decoded (a payment, an ERC-20 `transfer` or `approve`) or its data
  raw and marked not decoded, the fee at most, the balance left at least,
  the verified run; the unlock, and Refuse. The Safe's half comes with part
  1.5: that it is proposed as a Safe transaction; its nonce and the
  transactions queued ahead, the threshold, what the Safe has left at least,
  the call run from the Safe, the owner who signs here, and that the
  executing account pays the fee; Sign and queue, and Refuse. Once queued,
  its Safe transaction screen shows its signatures so far and who executes.
- **D7 Connect a wallet** (direction 2): the QR code, "Open in wallet", and
  the wallet's accounts matched against the Safe's owners.
- **D8 Waiting for a signature** (direction 2): what was asked, of which
  wallet, until when; and the way back to it.

## The plan

A multi-PR plan, so it lands through feature branches (CLAUDE.md, *Multi-PR
plans*). The session that starts part 1.1 first asks the owner whether it may
merge part PRs into the feature branch itself.

0. **The rulings**: this page, and the rulings in CLAUDE.md, the rule files
   and docs/keys-and-signing.md. One docs-only PR into `main`.
1. **`feature/walletconnect-wallet`, direction 1** (ruling 10):
   1. The protocol core in `:data`: the crypto, the envelopes, the relay
      client, pairing, the wallet role of a session, persistence. Tests run
      against the spec's vectors and a fake relay. A live test against the
      real relay runs only with a project ID
      (`-PwalletConnectLiveTest=<id>`); pairing with Reown's sample dApp on
      Sepolia is the spike once the ID exists.
   2. dApp sessions: pairing, the proposal, the Sepolia-only namespace, the
      session list, disconnect, expiry and extend, `accountsChanged`. Screens
      D1 to D3. Opening a `wc:` link on Android, which arrives at D2 with the
      link filled in, follows in a part of its own (1.2a).
   3. Signing in for an EOA: `personal_sign` and EIP-4361. Screen D4.
   4. The other EOA requests, in two PRs: `eth_signTypedData_v4` (D5, 4a),
      then `eth_sendTransaction` (D6, 4b).
   5. Requests on a Safe: `eth_sendTransaction` as a `SafeTx`, signed by
      local owner keys and co-signers, executed by a local EOA.
2. **`feature/walletconnect-signer`, direction 2**, after the first branch
   reaches `main`:
   1. The dApp role of a session, and connecting an external wallet: D7 and
      D1's wallets, matched against the Safe's verified owners.
   2. A `SafeTx` signed in the external wallet, starting with an ETH transfer
      from the Safe: the verified nonce, the `safeTxHash`, the typed-data
      request and the local check of its signature, the executor of ruling
      4, inclusion from a verified receipt and log. Screen D8. A dApp's Safe
      request (1.5) then takes the external wallet's signature too.
3. **Later, each its own issue:** QR scanning on Android; a `wc:` URL scheme
   registered by the desktop installers; requests delivered while Serotine is
   in the background; `wallet_sendCalls` (EIP-5792) through MultiSend for a
   Safe; Safe messages (EIP-1271), which `personal_sign` and typed data on a
   Safe wait for; the Verify API; and dApp sessions on Ethereum and Gnosis
   Chain. Each needs the owner's ruling where it touches one.

## Open decisions

The owner's, each recorded here and in the rules before its code lands:

- dApp sessions on Ethereum and Gnosis Chain (ruling 9).
- Each optional WalletConnect service (ruling 7).

## Risks to check early

- **MetaMask's support for WalletConnect v2.** It now promotes its own
  MetaMask Connect (CAIP-25). Part 2.1 checks that it still takes `wc:`
  pairings and approves an optional `eip155:11155111` namespace, whether it
  needs the typed data's `domain.chainId` to match the session's chain, and
  how the user gets back to Serotine after signing on the same phone.
- **One-click sign-in.** dApps built on Reown's AppKit may ask for
  `wc_sessionAuthenticate` first. Part 1.1's spike checks that they fall back
  to a session and a `personal_sign`.
- **Reown's quotas and terms for the relay**, which may change as the
  WalletConnect Network decentralises.
- **Request expiry** (5 minutes to 7 days) against co-signers who take
  longer.
- **Interoperability.** To be tried against Reown's sample dApp,
  app.safe.global and Uniswap as dApps on Sepolia, and against MetaMask
  Mobile, Rainbow, Rabby and Reown's sample wallet as signers.

## Done when

- A Sepolia dApp connects to Serotine, and the user signs in to it with an
  EOA whose key is on this device. Its transaction request, for an EOA or a
  Safe, is reviewed, signed, sent and answered with the transaction hash.
  Anything outside the closed method list, or off Sepolia, is refused with an
  error.
- A user with no key in Serotine connects an external wallet, which is
  recognised as a verified owner of a watched Safe, sends ETH from that Safe
  with the signature made in that wallet, and Serotine shows the execution,
  verified.
- Sessions survive a restart. No key and no session secret appears in a
  UiState, a log or a `toString()`.
- Tests per layer, the protocol among them against the spec's vectors, and
  the EIP-191, EIP-712 and signature-recovery code against fixed vectors.

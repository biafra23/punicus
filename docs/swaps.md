# Swaps

How Serotine swaps one token for another without a server it has to trust:
on Uniswap's contracts directly, and through CoW Protocol. This page is a
design. Its Uniswap plan is built, its eight parts merged through
`feature/swaps-uniswap` (*The plan*). Its CoW plan is issue #144, on
`feature/swaps-cow`. Issue #69 tracks both. A swap PR is held
to this page together with docs/wallet-ux.md (the screens and their rules),
docs/embedding-myotis.md (what the engine reads and sends) and
docs/keys-and-signing.md (what is signed). Where this page and the rules
under `.claude/` (their short form in CLAUDE.md) disagree, the rules win and
this page is fixed.

## The owner's direction (2026-10-18, and 2026-10-22)

- **Uniswap first, CoW Protocol second.** Uniswap is built first: it needs
  nothing but the chain, which the engine already proves.
- **The user decides which venue a swap uses** (2026-10-22). If CoW becomes
  unavailable, the user can still use Uniswap. This replaces the direction of
  2026-10-18, under which a swap went to CoW automatically while CoW's
  servers worked and fell back to Uniswap.
- **An API change must not break swaps.** CoW's HTTP API can change, and the
  app would then need an update. *How often CoW's API changes*, below,
  measures that and sets out how the design limits what it costs.

## The two venues

| | Uniswap, directly | CoW Protocol |
|---|---|---|
| Who has to be up | Ethereum peers | CoW's order API, its auction and at least one solver CoW DAO has allow-listed |
| Who can refuse a swap | No one: the contracts are open to every caller | The API (it keeps a deny-list of accounts) and the solvers (none has to fill) |
| The quote | A verified call: QuoterV2 run in the engine's local EVM over proven state | CoW's own estimate, net of its fee, which the light client cannot prove |
| What enforces the signed minimum | The router: `amountOutMinimum`, or the call reverts | The settlement contract: the order's `buyAmount`, or it does not settle |
| MEV | The transaction is gossiped to the public mempool, so a sandwich can take up to the slippage tolerance | A batch auction: the user's order never sits in the public mempool |
| Gas | The account pays it in ETH | Taken out of the price; ETH only for an approval, and for a cancellation on chain |
| What leaves the device | The signed transaction, to peers, like every send | The order, the account's address and the device's IP address, to `api.cow.fi`, and only for a swap the user sends to CoW |
| On Sepolia | v3 and v4 deployed | Deployed; the API is `https://api.cow.fi/sepolia/api/v1` |

Uniswap is the venue that needs no server. CoW is open to anyone but runs on
CoW's servers. Its trust is bounded: the settlement contract checks the
signature and the limit, so the API and the solvers can delay an order or
leave it unfilled, but they can never fill it at a worse rate than the one
signed.

## What both venues share

- **Amounts are token units** (docs/wallet-ux.md, *Rules every screen
  follows*). The user enters the amount to sell in the token's units.
  - The signed minimum never comes from a Chainlink price.
    - On Uniswap it is the pool's own verified quote for this exact amount,
      less the tolerance. That is the same kind of value as Send's verified
      simulation.
    - On CoW it is never below a floor made of that verified quote (*The
      signed minimum*); CoW's estimate can only raise it.
  - So the ruling that a price never reaches a signature (CLAUDE.md, *Data
    sources*) stands unchanged.
  - Fiat values stay hints beside the amounts.
- **Only tokens the wallet lists.** These are native ETH, the built-in tokens
  and the ones the user added (marked as the contract's claim, as
  everywhere).
  - A swap never offers a token by an address the wallet has not listed, so
    what it buys always shows among the account's assets and can be sent.
  - WETH is offered as the token it is: the pinned WETH9 is built in
    (docs/wallet-ux.md, *Tokens*), and ETH against WETH is a wrap, not a
    swap, so no route has it.
  - Otherwise the pinned WETH is only a route's middle token and ETH's
    wrapper, which the router wraps and unwraps within the swap.
- **The proceeds go to the account itself.** No other recipient is offered:
  sending somewhere else is Send's job.
- **Slippage.** The tolerance applied to a verified quote, rounded down: 0.5 %
  by default, between 0.1 % and 5 %, and no second confirmation for a large
  price impact for now (owner ruling, 2026-10-19, decision 2).
- **Approvals are their own transaction, for the exact amount.**
  - When the account's allowance for the venue's spender does not cover the
    amount, the swap review starts with an approval step: "Allow
    ‹SwapRouter02› to take 100 USDC". The spender is pinned and shown with its
    name, blockie and full address.
  - The approval is quoted, signed and sent as any transaction from the
    account is. The swap is quoted only once its verified receipt is in.
  - An allowance that already covers the amount is used and said. A token
    whose `approve` reverts from one non-zero value to another (USDT's kind)
    shows that in the approval's verified run, and the step then approves
    zero first.
  - An allowance left over by a swap that never ran moves nothing on its
    own:
    - SwapRouter02 pulls only from the account that calls it.
    - CoW's vault relayer pulls only for an order the account signed.
  - The exact amount, one approval per swap whose allowance falls short: no
    unlimited approval and no Permit2 (owner ruling, 2026-10-19, decision 3).
- **Freshness, for a swap from an account held here**, as Send's quote
  (docs/wallet-ux.md, screen 8):
  - A quote is made when the review is entered, again on Refresh, and when
    the screen is shown again after it was not. It is made again at the tap
    that would sign it when it is over a minute old.
  - At the tap that signs a Uniswap swap, the exact transaction is run again
    at the current head. When it still succeeds, what the user saw is what is
    signed. When it does not (the minimum no longer met), it is quoted again
    and shown, not signed.
  - At the tap that signs a CoW order, the Uniswap net is quoted again. When
    the floor it makes has risen above the minimum shown, the review is made
    again and shown, not signed.
  - A Safe's swap has its own rules (*A Safe's swap*).
- **The record.**
  - `SentAction` gains an approval, a Uniswap swap (the token sold and its
    amount, the token bought and the signed minimum) and a CoW order's
    cancellation on chain.
  - A CoW order is no transaction (no nonce, no signed bytes), so it gets a
    record of its own instead, from the moment it is signed (*A signed order
    is live until it is proven dead*).
  - The status screen (9) says "Swap of 100 USDC for at least 4.2 LINK" and,
    once known, the amount received.
  - On Uniswap that amount comes from the verified receipt: the token's
    `Transfer` log to the account. For ETH out, it is WETH's `Withdrawal` log
    from the router, since unwrapped ETH comes with no `Transfer` log. Only
    the logs of the contract the record names count (the token bought, or
    the pinned WETH9 the record keeps), since any token on the route could
    emit a log that claims a transfer.
  - On CoW it comes from the settlement's `Trade` log (*Proving an order's
    end*). Until that log is found, the status says "at least" the minimum.
- **Native ETH** goes in and out through WETH, as each venue does it (below).

## A Safe's swap

A Safe swaps through the co-signing queue (docs/wallet-ux.md, *Co-signing a
Safe transaction*), on Uniswap only until CoW's pre-signature is built
(*Later on CoW*).

- **Two transactions, or three.** The approval and the swap are two Safe
  transactions at consecutive nonces. A MultiSend batch would be a delegate
  call: the owner allowed one through the pinned `MultiSendCallOnly`
  (2026-10-18, issue #57), but until that is built every delegate call is
  refused.
  - The approval is for the exact amount, at the Safe's next nonce, and the
    swap takes the one after it. When a nonce they would take is taken
    already (a transaction queued further ahead), nothing is proposed: the
    swap would sit beside it.
  - The allowance is the router's on chain, as the approvals of it queued
    before the swap leave it (per nonce the least of its alternatives, since
    any one of them may be the one that runs). One that covers the amount
    needs no approval of its own.
  - A swap of the token through the router queued before it counts on the
    allowance a new approval would set: nothing is proposed until it has
    run or gone.
  - A token that refuses one allowance for another (USDT's kind) takes a
    third transaction, the allowance set to zero first (owner's request,
    2026-10-23): `approve(0)` at the Safe's next nonce, the approval of the
    amount after it, and the swap last. It is told by the approval's
    verified run from the Safe: when the approval of the amount reverts (or
    returns false) over a non-zero allowance on chain, `approve(0)` is run
    instead, and only if that goes through are the three proposed. The
    approval after it cannot run before the reset has, so like the swap it
    is run when its execution is quoted. A reset signed and queued whose
    approval was not is not proposed again: the approval goes after it,
    and the swap last. A token that refuses the approval
    with its allowance at zero, or `approve(0)` itself, is not swapped from
    a Safe: nothing is proposed.
- **What is held back.** What the transactions queued before the swap may
  move of the asset sold is not the swap's to sell, as for a Safe's transfer.
- **Only what is decoded counts.** The allowance and the balance a proposal
  shows count the transactions queued before it that decode exactly (an
  approval, a transfer, a swap). One that does not, a request imported from
  elsewhere that calls the token or the router in another way
  (`increaseAllowance`, `transferFrom`, a multicall of another shape), is
  counted as moving nothing and setting no allowance, so the figures may
  promise more than its turn finds. This wallet never queues such a call,
  and the swap's verified run before it is executed is what decides: the
  worst case is a swap that does not run and is signed for nothing, as for
  a token of USDT's kind behind an approval already queued.
- **Decoded here.** Screen 17 decodes each from its raw call to a pinned
  contract, never from a description: "Swap 100 USDC for at least 4.2 LINK on
  Uniswap".
- **Proposed from the Safe's own Swap** (screen 24): Swap's form, with no
  fee to choose (whoever executes it pays); then the review of the Safe
  transactions it takes, decoded here at their nonces, the minimum, the
  route and the deadline, the Safe's balance before and after, and the
  owner held here who signs every one of them, one after the other. An
  approval signed whose swap was not stays queued, and the swap proposed
  again goes after it.
- **The minimum and the deadline are fixed when the swap is proposed.**
  - Re-quoting at a later owner's tap would change the `SafeTx` hash and void
    the signatures already collected, so it is not done.
  - The deadline is longer than an account's ten minutes, since collecting
    signatures by QR code takes time: 24 hours (owner ruling, 2026-10-19,
    decision 5).
- **Each owner sees the current state**: the minimum signed, and what the
  swap would return now by a verified QuoterV2 quote, said as meeting the
  minimum or not. The quote needs no allowance.
- **Execute runs only while the swap still succeeds.** Its verified run must
  succeed: deadline not passed, minimum met.
  - The run of the swap's execution needs the approval executed first. Until
    then the execution says "Not simulated", while the quote above still
    shows the current figure.
  - A swap whose run fails is not executed. The owners replace it at its
    nonce (a new proposal, or a rejection), as for any Safe transaction.
- **A minimum that has fallen behind the market is said at Execute.** Over
  hours the rate can move in the Safe's favour. A sandwich can then take
  everything between what the swap returns now and the signed minimum, not
  only the tolerance.
  - When the swap returns more than the minimum by over the default
    tolerance (its verified QuoterV2 quote now: an `execTransaction` run
    returns only whether it succeeded), Execute says how much the swap
    leaves open and offers to propose it again with a fresh minimum.
  - Proposed again, the swap takes the nonce of the one it replaces, with
    a fresh minimum and deadline: only one of the two can ever run. It is
    the same swap (the pair, the amount and the router), so those queued
    after it are left as they were, and one past a gap in the queue is not
    proposed again. It gets no approval of its own; one the router still
    needs is proposed first, from the Safe's Swap.
  - It may still be executed as signed (owner ruling, 2026-10-19, decision
    5).

## Uniswap (built first)

### Pins

The deployment table on Uniswap's developer docs gives these addresses on
Sepolia (read 2026-10-18):

| contract | Sepolia |
|---|---|
| `UniswapV3Factory` | `0x0227628f3F023bb0B980b67D528571c95c6DaC1c` |
| `QuoterV2` | `0xEd1f6473345F45b75F8179591dd5bA1888cf2FB3` |
| `SwapRouter02` | `0x3bFA4769FB09eefC5a80d6E87c3B9C650f7Ae48E` |
| `WETH9` | `0xfff9976782d46cc05630d1f6ebab18b2324d6b14` |

They are pinned per `Network`, as Safe's deployments are (`SafeDeployments`):

- The PR that adds a pin also pins the runtime code hash it reads through the
  engine (`getCode` is verified against the account's proven code hash).
- A pinned address whose code hash differs is no venue, and the swap screen
  says why.
- A pool is a pool only when the pinned factory's `getPool(tokenA, tokenB,
  fee)` returns it in a verified call. An address from anywhere else is never
  treated as a pool.

### Finding a route

- **Candidates.**
  - The direct pool at each fee tier (0.01 %, 0.05 %, 0.3 %, 1 %).
  - One hop through WETH: tokenIn → WETH → tokenOut, with the tier of each
    leg that has the most liquidity in range (`liquidity()`).
  - A pool with no liquidity in range is skipped.
- **Each candidate is quoted** with a verified call:
  - `quoteExactInputSingle` for a single pool;
  - `quoteExactInput` with the encoded path (token ‖ fee ‖ token ‖ fee ‖
    token) for one hop.
- **The largest amount out, net of the swap's expected fee, wins**; on a tie,
  the route with fewer hops.
  - The net is the one CoW's floor and the review's comparison use (*The
    signed minimum*), so a one-hop route that only grosses more does not beat
    a direct one.
  - A Safe's swap is paid for by whoever executes it, so it compares routes
    gross.
- **Uniswap's routing API is not used**: it is an off-chain service, and its
  answer would be a claim the light client cannot prove. Split routes and
  routes of more than one hop are what this gives up.
- **The cost.** A quote reads every storage slot the swap touches: the pool's
  state, the tick bitmap words and each initialized tick it crosses. The
  engine proves each slot from peers.
  - Measured on Sepolia (2026-10-19, `eth_createAccessList` of QuoterV2's
    calls): a direct quote touches 9 to 12 slots of 3 or 4 contracts, a hop
    16 slots of 6. A review reads up to twelve pools (`getPool`, then
    `liquidity()` of each listed one) and quotes up to five candidates, all
    in parallel, plus one quote that converts the fee.
  - USDC, LINK and UNI have a pool with WETH at all four tiers on Sepolia,
    and USDC/LINK, USDC/UNI and LINK/UNI have pools of their own. Every
    built-in token added on 2026-10-22 has at least one WETH pool with
    liquidity (verified `getPool` and `liquidity()` at block 11,966,567):
    EURC at 0.01 % and 0.3 %, PYUSD and GHO at 0.3 %, DAI and USDT at all
    four tiers, WBTC at 0.05 %, AAVE at 0.3 % and 1 %.
  - How long a review's quote takes through the engine is what
    `MyotisHostSepoliaTest` prints when run with the engine's library; the
    number of candidates is bounded only if that asks for it.
- **The fee a route costs**, when routes are ranked net: its QuoterV2 gas ×
  (the next base fee + the Normal tip), in wei for WETH or ETH bought, and
  otherwise converted by one quote of the largest such fee in WETH through
  the bought token's deepest WETH pool, the others taking their share by
  their gas. No fee reading or no conversion leaves the routes ranked gross,
  and the finding says so: the ranking only picks among verified quotes,
  and the minimum is the chosen route's own quote either way. The router's
  own work (the `transferFrom` and the multicall) is the same for every
  route of a swap, so it is not counted here.
- **Exact input only.** The user says how much to sell. Exact output ("buy
  exactly 5 LINK") can come later.

### The transaction

- **One call:** `SwapRouter02.multicall(deadline, data)`, where `data` holds
  `exactInputSingle` (or `exactInput`) with the recipient, `amountIn`,
  `amountOutMinimum` (the signed minimum) and no price limit.
  - **The deadline**, for an account held here, is the verified head's
    timestamp plus ten minutes, never the device's clock (as the price rules
    measure age: docs/wallet-ux.md, *Prices*). A Safe's is longer (*A Safe's
    swap*).
  - **Selling ETH:** the token in is WETH9 and the transaction's value is
    `amountIn`; the router wraps it.
  - **Buying ETH:** the recipient is the router itself (`ADDRESS_THIS`), and
    `unwrapWETH9(amountOutMinimum, account)` follows in the same multicall.
- **Quoted and sent like any transaction from a held account**: through
  `CallQuoter` and `signAndSend`, with the same nonce record, fee levels,
  estimate, verified run and receipt checks (docs/embedding-myotis.md,
  *Sending*).
- **The review** (screen 23) shows:
  - the amount sold, exact;
  - the amount the verified run returns ("you receive 4.31 LINK, run at block
    N") and the signed minimum ("at least 4.29 LINK, 0.5 % tolerance");
  - the route: its pools with their fee tiers and blockies;
  - the price impact: the quote against what the route returns at its pools'
    spot prices (`slot0`), less their fees, chain data both; read with
    verified calls of the chosen route's pools, and said as not known when
    one cannot be read;
  - the most the fee can be, and the balances of both tokens and of ETH
    before and after.
- **A hint, display-only.** The pool's spot price can be moved within a
  block just as the quote can. When the quote's rate is far from the
  Chainlink values the screen already shows, the review says so. On Sepolia
  the testnet feeds and the test pools do not track each other, so there the
  hint says that and no more (the only one built: comparing against the
  feeds waits for a network with market prices). It never changes an
  amount.
- **MEV.** The transaction sits in the public mempool, where it can be
  sandwiched by up to the tolerance. That bound holds for an account's swap,
  quoted moments before it is sent. A Safe's swap can lose more (*A Safe's
  swap*).
  - On Sepolia this does not matter. On mainnet, once it is allowed, the
    review says so.
  - A private relay (Flashbots Protect, MEV Blocker) is an HTTP endpoint no
    ruling allows, so none is used.

### Later on Uniswap

- **v4** keeps every pool in one `PoolManager`, quotes through `V4Quoter` and
  swaps through the Universal Router with Permit2.
  - A v4 pool can carry a hook, arbitrary code that runs with every swap in
    it. Whether only pools without hooks are used, or pinned hooks too, is
    the owner's decision.
  - Permit2 brings EIP-712 permits and a standing approval of the Permit2
    contract.
- **v2 pools**, for a token that trades only there.

## CoW Protocol (built second, the user's choice)

### An order, step by step

1. **The quote.** `POST /api/v1/quote` with the tokens, the account, `kind:
   sell` and `sellAmountBeforeFee`, the amount the user sells.
   - The answer's `buyAmount` is CoW's estimate, net of the network fee CoW
     takes out of the trade.
   - It is shown as "CoW's quote (not verified)".
2. **The order is built here**, never taken from the API. Its fields:
   - `sellToken`, `buyToken`, `sellAmount` (the whole amount the user
     sells);
   - `receiver`: the account;
   - `buyAmount`: the signed minimum (*The signed minimum*, below);
   - `validTo`: the verified head's timestamp plus 5 minutes (decision 4);
   - `appData`: the hash of a pinned app-data document;
   - `feeAmount`: 0 (the API's spec, `OrderParameters`: "When creating an
     order, this should be set to zero");
   - `kind`: sell; `partiallyFillable`: false;
   - `sellTokenBalance` and `buyTokenBalance`: `erc20`.
3. **The digest** is GPv2's EIP-712 hash of that order under the domain
   `{name: "Gnosis Protocol", version: "v2", chainId, verifyingContract:
   GPv2Settlement}`.
   - The app computes the domain separator itself. Before anything is signed,
     it checks that value against the settlement contract's own
     `domainSeparator()`, read in a verified call.
   - The `Signer` port signs the digest as it is. To the signer an order is
     one more digest the domain computes, as the `SafeTx` hash is.
4. **The submission.**
   - The order's UID is computed here: digest ‖ owner ‖ `validTo`.
   - The order, its UID and its signature are recorded before anything is
     sent.
   - `POST /api/v1/orders` sends the order, its signature
     (`signingScheme: eip712`) and the full app-data document.
   - The UID the API returns must be the one computed here. Any other UID is
     a failed submission.
   - Whatever the POST returns, the order is now live (*A signed order is
     live until it is proven dead*).
5. **The fill is read on chain, never from the API**: the settlement's
   `Trade` log for the UID, found in the account's verified logs (*Proving an
   order's end*).
   - From the API, the status shows only the order's off-chain life:
     accepted, in the auction, expired in CoW's order book. These are labelled
     as CoW's.
   - CoW's report of a fill or of its amounts would be a claim about the
     chain that the light client does not prove (CLAUDE.md, *Trust*), so it
     is not shown.
6. **The approval** is of the pinned `GPv2VaultRelayer` for the exact sell
   amount, its own transaction as above, mined before the order is signed.

CoW's contracts have the same address on every network CoW runs on. The
settlement contract is not upgradeable and has been the same since 2021.

| contract | address |
|---|---|
| `GPv2Settlement` | `0x9008D19f58AAbD9eD0D60971565AA8510560ab41` |
| `GPv2VaultRelayer` | `0xC92E8bdf79f0507f65a392b0ab4667716BFE0110` |

They are pinned with their code hashes, as Uniswap's are.

### The signed minimum

CoW's estimate is net of its fee. Uniswap's quote is not: there the account
pays the gas in ETH on top. So the two are compared net.

- **The Uniswap net** is the verified Uniswap quote less that swap's expected
  network fee, converted into the bought token by a verified quote of that
  much WETH.
  - The expected fee is the swap's gas × (the next base fee + the Normal
    level's tip). It is not the fee cap, which would overstate it.
  - The gas is QuoterV2's own `gasEstimate` for the route, the figure that
    ranks the routes (*Finding a route*). `estimateGas` of the real router
    call cannot be used here: without an allowance for SwapRouter02 it
    reverts, and `CallQuoter` refuses it. On the CoW path the allowance goes
    to the vault relayer, and no state override is used
    (docs/embedding-myotis.md, *Safe transactions*).
  - Each figure errs low. QuoterV2's gas leaves out the router's own (the
    `transferFrom` and the multicall), so no overhead is added: an
    overstated fee would lower the net, and with it the floor that bounds
    what CoW may fill.
  - The conversion is a pool's answer for an exact amount, not a price.
  - When the bought token is ETH or WETH, nothing is converted.
  - When there is no route to convert through, the Uniswap net is the quote
    itself. CoW's floor is then higher.
  - When the fee is more than the swap returns, the net is zero or less, and
    no floor stands. CoW is then not offered for the swap: this follows from
    the ruling on a pair with no route (below, decision 1), whose reason is
    the same missing floor. Uniswap's review says that the fee is more than
    the swap buys.
- **The floor** is the Uniswap net × (1 − tolerance).
- **The order's `buyAmount`** is max(CoW's estimate × (1 − tolerance), the
  floor).
  - With CoW's estimate at or above the Uniswap net, the order keeps its
    whole tolerance below CoW's estimate.
  - The signed minimum is never below the floor. CoW's servers can therefore
    make an order fail to fill, but never fill it below what Uniswap would
    give.
  - An estimate below the Uniswap net means that, by CoW's own account,
    Uniswap gives more. The review says so, and says that an order signed at
    the floor may well not fill: an order that does not fill holds its tokens
    until `validTo` is final, about 18 to 24 minutes. Switching to Uniswap is
    one tap, and the choice stays the user's (owner's direction, 2026-10-22).
- **A pair with no Uniswap route** has no floor, only CoW's estimate. It is
  not offered on CoW (owner ruling, 2026-10-22, decision 1).

### The user's choice of venue

The user decides which venue each swap uses (owner's direction,
2026-10-22).

- **The choice is on Swap, compose (22):** Uniswap, verified on chain, or CoW
  Protocol, through CoW's servers.
  - It starts at the user's last choice, and at Uniswap the first time
    (owner ruling, 2026-10-22, decision 10).
  - The choice is kept on the device, in a file of its own like the display
    currency.
- **CoW is contacted only for a swap the user sends to CoW.** With Uniswap
  chosen, nothing goes to `api.cow.fi`, not even a quote (*What CoW learns*).
- **With CoW chosen, the review compares the two venues.** It shows CoW's
  estimate, marked as not verified, beside the verified Uniswap net, says
  which gives more, and signs the minimum *The signed minimum* sets.
- **CoW is not offered** for:
  - a pair with no Uniswap route, or a swap whose fee is more than it buys
    (*The signed minimum*);
  - an account whose history the log index follows only from a block after
    the finalized head, an account added minutes ago: its order's end could
    not be proven (*Proving an order's end*). CoW is offered once the
    finalized head has passed that block;
  - selling native ETH, until CoW's ETH-flow contract is pinned (*Later on
    CoW*);
  - a device whose light client cannot date a finalized block (iOS, until
    Myotis's iOS module reads a block by number: *Proving an order's end*);
  - a Safe, until its pre-signature is built (*Later on CoW*). A Safe's swap
    is its own screen (24), which has no venue choice;
  - a token an earlier CoW order of the account may still sell: one order
    per token at a time. The new one waits until a finalized block is past
    the earlier order's `validTo`, or its end is proven, even when the
    settlement already counts it filled at the head. Send and a Uniswap swap
    of the token are freed sooner, at that head read (*A signed order is
    live until it is proven dead*). The refusal opens the earlier order's
    status.

  What compose knows before anything is asked, ETH sold and the device,
  greys CoW out on the choice, and a tap says why. The rest only a quote
  can tell: after Review, the compose step says why, and the venue stays
  the user's to change.
- **CoW unavailable.** This covers the quote only: an error on the order's
  POST leaves the order live (*A signed order is live until it is proven
  dead*). When CoW's quote:
  - does not come within 10 seconds;
  - comes back as an HTTP error;
  - cannot be decoded or checked;
  - or is refused (no liquidity, an unsupported token, the account
    deny-listed),

  the compose step says why after Review, in the words of each case: "CoW
  did not answer" for no answer or an HTTP error, "CoW gave an answer this
  version does not understand" for one that cannot be decoded or checked
  (*How often CoW's API changes*, point 3), and CoW's own refusal for a
  refusal, a deny-listed account included. It offers the same swap on
  Uniswap, one tap away. Nothing switches by itself, and a venue CoW was
  greyed out for stays as shown until the user taps CoW.
  - These are the only cases that offer Uniswap: they come after the record
    of orders was read, so no earlier order of the token may still fill. A
    Uniswap swap of the same tokens beside such an order could swap twice.
- **A posted order that does not fill.** Once its end is proven (*Proving an
  order's end*), the status offers the same swap on Uniswap. Taking it is the
  user's choice, never automatic.

### A signed order is live until it is proven dead

Once a signature exists, anyone who holds it can submit the order until
`validTo`. That includes CoW after a POST that timed out, failed with a 5xx,
or returned another UID. A Uniswap swap of the same tokens while the order
can still fill could swap them twice.

- **An order with no POST attempted is the only one dropped**, with its
  signature.
  - Every attempted POST leaves the order live, whatever error the app sees
    in the end.
  - An HTTP client can retry a request it already wrote, silently, on a new
    connection: OkHttp's `retryOnConnectionFailure` is on by default, and so
    is Ktor's `HttpRequestRetry` when installed. A DNS or connect error can
    therefore follow an order CoW already holds.
  - The order POST still runs with no transparent retry, so the error the
    app sees is the one that happened. A second POST of the same order is
    refused by CoW as a duplicate of the same UID, which is harmless.
- **A live order is kept across restarts.** It sits in a record of its own
  beside `sent-transfers.json`. The record holds:
  - the order, its UID, its signature and the account;
  - the finalized block when its quote was made, at most a minute before
    it was signed, where the proof's range starts;
  - how its POST stands: attempted from before the request on, then CoW's
    answer;
  - and, once proven, its end.

  The file is written whole and replaced at once, and every kind and reason
  in it is a code of its own, so a newer build's record still reads. Only
  damage to the storage loses an order from it, and the damaged file is
  kept aside; an order lost that way holds nothing back.
- **Send and a new Uniswap swap hold back the sold amount of a live
  order**, as they do for a transfer not mined yet
  (docs/embedding-myotis.md, *Sending*).
  - The hold-back ends as soon as the order is known to have filled: its
    `Trade` is found, or `filledAmount(uid)` at the head reads the sell
    amount. From then on the balance already shows the fill, so holding it
    back would count it twice.
  - It also ends once the order can take nothing more: a finalized block is
    past `validTo`, or the order's cancellation on chain is finalized.
  - Before `validTo`, a head read of `filledAmount` is a sound hint:
    nothing can free the order's storage until `validTo` has passed.

### Proving an order's end

The proof reads no past state. The engine answers its reads from the head
(`MyotisHost` asks for `"latest"`), and a block number passed to
`VerifiedReads.getBalance` is answered from the head anyway
(docs/embedding-myotis.md). A read at the head after `validTo` proves
nothing either: `freeFilledAmountStorage` lets a settlement reset an expired
order's `filledAmount` to zero, filled or not.

So the proof is the account's own verified logs.

- **The account's index entry already catches an order's end.**
  - The engine's log index follows every account as a `Mention` entry: every
    log that carries the account's address as topic 1, 2 or 3
    (docs/embedding-myotis.md, *History*).
  - `GPv2Settlement` emits `Trade(address indexed owner, …, bytes orderUid)`
    for each fill, and `OrderInvalidated(address indexed owner, bytes
    orderUid)` for a cancellation on chain. Both carry the owner as topic 1,
    so the account's entry catches both.
- **The query** is a verified `getLogs` of the account's entry, topic 1 the
  account, for `Trade` and `OrderInvalidated`, over a range. A topic entry
  matches logs from any contract, so only those the pinned `GPv2Settlement`
  emitted count. The range runs:
  - from the block that was finalized when the order's quote was made, at
    most a minute before it was signed. A block made after that can replace
    the head of that moment, but never a finalized block;
  - to an end block, which has to be finalized.

  - The engine answers only a range it has covered (Myotis
    docs/eth-getlogs-design.md, *Serving*), and refuses one it has not
    reached yet. Below finality its entries never change.
  - Below the block an entry is followed from, the engine answers as if
    nothing came before it. So the adapter refuses a range that reaches below
    that block (`LogsReading.BeforeStart`, docs/embedding-myotis.md,
    *History*), and the proof goes through the adapter.
  - It follows that CoW is offered only to an account followed from the
    block finalized at signing or earlier (*The user's choice of venue*).
    Otherwise the order's end could never be proven.
  - A refusal is no answer, never an empty list.
  - A `Trade` carries its order's UID in its data: another order's `Trade` is
    not this one's.
- **Why the log index, not the blocks.**
  - The engine reads blocks back only so far: 512 blocks, per a reading of
    Myotis on 2026-10-16.
  - The log index reaches as far back as the account's history. Its
    head-follow appender checks each block up to finality: every block's
    receipts on its per-block path, and across a longer gap (past its
    128-block append window) a bloom filter, which never misses a log it
    matches.
  - So a pause, the app closed, or Android pausing the engine's networking
    only delays the proof.
- **The end block is one of two.**
  - *Expiry.* The first finalized block whose own timestamp, read from its
    verified header, is past `validTo`. No settlement can fill the order in a
    block past `validTo`.
    - The header's timestamp is exactly the value the settlement compares
      with `validTo` (`order.validTo >= block.timestamp`).
    - Not the finalized slot's time (genesis + slot × seconds per slot). The
      spike below found the finalized slot naming an empty slot after the
      finalized block's own, twice: 12 seconds later than its block. Under
      checkpoint semantics that pairing is harmless, as no block lies between
      the two; but `io.myotis.api` promises nothing of how it pairs the
      finalized slot with its block, and the header needs no such promise.
  - *Cancellation on chain.* The block of the account's
    `invalidateOrder(uid)`, once it is finalized. Nothing can settle the
    order after it, since a fill would overflow the maximum it writes. A
    settlement earlier in that same block is inside the range, which ends
    with that block.
    - The status of a live order offers "Cancel on chain", for a user who
      does not want to wait for `validTo`. It is a transaction from the
      account, reviewed and recorded as any (`SentAction`).
- **The outcomes.**
  - *A `Trade` found* proves the fill and gives the amount bought. The swap
    ends there, whatever the API says.
  - *No `Trade` in the whole range* proves the order never filled. The status
    then offers the same swap on Uniswap (*The user's choice of venue*).
  - *Not provable yet*: the index does not cover the range yet, or the
    engine refuses the query. This is said as such, and the proof is tried
    again as the index grows. The status offers no Uniswap swap until then.
    - The hold-back still ends as *A signed order is live until it is proven
      dead* says, once the order can take nothing more, whatever the proof.
    - A range refused for good is said as not provable, once a finalized
      block is past `validTo`. The balance shows what happened, and a new
      swap is the user's own decision. That is a range below its entry's
      start, or an entry no longer followed because the account was removed
      from the wallet: its entry went with it, and an account added again
      starts its history after the order.
    - An entry not followed yet, as at every start until the index is
      configured, is no refusal: the proof is tried again.
- **An off-chain cancellation through the API proves nothing**: a solver may
  already hold the order. It is not built.
- **The wait.** On Sepolia a block is final two to three epochs after it is
  made, 12.8 to 19.2 minutes. An order's end is proven only at a finalized
  block (owner ruling, 2026-10-22, decision 4), so the Uniswap offer after an
  unfilled order waits for `validTo` plus that range, and the status says so.
- **What the engine showed** in part 0's spike, on Sepolia on 2026-10-22,
  with the published engine library of Myotis 0.1.18. A cold start ran
  against a real CoW trade: owner `0x9fa3…da58`, block 11,968,860.
  - Synced in 33 s. The account's `Mention` entry, watched from block
    11,968,850, covered 11,968,850 to the head 3½ minutes in.
  - `getLogs` at topic 1 for `Trade` and `OrderInvalidated` answered with
    the trade's log, from `GPv2Settlement`, its data carrying the order's
    UID.
  - A query reaching below the entry's start came back `BeforeStart`, not an
    empty list.
  - The finalized block trailed the head by about 80 blocks. Its slot and
    its header disagreed by one slot, twice: the expiry rule above takes the
    header's timestamp.

  `CowOrderEndSepoliaTest` (`:data`, part 3 of the CoW plan) runs the same
  check again on the real engine, through the adapters: the pins against the
  chain's code, the finalized block's header time, the trade decoded to its
  order's UID, and the refusal below the entry's start. It needs the network
  and the engine's library, so it is skipped unless run with
  `-PmyotisLibraryDir` (CLAUDE.md, *Conventions*).
- **iOS has no finalized block's time yet.** Myotis 0.1.18's iOS module reads
  no block by number (its block reads are internal), so the iOS node reports
  the finalized block without its timestamp, and CoW's quote answers as for
  a read not verified (`CowSwapQuoting.Unavailable`): no order is signed
  that could not be dated. So the iOS light client says it dates no
  finalized block (`LightClient.datesFinalizedBlocks`), Swap greys CoW out
  there, and CoW's quote refuses (`CowNotOffered.NOT_ON_THIS_DEVICE`). CoW is
  offered on iOS once the module exposes the read.

  No read pinned to a past block is relied on. If one is ever used, a proof
  not anchored at the block asked for is no answer (CLAUDE.md, *Trust*:
  applied or refused).

### What CoW learns, and how the app talks to it

- **What CoW learns** is ruled in CLAUDE.md, *Data sources* (owner ruling,
  2026-10-22, decision 7). It learns only the swaps the user sends to CoW.
  With Uniswap chosen, CoW is never contacted.
  - For each such swap, over HTTPS to `api.cow.fi`: the account's address,
    the pair, the amounts, the device's IP address and the timing.
  - Each quote of a review with CoW chosen reaches CoW. So does a review the
    user then gives up, or switches to Uniswap.
  - For a signed order, also the order itself, its signature, `validTo` and
    UID. CoW's order book is public, and anyone can list an owner's orders
    in it.
  - The app data carries nothing beyond the required fields.
  - The IP address is not hidden for now. Tor, for one, is a question of its
    own.
- **What of CoW's answers is shown.**
  - Of an order, only its off-chain life is shown (the ruling): accepted, in
    the auction, expired in CoW's order book.
  - A fill, and every amount it moved, comes from the account's verified
    logs alone (*Proving an order's end*).
  - CoW's estimate is shown on the review, marked as not verified, so the
    user can compare the venues (*The user's choice of venue*; #144, point
    3).
- **No API key.** The base URL is pinned per `Network`.
- **Ktor** is the HTTP client (CLAUDE.md, *Platform & language direction*),
  the app's first: 3.3.3, on the same Kotlin and coroutines as the rest of
  the stack.
  - Its engines: CIO on Android and the desktop (pure Kotlin, no OkHttp, no
    `java.net.http`), Darwin (`NSURLSession`) on iOS, a mock in the tests.
  - One connection attempt, no redirect followed, no retry, 10 seconds a
    call. A POST that failed is never sent again by the client: a second
    one would be a second order only if CoW had not taken the first, which
    the app cannot know (*An order, step by step*).
- **The calls:**
  - `POST /api/v1/quote`;
  - `POST /api/v1/orders`, with the full app-data document, so no separate
    `PUT /api/v1/app_data` is needed;
  - `GET /api/v1/orders/{UID}`, for the order's off-chain life only (*An
    order, step by step*, 5).

  An off-chain cancellation (`DELETE /api/v1/orders`) would need a second
  EIP-712 signature (`OrderCancellations`) and proves nothing (above), so it
  is not used.

### How often CoW's API changes

The owner's question. The evidence is the history of
`crates/orderbook/openapi.yml` in `cowprotocol/services`, the spec the API is
served from, read 2026-10-18.

- **146 commits since 2021-11:** 3 in 2021, 32 in 2022, 26 in 2023, 31 in
  2024, 14 in 2025, and 40 in 2026 up to 2026-10-16. Most add endpoints,
  networks or fields.
- **No version to rely on.** The paths have been `/api/v1` since 2021 (only
  trades and the solver competition moved to `/api/v2`), the spec's version
  reads `0.0.1`, and changes land in place.
- **CoW usually deprecates first and removes later:**
  - `OrderClass` is deprecated and still returned, always `limit`
    (2026-09).
  - `/api/v1/trades` was deprecated when `/api/v2/trades` came (2025-12) and
    removed on 2026-10-16.
- **What would have broken a client that uses only the calls above:**
  - the app-data document (2023), after which the API must know the document
    an order's `appData` hashes;
  - the fee moving into the price (2024), after which an order is signed with
    `feeAmount` 0.

  That is about one change a year. Response fields have been removed too
  (`full_fee_amount` and `solver_fee`, 2025-01; `executed_surplus_fee`,
  2025-02), which breaks only a client that insists on them.
- **What cannot change** without a new settlement contract: the order's
  format, its EIP-712 domain and what a signature over it means.

So an API change can need an app update. The design keeps that to the CoW
route being unavailable, never to a wrong swap:

1. **Everything that matters is built and checked here:**
   - the order and its UID;
   - the domain separator, against the chain;
   - the floor, from verified quotes;
   - the fill, from the account's verified logs up to a finalized block.

   The API delivers the order and offers an estimate no one trusts.
2. **Lenient decoding of the few fields used.** Unknown fields are ignored,
   and only the fields the app reads are required. Requests carry only
   required fields.
3. **A quote that does not decode or check means CoW is unavailable.** The
   review says so before anything is signed, and offers the same swap on
   Uniswap.
   - A change shows up as "CoW gave an answer this version does not
     understand", not as silence. The next app update brings CoW back.
   - A change that breaks the POST after signing costs a wait for the order
     to be proven dead, never a second swap.
4. **A network test, skipped by default** (CLAUDE.md, *Conventions*):
   `CowApiSepoliaTest` (`:data`, run with `-PcowApiNetwork`) quotes against
   `api.cow.fi/sepolia` through the desktop's own engine and decodes the
   answer, and asks for an order CoW does not know. Whether a scheduled
   workflow runs it and watches `openapi.yml` for changes to the calls above
   is the owner's call.

### Later on CoW

- **A Safe on CoW** signs with a pre-signature: Safe transactions approve
  the vault relayer and call `setPreSignature(uid, true)`, then the order is
  posted with `signingScheme: presign` and the Safe as its owner.
  - It is not gasless.
  - The order's validity has to cover the co-signing, as *A Safe's swap*
    says for its deadline.
  - An EIP-1271 signature would need a fallback handler, which Serotine's
    Safes do not have (docs/wallet-ux.md, *Safes*). Only the pinned
    `CompatibilityFallbackHandler` may be set (2026-10-18, issue #63), and
    no screen sets it yet (issue #72).
- **Selling native ETH** goes through CoW's ETH-flow contract: an order
  placed on chain with the ETH as its value. Buying native ETH needs nothing
  extra: the buy token is `0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE`.

## The plan

Two multi-PR plans, the second after the first, so that Uniswap swaps can
reach `main` without waiting for CoW's ruling (CLAUDE.md, *Multi-PR plans*:
the final PR opens only when every planned part is merged). Both branch from
`main`, now that `feature/wallet-ux` has merged. Two plans, not one (owner
ruling, 2026-10-19, decision 9). Each part follows the checklist in
docs/architecture.md.

**`feature/swaps-uniswap`**

1. **Domain.**
   - The pin type, and the ABI of `getPool`, `liquidity`, `slot0`, QuoterV2,
     `SwapRouter02.multicall`, `exactInputSingle`, `exactInput` and
     `unwrapWETH9`, and ERC-20's `approve` and `allowance`.
   - The route candidates and their choice, and the slippage arithmetic.
   - Vectors made with Foundry's `cast`.
2. **Adapters.**
   - The port `UniswapV3Chain` (the venue once its code is proven, and
     verified calls) and the use case `FindUniswapRoute`, with fakes in
     their tests.
   - The Sepolia pins with their code hashes, and the engine adapter
     `VerifiedUniswapV3`.
   - Which built-in tokens have pools on Sepolia, what a quote touches, and
     a network test that times a route through the engine.
3. **The swap's quote and send.**
   - `QuoteSwap`, `SendSwap` (the exact transaction run again at the tap
     that signs it), `QuoteTokenApproval` (zero first for a token that
     refuses one allowance for another) and `SendTokenApproval`.
   - `SentAction`'s token approval and swap, recorded (the sent
     transactions' format 5), with what a swap sells held back from later
     transactions, and the status (9) for both.
4. **Swap screens.**
   - Swap, compose (22): from, sell, buy, amount, tolerance, fee.
   - Swap, review (23): the approval step, then the swap; an approval sent
     returns to 22, which says so and goes on once a block has it.
   - A Swap button on the account detail (4) and "Swap ‹symbol›" in the asset
     menu (5), greyed out, a tap saying why, when watch-only (owner ruling,
     2026-10-18, #55), and on a Safe until part 8 gave it its own swap.
   - The port and use cases wired into `AppGraph` and the hosts.
5. **The review's last figures.**
   - The price impact: each pool's `slot0` against the quote.
   - The Chainlink hint, display-only.
   - The amount received, from the verified receipt's logs, on the status (9).
6. **A Safe's swap on screen 17.**
   - The approval and the swap decoded from their raw calls: a swap exactly
     as `SwapRouter02.swap` builds one, paid to the Safe, read by building it
     again.
   - Whether the spender or router is the pinned SwapRouter02 (the verified
     venue), what the swap returns now by a verified QuoterV2 quote against
     its minimum, and its deadline against the verified head.
   - A minimum fallen behind the market said at Execute, with what it leaves
     open.
7. **Proposing a Safe's swap, the use case.** `ProposeSafeSwap`: the
   approval and the swap at consecutive nonces, or the swap alone, with the
   minimum and a 24-hour deadline fixed at the proposal, each run from the
   Safe; and the swap proposed again at the nonce of one whose minimum has
   fallen behind.
8. **Proposing a Safe's swap, the screens.** Swap on a Safe's detail (4,
   5), its compose and review (24), signed by an owner held here (both
   transactions); and 17's offer to propose a swap again with a fresh
   minimum.

**`feature/swaps-cow`** (issue #144), from `main`. The owner answered yes to
its part PRs being merged by their author on 2026-10-22, in that session's
conversation: plan history, not a standing authorization. A session resuming
the plan asks again (CLAUDE.md, *Multi-PR plans*, step 3).

0. **The design.** This page and CLAUDE.md take the direction of 2026-10-22
   (the user's choice of venue), CoW's *Data sources* ruling (decision 7) and
   the proof of an order's end from the log index, shown on Sepolia by a
   spike (*Proving an order's end*).
1. **The order, in the domain.**
   - The GPv2 order, its EIP-712 digest and UID, and the pinned app-data
     document.
   - The Uniswap net, the floor and the signed minimum, as pure arithmetic.
   - The ports: CoW's API, the record of live orders, and the chosen venue;
     and the finalized block with its header's timestamp, which the light
     client's status does not carry yet.
2. **The use cases.**
   - CoW's quote and the order's placement.
   - The proof of an order's end, from the account's log-index entry.
   - The cancellation on chain, as a `SentAction`, stored and listed in
     Activity.
3. **Adapters and wiring.**
   - The pinned `GPv2Settlement` and `GPv2VaultRelayer` with their code
     hashes.
   - Ktor, the app's first HTTP client, and the three calls, decoded
     leniently, with no transparent retry of the order POST.
   - The record of live orders and the chosen venue, each a file of its own.
   - The finalized block and its header's timestamp from the engine, and an
     order's `Trade` and `OrderInvalidated` from the log index.
   - The hold-back of a live order in Send's and the Uniswap swap's quotes
     (it needs the record's file, so it moved here from part 2).
   - The ports wired into `AppGraph` and the hosts, which run the proof of
     an order's end beside `FollowAccountLogs`.
   - Network tests, skipped by default: a quote from `api.cow.fi/sepolia`,
     and part 0's spike again (*Proving an order's end*).
   - As built: no CoW on iOS until Myotis's iOS module reads a block by
     number (*Proving an order's end*); the records of orders and the venue
     are `cow-orders.json` and `swap-venue.json`; an ended order's record is
     dropped a week after it was signed.
4. **The Swap screens.**
   - The venue choice on Swap, compose (22): "Swap on" Uniswap or CoW
     Protocol, starting at the last choice (`ObserveSwapVenue`,
     `ChooseSwapVenue`). CoW greyed out for ETH sold, a tap saying why.
   - CoW's review on 23: its estimate marked as not verified beside the
     verified Uniswap net, which gives more, the minimum signed and how it
     is set, until when the order may fill, the settlement contract, and
     what CoW learns. The vault relayer's approval first when needed, as
     the router's is.
   - Signing places the order (`PlaceCowOrder`), and the screen says how CoW
     answered.
   - When CoW cannot quote, the compose step says why in the words of each
     case and offers "Swap on Uniswap instead". Nothing switches by itself.
     It is not offered while an earlier order of the token may still fill.
   - The hold-back worded on Send and Swap: their notes and refusals count
     what a live order may still take.
   - Previews and tests.
5. **The order's status.**
   - The status (9) of an order, a screen of its own (`cow-order/{uid}`),
     opened after placing it, from Swap's refusal while it is live, and
     from Activity. It shows its end as the chain proves it (live, filled
     with what it bought, not filled, cancelled, not provable). While it is
     live it also shows CoW's word on its off-chain life, asked every 30
     seconds and labelled as CoW's (`ObserveCowOrderLife`).
   - "Cancel on chain" while it may fill (`QuoteCowCancellation`,
     `SendCowCancellation`), reviewed and signed as any transaction, and
     the cancellation's own status (9).
   - Once it is proven not to have swapped (not filled, or cancelled),
     "Swap on Uniswap" opens Swap with the same swap filled in, on Uniswap,
     still the user's to review.
   - The orders in the account's Activity (25), among its transactions: a
     live one on top, as a transaction waiting for its block; a filled or
     cancelled one at the block of its fill or cancellation; one never
     filled where it was signed (the block that proved it can come days
     later). A tap opens the order's status. A fill's own transfer of what
     it sold is not listed again.
   - Previews and tests.

Not part of either plan, and plans of their own when the owner wants them: a
Safe's pre-signed CoW order, CoW's ETH flow, and Uniswap v4.

## Open questions

The owner's decisions, each with a recommendation first. They are recorded
on issue #69 (and CoW's on #144) until answered. This page cites each as
"decision N", by its number here; #144 numbers its four in its own order.

1. **A pair with no verified Uniswap quote.** *Answered (owner ruling,
   2026-10-22):* not offered on CoW, since no verified floor stands.
2. **Tolerance.** *Answered (owner ruling, 2026-10-19):* 0.5 % by default,
   between 0.1 % and 5 %, and no second confirmation for a large price
   impact for now.
3. **Approvals.** *Answered (owner ruling, 2026-10-19):* the exact amount,
   one approval per swap; not unlimited, not Permit2.
4. **A CoW order's validity, and the wait.** *Answered (owner ruling,
   2026-10-22):* 5 minutes, and an order's end proven at a finalized block,
   12.8 to 19.2 more minutes on Sepolia, before the same swap is offered on
   Uniswap.
5. **A Safe's swap deadline, and a minimum that has fallen behind.**
   *Answered (owner ruling, 2026-10-19):* 24 hours. At Execute, when the swap
   returns more than its minimum by over the tolerance, warn and offer to
   propose it again; it may still be executed as signed.
6. **Uniswap v4 hooks.** Pools without hooks only, or pinned hooks?
7. **CoW's data-source ruling.** *Answered (owner ruling, 2026-10-22),
   recorded in CLAUDE.md, Data sources:*
   - CoW learns only the swaps the user sends to CoW;
   - the app data carries nothing beyond the required fields;
   - the IP address is not hidden for now;
   - from CoW's answers, only the order's off-chain life is shown, and fills
     come from the chain. The review also shows CoW's estimate, marked as
     not verified, for the comparison #144's point 3 describes.
8. **Mainnet**, once allowed: a Uniswap swap in the public mempool, bounded
   by its minimum, with no private relay.
9. **One plan or two.** *Answered (owner ruling, 2026-10-19):* two. Uniswap
   reaches `main` on `feature/swaps-uniswap`, and CoW follows on
   `feature/swaps-cow` once its ruling is made.
10. **The venue preselected on Swap, compose.** *Answered (owner ruling,
    2026-10-22):* the user's last choice, Uniswap the first time.

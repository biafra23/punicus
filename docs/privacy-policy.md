# Privacy policy

Punicus is a wallet app for Ethereum accounts and Safe accounts. It runs on
your device. This page says what it keeps there and what reaches anyone else.
Its developer runs no server for Punicus and receives nothing from the app.

Last updated: 2026-11-14.

## What Punicus does not do

- **No account, no sign-up.** Punicus asks for no name, email address or
  phone number.
- **No telemetry, no analytics, no crash reports.** The app sends nothing
  about how you use it, or about its crashes, to its developer or to anyone
  else.
- **No servers of its own.** Punicus has no backend, so there is no database
  of its users anywhere.
- **No RPC provider, no indexer, no price API.** Balances, prices, names and
  history come from the light client built into the app (Myotis), which
  verifies what it reads (docs/embedding-myotis.md).
- **No ads, and nothing sold or shared.** The app shows no ads and has no
  data to sell.

## What stays on your device

- Your accounts: their addresses, names and networks, and your settings.
- Your keys and recovery phrases, encrypted. Each key needs your wallet
  password and a secret of the device's hardware key store to open, where the
  device has one (docs/keys-and-signing.md).
- The light client's own state, the balances it last verified, the record of
  what you sent, and your dApp sessions.

These files are private to the app. Keys and session secrets are kept out of
cloud backups and device-to-device transfers. Removing the app deletes them.
The Debug tab's log, when you turn it on, stays in memory and is never
written to a file.

## What others can see

Using a blockchain means talking to other computers. These are the parties
Punicus talks to, and what each of them learns.

### Ethereum and Gnosis Chain peers (always)

The light client reads the chain from other nodes of the peer-to-peer
network (devp2p and libp2p), as every Ethereum node does. They are not run
by Punicus.

- They see your device's IP address.
- A peer that serves a read learns which account, contract or block it is
  about. Several reads at about the same time from one IP address could let
  a peer link your accounts to each other.
- When you send a transaction, the peers it goes to receive it, as every
  transaction is public once sent.
- Following an account's history fetches the receipts of the blocks that
  mention it, which tells the serving peer which blocks matter to you.

To find peers, the light client also uses your network's DNS servers. They
see those lookups, not your accounts.

### Checkpoint servers (only if you ask for a fresh checkpoint)

A light client starts from a trusted checkpoint. When the one it has is too
old to catch up from safely, Punicus stops verifying that network and asks
you what to do. If you choose to fetch a fresh checkpoint, the light client
asks that network's public checkpoint-sync servers which block the chain
finalized last.

- They see your IP address and that you asked for a checkpoint of that
  network.
- They learn no account, balance or transaction.
- They are not run by Punicus. Unless you ask, they are never contacted.

### The WalletConnect relay (only if you connect a dApp)

When you connect to a dApp with WalletConnect, messages between the dApp and
Punicus pass through WalletConnect's relay (run by Reown).

- It sees your IP address, the times and sizes of messages, and the
  connection's identifiers.
- The messages are end-to-end encrypted. The relay cannot read them, so it
  does not learn your addresses or what you sign.
- WalletConnect's optional services stay off: no verification service, no
  push server, and dApp icons are not loaded.

The dApp itself learns the accounts you choose to share with it, and what it
asks you to sign. It is not run by Punicus.

### CoW Protocol (only for a swap you send to CoW)

Swaps go through Uniswap by default, with verified on-chain calls and no
service in between. If you choose CoW Protocol for a swap, its order API
learns your address, the tokens, the amounts and your IP address, for that
swap. With Uniswap chosen, CoW is never contacted (docs/swaps.md).

### Another wallet app (only if you hand a transfer to one)

On Android you can hand a transfer to another wallet app on the device as a
payment link. That app and its own provider then learn the transfer and your
IP address. Their privacy policies apply.

## Your choices

- Turn off a network in Settings → Networks: its light client stops, and its
  peers hear nothing more from you.
- Leave dApps unconnected, and the relay is never contacted.
- Catch up from an old checkpoint instead of fetching a fresh one, and the
  checkpoint servers are never contacted.
- Keep Uniswap as the swap venue, and CoW is never contacted.
- Remove an account or the app to delete what is stored for it.

## Children

Punicus is not directed at children and collects nothing about anyone.

## Changes

A change to this policy is made in this file, in the public repository, and
its history is in the repository's commits.

## Contact

Questions: open an issue at <https://github.com/biafra23/punicus/issues>.

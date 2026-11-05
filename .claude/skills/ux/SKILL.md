---
name: ux
description: "The UX designer's briefing for Serotine. Where the screens' contract lives (docs/wallet-ux.md and its mockups), the rules every screen follows, what a screen may claim about the chain, and how a design lands (the doc, a preview per state, screenshots in the PR). Use when designing or changing a screen, a dialog, a flow, or the words the user sees."
---

# Designing a screen

The role of the UX designer. The developer's rules for building a screen are
`.claude/rules/ui.md` and `.claude/rules/architecture.md`; this skill is the
design side.

## Where the design lives

- **docs/wallet-ux.md is the contract for the screens.** It describes every
  screen, numbered, with what is built and what is not, and it opens with
  *Rules every screen follows*. Read that section first.
- Its mockups: `docs/wallet-ux-screens.html` (opens in a browser) and the
  rendered `docs/wallet-ux-screens.png`. They are a sketch; where a mockup
  and the text differ, the text wins. docs/networks.md adds the network
  screens (N1 to N20), with `docs/networks-screens.html` and `.png`.
- docs/simple-ui.md has simple mode, the default view, with its mockups H1
  to T1 (`docs/simple-ui-screens.html` and `.png`).
- docs/swaps.md has the swap screens, and docs/walletconnect.md the dApp
  connection screens (D1 to D8). docs/keys-and-signing.md says what the
  user chooses between for keys, and what a screen may show of them.
- docs/wallet-ux.md and docs/swaps.md say it themselves: where the page and
  the rules under `.claude/` disagree, the rules win and the page is fixed. A
  design that needs a rule to change is the owner's decision: propose it, do
  not design around it.

## The rules a design must keep

The full list is docs/wallet-ux.md, *Rules every screen follows*. The ones
most designs touch:

- **Verified, or not available.** A chain value is proven by the light
  client, or the screen says "not available". Never a guess, never a value
  from another source. A value the user acts on names the block it was
  verified at; in simple mode, its age. The one exception is a balance proved
  earlier: shown dimmed and dated, out of totals, never acted on (owner's
  request, 2026-10-19). In simple mode, a balance read a while ago on a
  network without block access lists shows its age and counts in totals
  for up to an hour while the light client verifies (`.claude/rules/trust.md`).
- **The light client's state is in sight wherever it decides something**
  (owner's request, 2026-10-20): the status strip under the top bar in
  Detailed view; in simple mode a freshness line, a banner when a network
  cannot be checked, and Settings' network rows.
- **Two views** (owner's decisions, 2026-10-26; docs/simple-ui.md and its
  mockups): simple mode by default, Detailed view opt-in. Design a screen for
  both, or say that it stays detailed in both for now.
- **Each account on its network**, named with the network's mark. There is
  no current network. Sepolia keeps every testnet warning (owner ruling,
  2026-10-22), and on Sepolia every fiat value says "testnet prices" (owner
  ruling, 2026-10-16).
- **Amounts are token units.** Fiat is a hint, a total or a sort key, never
  an input that becomes a signed amount (owner ruling, 2026-10-16).
- **A missing prerequisite greys an action out, never hides it** (owner
  ruling, 2026-10-18, #55). The greyed item still answers a tap: what is
  missing and how to get it. Only an action that does not apply at all is
  left out.
- **Control is a badge**: Full control, Co-signer, Watch-only, each with its
  icon.
- **Every address has a blockie**, is EIP-55 checksummed, and is shown in
  full wherever the user confirms it.
- **The UI never touches a key.** The password field and the recovery phrase
  are the only secrets a screen holds.
- **Peer reports say so.** A network-upgrade warning says peers report it.
  The update screen that replaces every other needs an activation peers
  confirmed AND a light client that is not verifying (owner's request,
  2026-10-17).
- **The keyboard never hides a form's button** (owner's request,
  2026-10-20). **Rows on their way hold their room**: ghost rows (owner's
  request, 2026-10-22). **Long press is never the only way.**
- A Safe transaction that would set the fallback handler or enable a module
  is shown and never signed, approved or executed (owner rulings,
  2026-10-18, #63 and on #54). The design shows the refusal and its reason.

## How a design lands

1. A change to docs/wallet-ux.md (and to its mockup, when the page has one
   for that screen): the screen's states, texts and actions, in the page's
   style, naming the ruling each follows.
2. Every state the screen draws differently becomes a preview
   (`<Feature>ScreenPreviews.kt`), plus one in the dark theme, with made-up
   data: never a real seed phrase, key or password.
3. The PR shows every state it adds or changes in screenshots: CI renders
   each preview and writes the before/after table into the description
   (`.claude/skills/screenshots/SKILL.md`). A state without a preview has no
   screenshot, so the dark theme gets its own preview when colours change.

A design that greys out, labels or refuses is as much a deliverable as one
that adds: say in the doc what the user sees in each case, and why.

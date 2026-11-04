---
description: "How to change these instructions and the docs: what is the owner's, how rulings are kept, where a new rule or skill goes, the Myotis sibling, the heading pointers."
paths:
  - "CLAUDE.md"
  - ".claude/**/*"
  - "docs/**/*"
---

# Changing these instructions and the docs

Loaded when you edit CLAUDE.md, a file under `.claude/`, or a doc.

## What is the owner's

- A project rule is changed by the owner, never by a PR author. A review
  comment that asks for a rule change gets the verdict *Deferred*.
- An owner ruling keeps its date and its issue number wherever it is quoted.
  Never drop, merge or reword a ruling so that its meaning changes. Shorter
  wording with the same meaning is fine.
- An open decision is recorded as open, with who decides. It is recorded as
  decided before its code lands.

## The layout

- `CLAUDE.md` is always loaded. It holds the short form of every rule, under
  the section headings the code comments and docs cite by name:
  *Architecture*, *Conventions*, *Workflow*, *Multi-PR plans*, *Releases*,
  *Pull requests and code review*, *Platform & language direction*, *Trust*,
  *Data sources*. Keep those headings: about a hundred code comments and docs
  say "CLAUDE.md, *Trust*" and the like. Keep the file short: Claude Code's
  documentation names about 200 lines as the target, since adherence drops
  as a file grows, and this one is already past it. Add a line only when
  every session needs it; otherwise it goes in a rule or a skill.
- `.claude/rules/<area>.md` holds the full text of one area's rules. Its
  frontmatter `paths:` lists the globs, relative to the repository root, that
  load it. A rule without `paths:` is always loaded, so every rule here has
  one. `paths` is the only frontmatter key Claude Code reads; the
  `description` is for the human reader. If the YAML does not parse, the rule
  loads unconditionally.
- `.claude/skills/<name>/SKILL.md` holds one task's procedure or one role's
  briefing. Frontmatter: `name`, and a `description` that says what it does
  and when to use it, since the model picks the skill from that text. Keep a
  skill under 500 lines; put reference material in files beside it.
- A new rule goes in the file of its area, with one line in CLAUDE.md if
  every session must know it. A new procedure goes in a skill. Both quote
  the ruling that made them.

## The docs

- docs/architecture.md and docs/keys-and-signing.md are the contracts. The
  rule files summarize them; where a summary and its contract disagree, the
  contract wins and the summary is fixed.
- docs/wallet-ux.md and docs/swaps.md apply the rulings to the screens and
  the swaps, and say so themselves: where such a page and the rules under
  `.claude/` disagree, the rules win and the page is fixed.
  docs/embedding-myotis.md, docs/networks.md and docs/walletconnect.md
  record their areas the same way.
- A doc records an owner ruling with its date, as the rules do.

## The Myotis sibling

The workflow, PR, review and release rules are Myotis's, copied with their
rulings and dates. Myotis carries them in its one CLAUDE.md in the original
wording. A change to a shared rule is made in both repositories. Several
workflow files are inherited verbatim from Myotis and say so in their
headers.

## Language

Everything in these files is English, as is everything that lands on GitHub
(owner ruling, 2026-09-17). Write short sentences, one rule per bullet, the
rule before its reason, and the ruling's date and issue at the end.

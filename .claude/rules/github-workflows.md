---
description: "The invariants of the GitHub workflows and scripts: unscoped PR triggers, the artifact builds' base-branch skip, the Claude reviewer and what it reads, how fork PRs are reviewed, the release guards, the minSdk check."
paths:
  - ".github/**/*"
  - "scripts/**/*"
---

# Workflows and scripts

Loaded when you work in `.github/` or `scripts/`. Each workflow file carries
its own rationale in its header; read the header before changing the file.
Several files are inherited verbatim from biafra23/myotis and say so. Keep the
two repositories in step when one changes.

## Triggers

- **Every PR workflow's `pull_request` trigger is unscoped** (any base
  branch). A part PR of a multi-PR plan targets its feature branch and still
  needs Build and Test (`ci.yml`) and the Claude review. Do not narrow them
  to `main`.
- **The artifact builds are the exception** (owner ruling, 2026-10-09): the
  Android APKs, the macOS dmg and the Windows msi skip a PR whose base is not
  `main`, through a job-level `if`, so the checks show as skipped, not
  absent. Why, what a part PR does about it, and what a retargeted PR must
  do: `.claude/skills/feature-branch/SKILL.md`, *What the workflows do with
  part PRs*.
- Build and Test and the other push triggers fire only on `main` and on `v*`
  tags. A clean `main` merge into a feature branch is first fully tested by
  the next PR into or out of the feature branch.
- A job that calls `wait-for-claude-review.yml` may carry an `if:` only if it
  is true on every event but `pull_request` (that file's header says why).

## The Claude reviewer

`claude-review.yml` and `.github/claude-review-prompt.md`.

- It reviews once per PR (opened, reopened, ready for review), not on every
  push. A re-run replays the original commit. Close and reopen the PR for a
  fresh review.
- It reads its prompt file from the PR's base commit, never from the PR's
  checkout. claude-code-action restores the root `CLAUDE.md`,
  `CLAUDE.local.md` and `.claude/` from the base branch's tip (as its
  docs/security.md documents). That is why the agent rules and skills live
  under the root `.claude/`: a PR cannot rewrite what its own reviewer reads.
  A `CLAUDE.md` or `.claude/rules/` in a subdirectory is NOT restored.
- A PR that edits `claude-review.yml` cannot review itself: the action skips,
  and the job goes green with a warning. The same happens on any branch whose
  copy of that file differs from `main`'s, such as a feature branch that has
  not merged a change to it. A green `claude-review` job alone is not a
  review.
- `wait-for-claude-review.yml` holds the artifact builds until the review has
  finished. It is fail-open.
- The reviewer's prompt opens "Read CLAUDE.md first". The author-side rules
  (`.claude/skills/pr`) are not addressed to it, and it could not follow them
  anyway: `gh api` is not on its allowlist.

## Fork PRs are reviewed manually, never by the CI reviewer

(Owner's decision, 2026-08-15.)

- The workflow skips forks (`head.repo.full_name == github.repository` in its
  `if:`). A `pull_request` run from a fork gets no secrets. The deeper reason
  is trust: a fork PR's tree is attacker-controlled, and an automated
  reviewer that reads it can be prompt-injected.
- A PR can no longer rewrite the reviewer's prompt file or the root
  instruction files, which are read or restored from the base. That does not
  make a fork tree safe to hand the reviewer: whoever opens a PR picks its
  base, instruction files below the repo root are not restored, and the diff
  itself is always attacker text.
- So do NOT wire fork PRs into the automated reviewer. Instead:
  1. The owner reads the diff first, watching for injection in `CLAUDE.md`,
     `.claude/**`, `.github/**`, build scripts, postinstall hooks, comments
     and encoded blobs.
  2. If wanted, a review is run manually from a trusted session restricted
     to read, diff and comment tools. It never builds or runs the fork tree:
     merely `./gradlew ...` executes fork-controlled build scripts locally.
     CI already builds fork PRs through the unscoped `pull_request` in
     `ci.yml`, but secretless and sandboxed; a local session is neither.
- Held to that, nothing auto-merges, and an injected reviewer's worst case is
  a comment a human still acts on.
- If automating fork review is ever reconsidered: pin the prompt and the
  instruction files to the base ref, never execute fork code, keep reviews
  `COMMENT`-only, and lock down runner egress. Myotis PR #375 has the
  discussion that produced this note.

## Releases

- `release-version-guard.yml` is called by every workflow that uploads to a
  release. It accepts `vX.Y.Z` and `vX.Y.Z-<suffix>` (rc and hotfix shapes),
  nothing else, and refuses the tag before anything is uploaded unless
  `./gradlew -q :printReleaseVersion` prints the tag's version core.
- `sign-release` (android-apk.yml) signs the release APK on a tag with the
  key in the `android-release` environment, and only for a tree with
  `android-app/release-signing-cert.sha256`. Without that pin a tag publishes
  the desktop installers and the debug APK, and no release-signed Android app
  (owner's request, 2026-10-21). With the pin, a signing failure publishes no
  Android asset at all.
- Every `v*` tag publishes a pre-release. Each workflow creates the release
  race-tolerantly and uploads its assets. The Windows msi is left out of tags
  until Myotis ships a Windows engine library (owner's decision, 2026-10-21):
  it builds on `main`, and a dispatch on a tag publishes it.
- The procedure for cutting a release: `.claude/skills/release/SKILL.md`.

## Scripts

- `scripts/check_apk_min_api.py` enforces the minSdk 29 JDK-API budget
  against the built APKs' dex. When it runs, and what may never be
  allowlisted: `.claude/rules/platform.md`, *The minSdk 29 JDK-API budget*.
- The APK workflow also checks the built manifests for the preview tooling's
  `PreviewActivity`, which must stay out of every APK (`.claude/rules/ui.md`,
  *Every screen has previews*).

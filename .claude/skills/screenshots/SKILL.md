---
name: screenshots
description: "How a PR's screenshots come about: CI renders every screen's previews into docs/screenshots/<screen>/<state>.png, commits the changed ones to the PR's branch and writes a before/after table into the description. What the author does (a preview per state, the map of screens, the Screenshots section, merging CI's commit before the next push), what never to do (commit a PNG there yourself), how to look at them locally, and what each failure of the workflow means. Use when a PR changes what the user sees, adds or renames a screen or a state, or when the Screenshots check fails."
---

# Screenshots

(Owner rulings, 2026-11-04. They replace the ruling of 2026-10-19 that no
screenshot enters the repository, and with it the routes it named: a
browser logged in to GitHub, one Artifact per PR, the session.)

What the owner decided that day:

- **It works from every kind of session**, local, SSH and cloud. "There is
  no way around that requirement."
- **The screenshots live in the repository, in one folder that can be
  purged if it grows too much:** `docs/screenshots/`. Any git client sees
  them on the commits that changed them, not only GitHub.
- **The same screen is always at the same path**, through a map of screen
  names: `ui/src/desktopTest/kotlin/eth/serotine/ui/Screens.kt`.
- **Every screen is rendered**, not only those a PR touches, so the images
  double as goldens. They are kept as small as possible, and one HTML page
  shows them all (`docs/screenshots/index.html`).

## What CI does

The Screenshots workflow (`.github/workflows/screenshots.yml`; its header
has the details) runs on every PR:

1. It renders every preview into `docs/screenshots/<screen>/<state>.png`,
   with the folder's `README.md` and `index.html`.
2. It optimizes the PNGs that changed, losslessly, and fails a folder over
   its size budget before anything is committed (`scripts/screenshots.py`).
3. It commits them to the PR's branch as `github-actions[bot]`, pushed with
   the `SCREENSHOTS_DEPLOY_KEY` deploy key, so CI runs again on the new
   head. That run finds nothing to commit.
4. It writes the before/after table into the description, between
   `<!-- screenshots:start -->` and `<!-- screenshots:end -->`, under
   `## Screenshots`: one row per screenshot added, changed or removed since
   the branch left its base.

On `main` it fails when the committed set differs from the render.

## What the author does

- **A preview per state** (`.claude/rules/ui.md`, *Every screen has
  previews*). Each entry of a previews list is
  `ScreenPreview("<state>") { … }`, and the name is the file: lowercase
  words joined by hyphens, a dark twin ending in `-dark`.
- **A new screen is registered in `Screens.kt`** with its slug, the folder's
  name. `ScreensTest` fails until it is.
- **A slug or a state name, once on `main`, stays.** Renaming one moves its
  image, and the table shows it as one removed and one added.
- **The `## Screenshots` section** holds one line in your words: what the
  PR changes on screen, or "No UI change". CI writes its table under the
  heading. Never edit between the markers: the next run rewrites them.
- **Merge CI's commit before you push again.** After a push, CI may add a
  commit `Screenshots: …` to the branch. Run `git pull --no-rebase` before
  the next push. Never force-push over it.
- **Never commit a PNG under `docs/screenshots/` yourself.** CI's render is
  the only source: a render on another machine draws other fonts.

The table is the check: a PR that says "No UI change" while its table lists
changed screenshots, or a changed screen whose table lacks a state the PR
adds, is a finding.

## Looking at them locally

```bash
# Every screen into build/screenshots/ (git-ignored), with README.md and index.html
./gradlew :ui:desktopTest --tests '*PreviewsTest' --tests '*ScreenshotIndexTest' --rerun -Pscreenshots=build/screenshots
```

One screen: `--tests '*.<Feature>ScreenPreviewsTest'`. The committed set is
in `docs/screenshots/`; open its `index.html` from a checkout. An older
commit's image: `git show <rev>:docs/screenshots/<screen>/<state>.png`.

## When the Screenshots check fails

| The message says | What it means, and what to do |
|---|---|
| this run cannot push the render | A fork PR, or the `SCREENSHOTS_DEPLOY_KEY` secret is not set. A maintainer renders on a branch of this repository, or the owner sets the secret. |
| a preview is not deterministic | The render differs right after CI's own commit. A preview draws the clock or randomness: give it fixed sample data. |
| the push was rejected | The branch moved while the run worked. The run on the new head renders again. |
| over its budget | The folder, or one image, is too large. The way out is in the message and in the folder's README. |
| does not match the render of main | A PR merged before its run had committed. Open a PR from `main`; its run commits the render. |

## The deploy key

`SCREENSHOTS_DEPLOY_KEY` is an Actions secret holding the private half of a
write deploy key of this repository; the public half is under Settings →
Deploy keys. Creating, rotating or removing it is the owner's action. A
push with `GITHUB_TOKEN` would start no workflow, which is why a key is
used. `main` has no branch protection today, so the key could push there
too; a rule that lets only PRs into `main` would confine it to PR branches.

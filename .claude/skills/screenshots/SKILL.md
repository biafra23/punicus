---
name: screenshots
description: "Render the screens' previews to PNG files and show them for a PR that changes the UI. What to show (every state the PR adds or changes, before and after side by side, the dark theme), how to render them headless, and where they go (the PR description, one artifact per PR, or the session), never the repository. Use when a PR changes what the user sees."
---

# Screenshots for a PR that changes the UI

(Owner's request, 2026-10-19, and the owner's ruling of the same day on
where they go.)

**A PR that changes the UI shows it in screenshots**: in its description,
or, from a session that cannot upload them, in the session. "Changes the UI"
means a user could see the difference: a screen, dialog or component drawn
differently, a new or changed text, state, colour or layout, a new screen. A
ViewModel-only change counts when it changes what a screen shows. A PR with
no visible change says "No UI change" in place of the screenshots, so a
reader can tell an omission from a decision.

## What to show

- Every state the PR adds or changes, each under a caption naming the screen
  and the state.
- For a changed screen, before and after side by side (a two-column table).
  The before is taken on the base branch.
- The dark theme too, when the change touches colours or the theme.
- Made-up data only, as in the previews: never a real seed phrase, key or
  password, and no account of the owner's.

## How to take them

The screen's previews are the source (`.claude/rules/ui.md`, *Every screen
has previews*), rendered headless, so a session with no display or device
can take them too.

```bash
# One screen (…DialogPreviewsTest for a dialog). Leave --tests out for every screen.
./gradlew :ui:desktopTest --tests '*.<Feature>ScreenPreviewsTest' --rerun -Pscreenshots=build/screenshots
```

It writes each preview of that screen to
`build/screenshots/<feature>Screen-<nn>.png` (`…Dialog-<nn>.png` for a
dialog's), nn the two-digit position in the previews list counting from 01
(`RenderPreviews.kt`), replacing that screen's files from an earlier run. A
state the PR adds gets a preview first, which the previews rule asks for
anyway. A capture of the running app (`:desktop-app:run`, the debug Android
app) may stand in or be added where the previews cannot show the change
(navigation, an animation, a host's window or notification).

## Where to put them

**In the PR description only, never in the repository** (owner ruling,
2026-10-19). No screenshot is committed anywhere: not on the PR's own branch
(binaries merged into `main` stay in its history for good), not on a branch
of its own, not in a commit removed again later. `build/screenshots/` is
git-ignored and stays so.

Use the first of these your session can do:

1. **A browser logged in to GitHub** (the desktop app's browser, on the
   owner's computer). GitHub takes an image into a description only from
   such a browser; the API offers no upload. Drag the files into the
   description's `## Screenshots` section yourself: each under its caption,
   before and after side by side for a changed screen.
2. **The session's Artifact tool** (owner ruling, 2026-10-19). ONE artifact
   per PR: a page with every screenshot under its caption, before and after
   side by side for a changed screen, titled with the PR's number and
   subject. Give its link in your reply. The description's `## Screenshots`
   section gives the same link (private to the owner; the owner's artifact
   gallery lists it too) and the command that renders the screenshots, with
   no table of file names in place of the images. A push that changes what
   the screenshots show republishes that same artifact in the same round, so
   its link stays and its history keeps the earlier versions.
3. **Neither a browser nor an Artifact tool:** attach the files to the
   conversation, each named after its screen and state
   (`accounts-head-too-old-after.png`) with its caption, and the section
   says they were shown in the session.
4. **A host where the files reach no one** (CI, a plain `claude` terminal):
   the section lists each state under its caption, with the
   `<feature>Screen-<nn>.png` it renders to, and says the images await
   rendering with the command above: the after images on the PR's branch,
   the before images on the base branch.

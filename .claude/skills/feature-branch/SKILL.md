---
name: feature-branch
description: "How a work item of more than one PR lands. A feature branch from main, part PRs into it, the question to the owner about merging the parts, keeping the branch current with main, and the final PR into main. Use when a task is planned as more than one PR or branch, or when a single-PR task grows a second PR."
---

# Multi-PR plans land through a feature branch, never piece by piece on `main`

**`main` must be releasable at every commit** (owner ruling, 2026-09-30): a
`v*` tag cut from its head at any moment must not ship half a feature. So a
work item planned as more than one PR (or more than one branch) does not
merge into `main` part by part.

## 1. Create the feature branch first

From the current `main`, before the first part is coded:

```bash
git fetch origin main && git push origin origin/main:refs/heads/feature/<topic>
```

This rule is the permission to create it and to push to it as described here.

## 2. Every part PR targets the feature branch, not `main`

- Each part goes through the full Workflow (code → review → PR → wait →
  address) like any other PR.
- Each part branches from the feature branch's current head. That holds even
  where a generic instruction says to restart from the default branch.
- Open each part's description with "Part k/n of `feature/<topic>`" and list
  the parts still to come, so a later session can pick the plan up from
  GitHub alone.

## 3. Ask the owner whether you may merge the parts yourself

Ask once per plan, before the first part is coded, together with the plan
itself (owner ruling, 2026-09-30): may you merge the part PRs into the
feature branch yourself, that is, work through every part without
interaction until the last one is merged into `feature/<topic>`?

The answer covers that one plan. Never infer it from an earlier plan's
answer, a standing instruction, or a comment on GitHub. It lives in the
conversation where the owner gave it. A later session resuming the plan
without that answer in its own conversation treats it as "no answer yet" and
may ask again: "once per plan" bounds repeat asking within a conversation,
not a resumed plan's first question.

- **Yes:** merge each part into `feature/<topic>` yourself (a merge commit,
  as on `main`; not by enabling auto-merge) once it is done under the
  Workflow: CI green on its current head, no merge conflict, the internal
  review addressed, the automated review POSTED on the PR and its findings
  handled, every review comment answered on its thread, and no open
  changes-requested review.
  - A green `claude-review` job alone is not a review: the action skips and
    still goes green when the PR's copy of `claude-review.yml` differs from
    `main`'s, and a feature branch lagging `main` on that file is enough
    (see the file's header).
  - A review of a head that has since moved beyond fixes for its findings
    and the other review comments is not one either: it runs once per PR
    (not on push), and a re-run replays the original commit, so it
    describes code that is no longer there.
  - A part without a posted review, or whose head moved like that after it,
    is not yours to merge: report it "ready to merge" with that noted and
    let the owner decide.
  - Otherwise branch the next part from the new feature head and carry on.
- **No, or no answer yet:** the owner merges each part. Report a green,
  answered part as "ready to merge into `feature/<topic>`". The next part
  that depends on it waits for that merge.
- Either way, **a PR into `main` is never yours to merge**, the final PR of
  step 5 included.

## 4. Keep the feature branch current with `main`

Merge `main` into it whenever `main` moves in a way the feature has to
absorb. Never by rebase or force-push: open part PRs are based on it.

- A merge that went through **cleanly** may be pushed straight to the
  feature branch: it adds nothing `main` does not already carry. "Cleanly"
  means more than "no conflict markers": a textually clean merge of two
  changes to the same file can still fail to compile (Myotis #587: `main`
  gave a constructor a fourth argument, the feature's test helper still
  passed three). Build the merged tree and run the affected tests locally
  BEFORE pushing it. This is the only direct push THIS STEP allows. The
  restriction is on how `main` reaches the feature branch (a merge, never a
  rebase or force-push; a resolution reviewed), not on the feature branch in
  general: the final PR's review fixes are pushed straight to it (step 5).
- A merge that needed **conflict resolution**, or that compiles or passes
  its affected tests only with a change (which is the same thing), goes
  through a PR into the feature branch like a part, because the resolution
  is new code. Like a part, it gets no artifact build (below).
- Build and Test (`ci.yml`) and the other push triggers fire only on `main`,
  so a clean merge is first fully tested on the next PR into or out of the
  feature branch. When a part PR goes red in code it did not touch, check
  the last `main` merge before blaming the part.
- A fix the feature needs in code `main` also carries lands on `main` as its
  own single PR and reaches the feature through this merge, not as a
  feature-only patch.

## 5. The final PR, `feature/<topic>` → `main`

Open it only when every planned part is merged into the feature branch.

- It is an ordinary PR under the Workflow: internal review of the whole
  combined diff, CI, reviewer comments, "ready to merge". The merge into
  `main` is the owner's. Its description links every part PR.
- **Its review comments are fixed on `feature/<topic>` itself** (step 4's
  exception for a bug in code `main` also carries aside): commit on the
  feature branch, push, answer each thread with the commit, exactly as on
  any PR's own branch. NEVER through another part PR (owner ruling,
  2026-10-09: Myotis #590 did that for #588's review and only added a
  merge-ordering trap, the final PR being mergeable without its own fixes).
  The final PR's review and CI cover what lands on the branch; the
  clean-merge sentence in step 4 does not forbid this push.
- Its review is the real gate, not a formality over parts already reviewed:
  `claude-review` reads its prompt and the root instruction files from the
  PR's base, so part PRs were reviewed under the feature branch's copies,
  and this is the one PR held to `main`'s (the header of
  `claude-review.yml`).

## When the rule applies

- A **single-PR** fix or plan targets `main` directly; none of the above
  applies.
- If a single-PR task grows a second PR before its first has merged, switch:
  create the feature branch from `main` and retarget the open PR's base to
  it.
- If a first part has already landed on `main` and left it unreleasable,
  tell the owner rather than stacking more parts on `main`.
- Skipping the feature branch for a multi-PR plan is the owner's call for
  that task, never the author's.
- Releases are unaffected: the release PR is a single PR against `main`, and
  whatever is still on a feature branch is simply not in that release.

## What the workflows do with part PRs

This rests on the PR workflows' `pull_request` triggers being unscoped (any
base branch; see the comments in `ci.yml`, `android-apk.yml` and siblings),
which gives part PRs the tests and the Claude review. Do not narrow them to
`main`.

**The artifact builds are the exception** (owner ruling, 2026-10-09): the
Android APKs, the macOS dmg and the Windows msi skip a PR whose base is not
`main` (a job-level `if` in those workflows; the checks show as skipped, not
absent), because a packaging leg per part costs more than it tells. The
final `feature/<topic>` → `main` PR builds everything. A part that touches
what those legs alone check (the packaging configuration, the minSdk-29
budget: `check_apk_min_api.py` runs only in the APK workflow) can dispatch
the workflow on its branch from the Actions tab. A PR retargeted to `main`
after it was opened keeps its skipped checks (a base change is an `edited`
event, which these workflows do not run on), so push a commit or dispatch the
workflows before it merges, or the first artifact build of that head is the
post-merge run on `main`.

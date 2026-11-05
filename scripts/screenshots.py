#!/usr/bin/env python3
"""The screenshots workflow's two helpers (`.github/workflows/screenshots.yml`), standard library only.

    screenshots.py table --repo OWNER/REPO --pr N --base-sha SHA --head-sha SHA [--dry-run] < name-status
    screenshots.py budget [--dir docs/screenshots]

`table` reads `git diff --name-status --no-renames <branch point> <head> -- docs/screenshots` on stdin and writes
the before/after table of the screenshots the PR changed into the PR's description, between the
markers `<!-- screenshots:start -->` and `<!-- screenshots:end -->`, each on a line of its own outside
fenced code (a marker quoted in the text is text). A description without the markers
gets them under its `## Screenshots` heading; without that heading, above its session link, the last
line (the pr skill); without either, at the end. Every run rewrites the whole block from the diff, so
the table never accumulates stale rows. The images are the files at the two commits, by raw URL, so
they stay as long as the commits do. `--dry-run` prints the new description instead of saving it.

`budget` fails when `docs/screenshots` is over its size budget, so growth is noticed before it hurts:
the folder as a whole (TOTAL_BUDGET) and any one file (FILE_BUDGET). The way out is in the message.

The screenshots themselves, where they live and why: the owner's rulings of 2026-11-04 in
`.claude/rules/ui.md` and `.claude/skills/screenshots/SKILL.md`.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

FOLDER = "docs/screenshots"
START = "<!-- screenshots:start -->"
END = "<!-- screenshots:end -->"
THUMB_WIDTH = 206
MAX_ROWS = 40
TOTAL_BUDGET = 64 * 1024 * 1024
FILE_BUDGET = 512 * 1024

STATUS_WORD = {"A": "added", "M": "changed", "D": "removed"}


@dataclass(frozen=True)
class Change:
    """One screenshot the PR adds (`A`), changes (`M`) or removes (`D`), by its repository path."""

    status: str
    path: str

    @property
    def state(self) -> str:
        """`<screen>/<state>`, the path without the folder and the extension."""
        return self.path[len(FOLDER) + 1 : -len(".png")]


def parse_changes(name_status: str) -> list[Change]:
    """The PNG changes in a `git diff --name-status --no-renames` listing, sorted by path.

    Other statuses (a rename, a type change) do not occur with `--no-renames` on regular files; one
    that did would be a bug in the caller, so it fails loudly rather than being dropped.
    """
    changes = []
    for line in name_status.splitlines():
        if not line.strip():
            continue
        status, _, path = line.partition("\t")
        if not path.startswith(FOLDER + "/") or not path.endswith(".png"):
            continue
        if status not in STATUS_WORD:
            raise ValueError(f"unexpected status {status!r} for {path}")
        changes.append(Change(status, path))
    return sorted(changes, key=lambda c: c.path)


def raw_url(repo: str, sha: str, path: str) -> str:
    """The file at the commit, as GitHub serves it to an `<img>`."""
    return f"https://raw.githubusercontent.com/{repo}/{sha}/{path}"


def table(changes: list[Change], repo: str, base_sha: str, head_sha: str, max_rows: int = MAX_ROWS) -> str:
    """The marked block for the description: a summary line, then one row per changed screenshot."""
    lines = [START]
    if not changes:
        lines.append(
            f"No screenshot changed: every screen renders as where this branch left its base "
            f"(CI render of {head_sha[:7]}, compared with {base_sha[:7]})."
        )
        lines.append(END)
        return "\n".join(lines)
    counts = {s: sum(1 for c in changes if c.status == s) for s in STATUS_WORD}
    summary = ", ".join(f"{n} {STATUS_WORD[s]}" for s, n in counts.items() if n)
    folder_url = f"https://github.com/{repo}/tree/{head_sha}/{FOLDER}"
    lines.append(
        f"{len(changes)} screenshot{'s' if len(changes) != 1 else ''}: {summary} "
        f"([`{FOLDER}/`]({folder_url}), CI render of {head_sha[:7]}; the before images are the branch point's, "
        f"{base_sha[:7]})."
    )
    lines.append("")
    lines.append("| Screen / state | Before | After |")
    lines.append("|---|---|---|")
    for change in changes[:max_rows]:
        before = img(raw_url(repo, base_sha, change.path), change.state) if change.status != "A" else "—"
        after = img(raw_url(repo, head_sha, change.path), change.state) if change.status != "D" else "—"
        lines.append(f"| `{change.state}` ({STATUS_WORD[change.status]}) | {before} | {after} |")
    rest = len(changes) - max_rows
    if rest > 0:
        lines.append("")
        lines.append(f"…and {rest} more: the folder's diff in this PR shows them all.")
    lines.append(END)
    return "\n".join(lines)


def img(url: str, alt: str) -> str:
    return f'<a href="{url}"><img src="{url}" width="{THUMB_WIDTH}" alt="{alt}"></a>'


def prose(lines: list[str]) -> list[bool]:
    """Per line, whether it lies outside a fenced code block: only there does a marker or the heading count."""
    result, fence = [], None
    for line in lines:
        opening = re.match(r" {0,3}(`{3,}|~{3,})", line)
        if fence is None:
            result.append(opening is None)
            fence = opening.group(1) if opening else None
        else:
            result.append(False)
            closing = re.fullmatch(r" {0,3}(`{3,}|~{3,})\s*", line)
            if closing and closing.group(1)[0] == fence[0] and len(closing.group(1)) >= len(fence):
                fence = None
    return result


def splice(body: str, block: str) -> str:
    """The description with the block in place of the marked one, or inserted where the docstring says.

    A marker counts only on a line of its own, outside fenced code: a description that quotes the markers
    in its text, as this workflow's own PR does, keeps that text.
    """
    body = body.replace("\r\n", "\n")
    lines = body.split("\n")
    counts = prose(lines)
    start = next((i for i, line in enumerate(lines) if counts[i] and line.rstrip() == START), None)
    end = None
    if start is not None:
        end = next((i for i in range(start + 1, len(lines)) if counts[i] and lines[i].rstrip() == END), None)
    if end is not None:
        return "\n".join(lines[:start] + [block] + lines[end + 1 :])
    heading = next(
        (i for i, line in enumerate(lines) if counts[i] and re.fullmatch(r"##\s+Screenshots\s*", line)), None
    )
    if heading is not None:
        insert = heading + 1
        while insert < len(lines) and not lines[insert].strip():
            insert += 1
        # The heading is followed by its blank line and the block by one, as written by hand.
        return "\n".join(lines[: heading + 1] + ["", block, ""] + lines[insert:])
    session = next((i for i in range(len(lines) - 1, -1, -1) if lines[i].startswith("Session:")), None)
    if session is not None:
        return "\n".join(lines[:session] + ["## Screenshots", "", block, ""] + lines[session:])
    return body.rstrip("\n") + "\n\n## Screenshots\n\n" + block + "\n"


def gh(*args: str, stdin: str | None = None) -> str:
    """gh's output; its error messages go to the step's log, so a failed call says why."""
    return subprocess.run(["gh", *args], check=True, stdout=subprocess.PIPE, text=True, input=stdin).stdout


def update_description(repo: str, pr: int, block: str, dry_run: bool) -> str:
    current = json.loads(gh("api", f"repos/{repo}/pulls/{pr}", "--jq", "{body: .body}"))["body"] or ""
    new = splice(current, block)
    if dry_run:
        return new
    if new != current:
        gh("api", "-X", "PATCH", f"repos/{repo}/pulls/{pr}", "--input", "-", stdin=json.dumps({"body": new}))
    return new


def over_budget(folder: Path, total_budget: int = TOTAL_BUDGET, file_budget: int = FILE_BUDGET) -> list[str]:
    """What exceeds the budget, as messages; empty when nothing does. A missing folder is within budget."""
    if not folder.is_dir():
        return []
    files = [p for p in folder.rglob("*") if p.is_file()]
    total = sum(p.stat().st_size for p in files)
    problems = []
    if total > total_budget:
        problems.append(
            f"{folder} holds {total / 2**20:.1f} MiB, over its {total_budget // 2**20} MiB budget. Its history is "
            f"the weight every clone carries: purge it (git filter-repo, which rewrites history; see "
            f"{folder}/README.md), or render fewer states."
        )
    for p in sorted(files):
        size = p.stat().st_size
        if size > file_budget:
            problems.append(
                f"{p} is {size // 1024} KiB, over the {file_budget // 1024} KiB a screenshot may take: a preview "
                f"that draws a photo or a gradient compresses badly; simplify its sample data."
            )
    return problems


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    t = sub.add_parser("table", help="write the before/after table into the PR description")
    t.add_argument("--repo", required=True)
    t.add_argument("--pr", required=True, type=int)
    t.add_argument("--base-sha", required=True)
    t.add_argument("--head-sha", required=True)
    t.add_argument("--dry-run", action="store_true")
    b = sub.add_parser("budget", help="fail when docs/screenshots is over its size budget")
    b.add_argument("--dir", default=FOLDER)
    args = parser.parse_args(argv)
    if args.command == "table":
        changes = parse_changes(sys.stdin.read())
        block = table(changes, args.repo, args.base_sha, args.head_sha)
        new = update_description(args.repo, args.pr, block, args.dry_run)
        print(new if args.dry_run else f"description of {args.repo}#{args.pr}: {len(changes)} screenshot(s) listed")
        return 0
    problems = over_budget(Path(args.dir))
    for problem in problems:
        print(f"::error::{problem}")
    if not problems:
        print(f"{args.dir}: within budget")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

"""Tests of scripts/screenshots.py: the table, where it lands in a description, and the budget.

Run before the script in the workflow: `python3 -m unittest discover -s scripts -p 'test_screenshots.py'`.
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import screenshots  # noqa: E402

REPO = "biafra23/punicus"
BASE = "a" * 40
HEAD = "b" * 40
DIFF = (
    "M\tdocs/screenshots/send/compose-empty.png\n"
    "A\tdocs/screenshots/send/compose-full.png\n"
    "D\tdocs/screenshots/watch/old-state.png\n"
    "M\tdocs/screenshots/README.md\n"
    "M\tdocs/screenshots/index.html\n"
    "M\tui/src/commonMain/kotlin/eth/serotine/ui/send/SendScreen.kt\n"
)


class ParseChangesTest(unittest.TestCase):
    def test_only_pngs_under_the_folder_sorted_by_path(self):
        changes = screenshots.parse_changes(DIFF)
        self.assertEqual(
            [(c.status, c.state) for c in changes],
            [("M", "send/compose-empty"), ("A", "send/compose-full"), ("D", "watch/old-state")],
        )

    def test_an_unexpected_status_fails_loudly(self):
        with self.assertRaises(ValueError):
            screenshots.parse_changes("R100\tdocs/screenshots/a/b.png\tdocs/screenshots/a/c.png\n")


class TableTest(unittest.TestCase):
    def test_one_row_per_change_with_the_images_of_both_commits(self):
        block = screenshots.table(screenshots.parse_changes(DIFF), REPO, BASE, HEAD)
        self.assertTrue(block.startswith(screenshots.START) and block.endswith(screenshots.END))
        self.assertIn("3 screenshots: 1 added, 1 changed, 1 removed", block)
        self.assertIn(f"https://raw.githubusercontent.com/{REPO}/{BASE}/docs/screenshots/send/compose-empty.png", block)
        self.assertIn(f"https://raw.githubusercontent.com/{REPO}/{HEAD}/docs/screenshots/send/compose-empty.png", block)
        rows = [line for line in block.splitlines() if line.startswith("| `")]
        self.assertEqual(len(rows), 3)
        added = next(r for r in rows if "compose-full" in r)
        self.assertTrue(added.split("|")[2].strip() == "—", "an added state has no before image")
        self.assertIn(f"{HEAD}/docs/screenshots/send/compose-full.png", added)
        removed = next(r for r in rows if "old-state" in r)
        self.assertTrue(removed.split("|")[3].strip() == "—", "a removed state has no after image")

    def test_no_change_says_so(self):
        block = screenshots.table([], REPO, BASE, HEAD)
        self.assertIn("No screenshot changed", block)
        self.assertNotIn("|---|", block)

    def test_long_lists_are_capped(self):
        diff = "".join(f"A\tdocs/screenshots/s/state-{i:03d}.png\n" for i in range(50))
        block = screenshots.table(screenshots.parse_changes(diff), REPO, BASE, HEAD, max_rows=40)
        self.assertEqual(sum(1 for line in block.splitlines() if line.startswith("| `")), 40)
        self.assertIn("…and 10 more", block)


class SpliceTest(unittest.TestCase):
    block = f"{screenshots.START}\nNEW\n{screenshots.END}"

    def test_replaces_an_existing_block(self):
        body = f"Intro\n\n## Screenshots\n\n{screenshots.START}\nOLD\n{screenshots.END}\n\nSession: `x`\n"
        new = screenshots.splice(body, self.block)
        self.assertNotIn("OLD", new)
        self.assertEqual(new.count(screenshots.START), 1)
        self.assertEqual(new, f"Intro\n\n## Screenshots\n\n{self.block}\n\nSession: `x`\n")

    def test_lands_under_the_screenshots_heading(self):
        body = "Intro\n\n## Screenshots\n\nNo UI change.\n\nSession: `x`\n"
        new = screenshots.splice(body, self.block)
        self.assertLess(new.index("## Screenshots"), new.index(screenshots.START))
        self.assertLess(new.index(screenshots.END), new.index("No UI change."))
        self.assertTrue(new.rstrip("\n").endswith("Session: `x`"), "the session link stays the last line")

    def test_lands_above_the_session_link_without_a_heading(self):
        body = "Intro\n\nSession: `x`\n"
        new = screenshots.splice(body, self.block)
        self.assertIn("## Screenshots", new)
        self.assertLess(new.index(screenshots.END), new.index("Session:"))
        self.assertTrue(new.rstrip("\n").endswith("Session: `x`"))

    def test_appends_to_a_description_with_neither(self):
        new = screenshots.splice("Intro\n", self.block)
        self.assertTrue(new.startswith("Intro\n\n## Screenshots\n\n"))
        self.assertTrue(new.rstrip("\n").endswith(screenshots.END))

    def test_spacing_elsewhere_is_left_alone(self):
        body = "Intro\n\n\n```\na\n\n\nb\n```\n\n## Screenshots\n\nNo UI change.\n\nSession: `x`\n"
        new = screenshots.splice(body, self.block)
        self.assertTrue(new.startswith("Intro\n\n\n```\na\n\n\nb\n```\n"))

    def test_markers_quoted_in_the_text_are_text(self):
        # PR #242's description: the first run put its table between these two quotes.
        intro = (
            f"Intro\n\n- one line quotes `{screenshots.START}` and `{screenshots.END}` together;\n"
            f"- this one quotes `{screenshots.START}\nand the next line `{screenshots.END}`, under the heading.\n"
        )
        body = intro + "\n## Screenshots\n\nNo UI change.\n\nSession: `x`\n"
        new = screenshots.splice(body, self.block)
        self.assertTrue(new.startswith(intro), "the quoted markers stay as written")
        self.assertIn(f"## Screenshots\n\n{self.block}\n\nNo UI change.", new)

    def test_markers_and_a_heading_in_fenced_code_are_text(self):
        fenced = f"~~~\n## Screenshots\n{screenshots.START}\nOLD\n{screenshots.END}\n~~~\n"
        body = f"Intro\n\n{fenced}\n## Screenshots\n\nNo UI change.\n\nSession: `x`\n"
        new = screenshots.splice(body, self.block)
        self.assertIn(fenced, new)
        self.assertIn(f"## Screenshots\n\n{self.block}\n\nNo UI change.", new)

    def test_an_unclosed_fence_or_inline_backticks_hide_no_block(self):
        for opener in ("```\nnever closed", "```inline``` code"):
            body = f"Intro\n\n{opener}\n\n## Screenshots\n\n{screenshots.START}\nOLD\n{screenshots.END}\n"
            new = screenshots.splice(body, self.block)
            self.assertNotIn("OLD", new, opener)
            self.assertEqual(new.count(screenshots.START), 1, opener)

    def test_a_second_run_replaces_the_first_runs_block(self):
        body = f"Quotes `{screenshots.START}` and `{screenshots.END}`.\n\n## Screenshots\n\nNo UI change.\n"
        first = screenshots.splice(body, self.block)
        second = screenshots.splice(first, f"{screenshots.START}\nNEWER\n{screenshots.END}")
        self.assertNotIn("NEW\n", second)
        self.assertEqual(second, first.replace("\nNEW\n", "\nNEWER\n"))

    def test_crlf_bodies_are_normalized(self):
        new = screenshots.splice("Intro\r\n\r\n## Screenshots\r\n\r\nSession: `x`\r\n", self.block)
        self.assertNotIn("\r", new)
        self.assertIn(screenshots.START, new)


class BudgetTest(unittest.TestCase):
    def test_within_budget_and_missing_folder_are_fine(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / "docs" / "screenshots"
            self.assertEqual(screenshots.over_budget(folder), [])
            (folder / "send").mkdir(parents=True)
            (folder / "send" / "a.png").write_bytes(b"\0" * 1000)
            self.assertEqual(screenshots.over_budget(folder, total_budget=2000, file_budget=1500), [])

    def test_the_folder_and_a_single_file_are_each_held_to_their_budget(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / "shots"
            (folder / "s").mkdir(parents=True)
            (folder / "s" / "big.png").write_bytes(b"\0" * 3000)
            (folder / "s" / "small.png").write_bytes(b"\0" * 10)
            problems = screenshots.over_budget(folder, total_budget=2000, file_budget=2500)
            self.assertEqual(len(problems), 2)
            self.assertIn("over its", problems[0])
            self.assertIn("big.png", problems[1])


if __name__ == "__main__":
    unittest.main()

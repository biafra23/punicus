#!/usr/bin/env python3
"""Tests of scripts/check_security_invariants.py against small synthetic trees.

Run: python3 -m unittest discover -s scripts -p 'test_check_security_invariants.py'

Each test plants one violation in a minimal repository and asserts the check that must catch it
does, and that the same tree without the violation passes it: a check that stops firing is a
regression of the tool itself.
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("check_security_invariants", HERE / "check_security_invariants.py")
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

SHA = "a" * 40
WORDS = (
    "abandon ability able about above absent absorb abstract absurd abuse access accident account "
    "accuse achieve acid acoustic acquire across act action actor actress actual adapt add"
).split()


class Tree:
    """A throw-away repository root with the files a check needs."""

    def __init__(self, test: unittest.TestCase) -> None:
        self.dir = tempfile.TemporaryDirectory()
        test.addCleanup(self.dir.cleanup)
        self.root = Path(self.dir.name)

    def write(self, relative: str, text: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def violations(self, check) -> list[tuple[str, str, str]]:
        checker = CHECKER.Checker(self.root, verbose=False)
        check(checker)
        return checker.violations

    def ids(self, check) -> set[str]:
        return {check_id for check_id, _, _ in self.violations(check)}


def workflow(uses: str, run: str = "echo ok") -> str:
    return f"""name: x
on:
  pull_request:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: {uses}
      - name: step
        run: |
          {run}
"""


class WorkflowChecks(unittest.TestCase):
    def test_a_movable_tag_is_a_violation_and_a_sha_is_not(self) -> None:
        tree = Tree(self)
        tree.write(".github/workflows/x.yml", workflow("actions/checkout@v4"))
        self.assertIn("WF-SHA-PIN", tree.ids(CHECKER.check_workflows))
        tree.write(".github/workflows/x.yml", workflow(f"actions/checkout@{SHA}"))
        self.assertNotIn("WF-SHA-PIN", tree.ids(CHECKER.check_workflows))

    def test_a_reusable_workflow_of_the_repository_needs_no_pin(self) -> None:
        tree = Tree(self)
        tree.write(".github/workflows/x.yml", workflow("./.github/workflows/guard.yml"))
        self.assertNotIn("WF-SHA-PIN", tree.ids(CHECKER.check_workflows))

    def test_an_event_expression_inside_run_is_a_violation(self) -> None:
        tree = Tree(self)
        tree.write(".github/workflows/x.yml", workflow(f"actions/checkout@{SHA}", 'echo "${{ github.event.pull_request.title }}"'))
        self.assertIn("WF-RUN-INJECTION", tree.ids(CHECKER.check_workflows))
        tree.write(".github/workflows/x.yml", workflow(f"actions/checkout@{SHA}", 'echo "$TITLE"'))
        self.assertNotIn("WF-RUN-INJECTION", tree.ids(CHECKER.check_workflows))

    def test_a_dangerous_trigger_is_a_violation(self) -> None:
        tree = Tree(self)
        tree.write(".github/workflows/x.yml", "on:\n  pull_request_target:\njobs: {}\n")
        self.assertIn("WF-DANGEROUS-TRIGGER", tree.ids(CHECKER.check_workflows))


MANIFEST = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules">
        <activity android:name=".MainActivity" android:exported="true" />
    </application>
</manifest>
"""
RULES = """<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="." /><exclude domain="file" path="." />
        <exclude domain="database" path="." /><exclude domain="sharedpref" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="." /><exclude domain="file" path="." />
        <exclude domain="database" path="." /><exclude domain="sharedpref" path="." />
    </device-transfer>
</data-extraction-rules>
"""
BUILD = """android {
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "androidx.compose.ui", module = "ui-tooling")
}
"""


class AndroidChecks(unittest.TestCase):
    def android_tree(self, owner: unittest.TestCase | None = None) -> Tree:
        tree = Tree(owner or self)
        tree.write("android-app/src/main/AndroidManifest.xml", MANIFEST)
        tree.write("android-app/src/main/res/xml/data_extraction_rules.xml", RULES)
        tree.write("android-app/build.gradle.kts", BUILD)
        tree.write("android-app/release-signing-cert.sha256", "# pin\n" + "d" * 64 + "\n")
        return tree

    def test_the_sound_host_passes(self) -> None:
        self.assertEqual(set(), self.android_tree().ids(CHECKER.check_android))

    def test_backup_turned_on_is_a_violation(self) -> None:
        tree = self.android_tree()
        tree.write("android-app/src/main/AndroidManifest.xml", MANIFEST.replace('allowBackup="false"', 'allowBackup="true"'))
        self.assertIn("AND-BACKUP", tree.ids(CHECKER.check_android))

    def test_a_component_without_exported_is_a_violation(self) -> None:
        tree = self.android_tree()
        tree.write("android-app/src/main/AndroidManifest.xml", MANIFEST.replace(' android:exported="true"', ""))
        self.assertIn("AND-MANIFEST", tree.ids(CHECKER.check_android))

    def test_a_release_signing_config_is_a_violation(self) -> None:
        tree = self.android_tree()
        tree.write("android-app/build.gradle.kts", BUILD.replace("isMinifyEnabled = false", 'signingConfig = signingConfigs.getByName("release")'))
        self.assertIn("AND-RELEASE-SIGNING", tree.ids(CHECKER.check_android))

    def test_the_debug_certificate_as_pin_is_a_violation(self) -> None:
        tree = self.android_tree()
        tree.write("android-app/release-signing-cert.sha256", CHECKER.DEBUG_CERT_SHA256 + "\n")
        self.assertIn("AND-CERT-PIN", tree.ids(CHECKER.check_android))


ENVELOPE = """package eth.serotine.data.keys
internal object Envelope {
    const val MIN_MEMORY_BYTES = 64L * 1024 * 1024
    const val MIN_OPS = 2L
    const val MAX_MEMORY_BYTES = 256L * 1024 * 1024
    const val MAX_OPS = 16L
    const val MAX_WORK_BYTES = MAX_MEMORY_BYTES * 4
    fun kek() = Hkdf.sha256(ikm, salt, KEK_INFO, 32)
}
"""


class ArchitectureChecks(unittest.TestCase):
    def minimal_tree(self, owner: unittest.TestCase | None = None) -> Tree:
        tree = Tree(owner or self)
        tree.write("data/src/commonMain/kotlin/eth/serotine/data/keys/Envelope.kt", ENVELOPE)
        tree.write("data/src/androidMain/kotlin/eth/serotine/data/keys/AndroidBiometricKey.kt",
                   "setUserAuthenticationRequired(true)\nsetInvalidatedByBiometricEnrollment(true)\n"
                   "setUnlockedDeviceRequired(true)\nAUTH_BIOMETRIC_STRONG\nsetIsStrongBoxBacked(true)\n")
        tree.write("android-app/src/main/kotlin/eth/serotine/android/keys/PlatformBiometricPrompter.kt",
                   "BIOMETRIC_STRONG\nCryptoObject\n")
        tree.write("data/src/desktopMain/kotlin/eth/serotine/data/keys/MacSecurity.kt", "PRIVATE_KEY_USAGE or BIOMETRY_CURRENT_SET\n")
        for rel in CHECKER.CAPTURE_PROTECTED:
            tree.write(rel, "fun Screen() { protectFromScreenCapture() }\n")
        return tree

    def test_the_minimal_tree_passes(self) -> None:
        self.assertEqual(set(), self.minimal_tree().ids(CHECKER.check_architecture))

    def test_myotis_outside_data_is_a_violation(self) -> None:
        tree = self.minimal_tree()
        tree.write("ui/src/commonMain/kotlin/eth/serotine/ui/X.kt", "import io.myotis.api.MyotisEngine\n")
        self.assertIn("ARCH-MYOTIS-BOUNDARY", tree.ids(CHECKER.check_architecture))

    def test_the_mnemonic_type_off_its_path_is_a_violation(self) -> None:
        tree = self.minimal_tree()
        tree.write("ui/src/commonMain/kotlin/eth/serotine/ui/send/SendViewModel.kt", "val phrase: Mnemonic = Mnemonic.EMPTY\n")
        self.assertIn("KEY-MNEMONIC-PATH", tree.ids(CHECKER.check_architecture))
        tree.write("ui/src/commonMain/kotlin/eth/serotine/ui/send/SendViewModel.kt", "// a comment naming Mnemonic is fine\n")
        self.assertNotIn("KEY-MNEMONIC-PATH", tree.ids(CHECKER.check_architecture))

    def test_a_serialized_password_is_a_violation(self) -> None:
        tree = self.minimal_tree()
        tree.write("data/src/commonMain/kotlin/eth/serotine/data/x/Stored.kt",
                   "@Serializable\nclass Stored(val address: String, val password: String)\n")
        self.assertIn("KEY-NO-SERIALIZED-SECRET", tree.ids(CHECKER.check_architecture))

    def test_a_raised_floor_is_a_violation(self) -> None:
        tree = self.minimal_tree()
        tree.write("data/src/commonMain/kotlin/eth/serotine/data/keys/Envelope.kt", ENVELOPE.replace("MIN_MEMORY_BYTES = 64L", "MIN_MEMORY_BYTES = 16L"))
        self.assertIn("KEY-ENVELOPE-BOUNDS", tree.ids(CHECKER.check_architecture))

    def test_the_device_credential_in_the_prompt_is_a_violation(self) -> None:
        tree = self.minimal_tree()
        tree.write("android-app/src/main/kotlin/eth/serotine/android/keys/PlatformBiometricPrompter.kt",
                   "BIOMETRIC_STRONG or DEVICE_CREDENTIAL\nCryptoObject\n")
        self.assertIn("KEY-BIOMETRIC-PARAMS", tree.ids(CHECKER.check_architecture))

    def test_a_screen_that_dropped_its_capture_protection_is_a_violation(self) -> None:
        tree = self.minimal_tree()
        tree.write(CHECKER.CAPTURE_PROTECTED[0], "fun Screen() {}\n")
        self.assertIn("UI-CAPTURE", tree.ids(CHECKER.check_architecture))

    def test_a_print_in_ui_or_a_cleartext_url_is_a_violation(self) -> None:
        tree = self.minimal_tree()
        tree.write("ui/src/commonMain/kotlin/eth/serotine/ui/X.kt", 'fun x() { println("x") }\n')
        tree.write("data/src/commonMain/kotlin/eth/serotine/data/Y.kt", 'val url = "http://example.org"\n')
        ids = tree.ids(CHECKER.check_architecture)
        self.assertIn("LOG-NO-PRINT", ids)
        self.assertIn("NET-ENDPOINTS", ids)

    def test_each_rpc_word_is_its_own_violation(self) -> None:
        tree = self.minimal_tree()
        tree.write("data/src/commonMain/kotlin/eth/serotine/data/Rpc.kt",
                   'val a = "jsonrpc"\nval b = "JsonRpc"\nval c = "eth_getBalance"\n')
        keys = {key for check_id, key, _ in tree.violations(CHECKER.check_architecture) if check_id == "NET-ENDPOINTS"}
        rel = "data/src/commonMain/kotlin/eth/serotine/data/Rpc.kt"
        # An entry accepting the first word never covers the second.
        self.assertEqual({f"{rel}:rpc:jsonrpc", f"{rel}:rpc:eth_getbalance"}, keys)

    def test_a_url_inside_a_comment_is_not_a_cleartext_literal(self) -> None:
        tree = self.minimal_tree()
        tree.write("data/src/commonMain/kotlin/eth/serotine/data/Y.kt", '// see http://example.org\nval x = 1\n')
        self.assertNotIn("NET-ENDPOINTS", tree.ids(CHECKER.check_architecture))


class PhraseAndBuildChecks(unittest.TestCase):
    def wordlist_tree(self, owner: unittest.TestCase | None = None) -> Tree:
        tree = Tree(owner or self)
        words = " ".join(f"w{i}" for i in range(2048 - len(WORDS))) + " " + " ".join(WORDS)
        tree.write("data/src/commonMain/kotlin/eth/serotine/data/keys/Bip39EnglishWordlist.kt",
                   'internal val BIP39_ENGLISH: List<String> = """\n' + words + '\n""".trim().split(" ")\n')
        return tree

    def test_twelve_wordlist_words_in_a_ui_string_are_a_violation(self) -> None:
        tree = self.wordlist_tree()
        tree.write("ui/src/commonMain/kotlin/eth/serotine/ui/x/Previews.kt", f'val phrase = "{" ".join(WORDS[:12])}"\n')
        self.assertIn("UI-NO-PUBLISHED-PHRASE", tree.ids(CHECKER.check_ui_phrases))
        tree.write("ui/src/commonMain/kotlin/eth/serotine/ui/x/Previews.kt", 'val phrase = "one two three four five six seven eight nine ten eleven twelve"\n')
        self.assertNotIn("UI-NO-PUBLISHED-PHRASE", tree.ids(CHECKER.check_ui_phrases))

    def test_an_engine_repository_on_a_branch_is_a_violation_and_on_a_commit_is_not(self) -> None:
        tree = Tree(self)
        settings = """dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven {
            name = "MyotisMavenRepo"
            url = uri("https://raw.githubusercontent.com/biafra23/myotis/REF")
            content { includeGroup("eth.myotis") }
        }
    }
}
"""
        tree.write("settings.gradle.kts", settings.replace("REF", "maven-repo"))
        tree.write("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.4-bin.zip\nvalidateDistributionUrl=true\ndistributionSha256Sum=abc\n")
        tree.write("gradle/verification-metadata.xml", "<verification-metadata/>")
        self.assertEqual({"GRADLE-ENGINE-IMMUTABLE"}, tree.ids(CHECKER.check_build))
        tree.write("settings.gradle.kts", settings.replace("REF", SHA))
        self.assertEqual(set(), tree.ids(CHECKER.check_build))
        tree.write("settings.gradle.kts", settings.replace("REF", SHA).replace('includeGroup("eth.myotis")', ""))
        self.assertIn("GRADLE-REPOSITORIES", tree.ids(CHECKER.check_build))


class ReviewFindingsOfPr217(unittest.TestCase):
    """Each fixes a gap the review of PR #217 found; a check that regresses to it fails here."""

    def test_a_needle_left_in_a_comment_does_not_satisfy_a_kotlin_check(self) -> None:
        tree = ArchitectureChecks.minimal_tree(ArchitectureChecks("test_the_minimal_tree_passes"), self)
        tree.write(CHECKER.CAPTURE_PROTECTED[0], "// protectFromScreenCapture()\nfun Screen() {}\n")
        tree.write(
            "data/src/commonMain/kotlin/eth/serotine/data/keys/Envelope.kt",
            ENVELOPE.replace("const val MIN_MEMORY_BYTES = 64L", "// const val MIN_MEMORY_BYTES = 64L * 1024 * 1024\n    const val MIN_MEMORY_BYTES = 16L"),
        )
        ids = tree.ids(CHECKER.check_architecture)
        self.assertIn("UI-CAPTURE", ids)
        self.assertIn("KEY-ENVELOPE-BOUNDS", ids)

    def test_a_guard_quoted_in_a_yaml_comment_does_not_satisfy_a_workflow_check(self) -> None:
        tree = Tree(self)
        tree.write(".github/workflows/android-apk.yml", "# persist-credentials: false\n# environment: android-release\n# scripts/sign_release_apk.sh\non: push\n")
        tree.write(".github/workflows/claude-review.yml", "# if: head.repo.full_name == github.repository\non: pull_request\n")
        ids = {(check, key) for check, key, _ in tree.violations(CHECKER.check_workflows)}
        self.assertIn(("WF-SIGNING", "persist-credentials"), ids)
        self.assertIn(("WF-REVIEWER", "fork-exclusion"), ids)

    def test_a_checkout_under_a_directory_named_test_still_scans_main_sources(self) -> None:
        outer = Tree(self)
        repo = outer.root / "test" / "serotine"
        repo.mkdir(parents=True)
        (repo / "ui/src/commonMain/kotlin/eth/serotine/ui").mkdir(parents=True)
        (repo / "ui/src/commonMain/kotlin/eth/serotine/ui/X.kt").write_text('fun x() { println("x") }\n', encoding="utf-8")
        checker = CHECKER.Checker(repo, verbose=False)
        CHECKER.check_architecture(checker)
        self.assertIn("LOG-NO-PRINT", {check for check, _, _ in checker.violations})

    def test_a_yaml_workflow_is_scanned_like_a_yml_one(self) -> None:
        tree = Tree(self)
        tree.write(".github/workflows/x.yaml", workflow("actions/checkout@v4"))
        self.assertIn("WF-SHA-PIN", tree.ids(CHECKER.check_workflows))

    def test_a_run_scalar_that_starts_on_the_next_line_is_scanned(self) -> None:
        tree = Tree(self)
        tree.write(
            ".github/workflows/x.yml",
            f"on: pull_request\njobs:\n  b:\n    steps:\n      - uses: actions/checkout@{SHA}\n"
            '      - run:\n          echo "${{ github.head_ref }}"\n',
        )
        ids = {(check, key) for check, key, _ in tree.violations(CHECKER.check_workflows)}
        self.assertIn(("WF-RUN-INJECTION", ".github/workflows/x.yml:${{ github.head_ref }}"), ids)

    def test_a_signing_config_hidden_by_the_layout_is_still_found(self) -> None:
        tree = AndroidChecks.android_tree(AndroidChecks("test_the_sound_host_passes"), self)
        above = "android {\n    signingConfigs {\n        release {\n            storeFile = file(\"x\")\n        }\n    }\n" + BUILD[len("android {\n"):]
        tree.write("android-app/build.gradle.kts", above.replace("isMinifyEnabled = false", "postprocessing {\n                isObfuscateEnabled = false\n            }\n            signingConfig = signingConfigs.getByName(\"release\")"))
        self.assertIn("AND-RELEASE-SIGNING", tree.ids(CHECKER.check_android))
        tree.write("android-app/build.gradle.kts", above)
        self.assertNotIn("AND-RELEASE-SIGNING", tree.ids(CHECKER.check_android), "a signingConfigs block alone is no violation")

    def test_a_phrase_key_survives_an_edit_above_it(self) -> None:
        tree = PhraseAndBuildChecks.wordlist_tree(PhraseAndBuildChecks("test_twelve_wordlist_words_in_a_ui_string_are_a_violation"), self)
        phrase = " ".join(WORDS[:12])
        tree.write("ui/src/commonMain/kotlin/eth/serotine/ui/x/Previews.kt", f'val phrase = "{phrase}"\n')
        before = {key for _, key, _ in tree.violations(CHECKER.check_ui_phrases)}
        tree.write("ui/src/commonMain/kotlin/eth/serotine/ui/x/Previews.kt", f'import x\nimport y\nval phrase = "{phrase}"\n')
        after = {key for _, key, _ in tree.violations(CHECKER.check_ui_phrases)}
        self.assertEqual(before, after)
        self.assertEqual({"ui/src/commonMain/kotlin/eth/serotine/ui/x/Previews.kt:" + " ".join(WORDS[:3])}, before)


class RepositoryChecks(unittest.TestCase):
    def test_a_tracked_key_file_or_token_is_a_violation(self) -> None:
        tree = Tree(self)
        tree.write("release.jks", "binary")
        tree.write("notes.md", "token ghp_" + "A" * 36 + "\n")
        tree.write("android-app/debug.keystore", "binary")
        ids = {(check, key) for check, key, _ in tree.violations(CHECKER.check_repository)}
        self.assertIn(("REPO-NO-KEY-FILES", "release.jks"), ids)
        self.assertIn(("REPO-NO-SECRETS", "notes.md:github-token"), ids)
        self.assertNotIn(("REPO-NO-KEY-FILES", "android-app/debug.keystore"), ids)


class Baseline(unittest.TestCase):
    def test_the_baseline_accepts_listed_keys_and_reports_stale_ones(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            baseline = Path(d) / "b.txt"
            baseline.write_text("CHECK\tkey\t# why\nOTHER\tgone\n", encoding="utf-8")
            accepted = CHECKER.load_baseline(baseline)
            self.assertEqual({("CHECK", "key"), ("OTHER", "gone")}, set(accepted))


if __name__ == "__main__":
    unittest.main()

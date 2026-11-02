#!/usr/bin/env python3
"""Security invariants of the Serotine source tree, checked without a build.

Each check encodes something the audit of 2026-11-01 verified and the rules under .claude/
require: the Myotis boundary, the mnemonic's closed path, the key store's floors, the biometric
keys' parameters, the Android backup and signing rules, the screens under capture protection, the
workflows' pinning and triggers, the build's repositories. A change that breaks one fails this
script, so a regression is caught in CI before review.

Usage:
    python3 scripts/check_security_invariants.py [--repo DIR] [--baseline FILE] [--verbose]

Exit status 1 when a violation is found that the baseline does not accept, 0 otherwise. Known,
accepted violations (the audit's open findings) are listed in scripts/security-baseline.txt, one
per line as `CHECK-ID<TAB>key`; an entry that no longer matches anything is reported as stale so
the baseline shrinks as findings are fixed. Standard library only; runs on Python 3.9 and later.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Callable, Iterable

# --- the model ---------------------------------------------------------------------------------

Violation = tuple[str, str, str]  # (check id, key, message)


class Checker:
    def __init__(self, repo: Path, verbose: bool) -> None:
        self.repo = repo
        self.verbose = verbose
        self.violations: list[Violation] = []
        self.checks_run: list[str] = []

    def fail(self, check: str, key: str, message: str) -> None:
        self.violations.append((check, key, message))

    def ran(self, check: str) -> None:
        self.checks_run.append(check)
        if self.verbose:
            print(f"  ran {check}")

    # file helpers
    def read(self, relative: str) -> str:
        return (self.repo / relative).read_text(encoding="utf-8", errors="replace")

    def exists(self, relative: str) -> bool:
        return (self.repo / relative).exists()

    def kotlin_files(self, *roots: str, main_only: bool = False, tests_only: bool = False) -> list[Path]:
        files: list[Path] = []
        for root in roots:
            base = self.repo / root
            if not base.exists():
                continue
            for path in sorted(base.rglob("*.kt")):
                if "/build/" in path.as_posix():
                    continue
                is_test = is_test_source(path)
                if main_only and is_test:
                    continue
                if tests_only and not is_test:
                    continue
                files.append(path)
        return files

    def rel(self, path: Path) -> str:
        return path.relative_to(self.repo).as_posix()


def is_test_source(path: Path) -> bool:
    parts = path.as_posix().split("/")
    return any(part.endswith("Test") or part == "test" for part in parts if part != path.name)


def strip_comments(text: str) -> str:
    """Kotlin/Gradle text without its line and block comments; string literals are kept whole."""
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(text[i:end])
            i = end
        elif c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i : j + 1])
            i = j + 1
        elif text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end < 0 else end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def brace_blocks(text: str, opener: str) -> list[str]:
    """Every `<opener> { ... }` block of [text] with its braces balanced, outermost first."""
    blocks: list[str] = []
    for m in re.finditer(opener, text):
        depth, i = 0, m.end() - 1
        while i < len(text):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    blocks.append(text[m.start() : i + 1])
                    break
            i += 1
    return blocks


MAIN_MODULES = ("domain/src", "data/src", "ui/src", "android-app/src", "desktop-app/src", "ios-app/src")

# --- workflows -----------------------------------------------------------------------------------

SHA40 = re.compile(r"^[0-9a-f]{40}$")
USES = re.compile(r"^\s*-?\s*uses:\s*([^\s#]+)")
RUN_BLOCK = re.compile(r"^(\s*)(?:-\s+)?run:\s*([|>].*)?$")
RUN_INLINE = re.compile(r"^(\s*)(?:-\s+)?run:\s*(\S.*)$")
INJECTABLE = re.compile(
    r"\$\{\{\s*(github\.event\.(?!repository\.|number\b|pull_request\.number\b)|github\.head_ref|inputs\.|"
    r"github\.event\.inputs\.|steps\.[^}]*\.outputs\.)"
)
DANGEROUS_TRIGGERS = re.compile(r"^\s*(pull_request_target|workflow_run|issue_comment)\s*:")


def check_workflows(c: Checker) -> None:
    workflows = sorted((c.repo / ".github" / "workflows").glob("*.yml"))
    for wf in workflows:
        text = wf.read_text(encoding="utf-8")
        name = c.rel(wf)
        lines = text.splitlines()
        # WF-SHA-PIN: every external action is pinned to a full commit SHA.
        for n, line in enumerate(lines, 1):
            m = USES.match(line)
            if not m:
                continue
            ref = m.group(1)
            if ref.startswith("./") or ref.startswith("."):
                continue  # a reusable workflow of this repository
            if "@" not in ref:
                c.fail("WF-SHA-PIN", f"{name}:{ref}", f"{name}:{n}: `uses: {ref}` has no ref at all")
                continue
            action, version = ref.rsplit("@", 1)
            if not SHA40.match(version):
                # Keyed per workflow and action, so a floating tag in a new file is a new violation.
                c.fail(
                    "WF-SHA-PIN",
                    f"{name}:{action}@{version}",
                    f"{name}:{n}: `{ref}` is pinned by a movable tag, not a commit SHA",
                )
        # WF-DANGEROUS-TRIGGER: no trigger that runs PR-controlled code with secrets.
        for n, line in enumerate(lines, 1):
            m = DANGEROUS_TRIGGERS.match(line)
            if m:
                c.fail("WF-DANGEROUS-TRIGGER", f"{name}:{m.group(1)}", f"{name}:{n}: trigger `{m.group(1)}`")
        # WF-RUN-INJECTION: no event-controlled expression inside a `run:` script.
        i = 0
        while i < len(lines):
            line = lines[i]
            block = RUN_BLOCK.match(line)
            inline = RUN_INLINE.match(line)
            if block and block.group(2) is not None:
                indent = len(block.group(1))
                j = i + 1
                while j < len(lines) and (not lines[j].strip() or len(lines[j]) - len(lines[j].lstrip()) > indent):
                    if INJECTABLE.search(lines[j]):
                        c.fail("WF-RUN-INJECTION", f"{name}:{j + 1}", f"{name}:{j + 1}: expression inside `run:`")
                    j += 1
                i = j
                continue
            if inline and not inline.group(2).startswith(("|", ">")) and INJECTABLE.search(inline.group(2)):
                c.fail("WF-RUN-INJECTION", f"{name}:{i + 1}", f"{name}:{i + 1}: expression inside `run:`")
            i += 1
    c.ran("WF-SHA-PIN")
    c.ran("WF-DANGEROUS-TRIGGER")
    c.ran("WF-RUN-INJECTION")

    # WF-REVIEWER: the CI reviewer skips forks and can neither write the tree nor merge.
    review = ".github/workflows/claude-review.yml"
    if c.exists(review):
        text = c.read(review)
        if "head.repo.full_name == github.repository" not in text:
            c.fail("WF-REVIEWER", "fork-exclusion", f"{review}: the fork exclusion is gone")
        if re.search(r"^\s*contents:\s*write", text, re.M):
            c.fail("WF-REVIEWER", "contents-write", f"{review}: a job has `contents: write`")
        if "pull_request_target" in text:
            c.fail("WF-REVIEWER", "pull_request_target", f"{review}: runs on pull_request_target")
    else:
        c.fail("WF-REVIEWER", "missing", f"{review} is missing")
    c.ran("WF-REVIEWER")

    # WF-SIGNING: the APK signing job keeps its guards.
    apk = ".github/workflows/android-apk.yml"
    if c.exists(apk):
        text = c.read(apk)
        for needle, key in (
            ("persist-credentials: false", "persist-credentials"),
            ("environment: android-release", "environment"),
            ("scripts/sign_release_apk.sh", "script"),
        ):
            if needle not in text:
                c.fail("WF-SIGNING", key, f"{apk}: `{needle}` is gone")
    c.ran("WF-SIGNING")


# --- the Android host ----------------------------------------------------------------------------

DEBUG_CERT_SHA256 = "56dbf70edfa55524ff1e77f121dfc0e73921e8fcb7dc3be38300be192814f00c"


def check_android(c: Checker) -> None:
    manifest = "android-app/src/main/AndroidManifest.xml"
    text = c.read(manifest) if c.exists(manifest) else ""
    # AND-BACKUP: rule 8 of docs/keys-and-signing.md.
    for needle, key in (
        ('android:allowBackup="false"', "allowBackup"),
        ("android:dataExtractionRules=", "dataExtractionRules"),
        ("android:fullBackupContent=", "fullBackupContent"),
    ):
        if needle not in text:
            c.fail("AND-BACKUP", key, f"{manifest}: `{needle}` is missing")
    rules = "android-app/src/main/res/xml/data_extraction_rules.xml"
    if c.exists(rules):
        xml = c.read(rules)
        for section in ("cloud-backup", "device-transfer"):
            body = re.search(rf"<{section}>(.*?)</{section}>", xml, re.DOTALL)
            if not body:
                c.fail("AND-BACKUP", section, f"{rules}: no <{section}> section")
                continue
            for domain in ("root", "file", "database", "sharedpref"):
                if not re.search(rf'<exclude\s+domain="{domain}"\s+path="\."', body.group(1)):
                    c.fail("AND-BACKUP", f"{section}:{domain}", f"{rules}: <{section}> does not exclude domain {domain}")
    else:
        c.fail("AND-BACKUP", "rules-file", f"{rules} is missing")
    c.ran("AND-BACKUP")

    # AND-MANIFEST: nothing debuggable, no cleartext, every component says whether it is exported.
    if 'android:debuggable="true"' in text:
        c.fail("AND-MANIFEST", "debuggable", f"{manifest}: android:debuggable=\"true\"")
    if 'android:usesCleartextTraffic="true"' in text:
        c.fail("AND-MANIFEST", "cleartext", f"{manifest}: cleartext traffic allowed")
    for m in re.finditer(r"<(activity|receiver|service|provider)\b(.*?)>", text, re.DOTALL):
        attrs = m.group(2)
        name = re.search(r'android:name="([^"]+)"', attrs)
        label = name.group(1) if name else m.group(1)
        if "android:exported=" not in attrs:
            c.fail("AND-MANIFEST", f"exported:{label}", f"{manifest}: {label} does not declare android:exported")
    c.ran("AND-MANIFEST")

    # AND-RELEASE-SIGNING: the release build type has no signingConfig (CLAUDE.md, Conventions).
    build = "android-app/build.gradle.kts"
    if c.exists(build):
        gradle = strip_comments(c.read(build))
        release = re.search(r"\brelease\s*\{(.*?)\n\s*\}", gradle, re.DOTALL)
        if release and "signingConfig" in release.group(1):
            c.fail("AND-RELEASE-SIGNING", "signingConfig", f"{build}: the release build type sets a signingConfig")
        if 'module = "ui-tooling"' not in gradle:
            c.fail("AND-RELEASE-SIGNING", "ui-tooling", f"{build}: Compose ui-tooling is no longer excluded")
    c.ran("AND-RELEASE-SIGNING")

    # AND-CERT-PIN: the release certificate pin is present, well-formed and not the debug key's.
    pin = "android-app/release-signing-cert.sha256"
    if c.exists(pin):
        digests = [line.strip() for line in c.read(pin).splitlines() if line.strip() and not line.startswith("#")]
        if len(digests) != 1 or not re.fullmatch(r"[0-9a-f]{64}", digests[0]):
            c.fail("AND-CERT-PIN", "shape", f"{pin}: expected exactly one 64-hex lowercase digest")
        elif digests[0] == DEBUG_CERT_SHA256:
            c.fail("AND-CERT-PIN", "debug-cert", f"{pin}: the pin is the public debug certificate")
    else:
        c.fail("AND-CERT-PIN", "missing", f"{pin} is missing: tags publish no release-signed APK")
    c.ran("AND-CERT-PIN")


# --- architecture and key material ---------------------------------------------------------------

MNEMONIC_PATH = (
    "domain/src/commonMain/kotlin/eth/serotine/domain/model/Mnemonic.kt",
    "domain/src/commonMain/kotlin/eth/serotine/domain/model/WalletPassword.kt",
    "domain/src/commonMain/kotlin/eth/serotine/domain/repository/KeyStore.kt",
    "domain/src/commonMain/kotlin/eth/serotine/domain/usecase/ImportMnemonicAccount.kt",
    "domain/src/commonMain/kotlin/eth/serotine/domain/usecase/NewRecoveryPhrase.kt",
    "data/src/commonMain/kotlin/eth/serotine/data/keys/EnvelopeKeyStore.kt",
    "data/src/commonMain/kotlin/eth/serotine/data/keys/Bip39.kt",
    "ui/src/commonMain/kotlin/eth/serotine/ui/createaccount/",
    "ui/src/commonMain/kotlin/eth/serotine/ui/mnemonicimport/",
)

CAPTURE_PROTECTED = (
    "ui/src/commonMain/kotlin/eth/serotine/ui/mnemonicimport/MnemonicImportScreen.kt",
    "ui/src/commonMain/kotlin/eth/serotine/ui/createaccount/CreateAccountScreen.kt",
    "ui/src/commonMain/kotlin/eth/serotine/ui/deriveaccount/DeriveAccountScreen.kt",
    "ui/src/commonMain/kotlin/eth/serotine/ui/security/SecurityScreen.kt",
    "ui/src/commonMain/kotlin/eth/serotine/ui/components/Unlock.kt",
)

ALLOWED_HTTPS_HOSTS = {"api.cow.fi", "github.com"}
RPC_WORDS = re.compile(r"infura|alchemy|etherscan|quicknode|JsonRpc|eth_getBalance|eth_blockNumber", re.I)


def check_architecture(c: Checker) -> None:
    # ARCH-MYOTIS-BOUNDARY: io.myotis is imported in :data only.
    for path in c.kotlin_files(*MAIN_MODULES):
        rel = c.rel(path)
        if rel.startswith("data/src/"):
            continue
        if re.search(r"^\s*import\s+io\.myotis", path.read_text(encoding="utf-8"), re.M):
            c.fail("ARCH-MYOTIS-BOUNDARY", rel, f"{rel} imports io.myotis outside :data")
    c.ran("ARCH-MYOTIS-BOUNDARY")

    # ARCH-DEPENDENCY-RULE: :domain is pure Kotlin; :ui never reaches :data.
    forbidden_in_domain = re.compile(r"^\s*import\s+(android\.|androidx\.|io\.ktor|kotlinx\.serialization|io\.myotis|eth\.serotine\.(data|ui))")
    for path in c.kotlin_files("domain/src", main_only=True):
        if forbidden_in_domain.search(path.read_text(encoding="utf-8"), ):
            c.fail("ARCH-DEPENDENCY-RULE", c.rel(path), f"{c.rel(path)}: :domain imports a platform or outer-layer type")
    for path in c.kotlin_files("ui/src"):
        if re.search(r"^\s*import\s+eth\.serotine\.data\b", path.read_text(encoding="utf-8"), re.M):
            c.fail("ARCH-DEPENDENCY-RULE", c.rel(path), f"{c.rel(path)}: :ui imports :data")
    c.ran("ARCH-DEPENDENCY-RULE")

    # KEY-MNEMONIC-PATH: the mnemonic type appears only on its one path (CLAUDE.md, Trust).
    for path in c.kotlin_files("domain/src", "data/src", "ui/src", "android-app/src", "desktop-app/src", "ios-app/src", main_only=True):
        rel = c.rel(path)
        if any(rel == allowed or rel.startswith(allowed) for allowed in MNEMONIC_PATH):
            continue
        if re.search(r"\bMnemonic\b", strip_comments(path.read_text(encoding="utf-8"))):
            c.fail("KEY-MNEMONIC-PATH", rel, f"{rel} names the Mnemonic type off its one path")
    c.ran("KEY-MNEMONIC-PATH")

    # KEY-NO-SERIALIZED-SECRET: no serializable class carries a password or a phrase.
    secret_field = re.compile(r"\bval\s+(password|mnemonic|phrase|privateKey|seedPhrase|words)\s*:")
    for path in c.kotlin_files(*MAIN_MODULES, main_only=True):
        text = strip_comments(path.read_text(encoding="utf-8"))
        for m in re.finditer(r"@Serializable[\s\S]*?class\s+\w+\s*\(([\s\S]*?)\)\s*(?::|\{|$)", text):
            found = secret_field.search(m.group(1))
            if found:
                c.fail("KEY-NO-SERIALIZED-SECRET", f"{c.rel(path)}:{found.group(1)}", f"{c.rel(path)}: a @Serializable class has `{found.group(1)}`")
    c.ran("KEY-NO-SERIALIZED-SECRET")

    # KEY-ENVELOPE-BOUNDS: the Argon2id floor and ceiling (docs/keys-and-signing.md, rule 2).
    envelope = "data/src/commonMain/kotlin/eth/serotine/data/keys/Envelope.kt"
    if c.exists(envelope):
        text = c.read(envelope)
        for needle in (
            "const val MIN_MEMORY_BYTES = 64L * 1024 * 1024",
            "const val MIN_OPS = 2L",
            "const val MAX_MEMORY_BYTES = 256L * 1024 * 1024",
            "const val MAX_OPS = 16L",
            "const val MAX_WORK_BYTES = MAX_MEMORY_BYTES * 4",
        ):
            if needle not in text:
                c.fail("KEY-ENVELOPE-BOUNDS", needle.split("=")[0].split()[-1], f"{envelope}: `{needle}` changed")
        if "Hkdf.sha256(ikm, salt, KEK_INFO, 32)" not in text:
            c.fail("KEY-ENVELOPE-BOUNDS", "kek", f"{envelope}: the KEK derivation changed")
    else:
        c.fail("KEY-ENVELOPE-BOUNDS", "missing", f"{envelope} is missing")
    c.ran("KEY-ENVELOPE-BOUNDS")

    # KEY-WS-BOUND: the weak-subjectivity bound is never raised (trust.md).
    for path in c.kotlin_files(*MAIN_MODULES, main_only=True):
        if "setWsBoundPeriods" in strip_comments(path.read_text(encoding="utf-8")):
            c.fail("KEY-WS-BOUND", c.rel(path), f"{c.rel(path)} calls setWsBoundPeriods")
    c.ran("KEY-WS-BOUND")

    # KEY-BIOMETRIC-PARAMS: rule 3's key parameters and prompt authenticators.
    android_key = "data/src/androidMain/kotlin/eth/serotine/data/keys/AndroidBiometricKey.kt"
    if c.exists(android_key):
        text = strip_comments(c.read(android_key))
        for needle in (
            "setUserAuthenticationRequired(true)",
            "setInvalidatedByBiometricEnrollment(true)",
            "setUnlockedDeviceRequired(true)",
            "AUTH_BIOMETRIC_STRONG",
            "setIsStrongBoxBacked(true)",
        ):
            if needle not in text:
                c.fail("KEY-BIOMETRIC-PARAMS", f"android:{needle}", f"{android_key}: `{needle}` is gone")
    prompter = "android-app/src/main/kotlin/eth/serotine/android/keys/PlatformBiometricPrompter.kt"
    if c.exists(prompter):
        text = strip_comments(c.read(prompter))
        if "BIOMETRIC_STRONG" not in text:
            c.fail("KEY-BIOMETRIC-PARAMS", "prompt:strong", f"{prompter}: BIOMETRIC_STRONG is gone")
        if "DEVICE_CREDENTIAL" in text or "setDeviceCredentialAllowed(true)" in text:
            c.fail("KEY-BIOMETRIC-PARAMS", "prompt:credential", f"{prompter}: allows the device credential")
        if "CryptoObject" not in text:
            c.fail("KEY-BIOMETRIC-PARAMS", "prompt:crypto-object", f"{prompter}: the prompt is not bound to the cipher")
    mac = "data/src/desktopMain/kotlin/eth/serotine/data/keys/MacSecurity.kt"
    if c.exists(mac):
        text = strip_comments(c.read(mac))
        if "PRIVATE_KEY_USAGE or BIOMETRY_CURRENT_SET" not in text:
            c.fail("KEY-BIOMETRIC-PARAMS", "mac:flags", f"{mac}: the biometric key's access-control flags changed")
        if re.search(r"USER_PRESENCE|DEVICE_PASSCODE", text):
            c.fail("KEY-BIOMETRIC-PARAMS", "mac:fallback", f"{mac}: a passcode fallback flag appeared")
    c.ran("KEY-BIOMETRIC-PARAMS")

    # UI-CAPTURE: every screen that takes the password or shows the words is protected.
    for rel in CAPTURE_PROTECTED:
        if not c.exists(rel):
            c.fail("UI-CAPTURE", rel, f"{rel} is missing (moved? add it to CAPTURE_PROTECTED)")
        elif "protectFromScreenCapture()" not in c.read(rel):
            c.fail("UI-CAPTURE", rel, f"{rel} no longer calls protectFromScreenCapture()")
    c.ran("UI-CAPTURE")

    # UI-NO-SAVED-SECRET: nothing secret goes into saved state.
    for path in c.kotlin_files("ui/src", main_only=True):
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if "rememberSaveable" in line and re.search(r"password|phrase|mnemonic|words|seed", line, re.I):
                c.fail("UI-NO-SAVED-SECRET", f"{c.rel(path)}:{n}", f"{c.rel(path)}:{n}: a secret in rememberSaveable")
    c.ran("UI-NO-SAVED-SECRET")

    # LOG-NO-PRINT: :ui and :domain never print (a UiState or a use case could carry a secret).
    printing = re.compile(r"\bprintln\(|\bLog\.[deiwv]\(|\.printStackTrace\(|System\.err")
    for path in c.kotlin_files("ui/src", "domain/src", main_only=True):
        text = strip_comments(path.read_text(encoding="utf-8"))
        if printing.search(text):
            c.fail("LOG-NO-PRINT", c.rel(path), f"{c.rel(path)} prints or logs")
    c.ran("LOG-NO-PRINT")

    # NET-ENDPOINTS: no cleartext URL, no java.net.http, only the known HTTPS hosts, no RPC provider.
    for path in c.kotlin_files(*MAIN_MODULES, main_only=True):
        rel = c.rel(path)
        text = strip_comments(path.read_text(encoding="utf-8"))
        if '"http://' in text or "ws://" in text:
            c.fail("NET-ENDPOINTS", f"{rel}:cleartext", f"{rel}: a cleartext URL literal")
        if re.search(r"^\s*import\s+java\.net\.http", text, re.M):
            c.fail("NET-ENDPOINTS", f"{rel}:java.net.http", f"{rel}: imports java.net.http (platform.md)")
        for m in re.finditer(r"https://([A-Za-z0-9.-]+)", text):
            if m.group(1) not in ALLOWED_HTTPS_HOSTS:
                c.fail("NET-ENDPOINTS", f"{rel}:{m.group(1)}", f"{rel}: HTTPS host {m.group(1)} is not in the allow-list")
        found = RPC_WORDS.search(text)
        if found:
            c.fail("NET-ENDPOINTS", f"{rel}:rpc", f"{rel}: mentions `{found.group(0)}`, a chain-data source that is not the engine")
    c.ran("NET-ENDPOINTS")


# --- published seed phrases in the UI module -----------------------------------------------------


def bip39_words(c: Checker) -> set[str]:
    source = "data/src/commonMain/kotlin/eth/serotine/data/keys/Bip39EnglishWordlist.kt"
    if not c.exists(source):
        return set()
    body = re.search(r'"""(.*?)"""', c.read(source), re.DOTALL)
    return set(body.group(1).split()) if body else set()


def check_ui_phrases(c: Checker) -> None:
    # UI-NO-PUBLISHED-PHRASE: previews and UI tests never carry a real-looking phrase (ui.md).
    words = bip39_words(c)
    if len(words) != 2048:
        c.fail("UI-NO-PUBLISHED-PHRASE", "wordlist", f"could not read the BIP-39 wordlist ({len(words)} words)")
    else:
        for path in c.kotlin_files("ui/src"):
            for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                for literal in re.findall(r'"([^"\\]*)"', line):
                    tokens = literal.lower().split()
                    if len(tokens) >= 12 and all(t in words for t in tokens):
                        c.fail("UI-NO-PUBLISHED-PHRASE", f"{c.rel(path)}:{n}", f"{c.rel(path)}:{n}: a string of {len(tokens)} BIP-39 words")
    c.ran("UI-NO-PUBLISHED-PHRASE")


# --- the build -----------------------------------------------------------------------------------


def check_build(c: Checker) -> None:
    settings = "settings.gradle.kts"
    text = strip_comments(c.read(settings)) if c.exists(settings) else ""
    # GRADLE-REPOSITORIES: project repositories refused; the engine's repository filtered to its group.
    if "FAIL_ON_PROJECT_REPOS" not in text:
        c.fail("GRADLE-REPOSITORIES", "fail-on-project-repos", f"{settings}: FAIL_ON_PROJECT_REPOS is gone")
    myotis = [block for block in brace_blocks(text, r"\bmaven\s*\{") if "myotis" in block.lower()]
    if len(myotis) != 1:
        c.fail("GRADLE-REPOSITORIES", "myotis-repo", f"{settings}: expected one Myotis repository block, found {len(myotis)}")
    else:
        block = myotis[0]
        content = brace_blocks(block, r"\bcontent\s*\{")
        if not content or 'includeGroup("eth.myotis")' not in content[0]:
            c.fail("GRADLE-REPOSITORIES", "myotis-filter", f"{settings}: the Myotis repository is not filtered to eth.myotis")
        # GRADLE-ENGINE-IMMUTABLE: the engine comes from a commit, not a branch (audit N1).
        url = re.search(r'url\s*=\s*uri\("([^"]+)"\)', block)
        if not url:
            c.fail("GRADLE-REPOSITORIES", "myotis-url", f"{settings}: the Myotis repository has no url")
        elif not re.search(r"/[0-9a-f]{40}/?$", url.group(1)):
            c.fail("GRADLE-ENGINE-IMMUTABLE", url.group(1), f"{settings}: the engine repository `{url.group(1)}` names a branch, not a commit")
    c.ran("GRADLE-REPOSITORIES")
    c.ran("GRADLE-ENGINE-IMMUTABLE")

    # GRADLE-VERIFICATION: dependency verification metadata is committed.
    if not c.exists("gradle/verification-metadata.xml"):
        c.fail("GRADLE-VERIFICATION", "metadata", "gradle/verification-metadata.xml is missing: artifacts are not checksummed")
    c.ran("GRADLE-VERIFICATION")

    # GRADLE-WRAPPER: the distribution URL is validated and checksummed.
    props = "gradle/wrapper/gradle-wrapper.properties"
    if c.exists(props):
        text = c.read(props)
        if "validateDistributionUrl=true" not in text:
            c.fail("GRADLE-WRAPPER", "validate-url", f"{props}: validateDistributionUrl is not true")
        if "distributionSha256Sum=" not in text:
            c.fail("GRADLE-WRAPPER", "sha256", f"{props}: no distributionSha256Sum")
        if not re.search(r"distributionUrl=https\\?://services\.gradle\.org/", text):
            c.fail("GRADLE-WRAPPER", "url", f"{props}: the distribution comes from somewhere other than services.gradle.org")
    c.ran("GRADLE-WRAPPER")


# --- the repository ------------------------------------------------------------------------------

SECRET_PATTERNS = (
    ("github-token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b")),
    ("aws-key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("private-key", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY")),
    ("slack-token", re.compile(r"\bxox[baprs]-[0-9A-Za-z-]{10,}\b")),
    ("google-api-key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("anthropic-key", re.compile(r"\bsk-ant-[A-Za-z0-9_-]{20,}\b")),
)
KEYSTORE_SUFFIXES = (".jks", ".p12", ".pfx", ".keystore", ".pem", ".key")
ALLOWED_KEY_FILES = {"android-app/debug.keystore"}


def tracked_files(c: Checker) -> list[str]:
    try:
        out = subprocess.run(["git", "-C", str(c.repo), "ls-files", "-z"], capture_output=True, check=True)
        return [f for f in out.stdout.decode("utf-8", "replace").split("\0") if f]
    except (OSError, subprocess.CalledProcessError):
        return [p.relative_to(c.repo).as_posix() for p in c.repo.rglob("*") if p.is_file() and "/.git/" not in p.as_posix() and "/build/" not in p.as_posix()]


def check_repository(c: Checker) -> None:
    files = tracked_files(c)
    # REPO-NO-KEY-FILES: no key material in the tree but the public debug keystore.
    for rel in files:
        if rel.endswith(KEYSTORE_SUFFIXES) and rel not in ALLOWED_KEY_FILES:
            c.fail("REPO-NO-KEY-FILES", rel, f"{rel}: a key or certificate file is tracked")
    c.ran("REPO-NO-KEY-FILES")
    # REPO-NO-SECRETS: no credential-shaped string in a tracked text file.
    for rel in files:
        path = c.repo / rel
        if not path.is_file() or path.stat().st_size > 2_000_000:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for label, pattern in SECRET_PATTERNS:
            if pattern.search(text):
                c.fail("REPO-NO-SECRETS", f"{rel}:{label}", f"{rel}: looks like a {label}")
    c.ran("REPO-NO-SECRETS")


# --- main ----------------------------------------------------------------------------------------

CHECKS: tuple[Callable[[Checker], None], ...] = (
    check_workflows,
    check_android,
    check_architecture,
    check_ui_phrases,
    check_build,
    check_repository,
)


def load_baseline(path: Path) -> dict[tuple[str, str], str]:
    accepted: dict[tuple[str, str], str] = {}
    if not path.exists():
        return accepted
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].rstrip()
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            print(f"::warning::baseline line ignored (expected CHECK-ID<TAB>key): {raw!r}")
            continue
        accepted[(parts[0].strip(), parts[1].strip())] = raw
    return accepted


def main(argv: Iterable[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parent.parent), help="repository root")
    parser.add_argument("--baseline", default=None, help="accepted violations (default: scripts/security-baseline.txt)")
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument(
        "--write-baseline",
        action="store_true",
        help="write every current violation to the baseline file (accepting it) instead of failing",
    )
    args = parser.parse_args(list(argv))
    repo = Path(args.repo).resolve()
    baseline_path = Path(args.baseline) if args.baseline else repo / "scripts" / "security-baseline.txt"

    checker = Checker(repo, args.verbose)
    for check in CHECKS:
        check(checker)
    if args.write_baseline:
        lines = [
            "# Accepted violations of scripts/check_security_invariants.py: CHECK-ID<TAB>key<TAB># why.",
            "# Each is an open finding of the security audit of 2026-11-01, or a decision the owner",
            "# recorded. Remove a line when its finding is fixed; the script reports a stale line.",
            "",
        ]
        for check, key, message in checker.violations:
            lines.append(f"{check}\t{key}\t# {message}")
        baseline_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"wrote {len(checker.violations)} accepted violations to {baseline_path}")
        return 0
    accepted = load_baseline(baseline_path)

    on_ci = bool(os.environ.get("GITHUB_ACTIONS"))
    new: list[Violation] = []
    seen: set[tuple[str, str]] = set()
    for check, key, message in checker.violations:
        seen.add((check, key))
        if (check, key) in accepted:
            print(f"accepted  {check}  {message}")
        else:
            new.append((check, key, message))
    stale = [raw for (check, key), raw in accepted.items() if (check, key) not in seen]

    for check, key, message in new:
        print(f"{'::error::' if on_ci else 'VIOLATION '}{check}: {message}")
    for raw in stale:
        print(f"{'::warning::' if on_ci else 'stale baseline '}{raw.strip()}  (no longer matches: remove it)")
    print(
        f"\n{len(checker.checks_run)} checks, {len(checker.violations)} violations: "
        f"{len(new)} new, {len(checker.violations) - len(new)} accepted by the baseline, {len(stale)} stale baseline entries"
    )
    return 1 if new else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

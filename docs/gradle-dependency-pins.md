# Gradle dependency pins

Every dependency the Gradle build downloads, plugins included, is pinned
twice: by **version**, in lockfiles, and by **content**, in a SHA-256 list. A
build that meets anything else fails closed. The setup is carried over from
Myotis (its #734, `docs/guides/gradle-dependency-pins.md` there) for #281,
Walletbeat's *Release process* attribute, which asks for dependency locking.

## What is pinned where

| File | Pins | Written by |
|---|---|---|
| `gradle/wrapper/gradle-wrapper.properties` | the Gradle distribution (`distributionSha256Sum`) | by hand, from `https://services.gradle.org/distributions/gradle-<v>-bin.zip.sha256` |
| `<project>/gradle.lockfile` (one per project, plus the root's) | the exact module version every configuration resolved to, transitives included | `--write-locks` |
| `buildscript-gradle.lockfile`, `settings-gradle.lockfile` | the root buildscript classpath (every plugin) and the settings classpath | `--write-locks` |
| `gradle/verification-metadata.xml` | the SHA-256 of every artifact and metadata file (`.jar`, `.aar`, `.klib`, `.module`, `.pom`) of every external module, on every CI host | `--write-verification-metadata sha256` |

The lock catches a **version** moving: a transitive version range, or a
dependency's own dependency bumped by a republished BOM. The hashes catch the
**bytes** moving under a fixed version: a moved tag, a compromised repository
or mirror, a republished engine build on Myotis's `maven-repo` branch (which
names a branch, not a commit). The repositories themselves stay filtered by
group (`settings.gradle.kts`).

Verification is strict once `gradle/verification-metadata.xml` is committed:
every Gradle build checks it, locally and in CI. The locks run in Gradle's
default mode, and the rule of its `STRICT` mode, no configuration without lock
state, is enforced by the `gradle pins` check (below). `STRICT` itself would
break IDE syncs, which resolve configurations a build never does.

## The one command

```bash
scripts/update_gradle_pins.sh            # this host: locks + hashes
scripts/update_gradle_pins.sh --from-ci  # take what the `gradle pins` workflow built for HEAD (needs gh)
```

Locally it runs `resolveAllDependencies`, a task in every project that
resolves every resolvable configuration, three times:

1. Writing the locks. Verification is `lenient` for this run only, because a
   bumped dependency has no hash yet.
2. Writing this host's hashes with the new locks in force, plus
   `assemble lint :desktop-app:checkRuntime`, whose plugins resolve tools only
   while they run (`--quick` skips them). A separate run, because without lock
   constraints conflict resolution also fetches the metadata of every
   candidate version it then discards.
3. Verifying, with both pins in force.

Either mode then compares the metadata with the one committed at `HEAD`
(`scripts/check_pins_additive.py`): new entries pass, and a changed hash of an
artifact already pinned, a removed entry or a changed `<configuration>` block
stops the script, naming each. Bytes that changed under a pinned version are
what pinning exists to catch: find out why before passing `--allow-repin`.

Some configurations cannot resolve on a given host by design; the task prints
those as `skipped` and goes on: KGP's commonizer inputs
(`iosMainCInterop`, …), KGP's IDE-only `*DependenciesMetadata` configurations,
and the Android ones without an SDK. A **pin** failure always fails the task: a
missing or wrong hash, a graph that differs from its lock, or a configuration
that resolves with no lock state at all.

## Hosts: why CI finishes the job

Some artifacts exist once per **build host**: Compose desktop's `currentOs`
runtime and its skiko natives, Kotlin/Native's compiler bundle, aapt2's
per-OS jar. A Linux machine never downloads the macOS ones, so it cannot pin
them. The `gradle pins` workflow (`.github/workflows/gradle-pins.yml`) runs on
the five hosts that run Gradle in CI: Linux x64, Windows x64, Linux arm64
(while the repository is public), macOS x64 (an x86_64 JDK under Rosetta 2, as
the Intel dmg is built) and macOS arm64, in that order. Each host checks the
committed pins, then adds its own entries to the previous host's file. The
first host also rewrites the lockfiles, so the chain can make every pin from
nothing. The last upload, `gradle-pins-merged`, is the union.

The workflow never writes to the repository: a person takes its upload with
`--from-ci`, reviews it and commits it. So the loop after a dependency change
is:

1. Bump `gradle/libs.versions.toml`, and run `scripts/update_gradle_pins.sh`
   if you can run Gradle (skip this where you cannot, as in a cloud session:
   the chain writes the locks too).
2. Commit and push. `gradle pins` runs on the PR. A host whose pins are
   missing fails, and its log names them.
3. Once the chain has finished, run `scripts/update_gradle_pins.sh --from-ci`
   (or download the `gradle-pins-merged` artifact of the run and copy its
   files into place). Commit and push, and `gradle pins` goes green.
4. **Review the diff like code.** Every new `<component>` is an artifact the
   build will run or ship. Look for anything unexpected: a group nobody
   added, or a version the catalog does not name.

The lockfiles hold no host-specific entries: the host-specific modules are
listed in `dependencyLocking.ignoredDependencies` (root `build.gradle.kts`).
Their versions still follow the pinned Compose version, and their bytes are
still pinned per host in the metadata. A lock mismatch on one host means a new
host-specific module: add it to that list; do not write locks on that host.

`--write-verification-metadata` only ever adds entries, so entries for
versions nothing resolves any more stay behind. `--prune` deletes the file
first and regenerates it from this host; then do steps 2 and 3 so the other
hosts add theirs back, and restore the `<configuration>` block from `HEAD`.

## Day to day

- **A build fails with "Dependency verification failed"** or "is not part of
  the dependency lock state": a dependency changed without its pins. If you
  changed it, run the loop above. If you did not, stop: an artifact's bytes
  changed under a pinned version. Find out why before re-pinning.
- **The wrapper** is regenerated with the checksum, or the `wrapper` task
  drops it: `./gradlew wrapper --gradle-version <v> --gradle-distribution-sha256-sum <sha>`.
- **PGP signatures are not checked**, only SHA-256, as in Myotis. Gradle's
  `pgp` mode writes a trusted key instead of a checksum for each signed
  artifact, so the pin would become "signed by this key" rather than "these
  bytes", and the Myotis engine's artifacts are not signed at all.

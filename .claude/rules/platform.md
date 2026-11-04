---
description: "Kotlin conventions, the Android-first platform rules, the minSdk 29 JDK-API budget, Java 17 class files, the toolchain pins and the HTTP client."
paths:
  - "**/*.kt"
  - "**/*.kts"
  - "gradle/**/*"
  - "gradle.properties"
---

# Kotlin conventions and platform direction

Loaded for every Kotlin and Gradle file.

## Kotlin conventions

- Official Kotlin style (`.editorconfig`): 120 columns, trailing commas on
  multi-line declarations.
- Immutable by default: `val`, `data class`, `List` (never `MutableList`) in
  every signature.
- Value classes for identifiers, validated at construction, so an invalid one
  cannot exist.
- Structured concurrency only: `viewModelScope`, and `runTest` in tests.
  Never `GlobalScope`. Nothing blocks the main thread.
- `expect`/`actual` in the smallest source set that needs it. Common code
  never branches on the platform.
- KDoc on every public type: what it is, what it guarantees, what it never
  does.
- A test that needs a device or the network is `@Ignore`d by default and
  documented.

## Android compatibility is a first-class concern

Serotine runs inside an Android app (`:android-app`, minSdk 29). Every change
must keep it working:

- Avoid JVM-only APIs that the Android runtime or `coreLibraryDesugaring`
  cannot cover.
- Mind APK and DEX size.
- Prefer libraries with known Android support.
- `java.net.http` is not desugared and does not exist below API 33. Never use
  it.

## The minSdk 29 JDK-API budget

Verified empirically against R8's backport list and the desugar_jdk_libs
2.1.5 config (2026-08, in Myotis).

Safe at minSdk 29, through D8 backports and desugaring: `List/Set/Map.of`
and `copyOf`, `Map.entry`, `String.strip/isBlank/repeat`,
`Collection.toArray(IntFunction)`, `Optional.isEmpty`, `Predicate.not`,
`Collectors.toUnmodifiable*`, and (from desugar_jdk_libs 2.1.5)
`Stream.toList()`.

NOT covered. Write the few lines yourself instead:

- `CompletableFuture.failedFuture/orTimeout/exceptionallyCompose`
- `java.util.HexFormat`
- `BigInteger.intValueExact/longValueExact`
- `Path.of` (use `Paths.get`)
- `InputStream.readAllBytes/readNBytes/transferTo` (a manual drain loop)
- `Files.readString/writeString` (`Files.readAllBytes` + `Files.write`)

In Kotlin common code most of this never comes up: prefer the stdlib and
kotlinx. It matters in `androidMain`, in `desktopMain` and in every
dependency.

Enforcement: `scripts/check_apk_min_api.py`, run by the android-apk workflow
against the built APKs' dex, the post-desugaring ground truth, on pushes to
`main`, on PRs into `main` and on dispatch. A part PR into a feature branch
does not build the APK, so for a feature plan this check first runs at the
final PR, unless a part dispatches the workflow. A first-party referrer
(`eth/serotine/*`, `io/myotis/*`) can never be allowlisted;
`scripts/min-api-allowlist.txt` is for reviewed third-party residue only.

## Java 17 class files for everything Android loads

- The Android target of every KMP module and `:android-app` compile to Java
  17 (`jvmTarget 17` + `compileOptions 17`).
- So does `:domain`'s JVM target: it has no Android target, so that variant
  is what the Android app dexes.
- The toolchain that runs the compilers is Java 21. A JVM target nothing on
  Android consumes may emit 21 class files.
- Diverge only when a transitive dependency forces it, and document the
  reason in the module's build file.

## Kotlin + Compose Multiplatform everywhere

- New code is Kotlin in `commonMain`, unless it is a platform seam.
- Public APIs in the shared modules use no Java-only types that would block
  Kotlin/Native (`CompletableFuture`, `java.nio.file.Path`, ...). Use
  suspending functions and `Flow` instead.
- The iOS targets exist on every shared module from day one, so that stays
  true.

## Toolchain pins mirror Myotis's

`gradle/libs.versions.toml` holds one known-good Kotlin/Compose stack for the
engine library and its host. AGP and Gradle are newer here, for the reason the
catalog states. Bump both repositories together.

## HTTP client: Ktor

When a feature needs HTTP at all (`.claude/rules/data-sources.md` says when),
it uses Ktor. Not OkHttp, not `java.net.http`.

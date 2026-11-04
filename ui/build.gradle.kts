// :ui — the shared Compose Multiplatform presentation layer: screens, their ViewModels
// and UI state types (MVVM, docs/architecture.md), plus AppGraph, the composition
// root's contract. Depends on :domain only — a ViewModel takes use cases, never a
// repository, and nothing here knows :data or a host. Targets: Android + Desktop JVM
// + iOS (device and simulator; built on macOS only, see gradle.properties).

import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// The version string Settings shows under About, generated into commonMain so EVERY host
// (Android, Desktop, later iOS) reads the same constant with no per-host wiring.
//
// project.version never loses its qualifier in this repo: a release is a pushed
// `v<x.y.z>` TAG on the commit, and the packaging only strips "-SNAPSHOT" to form
// the installer versions. So the version string alone cannot tell a release build
// from a dev build. Git can:
//
//   clean checkout of a v* tag  ->  "0.1.0"                  a real release build
//   ... of a pre-release tag    ->  "0.1.0-rc1"              the tag as cut (v0.1.0-rc1)
//   any other commit            ->  "0.1.0-fbf551"           last release + 6 sha digits
//   ... after a pre-release     ->  "0.1.0-rc1-fbf551"       a pre-release counts as one
//   dirty tree on a v* tag      ->  "0.1.0-fbf551"           NOT what shipped — a dev build
//   git but no tags reachable   ->  "0.1.0-SNAPSHOT-fbf551"  see below
//   no git at all (src tarball) ->  "0.1.0-SNAPSHOT"         project.version verbatim
//
// The tagless row deliberately KEEPS the qualifier: its base is project.version,
// the version being worked TOWARD, while every other row's base is the last version
// actually RELEASED. Every probe degrades to "" instead of failing the build, so a
// git-less checkout still builds — it just shows the least specific label.
//
// A ValueSource keeps this configuration-cache-safe AND lazy, so unrelated
// invocations fork no git at all.
abstract class AppVersionSource : ValueSource<String, AppVersionSource.Params> {
    interface Params : ValueSourceParameters {
        val repoDir: DirectoryProperty
        val projectVersion: Property<String>
    }

    @get:Inject abstract val execOperations: ExecOperations

    /** `git <args>` stdout, or "" when git is missing or the command exits non-zero. */
    private fun git(vararg args: String): String = try {
        val out = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine(listOf("git") + args)
            workingDir = parameters.repoDir.get().asFile
            standardOutput = out
            // `describe` fails on every untagged commit — an expected answer here,
            // not something to print on each build.
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        if (result.exitValue == 0) out.toString(Charsets.UTF_8).trim() else ""
    } catch (_: Exception) {
        "" // git not on PATH
    }

    /** True when tracked files differ from HEAD. Untracked files are ignored, as `describe --dirty` does. */
    private fun treeIsPatched(): Boolean =
        git("status", "--porcelain", "--untracked-files=no").isNotEmpty()

    /**
     * Newest RELEASED version reachable from HEAD, a pre-release (v0.3.0-rc1) included, or null
     * when none is. `versionsort.suffix=-` sorts a pre-release below its final release, so
     * v0.3.0 wins over v0.3.0-rc1 once both are tagged.
     */
    private fun lastRelease(shape: Regex): String? =
        // Not `describe --abbrev=0`: that returns exactly ONE tag, the nearest, so a single
        // off-shape tag on the release line would fail the shape check and silently demote
        // every descendant commit to the project.version fallback.
        git("-c", "versionsort.suffix=-", "tag", "--list", "v[0-9]*", "--merged", "HEAD", "--sort=-v:refname")
            .lineSequence().map { it.trim() }
            .firstOrNull { shape.matches(it) }?.removePrefix("v")

    override fun obtain(): String {
        val projectVersion = parameters.projectVersion.get()
        val sha = git("rev-parse", "--short=6", "HEAD")
        if (sha.isEmpty()) return projectVersion // no git, not a repo, or no commits yet
        // A release is a tag of the shapes the release guard accepts (release-version-guard.yml):
        // vX.Y.Z, and a pre-release's vX.Y.Z-<suffix> (v0.3.0-rc1).
        val shape = Regex("""^v\d+(\.\d+)*(-[0-9A-Za-z][0-9A-Za-z.]*)?$""")
        // --match confines this to the release line: without it describe picks by tag
        // RECENCY, so a `nightly-*` laid down later would be read as this build's release.
        val exactTag = git("describe", "--tags", "--exact-match", "--match", "v[0-9]*")
        if (shape.matches(exactTag) && !treeIsPatched()) return exactTag.removePrefix("v")
        // Keeping the qualifier on the fallback is load-bearing — see the table above.
        return "${lastRelease(shape) ?: projectVersion}-$sha"
    }
}

val uiAppVersion: Provider<String> = providers.of(AppVersionSource::class) {
    parameters.repoDir.fileValue(rootDir)
    parameters.projectVersion.set(project.version.toString())
}

val generateUiBuildInfo by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/buildInfo/kotlin")
    // Locals, not script properties: capturing the script object in doLast is what breaks
    // the configuration cache. Both are lazy, so git runs only when this task is in the
    // graph — the input is what makes the file regenerate on a version bump, a new tag or
    // a new commit, and stay up to date otherwise.
    val version = uiAppVersion
    inputs.property("version", version)
    outputs.dir(outDir)
    doLast {
        // Escaped because this lands inside a Kotlin string LITERAL. Unreachable today —
        // every value is a shape-checked tag, a hex sha, or the root build's literal — but
        // a stray " or \ would emit a file that doesn't parse, and a $ would emit a
        // template expression, both surfacing as a compile error in generated code.
        val literal = version.get()
            .replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\${'$'}")
        val f = outDir.get().file("eth/serotine/ui/BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            """
            // GENERATED by :ui's generateUiBuildInfo task — do not edit.
            package eth.serotine.ui

            /** The app version Settings shows under About. */
            const val APP_VERSION: String = "$literal"
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    jvmToolchain(21)

    androidTarget {
        compilerOptions {
            // Java 17 class files for Android (CLAUDE.md, "Platform & language direction").
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            // The TaskProvider carries the task dependency, so every target's compile
            // (including the Kotlin/Native iOS ones) generates BuildInfo.kt first.
            kotlin.srcDir(generateUiBuildInfo)
            dependencies {
                api(project(":domain"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(libs.kotlinx.coroutines.core)
                // ViewModel + viewModelScope, viewModel {} and collectAsStateWithLifecycle:
                // the multiplatform lifecycle, the MVVM seam of docs/architecture.md.
                implementation(libs.lifecycle.viewmodel.compose)
                implementation(libs.lifecycle.runtime.compose)
                // One destination per screen; each back-stack entry is the
                // ViewModelStoreOwner of its screen's ViewModel, so leaving a screen
                // clears its ViewModel (the seed screen relies on that).
                implementation(libs.navigation.compose)
                // System back on the Send review (back to compose, held while signing). It is in
                // every build already, through navigation-compose; declared for its API.
                implementation(libs.compose.ui.backhandler)
                implementation(libs.material.icons.core)
                // Keccak-256 for EIP-55, the checksum every address is shown with (components/Eip55.kt).
                // :domain computes EIP-55 too, to check a typed address at the input
                // (hasValidChecksum): the owner's ruling of 2026-10-18 (#50) allows it there for that,
                // and the screens format with this copy of the same library.
                implementation(libs.kotlincrypto.sha3)
                // The multiplatform @Preview every screen's previews carry; Android Studio
                // renders it on the Android target (see ui-tooling below).
                implementation(compose.components.uiToolingPreview)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // Desktop-JVM UI tests: drive the real composables headless (skiko software
        // rendering — no display needed, CI-safe). Screen-level behaviour is pinned here.
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.uiTestJUnit4)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// What Android Studio's preview pane renders with, in the debug variant only. The Android
// app keeps it out of its APKs (android-app/build.gradle.kts).
dependencies {
    debugImplementation(compose.uiTooling)
}

android {
    namespace = "eth.serotine.ui"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// `-Pscreenshots=<dir>` (relative to the repository root): the preview tests also save every
// preview as a PNG there (RenderPreviews.kt) — the screenshots a PR that changes the UI
// carries (CLAUDE.md, "Pull requests and code review").
val screenshotDir: String? = providers.gradleProperty("screenshots").orNull?.let { rootDir.resolve(it).absolutePath }
tasks.named<Test>("desktopTest") {
    screenshotDir?.let { systemProperty("serotine.screenshots", it) }
}

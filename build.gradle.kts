// Root build: plugin versions (applied per module), the ONE project version every
// installable app derives its own version from, and the release-version query
// the tag guard makes (.github/workflows/release-version-guard.yml).

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "eth.serotine"
    version = "0.2.0-SNAPSHOT"
}

// The release version — project.version minus the -SNAPSHOT suffix — and the
// BUILD NUMBER the installable apps derive from it, defined ONCE here and read
// by :android-app (versionCode) via rootProject.extra and by the iOS app's
// Xcode build via :printReleaseVersion (ios-app/xcode/project.yml), so a
// release sweep can never leave an app reporting the previous release.
//
// MAJOR*1_000_000 + MINOR*1_000 + PATCH is order-preserving for every semver
// bump (0.1.0 -> 1000, 0.2.0 -> 2000, 1.0.0 -> 1_000_000), so it is strictly
// increasing forever and — unlike a dotted "0.1.0" — a valid iOS
// CFBundleVersion for the day the iOS host arrives. It deliberately does NOT
// encode a -SNAPSHOT/-rc suffix: a re-cut of the same version gets the same
// number, so a re-cut bumps PATCH. MINOR/PATCH < 1000 keeps it under Android's
// 2_100_000_000 versionCode ceiling until MAJOR 2100.
val releaseVersion: String = project.version.toString().substringBefore('-')
val releaseBuildNumber: Int = releaseVersion.split('.').let { parts ->
    require(parts.size == 3 && parts.all { it.toIntOrNull() != null }) {
        "Unexpected project version '${project.version}': expected numeric MAJOR.MINOR.PATCH"
    }
    require(parts[1].toInt() < 1000 && parts[2].toInt() < 1000) {
        "MINOR/PATCH must stay below 1000 for the build-number mapping: ${project.version}"
    }
    parts[0].toInt() * 1_000_000 + parts[1].toInt() * 1_000 + parts[2].toInt()
}
extra["releaseVersion"] = releaseVersion
extra["releaseBuildNumber"] = releaseBuildNumber

// Release CI reads the version from HERE (via Gradle, so it can never drift from
// what the build actually uses) and refuses to publish when the pushed `v*` tag
// disagrees: otherwise tagging without bumping the version goes green and ships
// assets named after the tag while the binaries inside report the older version.
// This reads the ROOT project's version, which is what the apps see only because
// `allprojects` above sets ONE version for the whole build — give a module its
// own version and it silently stops being covered by the guard.
//
// Marker-prefixed output: `./gradlew -q` still lets non-task text reach stdout
// (wrapper distribution download progress on a cold runner, JVM/plugin warnings),
// so the consumer greps for the marker instead of trusting the last line.
//
// It also prints releaseBuildNumber=<n>, for the iOS app's Xcode build, which
// stamps both into the built Info.plist: Xcode reads its build settings before
// any script runs, so they cannot come from Gradle as settings, and a committed
// copy would be a second pin. The guard's pattern does not match that line.
tasks.register("printReleaseVersion") {
    group = "help"
    description = "Print the release version (project.version without -SNAPSHOT) as releaseVersion=<x.y.z>, " +
        "and the build number as releaseBuildNumber=<n>"
    // The task action captures plain values, so it stays configuration-cache
    // compatible.
    doLast {
        println("releaseVersion=$releaseVersion")
        println("releaseBuildNumber=$releaseBuildNumber")
    }
}


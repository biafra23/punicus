// :android-app — the Android host. Its composition root (SerotineApplication) builds
// the AppGraph from :data's adapters and MainActivity renders the shared :ui. Two apps
// come out of it: the release one (eth.serotine) and the debug one (eth.serotine.debug,
// its own label and amber icon), see the build types below.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// The root build's release version (project.version minus -SNAPSHOT, the value
// the tag guard compares against), so a release sweep can't leave the app
// reporting a stale version in Settings → Apps.
val releaseVersion = rootProject.extra["releaseVersion"] as String

// versionCode is DERIVED too: the release guard does not check it, so as a
// hand-bumped literal a forgotten bump would ship an APK that refuses to install
// over the previous one (INSTALL_FAILED_VERSION_DOWNGRADE) with green CI. The
// mapping (and why it is monotonic) lives in the root build's releaseBuildNumber.
val derivedVersionCode: Int = rootProject.extra["releaseBuildNumber"] as Int

android {
    namespace = "eth.serotine.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "eth.serotine"
        minSdk = 29
        targetSdk = 35
        versionCode = derivedVersionCode
        versionName = releaseVersion
        // The four ABIs Android devices run today, the ones the native libraries ship for.
        // JNA's AAR also carries stubs for armeabi and MIPS, which Android dropped long ago;
        // this keeps them out of the APK. (Which of the four to ship is the owner's APK-size
        // call: the engine library has only arm64-v8a and x86_64, the key store runs on all four.)
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Core-library desugaring: at minSdk 29 this supplies the Java 9-11 API
        // backports (List.of, String.strip, Collectors.toUnmodifiable*, …) and — from
        // desugar_jdk_libs 2.1.5 — Stream.toList(), for our modules AND third-party
        // jars alike. What it does NOT cover is listed in .claude/rules/platform.md
        // ("minSdk 29 JDK-API budget") and enforced by scripts/check_apk_min_api.py on the APK.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    // Pin the debug signing identity to a keystore checked into the repo so every
    // developer (and CI) signs debug APKs with the same key — the Android Studio
    // defaults (password `android`, alias `androiddebugkey`). That key is PUBLIC, so
    // anything it signs can be "updated" by anyone: which is why the debug build
    // type is a separate app (below), never the release one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // Its own app next to the release one: eth.serotine.debug, "Serotine Debug",
        // amber launcher icon (src/debug/res). Android accepts an update only from the
        // installed app's signer, and the debug signer above is reproducible by anyone,
        // so sharing the release app's package would let any lookalike debug-signed APK
        // install over Serotine and keep its data — for a wallet, its accounts.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        // No signingConfig ON PURPOSE: assembleRelease writes
        // android-app-release-unsigned.apk, and only the tag-only signing job in
        // .github/workflows/android-apk.yml signs it, with the release key from the
        // `android-release` environment's secrets (docs/android-release-signing.md).
        // Signing here would put that key in the environment of the whole Gradle
        // build, third-party plugins and build scripts included.
        release {
            // Turning this on needs keep rules first: for libsecp256k1's reflectively loaded
            // JNI binding (see data/build.gradle.kts), and for JNA, whose AAR ships none —
            // `-keep class com.sun.jna.** { *; }` plus the envelope's own mapping,
            // `-keep class eth.serotine.data.keys.Sodium$* { *; }`; and for the engine, whose
            // AAR ships no rules either, `-keep class io.myotis.** { *; }`. Without them the key
            // store and the light client report themselves unavailable in a minified build.
            isMinifyEnabled = false
        }
    }

    packaging {
        // JNA's native stub arrives with the libsodium bindings' JNA AAR and with the engine
        // library's AAR too (for arm64-v8a and x86_64): the same JNA version, both pinned to
        // 5.18.1, so either copy will do. JNA's classes come from the AAR alone: :data drops
        // the engine's JNA jar on Android.
        jniLibs {
            pickFirsts += "**/libjnidispatch.so"
        }
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
    }
}

kotlin {
    // Gradle runs javac/kotlinc from a Java 21 toolchain; the OUTPUT stays Java 17
    // class files (compileOptions above + jvmTarget here), the Android floor in
    // CLAUDE.md.
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// :ui's debug variant carries Compose's ui-tooling, which Android Studio needs to render
// the screens' previews, and Gradle would hand it on to this app's debug build. Kept out:
// its PreviewActivity is exported and runs whatever composable an intent names, which no
// other app on the device may do in a wallet, a debug one included. Out of every runtime
// classpath, so a build type added later that falls back to :ui's debug variant ships
// without it too; nothing in this app runs it. The -android artifact is what androidx's
// ui-tooling redirects to on Android, named too so a direct dependency on it is cut as
// well. The APK workflow checks the built manifests for it (android-apk.yml).
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.compose.ui", module = "ui-tooling")
    exclude(group = "androidx.compose.ui", module = "ui-tooling")
    exclude(group = "androidx.compose.ui", module = "ui-tooling-android")
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":data"))
    // setContent {} and the composable lambda it takes: the host compiles Compose code
    // of its own, so the runtime and UI artifacts must be on ITS compile classpath
    // (:ui's are `implementation`, not exposed).
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(libs.androidx.activity.compose)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    constraints {
        // JNA's classes come from the libsodium bindings' AAR here (:data drops the engine's
        // JNA jar on Android, which took the engine's own strict pin with it), and the native
        // stub may come from either AAR: both must stay the engine's JNA, or a pickFirst stub
        // pairs with classes of another version and fails at run time only. Strict, so any
        // library that wants another JNA fails the build instead.
        implementation(libs.jna) { version { strictly(libs.versions.jna.get()) } }
    }
}

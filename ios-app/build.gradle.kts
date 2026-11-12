// :ios-app — the iOS host: a Kotlin/Native framework, SerotineKit, whose SerotineWallet is the
// composition root (it builds the AppGraph from :data's adapters) and whose MainViewController()
// renders the shared :ui on that graph, and the Xcode shell in xcode/ that shows it
// (xcode/project.yml, generated into an Xcode project with XcodeGen). The Xcode build runs
// embedAndSignAppleFrameworkForXcode, which links the framework for the configuration and SDK
// Xcode is building. Kotlin/Native builds
// on macOS only: elsewhere both targets are disabled (gradle.properties) and this module
// builds nothing.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    // The targets Myotis's iOS module has: devices and the Apple-silicon simulator.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SerotineKit"
            // Static: the Kotlin code, the Myotis engine's libmyotis_engine.a, libsodium and
            // libsecp256k1 end up in one archive the app links, with nothing to embed or load.
            isStatic = true
            // The Myotis engine uses SystemConfiguration.framework for network monitoring.
            linkerOpts("-framework", "SystemConfiguration")
        }
    }

    sourceSets {
        // The host is platform calls through (Foundation, UIKit, UserNotifications).
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        iosMain.dependencies {
            implementation(project(":ui"))
            implementation(project(":data"))
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

// The app icon: the ANDROID launcher icon (its foreground vector and background colour, both
// generated from assets/serotine_logo.svg by scripts/gen_android_icons.py), rendered by
// scripts/gen_app_icons.java as the 1024 px opaque square iOS masks itself, into the app's
// asset catalog. The Xcode build's Gradle phase runs it before the catalog is compiled
// (xcode/project.yml); the PNG is git-ignored, so nothing binary is committed.
val appIcon = layout.projectDirectory.file("xcode/Serotine/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
tasks.register<Exec>("generateAppIcon") {
    group = "build"
    description = "Render the iOS app icon from the Android launcher icon resources"
    val script = rootProject.file("scripts/gen_app_icons.java")
    val res = rootProject.file("android-app/src/main/res")
    val foreground = res.resolve("drawable/ic_launcher_foreground.xml")
    val background = res.resolve("values/ic_launcher_background.xml")
    inputs.files(script, foreground, background)
        .withPropertyName("iconSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(appIcon).withPropertyName("appIcon")
    executable = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }.get().executablePath.asFile.absolutePath
    args(
        "-Djava.awt.headless=true", script.absolutePath, foreground.absolutePath, background.absolutePath,
        "--ios", appIcon.asFile.absolutePath,
    )
}

// Off a Mac the iOS targets are disabled, and :data's along with them (its cinterop runs on a Mac
// only), so its iOS code is not there for this module's shared iOS source set to compile
// against: everything here is skipped instead, as the targets are. `clean` and the help tasks
// stay usable.
if ("mac" !in System.getProperty("os.name").lowercase()) {
    tasks.configureEach {
        if (name != "clean" && group != "help") enabled = false
    }
}

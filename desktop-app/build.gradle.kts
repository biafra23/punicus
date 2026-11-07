// :desktop-app — the desktop JVM host (Linux, macOS, Windows). main() is the composition
// root: it builds the AppGraph from :data's adapters and opens one window on the shared
// :ui. Compose Desktop's jpackage integration below produces the three installers the
// release workflows publish (.deb, .dmg, .msi).

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin { jvmToolchain(21) }

// Installer versions, DERIVED from the root build's release version (project.version
// minus -SNAPSHOT, already shape-checked there and the value the tag guard compares
// against) so a release sweep can't leave an installer stamped with the previous release.
val releaseVersion = rootProject.extra["releaseVersion"] as String

// jpackage REJECTS a major of 0 on macOS (--app-version must start above 0), so
// the .dmg cannot carry the honest 0.x version. The rule: macOS MAJOR is always
// the project MAJOR plus one (0.1.0 -> 1.1.0, 1.0.0 -> 2.0.0). Adding a constant
// to the major is order-preserving, so the emitted version is strictly increasing
// for every bump, forever — which is what macOS needs to treat a new bundle as an
// upgrade rather than a downgrade — and it has no cliff at the real 1.0.0.
val macOsPackageVersion: String = releaseVersion.split('.').let { parts ->
    "${parts[0].toInt() + 1}.${parts[1]}.${parts[2]}"
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":data"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    // Dispatchers.Main on the desktop JVM is the Swing event thread; the ViewModels'
    // viewModelScope needs it on the runtime classpath.
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(kotlin("test"))
}

// The Myotis engine's host library (libmyotis_engine.{dylib,so}) is not inside its jar
// (docs/embedding-myotis.md): eth.myotis:myotis-engine-natives-<platform> carries it, one
// artifact per desktop platform at the engine's version. jpackage is host-bound, so each
// installer leg ships its own host's: stageMyotisLibrary takes the library out of that jar
// into Compose's app resources, with JNA's native stub beside it, and the packaged app loads
// both from its bundle (Main.kt points the engine at the library; jna.boot.library.path
// below takes the stub), never from a temporary file a signed macOS bundle may refuse to
// load. `:desktop-app:run` loads them from the same staged resources. Windows has no
// natives artifact yet: there the app runs with no light client, and its balances say so.
// `-PmyotisLibraryDir=<dir>` makes the run task use a host library built from Myotis instead
// (`./gradlew cargoBuildHost` there: rust/target/release); only the run task gets it, so a
// local packaging never bakes a developer's path into an installer.
val hostOs = System.getProperty("os.name").lowercase()
val hostArch = System.getProperty("os.arch").lowercase()
val hostArm = hostArch == "aarch64" || hostArch == "arm64"
/** The host's natives artifact suffix, JNA's resource prefix; null where none is published. */
val myotisPlatform: String? = run {
    val arch = when (hostArch) {
        "aarch64", "arm64" -> "aarch64"
        "amd64", "x86_64" -> "x86-64"
        else -> return@run null
    }
    when {
        hostOs.contains("mac") -> "darwin-$arch"
        hostOs.contains("linux") -> "linux-$arch"
        else -> null
    }
}
val myotisLibraryName = when {
    hostOs.contains("mac") -> "libmyotis_engine.dylib"
    hostOs.contains("win") -> "myotis_engine.dll"
    else -> "libmyotis_engine.so"
}
val myotisResources = layout.buildDirectory.dir("myotisAppResources")
val stageMyotisLibrary = myotisPlatform?.let { platform ->
    tasks.register("stageMyotisLibrary") {
        group = "build"
        description = "Stage the Myotis engine's host library and JNA's stub into Compose app resources"
        val natives = configurations.detachedConfiguration(
            dependencies.create("eth.myotis:myotis-engine-natives-$platform:${libs.versions.myotis.get()}@jar"),
        ).also { it.isTransitive = false }
        val jna = configurations.detachedConfiguration(
            dependencies.create("net.java.dev.jna:jna:${libs.versions.jna.get()}@jar"),
        ).also { it.isTransitive = false }
        // Compose's subdirectory for this host's resources; the bundle flattens it into one dir.
        val osArchDir = (if (platform.startsWith("darwin")) "macos" else "linux") + if (hostArm) "-arm64" else "-x64"
        // On a Mac under a .dylib name: jpackage's signing pass signs *.dylib and leaves a
        // .jnilib unsigned, and JNA's boot-path lookup tries both names.
        val (stubEntry, stubName) = if (platform.startsWith("darwin")) {
            "com/sun/jna/$platform/libjnidispatch.jnilib" to "libjnidispatch.dylib"
        } else {
            "com/sun/jna/$platform/libjnidispatch.so" to "libjnidispatch.so"
        }
        // jpackage bundles the JDK 21 toolchain's architecture, the libraries here are the Gradle
        // JVM's: a mixed pair packages an app whose engine never loads. The installer workflows
        // pin both to one; a local packaging is refused instead.
        val toolchainArch = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.map { launcher ->
            val release = launcher.metadata.installationPath.asFile.resolve("release")
            runCatching { Regex("OS_ARCH=\"([^\"]+)\"").find(release.readText())?.groupValues?.get(1) }
                .getOrNull()?.lowercase() ?: "unknown"
        }
        inputs.files(natives, jna)
        inputs.property("toolchainArch", toolchainArch)
        outputs.dir(myotisResources)
        doLast {
            val toolchain = toolchainArch.get()
            check(toolchain == "unknown" || (toolchain == "aarch64" || toolchain == "arm64") == hostArm) {
                "Gradle runs under a $hostArch JVM but the JDK 21 toolchain jpackage bundles is $toolchain: " +
                    "the app would carry another architecture's engine. Run Gradle under a JDK of the target " +
                    "architecture (JAVA_HOME plus -Porg.gradle.java.installations.paths=\$JAVA_HOME, as the " +
                    "installer workflows do)."
            }
            val root = myotisResources.get().asFile
            root.deleteRecursively()
            val dest = root.resolve(osArchDir)
            copy {
                from(zipTree(natives.singleFile)) { include("$platform/$myotisLibraryName") }
                into(dest)
                eachFile { path = name }
                includeEmptyDirs = false
            }
            copy {
                from(zipTree(jna.singleFile)) { include(stubEntry) }
                into(dest)
                eachFile { path = stubName }
                includeEmptyDirs = false
            }
            check(dest.resolve(myotisLibraryName).isFile) {
                "$platform/$myotisLibraryName not in ${natives.singleFile}"
            }
            check(dest.resolve(stubName).isFile) { "$stubEntry not in ${jna.singleFile}" }
        }
    }
}
// Compose's own prepareAppResources copies the resources into the run's and the bundle's
// resources dir, so the staging runs before it.
if (stageMyotisLibrary != null) {
    tasks.configureEach { if (name == "prepareAppResources") dependsOn(stageMyotisLibrary) }
}
val myotisLibraryDir: Provider<String> = providers.gradleProperty("myotisLibraryDir")
// Through a provider, not jvmArgs: Compose sets the run task's jvmArgs after this, replacing
// whatever was added here, so neither property ever reached a development run.
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    // Locals, not the script's vals: a provider holding the script object cannot be cached.
    val libraryDir = myotisLibraryDir
    val libraryName = myotisLibraryName
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOfNotNull(
                libraryDir.orNull?.let { "-Dmyotis.engine.lib=${File(it, libraryName).absolutePath}" },
                // The desktop's debug build: a development run logs the adapters' diagnostics
                // (which proof of a Safe's read failed or straddled) and the engine's own log to
                // stderr. No installer sets it.
                "-Dserotine.debugLog=true",
            )
        },
    )
}

// ---------------------------------------------------------------------------
// The app icons: the ANDROID launcher icon, rendered for each desktop. scripts/gen_app_icons.java
// reads the launcher icon's committed resources (the mark's foreground vector and its
// background colour, both generated from assets/serotine_logo.svg by
// scripts/gen_android_icons.py) and writes the dmg's .icns, the msi's .ico and a PNG for the
// deb, the window and the tray, so the installers and the APK carry the same icon by
// construction and nothing binary is committed. Plain JDK in single-file source mode on the
// JDK 21 toolchain, headless so the render never touches the window server. The PNG goes on
// the classpath (AppIcon.kt).
// ---------------------------------------------------------------------------
val appIcons = layout.buildDirectory.dir("generated/app-icons")
val appIconResources = layout.buildDirectory.dir("generated/app-icon-resources")
val generateAppIcons = tasks.register<Exec>("generateAppIcons") {
    group = "build"
    description = "Render the desktop app icons (.icns, .ico, .png) from the Android launcher icon resources"
    val script = rootProject.file("scripts/gen_app_icons.java")
    val res = rootProject.file("android-app/src/main/res")
    val foreground = res.resolve("drawable/ic_launcher_foreground.xml")
    val background = res.resolve("values/ic_launcher_background.xml")
    inputs.files(script, foreground, background)
        .withPropertyName("iconSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(appIcons).withPropertyName("installerIcons")
    outputs.dir(appIconResources).withPropertyName("iconResources")
    executable = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }.get().executablePath.asFile.absolutePath
    args(
        "-Djava.awt.headless=true", script.absolutePath, foreground.absolutePath, background.absolutePath,
        "--icns", appIcons.get().file("Punicus.icns").asFile.absolutePath,
        "--ico", appIcons.get().file("Punicus.ico").asFile.absolutePath,
        "--png", appIconResources.get().file("serotine-icon.png").asFile.absolutePath,
    )
}
sourceSets.main {
    resources.srcDir(files(appIconResources).builtBy(generateAppIcons))
}
// The installers bake the icons in (iconFile below); Compose's packaging tasks do not see
// that as a task dependency.
tasks.configureEach {
    if (name.startsWith("package") || name.startsWith("createDistributable")
        || name.startsWith("createReleaseDistributable")
    ) {
        dependsOn(generateAppIcons)
    }
}

compose.desktop {
    application {
        mainClass = "eth.serotine.desktop.MainKt"
        // JNA loads its native stub from the bundle (stageMyotisLibrary); jpackage's launcher
        // expands $APPDIR. A development run sees a literal, missing $APPDIR, and JNA extracts
        // the stub from its jar as before.
        jvmArgs += "-Djna.boot.library.path=\$APPDIR/resources"
        // Pin the runtime jpackage bundles (via jlink) to the Java 21 toolchain, the one
        // our class files target. Without this, jpackage defaults to whatever JDK runs
        // Gradle.
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            if (stageMyotisLibrary != null) appResourcesRootDir.set(myotisResources)
            // jpackage is host-OS-bound: the .dmg can only be produced on macOS, the .deb
            // only on Linux, the .msi only on Windows (with WiX 3 on the PATH) — one
            // workflow per OS under .github/workflows.
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
            packageName = "Punicus"
            packageVersion = macOsPackageVersion
            description = "Punicus — an Ethereum wallet for accounts and Safes"
            // jpackage builds the deb's Maintainer field as "<vendor> <debMaintainer>",
            // so the human name lives here and debMaintainer stays a bare email.
            vendor = "Dirk Jäckel"
            // The whole JDK module graph, until the app's real module needs are known;
            // trimming to `modules(...)` is a follow-up that saves tens of MB per installer.
            includeAllModules = true
            macOS {
                bundleID = "eth.serotine"
                dockName = "Punicus"
                iconFile.set(appIcons.map { it.file("Punicus.icns") })
            }
            linux {
                iconFile.set(appIconResources.map { it.file("serotine-icon.png") })
                packageVersion = releaseVersion
                debMaintainer = "dirk@jaeckel.com"
                menuGroup = "Utility"
            }
            windows {
                iconFile.set(appIcons.map { it.file("Punicus.ico") })
                packageVersion = releaseVersion
                // The msi's UpgradeCode: a newer msi with the SAME code upgrades the
                // installed app in place; a different one installs beside it. Generated
                // once for Punicus and never to be changed (the msi workflow pins a
                // second copy so a change fails there instead of orphaning installs).
                upgradeUuid = "a7691ec4-0334-46d8-8160-599a8dcdbd18"
                // Per-user: installs under %LOCALAPPDATA%\Punicus with no UAC prompt.
                perUserInstall = true
                dirChooser = false
                menu = true
                menuGroup = "Punicus"
                shortcut = true
            }
        }
    }
}

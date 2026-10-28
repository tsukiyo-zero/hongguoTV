// SPDX-License-Identifier: GPL-3.0-only
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin { jvmToolchain(17) }

val appVersion = providers.gradleProperty("guoyingtv.version").orElse("0.1.0")

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("uk.co.caprica:vlcj:4.12.1")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    testImplementation("junit:junit:4.13.2")
}

// The loopback bridge runs as a child JVM, exactly like on macOS. Its jars are staged
// into the packaged app resources so the UI process never loads them itself. Compose
// expects appResourcesRootDir/common (and optional per-OS folders) to hold the files that
// end up in app/resources.
val stageBridge by tasks.registering(Sync::class) {
    dependsOn(project(":bridge").tasks.named("jar"))
    from(project(":bridge").tasks.named("jar"))
    from(project(":bridge").configurations.named("runtimeClasspath"))
    into(layout.buildDirectory.dir("app-resources/common/bridge"))
}

// jpackage copies the app resources before createDistributable runs, so the bridge has to
// be staged first or app/resources ends up without app/resources/bridge.
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stageBridge) }

tasks.processResources {
    inputs.property("version", appVersion)
    filesMatching("guoyingtv.properties") {
        expand("version" to appVersion.get())
    }
}

tasks.withType<JavaExec>().configureEach {
    systemProperty("bridge.dir", layout.buildDirectory.dir("app-resources/common/bridge").get().asFile.absolutePath)
}

compose.desktop {
    application {
        mainClass = "com.hongguotv.windows.MainKt"

        jvmArgs += listOf(
            "-Dfile.encoding=UTF-8",
            "-Dsun.java2d.uiScale.enabled=true",
        )

        nativeDistributions {
            // Portable ZIP only: the updater-free build ships an app-image, so no WiX or
            // MSI is involved.
            packageName = "GuoyingTV"
            packageVersion = appVersion.get()
            // jpackage parses the generated @argfile with the ANSI code page, so the
            // package metadata must stay ASCII or the build fails with
            // "Input length = 1". Chinese strings live in the UI and README instead.
            description = "GuoyingTV desktop client for Windows"
            vendor = "tsukiyo-zero"
            copyright = "GPL-3.0-only"
            appResourcesRootDir.set(layout.buildDirectory.dir("app-resources"))
            // The bridge child process needs jdk.httpserver at runtime; VLC/JNA need
            // jdk.unsupported and a charset set that covers UTF-8 locales.
            modules(
                "java.naming",
                "java.management",
                "jdk.httpserver",
                "jdk.crypto.ec",
                "jdk.unsupported",
                "jdk.charsets",
                "jdk.localedata",
            )
            windows {
                menuGroup = "GuoyingTV"
                shortcut = true
                dirChooser = true
                perUserInstall = true
            }
        }
    }
}

tasks.matching { it.name in setOf("createDistributable", "run", "runDistributable") }
    .configureEach { dependsOn(stageBridge) }

// jpackage builds its runtime image with --strip-native-commands, so the packaged runtime
// has no bin/java.exe. The bridge child process needs a launcher, so copy back the one from
// the same JDK that produced the image (identical build, so the runtime stays consistent).
val addBridgeJavaLauncher by tasks.registering(Copy::class) {
    onlyIf { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    from(File(System.getProperty("java.home"), "bin/java.exe"))
    into(layout.buildDirectory.dir("compose/binaries/main/app/GuoyingTV/runtime/bin"))
}

tasks.matching { it.name == "createDistributable" }
    .configureEach { finalizedBy(addBridgeJavaLauncher) }

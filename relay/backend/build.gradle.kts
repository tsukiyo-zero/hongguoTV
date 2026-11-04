import java.security.MessageDigest
import java.util.Properties

plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("org.jmdns:jmdns:3.5.9")
    testImplementation("junit:junit:4.13.2")
}
application { mainClass.set("com.hongguotv.relay.RelayMainKt") }

tasks.register<JavaExec>("probe") {
    group = "application"
    description = "运行 relay 取流解密探针（ProbeMain）"
    mainClass.set("com.hongguotv.relay.ProbeMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// 发布形态：单文件 fat jar（java -jar guoying-relay-<版本>-all.jar，需系统 ffmpeg 7+）。
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("guoying-relay")
    archiveClassifier.set("all")
    manifest { attributes["Main-Class"] = "com.hongguotv.relay.RelayMainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/versions/*/module-info.class", "module-info.class")
}

// ── macOS 产品化打包：ffmpeg 捆绑（sha256 校验）+ jpackage dmg ────────────────

val ffmpegBundleDir = layout.buildDirectory.dir("ffmpeg-bundle")
val jpackageInputDir = layout.buildDirectory.dir("jpackage-input")
val jpackageOutputDir = layout.buildDirectory.dir("jpackage")

tasks.register("bundleFfmpeg") {
    description = "下载并校验捆绑用 ffmpeg（osxexperts 预编译，arm=9.0.2/intel=8.0.2，均含 VVC 解码器）"
    outputs.file(ffmpegBundleDir.map { it.file("ffmpeg") })
    doLast {
        val dir = ffmpegBundleDir.get().asFile.apply { mkdirs() }
        val arch = when (System.getProperty("os.arch")) {
            "aarch64" -> "arm"
            else -> "intel"
        }
        val zipName = if (arch == "arm") "ffmpeg902arm.zip" else "ffmpeg80intel.zip"
        val url = "https://www.osxexperts.net/$zipName"
        val expected =
            Properties().apply {
                rootProject.file("ffmpeg-hashes.properties").inputStream().use { load(it) }
            }.getProperty(zipName) ?: error("ffmpeg-hashes.properties 缺少 $zipName 的校验值")

        val zipFile = ffmpegBundleDir.get().file(zipName).asFile
        if (!zipFile.exists() || zipFile.length() < 10_000_000L) {
            logger.lifecycle("下载 $url")
            exec {
                // curl 自动继承 https_proxy 环境变量；构建机需代理时以 -Prelay.proxy 传入。
                commandLine("curl", "-sL", "--retry", "3", "--max-time", "600", "-o", zipFile, url)
                providers.gradleProperty("relay.proxy").orNull?.let { proxy ->
                    environment("https_proxy", proxy)
                    environment("http_proxy", proxy)
                }
            }
        }

        copy {
            from(zipTree(zipFile))
            into(dir)
        }
        val binary = File(dir, "ffmpeg").apply { setExecutable(true) }
        val actual =
            MessageDigest.getInstance("SHA-256")
                .digest(binary.readBytes())
                .joinToString("") { "%02x".format(it) }
        check(actual == expected) { "ffmpeg sha256 校验失败：$actual != $expected" }
        exec { commandLine(binary.absolutePath, "-hide_banner", "-h", "decoder=vvc") }
        logger.lifecycle("ffmpeg 捆绑就绪：${binary.absolutePath}")
    }
}

tasks.register("jpackageDmg") {
    description = "打包 macOS dmg（JRE + fatJar + 捆绑 ffmpeg）"
    dependsOn("fatJar", "bundleFfmpeg")
    doLast {
        val fatJarFile = tasks.named<Jar>("fatJar").get().archiveFile.get().asFile
        val inputDir = jpackageInputDir.get().asFile
        inputDir.deleteRecursively() // 清旧布局残留，保证输入目录可复现
        inputDir.mkdirs()
        copy {
            from(fatJarFile)
            into(inputDir.resolve("app"))
        }
        // ffmpeg 以「目录/二进制」布局进包：Contents/app/ffmpeg/ffmpeg（与 RelayLocations.resolveFfmpeg 对应）。
        copy {
            from(ffmpegBundleDir.get().file("ffmpeg"))
            into(inputDir.resolve("app/ffmpeg"))
        }
        val jdkHome = providers.gradleProperty("relay.jdk").orElse(providers.systemProperty("java.home")).get()
        delete(jpackageOutputDir)
        exec {
            commandLine(
                File(jdkHome, "bin/jpackage"),
                "--type", "dmg",
                "--name", "GuoyingRelay",
                "--app-version", "1.0.0",
                "--input", inputDir.resolve("app"),
                "--main-jar", fatJarFile.name,
                "--java-options", "-Dapple.awt.UIElement=true",
                "--dest", jpackageOutputDir.get().asFile,
            )
        }
        logger.lifecycle("dmg 产物：${jpackageOutputDir.get().asFile}")
    }
}

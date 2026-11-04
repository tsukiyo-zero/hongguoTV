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

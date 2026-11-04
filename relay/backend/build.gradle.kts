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

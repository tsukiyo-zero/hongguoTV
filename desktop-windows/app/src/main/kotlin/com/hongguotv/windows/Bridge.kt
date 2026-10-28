// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class BridgeException(message: String) : Exception(message)

/**
 * Runs the same loopback bridge the Mac client ships, as a child JVM. The bridge only
 * listens on 127.0.0.1, requires its random path token, and rejects foreign origins.
 */
class Bridge private constructor(
    private val process: Process,
    private val input: OutputStream,
    private val baseUrl: String,
) {
    private val http =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

    @Volatile private var closed = false

    fun get(path: String, parameters: Map<String, String> = emptyMap()): JSONObject =
        call(path, parameters, null)

    fun post(path: String, body: JSONObject = JSONObject(), parameters: Map<String, String> = emptyMap()): JSONObject =
        call(path, parameters, body.toString().toRequestBody(JSON))

    private fun call(path: String, parameters: Map<String, String>, body: okhttp3.RequestBody?): JSONObject {
        val url =
            baseUrl.toHttpUrl().resolve(path)?.newBuilder()?.apply {
                parameters.forEach { (key, value) -> addQueryParameter(key, value) }
            }?.build() ?: throw BridgeException("客户端地址无效，请重新打开应用。")
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply { if (body != null) post(body) }
                .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                throw BridgeException(detail.ifBlank { "请求失败，请重试（HTTP ${response.code}）。" })
            }
            return runCatching { JSONObject(text) }
                .getOrElse { throw BridgeException("客户端返回的数据无法解析，请重试。") }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        // Closing the bridge's stdin asks it to release playback and stop the server.
        runCatching { input.close() }
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        runCatching { http.connectionPool.evictAll() }
        runCatching { http.dispatcher.executorService.shutdownNow() }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        val appRoot: File?
            get() =
                System.getProperty("compose.application.resources.dir")
                    ?.let { File(it).parentFile?.parentFile }

        fun launch(): Bridge {
            val bridgeDir = locateBridge()
            val java = javaExecutable()
            val log = File(AppDirs.data, "bridge.log")
            val command =
                listOf(
                    java.absolutePath,
                    "--add-modules",
                    "jdk.httpserver,jdk.crypto.ec",
                    "-Dfile.encoding=UTF-8",
                    "-cp",
                    File(bridgeDir, "*").path,
                    "com.hongguotv.desktop.MainKt",
                )
            val builder =
                ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .redirectError(ProcessBuilder.Redirect.appendTo(log))
            val process =
                try {
                    builder.start()
                } catch (problem: Exception) {
                    throw BridgeException("客户端服务启动失败：${problem.message ?: "请重新解压客户端"}")
                }
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            val pool = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "bridge-startup").apply { isDaemon = true } }
            val future =
                pool.submit<String> {
                    var line = reader.readLine()
                    // Skip blank lines and anything that is not the handshake URL.
                    while (line != null && !line.startsWith("http://127.0.0.1:")) line = reader.readLine()
                    line.orEmpty()
                }
            val line =
                try {
                    future.get(60, TimeUnit.SECONDS)
                } catch (problem: Exception) {
                    process.destroyForcibly()
                    throw BridgeException("客户端服务启动超时，请重新打开应用。")
                } finally {
                    pool.shutdownNow()
                }
            if (!line.startsWith("http://127.0.0.1:") || !line.endsWith("/")) {
                process.destroyForcibly()
                throw BridgeException("客户端服务未能就绪，请重新打开应用。")
            }
            return Bridge(process, process.outputStream, line)
        }

        private fun locateBridge(): File {
            System.getProperty("bridge.dir")?.let { value ->
                File(value).takeIf { it.isDirectory }?.let { return it }
            }
            val candidates =
                buildList {
                    appRoot?.let { add(File(it, "app/resources/bridge")) }
                    add(File(System.getProperty("user.dir"), "build/app-resources/bridge"))
                    add(File(System.getProperty("user.dir"), "app/build/app-resources/bridge"))
                    add(File(System.getProperty("user.dir"), "../app/build/app-resources/bridge"))
                    add(File(System.getProperty("user.dir"), "app-resources/bridge"))
                }
            return candidates.firstOrNull { dir ->
                dir.isDirectory && dir.listFiles { file -> file.name.endsWith(".jar") }?.isNotEmpty() == true
            } ?: throw BridgeException("未找到内置服务组件，请重新解压客户端。")
        }

        private fun javaExecutable(): File {
            System.getProperty("bridge.java")?.let { value ->
                File(value).takeIf { it.isFile }?.let { return it }
            }
            appRoot?.let { root ->
                listOf("runtime/bin/java.exe", "runtime/bin/java").map { File(root, it) }.firstOrNull { it.isFile }?.let { return it }
            }
            val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
            val bundled = File(System.getProperty("java.home"), "bin/$executable")
            if (bundled.isFile) return bundled
            throw BridgeException("未找到 Java 运行时，请重新解压客户端。")
        }
    }
}

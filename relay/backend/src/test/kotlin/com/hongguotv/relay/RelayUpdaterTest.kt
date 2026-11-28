// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class RelayUpdaterTest {
    @get:Rule
    val temp = TemporaryFolder()

    // 清单解析与字段校验

    @Test
    fun `清单解析通过合法样例`() {
        val manifest =
            RelayUpdater.parseManifest(
                """
                {"version":"1.1.0","notes":"修复若干问题",
                 "mac":{"fileName":"GuoyingRelay-1.1.0.dmg","size":100,"sha256":"${"a".repeat(64)}"},
                 "win":{"fileName":"GuoyingRelay-1.1.0.msi","size":200,"sha256":"${"b".repeat(64)}"}}
                """.trimIndent(),
            )
        assertEquals("1.1.0", manifest.version)
        assertEquals("GuoyingRelay-1.1.0.dmg", manifest.mac?.fileName)
        assertEquals(200L, manifest.win?.size)
    }

    @Test
    fun `清单字段缺失或非法一律拒用`() {
        val bad =
            listOf(
                """{"notes":"缺版本"}""",
                """{"version":"abc","mac":{"fileName":"x.dmg","size":1,"sha256":"${"a".repeat(64)}"}}""",
                """{"version":"1.0","mac":{"fileName":"../evil.dmg","size":1,"sha256":"${"a".repeat(64)}"}}""",
                """{"version":"1.0","mac":{"fileName":"x.dmg","size":0,"sha256":"${"a".repeat(64)}"}}""",
                """{"version":"1.0","mac":{"fileName":"x.dmg","size":1,"sha256":"短哈希"}}""",
                """{"version":"1.0","mac":{"fileName":"x.dmg","size":1,"sha256":"${"A".repeat(63)}z"}}""",
                "不是 json",
            )
        for (text in bad) {
            try {
                RelayUpdater.parseManifest(text)
                org.junit.Assert.fail("应拒用：$text")
            } catch (expected: RuntimeException) {
            }
        }
    }

    @Test
    fun `缺平台块解析为 null`() {
        val manifest =
            RelayUpdater.parseManifest(
                """{"version":"1.2.0","notes":"","win":{"fileName":"x.msi","size":1,"sha256":"${"c".repeat(64)}"}}""",
            )
        assertNull(manifest.mac)
        assertNotNull(manifest.win)
    }

    // 版本比较

    @Test
    fun `三段版本号比较`() {
        assertTrue(RelayUpdater.isNewer("1.0.0", "1.1.0"))
        assertTrue(RelayUpdater.isNewer("1.9.9", "1.10.0"))
        assertTrue(RelayUpdater.isNewer("1.1", "1.1.1"))
        assertFalse(RelayUpdater.isNewer("1.1.0", "1.1.0"))
        assertFalse(RelayUpdater.isNewer("1.1.0", "1.0.9"))
        assertFalse(RelayUpdater.isNewer("2.0.0", "1.9.9"))
        // 非数字段按 0
        assertFalse(RelayUpdater.isNewer("1.1.0", "1.1.0-beta"))
    }

    // 下载与校验（本地 HttpServer fixture）

    private val payload = ByteArray(300 * 1024) { ((it * 31) and 0xff).toByte() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 本地 HttpServer：清单走 relay-update.json，其余任意路径（含安装包名）回payload；计数器供重复下载断言。 */
    private fun serve(
        manifestJson: String,
        payloadBytes: ByteArray = payload,
        hitCount: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(),
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path.endsWith("relay-update.json")) {
                val bytes = manifestJson.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                hitCount.incrementAndGet()
                exchange.sendResponseHeaders(200, payloadBytes.size.toLong())
                exchange.responseBody.use { it.write(payloadBytes) }
            }
        }
        server.start()
        return server
    }

    private fun manifestJson(fileName: String, bytes: ByteArray, sizeOverride: Long = bytes.size.toLong()) =
        """
        {"version":"9.9.9","notes":"测试",
         "mac":{"fileName":"$fileName","size":$sizeOverride,"sha256":"${sha256(bytes)}"}}
        """.trimIndent()

    /** 每个测试实例共用一个 updates 目录（复用断言要求同目录）。 */
    private fun updatesFolder(): File = File(temp.root, "updates").apply { mkdirs() }

    private fun updater(port: Int, current: String = "1.0.0") =
        RelayUpdater(
            OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
            "http://127.0.0.1:$port/",
            current,
            updatesFolder().toPath(),
            platform = "mac",
        )

    @Test
    fun `check 拉取清单且仅新版可用`() {
        val server = serve(manifestJson("GuoyingRelay-9.9.9.dmg", payload))
        try {
            val old = updater(server.address.port, current = "9.9.9")
            assertNull(old.check())
            val fresh = updater(server.address.port, current = "1.0.0")
            assertEquals("9.9.9", fresh.check()?.version)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `下载校验通过并落盘目标文件名`() {
        val server = serve(manifestJson("GuoyingRelay-9.9.9.dmg", payload))
        try {
            val target = updater(server.address.port).download(updater(server.address.port).check()!!)
            assertEquals("GuoyingRelay-9.9.9.dmg", target.fileName.toString())
            assertTrue(target.toFile().length() == payload.size.toLong())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `size 与 sha256 不符即删除半成品`() {
        val broken = payload.copyOfRange(0, payload.size - 100) // sha 不符
        val json = manifestJson("GuoyingRelay-9.9.9.dmg", payload, sizeOverride = broken.size.toLong())
        val server = serve(json, broken)
        try {
            try {
                updater(server.address.port).download(updater(server.address.port).check()!!)
                org.junit.Assert.fail("sha256 不符应抛错")
            } catch (expected: java.io.IOException) {
            }
            val dir = temp.root.resolve("updates")
            assertTrue(dir.listFiles().isNullOrEmpty())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `同版本已校验包复用不重复下载`() {
        val hits = java.util.concurrent.atomic.AtomicInteger()
        val server = serve(manifestJson("GuoyingRelay-9.9.9.dmg", payload), hitCount = hits)
        try {
            val target = updater(server.address.port).download(updater(server.address.port).check()!!)
            assertEquals(1, hits.get())
            val again = updater(server.address.port).download(updater(server.address.port).check()!!)
            assertEquals(target, again)
            assertEquals(1, hits.get()) // 复用本地已校验包，零新增下载
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `新版本落地后清理旧版本包`() {
        val dir = updatesFolder().toPath()
        val stale = dir.resolve("GuoyingRelay-1.0.0.dmg").toFile().apply { writeBytes(byteArrayOf(1)) }
        val partial = dir.resolve("GuoyingRelay-1.0.0.dmg.part").toFile().apply { writeBytes(byteArrayOf(1)) }
        val hits = java.util.concurrent.atomic.AtomicInteger()
        val server = serve(manifestJson("GuoyingRelay-9.9.9.dmg", payload), hitCount = hits)
        try {
            val updater = RelayUpdater(
                OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
                "http://127.0.0.1:${server.address.port}/",
                "1.0.0", dir, platform = "mac",
            )
            updater.download(updater.check()!!)
            assertFalse(stale.exists())
            assertFalse(partial.exists())
            assertTrue(dir.resolve("GuoyingRelay-9.9.9.dmg").toFile().isFile)
        } finally {
            server.stop(0)
        }
    }

    // 安装脚本与命令

    @Test
    fun `mac 安装脚本包含关键步骤且路径带引号`() {
        val script =
            RelayUpdater.macInstallScript(
                "/Users/x/Library/Application Support/GuoyingRelay/updates/GuoyingRelay-1.1.0.dmg",
                "/Applications/果映伴机.app",
            )
        assertTrue(script.contains("hdiutil attach"))
        assertTrue(script.contains("ditto --rsrc"))
        assertTrue(script.contains("xattr -dr com.apple.quarantine"))
        assertTrue(script.contains("hdiutil detach"))
        assertTrue(script.contains("open '/Applications/果映伴机.app'"))
        // 含空格路径必须整段单引号包裹
        assertTrue(
            script.contains(
                "'/Users/x/Library/Application Support/GuoyingRelay/updates/GuoyingRelay-1.1.0.dmg'",
            ),
        )
        // shell 变量在 Kotlin 模板中以 \$ 转义输出为字面 $MNT/$APP_SRC
        assertTrue(script.contains("\$MNT"))
        assertTrue(script.contains("\$APP_SRC"))
    }

    @Test
    fun `win 命令为 msiexec 图形向导`() {
        assertEquals(listOf("msiexec", "/i", "C:\\x\\GuoyingRelay-1.1.0.msi"), RelayUpdater.winInstallCommand("C:\\x\\GuoyingRelay-1.1.0.msi"))
    }

    @Test
    fun `失败回退命令按平台展示安装包`() {
        val file = File(temp.root, "x.dmg").toPath()
        // 回退命令本就随平台分派：Windows=资源管理器定位，mac=Finder 定位（CI matrix 两端都会跑到）。
        val expected =
            if (RelayUpdater.IS_WINDOWS) listOf("explorer", "/select,", file.toString())
            else listOf("open", "-R", file.toString())
        assertEquals(expected, RelayUpdater.revealCommand(file))
    }
}

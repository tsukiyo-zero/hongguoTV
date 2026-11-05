// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import com.hongguotv.core.Mp4SampleTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionTest {
    @get:Rule
    val temp = TemporaryFolder()

    // 假 ffmpeg 脚本是 POSIX 方案（.sh + 进程执行），Windows CI 无法运行——状态机逻辑平台无关，
    // Windows 侧由 CI 的真实 MSI 安装冒烟覆盖，本类跳过。
    @Before
    fun skipOnWindows() {
        Assume.assumeFalse(
            System.getProperty("os.name").orEmpty().contains("Windows", true),
        )
    }

    /** 10 样本 × 28 字节（2 个长度前缀 NALU），dts 5fps 步进 200ms。 */
    private fun fakeTable() =
        Mp4SampleTable.Table(
            "bvc2", 720, 1280, 10_000,
            (0 until 10).map { Mp4SampleTable.Sample(it * 28L, 28, it * 200_000L, it * 200_000L) },
            listOf(0, 5),
        )

    private class FakeSource(private val bytes: Long) : VideoSource {
        override val total = bytes
        override fun prepare() {}
        override fun readHeader() = read(0, bytes.toInt())
        override fun read(start: Long, length: Int): ByteArray {
            val n = minOf(length.toLong(), bytes - start).toInt().coerceAtLeast(0)
            return ByteArray(n) { ((start + it) % 251).toByte() }
        }
        override fun close() {}
    }

    /** 假 ffmpeg：记录调用参数、写出假 index.m3u8+分段、吃掉 stdin；slow 变体多睡 30s 维持 RUNNING。 */
    private fun fakeFfmpeg(slow: Boolean): File {
        val log = File(temp.root, "args-${if (slow) "slow" else "fast"}.log")
        return File(temp.root, "fake-ffmpeg-${if (slow) "slow" else "fast"}.sh").apply {
            writeText(
                """
                #!/bin/sh
                echo "$$" >> ${log.absolutePath}
                echo "$@" >> ${log.absolutePath}
                for last; do :; done
                dir=${'$'}(dirname "${'$'}last")
                echo "#EXTM3U" > "${'$'}last"
                echo fake > "${'$'}dir/seg00000.ts"
                """.trimIndent() + if (slow) "\nsleep 30\ncat > /dev/null\n" else "\ncat > /dev/null\n",
            )
            setExecutable(true)
        }
    }

    private fun session(script: File, dir: File) =
        Session(
            "t1", FakeSource(280), FakeSource(280), fakeTable(), emptyList(), dir.toPath(),
            "http://127.0.0.1:9/a/t1/audio.mp4", script.absolutePath, "libx264",
        )

    private fun eventually(timeoutMs: Long = 8_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(check).getOrDefault(false)) return
            Thread.sleep(50)
        }
        error("等待超时")
    }

    @Test
    fun `喂帧完毕后转码完成并记录调用参数`() {
        val dir = temp.newFolder("done")
        val target = session(fakeFfmpeg(slow = false), dir)
        eventually { target.state == Session.State.COMPLETED }
        val args = File(temp.root, "args-fast.log").readText()
        assertTrue(args.contains("-f vvc"))
        assertTrue(args.contains("libx264"))
        assertTrue(args.contains("pipe:0"))
        assertEquals(1, File(temp.root, "args-fast.log").readLines().size / 2) // pid+args 成对
    }

    @Test
    fun `seek 重启转码进程并定位目标样本`() {
        val dir = temp.newFolder("seek")
        val target = session(fakeFfmpeg(slow = true), dir)
        eventually { File(temp.root, "args-slow.log").readLines().size >= 2 } // pid+args 一次调用两行
        assertTrue(target.seek(1.0))
        eventually { File(temp.root, "args-slow.log").readLines().size >= 4 }
        // 目标 1.0s：首个 dts≥1s 是 idx5，恰为同步样本 → 起喂样本 5
        assertEquals(5, target.startIdx)
        target.stop()
        assertEquals(Session.State.STOPPED, target.state)
    }

    @Test
    fun `stop 清理会话目录`() {
        val dir = temp.newFolder("stop")
        val target = session(fakeFfmpeg(slow = true), dir)
        eventually { File(dir, "index.m3u8").isFile }
        target.stop()
        assertEquals(Session.State.STOPPED, target.state)
        assertFalse(dir.exists())
    }

    @Test
    fun `单活跃会话互斥与复用`() {
        val manager = SessionManager(temp.root.toPath())
        val source = { FakeSource(280) }
        val first = manager.create("a") { dir -> Session("a", source(), source(), fakeTable(), emptyList(), dir, "http://x/a.mp4", fakeFfmpeg(slow = true).absolutePath, "libx264") }
        assertTrue(first.alive)
        try {
            manager.create("b") { dir -> Session("b", source(), source(), fakeTable(), emptyList(), dir, "http://x/b.mp4", fakeFfmpeg(slow = false).absolutePath, "libx264") }
            fail("第二个会话应被拒绝")
        } catch (expected: SessionManager.BusyException) {
        }
        assertTrue(manager.stop("a"))
        val second = manager.create("b") { dir -> Session("b", source(), source(), fakeTable(), emptyList(), dir, "http://x/b.mp4", fakeFfmpeg(slow = false).absolutePath, "libx264") }
        assertEquals("b", second.id)
        eventually { second.state == Session.State.COMPLETED }
    }

    @Test
    fun `空闲回收停止超时会话`() {
        val manager = SessionManager(temp.root.toPath())
        val target = manager.create("a") { dir -> Session("a", FakeSource(280), FakeSource(280), fakeTable(), emptyList(), dir, "http://x/a.mp4", fakeFfmpeg(slow = true).absolutePath, "libx264") }
        assertTrue(target.alive)
        manager.sweepIdle(System.currentTimeMillis(), 60_000)
        assertTrue(target.alive)
        manager.sweepIdle(System.currentTimeMillis() + 120_000, 60_000)
        assertEquals(Session.State.STOPPED, target.state)
    }
}

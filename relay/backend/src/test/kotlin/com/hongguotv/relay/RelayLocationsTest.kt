// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RelayLocationsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val home: Path get() = temp.root.toPath().resolve("home")

    @Test
    fun `状态目录按平台取位并受 home 包含校验`() {
        val mac = RelayLocations.stateDir(home, null, "Mac OS X")
        assertEquals(home.resolve("Library/Application Support/GuoyingRelay").normalize(), mac.normalize())
        assertTrue(mac.startsWith(home.normalize()))

        val windows =
            RelayLocations.stateDir(home, home.resolve("AppData/Roaming").toString(), "Windows 11")
        assertEquals(home.resolve("AppData/Roaming/GuoyingRelay").normalize(), windows.normalize())
    }

    @Test
    fun `legacy 令牌文件仅在实际存在时返回`() {
        val cwd = temp.root.toPath().resolve("cwd")
        assertNull(RelayLocations.legacyTokenFile(cwd))
        val legacy = cwd.resolve(".guoying-relay/token")
        Files.createDirectories(legacy.parent)
        Files.writeString(legacy, "abcd1234")
        assertEquals(legacy.normalize(), RelayLocations.legacyTokenFile(cwd))
    }

    @Test
    fun `ffmpeg 解析顺序 捆绑到显式到 PATH`() {
        val appContents = temp.root.toPath().resolve("GuoyingRelay.app/Contents")
        val bundled = appContents.resolve("app/ffmpeg/ffmpeg")
        Files.createDirectories(bundled.parent)
        Files.writeString(bundled, "binary")

        val ok: (String) -> Boolean = { true }
        assertEquals(bundled.toString(), RelayLocations.resolveFfmpeg("/usr/bin/ffmpeg", appContents, ok))

        Files.delete(bundled)
        assertEquals("/usr/bin/ffmpeg", RelayLocations.resolveFfmpeg("/usr/bin/ffmpeg", appContents, ok))

        assertEquals("ffmpeg", RelayLocations.resolveFfmpeg(null, appContents, ok))
        assertNull(RelayLocations.resolveFfmpeg(null, appContents) { false })
    }

    @Test
    fun `令牌迁移 生成与持久化`() {
        val legacyCwd = temp.root.toPath().resolve("legacy")
        val legacy = legacyCwd.resolve(".guoying-relay/token")
        Files.createDirectories(legacy.parent)
        Files.writeString(legacy, "abcd1234")

        val first = RelayLocations.ensureToken(home, null, legacyCwd)
        assertEquals("abcd1234", first.first)
        assertTrue(Files.exists(first.second))

        // 已持久化：再次读取拿到同一令牌（重启不变）。
        assertEquals(first, RelayLocations.ensureToken(home, null, legacyCwd))
    }

    @Test
    fun `无旧令牌时生成 8 位十六进制`() {
        val generated = RelayLocations.ensureToken(home, null, temp.root.toPath().resolve("empty-cwd"))
        assertTrue(generated.first.matches(Regex("[0-9a-f]{8}")))
        assertEquals(generated.first, Files.readString(generated.second).trim())
        assertFalse(generated.first.isBlank())
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RelayAutostartTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val home: Path get() = temp.root.toPath().resolve("home")

    private fun fakeAppContents(): Path {
        val contents = temp.root.toPath().resolve("GuoyingRelay.app/Contents")
        val macos = contents.resolve("MacOS")
        Files.createDirectories(macos)
        val binary = macos.resolve("GuoyingRelay")
        Files.writeString(binary, "#!/bin/sh\n")
        binary.toFile().setExecutable(true)
        return contents
    }

    @Test
    fun `enable 写出 RunAtLoad plist 并指向 app 内二进制`() {
        val contents = fakeAppContents()
        RelayAutostart.enable(home, contents)
        assertTrue(RelayAutostart.isEnabled(home))
        val plist = RelayAutostart.plistFile(home)
        val text = Files.readString(plist)
        assertTrue(text.contains("<string>com.guoying.relay</string>"))
        assertTrue(text.contains(contents.resolve("MacOS/GuoyingRelay").toString()))
        assertTrue(text.contains("<key>RunAtLoad</key>"))
        assertTrue(text.contains("--launchd"))
    }

    @Test
    fun `disable 删除 plist`() {
        val contents = fakeAppContents()
        RelayAutostart.enable(home, contents)
        assertTrue(RelayAutostart.isEnabled(home))
        RelayAutostart.disable(home)
        assertFalse(RelayAutostart.isEnabled(home))
    }
}

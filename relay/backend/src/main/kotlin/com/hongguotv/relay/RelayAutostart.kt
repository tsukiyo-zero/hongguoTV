// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** 开机自启（macOS LaunchAgents）：开关即写/删 ~/Library/LaunchAgents/com.guoying.relay.plist。 */
internal object RelayAutostart {
    private const val LABEL = "com.guoying.relay"

    fun plistFile(home: Path): Path {
        val agents = home.resolve("Library").resolve("LaunchAgents").normalize()
        require(agents.startsWith(home.normalize())) { "自启配置路径异常" }
        return agents.resolve("$LABEL.plist")
    }

    fun isEnabled(home: Path): Boolean = Files.exists(plistFile(home))

    /** 开启：写入 RunAtLoad 的 plist，ProgramArguments 指向当前 app 内的可执行入口。 */
    fun enable(home: Path, appContents: Path) {
        val plist = plistFile(home)
        Files.createDirectories(plist.parent)
        val binary =
            appContents.resolve("MacOS")
                .takeIf { Files.isDirectory(it) }
                ?.toFile()
                ?.listFiles()
                ?.firstOrNull { it.canExecute() }
                ?.absolutePath
                ?: error("未找到伴机可执行文件，无法配置开机自启")
        val xml =
            """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>$LABEL</string>
    <key>ProgramArguments</key>
    <array>
        <string>$binary</string>
        <string>--launchd</string>
    </array>
    <key>RunAtLoad</key><true/>
</dict>
</plist>
"""
        Files.writeString(plist, xml)
    }

    fun disable(home: Path) {
        Files.deleteIfExists(plistFile(home))
    }
}

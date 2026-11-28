// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import java.awt.CheckboxMenuItem
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.nio.file.Path

/** 菜单栏壳（AWT SystemTray）：状态行、状态窗口、复制地址/令牌、检查更新、开机自启开关、退出。 */
internal class RelayTray private constructor(
    private val trayIcon: TrayIcon,
    private val onCopyAddress: () -> String,
    private val onCopyToken: () -> String,
    private val onShowWindow: () -> Unit,
    private val onCheckUpdates: () -> Unit,
    autostartHome: Path?,
    appRoot: Path?,
    private val onAutostartChanged: (Boolean) -> Unit,
    private val onExit: () -> Unit,
) {
    private val statusItem = MenuItem("启动中…").apply { isEnabled = false }
    private val exitItem = action("退出") { onExit() }

    /** 升级执行期间除状态行与「退出」外全部禁用，避免半途再起会话/触发其他动作。 */
    private val upgradableItems: List<MenuItem>

    @Volatile private var upgrading = false
    private val autostartItem =
        if (autostartHome == null || appRoot == null) null // 自启 plist 为 macOS 专属
        else
            CheckboxMenuItem("开机自启", RelayAutostart.isEnabled(autostartHome)).apply {
                addItemListener {
                    val enabled = state
                    runCatching {
                        if (enabled) RelayAutostart.enable(autostartHome, appRoot)
                        else RelayAutostart.disable(autostartHome)
                    }
                        .onFailure { problem ->
                            state = !enabled
                            notify("自启配置失败：${problem.message?.take(80)}", error = true)
                        }
                    onAutostartChanged(enabled)
                }
            }

    init {
        val checkItem = action("检查更新") { onCheckUpdates() }
        val popup = PopupMenu()
        popup.add(statusItem)
        popup.addSeparator()
        val showItem = action("打开状态窗口") { onShowWindow() }
        val copyAddressItem = action("复制地址") { copyToClipboard(onCopyAddress()) }
        val copyTokenItem = action("复制令牌") { copyToClipboard(onCopyToken()) }
        popup.add(showItem)
        popup.add(copyAddressItem)
        popup.add(copyTokenItem)
        popup.add(checkItem)
        upgradableItems = buildList {
            add(showItem)
            add(copyAddressItem)
            add(copyTokenItem)
            add(checkItem)
            autostartItem?.let { add(it) }
        }
        autostartItem?.let {
            popup.addSeparator()
            popup.add(it)
        }
        popup.addSeparator()
        popup.add(exitItem)
        trayIcon.popupMenu = popup
        SystemTray.getSystemTray().add(trayIcon)
    }

    /** 更新菜单栏状态行；notify 时弹系统通知。 */
    fun status(text: String, notify: Boolean = false, error: Boolean = false) {
        statusItem.label = text
        if (notify) notify(text, error)
    }

    /** 升级执行期间锁定菜单（状态行/退出除外）；完成后（自动重启前无需解锁）由退出路径兜底。 */
    fun setUpgrading(value: Boolean) {
        upgrading = value
        upgradableItems.forEach { it.isEnabled = !value }
    }

    private fun notify(text: String, error: Boolean) {
        trayIcon.displayMessage(
            "果映伴机",
            text,
            if (error) TrayIcon.MessageType.ERROR else TrayIcon.MessageType.INFO,
        )
    }

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        notify("已复制", error = false)
    }

    private fun action(label: String, onClick: () -> Unit): MenuItem =
        MenuItem(label).apply { addActionListener { onClick() } }

    companion object {
        /** 安装菜单栏图标；系统不支持（headless 等）返回 null，伴机仍以纯后台运行。 */
        fun install(
            autostartHome: Path?,
            appRoot: Path?,
            onCopyAddress: () -> String,
            onCopyToken: () -> String,
            onShowWindow: () -> Unit,
            onCheckUpdates: () -> Unit,
            onAutostartChanged: (Boolean) -> Unit,
            onExit: () -> Unit,
        ): RelayTray? =
            runCatching {
                if (!SystemTray.isSupported()) return null
                val trayIcon = TrayIcon(iconImage(), "果映伴机")
                trayIcon.isImageAutoSize = true
                RelayTray(
                    trayIcon, onCopyAddress, onCopyToken, onShowWindow, onCheckUpdates,
                    autostartHome, appRoot, onAutostartChanged, onExit,
                )
            }.getOrNull()

        /** 程序化生成菜单栏图标（深色圆底 + 白色播放三角），免去图片资源依赖。 */
        private fun iconImage(): java.awt.Image {
            val size = 20
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.color = java.awt.Color(255, 99, 76)
            g.fillOval(1, 1, size - 2, size - 2)
            g.color = java.awt.Color.WHITE
            g.fillPolygon(
                intArrayOf(size / 2 - 3, size / 2 - 3, size / 2 + 5),
                intArrayOf(size / 2 - 5, size / 2 + 5, size / 2),
                3,
            )
            g.dispose()
            return image
        }
    }
}

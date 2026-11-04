// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import java.awt.Font
import java.awt.Frame
import java.awt.GridLayout
import java.awt.Label
import java.awt.TextArea
import java.awt.TextField
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * 状态窗口：让「菜单栏驻留」的应用有一张可见的脸。
 * 展示全部可用局域网地址（多网卡全列）与最近活动（电视接入/建会话/拖动/结束）。
 * 关闭窗口仅隐藏（程序继续驻留菜单栏），不停止转码。
 */
internal class RelayWindow private constructor(private val frame: Frame) {
    private val statusLabel = Label("启动中…")
    private val addressArea = TextArea("", 2, 44, TextArea.SCROLLBARS_NONE).apply { isEditable = false }
    private val tokenField = TextField("").apply { isEditable = false }
    private val activityLabel = Label("最近活动：暂无")

    init {
        val bodyFont = Font(Font.SANS_SERIF, Font.PLAIN, 16)
        val smallFont = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        frame.apply {
            layout = GridLayout(0, 1, 4, 4)
            add(statusLabel.apply { font = Font(Font.SANS_SERIF, Font.BOLD, 17) })
            add(Label("局域网地址（任选其一，电视端搜索/手动填入）：").apply { font = bodyFont })
            add(addressArea.apply { font = bodyFont })
            add(Label("伴机令牌（电视端播放设置填入）：").apply { font = bodyFont })
            add(tokenField.apply { font = bodyFont })
            add(activityLabel.apply { font = bodyFont })
            add(
                Label("关闭本窗口不会停止转码；程序驻留于屏幕右上角菜单栏（橙色圆点）。")
                    .apply { font = smallFont },
            )
            add(Label("退出请点菜单栏图标 → 退出；电视端用「搜索伴机」自动发现本机。").apply { font = smallFont })
            addWindowListener(
                object : WindowAdapter() {
                    override fun windowClosing(event: WindowEvent?) = hide()
                },
            )
            pack()
            isResizable = false
            setLocationRelativeTo(null)
        }
    }

    fun update(status: String, addresses: List<String>, token: String) {
        // 窗口属性变更须在 EDT 上执行（AWT 线程约束）。
        java.awt.EventQueue.invokeLater {
            statusLabel.text = "状态：$status"
            addressArea.text = addresses.joinToString("\n")
            tokenField.text = token
        }
    }

    fun updateActivity(line: String) {
        java.awt.EventQueue.invokeLater { activityLabel.text = "最近活动：$line" }
    }

    fun showWindow() {
        java.awt.EventQueue.invokeLater {
            frame.isVisible = true
            frame.toFront()
            activateMacApp() // 后台应用 toFront 不抬窗：显式激活 macOS 应用
        }
    }

    /** macOS：激活应用（菜单栏切换 + 窗口前置）；非 mac 平台反射找不到类时静默跳过。 */
    private fun activateMacApp() {
        runCatching {
            val appClass = Class.forName("com.apple.eawt.Application")
            val app = appClass.getMethod("getApplication").invoke(null)
            appClass.getMethod("activate").invoke(app)
        }
    }

    companion object {
        fun install(): RelayWindow? =
            runCatching {
                RelayWindow(Frame("果映伴机"))
            }.getOrNull()
    }
}

package com.hongguotv.nativeapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.app.Activity
import android.os.Build
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 26], qualifiers = "land")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AndroidCompatibilityTest {
    class InstallerActivity : Activity() {
        val attempts = mutableListOf<Intent>()
        var acceptedAction: String? = null

        override fun startActivity(intent: Intent, options: Bundle?) {
            attempts += intent
            if (intent.action != acceptedAction) throw ActivityNotFoundException()
        }

        override fun startActivity(intent: Intent) = startActivity(intent, null)
    }

    /**
     * 安装入口在候选集里按顺序尝试；这里只断言「该试的都试过、该带的权限都带」，
     * 不锁定候选数量（组件直达与 file:// 兜底会随系统版本增减）。
     */
    private fun checkInstaller(acceptedAction: String?, opened: Boolean) {
        val controller = Robolectric.buildActivity(InstallerActivity::class.java).setup()
        val activity = controller.get()
        activity.acceptedAction = acceptedAction
        val updater = AppUpdater(activity) { false }
        val uri = Uri.parse("content://${activity.packageName}.updates/updates/update.apk")
        try {
            val launch = AppUpdater::class.java.getDeclaredMethod("launchInstaller", Uri::class.java)
            launch.isAccessible = true
            assertEquals(opened, launch.invoke(updater, uri))
            val actions = activity.attempts.map { it.action }
            assertTrue(actions.isNotEmpty())
            if (!opened) {
                // 只有全部失败时才会走完整个候选集。
                if (Build.VERSION.SDK_INT < 26)
                    assertTrue(actions.contains(Intent.ACTION_INSTALL_PACKAGE))
                assertTrue(actions.contains(Intent.ACTION_VIEW))
            }
            activity.attempts.forEach {
                assertTrue(it.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertEquals("application/vnd.android.package-archive", it.type)
                if (it.data?.scheme == "content") {
                    assertEquals(uri, it.data)
                    assertEquals(uri, it.clipData!!.getItemAt(0).uri)
                }
            }
        } finally {
            updater.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun installerUsesAvailableEntryAndGrantsReadAccess() {
        val action = if (Build.VERSION.SDK_INT < 26) Intent.ACTION_INSTALL_PACKAGE else Intent.ACTION_VIEW
        checkInstaller(action, true)
    }

    @Test
    fun legacyInstallerFallsBackWhenInstallActionIsMissing() {
        checkInstaller(Intent.ACTION_VIEW, true)
    }

    @Test
    fun missingInstallerIsReportedAfterTryingAllEntries() {
        checkInstaller(null, false)
    }

    @Test
    fun mainActivityCreatesAndClosesOnSupportedSystems() {
        assertTrue(Build.VERSION.SDK_INT in listOf(23, 26))
        val controller =
            Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val content = controller.get().findViewById<FrameLayout>(android.R.id.content)
            assertTrue(content.childCount > 0)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun returningFromInstallPermissionWorksOnSupportedSystems() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val updater = AppUpdater(controller.get()) { false }
        try {
            // Also exercise state restored from a newer system or a pending installer action.
            val requested =
                AppUpdater::class.java.getDeclaredField("permissionRequested").apply {
                    isAccessible = true
                }
            requested.setBoolean(updater, true)
            updater.resume()
            assertFalse(requested.getBoolean(updater))
        } finally {
            updater.destroy()
            controller.pause().stop().destroy()
        }
    }
}

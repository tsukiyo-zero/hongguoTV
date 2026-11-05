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

    private fun checkInstaller(acceptedAction: String?, expected: List<String>, opened: Boolean) {
        val controller = Robolectric.buildActivity(InstallerActivity::class.java).setup()
        val activity = controller.get()
        activity.acceptedAction = acceptedAction
        val updater = AppUpdater(activity) { false }
        val uri = Uri.parse("content://${activity.packageName}.updates/updates/update.apk")
        try {
            val launch = AppUpdater::class.java.getDeclaredMethod("launchInstaller", Uri::class.java)
            launch.isAccessible = true
            assertEquals(opened, launch.invoke(updater, uri))
            assertEquals(expected, activity.attempts.map { it.action })
            activity.attempts.forEach {
                assertEquals(uri, it.data)
                assertEquals(uri, it.clipData!!.getItemAt(0).uri)
                assertTrue(it.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                if (it.action == Intent.ACTION_VIEW)
                    assertEquals("application/vnd.android.package-archive", it.type)
            }
        } finally {
            updater.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun installerUsesAvailableEntryAndGrantsReadAccess() {
        val action = if (Build.VERSION.SDK_INT < 26) Intent.ACTION_INSTALL_PACKAGE else Intent.ACTION_VIEW
        checkInstaller(action, listOf(action), true)
    }

    @Test
    fun legacyInstallerFallsBackWhenInstallActionIsMissing() {
        val expected = if (Build.VERSION.SDK_INT < 26)
            listOf(Intent.ACTION_INSTALL_PACKAGE, Intent.ACTION_VIEW) else listOf(Intent.ACTION_VIEW)
        checkInstaller(Intent.ACTION_VIEW, expected, true)
    }

    @Test
    fun missingInstallerIsReportedAfterTryingAllEntries() {
        val expected = if (Build.VERSION.SDK_INT < 26)
            listOf(Intent.ACTION_INSTALL_PACKAGE, Intent.ACTION_VIEW) else listOf(Intent.ACTION_VIEW)
        checkInstaller(null, expected, false)
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

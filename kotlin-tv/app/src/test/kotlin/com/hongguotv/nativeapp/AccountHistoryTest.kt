package com.hongguotv.nativeapp

import android.app.Activity
import com.hongguotv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,26],qualifiers="land")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AccountHistoryTest {
    private fun field(account: AccountController,name: String,value: Any?) {
        AccountController::class.java.getDeclaredField(name).apply { isAccessible=true }.set(account,value)
    }
    @Test fun accountTabShowsCloudRowsWhileRecentTabKeepsDeviceRows() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val activity=controller.get()
        fun get(name: String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
        fun texts(view: android.view.View): List<String> = when(view) {
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            is android.widget.TextView -> listOf(view.text.toString())
            else -> emptyList()
        }
        try {
            val library=get("library") as Library
            val local=Series("111","本机独有记录")
            val data=BackupData(emptyList(),listOf(BackupProgress(local,"112",0,1000,90000,false,1000)),emptySet(),emptyList(),BackupSettings())
            Library::class.java.getDeclaredField("index").apply { isAccessible=true }.set(library,LibraryIndex(data))
            val account=get("cloudAccount") as AccountController
            val switch=MainActivity::class.java.getDeclaredMethod("switchTab",Int::class.javaPrimitiveType).apply { isAccessible=true }
            switch.invoke(activity,5)
            val root=activity.findViewById<android.view.View>(android.R.id.content)
            assertTrue(texts(root).any { it.contains("登录红果账号后") })
            field(account,"credentials",AccountCredentials("test-only"))
            field(account,"profile",CloudProfile("a","账号 A"))
            field(account,"history",listOf(CloudProgress(Series("221","云端独有记录"),"222",3,45000,90000,2000)))
            field(account,"busy",true)
            switch.invoke(activity,5)
            assertTrue(texts(root).contains("云端独有记录"))
            assertFalse(texts(root).contains("本机独有记录"))
            assertTrue(texts(root).any { it.contains("第 4 集") })
            switch.invoke(activity,4)
            assertTrue(texts(root).contains("本机独有记录"))
            assertFalse(texts(root).contains("云端独有记录"))
        } finally { controller.destroy() }
    }
    @Test fun switchingAccountsAndLoggingOutImmediatelyDiscardPreviousHistory() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        val account=AccountController(activity.get()) {}
        val row=CloudProgress(Series("123","账号 A 的记录"),"456",2,45000,90000,1000)
        try {
            field(account,"credentials",AccountCredentials("test-only"))
            field(account,"profile",CloudProfile("a","账号 A"))
            field(account,"history",listOf(row))
            assertEquals(row,account.progress("123"))
            var notifications=0
            account.onHistoryChanged={ notifications++ }
            // Keep network disabled while exercising the real login state transition.
            field(account,"busy",true)
            AccountController::class.java.getDeclaredMethod("acceptAccount",AccountCredentials::class.java,CloudProfile::class.java).apply { isAccessible=true }
                .invoke(account,AccountCredentials("test-only-b"),CloudProfile("b","账号 B"))
            assertTrue(account.signedIn)
            assertEquals("账号 B",account.accountLabel)
            assertTrue(account.historyRows().isEmpty())
            assertNull(account.progress("123"))
            assertEquals(1,notifications)
            field(account,"history",listOf(row))
            val scope=account.historyScope
            AccountController::class.java.getDeclaredMethod("logout").apply { isAccessible=true }.invoke(account)
            assertFalse(account.signedIn)
            assertTrue(account.historyScope>scope)
            assertTrue(account.historyRows().isEmpty())
            assertNull(account.progress("123"))
            assertEquals(2,notifications)
        } finally { account.destroy(); activity.pause().stop().destroy() }
    }
}

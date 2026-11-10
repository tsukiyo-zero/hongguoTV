// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PhoneLoginRegionUiTest {
    private fun texts(view: View): List<TextView> =
        (if (view is TextView) listOf(view) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()

    @Test
    fun limitedPickerAndChangedNumberCannotReuseOldVerificationTicket() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val account = AccountController(controller.get()) {}
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("+852 12345678", chain.request().url.queryParameter("mobile"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"message":"success","data":{"mobile":"+852******78","mobile_ticket":"test-only"}}""".toResponseBody()).build()
        }.build()
        try {
            AccountController::class.java.getDeclaredField("phoneHttp").apply { isAccessible = true }.set(account, http)
            AccountController::class.java.getDeclaredMethod("phone").apply { isAccessible = true }.invoke(account)
            val login = ShadowAlertDialog.getLatestAlertDialog()
            val fields = texts(login.window!!.decorView).filterIsInstance<EditText>()
            assertTrue(texts(login.window!!.decorView).any { it.text.toString().contains("非大陆短信未做实号验证") })
            texts(login.window!!.decorView).single { it.text.toString() == "+86 ▾" }.performClick()
            val picker = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(14, picker.listView.adapter.count)
            assertTrue(picker.listView.adapter.getItem(1).toString().contains("香港"))
            picker.listView.performItemClick(null, 1, 1)
            fields[0].setText("12345678")
            texts(login.window!!.decorView).single { it.text.toString() == "获取验证码" }.performClick()
            val worker = AccountController::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(account) as ExecutorService
            worker.submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            assertEquals(1, calls)
            assertTrue(texts(login.window!!.decorView).any { it.text.toString().contains("平台已受理") })
            fields[0].setText("87654321")
            fields[1].setText("000000")
            login.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(texts(login.window!!.decorView).any { it.text.toString().contains("号码或区号已更改") })
            assertEquals(1, calls)
        } finally {
            account.destroy()
            controller.pause().stop().destroy()
        }
    }
}

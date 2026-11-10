// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 本地模拟仅验证请求、号码处理与失败分类；不向真实号码发码，不证明短信可达。 */
class PhoneLoginTest {

    private fun client(
        cookies: Map<String, String> = emptyMap(),
        respond: (Request) -> Pair<Int, String>,
    ) =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val (code, body) = respond(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("fixture")
                    .apply {
                        cookies.forEach { (k, v) -> addHeader("Set-Cookie", "$k=$v; Path=/") }
                        if (code == 307) addHeader("Location", "https://redirect.invalid/not-allowed")
                    }
                    .body(body.toResponseBody())
                    .build()
            }
            .build()

    private fun form(request: Request): Map<String, String> {
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        return buffer.readUtf8().split('&').associate {
            val pair = it.split('=', limit = 2)
            java.net.URLDecoder.decode(pair[0], "UTF-8") to java.net.URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
    }

    @Test
    fun appSendUsesMixedPostBodyAndConsistentVersionWithNoRedirectRetry() {
        var calls = 0
        val login = PhoneLogin(client { request ->
            calls++
            assertEquals("POST", request.method)
            assertEquals(null, request.url.queryParameter("mobile"))
            assertEquals("74332", request.url.queryParameter("version_code"))
            assertTrue(request.header("User-Agent")!!.contains("74332"))
            assertEquals("5051452", request.url.queryParameter("passport-sdk-version"))
            assertEquals(null, request.header("passport-sdk-version"))
            val body = form(request)
            assertEquals(PhoneLogin.mix("+886 123456789"), body["mobile"])
            assertEquals("3731", body["type"])
            assertEquals("35", body["unbind_exist"])
            assertEquals("0", body["auto_read"])
            assertEquals("1", body["mix_mode"])
            assertEquals("app", body["account_sdk_source"])
            assertFalse(request.header("x-ss-stub").isNullOrBlank())
            307 to """{"message":"error","data":{"error_code":7,"description":"测试拒绝"}}"""
        })
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { login.send("+886123456789") }
        assertEquals(1, calls)
        assertEquals("2e3d3d3325343736313033323d3c", PhoneLogin.mix("+886 123456789"))
    }

    private fun deviceRegister(request: Request) =
        request.url.encodedPath == "/service/2/device_register/"

    private val registered = 200 to """{"device_id":"333","install_id":"444"}"""

    @Test
    fun sendReportsMaskedNumberRetryAndTicket() {
        val login = PhoneLogin(
            client { request ->
                if (deviceRegister(request)) registered
                else 200 to """{"message":"success","data":{"mobile":"138******00","retry_time":60,"mobile_ticket":"tk"}}"""
            }
        )
        val sent = login.send("13800000000")
        assertEquals("138******00", sent.maskedMobile)
        assertEquals(60, sent.retrySeconds)
        assertEquals("tk", login.ticket)
        // 兼容层当前保留设备表身份，不把它视为官方真实注册身份。
        assertEquals("1905892595378490", login.deviceId)
        assertEquals("1905892595382586", login.installId)
    }

    @Test
    fun sliderChallengeIsReportedAsUnsupported() {
        val login = PhoneLogin(
            client { request ->
                if (deviceRegister(request)) registered
                else 200 to """{"message":"success","data":{"verify_center_decision_conf":"{}"}}"""
            }
        )
        try {
            login.send("13800000000")
            fail("expected slider failure")
        } catch (failure: PhoneLogin.Failure) {
            assertTrue(failure.message.orEmpty().contains("滑块"))
        }
    }

    @Test
    fun verifyReturnsCredentialsBoundToTheRegisteredDevice() {
        val calls = AtomicInteger()
        val login = PhoneLogin(
            client(
                respond = { request ->
                    when {
                        deviceRegister(request) -> registered
                        request.url.encodedPath.contains("/send_code/") ->
                            200 to """{"message":"success","data":{"mobile":"138******00","mobile_ticket":"tk"}}"""
                        else -> {
                            calls.incrementAndGet()
                            200 to """{"message":"success","data":{}}"""
                        }
                    }
                },
                cookies = mapOf("sessionid" to "abc"),
            )
        )
        login.send("13800000000")
        val credentials = login.verify("123456")
        assertEquals("sessionid=abc", credentials.cookie)
        assertEquals("1905892595378490", credentials.deviceId)
        assertEquals("1905892595382586", credentials.installId)
        assertEquals("1905892595378490", credentials.device["device_id"])
        assertEquals(1, calls.get())
    }

    /** 会话错误最多尝试两条已核对的短信登录入口，失败保留原错误。 */
    @Test
    fun sessionExpiredErrorKeepsTryingOtherEndpoints() {
        val endpoints = mutableListOf<String>()
        val login = PhoneLogin(
            client { request ->
                when {
                    deviceRegister(request) -> registered
                    request.url.encodedPath.contains("/send_code/") ->
                        200 to """{"message":"success","data":{"mobile":"138******00","mobile_ticket":"tk"}}"""
                    else -> {
                        endpoints += request.url.encodedPath
                        200 to """{"message":"error","data":{"error_code":1,"description":"会话过期，请重新登录"}}"""
                    }
                }
            }
        )
        login.send("13800000000")
        try {
            login.verify("123456")
            fail("expected rejected session")
        } catch (failure: PhoneLogin.Failure) {
            assertEquals(1, failure.errorCode)
            assertFalse(failure.canRetryCode)
        }
        // 仅尝试静态核对的两条入口，不再提交未登录会话不适用的校验端点。
        assertEquals(2, endpoints.size)
        try {
            login.verify("123456")
            fail("expected another rejection")
        } catch (failure: PhoneLogin.Failure) {
            assertFalse(failure.message.orEmpty().contains("已失效"))
        }
    }

    /** 1203 是「错误次数过多或验证码过期」，本次会话作废，不再继续提交。 */
    @Test
    fun tooManyAttemptsExpiresTheSession() {
        val login = PhoneLogin(
            client { request ->
                when {
                    deviceRegister(request) -> registered
                    request.url.encodedPath.contains("/send_code/") ->
                        200 to """{"message":"success","data":{"mobile":"138******00","mobile_ticket":"tk"}}"""
                    else ->
                        200 to """{"message":"error","data":{"error_code":1203,"description":"错误次数过多或验证码过期，请稍后重试"}}"""
                }
            }
        )
        login.send("13800000000")
        try {
            login.verify("123456")
            fail("expected exhausted session")
        } catch (failure: PhoneLogin.Failure) {
            assertEquals(1203, failure.errorCode)
            assertTrue(failure.message.orEmpty().contains("错误次数过多"))
        }
        try {
            login.verify("123456")
            fail("expected expired session")
        } catch (failure: PhoneLogin.Failure) {
            assertTrue(failure.message.orEmpty().contains("已失效"))
        }
    }

    /** sms_login 成功时直接基于它建立会话，不应再打 validate_code。 */
    @Test
    fun smsLoginEstablishesSessionWithoutTouchingValidateCode() {
        val paths = mutableListOf<String>()
        val login = PhoneLogin(
            client(
                cookies = mapOf("sessionid" to "abc"),
                respond = { request ->
                    when {
                        deviceRegister(request) -> registered
                        request.url.encodedPath.contains("/send_code/") ->
                            200 to """{"message":"success","data":{"mobile":"138******00","mobile_ticket":"tk"}}"""
                        else -> {
                            assertEquals("POST", request.method)
                            assertEquals(PhoneLogin.mix("123456"), form(request)["code"])
                            assertEquals("1", form(request)["mix_mode"])
                            paths += request.url.encodedPath
                            200 to """{"message":"success","data":{}}"""
                        }
                    }
                },
            )
        )
        login.send("13800000000")
        login.verify("123456")
        assertEquals(listOf("/passport/mobile/sms_login/"), paths)
    }

    @Test
    fun unsupportedRegionsAndMalformedNumbersAreRejectedBeforeNetwork() {
        var calls = 0
        val login = PhoneLogin(client { calls++; error("不得发送真实短信") })
        for (number in listOf("+44 12345678", "+1 2025550123", "+852 012345678", "+853 123", "+852 abc12345678")) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { login.send(number) }
        }
        assertEquals(0, calls)
        assertEquals(listOf("86", "852", "853", "886", "60", "65", "62", "95", "63", "84", "855", "81", "82", "971"), PhoneLogin.regions.map { it.code })
    }

    @Test
    fun overseasSendUsesNormalizedNumberAndKeepsPlatformErrorsVisible() {
        var calls = 0
        val login = PhoneLogin(client { request ->
            calls++
            assertEquals("POST", request.method)
            assertEquals(PhoneLogin.mix("+852 12345678"), form(request)["mobile"])
            200 to """{"message":"success","data":{"error_code":2046,"description":"请求被拒绝"}}"""
        })
        val failure = org.junit.Assert.assertThrows(PhoneLogin.Failure::class.java) {
            login.send("+85212345678")
        }
        assertEquals(2046, failure.errorCode)
        assertEquals(1, calls)
        assertEquals("", login.ticket)
    }

    @Test
    fun screenshotRegionsRemainSelectableWithoutInventingLocalNumberRules() {
        for (region in PhoneLogin.regions.filter { it.digits == null }) {
            val normalized = "+${region.code} 01234567"
            assertEquals(normalized, PhoneLogin.format(region.code, "0123-4567"))
            assertEquals(normalized, PhoneLogin.parse("+${region.code}01234567"))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PhoneLogin.format("886", "1".repeat(14))
        }
    }

    @Test
    fun absentMaskedNumberDoesNotEchoFullNumberAsSent() {
        val login = PhoneLogin(client { 200 to """{"message":"success","data":{"mobile_ticket":"test-only"}}""" })
        val result = login.send("+85312345678")
        assertEquals("+853 ******78", result.maskedMobile)
    }

    @Test
    fun formatAcceptsMainlandAndOverseasNumbers() {
        assertEquals("13800138000", PhoneLogin.format("86", "138 0013 8000"))
        assertEquals("+852 12345678", PhoneLogin.format("852", "1234-5678"))
        assertEquals("+853 12345678", PhoneLogin.parse("+85312345678"))
        assertEquals("+852 12345678", PhoneLogin.parse("+852 1234 5678"))
        assertEquals("13800138000", PhoneLogin.parse("+8613800138000"))
        try {
            PhoneLogin.format("86", "12345")
            fail("expected invalid mainland number")
        } catch (problem: IllegalArgumentException) {
            assertTrue(problem.message.orEmpty().contains("11 位"))
        }
    }
}

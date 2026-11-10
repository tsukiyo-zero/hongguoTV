// Adapted 2026-10-21 from charlie901208/HGTV feat/phone-login (GPL-3.0-only).
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 手机号与验证码登录：区号取所参考官方 APK 登录配置的三个默认项。
 * 发码与验码沿用设备表身份，不新注册设备；凭据与该身份一起保存。
 * 请求受理不等同于短信送达，非大陆号码尚未完成实号验收。
 * 一个实例保留本次验证码票据；重新发码会清除旧票据与登录凭据。
 */
class PhoneLogin(private val http: OkHttpClient) {
    var deviceId = ""
    var installId = ""
    var mobile = ""
    var ticket = ""
    var type = LOGIN_CODE_TYPE
    private var expired = false
    val cookies = linkedMapOf<String, String>()
    /** Compatibility field; authentication responses are never retained here. */
    val trace = mutableListOf<String>()

    /** 平台拒绝时的可读原因，界面直接显示 [message]。 */
    class Failure(message: String, val errorCode: Int? = null, val canRetryCode: Boolean = false) :
        Exception(message)

    /** [maskedMobile] 是平台回显的打码号码（如 `+886******23`）。 */
    data class Sent(val maskedMobile: String, val retrySeconds: Int)

    fun send(mobile: String): Sent {
        // 沿用现有设备表兼容请求；本次区号修复不改变底层登录身份协议。
        deviceId = VendorConstants.device.getValue("device_id")
        installId = VendorConstants.device.getValue("iid")
        cookies.clear()
        expired = false
        ticket = ""
        this.mobile = parse(mobile)
        val reply =
            call(
                "/passport/mobile/send_code/v1/",
                mapOf("mobile" to this.mobile, "type" to type),
                form = false,
            )
        val data = reply.optJSONObject("data") ?: JSONObject()
        // 风控要求滑块验证：电视上没法做，也不去绕它。
        if (data.has("verify_center_decision_conf"))
            throw Failure("平台要求先完成滑块验证，当前电视登录流程不支持，尚未完成登录。")
        val errorCode = data.optInt("error_code", 0)
        if (reply.optString("message") != "success" || errorCode != 0)
            throw Failure(describe(data) ?: "平台未受理验证码请求", errorCode.takeIf { it != 0 })
        ticket = data.optString("mobile_ticket")
        val fallbackMask = this.mobile.substringBefore(' ', "") +
            (if (this.mobile.contains(' ')) " " else "") + "******" + this.mobile.takeLast(2)
        return Sent(data.optString("mobile").ifBlank { fallbackMask }, data.optInt("retry_time", 60))
    }

    fun verify(code: String): AccountCredentials {
        if (expired) throw Failure("本次登录会话已失效，请重新获取验证码", 1)
        check(deviceId.isNotEmpty()) { "请先发送验证码" }
        var params =
            mapOf("mobile" to mobile, "code" to code, "type" to type, "mobile_ticket" to ticket)
        val failures = mutableListOf<Failure>()
        for ((path, form) in VERIFY_ATTEMPTS) {
            val reply = call(path, params, form)
            if (cookies.containsKey("sessionid")) break
            val data = reply.optJSONObject("data")
            // 平台会在每次尝试后下发新的 mobile_ticket，后续端点沿用最新值。
            data?.optString("mobile_ticket")?.takeIf(String::isNotBlank)?.let { fresh ->
                ticket = fresh
                params = params + ("mobile_ticket" to fresh)
            }
            val errorCode = data?.optInt("error_code", 0) ?: 0
            when {
                // 1 = 会话过期：这条端点不适用于「未登录换验证码」，继续试下一条。
                errorCode == 0 || errorCode == 1 -> {
                    if (errorCode == 1) failures += Failure("会话过期，请重新登录（错误码 1）", 1)
                    continue
                }
                // 1203 = 错误次数过多或验证码过期：本次会话作废，继续提交只会更糟。
                errorCode == 1203 -> {
                    expired = true
                    cookies.clear()
                    ticket = ""
                    throw Failure(
                        describe(data) ?: "错误次数过多或验证码过期，请重新获取验证码（错误码 $errorCode）",
                        errorCode,
                    )
                }
                else ->
                    throw Failure(
                        describe(data) ?: "验证码校验失败（错误码 $errorCode）",
                        errorCode,
                        errorCode == 1102,
                    )
            }
        }
        if (!cookies.containsKey("sessionid")) {
            throw failures.firstOrNull()
                ?: Failure("登录没有成功，平台没有下发登录态")
        }
        val header = cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
        return AccountCredentialsParser.parseHeader(header)
            .copy(
                deviceId = deviceId,
                installId = installId,
                device = mapOf("device_id" to deviceId, "iid" to installId),
            )
    }

    /** 带这台设备的身份签名后发一次 passport 请求；`Set-Cookie` 并进 [cookies]，返回 JSON（非 JSON 时包成 `raw`）。 */
    private fun call(path: String, params: Map<String, String>, form: Boolean): JSONObject {
        val device =
            LinkedHashMap(VendorConstants.device).apply {
                put("device_id", deviceId)
                put("iid", installId)
                for (key in
                    listOf(
                        "version_code",
                        "manifest_version_code",
                        "update_version_code",
                        "pv_player",
                    )) put(key, "73932")
                put("version_name", "7.3.9.32")
            }
        val passport =
            mapOf("aid" to "8662", "app_name" to "novelread", "device_platform" to "android") +
                params.filterValues { it.isNotBlank() }
        val payload = if (form) Signer.encode(passport).toByteArray() else null
        // 官方 App 的 passport 请求把 device_id/iid 从 query 里去掉（设备靠 Cookie 识别），
        // 带着它们发会被判异常设备；签名器内部仍使用完整设备表。
        val queryDevice = device.filterKeys { it != "device_id" && it != "iid" }
        val signed =
            Signer(device = device)
                .sign(PASSPORT + path, if (form) queryDevice else queryDevice + passport, payload)
        val request = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> request.header(k, v) }
        // 刻意不回发 Cookie：实测把 send_code 下发的 cookie 带到 sms_login 会被 passport
        // 判 `2046`，而不带 cookie 时同一请求回 `1202 验证码错误`。Set-Cookie 仍会解析并保留，
        // 只用于判断是否已经拿到 sessionid。
        if (payload != null)
            request.post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        val reply =
            http.newCall(request.build()).execute().use { response ->
                response.headers("Set-Cookie").forEach { line ->
                    val pair = line.substringBefore(';')
                    val name = pair.substringBefore('=').trim()
                    val value = pair.substringAfter('=', "").trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
                }
                val text = cloudResponse(response)
                runCatching { JSONObject(text) }
                    .getOrElse {
                        JSONObject().put("http", response.code).put("raw", text.take(300))
                    }
            }
        // Never retain authentication responses or verification codes in diagnostics.
        return reply
    }

    private fun describe(data: JSONObject): String? =
        data
            .optString("description")
            .takeIf { it.isNotBlank() }
            ?.let { "$it（错误码 ${data.optInt("error_code")}）" }

    companion object {
        /** 官方 App 的 passport 域是 `security.snssdk.com`；`is.snssdk.com` 上短信登录会被风控回 2046。 */
        private const val PASSPORT = "https://security.snssdk.com"
        /** login-lab 实测：24 = 登录验证码；1 / 3 是绑定 / 换绑手机号。 */
        const val LOGIN_CODE_TYPE = "24"
        /**
         * 短信登录的主端点是 `sms_login` 系列；`validate_code` 是「已登录后校验手机号」的入口，
         * 对未登录会话固定回 `error_code 1 会话过期`（2026-11-03 实测），因此放到最后兜底。
         */
        private val VERIFY_ATTEMPTS =
            listOf(
                "/passport/mobile/sms_login/" to true,
                "/passport/mobile/sms_login_only/" to true,
                "/passport/mobile/sms_login_continue/" to true,
                "/passport/mobile/validate_code/v1/" to false,
                "/passport/mobile/validate_code/v1/" to true,
            )

        data class Region(val code: String, val name: String, val digits: Int)

        /** 参考官方 7.4.3.32 登录配置的默认项，不等同于各地区短信已验证可用。 */
        val regions = listOf(
            Region("86", "中国大陆", 11),
            Region("852", "中国香港", 8),
            Region("853", "中国澳门", 8),
        )

        /** 只清理常见排版符号，不悄悄删除字母或具有号码含义的前导零。 */
        fun format(countryCode: String, number: String): String {
            val region = regions.singleOrNull { it.code == countryCode.removePrefix("+") }
                ?: throw IllegalArgumentException("当前仅提供 +86、+852、+853；其他地区短信尚未验证，请改用官方 App 或扫码登录")
            require(number.isNotBlank() && number.all { it in '0'..'9' || it in " -()" }) {
                "号码只能包含数字、空格、短横线或括号"
            }
            val digits = number.filter { it in '0'..'9' }
            require(digits.length == region.digits) { "${region.name}手机号需 ${region.digits} 位数字，不要重复输入区号" }
            if (region.code == "86") {
                require(digits.startsWith("1")) { "大陆手机号是 1 开头的 11 位数字" }
                return digits
            }
            return "+${region.code} $digits"
        }

        /** 支持带或不带分隔符的完整号码；只解析当前明确提供的区号。 */
        fun parse(raw: String): String {
            val text = raw.trim()
            if (!text.startsWith("+")) return format("86", text)
            val international = text.drop(1)
            val region = regions.sortedByDescending { it.code.length }.firstOrNull {
                international.startsWith(it.code)
            } ?: throw IllegalArgumentException("当前仅提供 +86、+852、+853；其他地区短信尚未验证，请改用官方 App 或扫码登录")
            return format(region.code, international.drop(region.code.length).trimStart(' ', '-'))
        }
    }
}

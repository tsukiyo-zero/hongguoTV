// Adapted 2026-10-21 from charlie901208/HGTV feat/phone-login (GPL-3.0-only).
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 手机号与验证码登录：区号按用户提供的官方登录界面截图核对。
 * 发码与验码复用正常匿名注册身份；公共表单、签名及凭据使用同一身份。
 * 请求受理不等同于短信送达，非大陆号码尚未完成实号验收。
 * 一个实例保留本次验证码票据；重新发码会清除旧票据与登录凭据。
 */
class PhoneLogin(
    http: OkHttpClient,
    private val deviceSession: PlaybackDeviceSession = PlaybackDeviceSession.shared,
) {
    // 发码是有副作用的操作：一次点击只允许一次传输，不跟随重定向或自动重试。
    private val http = http.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
    private var profile: PlaybackDeviceSession.Profile? = null
    private var registrationCookies = emptyMap<String, String>()
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
    class Failure(
        message: String,
        val errorCode: Int? = null,
        val canRetryCode: Boolean = false,
        val requiresHumanVerification: Boolean = false,
    ) :
        Exception(message)

    /** [maskedMobile] 是平台回显的打码号码（如 `+886******23`）。 */
    data class Sent(val maskedMobile: String, val retrySeconds: Int)

    fun send(mobile: String): Sent {
        // 号码先校验，不能为无效输入注册设备或请求短信。
        this.mobile = parse(mobile)
        cookies.clear()
        expired = false
        ticket = ""
        val ready = deviceSession.profile(this.http)
        profile = ready
        deviceId = ready.values.getValue("device_id")
        installId = ready.values.getValue("iid")
        registrationCookies = ready.cookie.split(';').mapNotNull { raw ->
            val pair = raw.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0].isNotBlank()) pair[0] to pair[1] else null
        }.toMap()
        val reply =
            call(
                "/passport/mobile/send_code/v1/",
                mapOf("mobile" to passportMobile(), "type" to type, "unbind_exist" to "0", "auto_read" to "0"),
                form = true,
            )
        val data = reply.optJSONObject("data") ?: JSONObject()
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
            mapOf("mobile" to passportMobile(), "code" to code, "mobile_ticket" to ticket)
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
                // 保留原会话错误；兼容尝试仅限下一条已核对的短信登录入口。
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
        val header = (registrationCookies + cookies).entries.joinToString("; ") { (name, value) -> "$name=$value" }
        return AccountCredentialsParser.parseHeader(header)
            .copy(
                deviceId = deviceId,
                installId = installId,
                device = deviceValues(),
            )
    }

    // 官方安卓输入组件直接拼接区号和号码；展示用的分隔空格不能进入 App 协议。
    private fun passportMobile(): String = mobile.replace(" ", "")

    private fun deviceValues(): Map<String, String> =
            LinkedHashMap(profile?.values ?: VendorConstants.device).apply {
                put("device_id", deviceId)
                put("iid", installId)
                for (key in
                    listOf(
                        "version_code",
                        "manifest_version_code",
                        "update_version_code",
                        "pv_player",
                    )) put(key, "74332")
                put("version_name", "7.4.3.32")
            }

    /** 带这台设备的身份签名后发一次 passport 请求；`Set-Cookie` 并进 [cookies]，返回 JSON（非 JSON 时包成 `raw`）。 */
    private fun call(path: String, params: Map<String, String>, form: Boolean): JSONObject {
        val device = deviceValues()
        val passport =
            device +
                params.filterValues { it.isNotBlank() }
        // 官方 App 的账号 SDK 对指定字段执行逐字节 XOR 5，再提交十六进制表单。
        val mixed = passport.mapValues { (key, value) ->
            if (key in setOf("mobile", "code", "type", "unbind_exist")) mix(value) else value
        } + mapOf("mix_mode" to "1", "account_sdk_source" to "app")
        val payload = if (form) Signer.encode(mixed).toByteArray(Charsets.UTF_8) else null
        // 与公共表单使用同一注册身份，不能删去已确认由官方公共参数提供的设备字段。
        val queryDevice = device + mapOf("passport-sdk-version" to "5051452")
        val signed =
            (profile?.passportSigner(System::currentTimeMillis, device) ?: Signer(device = device))
                .sign(PASSPORT + path, if (form) queryDevice else queryDevice + mixed, payload)
        val request = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> request.header(k, v) }
        request.header("User-Agent", "com.phoenix.read/74332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)")
        request.removeHeader("passport-sdk-version")
        // 保留注册及该登录流程的同域会话；不借用外部账号或网页会话。
        val sessionCookies = registrationCookies + cookies
        if (sessionCookies.isNotEmpty()) request.header("Cookie", sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
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
        rejectHumanVerification(reply)
        // Never retain authentication responses or verification codes in diagnostics.
        return reply
    }

    /** 官方验证流程同时检查顶层和 data；仅错误7不能证明需要滑块。 */
    private fun rejectHumanVerification(reply: JSONObject) {
        val data = reply.optJSONObject("data")
        val nodes = listOfNotNull(reply, data)
        val code = nodes.firstNotNullOfOrNull { node ->
            listOf("error_code", "code").firstNotNullOfOrNull { key ->
                node.optInt(key, 0).takeIf { it != 0 }
            }
        }
        val challenge = nodes.any { node ->
            listOf("verify_center_decision_conf", "verify_center_secondary_decision_conf").any { key ->
                !node.isNull(key) && node.optString(key).isNotBlank()
            }
        }
        if (challenge || code == 1104 || code == 1105) {
            expired = true
            ticket = ""
            cookies.clear()
            throw Failure(
                "平台要求先完成人工验证（如滑块），当前电视尚未接入该流程。请在官方 App 完成登录，再使用电视的扫码登录。",
                errorCode = code,
                requiresHumanVerification = true,
            )
        }
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
        /** 静态核对官方 App 的两条短信登录入口，均为编码后的 POST 表单。 */
        private val VERIFY_ATTEMPTS = listOf(
            "/passport/mobile/sms_login/" to true,
            "/passport/mobile/sms_login_only/" to true,
        )

        internal fun mix(value: String): String = value.toByteArray(Charsets.UTF_8).joinToString("") {
            "%02x".format((it.toInt() and 255) xor 5)
        }

        data class Region(val code: String, val name: String, val digits: Int? = null)

        /** 官方登录截图可确认的区号；仅有明确依据的位数做严格校验，不等于短信可达。 */
        val regions = listOf(
            Region("86", "中国大陆", 11),
            Region("852", "中国香港", 8),
            Region("853", "中国澳门", 8),
            Region("886", "中国台湾"),
            Region("60", "马来西亚"),
            Region("65", "新加坡"),
            Region("62", "印度尼西亚"),
            Region("95", "缅甸"),
            Region("63", "菲律宾"),
            Region("84", "越南"),
            Region("855", "柬埔寨"),
            Region("81", "日本"),
            Region("82", "韩国"),
            Region("971", "阿拉伯联合酋长国"),
        )

        /** 只清理常见排版符号，不悄悄删除字母或具有号码含义的前导零。 */
        fun format(countryCode: String, number: String): String {
            val region = regions.singleOrNull { it.code == countryCode.removePrefix("+") }
                ?: throw IllegalArgumentException("该区号尚未列入已核对的官方登录列表，请改用官方 App 或扫码登录")
            require(number.isNotBlank() && number.all { it in '0'..'9' || it in " -()" }) {
                "号码只能包含数字、空格、短横线或括号"
            }
            val digits = number.filter { it in '0'..'9' }
            if (region.digits != null) {
                require(digits.length == region.digits) { "${region.name}手机号需 ${region.digits} 位数字，不要重复输入区号" }
            } else {
                // 截图未提供各地位数规则，只限制国际号码总长度，不猜测本地拨号规则。
                require(digits.length >= 4 && region.code.length + digits.length <= 15) {
                    "号码长度不对：区号加号码最多 15 位，本地号码至少 4 位"
                }
            }
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
            } ?: throw IllegalArgumentException("该区号尚未列入已核对的官方登录列表，请改用官方 App 或扫码登录")
            return format(region.code, international.drop(region.code.length).trimStart(' ', '-'))
        }
    }
}

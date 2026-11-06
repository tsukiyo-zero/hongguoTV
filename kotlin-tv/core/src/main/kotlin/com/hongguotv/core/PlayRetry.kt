// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException

/**
 * Bounded fallback retries for playback model requests (issue #38).
 * 2026-11-06: HTTP/2 may reuse/coalesce connections despite Connection: close.
 * The caller must use an isolated pool for each retry. Fresh connections are not
 * a cure for every HTTP 200/0B response; keep timing/count unchanged and diagnose.
 */
internal object PlayRetryPlan {
    val hosts =
        listOf(
            "api5-normal-sinfonlineb.fqnovel.com",
            "api5-normal-hl.fqnovel.com",
            "api5-normal-lf.fqnovel.com",
        )

    /** 第 1 次尝试后各次重试前的退避；三次重试合计 5.2 秒。 */
    val delaysMillis = listOf(700L, 1500L, 3000L)

    val maxAttempts = hosts.size + 1

    fun host(attempt: Int) = hosts[attempt % hosts.size]

    /** 首次使用共享连接池；重试使用独立连接池，不保证切换服务端节点。 */
    fun freshConnection(attempt: Int) = attempt > 0

    /** 诊断行用的短代号；不暴露完整域名以外的任何信息。 */
    fun tag(host: String) =
        when (host) {
            "api5-normal-sinfonlineb.fqnovel.com" -> "b"
            "api5-normal-hl.fqnovel.com" -> "hl"
            "api5-normal-lf.fqnovel.com" -> "lf"
            else -> host
        }

    /** 与 ContentRepository.text() 相同的临时故障判定：连接类故障与 5xx 值得换路重试。 */
    fun temporary(problem: IOException) =
        problem is EmptyPlaybackResponseException ||
            problem is java.net.SocketTimeoutException ||
            problem is java.net.SocketException ||
            problem is java.net.UnknownHostException ||
            (problem is ContentHttpException && problem.statusCode in setOf(500, 502, 503, 504))
}

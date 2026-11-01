package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

fun main(args: Array<String>) {
    val repository = ContentRepository()
    if (args.contains("--category-recommendations")) {
        try {
            for (category in listOf(DramaCategory.COMIC, DramaCategory.REAL)) {
                val cursor = RecommendationCursor()
                val feed = RecommendationFeed(repository.http, cursor = cursor, category = category)
                val first = feed.page(1)
                val second = feed.page(2)
                val fresh = second.items.count { row -> first.items.none { it.id == row.id } }
                check(first.items.isNotEmpty() && fresh > 0)
                val detail = repository.detail(first.items.first().id)
                check(detail.episodes.isNotEmpty())
                first.trialHints[first.items.first().id]?.let {
                    check(it.episodeId == detail.episodes.first())
                    check(it.episodeCount == detail.episodes.size)
                }
                println("category=${category.name}; directTrialHints=${first.trialHints.size}")
                if (args.contains("--prepare-video")) {
                    val remote =
                        RemoteVideo(
                            repository.http,
                            repository.stream(detail.episodes.first(), 720),
                        )
                    try {
                        remote.prepare()
                        check(remote.total > 0)
                        println("category=${category.name}; videoPrepared=true")
                    } finally {
                        remote.close()
                    }
                }
                println(
                    "category=${category.name}; first=${first.items.size}; second=${second.items.size}; new=$fresh; detailEpisodes=${detail.episodes.size}"
                )
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--recommendations")) {
        try {
            val cursor = RecommendationCursor()
            val feed = RecommendationFeed(repository.http, cursor = cursor)
            val first = feed.page(1)
            val second = feed.page(2)
            val new = second.items.count { row -> first.items.none { it.id == row.id } }
            check(first.items.isNotEmpty() && second.items.isNotEmpty() && new > 0)
            val refreshed = RecommendationFeed(repository.http, cursor = cursor.nextBatch()).page(1)
            val initial = first.items.first()
            val hint = first.trialHints[initial.id]
            if (hint != null) {
                val detail = repository.detail(initial.id)
                check(hint.episodeId == detail.episodes.first())
                check(hint.episodeCount == detail.episodes.size)
            }
            println("recommendationDirectTrialHints=${first.trialHints.size}")
            println(
                "officialFeed=true; first=${first.items.size}; second=${second.items.size}; new=$new; firstMore=${first.hasMore}; secondMore=${second.hasMore}; refreshed=${refreshed.items.size}; authenticated=false"
            )
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--discovery")) {
        try {
            for (category in DramaCategory.entries) {
                val first = repository.discover(category)
                val info = checkNotNull(first.discovery)
                check(first.items.isNotEmpty() && info.topics.isNotEmpty())
                val topic = info.topics.first()
                val filtered = repository.discover(category, topic.id)
                val second = repository.discover(category, topic.id, 2)
                check(filtered.items.isNotEmpty() && second.items.isNotEmpty())
                check(filtered.items.map { it.id } != second.items.map { it.id })
                println(
                    "category=${category.route}; topics=${info.topics.joinToString { it.label }}; first=${first.items.size}; filtered=${filtered.items.size}; secondPage=${second.items.size}; totalPages=${info.totalPages}"
                )
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--tv-rankings")) {
        try {
            for (board in RankBoard.entries) {
                val snapshot = repository.rankSnapshot(board)
                check(snapshot.items.isNotEmpty()) { "Empty ${board.name} board" }
                println(
                    "board=${board.name}; items=${snapshot.items.size}; topics=${snapshot.topics.joinToString(",")}; ranks=${snapshot.ranking.positions.values.mapNotNull { it.rank }.minOrNull()}..${snapshot.ranking.positions.values.mapNotNull { it.rank }.maxOrNull()}"
                )
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--issue38")) {
        // 复现 issue #38「播放信息接口返回空响应」：同一请求构造下对比设备身份、device_level、
        // 备选 host 与时钟偏差。输出仅含计数与档位元数据，不打印地址与凭据。
        val sessionA = PlaybackDeviceSession()
        val sessionB = PlaybackDeviceSession()
        val repo = ContentRepository(playbackSession = sessionB)
        try {
            val keyword =
                args.getOrNull(args.indexOf("--issue38") + 1)?.takeIf { !it.startsWith("--") }
                    ?: "美人替嫁祁王"
            val found =
                runCatching { repo.search(keyword) }.getOrElse { repo.searchAll(keyword) }
            val series =
                found.items.firstOrNull { it.title == "春色韫韫，美人替嫁祁王" }
                    ?: found.items.firstOrNull { "美人替嫁祁王" in it.title }
            checkNotNull(series) { "search=${found.items.size}; no match for $keyword" }
            println("issue38 series=${series.title}; id=${series.id}")
            val detail = repo.detail(series.id)
            println("issue38 episodes=${detail.episodes.size}")
            val episode8 = detail.episodes.getOrNull(7) ?: detail.episodes.first()
            val targets =
                listOfNotNull(
                    detail.episodes.getOrNull(0)?.let { "ep1" to it },
                    "ep8" to episode8,
                )
            val identityA = sessionA.profile(repo.http)
            println(
                "issue38 identity=A; storeRegion=${Regex("store-region=([^;]+)").find(identityA.cookie)?.groupValues?.get(1) ?: "none"}"
            )
            for ((label, episodeId) in targets) {
                for (level in listOf(1, 3)) {
                    println(
                        "issue38 probe=$label; level=$level; ${playerProbe(repo.http, sessionA, episodeId, level, PLAYER_HOST_PRIMARY)}"
                    )
                }
            }
            // 失效分集 id 假设：收藏/历史里可能保存着下架或重发前的旧 id。
            println(
                "issue38 probe=bogus; level=1; ${playerProbe(repo.http, sessionA, "1", 1, PLAYER_HOST_PRIMARY)}"
            )
            for (host in PLAYER_HOST_FALLBACKS) {
                println(
                    "issue38 host=$host; ${playerProbe(repo.http, sessionA, episode8, 1, host)}"
                )
            }
            // 端到端：与线上 streamLadder 完全一致（repository 自持身份 B，含三次退避重试）。
            try {
                val ladder = repo.streamLadder(episode8)
                println(
                    "issue38 e2e=ok; tiers=${ladder.renditions.size}; codecs=${ladder.renditions.joinToString { it.codec }}"
                )
            } catch (problem: Exception) {
                println(
                    "issue38 e2e=FAILED; ${problem.javaClass.simpleName}; ${problem.message?.take(120)}"
                )
            }
            // 身份标记实验：被标记身份是否持续失败、新身份是否立即恢复。
            println(
                "issue38 again=B1; ${playerProbe(repo.http, sessionB, episode8, 1, PLAYER_HOST_PRIMARY)}"
            )
            println(
                "issue38 again=B2; ${playerProbe(repo.http, sessionB, episode8, 1, PLAYER_HOST_PRIMARY)}"
            )
            val sessionC = PlaybackDeviceSession()
            println(
                "issue38 again=C1; ${playerProbe(repo.http, sessionC, episode8, 1, PLAYER_HOST_PRIMARY)}"
            )
            val identityC = sessionC.profile(repo.http)
            println(
                "issue38 identity=C; storeRegion=${Regex("store-region=([^;]+)").find(identityC.cookie)?.groupValues?.get(1) ?: "none"}"
            )
            println(
                "issue38 again=B3; ${playerProbe(repo.http, sessionB, episode8, 1, PLAYER_HOST_PRIMARY)}"
            )
            // 窗口捕捉：2 分钟内每 ~4 秒轮询三条路径（主 host 复用连接 / 主 host 新连接 /
            // 备 host 新连接）。空响应窗口出现时，当场可见哪条路径能逃出去。
            val deadline = System.currentTimeMillis() + 120_000
            var cycle = 0
            var empties = 0
            while (System.currentTimeMillis() < deadline) {
                cycle++
                val marks =
                    listOf(
                            playerProbe(repo.http, sessionA, episode8, 1, PLAYER_HOST_PRIMARY),
                            playerProbe(repo.http, sessionA, episode8, 1, PLAYER_HOST_PRIMARY, fresh = true),
                            playerProbe(repo.http, sessionA, episode8, 1, PLAYER_HOST_FALLBACKS.first(), fresh = true),
                        )
                        .map { if (it.contains("EMPTY")) "E" else "." }
                empties += marks.count { it == "E" }
                if (marks.any { it == "E" })
                    println(
                        "issue38 window; cycle=$cycle; pooled=${marks[0]}; fresh=${marks[1]}; altHost=${marks[2]}"
                    )
                Thread.sleep(3000)
            }
            println("issue38 windowSummary; cycles=$cycle; empties=$empties")
        } finally {
            repo.http.connectionPool.evictAll()
            repo.http.dispatcher.executorService.shutdown()
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--soak")) {
        // 长时间采样空响应窗口（issue #38/#41 后续观测）：每约 5 秒一轮 × 3 条路径（主 host
        // 池化连接 / 主 host 新连接 / 备用 host hl 新连接），与线上重试策略及 --issue38 窗口
        // 捕捉一致。统计窗口簇数、簇时长、簇内路径分歧与总空响应率，为恢复预算判据提供数据。
        // 输出仅含计数与 host 代号，不打印地址与凭据。
        val minutes =
            args.getOrNull(args.indexOf("--soak") + 1)?.takeIf { !it.startsWith("--") }?.toIntOrNull()
                ?: 45
        val sessionA = PlaybackDeviceSession()
        val repo = ContentRepository(playbackSession = sessionA)
        try {
            val keyword = "美人替嫁祁王"
            val found = runCatching { repo.search(keyword) }.getOrElse { repo.searchAll(keyword) }
            val series =
                found.items.firstOrNull { it.title == "春色韫韫，美人替嫁祁王" }
                    ?: found.items.firstOrNull { "美人替嫁祁王" in it.title }
            checkNotNull(series) { "search=${found.items.size}; no match for $keyword" }
            val detail = repo.detail(series.id)
            val episode8 = detail.episodes.getOrNull(7) ?: detail.episodes.first()
            println(
                "soak series=${series.title}; id=${series.id}; episodes=${detail.episodes.size}; minutes=$minutes"
            )
            val deadline = System.currentTimeMillis() + minutes * 60_000L
            var cycle = 0
            var probes = 0
            var empties = 0
            var clusters = 0
            var inCluster = false
            var clusterStart = 0L
            var clusterLast = 0L
            var longestSpan = 0L
            var escape = ""
            while (System.currentTimeMillis() < deadline) {
                cycle++
                val marks =
                    listOf(
                            "pooled" to
                                playerProbe(repo.http, sessionA, episode8, 1, PLAYER_HOST_PRIMARY),
                            "fresh" to
                                playerProbe(repo.http, sessionA, episode8, 1, PLAYER_HOST_PRIMARY, fresh = true),
                            "altHost" to
                                playerProbe(repo.http, sessionA, episode8, 1, PLAYER_HOST_FALLBACKS.first(), fresh = true),
                        )
                        .map { (label, result) -> label to result.contains("EMPTY") }
                probes += marks.size
                val now = System.currentTimeMillis()
                val bad = marks.filter { it.second }.map { it.first }
                val good = marks.filterNot { it.second }.map { it.first }
                empties += bad.size
                if (bad.isNotEmpty()) {
                    if (!inCluster) {
                        inCluster = true
                        clusterStart = now
                        escape = ""
                        clusters++
                    }
                    clusterLast = now
                    // 同一轮内「部分路径空、部分路径通」即路径分歧：记下第一条逃逸路径。
                    if (good.isNotEmpty() && escape.isEmpty()) escape = good.first()
                    println(
                        "soak window; cycle=$cycle; empty=${bad.joinToString(",")}; ok=${good.joinToString(",")}"
                    )
                } else if (inCluster) {
                    inCluster = false
                    val span = clusterLast - clusterStart
                    if (span > longestSpan) longestSpan = span
                    println(
                        "soak windowEnd; cluster=$clusters; spanMs=$span; escape=${escape.ifEmpty { "none-observed" }}"
                    )
                }
                if (cycle % 10 == 0)
                    println("soak progress; cycle=$cycle; clusters=$clusters; empty=$empties/$probes")
                Thread.sleep(3000)
            }
            val spanText =
                if (inCluster) "ongoingSpanMs=${System.currentTimeMillis() - clusterStart}"
                else "longestSpanMs=$longestSpan"
            val rate = if (probes > 0) "%.1f%%".format(java.util.Locale.US, empties * 100.0 / probes) else "n/a"
            println(
                "soak summary; minutes=$minutes; cycles=$cycle; probes=$probes; empty=$empties; rate=$rate; clusters=$clusters; $spanText"
            )
        } finally {
            repo.http.connectionPool.evictAll()
            repo.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--official-qr-login")) {
        try {
            val login = WebLogin(repository.http)
            val qr = login.qr()
            java.io.File("/tmp/hongguotv-official-login.png").writeBytes(qr.image)
            println(
                "officialQrReady=true; image=/tmp/hongguotv-official-login.png; expiresIn=${qr.expiresIn}"
            )
            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(3000)
                if (!login.poll()) continue
                println("officialWebLogin=true")
                val web = login.credentials()
                val client = CloudAccountClient(repository.http)
                val device = checkNotNull(client.registerDevice()) { "Device registration failed" }
                val account = web.copy(deviceId = device.deviceId, installId = device.installId)
                val profile = client.profile(account)
                println("mobileAccountVerified=true; idPresent=${profile.id.isNotBlank()}")
                val history = client.history(account)
                println("cloudHistoryLoaded=true; items=${history.size}")
                return
            }
            println("qrExpired=true")
        } catch (error: Exception) {
            println("loginTestFailed=${error.message?.take(180)}")
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--account-register")) {
        try {
            val registered = CloudAccountClient(repository.http).registerDevice()
            check(registered != null) { "Account device registration rejected" }
            println("accountDeviceRegistered=true; credentialsRequested=false; smsSent=false")
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--search-all")) {
        try {
            val keyword = args.getOrNull(args.indexOf("--search-all") + 1) ?: "大佬"
            for (page in 1..2) {
                val result = repository.searchAll(keyword, page)
                println(
                    "allSearchPage=$page; items=${result.items.size}; hasMore=${result.hasMore}; titles=${result.items.take(5).joinToString { it.title }}"
                )
                if (!result.hasMore) break
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--ranking")) {
        try {
            val first = repository.comicRanking()
            val metadata = checkNotNull(first.ranking)
            check(first.items.isNotEmpty())
            val seen = mutableSetOf<String>()
            for (pageNumber in
                listOf(1, 2, metadata.totalPages).distinct().filter { it <= metadata.totalPages }) {
                val result = if (pageNumber == 1) first else repository.comicRanking(pageNumber)
                val ranking = checkNotNull(result.ranking)
                check(result.items.isNotEmpty() && result.items.none { it.id in seen })
                seen.addAll(result.items.map { it.id })
                val ranks = result.items.map { checkNotNull(ranking.positions[it.id]).rank }
                check(
                    ranks.all { it != null && it > 0 } &&
                        ranks.filterNotNull().zipWithNext().all { (a, b) -> a < b }
                )
                check(result.items.all { ranking.positions[it.id]!!.heat.isNotBlank() })
                check(result.hasMore == (pageNumber < ranking.totalPages))
                println(
                    "rankingPage=$pageNumber/${ranking.totalPages}; items=${result.items.size}; ranks=${ranks.first()}..${ranks.last()}; firstHeat=${ranking.positions[result.items.first().id]!!.heat}; updated=${ranking.updatedText}"
                )
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    val type = if (args.contains("--comic")) ContentType.COMIC else ContentType.SHORT
    val keyword = if (type == ContentType.COMIC) "修仙" else "总裁"
    val search = repository.search(keyword, type = type)
    println("search=${search.items.size}; hasMore=${search.hasMore}")
    if (type == ContentType.COMIC && search.hasMore) {
        val next = repository.search(keyword, 2, type)
        println(
            "searchPage2=${next.items.size}; overlap=${search.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}"
        )
    }
    val home = repository.home(type = type)
    println("type=${type.label}; home=${home.items.size}; hasMore=${home.hasMore}")
    if (type == ContentType.COMIC) {
        val next = repository.home(2, type)
        println(
            "homePage2=${next.items.size}; overlap=${home.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}"
        )
    }
    val detail =
        repository.detail(
            args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: home.items.first().id
        )
    println("detail=${detail.series.title}; episodes=${detail.episodes.size}")
    val stream = repository.stream(detail.episodes.first())
    RemoteVideo(repository.http, stream).use { remote ->
        remote.prepare()
        val first = remote.read(0, 1024 * 1024)
        val tail = remote.read((remote.total - 65536).coerceAtLeast(0), 65536)
        println(
            "quality=${stream.quality}; total=${remote.total}; first=${first.size}; tail=${tail.size}; header=${String(first,4,4)}"
        )
        if (args.contains("--save")) java.io.File("/tmp/hongguo-kotlin-first.mp4").writeBytes(first)
    }
    repository.http.connectionPool.evictAll()
    repository.http.dispatcher.executorService.shutdown()
}

private const val PLAYER_HOST_PRIMARY = "api5-normal-sinfonlineb.fqnovel.com"

private val PLAYER_HOST_FALLBACKS =
    listOf(
        "api5-normal-hl.fqnovel.com",
        "api5-normal-lf.fqnovel.com",
        "api5-normal-sinfonlinec.fqnovel.com",
    )

/**
 * 播放信息接口单发探测：请求构造与 ContentRepository.streamLadder 完全一致，但不做内部重试。
 * 输出仅含 HTTP 状态、错误码、档位元数据与本机时钟偏差，不打印签名查询、Cookie 或媒体地址。
 */
private fun playerProbe(
    http: OkHttpClient,
    session: PlaybackDeviceSession,
    episodeId: String,
    level: Int,
    host: String,
    fresh: Boolean = false,
): String {
    val identity = session.profile(http)
    val payload =
        """{"biz_param":{"detail_page_version":0,"device_level":$level,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":${org.json.JSONArray(listOf(episodeId))}}}"""
            .toByteArray()
    val query =
        Signer.encode(identity.values + ("_rticket" to System.currentTimeMillis().toString()))
    val request =
        okhttp3.Request.Builder()
            .url("https://$host/novel/player/multi_video_model/v1/?$query")
            .header(
                "User-Agent",
                "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)",
            )
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .apply {
                if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie)
                // 强制每发新建 TCP+TLS，避免连接池把重试粘在同一个后端节点上。
                if (fresh) header("Connection", "close")
            }
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    return try {
        RequestScope.execute(http.newCall(request)) { response ->
            val skew =
                response.header("Date")?.let {
                    val format =
                        java.text.SimpleDateFormat(
                            "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
                            java.util.Locale.US,
                        )
                    format.timeZone = java.util.TimeZone.getTimeZone("GMT")
                    format.parse(it)?.let { parsed -> (System.currentTimeMillis() - parsed.time) / 1000 }
                }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@execute "http=${response.code}; body=EMPTY; skewSec=$skew"
            val json = org.json.JSONObject(body)
            val code = json.optInt("Code", json.optInt("code", 0))
            val data = json.optJSONObject("data")
            val row = data?.optJSONObject(episodeId)
            val model =
                row?.opt("video_model")?.let { value ->
                    runCatching {
                        (value as? org.json.JSONObject) ?: org.json.JSONObject(value.toString())
                    }
                        .getOrNull()
                }
            val renditions =
                model?.optJSONArray("video_list")?.objects().orEmpty().mapNotNull { entry ->
                    val meta = entry.optJSONObject("video_meta") ?: return@mapNotNull null
                    "${meta.optString("codec_type")}/${meta.optString("definition")}/${meta.optInt("vwidth")}x${meta.optInt("vheight")}"
                }
            "http=${response.code}; code=$code; rows=${data?.length() ?: 0}; renditions=${renditions.joinToString(",")}; skewSec=$skew"
        }
    } catch (problem: Exception) {
        "error=${problem.javaClass.simpleName}: ${problem.message?.take(80)}"
    }
}

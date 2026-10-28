// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class Session(
    val series: Series,
    val episodeID: String,
    val index: Int,
    var trial: Boolean,
)

/**
 * Single source of truth for the window. Every bridge call runs off the UI thread and
 * every user-visible failure lands in [status] instead of a dialog.
 */
class AppState(
    val library: LocalLibrary = LocalLibrary(),
    val preferences: Preferences = Preferences(),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val comments = CommentsController(
        fetchSocial = { series, episode ->
            api { bridge ->
                bridge.get("comments", mapOf("series" to series, "episode" to episode)).toSocial()
            }
        },
        fetchPage = { series, episode, cursor ->
            api { bridge ->
                bridge
                    .get(
                        "comment-page",
                        mapOf("series" to series, "episode" to episode, "cursor" to cursor),
                    ).toCommentPage()
            }
        },
    )

    val player =
        PlaybackController(
            preferences = preferences,
            onFinished = { scope.launch { handleFinished() } },
            onError = { message -> status = message },
        )

    var bridge by mutableStateOf<Bridge?>(null)
        private set
    var status by mutableStateOf("正在启动…")
    var fatal by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var canRetryStart by mutableStateOf(false)
        private set

    var section by mutableStateOf("首页")
        private set
    var showDetail by mutableStateOf(false)
        private set
    var showComments by mutableStateOf(false)

    // Search
    var query by mutableStateOf("")
    var rows by mutableStateOf<List<Series>>(emptyList())
        private set
    var hasMore by mutableStateOf(false)
        private set
    var page by mutableStateOf(1)
        private set
    var searched by mutableStateOf("")
        private set

    // Discover / rank
    var catalogRows by mutableStateOf<List<Series>>(emptyList())
        private set
    var catalogMore by mutableStateOf(false)
        private set
    var topics by mutableStateOf<List<Topic>>(emptyList())
        private set
    var category by mutableStateOf("COMIC")
        private set
    var board by mutableStateOf("COMIC")
        private set
    var topic by mutableStateOf("")
        private set
    var rankPositions by mutableStateOf<Map<String, RankPosition>>(emptyMap())
        private set

    // Home trial
    var homeCategory by mutableStateOf("ALL")
        private set
    var homeSeries by mutableStateOf<Series?>(null)
        private set
    var homeReady by mutableStateOf(false)
        private set

    // Detail / playback
    var detail by mutableStateOf<Detail?>(null)
        private set
    var episode by mutableStateOf<Int?>(null)
        private set
    var preview by mutableStateOf(false)
        private set
    var actualQuality by mutableStateOf("")
        private set
    var related by mutableStateOf<List<RelatedEntry>>(emptyList())
        private set
    var relatedMessage by mutableStateOf("")
        private set

    // Player mirror for Compose (VLC is polled, never observed from its own thread)
    var playerTime by mutableStateOf(0.0)
        private set
    var playerDuration by mutableStateOf(0.0)
        private set
    var playerPlaying by mutableStateOf(false)
        private set
    var scrubbing by mutableStateOf(false)
    var scrubPosition by mutableStateOf(0.0)

    // Account
    var account by mutableStateOf<AccountInfo?>(null)
        private set
    var accountMessage by mutableStateOf("登录后可查看账号片单和同步进度")
    var accountBusy by mutableStateOf(false)
        private set
    var qr by mutableStateOf<ImageBitmap?>(null)
        private set
    var cloudType by mutableStateOf("账户历史")
        private set
    var cloudRows by mutableStateOf<List<CloudEntry>>(emptyList())
        private set
    var cloudLoading by mutableStateOf(false)
        private set
    var cloudError by mutableStateOf<String?>(null)
        private set
    var cloudLoaded by mutableStateOf(false)
        private set

    private var session: Session? = null
    private var catalogPage = 1
    private var pendingLoginID: String? = null
    private var loginJob: Job? = null
    private var uploadJob: Job? = null
    private var lastUpload = 0L
    private var stopped = false

    // ---- lifecycle ------------------------------------------------------------------------

    fun start() {
        canRetryStart = false
        status = "正在启动…"
        fatal = null
        scope.launch {
            try {
                val value = withContext(Dispatchers.IO) { Bridge.launch() }
                bridge = value
                status = "输入剧名，开始找剧"
                restoreAccount(value)
                navigate("首页")
            } catch (problem: Exception) {
                fatal = problem.message ?: "客户端启动失败，请重新打开应用。"
                canRetryStart = true
            }
        }
    }

    fun shutdown() {
        if (stopped) return
        stopped = true
        saveProgress()
        loginJob?.cancel()
        uploadJob?.cancel()
        comments.clear()
        player.release()
        bridge?.close()
        scope.cancel()
    }

    // ---- bridge helper --------------------------------------------------------------------

    private suspend fun <T> api(block: (Bridge) -> T): T =
        withContext(Dispatchers.IO) {
            val current = bridge ?: throw BridgeException("客户端尚未就绪，请稍候重试。")
            block(current)
        }

    fun runTask(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                status = problem.message ?: "操作失败，请重试。"
            } finally {
                busy = false
            }
        }
    }

    // ---- navigation -----------------------------------------------------------------------

    fun navigate(destination: String) {
        saveProgress()
        player.pause()
        showDetail = false
        showComments = false
        section = destination
        if (destination != "搜索") query = ""
        when (destination) {
            "首页" -> loadHome()
            "找剧" -> {
                category = "COMIC"
                topic = ""
                loadCatalog(reset = true)
            }
            "榜单" -> {
                board = "COMIC"
                topic = ""
                loadRank()
            }
            "账户历史", "账户收藏", "已上线预约", "待上线预约" -> {
                cloudType = destination
                loadCloud()
            }
            else -> Unit
        }
    }

    fun backToLibrary() {
        saveProgress()
        player.pause()
        showDetail = false
        showComments = false
    }

    // ---- search ---------------------------------------------------------------------------

    fun search(more: Boolean = false) {
        val keyword = if (more) searched else query.trim()
        if (keyword.isEmpty() || keyword.length > 80) {
            status = "请输入 1～80 字的剧名"
            return
        }
        runTask {
            val next = if (more) page + 1 else 1
            status = "正在搜索…"
            val result = api { it.get("search", mapOf("q" to keyword, "page" to next.toString())) }
            val items = result.seriesList()
            if (more) {
                val ids = rows.map { it.id }.toSet()
                rows = rows + items.filter { it.id !in ids }
            } else {
                rows = items
            }
            searched = keyword
            page = next
            hasMore = result.optBoolean("hasMore")
            section = "搜索"
            showDetail = false
            status = if (rows.isEmpty()) "没有找到相关剧集" else "已加载 ${rows.size} 部剧"
        }
    }

    // ---- discover / rank ------------------------------------------------------------------

    fun loadCatalog(reset: Boolean = true) {
        if (section == "榜单") {
            loadRank()
            return
        }
        runTask {
            val next = if (reset) 1 else catalogPage + 1
            status = "正在读取剧集…"
            val result =
                api {
                    it.get(
                        "catalog",
                        mapOf(
                            "category" to category,
                            "topic" to topic,
                            "page" to next.toString(),
                        ),
                    )
                }
            val items = result.seriesList()
            if (reset) catalogRows = items
            else {
                val ids = catalogRows.map { it.id }.toSet()
                catalogRows = catalogRows + items.filter { it.id !in ids }
            }
            catalogPage = next
            catalogMore = result.optBoolean("hasMore")
            topics = result.topics()
            status = if (catalogRows.isEmpty()) "暂无内容" else "已加载 ${catalogRows.size} 部剧"
        }
    }

    fun loadRank() {
        runTask {
            status = "正在读取榜单…"
            val result = api { it.get("rank", mapOf("board" to board, "topic" to topic)) }
            catalogRows = result.seriesList()
            catalogMore = false
            topics = result.topics()
            val positions = result.optJSONObject("positions")
            rankPositions =
                buildMap {
                    positions?.keys()?.forEach { id ->
                        val value = positions.optJSONObject(id) ?: return@forEach
                        put(
                            id,
                            RankPosition(
                                rank = if (value.isNull("rank")) null else value.optInt("rank"),
                                heat = value.optString("heat"),
                            ),
                        )
                    }
                }
            status = "已加载 ${catalogRows.size} 部热播剧"
        }
    }

    fun selectCategory(value: String) {
        if (category == value) return
        category = value
        topic = ""
        loadCatalog(reset = true)
    }

    fun selectBoard(value: String) {
        if (board == value) return
        board = value
        topic = ""
        loadRank()
    }

    fun selectTopic(value: String) {
        if (topic == value) return
        topic = value
        if (section == "榜单") loadRank() else loadCatalog(reset = true)
    }

    // ---- home -----------------------------------------------------------------------------

    fun loadHome(refresh: Boolean = true) {
        runTask {
            status = "正在读取推荐作品…"
            val result =
                api {
                    it.get(
                        "feed",
                        mapOf(
                            "category" to homeCategory,
                            "page" to "1",
                            "refresh" to refresh.toString(),
                        ),
                    )
                }
            val items = result.seriesList()
            catalogRows = items
            catalogMore = result.optBoolean("hasMore")
            topics = emptyList()
            homeReady = true
            val first = items.firstOrNull()
            if (first == null) {
                status = "暂时没有推荐作品，请换一批重试"
                return@runTask
            }
            homeSeries = first
            openNow(first, trial = true)
        }
    }

    fun selectHomeCategory(value: String) {
        if (homeCategory == value) return
        homeCategory = value
        loadHome()
    }

    fun moveRecommendation(delta: Int) {
        if (busy) return
        val current = detail ?: return
        val index = catalogRows.indexOfFirst { it.id == current.series.id }
        if (index < 0) {
            status = "当前批次已更新，请换一批"
            return
        }
        val next = catalogRows.getOrNull(index + delta)
        if (next == null) {
            status = "当前批次已到边界，可以换一批"
            return
        }
        runTask { openNow(next, trial = true) }
    }

    // ---- detail / playback ----------------------------------------------------------------

    fun open(series: Series) {
        runTask { openNow(series, trial = false) }
    }

    fun openFromCloud(entry: CloudEntry) {
        if (entry.online == false) {
            status = "该剧待上线，暂时无法播放"
            return
        }
        runTask {
            openNow(
                entry.series,
                trial = false,
                preferredEpisodeID = entry.episodeID,
                startPosition = entry.position,
            )
        }
    }

    private suspend fun openNow(
        series: Series,
        trial: Boolean,
        preferredEpisodeID: String? = null,
        startPosition: Double = 0.0,
    ) {
        comments.clear()
        status = "正在读取剧集…"
        val detailValue = api { it.get("detail", mapOf("id" to series.id)) }.toDetail()
        saveProgress()
        player.pause()
        detail = detailValue
        showDetail = true
        showComments = false
        episode = null
        preview = trial
        session = null
        related = emptyList()
        relatedMessage = "正在读取相关作品…"
        status = "选择集数开始播放 · 共 ${detailValue.episodes.size} 集"
        loadRelated(detailValue.series.id)
        if (detailValue.episodes.isEmpty()) {
            status = "该剧暂时没有可播放的集数"
            return
        }
        val index =
            preferredEpisodeID?.let { detailValue.episodes.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        playNow(index, position = if (index > 0) startPosition else 0.0, trial = trial)
    }

    private fun loadRelated(series: String) {
        scope.launch {
            try {
                val result = api { it.get("related", mapOf("id" to series)) }
                if (detail?.series?.id != series) return@launch
                val entries =
                    result.optJSONArray("items").orEmpty().objects().mapNotNull { value ->
                        val item = value.optJSONObject("series") ?: return@mapNotNull null
                        RelatedEntry(
                            series = Series.from(item),
                            season = value.optString("season"),
                            upcoming = value.optBoolean("upcoming"),
                        )
                    }
                related = entries
                relatedMessage = if (entries.isEmpty()) "暂无相关作品" else ""
            } catch (_: Exception) {
                if (detail?.series?.id == series) relatedMessage = "相关作品暂时无法加载"
            }
        }
    }

    fun play(
        index: Int,
        position: Double = 0.0,
        autoplay: Boolean = true,
        trial: Boolean = false,
    ) {
        runTask { playNow(index, position, autoplay, trial) }
    }

    private suspend fun playNow(
        index: Int,
        position: Double = 0.0,
        autoplay: Boolean = true,
        trial: Boolean = false,
    ) {
        val current = detail ?: return
        if (index !in current.episodes.indices) return
        saveProgress()
        status = "正在准备第 ${index + 1} 集…"
        val result =
            api {
                it.get(
                    "play",
                    mapOf(
                        "id" to current.episodes[index],
                        "quality" to preferences.quality.toString(),
                    ),
                )
            }
        val url = result.optString("url")
        if (!url.startsWith("http://127.0.0.1:")) throw BridgeException("播放地址无效，请重试。")
        player.play(url, position, preferences.speed)
        if (!autoplay) player.pause()
        episode = index
        preview = trial
        actualQuality = result.optString("quality")
        session = Session(current.series, current.episodes[index], index, trial)
        status = "第 ${index + 1} 集 · $actualQuality"
        playerTime = position
        playerDuration = 0.0
    }

    fun togglePlayback() {
        if (episode == null) {
            resume()
            return
        }
        player.toggle()
    }

    fun skipPlayback(seconds: Double) {
        if (episode != null) player.skip(seconds)
    }

    fun seekTo(seconds: Double) {
        player.seek(seconds)
        playerTime = seconds
    }

    fun changeSpeed(value: Float) {
        preferences.updateSpeed(value)
        player.setRate(value)
    }

    fun changeQuality(value: Int) {
        if (value == preferences.quality) return
        preferences.updateQuality(value)
        val index = episode ?: return
        play(index, player.timeSeconds, autoplay = player.isPlaying, trial = preview)
    }

    fun watchFull() {
        preview = false
        session?.trial = false
        saveProgress()
        val duration = player.durationSeconds
        if (duration > 0 && player.timeSeconds >= duration - 0.5) {
            play(episode ?: 0)
        } else if (episode != null) {
            player.ensure()?.mediaPlayer()?.controls()?.play()
            player.setRate(preferences.speed)
        }
        status = "已进入正式观看，可选择集数"
    }

    val resumeLabel: String
        get() {
            val current = detail ?: return "从第 1 集播放"
            val saved = library.recordFor(current.series.id) ?: return "从第 1 集播放"
            val index =
                if (saved.completed && saved.episodeIndex + 1 < current.episodes.size) saved.episodeIndex + 1
                else saved.episodeIndex
            return "继续第 ${index + 1} 集"
        }

    fun resume() {
        val current = detail ?: return
        val saved = library.recordFor(current.series.id)
        if (saved == null) {
            play(0)
            return
        }
        val index = current.episodes.indexOf(saved.episodeID)
        if (index < 0) {
            play(0)
            return
        }
        if (saved.completed && index + 1 < current.episodes.size) play(index + 1)
        else play(index, if (saved.completed) 0.0 else saved.position)
    }

    fun handleFinished() {
        val current = session ?: return
        saveProgress(completed = true)
        if (current.trial) {
            status = "试播结束"
            return
        }
        val detailValue = detail
        val next = current.index + 1
        if (preferences.autoNext && detailValue != null && next < detailValue.episodes.size) {
            play(next)
        } else {
            status = "本集播放结束"
        }
    }

    fun saveProgress(completed: Boolean = false) {
        val current = session ?: return
        if (current.trial) return
        val time = player.timeSeconds
        if (!time.isFinite() || time <= 0) return
        val duration = player.durationSeconds
        val finished = completed || (duration > 0 && time >= duration - 1.0)
        val record =
            WatchRecord(
                series = current.series,
                episodeID = current.episodeID,
                episodeIndex = current.index,
                position = time.coerceAtLeast(0.0),
                duration = if (duration.isFinite() && duration > 0) duration else 0.0,
                completed = finished,
                updatedAt = System.currentTimeMillis(),
            )
        library.record(record)
        uploadProgress(record)
    }

    fun refreshPlayerState() {
        if (!scrubbing) playerTime = player.timeSeconds
        playerDuration = player.durationSeconds
        playerPlaying = player.isPlaying
    }

    // ---- account --------------------------------------------------------------------------

    private suspend fun restoreAccount(activeBridge: Bridge) {
        val saved = runCatching { SecretStore.load() }.getOrNull() ?: return
        try {
            val result =
                withContext(Dispatchers.IO) {
                    activeBridge.post("account/restore", JSONObject().put("credentials", saved))
                }
            val info = AccountInfo.from(result)
            if (info.loggedIn) {
                account = info
                accountMessage = "已恢复登录态，播放进度会定期上传。"
            }
        } catch (_: Exception) {
            accountMessage = "上次账号暂时无法验证，可重新扫码或稍后重开应用。"
        }
    }

    fun beginLogin() {
        if (accountBusy) return
        accountBusy = true
        loginJob =
            scope.launch {
                try {
                    val qrResult = api { it.post("account/qr") }
                    val image = qrResult.optString("image")
                    val loginID = qrResult.optString("loginID")
                    if (image.isEmpty() || loginID.isEmpty()) throw BridgeException("二维码获取失败，请重试。")
                    qr = Images.fromBase64(image)
                    pendingLoginID = loginID
                    accountMessage = "请用红果 App 扫一扫，扫码后在手机确认登录。"
                    val expiresIn = qrResult.optInt("expiresIn", 120).coerceIn(5, 300)
                    val deadline = System.currentTimeMillis() + expiresIn * 1000L
                    while (System.currentTimeMillis() < deadline) {
                        delay(2000)
                        val poll = api { it.get("account/poll", mapOf("loginID" to loginID)) }
                        val info = AccountInfo.from(poll)
                        if (info.loggedIn) {
                            acceptAccount(info)
                            qr = null
                            pendingLoginID = null
                            accountMessage = "登录成功，播放进度会定期上传。"
                            info.credentials?.let { saveSession(it) }
                            return@launch
                        }
                    }
                    qr = null
                    pendingLoginID = null
                    accountMessage = "二维码已过期，请重新获取。"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (problem: Exception) {
                    qr = null
                    pendingLoginID = null
                    accountMessage = problem.message ?: "登录失败，请重试。"
                } finally {
                    accountBusy = false
                }
            }
    }

    private fun saveSession(credentials: String) {
        runCatching { SecretStore.save(credentials) }
            .onFailure { accountMessage = "已登录，但登录态未能保存到 Windows 凭据；下次可能需要重新扫码。" }
    }

    fun cancelLogin() {
        val pending = pendingLoginID
        pendingLoginID = null
        loginJob?.cancel()
        qr = null
        accountBusy = false
        if (pending != null) {
            scope.launch {
                runCatching { api { it.post("account/cancel", JSONObject().put("loginID", pending)) } }
            }
        }
    }

    fun logout() {
        saveProgress()
        cancelLogin()
        account = null
        cloudRows = emptyList()
        cloudLoaded = false
        cloudError = null
        accountMessage = "已退出账号，本机记录保留。"
        scope.launch {
            runCatching { api { it.post("account/logout") } }
            runCatching { SecretStore.delete() }
        }
    }

    private fun acceptAccount(info: AccountInfo) {
        account = info
        cloudRows = emptyList()
        cloudError = null
        cloudLoaded = false
        if (section in ACCOUNT_SECTIONS) loadCloud()
    }

    fun loadCloud() {
        val identity = account?.id
        if (identity == null) {
            cloudRows = emptyList()
            cloudLoading = false
            cloudError = null
            cloudLoaded = false
            return
        }
        val type = cloudType
        cloudLoading = true
        cloudError = null
        cloudLoaded = false
        cloudRows = emptyList()
        scope.launch {
            try {
                val action =
                    when (type) {
                        "账户历史" -> "history"
                        "账户收藏" -> "favorites"
                        else -> "reservations"
                    }
                val result =
                    api {
                        it.get(
                            "account/$action",
                            if (action == "reservations") mapOf("online" to (type != "待上线预约").toString())
                            else emptyMap(),
                        )
                    }
                if (account?.id != identity || cloudType != type) return@launch
                cloudRows = result.toCloudEntries()
                cloudLoaded = true
                status = "已读取 ${cloudRows.size} 条$type"
            } catch (problem: Exception) {
                if (account?.id != identity) return@launch
                cloudError = "$type 读取失败：${problem.message ?: "请重试"}"
            } finally {
                if (account?.id == identity && cloudType == type) cloudLoading = false
            }
        }
    }

    fun cloudFavorite(series: Series, add: Boolean) {
        val identity =
            account?.id
                ?: run {
                    accountMessage = "请先扫码登录"
                    return
                }
        runTask {
            api {
                it.post(
                    "account/favorite",
                    JSONObject().put("id", series.id).put("add", add).put("accountID", identity),
                )
            }
            status = if (add) "已加入账户收藏" else "已取消账户收藏"
            if (section == "账户收藏") loadCloud()
        }
    }

    fun reserve(work: RelatedEntry) {
        if (!work.upcoming) return
        val identity =
            account?.id
                ?: run {
                    status = "请先登录红果账号后预约"
                    return
                }
        runTask {
            val result =
                api {
                    it.post(
                        "account/reserve",
                        JSONObject().put("id", work.series.id).put("accountID", identity),
                    )
                }
            status = if (result.optBoolean("confirmed")) "预约已确认" else "预约请求已提交，请在手机端核对"
        }
    }

    private fun uploadProgress(record: WatchRecord) {
        val identity = account?.id ?: return
        if (uploadJob != null) return
        val now = System.currentTimeMillis()
        if (now - lastUpload < 30_000) return
        lastUpload = now
        val count = detail?.episodes?.size ?: 0
        uploadJob =
            scope.launch {
                try {
                    api {
                        it.post(
                            "account/upload",
                            JSONObject()
                                .put("series", record.series.toJson())
                                .put("episodeID", record.episodeID)
                                .put("episodeIndex", record.episodeIndex)
                                .put("position", (record.position * 1000).toLong())
                                .put("duration", (record.duration * 1000).toLong())
                                .put("episodeCount", count)
                                .put("accountID", identity),
                        )
                    }
                } catch (_: Exception) {
                    // Local progress is authoritative; a failed upload is retried later.
                } finally {
                    uploadJob = null
                }
            }
    }

    companion object {
        val ACCOUNT_SECTIONS = setOf("账户历史", "账户收藏", "已上线预约", "待上线预约")
    }
}

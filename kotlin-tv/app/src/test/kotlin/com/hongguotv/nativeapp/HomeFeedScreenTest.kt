package com.hongguotv.nativeapp

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hongguotv.core.*
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeFeedScreenTest {
    private open class Backend(private val count: Int = 2) : FeedBackend {
        private val prepareCount = java.util.concurrent.atomic.AtomicInteger()
        val prepares
            get() = prepareCount.get()

        val preparedIds = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val displayedIds = java.util.concurrent.ConcurrentLinkedQueue<String>()
        var closed = false
        var recommendationReads = 0

        override fun recommendations(page: Int, cursor: RecommendationCursor): CatalogPage {
            recommendationReads++
            return catalog(DramaCategory.COMIC, page)
        }

        override fun catalog(category: DramaCategory, page: Int) =
            CatalogPage((1..count).map { Series("$it", "作品$it") }, false)

        override fun detail(series: Series) =
            Detail(series, listOf("${series.id}01", "${series.id}02"))

        override fun prepare(detail: Detail): TrialClip {
            prepareCount.incrementAndGet()
            preparedIds += detail.series.id
            return TrialClip(
                detail,
                RemoteVideo(OkHttpClient(), StreamInfo("https://example.com/video", null, "test")),
            )
        }

        override fun close() {
            closed = true
        }

        override fun noteDisplayed(series: Series, cursor: RecommendationCursor, category: DramaCategory?) {
            displayedIds += series.id
        }
    }

    private class Playback(activity: Activity) : TrialPlayback {
        override val view = View(activity)
        override var status: ((TrialStatus) -> Unit)? = null
        val queued = mutableMapOf<String, TrialClip>()
        var selections = 0
        var clears = 0
        var loads = 0
        var loaded = ""
        var saved = 0L
        var playing = false
        var released = false

        override fun load(clip: TrialClip, position: Long, autoplay: Boolean) {
            loads++
            queued.clear()
            queued[clip.detail.series.id] = clip
            loaded = clip.detail.series.id
            saved = position
            playing = autoplay
        }

        override fun queue(clip: TrialClip, index: Int) {
            queued[clip.detail.series.id] = clip
        }

        override fun remove(seriesId: String) {
            queued.remove(seriesId)
        }

        override fun select(clip: TrialClip, position: Long, autoplay: Boolean): Boolean {
            if (clip.detail.series.id !in queued) return false
            selections++
            loaded = clip.detail.series.id
            saved = position
            playing = autoplay
            return true
        }

        override fun position() = saved

        override fun pause() {
            playing = false
        }

        override fun resume() {
            playing = true
        }

        override fun restart() {
            saved = 0
            playing = true
        }

        override fun clear() {
            clears++
            queued.clear()
            loaded = ""
            playing = false
        }

        override fun release() {
            released = true
        }
    }

    private fun get(target: Any, name: String) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun pump(screen: HomeFeedScreen) {
        repeat(4) {
            (get(screen, "job") as? Future<*>)?.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
        }
    }

    private fun finishPrefetch(screen: HomeFeedScreen) {
        repeat(8) {
            val pending = get(screen, "prefetchJob") as? Future<*> ?: return
            pending.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
        }
        fail("首页预缓存任务未完成")
    }

    private fun texts(view: View): List<TextView> =
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else if (view is TextView) listOf(view) else emptyList()

    @Test
    fun transportCleanupRunsOffUiThreadAndOnlyOnce() {
        val finished = java.util.concurrent.CountDownLatch(1)
        val shutdowns = java.util.concurrent.atomic.AtomicInteger()
        var cleanupThread: Thread? = null
        val executor =
            object :
                java.util.concurrent.ThreadPoolExecutor(
                    0,
                    1,
                    1,
                    TimeUnit.SECONDS,
                    java.util.concurrent.LinkedBlockingQueue<Runnable>(),
                ) {
                override fun shutdown() {
                    cleanupThread = Thread.currentThread()
                    shutdowns.incrementAndGet()
                    super.shutdown()
                    finished.countDown()
                }
            }
        val http = OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher(executor)).build()
        val backend = PublicFeedBackend(720, http)
        val ui = Thread.currentThread()
        backend.close()
        backend.close()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertNotSame(ui, cleanupThread)
        assertEquals(1, shutdowns.get())
    }

    @Test
    fun remoteSwitchesWorksAndCompletionDoesNotAdvance() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val state = DramaFeedState()
        val backend = Backend()
        val player = Playback(activity)
        val screen =
            HomeFeedScreen(
                activity,
                state,
                listOf(TextView(activity)),
                {},
                { _, _ -> },
                { "账户收藏" },
                backend,
                player,
            )
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            assertEquals("1", player.loaded)
            assertTrue(player.playing)
            // Next metadata may load, but only the current work prepares a video stream.
            assertEquals(1, backend.prepares)
            val stage = get(screen, "stage") as View
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            pump(screen)
            assertEquals("2", player.loaded)
            player.status?.invoke(TrialStatus.ENDED)
            assertEquals("2", state.current?.id)
            assertTrue(state.ended)
            assertTrue(screen.back())
            assertFalse(screen.back())
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
        assertTrue(player.released)
        assertTrue(backend.closed)
    }

    @Test
    fun prebuffersThreeWorksAndReusesCachedNextAndPrevious() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val state = DramaFeedState()
        val backend = Backend(6)
        val player = Playback(activity)
        val screen =
            HomeFeedScreen(
                activity,
                state,
                listOf(TextView(activity)),
                {},
                { _, _ -> },
                { "账户收藏" },
                backend,
                player,
            )
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            assertEquals(1, backend.prepares)
            player.status?.invoke(TrialStatus.READY)
            finishPrefetch(screen)
            assertEquals(4, backend.prepares)
            assertEquals(setOf("2", "3", "4"), (get(screen, "cachedTrials") as Map<*, *>).keys)
            val clears = player.clears
            val loads = player.loads
            val stage = get(screen, "stage") as View
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            pump(screen)
            assertEquals("2", player.loaded)
            assertEquals(4, backend.prepares)
            assertEquals(clears, player.clears)
            assertEquals(loads, player.loads)
            assertEquals(1, player.selections)
            assertTrue("预加载直切也必须记录当前展示作品", "2" in backend.displayedIds)
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
            pump(screen)
            assertEquals("1", player.loaded)
            assertEquals(4, backend.prepares)
            assertEquals(clears, player.clears)
            assertEquals(loads, player.loads)
            assertEquals(2, player.selections)
            screen.close()
            assertTrue((get(screen, "cachedTrials") as Map<*, *>).isEmpty())
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun switchingToInFlightPreloadDoesNotCancelOrResolveItTwice() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val backend =
            object : Backend(5) {
                override fun preload(series: Series): TrialClip {
                    if (series.id == "2") {
                        entered.countDown()
                        check(finish.await(5, TimeUnit.SECONDS))
                    }
                    return super.preload(series)
                }
            }
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val player = Playback(activity)
        val state = DramaFeedState()
        val screen =
            HomeFeedScreen(
                activity,
                state,
                listOf(TextView(activity)),
                {},
                { _, _ -> },
                { "账户收藏" },
                backend,
                player,
            )
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            player.status?.invoke(TrialStatus.READY)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val pending = get(screen, "prefetchJob") as Future<*>
            (get(screen, "stage") as View).dispatchKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
            )
            assertEquals("2", state.current?.id)
            assertFalse(pending.isCancelled)
            assertEquals(1, backend.prepares)
            finish.countDown()
            finishPrefetch(screen)
            pump(screen)
            assertEquals("2", player.loaded)
            assertEquals(1, backend.preparedIds.count { it == "2" })
            assertTrue(player.playing)
        } finally {
            finish.countDown()
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun promotedPreloadFailureFallsBackToForegroundRequest() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val backend =
            object : Backend(3) {
                override fun preload(series: Series): TrialClip {
                    if (series.id == "2") {
                        entered.countDown()
                        check(finish.await(5, TimeUnit.SECONDS))
                        throw java.io.IOException("预加载失败")
                    }
                    return super.preload(series)
                }
            }
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val player = Playback(activity)
        val screen =
            HomeFeedScreen(
                activity,
                DramaFeedState(),
                listOf(TextView(activity)),
                {},
                { _, _ -> },
                { "账户收藏" },
                backend,
                player,
            )
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            player.status?.invoke(TrialStatus.READY)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            (get(screen, "stage") as View).dispatchKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
            )
            finish.countDown()
            finishPrefetch(screen)
            pump(screen)
            assertEquals("2", player.loaded)
            assertTrue(player.playing)
            assertEquals(1, backend.preparedIds.count { it == "2" })
        } finally {
            finish.countDown()
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun recommendationRefreshRemainsSeparateFromCategoryBrowsing() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val state = DramaFeedState()
        val backend = Backend()
        val player = Playback(activity)
        val screen =
            HomeFeedScreen(
                activity,
                state,
                listOf(TextView(activity)),
                {},
                { _, _ -> },
                { "账户收藏" },
                backend,
                player,
            )
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            assertTrue(state.recommendation)
            assertEquals(1, backend.recommendationReads)
            texts(screen).first { it.text == "真人剧" }.performClick()
            pump(screen)
            assertFalse(state.recommendation)
            assertEquals(1, backend.recommendationReads)
            texts(screen).first { it.text == "推荐" }.performClick()
            pump(screen)
            assertTrue(state.recommendation)
            assertEquals(1, backend.recommendationReads)
            player.saved = 1200
            texts(screen).first { it.text == "换一批" }.performClick()
            pump(screen)
            assertEquals(2, backend.recommendationReads)
            assertEquals(0L, player.saved)
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun openingFullSeriesAndReturningRestoresTrialPosition() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val state = DramaFeedState()
        val backend = Backend()
        val player = Playback(activity)
        var watched = ""
        val screen =
            HomeFeedScreen(
                activity,
                state,
                listOf(TextView(activity)),
                { watched = it.id },
                { _, _ -> },
                { "账户收藏" },
                backend,
                player,
            )
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            player.saved = 12345
            texts(screen).first { it.text == "观看全集" }.performClick()
            assertEquals("1", watched)
            assertEquals(12345L, state.position)
            assertFalse(player.playing)
            screen.resume()
            assertTrue(player.playing)
            screen.close()
            val nextPlayer = Playback(activity)
            val next =
                HomeFeedScreen(
                    activity,
                    state,
                    listOf(TextView(activity)),
                    {},
                    { _, _ -> },
                    { "账户收藏" },
                    Backend(),
                    nextPlayer,
                )
            try {
                activity.setContentView(next)
                next.start(true, false)
                pump(next)
                assertEquals(12345L, nextPlayer.saved)
            } finally {
                next.close()
            }
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun lifecyclePauseAndTypeSwitchStopPreviousTrial() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val state = DramaFeedState()
        val player = Playback(activity)
        val screen =
            HomeFeedScreen(
                activity,
                state,
                listOf(TextView(activity)),
                {},
                { _, _ -> },
                { "账户收藏" },
                Backend(),
                player,
            )
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            player.saved = 456
            screen.pause()
            assertFalse(player.playing)
            assertEquals(456L, state.position)
            screen.resume()
            assertTrue(player.playing)
            val comic = texts(screen).first { it.text == "漫剧" }
            val real = texts(screen).first { it.text == "真人剧" }
            val recommended = texts(screen).first { it.text == "推荐" }
            assertTrue(recommended.isSelected)
            assertFalse(comic.isSelected)
            assertFalse(real.isSelected)
            // resume() starts background work for other series. Only current-player reloads
            // indicate an incorrect repeated category selection, regardless of worker timing.
            val loadsBefore = player.loads
            recommended.performClick()
            pump(screen)
            assertEquals(loadsBefore, player.loads)
            finishPrefetch(screen)
            assertEquals("1", player.loaded)
            assertEquals(456L, player.saved)
            real.performClick()
            pump(screen)
            assertTrue(real.isSelected)
            assertFalse(comic.isSelected)
            assertEquals(DramaCategory.REAL, state.category)
            assertEquals(0L, player.saved)
            comic.performClick()
            pump(screen)
            assertEquals(DramaCategory.COMIC, state.category)
            assertTrue(comic.isSelected)
            assertFalse(real.isSelected)
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
    }
}

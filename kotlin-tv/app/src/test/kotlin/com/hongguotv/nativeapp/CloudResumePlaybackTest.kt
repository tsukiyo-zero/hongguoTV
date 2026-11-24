package com.hongguotv.nativeapp

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hongguotv.core.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CloudResumePlaybackTest {
    private val series = Series("71", "云端续播测试")
    private val cloud = CloudProgress(series, "999", 2, 45000, 90000, 2000)

    private fun get(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun texts(view: View): List<TextView> =
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else if (view is TextView) listOf(view) else emptyList()

    private fun fixture(test: (MainActivity, Library, AccountController) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val library = get(activity, "library") as Library
            val account = get(activity, "cloudAccount") as AccountController
            (get(library, "loader") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            (get(account, "worker") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            set(account, "credentials", AccountCredentials("test-only"))
            set(account, "profile", CloudProfile("a", "测试账号"))
            set(account, "history", listOf(cloud))
            set(account, "busy", true)
            set(activity, "foreground", true)
            // 全部请求由本地样例响应接管，测试不访问外网或真实账号。
            set(
                activity,
                "repository",
                ContentRepository(
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            val request = chain.request()
                            val body =
                                if (request.url.encodedPath == "/detail") {
                                    """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"71","series_name":"云端续播测试","vid_list":["101","102","103"]}}}};</script>"""
                                } else """{"Code":110001}"""
                            Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("fixture")
                                .body(body.toResponseBody())
                                .build()
                        }
                        .build(),
                    playbackSession = testPlaybackDeviceSession(),
                ),
            )
            test(activity, library, account)
        } finally {
            controller.destroy()
        }
    }

    private fun open(activity: MainActivity, resume: Boolean, remote: CloudProgress? = null, resumeLatest: Boolean = false) {
        MainActivity::class
            .java
            .getDeclaredMethod(
                "openDetail",
                Series::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                CloudProgress::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            .apply { isAccessible = true }
            .invoke(activity, series, resume, true, remote, false, resumeLatest)
        (get(activity, "workJob") as? Future<*>)?.get(5, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper()
    }

    private fun assertPlayback(activity: MainActivity, index: Int, position: Long) {
        assertEquals("player", get(activity, "screen"))
        assertEquals(index, get(activity, "episodeIndex"))
        assertEquals(position, get(activity, "startPosition"))
    }

    @Test
    fun searchRestoresDraftAndSelectedResultAfterReturningFromDetails() =
        fixture { activity, _, _ ->
            set(activity, "tab", 1)
            set(activity, "query", "原搜索")
            set(activity, "catalog", listOf(Series("72", "第一项"), series))
            showCatalog(activity)
            val input = get(activity, "searchInput") as android.widget.EditText
            input.setText("还没提交的关键词")
            val grid = (get(activity, "continuousCatalog") as ContinuousCatalogView).grid
            grid.setSelection(1)
            grid.performItemClick(View(activity), 1, grid.adapter.getItemId(1))
            (get(activity, "workJob") as? Future<*>)?.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            assertEquals("detail", get(activity, "screen"))

            activity.onBackPressed()
            layout(activity)
            val restored = (get(activity, "continuousCatalog") as ContinuousCatalogView).grid
            assertEquals("原搜索", get(activity, "query"))
            assertEquals("还没提交的关键词", (get(activity, "searchInput") as android.widget.EditText).text.toString())
            assertEquals(series.id, (restored.selectedItem as Series).id)
            assertTrue(restored.hasFocus())
        }

    @Test
    fun favoritesRestoreTheSelectedCardAfterReturningFromDetails() =
        fixture { activity, library, _ ->
            library.toggle(series)
            library.toggle(Series("72", "另一部收藏"))
            set(activity, "tab", 3)
            showCatalog(activity)
            fun card() = (get(activity, "catalogCards") as Map<*, *>)[series.id] as View
            assertTrue(card().requestFocus())
            assertTrue(card().performClick())
            (get(activity, "workJob") as? Future<*>)?.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            assertEquals("detail", get(activity, "screen"))

            activity.onBackPressed()
            layout(activity)
            assertTrue(card().hasFocus())
        }

    private fun showCatalog(activity: MainActivity) {
        MainActivity::class.java.getDeclaredMethod(
            "showCatalog",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(activity, false, false, false)
        layout(activity)
    }

    private fun layout(activity: MainActivity) {
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 960, 540)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun cloudHistoryUsesItsEpisodeNumberAndPositionEvenWhenIdsDiffer() =
        fixture { activity, library, _ ->
            library.save(WatchProgress(series, "102", 1, 12000, 90000, false, 3000))
            open(activity, true, cloud)
            assertPlayback(activity, 2, 45000)
            assertEquals("102", library.progress(series.id)!!.episodeId)
        }

    @Test
    fun homepageDirectPlayResumesTheNewestRecordWithoutOpeningDetails() =
        fixture { activity, library, _ ->
            library.save(WatchProgress(series, "102", 1, 12000, 90000, false, 1000))
            open(activity, true, resumeLatest = true)
            assertPlayback(activity, 2, 45000)
        }

    @Test
    fun homepageDetailShowsAndPlaysCloudResumeWithoutImportingItIntoLocalHistory() =
        fixture { activity, library, _ ->
            open(activity, false)
            assertEquals("detail", get(activity, "screen"))
            assertNull(library.progress(series.id))
            val root = activity.findViewById<View>(android.R.id.content)
            texts(root).single { it.text == "继续第 3 集" }.performClick()
            assertPlayback(activity, 2, 45000)
            assertNull(library.progress(series.id))
        }

    @Test
    fun homepageKeepsNewerLocalProgressAndLocalHistoryNeverUsesCloudInstead() =
        fixture { activity, library, _ ->
            library.save(WatchProgress(series, "102", 1, 12000, 90000, false, 3000))
            open(activity, false)
            texts(activity.findViewById(android.R.id.content))
                .single { it.text == "继续第 2 集" }
                .performClick()
            assertPlayback(activity, 1, 12000)
        }

    @Test
    fun localHistoryResumesLocalRecordEvenIfCloudIsNewer() = fixture { activity, library, account ->
        library.save(WatchProgress(series, "102", 1, 12000, 90000, false, 1000))
        set(account, "history", listOf(cloud.copy(updatedAt = 4000)))
        open(activity, true)
        assertPlayback(activity, 1, 12000)
    }

    @Test
    fun detailEpisodeButtonUsesResolvedCloudPosition() = fixture { activity, _, _ ->
        open(activity, false)
        texts(activity.findViewById(android.R.id.content))
            .single { it.contentDescription == "第 3 集" }
            .performClick()
        assertPlayback(activity, 2, 45000)
    }

    @Test
    fun switchingAccountsBeforeClickDiscardsOldCloudResume() = fixture { activity, _, account ->
        open(activity, false)
        val button =
            texts(activity.findViewById(android.R.id.content)).single { it.text == "继续第 3 集" }
        set(account, "generation", account.historyScope + 1)
        set(account, "history", emptyList<CloudProgress>())
        button.performClick()
        assertPlayback(activity, 0, 0)
    }
}

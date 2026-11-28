// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.max

/** 弹幕调度：严格零重叠、洪峰丢弃、几何入轨窗口、合并计数与轨道推算。 */
class DanmakuLayoutTest {
    private val screenW = 1920
    private val gap = DanmakuLayout.ENTRY_GAP_MS

    // 合成宽度：按字符数实测，模拟真实 Paint.measureText
    private val widthOf: (String) -> Float = { it.length * 40f }

    private fun assign(
        items: List<DanmakuItem>,
        lanes: Int,
        travelMs: Int,
        merge: Boolean = true,
        widthOf: (String) -> Float = this.widthOf,
    ) = DanmakuLayout.assign(items, lanes, travelMs, screenW, widthOf, merge)

    private fun items(vararg pairs: Pair<String, Long>) =
        pairs.mapIndexed { index, (text, at) -> DanmakuItem("id$index", text, at) }

    /**
     * 绘制公式下的精确重叠判定：D(t) = 右缘A − 左缘B 为线性，
     * 取可见窗口两端点值，与 [0, WA+WB] 相交即重叠。
     */
    private fun overlaps(prev: ScheduledDanmaku, next: ScheduledDanmaku, travelMs: Int, wOf: (String) -> Float): Boolean {
        val windowStart = maxOf(prev.startMs, next.startMs)
        val windowEnd = minOf(prev.startMs + travelMs, next.startMs + travelMs)
        if (windowStart >= windowEnd) return false
        val wA = wOf(prev.text).toDouble()
        val wB = wOf(next.text).toDouble()
        fun d(t: Long): Double =
            wA + (wB - wA) * (t - prev.startMs) / travelMs -
                (screenW + wB) * (next.startMs - prev.startMs) / travelMs
        val lo = minOf(d(windowStart), d(windowEnd))
        val hi = maxOf(d(windowStart), d(windowEnd))
        return hi >= 0.0 && lo <= wA + wB
    }

    private fun assertNoOverlap(scheduled: List<ScheduledDanmaku>, travelMs: Int, wOf: (String) -> Float) {
        for (lane in scheduled.map { it.lane }.distinct()) {
            val inLane = scheduled.filter { it.lane == lane }.sortedBy { it.startMs }
            for (i in inLane.indices) for (j in i + 1 until inLane.size) {
                assertTrue(
                    "同轨重叠：${inLane[i]} 与 ${inLane[j]}",
                    !overlaps(inLane[i], inLane[j], travelMs, wOf),
                )
            }
        }
    }

    @Test
    fun mixedWidthsNeverOverlapEvenUnderLoad() {
        // 宽窄混杂、密集分布：文本附加序号避免落入合并窗口，任何两条同轨弹幕在屏期间横向区间不得相交。
        val rows = (0 until 120).map { DanmakuItem("id$it", "字".repeat(1 + it % 30) + it.toString(), it * 137L) }
        val result = assign(rows, lanes = 4, travelMs = 9000)
        assertEquals(rows.size, result.scheduled.size + result.droppedCount)
        assertNoOverlap(result.scheduled, 9000, widthOf)
        assertTrue(result.scheduled.all { it.startMs >= 0 })
    }

    @Test
    fun burstIsDroppedNotStacked() {
        // 同一刻 60 条、2 条轨道：宁可丢弃也不堆叠，顺延上限按行数自适应（2 行 = 2.4 秒）。
        val rows = (0 until 60).map { DanmakuItem("id$it", "弹幕$it", 1000L) }
        val result = assign(rows, lanes = 2, travelMs = 12000)
        assertEquals(60, result.scheduled.size + result.droppedCount)
        assertTrue(result.droppedCount > 0)
        assertTrue(result.scheduled.all { it.startMs in 1000L..3400L })
        assertNoOverlap(result.scheduled, 12000, widthOf)
    }

    @Test
    fun sameLaneKeepsGeometricEntryGap() {
        val rows = items("AAAA" to 0L, "B" to 100L, "CCCCC" to 200L, "DD" to 300L)
        val result = assign(rows, lanes = 1, travelMs = 9000)
        assertEquals(4, result.scheduled.size + result.droppedCount)
        val ordered = result.scheduled.sortedBy { it.startMs }
        assertTrue(ordered.isNotEmpty())
        for (index in 1 until ordered.size) {
            val w = max(widthOf(ordered[index - 1].text), widthOf(ordered[index].text)).toDouble()
            val settle = ceil(w * 9000 / (screenW + w)).toLong()
            assertTrue(
                "同轨几何间隔不足：${ordered[index - 1]} -> ${ordered[index]}",
                ordered[index].startMs - ordered[index - 1].startMs >= settle + gap,
            )
            assertEquals(0, ordered[index].lane)
        }
    }

    @Test
    fun fasterLongCommentNeverCatchesSlowerShortOne() {
        // 短弹幕在前（慢）、长弹幕在后（快）：旧时间调度必追尾，几何排程须等足安全窗口。
        val rows = items("短" to 0L, "很长很长很长很长的弹幕" to 1000L)
        val result = assign(rows, lanes = 1, travelMs = 12000)
        assertEquals(2, result.scheduled.size)
        assertNoOverlap(result.scheduled, 12000, widthOf)
        val long = result.scheduled.last()
        val w = max(widthOf("短"), widthOf(long.text)).toDouble()
        val settle = ceil(w * 12000 / (screenW + w)).toLong()
        assertTrue("长弹幕入轨早于安全窗口：${long.startMs} < $settle + $gap", long.startMs >= settle + gap)
        assertTrue(long.startMs <= 1000L + DanmakuLayout.adaptiveDelayMs(1))
    }

    @Test
    fun earliestSafeEntryUsesWiderOfTheTwo() {
        // 前短后长 / 前长后短：settle 都由较宽者决定（gap=0 隔离公式本身）。
        val settleWide = ceil(1900.0 * 12000 / (1920 + 1900)).toLong()
        assertEquals(settleWide, DanmakuLayout.earliestSafeEntryMs(0L, 200f, 1900f, 1920, 12000, 0L))
        assertEquals(settleWide, DanmakuLayout.earliestSafeEntryMs(0L, 1900f, 200f, 1920, 12000, 0L))
        val settleSame = ceil(400.0 * 9000 / (1920 + 400)).toLong()
        assertEquals(settleSame, DanmakuLayout.earliestSafeEntryMs(0L, 400f, 400f, 1920, 9000, 0L))
        // 呼吸间隔默认叠加
        assertEquals(
            5000L + settleSame + DanmakuLayout.ENTRY_GAP_MS,
            DanmakuLayout.earliestSafeEntryMs(5000L, 400f, 400f, 1920, 9000),
        )
    }

    @Test
    fun duplicateTextIsMergedWithCountAndMeasuredAfterMerge() {
        val measured = mutableListOf<String>()
        val wOf: (String) -> Float = { measured += it; it.length * 40f }
        val rows = items("哈哈" to 1000L, "哈哈" to 5000L, "好看" to 2000L)
        val result = DanmakuLayout.assign(rows, 3, 9000, screenW, wOf, merge = true)
        assertEquals(2, result.scheduled.size)
        val merged = result.scheduled.first { it.text.startsWith("哈哈") }
        assertEquals(2, merged.count)
        assertEquals("哈哈 ×2", merged.text)
        // 合并保留最早出现时间
        assertEquals(1000L, merged.startMs)
        // 合并文本按合并后的宽度参与几何判定
        assertTrue("合并文本须按合并后宽度实测", "哈哈 ×2" in measured)
    }

    @Test
    fun repeatedTextAtDifferentTimesStaysVisibleAndOverlappingPagesDoNotDoubleCount() {
        val rows = listOf(
            DanmakuItem("a", "哈哈", 0), DanmakuItem("a", "哈哈", 0),
            DanmakuItem("b", "哈哈", 4000), DanmakuItem("c", "哈哈", 9000),
            DanmakuItem("d", "哈哈", 30000),
        )
        val result = assign(rows.reversed(), lanes = 3, travelMs = 9000)
        assertEquals(listOf(2, 1, 1), result.scheduled.map { it.count })
        assertEquals(listOf(0L, 9000L, 30000L), result.scheduled.map { it.startMs })
    }

    @Test
    fun denseCommentsAreDroppedWithinMaximumDelayNotStacked() {
        val rows = (0 until 300).map { DanmakuItem("id$it", "弹幕$it", 1000) }
        val result = assign(rows, lanes = 2, travelMs = 12000)
        assertEquals(300, result.scheduled.size + result.droppedCount)
        assertTrue(result.scheduled.all { it.startMs in 1000L..3400L })
        assertNoOverlap(result.scheduled, 12000, widthOf)
    }

    @Test
    fun mergeCanBeDisabled() {
        val rows = items("哈哈" to 1000L, "哈哈" to 5000L)
        val result = DanmakuLayout.assign(rows, 3, 9000, screenW, widthOf, merge = false)
        assertEquals(2, result.scheduled.size)
        assertTrue(result.scheduled.all { it.count == 1 })
    }

    @Test
    fun laneCountFollowsScreenHeight() {
        // 1080p 屏幕、24dp 字号（行高与渲染一致 = 字号 + 行间距）：
        assertEquals(12, DanmakuLayout.laneCountFor(heightPx = 1000, textSizePx = 72, topPaddingPx = 100))
        assertEquals(1, DanmakuLayout.laneCountFor(heightPx = 0, textSizePx = 72, topPaddingPx = 0))
        assertEquals(14, DanmakuLayout.laneCountFor(heightPx = 10000, textSizePx = 10, topPaddingPx = 0))
        // 上部区域 + 行间距 24px：可用 950px / 行高 72px = 13 行（旧 1.8 倍行高公式只有 11 行）
        assertEquals(13, DanmakuLayout.laneCountFor(heightPx = 950, textSizePx = 48, topPaddingPx = 0, lineGapPx = 24))
    }

    @Test
    fun emptyInputProducesNothing() {
        assertEquals(0, assign(emptyList(), 8, 9000).scheduled.size)
        assertEquals(0, assign(items("A" to 0L), 0, 9000).scheduled.size)
        assertEquals(0, assign(items("A" to 0L), 8, 0).scheduled.size)
    }

    @Test
    fun fixedDensityCannotScheduleLanesOutsideTheVisibleArea() {
        assertEquals(0, DanmakuLayout.visibleLanes(12, 0, 36, 30))
        assertEquals(3, DanmakuLayout.visibleLanes(12, 120, 36, 30))
        assertEquals(2, DanmakuLayout.visibleLanes(2, 120, 36, 30))
    }
}

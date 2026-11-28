// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import kotlin.math.ceil
import kotlin.math.max

/**
 * 弹幕轨道调度：只做时间与轨道分配，不涉及绘制。
 *
 * 历史演变：「轨道占用即丢弃」（一集 259 条丢九成以上）→「最多顺延 3 秒后允许重叠」
 * （洪峰期堆叠不可读）。现在为严格零重叠的几何排程：绘制公式使滚动速度与文本宽度成正比
 * （长弹幕快、短弹幕慢），调度据此对每条弹幕做时空占位判定，同轨道任意两条弹幕在屏期间
 * 横向区间永不相交；塞不下时丢弃（计数可见于弹幕排查诊断），绝不强插。
 */
internal data class DanmakuItem(val id: String, val text: String, val timeMs: Long)

internal data class ScheduledDanmaku(
    val text: String,
    val startMs: Long,
    val lane: Int,
    val endMs: Long,
    val count: Int,
)

/** 排程结果：被接受的弹幕与因严格零重叠被丢弃的数量。 */
internal data class ScheduleResult(
    val scheduled: List<ScheduledDanmaku>,
    val droppedCount: Int,
)

internal object DanmakuLayout {

    /** 相邻两条弹幕入轨时刻之间的额外视觉呼吸间隔。 */
    const val ENTRY_GAP_MS = 80L

    /** 自适应顺延：基准 2 秒，每轨道 +200ms，总上限 4 秒；行数越多越能吸收开场洪峰。 */
    const val DELAY_BASE_MS = 2000L
    const val DELAY_PER_LANE_MS = 200L
    const val DELAY_CAP_MS = 4000L

    /** 按轨道数推算顺延上限；仍无安全轨道则丢弃，绝不与已有弹幕重叠。 */
    fun adaptiveDelayMs(lanes: Int): Long =
        (DELAY_BASE_MS + DELAY_PER_LANE_MS * lanes.coerceAtLeast(0)).coerceAtMost(DELAY_CAP_MS)

    /**
     * 同轨道相邻两条（A 先入轨）零重叠所需的最早入轨时刻。
     *
     * 绘制坐标为文本左缘 x_L(t) = S − (S+W)(t−t₀)/T，滚动速度与文本宽度成正比。
     * 令 D(t) = 右缘A − 左缘B（线性于 t），重叠 ⇔ 存在 t 使 0 ≤ D(t) ≤ WA+WB：
     * 后条不快于前条时需等前条完全进屏；后条更快时须保证追上前前条已完全出屏。
     * 两者统一为「较宽者完全进屏所需时间」已被前条消耗，即
     * tB ≥ tA + max(WA,WB)·T/(S+max(WA,WB))，另加视觉呼吸间隔；
     * settle 向上取整，避免截断造成像素级重叠。
     */
    fun earliestSafeEntryMs(
        prevStartMs: Long,
        prevWidthPx: Float,
        nextWidthPx: Float,
        screenWidthPx: Int,
        travelMs: Int,
        gapMs: Long = ENTRY_GAP_MS,
    ): Long {
        if (screenWidthPx <= 0 || travelMs <= 0) return prevStartMs + gapMs
        val w = max(max(prevWidthPx, nextWidthPx), 1f).toDouble()
        val settleMs = ceil(w * travelMs / (screenWidthPx + w)).toLong()
        return prevStartMs + settleMs + gapMs
    }

    /**
     * @param lanes 可用轨道数（已按屏幕高度算好）
     * @param travelMs 单条弹幕完整穿过屏幕的时间
     * @param screenWidthPx 画布宽度，几何判定的 S
     * @param widthOf 与绘制同一 Paint 的文本宽度实测（合并文本 ×N 也按合并后宽度）
     * @param merge 五秒内相同文本是否合并为「文字 ×N」，保留最早出现时间
     * @param maxDelayMs 顺延上限；小于 0 表示按轨道数自适应（2000+200×lanes，上限 4000）
     */
    fun assign(
        items: List<DanmakuItem>,
        lanes: Int,
        travelMs: Int,
        screenWidthPx: Int,
        widthOf: (String) -> Float,
        merge: Boolean = true,
        maxDelayMs: Long = -1L,
        entryGapMs: Long = ENTRY_GAP_MS,
    ): ScheduleResult {
        val delayCap = if (maxDelayMs >= 0) maxDelayMs else adaptiveDelayMs(lanes)
        if (items.isEmpty() || lanes <= 0 || travelMs <= 0 || screenWidthPx <= 0) {
            return ScheduleResult(emptyList(), 0)
        }
        // 先按真实 id 去重，避免重叠时间段重复计数；相同文字只在五秒内合并。
        val unique = items.distinctBy { it.id.ifBlank { "${it.timeMs}:${it.text}" } }.sortedBy { it.timeMs }
        val prepared = mutableListOf<Triple<String, Long, Int>>()
        val latest = mutableMapOf<String, Int>()
        for (item in unique) {
            val text = item.text.trim()
            if (text.isEmpty()) continue
            val index = if (merge) latest[text] else null
            val previous = index?.let { prepared[it] }
            if (previous != null && item.timeMs - previous.second <= 5000L) {
                prepared[index!!] = previous.copy(third = previous.third + 1)
            } else {
                latest[text] = prepared.size
                prepared += Triple(text, item.timeMs, 1)
            }
        }

        // 每轨道只记末条 (入轨时刻, 宽度)：后条要与更早条重叠必先穿过末条，检查末条即可保证零重叠。
        val laneLastStart = LongArray(lanes) { Long.MIN_VALUE }
        val laneLastWidth = FloatArray(lanes)
        val output = ArrayList<ScheduledDanmaku>(prepared.size)
        var dropped = 0
        for ((text, at, count) in prepared) {
            val shown = if (count > 1) "$text ×$count" else text
            val widthPx = widthOf(shown).coerceAtLeast(1f)
            var bestLane = -1
            var bestStart = Long.MAX_VALUE
            for (lane in 0 until lanes) {
                val start = if (laneLastStart[lane] == Long.MIN_VALUE) {
                    at
                } else {
                    maxOf(at, earliestSafeEntryMs(laneLastStart[lane], laneLastWidth[lane], widthPx, screenWidthPx, travelMs, entryGapMs))
                }
                if (start < bestStart) {
                    bestStart = start
                    bestLane = lane
                }
            }
            // 严格零重叠：超过顺延上限仍无安全轨道就丢弃，绝不叠进已占用轨道。
            if (bestStart > at + delayCap) {
                dropped++
                continue
            }
            laneLastStart[bestLane] = bestStart
            laneLastWidth[bestLane] = widthPx
            output +=
                ScheduledDanmaku(
                    text = shown,
                    startMs = bestStart,
                    lane = bestLane,
                    endMs = bestStart + travelMs,
                    count = count,
                )
        }
        return ScheduleResult(output, dropped)
    }

    fun visibleLanes(requested: Int, areaHeight: Int, lineHeight: Int, glyphHeight: Int): Int {
        if (lineHeight <= 0 || areaHeight < glyphHeight) return 0
        return minOf(requested, 1 + (areaHeight - glyphHeight) / lineHeight)
    }

    /** 按屏幕高度推算可用轨道数；行高与渲染一致（字号 + 行间距），避免容量被高估行高压低。 */
    fun laneCountFor(heightPx: Int, textSizePx: Int, topPaddingPx: Int, lineGapPx: Int = 0, maxLanes: Int = 14): Int {
        if (heightPx <= 0 || textSizePx <= 0) return 1
        val usable = heightPx - topPaddingPx
        val lineHeight = (textSizePx + lineGapPx).coerceAtLeast(1)
        return (usable / lineHeight).coerceIn(1, maxLanes)
    }
}

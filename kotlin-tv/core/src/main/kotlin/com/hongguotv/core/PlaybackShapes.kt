// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 播放模型的请求形态。
 *
 * 平台在这三条路径之间反复切换（2026-11-20 早：v1 可用；11-20 晚：仅 GET；11-22：player 可用）。
 * 与其每次改常量发版，不如把三种形态都实现，运行时轮换并记住可用的一条。
 */
enum class PlaybackShape(val label: String) {
    GET_VIDEO_TAB("get-video-tab"),
    POST_PLAYER("post-player"),
    POST_V1("post-v1");

    companion object {
        fun parse(value: String?): PlaybackShape? = entries.firstOrNull { it.label == value }
    }
}

/**
 * 记住最近一次成功的形态：进程内生效，[onChange] 由 app 层接到偏好存储上。
 *
 * 顺序规则：优先上次成功的形态，其余按枚举顺序兜底——平台切换后第一条失败会很快落到下一条。
 */
object PlaybackShapeMemory {
    @Volatile private var preferred: PlaybackShape? = null

    /** 形态变化回调；app 层用它把结果写进 SharedPreferences。 */
    @Volatile var onChange: ((PlaybackShape) -> Unit)? = null

    fun current(): PlaybackShape? = preferred

    fun restore(shape: PlaybackShape?) {
        preferred = shape
    }

    fun order(): List<PlaybackShape> =
        buildList {
            preferred?.let { add(it) }
            PlaybackShape.entries.filterTo(this) { it != preferred }
        }

    fun success(shape: PlaybackShape) {
        if (preferred == shape) return
        preferred = shape
        onChange?.invoke(shape)
    }
}

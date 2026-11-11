// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

object PrefetchPolicy {
    const val WARM_BYTES = 2 * 1024 * 1024

    /** 按剩余实际播放时间提前准备，控制在模型和预加载资源的有效期内。 */
    fun nearEnd(position: Long, duration: Long, speed: Float): Boolean {
        if (
            duration <= 0 || position < 0 || position >= duration || !speed.isFinite() || speed <= 0
        )
            return false
        return (duration - position) / speed <= 45_000f
    }

    /** 当前播放稳定且缓冲充足时才开始，避免争抢正在播放的视频带宽。 */
    fun canStart(
        position: Long,
        duration: Long,
        speed: Float,
        stableFor: Long,
        bufferedAhead: Long,
    ): Boolean =
        nearEnd(position, duration, speed) &&
            stableFor >= 1500 &&
            bufferedAhead >= minOf(5000L, duration - position)
}

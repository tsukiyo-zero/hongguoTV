// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 伴机兜底选档（app 的 relayOpen 与 relay 的 --selftest 共用）。
 *
 * 2026-11-28 供给形态调查：新剧出现「单档 1080P 竖屏 bytevc1」（标准 hvc1+hvcC），
 * 无 bytevc2 档——硬解超竖屏 1088 上限全拒后，伴机旧选档（仅认 bytevc2）接不住。
 * 两级策略：①bytevc2 最高档（老形态剧集行为不变）；②ladder 最高档（标准容器，
 * 伴机走整文件缓存模式转 H.264）。能进入伴机兜底说明正常播放路径已失败，
 * 因此不做解码能力过滤。
 */
object RelaySourcePicker {
    private val VVC_LABELS = setOf("bytevc2", "bvc2")

    fun pick(raw: List<Rendition>, ladder: List<Rendition>): Rendition? =
        raw.filter { it.codec.lowercase() in VVC_LABELS && it.url.isNotBlank() }
            .maxByOrNull { it.quality }
            ?: ladder
                .filter { it.codec.lowercase() !in VVC_LABELS && it.url.isNotBlank() }
                .maxByOrNull { it.quality }

    /**
     * 伴机 /health caps 判定。caps 为 null（字段缺失=老伴机）视为仅支持 bytevc2；
     * 明确的空列表表示伴机 ffmpeg 能力探测失败，不支持任何编码。
     */
    fun capsAllow(caps: List<String>?, codec: String): Boolean =
        codec.lowercase() in (caps ?: listOf("bytevc2")).map(String::lowercase)
}

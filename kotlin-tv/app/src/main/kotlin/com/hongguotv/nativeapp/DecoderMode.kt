// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.SharedPreferences
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * 解码方式偏好。
 *
 * 默认「自动」保持 Media3 的原有行为：优先硬件解码器，初始化失败时通过
 * `setEnableDecoderFallback(true)` 退到软件解码器。部分电视（如部分投影）退到软解后
 * 不会回切，1080P HEVC 会持续掉帧，因此提供强制锁定入口。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
enum class DecoderMode(val label: String) {
    AUTO("自动"),
    HARDWARE("强制硬解"),
    SOFTWARE("强制软解");

    fun next(): DecoderMode = entries[(ordinal + 1) % entries.size]

    companion object {
        private const val KEY = "decoder-mode"

        fun read(prefs: SharedPreferences): DecoderMode =
            runCatching { valueOf(prefs.getString(KEY, AUTO.name)!!) }.getOrDefault(AUTO)

        fun write(prefs: SharedPreferences, mode: DecoderMode) {
            prefs.edit().putString(KEY, mode.name).apply()
        }

        /**
         * 按模式过滤候选解码器。
         *
         * 强制模式下若过滤结果为空（设备根本没有对应类型的解码器），返回原列表，
         * 让播放器按默认逻辑处理，避免直接黑屏。
         */
        fun filter(mode: DecoderMode, infos: List<MediaCodecInfo>): List<MediaCodecInfo> {
            val wanted =
                when (mode) {
                    AUTO -> return infos
                    HARDWARE -> infos.filter { !it.softwareOnly }
                    SOFTWARE -> infos.filter { it.softwareOnly }
                }
            return wanted.ifEmpty { infos }
        }

        /** Media3 的解码器命名约定：`c2.android.*` / `OMX.google.*` 属于软件实现。 */
        fun isSoftware(decoderName: String): Boolean =
            decoderName.startsWith("c2.android.") || decoderName.startsWith("OMX.google.")

        fun codecLabel(decoderName: String): String =
            when {
                decoderName.contains("hevc", true) || decoderName.contains("h265", true) -> "HEVC"
                decoderName.contains("avc", true) || decoderName.contains("h264", true) -> "H.264"
                else -> "未知编码"
            }

        /**
         * 播放设置里的只读状态行：当前实际解码器 + 硬/软解判定 + 是否与所选模式一致。
         * [decoderName] 为空表示还没起播。
         */
        fun describe(decoderName: String?, mode: DecoderMode): String {
            if (decoderName.isNullOrBlank()) return "解码 ${mode.label} · 起播后显示实际解码器"
            val software = isSoftware(decoderName)
            val kind = if (software) "软解" else "硬解"
            val mismatch =
                when (mode) {
                    AUTO -> false
                    HARDWARE -> software
                    SOFTWARE -> !software
                }
            val suffix = if (mismatch) "（与所选模式不一致）" else ""
            return "解码 ${codecLabel(decoderName)} · $kind · $decoderName$suffix"
        }

        fun selector(mode: DecoderMode): MediaCodecSelector =
            if (mode == AUTO) MediaCodecSelector.DEFAULT
            else
                MediaCodecSelector { mimeType, secure, tunneling ->
                    filter(mode, MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling))
                }
    }
}

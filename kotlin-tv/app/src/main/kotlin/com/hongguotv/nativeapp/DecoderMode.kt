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

        fun selector(mode: DecoderMode): MediaCodecSelector =
            if (mode == AUTO) MediaCodecSelector.DEFAULT
            else
                MediaCodecSelector { mimeType, secure, tunneling ->
                    filter(mode, MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling))
                }
    }
}

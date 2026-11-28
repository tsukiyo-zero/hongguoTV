// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

internal data class GokeHevcQuirk(private val longSide: Int, private val shortSide: Int) {
    fun supports(width: Int, height: Int): Boolean =
        maxOf(width, height) in 1..longSide && minOf(width, height) in 1..shortSide

    companion object {
        const val HEVC_DECODER = "OMX.goke.video.decoder.hevc"
        const val AVC_DECODER = "OMX.goke.video.decoder.avc"

        fun fromReportedSizes(
            sdk: Int,
            hevcWidth: Int,
            hevcHeight: Int,
            avcWidth: Int,
            avcHeight: Int,
        ): GokeHevcQuirk? {
            // 已验证的 Android 9 固件将缺少 profile/level 的国科 HEVC 上限报为 288×288。
            if (sdk != 28 || hevcWidth != 288 || hevcHeight != 288) return null
            val longSide = minOf(maxOf(avcWidth, avcHeight), 1920)
            val shortSide = minOf(minOf(avcWidth, avcHeight), 1088)
            if (longSide < 1280 || shortSide < 720) return null
            return GokeHevcQuirk(longSide, shortSide)
        }
    }
}

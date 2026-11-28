// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

@androidx.annotation.OptIn(UnstableApi::class)
internal fun playbackRenderersFactory(context: Context, mode: DecoderMode): DefaultRenderersFactory {
    val quirk = if (mode == DecoderMode.SOFTWARE) null else CodecCapabilities.gokeHevcQuirk
    val factory = if (quirk == null) DefaultRenderersFactory(context) else GokeRenderersFactory(context, quirk)
    return factory.setEnableDecoderFallback(true).setMediaCodecSelector(DecoderMode.selector(mode))
}

@androidx.annotation.OptIn(UnstableApi::class)
private class GokeRenderersFactory(context: Context, private val quirk: GokeHevcQuirk) :
    DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        if (extensionRendererMode != EXTENSION_RENDERER_MODE_OFF) {
            super.buildVideoRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                allowedVideoJoiningTimeMs,
                out,
            )
            return
        }

        val builder =
            MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(50)
        out.add(GokeVideoRenderer(builder, quirk))
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class GokeVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val quirk: GokeHevcQuirk,
) : MediaCodecVideoRenderer(builder) {
    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val infos = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        if (requiresSecureDecoder || format.sampleMimeType != MimeTypes.VIDEO_H265 ||
            !quirk.supports(format.width, format.height) || infos.firstOrNull()?.softwareOnly != true
        ) return infos

        // 在 Media3 按 profile/level 排序之后，只前移确认误报的国科组件，其余候选保留原顺序。
        val index = infos.indexOfFirst { !it.softwareOnly && it.name == GokeHevcQuirk.HEVC_DECODER }
        if (index < 1) return infos
        return listOf(infos[index]) + infos.take(index) + infos.drop(index + 1)
    }
}

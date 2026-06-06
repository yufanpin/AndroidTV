package com.tivimatelite.player

import android.content.Context
import android.os.Build
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

@UnstableApi
class AmlogicMediaCodecVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    maxDroppedFramesToNotify: Int,
) : MediaCodecVideoRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    allowedJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify,
) {
    override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean {
        return AmlogicSurfaceWorkaround.shouldForceSetOutputSurfaceWorkaround(
            sdkInt = Build.VERSION.SDK_INT,
            codecName = name,
        ) || super.codecNeedsSetOutputSurfaceWorkaround(name)
    }
}

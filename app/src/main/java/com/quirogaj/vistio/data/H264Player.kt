package com.quirogaj.vistio.data

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaCodecInfo
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

internal object H264Player {
    fun play(input: InputStream, surface: Surface, active: () -> Boolean, onFrame: (AvcSize) -> Unit) {
        val reader = AnnexBReader(input)
        val info = MediaCodec.BufferInfo()
        var decoder: MediaCodec? = null
        var currentSps: ByteArray? = null
        var currentPps: ByteArray? = null
        var size: AvcSize? = null
        var lastOutput = SystemClock.elapsedRealtime()
        var lastPts = 0L
        var announcedSize: AvcSize? = null
        try {
            while (active() && surface.isValid) {
                val frame = reader.readAccessUnit()
                if (!active() || !surface.isValid) break
                if (SystemClock.elapsedRealtime() - lastOutput > 8000) {
                    throw IOException("H.264 no produjo video")
                }
                val sps = frame.sps ?: continue
                val pps = frame.pps ?: continue
                if (decoder == null || !sps.contentEquals(currentSps) || !pps.contentEquals(currentPps)) {
                    if (!frame.keyFrame) continue
                    decoder?.release()
                    decoder = null
                    size = readAvcSize(sps)
                    decoder = createDecoder(surface, size, sps, pps)
                    currentSps = sps
                    currentPps = pps
                }

                var submitted = false
                while (active() && surface.isValid && !submitted) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: throw IOException("Decodificador H.264 sin búfer")
                        buffer.clear()
                        if (buffer.remaining() < frame.bytes.size) throw IOException("Fotograma H.264 demasiado grande")
                        buffer.put(frame.bytes)
                        lastPts = maxOf(lastPts + 1, System.nanoTime() / 1000)
                        decoder.queueInputBuffer(index, 0, frame.bytes.size, lastPts,
                            if (frame.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        submitted = true
                    }
                    var latest = -1
                    while (true) {
                        val output = decoder.dequeueOutputBuffer(info, 0)
                        if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                        if (output < 0) break
                        if (latest >= 0) decoder.releaseOutputBuffer(latest, false)
                        latest = output
                    }
                    if (latest >= 0) {
                        decoder.releaseOutputBuffer(latest, true)
                        lastOutput = SystemClock.elapsedRealtime()
                        if (announcedSize != size && size != null) {
                            announcedSize = size
                            onFrame(size)
                        }
                    }
                    if (SystemClock.elapsedRealtime() - lastOutput > 8000) {
                        throw IOException("H.264 no produjo video")
                    }
                }
            }
        } finally {
            decoder?.release()
        }
    }

    private fun createDecoder(
        surface: Surface,
        size: AvcSize,
        sps: ByteArray,
        pps: ByteArray,
        allowLowLatency: Boolean = true,
    ): MediaCodec {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, size.width, size.height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(AnnexBReader.startCode + sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(AnnexBReader.startCode + pps))
        }
        val codec = MediaCodec.createDecoderByType(mime)
        var lowLatency = false
        try {
            if (allowLowLatency && Build.VERSION.SDK_INT >= 30) {
                lowLatency = codec.codecInfo.getCapabilitiesForType(mime)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                if (lowLatency) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            return codec
        } catch (error: Exception) {
            codec.release()
            if (lowLatency && error is MediaCodec.CodecException && surface.isValid) {
                return createDecoder(surface, size, sps, pps, allowLowLatency = false)
            }
            throw error
        }
    }
}

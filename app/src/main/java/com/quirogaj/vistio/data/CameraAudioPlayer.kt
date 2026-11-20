package com.quirogaj.vistio.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Plays live mono PCM from the supported camera audio streams. */
internal object CameraAudioPlayer {
    private const val SAMPLE_RATE = 44_100
    private const val FRAME_BYTES = 2

    fun play(source: InputStream, active: () -> Boolean, onPlaying: () -> Unit) {
        val input = DataInputStream(source.buffered())
        checkWavHeader(input)
        playPcm(SAMPLE_RATE, SAMPLE_RATE * FRAME_BYTES / 50, active, onPlaying, input::readFully)
    }

    fun playFalcon(source: InputStream, active: () -> Boolean, onPlaying: () -> Unit) {
        val reader = FalconAudio.Reader(DataInputStream(source.buffered()))
        val filter = FalconAudioFilter()
        playPcm(FalconAudio.SAMPLE_RATE, FalconAudio.PCM_BYTES, active, onPlaying) { pcm ->
            reader.read(pcm)
            filter.process(pcm)
        }
    }

    private fun playPcm(
        sampleRate: Int,
        chunkSize: Int,
        active: () -> Boolean,
        onPlaying: () -> Unit,
        readChunk: (ByteArray) -> Unit,
    ) {
        if (!active()) return
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val channel = AudioFormat.CHANNEL_OUT_MONO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channel, encoding)
        if (minimum <= 0) throw IOException("El dispositivo no puede reproducir el audio de la cámara.")
        val chunk = ByteArray(chunkSize)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channel)
                .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, chunk.size * 2))
            .build()
        try {
            if (!active()) return
            track.play()
            var started = false
            while (active()) {
                readChunk(chunk)
                var offset = 0
                while (offset < chunk.size && active()) {
                    val written = track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) throw IOException("El dispositivo interrumpió la reproducción de audio.")
                    offset += written
                }
                if (!started && active()) {
                    started = true
                    onPlaying()
                }
            }
        } finally {
            // Releasing the streaming track also discards any queued samples.
            track.release()
        }
    }

    private fun checkWavHeader(input: DataInputStream) {
        val header = ByteArray(44).also(input::readFully)
        val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        fun matches(offset: Int, tag: String) = tag.toByteArray(Charsets.US_ASCII)
            .indices.all { header[offset + it] == tag[it].code.toByte() }

        if (!matches(0, "RIFF") || !matches(8, "WAVE") || !matches(12, "fmt ") ||
            fields.getInt(16) != 16 || fields.getShort(20).toInt() != 1 ||
            fields.getShort(22).toInt() != 1 || fields.getInt(24) != SAMPLE_RATE ||
            fields.getShort(32).toInt() != FRAME_BYTES || fields.getShort(34).toInt() != 16 ||
            !matches(36, "data")
        ) throw IOException("Formato WAV no compatible")
    }
}

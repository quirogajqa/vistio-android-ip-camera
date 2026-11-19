package com.quirogaj.vistio.data

import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object FalconAudio {
    const val SAMPLE_RATE = 8_000
    const val PCM_BYTES = 2_048
    private const val PACKET_BYTES = 512
    private const val MAGIC = 0xa815aa55.toInt()
    private val steps = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
        34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
        157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544,
        598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707,
        1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
        5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635,
        13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    )
    private val indexDeltas = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)

    class Reader(private val input: DataInputStream) {
        private val header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        private val payload = ByteArray(PACKET_BYTES)

        fun read(pcm: ByteArray) {
            input.readFully(header.array())
            if (header.getInt(0) != MAGIC || header.get(4).toInt() != 6 ||
                header.get(5).toInt() != 1 || header.getInt(16) != PACKET_BYTES
            ) throw IOException("La cámara no devolvió audio ADPCM compatible con Falcon.")
            input.readFully(payload)
            decode(payload, pcm)
        }
    }

    private fun decode(adpcm: ByteArray, pcm: ByteArray) {
        // Each Falcon packet starts with a fresh IMA ADPCM state and sends the high nibble first.
        var sample = 0
        var index = 0
        for (offset in 0 until adpcm.size * 2) {
            val byte = adpcm[offset / 2].toInt()
            val code = (byte ushr (if (offset % 2 == 0) 4 else 0)) and 15
            val step = steps[index]
            var difference = step shr 3
            if (code and 1 != 0) difference += step shr 2
            if (code and 2 != 0) difference += step shr 1
            if (code and 4 != 0) difference += step
            sample = (sample + if (code and 8 == 0) difference else -difference)
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            index = (index + indexDeltas[code and 7]).coerceIn(steps.indices)
            pcm[offset * 2] = sample.toByte()
            pcm[offset * 2 + 1] = (sample shr 8).toByte()
        }
    }
}

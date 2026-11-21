package com.quirogaj.vistio.data

import java.io.DataInputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Sends Falcon VCS001 talkback frames over the binary live stream opened by the HTTP GET. */
internal object FalconTalk {
    private const val AUDIO_BYTES = 256
    private const val HEADER_BYTES = 32
    private val frameIntervalNanos = TimeUnit.MILLISECONDS.toNanos(64)

    fun send(socket: Socket, host: String, credentials: String, clip: ByteArray) {
        require(clip.isNotEmpty() && clip.size % AUDIO_BYTES == 0)
        socket.tcpNoDelay = true
        val output = socket.getOutputStream()
        val request = "GET /livestream.cgi?$credentials&streamid=10&audio=1&filename= HTTP/1.1\r\n" +
            "Host: $host:${socket.port}\r\nConnection: Keep-Alive\r\n\r\n"
        output.write(request.toByteArray(Charsets.US_ASCII))

        val input = DataInputStream(socket.getInputStream().buffered())
        val prefix = ByteArray(12)
        input.readFully(prefix)
        val response = prefix.toString(Charsets.US_ASCII)
        if (response.startsWith("HTTP/")) {
            val status = response.substring(9).toIntOrNull()
            if (status != 200) throw IOException("La Falcon rechazó el inicio del video.")
        }

        // Keep reading the live stream while writing talkback frames on the same connection.
        thread(name = "FalconTalkVideoDrain", isDaemon = true) {
            val buffer = ByteArray(16 * 1024)
            try {
                while (input.read(buffer) != -1) {
                    // The viewer uses its own connection, so these frames can be discarded.
                }
            } catch (_: IOException) {
                // Closing or cancelling the request also unblocks this thread.
            } finally {
                runCatching { socket.close() }
            }
        }

        val frame = ByteBuffer.allocate(HEADER_BYTES + AUDIO_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        frame.putInt(4, 8)
        frame.putInt(16, AUDIO_BYTES)
        val started = System.nanoTime()
        for (offset in clip.indices step AUDIO_BYTES) {
            val index = offset / AUDIO_BYTES
            frame.putInt(8, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()).toInt())
            frame.putInt(12, index)
            clip.copyInto(frame.array(), HEADER_BYTES, offset, offset + AUDIO_BYTES)
            output.write(frame.array())
            val remaining = started + (index + 1) * frameIntervalNanos - System.nanoTime()
            if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
        }
        // Give the camera time to play the last buffered frames before closing the socket.
        Thread.sleep(400)
    }
}

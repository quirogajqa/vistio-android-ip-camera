package com.quirogaj.vistio.data

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Local PPPP voice transport used by the Falcon VCS001. */
internal class FalconTalk(private val socket: DatagramSocket, private val endpoint: CameraEndpoint) {
    private val receiveBuffer = ByteArray(2048)
    private var commandReply = false

    fun send(clip: ByteArray) {
        require(clip.isNotEmpty() && clip.size % AUDIO_BYTES == 0)
        val camera = InetAddress.getByName(endpoint.origin.host) as? Inet4Address
            ?: throw IOException("El aviso de la Falcon requiere una dirección IPv4 local.")
        val lan = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.interfaceAddresses }
            .firstOrNull { address ->
                address.broadcast != null && address.address is Inet4Address &&
                    sameSubnet(address.address, camera, address.networkPrefixLength.toInt())
            } ?: throw IOException("Conecta el dispositivo a la misma red local que la Falcon.")

        socket.bind(InetSocketAddress(lan.address, 0))
        socket.broadcast = true
        openSession(camera, lan.broadcast)
        try {
            write(packet(0xe0))
            if (!sendCommand(0, "get_params.cgi")) {
                throw IOException("La Falcon no confirmó la consulta de inicio.")
            }
            if (!commandReply && !waitFor(2_000) { reply ->
                respond(reply)
                commandReply
            }) throw IOException("La Falcon no devolvió una respuesta a la consulta de inicio.")

            if (!sendCommand(1, "audiostream.cgi?streamid=16")) {
                throw IOException("La Falcon no confirmó la preparación del altavoz.")
            }

            val audioHeader = ByteArray(32).apply {
                this[4] = 8
                this[5] = 1
                this[17] = 1 // ADPCM payload length, little endian: 256 bytes.
            }
            for (offset in clip.indices step AUDIO_BYTES) {
                val started = System.nanoTime()
                val index = offset / AUDIO_BYTES * 2
                if (!deliver(3, index, audioHeader) ||
                    !deliver(3, index + 1, clip.copyOfRange(offset, offset + AUDIO_BYTES))
                ) throw IOException("La Falcon dejó de confirmar el audio.")
                val remaining = TimeUnit.MILLISECONDS.toNanos(64) - (System.nanoTime() - started)
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
                if (index % 40 == 0) write(packet(0xe0))
            }
        } finally {
            if (!socket.isClosed) runCatching { write(packet(0xf0)) }
        }
    }

    private fun openSession(camera: InetAddress, broadcast: InetAddress) {
        repeat(3) {
            sendTo(packet(0x30), InetSocketAddress(broadcast, 32108))
            val found = waitFor(750) { reply ->
                val bytes = reply.data
                if (reply.address != camera || reply.length != 24 || !matches(reply, 0x41)) {
                    false
                } else {
                    val identity = bytes.copyOfRange(4, 24)
                    val peer = InetSocketAddress(reply.address, reply.port)
                    sendTo(packet(0x41, identity), peer)
                    val ready = waitFor(500) { response ->
                        response.address == peer.address && response.port == peer.port &&
                            response.length == 24 && matches(response, 0x42) &&
                            response.data.copyOfRange(4, 24).contentEquals(identity)
                    }
                    if (ready) socket.connect(peer)
                    ready
                }
            }
            if (found) return
        }
        throw IOException("No se pudo abrir la sesión P2P local con la Falcon.")
    }

    private fun sendCommand(index: Int, path: String): Boolean {
        val user = URLEncoder.encode(endpoint.user, "UTF-8")
        val pass = URLEncoder.encode(endpoint.password, "UTF-8")
        val separator = if ('?' in path) '&' else '?'
        val command = "GET /$path${separator}loginuse=$user&loginpas=$pass&user=$user&pwd=$pass&"
        val data = command.toByteArray(Charsets.US_ASCII)
        val body = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x0a01).putInt(data.size).put(data).array()
        return deliver(0, index, body)
    }

    private fun deliver(channel: Int, index: Int, data: ByteArray): Boolean {
        val content = ByteBuffer.allocate(4 + data.size)
            .put(0xd1.toByte()).put(channel.toByte()).putShort(index.toShort()).put(data).array()
        val frame = packet(0xd0, content)
        repeat(5) {
            write(frame)
            if (waitFor(90) { reply ->
                respond(reply)
                val bytes = reply.data
                matches(reply, 0xd1) && reply.length >= 10 &&
                    bytes[4] == 0xd1.toByte() && bytes[5].toInt() == channel &&
                    (8 until reply.length - 1 step 2).any {
                        ((bytes[it].toInt() and 255) shl 8 or (bytes[it + 1].toInt() and 255)) == index
                    }
            }) return true
        }
        return false
    }

    private fun respond(reply: DatagramPacket) {
        val bytes = reply.data
        when {
            matches(reply, 0xe0) -> write(packet(0xe1))
            matches(reply, 0xd0) && reply.length >= 8 && bytes[4] == 0xd1.toByte() -> {
                write(packet(0xd1, byteArrayOf(0xd1.toByte(), bytes[5], 0, 1, bytes[6], bytes[7])))
                if (bytes[5].toInt() == 0 &&
                    String(bytes, 8, reply.length - 8, Charsets.US_ASCII).contains("var ")
                ) commandReply = true
            }
        }
    }

    private fun waitFor(timeout: Long, accepts: (DatagramPacket) -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        while (true) {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) return false
            socket.soTimeout = remaining.toInt()
            val reply = DatagramPacket(receiveBuffer, receiveBuffer.size)
            try {
                socket.receive(reply)
            } catch (_: SocketTimeoutException) {
                return false
            }
            if (accepts(reply)) return true
        }
    }

    private fun write(data: ByteArray) = socket.send(DatagramPacket(data, data.size))

    private fun sendTo(data: ByteArray, target: InetSocketAddress) =
        socket.send(DatagramPacket(data, data.size, target))

    private fun packet(type: Int, payload: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(4 + payload.size)
            .put(0xf1.toByte()).put(type.toByte()).putShort(payload.size.toShort()).put(payload).array()

    private fun matches(packet: DatagramPacket, type: Int): Boolean =
        packet.length >= 4 && packet.data[0] == 0xf1.toByte() && packet.data[1] == type.toByte()

    private fun sameSubnet(local: InetAddress, camera: InetAddress, prefix: Int): Boolean {
        if (prefix !in 1..30) return false
        val mask = -1 shl (32 - prefix)
        return (ByteBuffer.wrap(local.address).int and mask) == (ByteBuffer.wrap(camera.address).int and mask)
    }

    private companion object {
        const val AUDIO_BYTES = 256
    }
}

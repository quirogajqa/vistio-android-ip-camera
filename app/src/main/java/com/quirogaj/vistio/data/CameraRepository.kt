package com.quirogaj.vistio.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal class CameraEndpoint(
    val origin: URL,
    val user: String,
    val password: String,
    fingerprint: String? = null,
    val mjpegUrl: URL? = null,
) {
    val isFalconStream = mjpegUrl?.let { isFalconStream(it) } == true
    val certificatePin by lazy { fingerprint?.let { CameraCertificatePin(origin, it) } }

    companion object {
        fun isFalconStream(url: URL): Boolean =
            url.protocol == "http" && url.path == "/videostream.cgi"

        fun hasStreamPath(address: String): Boolean {
            val uri = URI(address.trim())
            return uri.rawPath.orEmpty().let { it.isNotEmpty() && it != "/" } || uri.rawQuery != null
        }

        fun mjpegUrl(address: String): URL {
            val uri = URI(address.trim())
            origin(address, allowQuery = true)
            return URI(uri.toASCIIString()).toURL()
        }

        fun origin(address: String, allowQuery: Boolean = false): URL {
            val uri = URI(address.trim())
            val scheme = uri.scheme?.lowercase()
            require((scheme == "http" || scheme == "https") && uri.host != null &&
                (uri.port == -1 || uri.port in 1..65535) && uri.userInfo == null &&
                (allowQuery || uri.rawQuery == null) && uri.rawFragment == null)
            return URI(scheme, null, uri.host, uri.port, "/", null, null).toURL()
        }
    }
}

internal enum class CameraDirection(val command: Int, val description: String) {
    Up(0, "Mover hacia arriba"),
    Down(2, "Mover hacia abajo"),
    Left(4, "Mover hacia la izquierda"),
    Right(6, "Mover hacia la derecha"),
}

internal class CameraResponse(val status: Int) : IOException()

internal class CameraRepository {
    class Request(private val endpoint: CameraEndpoint) {
        @Volatile private var cancelled = false
        @Volatile private var connection: HttpURLConnection? = null
        @Volatile private var audioSocket: Socket? = null
        @Volatile private var talkSocket: DatagramSocket? = null

        fun stream(onFrame: (ByteArray) -> Unit) {
            val (active, delimiter) = openMjpeg()
            active.inputStream.buffered().use { input ->
                while (!cancelled) onFrame(MjpegReader.readFrame(input, delimiter))
            }
        }

        private fun openMjpeg(): Pair<HttpURLConnection, String> {
            val url = endpoint.mjpegUrl ?: URL(endpoint.origin, "/video/mjpeg")
            val active = open(url, 12_000)
            val type = active.contentType.orEmpty()
            val match = boundary.find(type)
            if (!type.substringBefore(';').trim().equals("multipart/x-mixed-replace", true) ||
                match == null
            ) throw IOException("Respuesta MJPEG inválida")
            val delimiter = "--" + (match.groups[1]?.value ?: match.groupValues[2])
            return active to delimiter
        }

        fun streamH264(surface: Surface, onFrame: (AvcSize) -> Unit) {
            val active = open("/video/h264", 8000)
            active.inputStream.use { input ->
                H264Player.play(input, surface, { !cancelled }, onFrame)
            }
        }

        fun streamAudio(onPlaying: () -> Unit) {
            if (endpoint.isFalconStream) {
                streamFalconAudio(onPlaying)
                return
            }
            val active = open("/audio", 8_000)
            if (!active.contentType.orEmpty().substringBefore(';').trim().equals("audio/wav", true)) {
                throw IOException("El servidor no devolvió audio WAV.")
            }
            active.inputStream.use { input -> CameraAudioPlayer.play(input, { !cancelled }, onPlaying) }
        }

        private fun streamFalconAudio(onPlaying: () -> Unit) {
            withFalconAudioSocket { socket ->
                CameraAudioPlayer.playFalcon(socket.getInputStream(), { !cancelled }, onPlaying)
            }
        }

        fun sendFalconAnnouncement(adpcm: ByteArray) {
            check(endpoint.isFalconStream)
            DatagramSocket(null).use { socket ->
                talkSocket = socket
                if (cancelled) throw InterruptedIOException()
                FalconTalk(socket, endpoint).send(adpcm)
            }
        }

        private fun withFalconAudioSocket(useSocket: (Socket) -> Unit) {
            val origin = endpoint.origin
            val port = origin.port.takeIf { it != -1 } ?: origin.defaultPort
            val username = URLEncoder.encode(endpoint.user, "UTF-8")
            val password = URLEncoder.encode(endpoint.password, "UTF-8")
            val socket = Socket(Proxy.NO_PROXY)
            audioSocket = socket
            try {
                if (cancelled) throw InterruptedIOException()
                socket.soTimeout = 8_000
                socket.connect(InetSocketAddress(origin.host.removeSurrounding("[", "]"), port), 6_000)
                // The Falcon CGI returns binary frames directly on the socket, without HTTP headers.
                val request = "GET /audiostream.cgi?user=$username&pwd=$password HTTP/1.0\r\n" +
                    "Host: ${origin.host}:$port\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                useSocket(socket)
            } finally {
                socket.close()
            }
        }

        fun moveFalcon(direction: CameraDirection, release: CountDownLatch = CountDownLatch(0)) {
            check(endpoint.isFalconStream)
            val user = URLEncoder.encode(endpoint.user, "UTF-8")
            val password = URLEncoder.encode(endpoint.password, "UTF-8")
            val path = "/decoder_control.cgi?loginuse=$user&loginpas=$password&onestep=0&command="
            if (cancelled) throw InterruptedIOException()
            try {
                sendFalconMovement(path, direction.command)
                if (!cancelled) {
                    Thread.sleep(70)
                    if (!cancelled) release.await(1_930, TimeUnit.MILLISECONDS)
                }
            } finally {
                // The camera may have started even if the response was lost. Do not let
                // cancelling the preview interrupt delivery of the stop command.
                val stopCommand = direction.command + 1
                stopFalconMovement(path, stopCommand)
                // Repeat a confirmed stop after the motor has had time to process it.
                try {
                    Thread.sleep(120)
                } finally {
                    stopFalconMovement(path, stopCommand)
                }
            }
        }

        private fun stopFalconMovement(path: String, command: Int) {
            repeat(3) {
                try {
                    sendFalconMovement(path, command)
                    return
                } catch (_: IOException) {
                    // The stop command is safe to repeat if its response was lost.
                }
            }
            sendFalconMovement(path, command)
        }

        private fun sendFalconMovement(path: String, command: Int) {
            val active = URL(endpoint.origin, path + command).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                active.instanceFollowRedirects = false
                active.connectTimeout = 600
                active.readTimeout = 600
                active.useCaches = false
                val status = active.responseCode
                if (status != HttpURLConnection.HTTP_OK) throw CameraResponse(status)
                val expected = """var result="ok";""".toByteArray(Charsets.US_ASCII)
                val response = ByteArray(expected.size)
                DataInputStream(active.inputStream).use { it.readFully(response) }
                if (!response.contentEquals(expected)) {
                    throw IOException("La cámara rechazó el movimiento.")
                }
            } finally {
                active.disconnect()
            }
        }

        fun snapshot(): Snapshot {
            if (endpoint.mjpegUrl != null) {
                val (active, delimiter) = openMjpeg()
                val bytes = active.inputStream.buffered().use { MjpegReader.readFrame(it, delimiter) }
                return Snapshot(bytes, decode(bytes, 1600))
            }
            val active = open("/video/snapshot", 20_000)
            if (!active.contentType.orEmpty().substringBefore(';').trim().equals("image/jpeg", true)) {
                throw IOException("Respuesta JPEG inválida")
            }
            val bytes = active.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var count = input.read(buffer)
                while (count != -1) {
                    if (output.size() + count > MjpegReader.MAX_JPEG) throw IOException("Captura demasiado grande")
                    output.write(buffer, 0, count)
                    count = input.read(buffer)
                }
                output.toByteArray()
            }
            return Snapshot(bytes, decode(bytes, 1600))
        }

        fun settings(): CameraSettings = try {
            val active = open("/info.json", 8_000)
            val json = active.inputStream.use { input ->
                val buffer = ByteArray(8192)
                val output = ByteArrayOutputStream()
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > 1024 * 1024) throw IOException("Datos de cámara demasiado grandes")
                    output.write(buffer, 0, count)
                }
                JSONObject(output.toString("UTF-8"))
            }
            CameraSettings.parse(json)
        } finally {
            close()
        }

        fun apply(control: CameraControl) {
            try {
                val value = URLEncoder.encode(control.value, "UTF-8")
                open("/?" + control.parameter + "=" + value, 6000)
            } finally {
                close()
            }
        }

        fun cancel(executor: Executor) {
            cancelled = true
            executor.execute(::close)
        }

        fun close() {
            connection?.disconnect()
            audioSocket?.close()
            talkSocket?.close()
        }

        private fun open(path: String, timeout: Int): HttpURLConnection {
            return open(endpoint.origin.toURI().resolve(path).toURL(), timeout)
        }

        private fun open(url: URL, timeout: Int): HttpURLConnection {
            val basic = endpoint.user.takeIf(String::isNotEmpty)?.let {
                val credentials = "$it:${endpoint.password}".toByteArray(Charsets.UTF_8)
                "Basic " + Base64.getEncoder().encodeToString(credentials)
            }
            var active = connect(url, timeout, basic.takeIf { endpoint.mjpegUrl == null })
            if (endpoint.mjpegUrl != null && basic != null && active.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val challenges = active.headerFields.filterKeys { it.equals("WWW-Authenticate", true) }.values.flatten()
                val digest = challenges.firstNotNullOfOrNull {
                    CameraDigest.authorization(it, url, endpoint.user, endpoint.password)
                }
                val authorization = digest ?: basic.takeIf {
                    challenges.any { it.startsWith("Basic ", ignoreCase = true) } &&
                        challenges.none { it.startsWith("Digest ", ignoreCase = true) }
                }
                if (authorization != null) {
                    active.disconnect()
                    active = connect(url, timeout, authorization)
                }
            }
            val status = active.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw CameraResponse(status)
            return active
        }

        private fun connect(url: URL, timeout: Int, authorization: String?): HttpURLConnection {
            val active = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
            connection = active
            if (cancelled) throw InterruptedIOException()
            active.instanceFollowRedirects = false
            endpoint.certificatePin?.configure(active as HttpsURLConnection)
            active.connectTimeout = 6000
            active.readTimeout = timeout
            active.useCaches = false
            authorization?.let { active.setRequestProperty("Authorization", it) }
            return active
        }
    }

    fun request(endpoint: CameraEndpoint) = Request(endpoint)

    companion object {
        private val boundary = Regex("""(?:^|;)\s*boundary\s*=\s*(?:"([^"]+)"|([^;\s]+))""", RegexOption.IGNORE_CASE)

        fun decode(jpeg: ByteArray, maxSide: Int): Bitmap {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > maxSide) {
                options.inSampleSize *= 2
            }
            options.inJustDecodeBounds = false
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
                ?: throw IOException("JPEG inválido")
        }
    }
}

internal class Snapshot(val bytes: ByteArray, val preview: Bitmap)

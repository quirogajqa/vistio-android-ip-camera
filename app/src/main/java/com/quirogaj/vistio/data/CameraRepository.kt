package com.quirogaj.vistio.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import java.util.concurrent.Executor
import javax.net.ssl.HttpsURLConnection

internal class CameraEndpoint(
    val origin: URL,
    val user: String,
    val password: String,
    fingerprint: String? = null,
) {
    val certificatePin by lazy { fingerprint?.let { CameraCertificatePin(origin, it) } }

    companion object {
        fun origin(address: String): URL {
            val uri = URI(address.trim())
            val scheme = uri.scheme?.lowercase()
            require((scheme == "http" || scheme == "https") && uri.host != null &&
                (uri.port == -1 || uri.port in 1..65535) && uri.userInfo == null &&
                uri.query == null && uri.fragment == null)
            return URI(scheme, null, uri.host, uri.port, "/", null, null).toURL()
        }
    }
}

internal class CameraResponse(val status: Int) : IOException()

internal class CameraRepository {
    class Request(private val endpoint: CameraEndpoint) {
        @Volatile private var cancelled = false
        @Volatile private var connection: HttpURLConnection? = null

        fun stream(onFrame: (ByteArray) -> Unit) {
            val active = open("/video/mjpeg", 12_000)
            val type = active.contentType.orEmpty()
            val match = boundary.find(type)
            if (!type.substringBefore(';').trim().equals("multipart/x-mixed-replace", true) ||
                match == null
            ) throw IOException("Respuesta MJPEG inválida")
            val delimiter = "--" + (match.groups[1]?.value ?: match.groupValues[2])
            active.inputStream.buffered().use { input ->
                while (!cancelled) onFrame(MjpegReader.readFrame(input, delimiter))
            }
        }

        fun streamH264(surface: Surface, onFrame: (AvcSize) -> Unit) {
            val active = open("/video/h264", 8000)
            active.inputStream.use { input ->
                H264Player.play(input, surface, { !cancelled }, onFrame)
            }
        }

        fun streamAudio(onPlaying: () -> Unit) {
            val active = open("/audio", 8_000)
            if (!active.contentType.orEmpty().substringBefore(';').trim().equals("audio/wav", true)) {
                throw IOException("El servidor no devolvió audio WAV.")
            }
            active.inputStream.use { input -> CameraAudioPlayer.play(input, { !cancelled }, onPlaying) }
        }

        fun snapshot(): Snapshot {
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
        }

        private fun open(path: String, timeout: Int): HttpURLConnection {
            val active = endpoint.origin.toURI().resolve(path).toURL()
                .openConnection(Proxy.NO_PROXY) as HttpURLConnection
            connection = active
            if (cancelled) throw InterruptedIOException()
            active.instanceFollowRedirects = false
            endpoint.certificatePin?.configure(active as HttpsURLConnection)
            active.connectTimeout = 6000
            active.readTimeout = timeout
            active.useCaches = false
            if (endpoint.user.isNotEmpty()) {
                val credentials = (endpoint.user + ":" + endpoint.password).toByteArray(Charsets.UTF_8)
                active.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(credentials, Base64.NO_WRAP))
            }
            val status = active.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw CameraResponse(status)
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

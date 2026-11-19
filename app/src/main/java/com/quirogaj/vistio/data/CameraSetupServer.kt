package com.quirogaj.vistio.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom

internal data class CameraSetup(
    val name: String,
    val address: String,
    val username: String,
    val fingerprint: String,
    val customMjpeg: Boolean,
)

/** A one-use server bound to the TV's local Wi-Fi or Ethernet address. */
internal class CameraSetupServer(context: Context) : AutoCloseable {
    private val network = context.getSystemService(ConnectivityManager::class.java)
    private val active = network.activeNetwork
    private val address = network.getLinkProperties(active)?.linkAddresses
        ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull {
            it.isSiteLocalAddress
        }?.takeIf {
            network.getNetworkCapabilities(active)?.let { capabilities ->
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        } ?: throw IOException("Conecta el televisor a la red local.")
    private val token = ByteArray(24).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val path = "/$token"
    private val socket = ServerSocket().apply {
        try {
            bind(InetSocketAddress(address, 0), 4)
            soTimeout = 500
        } catch (error: IOException) {
            close()
            throw error
        }
    }
    val url = "http://${address.hostAddress}:${socket.localPort}$path"

    suspend fun receive(): CameraSetup? {
        val expires = SystemClock.elapsedRealtime() + 180_000L
        while (SystemClock.elapsedRealtime() < expires) {
            currentCoroutineContext().ensureActive()
            val client = try {
                socket.accept()
            } catch (_: SocketTimeoutException) {
                continue
            }
            val result = client.use {
                try {
                    handle(it, expires)
                } catch (_: IOException) {
                    null
                }
            }
            if (result != null) return result
        }
        return null
    }

    private fun handle(client: Socket, expires: Long): CameraSetup? {
        val deadline = minOf(expires, SystemClock.elapsedRealtime() + 3_000L)
        val source = client.getInputStream()
        val input = object : InputStream() {
            override fun read(): Int = timed { source.read() }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                timed { source.read(buffer, offset, length) }

            private inline fun timed(read: () -> Int): Int {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) throw SocketTimeoutException()
                client.soTimeout = remaining.toInt()
                return read()
            }
        }.buffered()
        val first = input.line(512).split(' ')
        if (first.size != 3 || first[1] != path || first[2] !in listOf("HTTP/1.0", "HTTP/1.1")) {
            respond(client, "404 Not Found", "<p>El formulario ya no está disponible.</p>")
            return null
        }

        val headers = mutableMapOf<String, String>()
        repeat(24) {
            val line = input.line(1024)
            if (line.isEmpty()) return handleRequest(first[0], headers, input, client)
            val key = line.substringBefore(':').lowercase()
            if (':' !in line || headers.put(key, line.substringAfter(':').trim()) != null) throw IOException()
        }
        return null
    }

    private fun handleRequest(method: String, headers: Map<String, String>, input: InputStream, client: Socket): CameraSetup? {
        val host = "${address.hostAddress}:${socket.localPort}"
        val origin = headers["origin"]
        // Some browsers report an opaque origin for a local form POST with no-referrer.
        // The exact one-use path is checked before reading the headers.
        if (headers["host"] != host || "transfer-encoding" in headers ||
            headers["sec-fetch-site"] == "cross-site" ||
            (origin != null && origin != "null" && origin != "http://$host")
        ) {
            respond(client, "403 Forbidden", "<p>Abre el formulario desde el código QR.</p>")
            return null
        }
        if (method == "GET") {
            respond(client, "200 OK", form)
            return null
        }
        val length = headers["content-length"]?.toIntOrNull()
        if (method != "POST" || length == null || length !in 1..4096 ||
            headers["content-type"]?.substringBefore(';') != "application/x-www-form-urlencoded"
        ) {
            respond(client, "400 Bad Request", "<p>La solicitud no es válida.</p>")
            return null
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(body, offset, length - offset)
            if (n < 0) throw IOException()
            offset += n
        }
        val setup = runCatching {
            val values = body.toString(Charsets.UTF_8).split('&').associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8").trim()
            }
            val name = values["name"].orEmpty()
            val username = values["username"].orEmpty()
            require(name.length <= 80 && username.length <= 120 && ':' !in username)
            val rawAddress = values.getValue("address")
            require(rawAddress.length <= 255)
            val customMjpeg = CameraEndpoint.hasStreamPath(rawAddress)
            val camera = if (customMjpeg) CameraEndpoint.mjpegUrl(rawAddress) else CameraEndpoint.origin(rawAddress)
            val enteredPin = values["fingerprint"].orEmpty()
            val fingerprint = if (enteredPin.isEmpty()) "" else {
                require(camera.protocol == "https")
                requireNotNull(CameraCertificatePin.normalize(enteredPin))
            }
            CameraSetup(name, camera.toString(), username, fingerprint, customMjpeg)
        }.getOrNull()
        if (setup == null) {
            respond(client, "400 Bad Request", "<p>Comprueba la dirección y, si ingresaste una huella, usa HTTPS y 64 dígitos hexadecimales.</p>$form")
            return null
        }
        // The TV still reviews the profile and saves it explicitly.
        respond(client, "200 OK", "<h1>Datos enviados</h1><p>Revisa el perfil y guárdalo en Vistio TV.</p>")
        return setup
    }

    private fun InputStream.line(max: Int): String {
        val result = StringBuilder()
        while (result.length < max) {
            val next = read()
            if (next < 0) throw IOException()
            if (next == 10) return result.toString().removeSuffix("\r")
            result.append(next.toChar())
        }
        throw IOException()
    }

    private fun respond(client: Socket, status: String, body: String) {
        val page = """
            <!doctype html><html lang="es"><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Vistio TV</title><style>
            :root{color-scheme:dark;background:#14121b;color:#f6eeff;font:17px system-ui}
            main{max-width:30rem;margin:2rem auto;padding:0 1rem}
            label{display:block;margin:1rem 0} input,button{box-sizing:border-box;width:100%;padding:.8rem;
            border-radius:.6rem;border:1px solid #847791;font:inherit} button{color:#102823;background:#7cf4dd;
            font-weight:600;margin-top:.6rem} p{line-height:1.5}
            </style><main>$body</main></html>
        """.trimIndent().toByteArray()
        val header = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${page.size}\r\nConnection: close\r\nCache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
        client.getOutputStream().apply { write(header.toByteArray()); write(page); flush() }
    }

    override fun close() = socket.close()

    private val form = """
        <h1>Agregar cámara a Vistio TV</h1>
        <p>Escribe la dirección que muestra Android IP Camera. Si usa contraseña, introdúcela después en la TV.</p>
        <form action="$path" method="post" autocomplete="off">
            <label>Nombre (opcional)<input name="name" maxlength="80"></label>
            <label>Dirección de la cámara o URL del video MJPEG<input name="address" type="url" maxlength="255" placeholder="https://192.168.1.10:4444" required></label>
            <label>Usuario (opcional)<input name="username" maxlength="120" autocapitalize="none"></label>
            <label>Huella SHA-256 (opcional, solo HTTPS)<input name="fingerprint" maxlength="160" autocapitalize="none" spellcheck="false" placeholder="64 dígitos hexadecimales"></label>
            <p>Comprueba la huella contra el certificado de tu cámara antes de guardar en la TV.</p>
            <button type="submit">Enviar a Vistio TV</button>
        </form>
    """
}

package com.quirogaj.vistio

import com.quirogaj.vistio.data.CameraDirection
import com.quirogaj.vistio.data.CameraEndpoint
import com.quirogaj.vistio.data.CameraRepository
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Test

class FalconMovementTest {
    @Test fun retriesStopWithoutRestartingMotor() {
        val commands = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/decoder_control.cgi") { request ->
            val query = request.requestURI.query.substringAfter("&onestep=")
            commands.add(query)
            try {
                if (commands.size == 2) {
                    request.sendResponseHeaders(503, -1)
                } else {
                    val body = """var result="ok";""".toByteArray()
                    request.sendResponseHeaders(200, body.size.toLong())
                    request.responseBody.write(body)
                }
            } finally {
                request.close()
            }
        }
        server.start()
        try {
            val address = "http://127.0.0.1:${server.address.port}"
            val camera = CameraEndpoint(
                CameraEndpoint.origin(address), "viewer", "test",
                mjpegUrl = CameraEndpoint.mjpegUrl("$address/videostream.cgi"),
            )
            CameraRepository().request(camera).moveFalcon(CameraDirection.Left)
            assertEquals(listOf("0&command=4", "0&command=5", "0&command=5", "0&command=5"), commands)
        } finally {
            server.stop(0)
        }
    }
}

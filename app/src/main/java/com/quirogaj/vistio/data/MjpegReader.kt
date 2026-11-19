package com.quirogaj.vistio.data

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

internal object MjpegReader {
    const val MAX_JPEG = 16 * 1024 * 1024

    @JvmStatic
    fun readFrame(input: InputStream, delimiter: String): ByteArray {
        do {
            val line = readLine(input)
            if (line == "$delimiter--") throw EOFException()
        } while (line != delimiter)

        var length: Int? = null
        var headers = 0
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            if (++headers > 32) throw IOException("Cabeceras MJPEG demasiado largas")
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                length = line.substringAfter(':').trim().toIntOrNull()
                if (length == null || length !in 2..MAX_JPEG) throw IOException("Tamaño JPEG inválido")
            }
        }
        return if (length == null) {
            readJpeg(input)
        } else {
            ByteArray(length).also { DataInputStream(input).readFully(it) }
        }
    }

    private fun readLine(input: InputStream): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) throw EOFException()
            if (value == '\n'.code) return line.toString("US-ASCII").removeSuffix("\r")
            if (line.size() == 8192) throw IOException("Línea MJPEG demasiado larga")
            line.write(value)
        }
    }

    private fun readJpeg(input: InputStream): ByteArray {
        if (input.read() != 255 || input.read() != 216) throw IOException("JPEG inválido")
        val jpeg = ByteArrayOutputStream()
        jpeg.write(255)
        jpeg.write(216)
        var previous = 216
        while (jpeg.size() < MAX_JPEG) {
            val value = input.read()
            if (value == -1) break
            jpeg.write(value)
            if (previous == 255 && value == 217) return jpeg.toByteArray()
            previous = value
        }
        throw IOException("JPEG incompleto o demasiado grande")
    }
}


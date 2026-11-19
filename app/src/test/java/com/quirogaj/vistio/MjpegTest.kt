package com.quirogaj.vistio

import com.quirogaj.vistio.data.MjpegReader
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MjpegTest {
    @Test fun readsConsecutiveFramesWithAndWithoutContentLength() {
        val input = bytes(
            "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 5\r\n\r\n" +
                "\u00ff\u00d8a\u00ff\u00d9\r\n--frame\r\nContent-Type: image/jpeg\r\n\r\n" +
                "\u00ff\u00d8b\u00ff\u00d9\r\n--frame--\r\n",
        ).inputStream()

        assertArrayEquals(bytes("\u00ff\u00d8a\u00ff\u00d9"), MjpegReader.readFrame(input, "--frame"))
        assertArrayEquals(bytes("\u00ff\u00d8b\u00ff\u00d9"), MjpegReader.readFrame(input, "--frame"))
        assertThrows(EOFException::class.java) { MjpegReader.readFrame(input, "--frame") }
    }

    @Test fun rejectsAnOversizedPartBeforeReadingItsBody() {
        val input = bytes("--frame\r\nContent-Length: 99999999\r\n\r\n").inputStream()
        assertThrows(IOException::class.java) { MjpegReader.readFrame(input, "--frame") }
    }

    private fun bytes(body: String) = body.toByteArray(Charsets.ISO_8859_1)
}

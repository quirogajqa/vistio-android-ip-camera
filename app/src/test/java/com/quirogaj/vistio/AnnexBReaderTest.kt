package com.quirogaj.vistio

import com.quirogaj.vistio.data.AnnexBReader
import com.quirogaj.vistio.data.AvcSize
import com.quirogaj.vistio.data.readAvcSize
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnexBReaderTest {
    @Test fun groupsMultipleSlicesAndRetainsDecoderConfiguration() {
        val sps = bytes(0x67, 0x42, 0, 0x1f, 0xf4, 2, 0x80, 0x2d, 0xc8)
        val pps = bytes(0x68, 0xce, 6, 0xe2)
        val first = bytes(0x65, 0x80)
        val continuation = bytes(0x65, 0x40)
        val second = bytes(0x41, 0x80)
        val start = bytes(0, 0, 0, 1)
        val reader = AnnexBReader(
            (start + sps + bytes(0, 0, 1) + pps + start + first + start + continuation + start + second)
                .inputStream(),
        )

        val key = reader.readAccessUnit()
        assertTrue(key.keyFrame)
        assertArrayEquals(start + first + start + continuation, key.bytes)
        assertArrayEquals(sps, key.sps)
        assertArrayEquals(pps, key.pps)
        assertEquals(AvcSize(1280, 720), readAvcSize(sps))

        val next = reader.readAccessUnit()
        assertFalse(next.keyFrame)
        assertArrayEquals(start + second, next.bytes)
        assertThrows(EOFException::class.java) { reader.readAccessUnit() }
    }

    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
}

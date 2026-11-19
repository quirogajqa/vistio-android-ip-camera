package com.quirogaj.vistio.data

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

internal data class AvcAccessUnit(
    val bytes: ByteArray,
    val keyFrame: Boolean,
    val sps: ByteArray?,
    val pps: ByteArray?,
)

internal class AnnexBReader(input: InputStream) {
    private val source = input.buffered(32 * 1024)
    private var foundStart = false
    private var nextNal: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun readAccessUnit(): AvcAccessUnit {
        val bytes = ByteArrayOutputStream()
        var hasSlice = false
        var key = false
        var count = 0
        while (true) {
            if (++count > 2048) throw IOException("Demasiadas unidades H.264 sin fotograma")
            val nal = try {
                nextNal ?: readNal()
            } catch (eof: EOFException) {
                if (hasSlice) return AvcAccessUnit(bytes.toByteArray(), key, sps, pps)
                throw eof
            }
            nextNal = null
            val type = nal[0].toInt() and 31
            val isSlice = type in 1..5
            val startsPicture = isSlice && nal.size > 1 && (nal[1].toInt() and 128) != 0
            if (hasSlice && (startsPicture || type in 6..9)) {
                nextNal = nal
                return AvcAccessUnit(bytes.toByteArray(), key, sps, pps)
            }
            when (type) {
                7 -> sps = nal
                8 -> pps = nal
            }
            if (isSlice) {
                bytes.appendNal(nal)
                hasSlice = true
                key = key || type == 5
            }
        }
    }

    private fun readNal(): ByteArray {
        var zeros = 0
        if (!foundStart) {
            var scanned = 0
            while (!foundStart) {
                val value = source.read()
                if (value < 0) throw EOFException()
                if (++scanned > MAX_UNIT) throw IOException("No se encontró el inicio H.264")
                foundStart = value == 1 && zeros >= 2
                zeros = if (value == 0) zeros + 1 else 0
            }
        }
        val nal = ByteArrayOutputStream()
        zeros = 0
        while (true) {
            val value = source.read()
            if (value < 0) {
                if (nal.size() == 0) throw EOFException()
                return nal.toByteArray()
            }
            if (value == 0) {
                zeros++
            } else if (value == 1 && zeros >= 2) {
                if (nal.size() != 0) return nal.toByteArray()
                zeros = 0
            } else {
                repeat(zeros) { nal.write(0) }
                zeros = 0
                nal.write(value)
            }
            if (nal.size() + zeros > MAX_UNIT) throw IOException("Unidad H.264 demasiado grande")
        }
    }

    companion object {
        private const val MAX_UNIT = 4 * 1024 * 1024
        val startCode = byteArrayOf(0, 0, 0, 1)

        private fun ByteArrayOutputStream.appendNal(nal: ByteArray) {
            if (size() + nal.size + startCode.size > MAX_UNIT) throw IOException("Fotograma H.264 demasiado grande")
            write(startCode)
            write(nal)
        }
    }
}

internal data class AvcSize(val width: Int, val height: Int)

internal fun readAvcSize(sps: ByteArray): AvcSize {
    val rbsp = ByteArrayOutputStream()
    var zeros = 0
    for (index in 1 until sps.size) {
        val value = sps[index].toInt() and 255
        if (zeros == 2 && value == 3) {
            zeros = 0
            continue
        }
        rbsp.write(value)
        zeros = if (value == 0) zeros + 1 else 0
    }
    val bits = AvcBits(rbsp.toByteArray())
    val profile = bits.read(8)
    bits.read(16)
    bits.ue()
    var chromaFormat = 1
    var separateColorPlane = false
    if (profile in setOf(44, 83, 86, 100, 110, 118, 122, 128, 134, 135, 138, 139, 144, 244)) {
        chromaFormat = bits.ue()
        require(chromaFormat in 0..3)
        if (chromaFormat == 3) separateColorPlane = bits.read(1) != 0
        bits.ue()
        bits.ue()
        bits.read(1)
        if (bits.read(1) != 0) {
            repeat(if (chromaFormat == 3) 12 else 8) { index ->
                if (bits.read(1) != 0) {
                    var last = 8
                    var next = 8
                    repeat(if (index < 6) 16 else 64) {
                        if (next != 0) next = (last + bits.se() + 256) and 255
                        if (next != 0) last = next
                    }
                }
            }
        }
    }
    bits.ue()
    when (bits.ue()) {
        0 -> bits.ue()
        1 -> {
            bits.read(1)
            bits.se()
            bits.se()
            val count = bits.ue()
            require(count <= 255)
            repeat(count) { bits.se() }
        }
    }
    bits.ue()
    bits.read(1)
    val widthBlocks = bits.ue() + 1
    val heightBlocks = bits.ue() + 1
    val frameFactor = 2 - bits.read(1)
    if (frameFactor == 2) bits.read(1)
    bits.read(1)
    val chromaArray = if (separateColorPlane) 0 else chromaFormat
    val cropX = if (chromaArray in 1..2) 2 else 1
    val cropY = frameFactor * if (chromaArray == 1) 2 else 1
    var width = widthBlocks * 16
    var height = heightBlocks * 16 * frameFactor
    if (bits.read(1) != 0) {
        width -= (bits.ue() + bits.ue()) * cropX
        height -= (bits.ue() + bits.ue()) * cropY
    }
    if (width !in 16..8192 || height !in 16..8192) throw IOException("Tamaño H.264 inválido")
    return AvcSize(width, height)
}

private class AvcBits(private val bytes: ByteArray) {
    private var position = 0

    fun read(count: Int): Int {
        if (position + count > bytes.size * 8) throw IOException("Cabecera H.264 incompleta")
        var value = 0
        repeat(count) {
            value = (value shl 1) or ((bytes[position / 8].toInt() shr (7 - position % 8)) and 1)
            position++
        }
        return value
    }

    fun ue(): Int {
        var leading = 0
        while (read(1) == 0) {
            if (++leading > 20) throw IOException("Cabecera H.264 inválida")
        }
        return (1 shl leading) - 1 + read(leading)
    }

    fun se(): Int {
        val code = ue()
        return if (code % 2 == 0) -(code / 2) else (code + 1) / 2
    }
}

package com.umbette.nozzleguardian.engine

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Self-contained pure-JVM ARGB raster surface + PWG 5102.4 ('RaS2') PackBits encoder/decoder.
 * Compiles micro-purge sheets into driverless image/pwg-raster streams with zero native dependencies.
 */
class RasterSurface(
    val width: Int,
    val height: Int,
    val isGrayscale: Boolean = false,
    fillArgb: Int = 0xFFFFFFFF.toInt(),
) {
    val pixels = IntArray(width * height) { fillArgb }

    fun setPixel(x: Int, y: Int, argb: Int) {
        if (x in 0 until width && y in 0 until height) {
            pixels[y * width + x] = argb
        }
    }

    fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int) {
        val sx0 = x0.coerceIn(0, width)
        val sx1 = x1.coerceIn(0, width)
        val sy0 = y0.coerceIn(0, height)
        val sy1 = y1.coerceIn(0, height)
        for (y in sy0 until sy1) {
            val rowOff = y * width
            for (x in sx0 until sx1) {
                pixels[rowOff + x] = argb
            }
        }
    }

    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int, thickness: Int = 1) {
        val dx = x1 - x0
        val dy = y1 - y0
        val steps = maxOf(abs(dx), abs(dy), 1)
        val half = maxOf(0, thickness / 2)
        for (i in 0..steps) {
            val cx = (x0 + dx * (i.toDouble() / steps)).roundToInt()
            val cy = (y0 + dy * (i.toDouble() / steps)).roundToInt()
            if (thickness <= 1) {
                setPixel(cx, cy, argb)
            } else {
                fillRect(cx - half, cy - half, cx + half + 1, cy + half + 1, argb)
            }
        }
    }
}

data class MediaSpec(
    val mediaName: String,
    val widthMm: Double,
    val heightMm: Double,
    val pointsWidth: Int,
    val pointsHeight: Int,
)

data class DecodedPwgRaster(
    val mediaClass: String,
    val mediaType: String,
    val mediaSizeName: String,
    val dpiX: Int,
    val dpiY: Int,
    val width: Int,
    val height: Int,
    val bitsPerPixel: Int,
    val bytesPerLine: Int,
    val colorSpace: Int,
    val rawPixels: ByteArray,
)

object PwgRasterEncoder {
    private val PWG_MAGIC = byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'S'.code.toByte(), '2'.code.toByte())
    private const val PWG_HEADER_SIZE = 1796
    const val CUPS_CSPACE_SGRAY = 18
    const val CUPS_CSPACE_SRGB = 19

    private val PWG_MEDIA_TYPE_MAP = mapOf(
        "auto" to "Stationery",
        "stationery" to "Stationery",
        "photographic" to "Photographic",
        "photographic-glossy" to "PhotographicGlossy",
        "photographic-matte" to "PhotographicMatte",
        "photographic-semi-gloss" to "PhotographicSemiGloss",
    )

    fun pwgMediaTypeName(mediaType: String?): String {
        if (mediaType.isNullOrBlank()) return "Stationery"
        val key = mediaType.lowercase().trim()
        return PWG_MEDIA_TYPE_MAP[key] ?: mediaType.trim().take(63).ifBlank { "Stationery" }
    }

    val MEDIA_DIMENSIONS = mapOf(
        "iso_a4_210x297mm" to MediaSpec("iso_a4_210x297mm", 210.0, 297.0, 595, 842),
        "na_letter_8.5x11in" to MediaSpec("na_letter_8.5x11in", 215.9, 279.4, 612, 792),
    )

    fun mediaSpecFor(mediaName: String): MediaSpec =
        MEDIA_DIMENSIONS[mediaName] ?: MEDIA_DIMENSIONS.getValue("iso_a4_210x297mm")

    private fun packCstr(text: String, length: Int = 64): ByteArray {
        val raw = text.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(length)
        val count = minOf(raw.size, length - 1)
        System.arraycopy(raw, 0, out, 0, count)
        return out
    }

    private fun putIntBE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    private fun getIntBE(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)

    private fun rowToBytes(surface: RasterSurface, y: Int): ByteArray {
        val rowOff = y * surface.width
        return if (surface.isGrayscale) {
            ByteArray(surface.width) { x ->
                val p = surface.pixels[rowOff + x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            }
        } else {
            val out = ByteArray(surface.width * 3)
            var idx = 0
            for (x in 0 until surface.width) {
                val p = surface.pixels[rowOff + x]
                out[idx++] = ((p ushr 16) and 0xFF).toByte()
                out[idx++] = ((p ushr 8) and 0xFF).toByte()
                out[idx++] = (p and 0xFF).toByte()
            }
            out
        }
    }

    private fun pixelsEqual(row: ByteArray, idxA: Int, idxB: Int, bppBytes: Int): Boolean {
        val a = idxA * bppBytes
        val b = idxB * bppBytes
        for (k in 0 until bppBytes) {
            if (row[a + k] != row[b + k]) return false
        }
        return true
    }

    private fun packBitsEncodeRow(row: ByteArray, bppBytes: Int): ByteArray {
        val nPixels = row.size / bppBytes
        if (nPixels == 0) return ByteArray(0)
        val out = ByteArrayOutputStream(nPixels)
        var i = 0
        while (i < nPixels) {
            var runLen = 1
            while (runLen < 128 && (i + runLen) < nPixels && pixelsEqual(row, i, i + runLen, bppBytes)) {
                runLen++
            }
            if (runLen >= 2 || i + 1 >= nPixels) {
                out.write(runLen - 1)
                out.write(row, i * bppBytes, bppBytes)
                i += runLen
                continue
            }

            val litStart = i
            var litCount = 1
            i++
            while (litCount < 128 && i < nPixels) {
                if (i + 1 < nPixels && pixelsEqual(row, i, i + 1, bppBytes)) {
                    break
                }
                litCount++
                i++
            }
            if (litCount == 1) {
                out.write(0)
                out.write(row, litStart * bppBytes, bppBytes)
            } else {
                out.write(257 - litCount)
                out.write(row, litStart * bppBytes, litCount * bppBytes)
            }
        }
        return out.toByteArray()
    }

    fun encode(
        surface: RasterSurface,
        dpi: Int = 300,
        mediaName: String = "iso_a4_210x297mm",
        mediaType: String = "Stationery",
    ): ByteArray {
        val bpp = if (surface.isGrayscale) 8 else 24
        val bppBytes = bpp / 8
        val numColors = if (surface.isGrayscale) 1 else 3
        val cspace = if (surface.isGrayscale) CUPS_CSPACE_SGRAY else CUPS_CSPACE_SRGB
        val bytesPerLine = surface.width * bppBytes
        val spec = mediaSpecFor(mediaName)

        val header = ByteArray(PWG_HEADER_SIZE)
        System.arraycopy(packCstr("PwgRaster", 64), 0, header, 0, 64)
        System.arraycopy(packCstr(pwgMediaTypeName(mediaType), 64), 0, header, 128, 64)
        putIntBE(header, 276, dpi)
        putIntBE(header, 280, dpi)
        putIntBE(header, 340, 1)
        putIntBE(header, 352, spec.pointsWidth)
        putIntBE(header, 356, spec.pointsHeight)
        putIntBE(header, 372, surface.width)
        putIntBE(header, 376, surface.height)
        putIntBE(header, 384, 8)
        putIntBE(header, 388, bpp)
        putIntBE(header, 392, bytesPerLine)
        putIntBE(header, 396, 0)
        putIntBE(header, 400, cspace)
        putIntBE(header, 420, numColors)
        putIntBE(header, 452, 1)
        putIntBE(header, 456, 1)
        putIntBE(header, 460, 1)
        putIntBE(header, 480, 0x00FFFFFF)
        System.arraycopy(packCstr(spec.mediaName, 64), 0, header, 1732, 64)

        val out = ByteArrayOutputStream(PWG_MAGIC.size + PWG_HEADER_SIZE + surface.height * 32)
        out.write(PWG_MAGIC)
        out.write(header)

        var y = 0
        while (y < surface.height) {
            val row = rowToBytes(surface, y)
            var repeat = 1
            while (repeat < 256 && (y + repeat) < surface.height) {
                val nextRow = rowToBytes(surface, y + repeat)
                if (!row.contentEquals(nextRow)) break
                repeat++
            }
            out.write(repeat - 1)
            val packed = packBitsEncodeRow(row, bppBytes)
            out.write(packed)
            y += repeat
        }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): DecodedPwgRaster {
        require(data.size >= 4 + PWG_HEADER_SIZE) { "Invalid PWG-Raster stream size" }
        require(
            data[0] == PWG_MAGIC[0] &&
                data[1] == PWG_MAGIC[1] &&
                data[2] == PWG_MAGIC[2] &&
                data[3] == PWG_MAGIC[3]
        ) { "Invalid PWG-Raster magic" }

        val hdr = data.copyOfRange(4, 4 + PWG_HEADER_SIZE)
        var nullPos = 0
        while (nullPos < 64 && hdr[nullPos].toInt() != 0) nullPos++
        val mediaClass = String(hdr, 0, nullPos, Charsets.US_ASCII)
        var mtNull = 0
        while (mtNull < 64 && hdr[128 + mtNull].toInt() != 0) mtNull++
        val decodedMediaType = String(hdr, 128, mtNull, Charsets.US_ASCII)
        var msNull = 0
        while (msNull < 64 && hdr[1732 + msNull].toInt() != 0) msNull++
        val decodedMediaSizeName = String(hdr, 1732, msNull, Charsets.US_ASCII)
        val dpiX = getIntBE(hdr, 276)
        val dpiY = getIntBE(hdr, 280)
        val width = getIntBE(hdr, 372)
        val height = getIntBE(hdr, 376)
        val bpp = getIntBE(hdr, 388)
        val bytesPerLine = getIntBE(hdr, 392)
        val cspace = getIntBE(hdr, 400)
        val bppBytes = bpp / 8

        val pixelsOut = ByteArrayOutputStream(height * bytesPerLine)
        var pos = 4 + PWG_HEADER_SIZE
        var decodedRows = 0
        while (decodedRows < height && pos < data.size) {
            val repeat = (data[pos++].toInt() and 0xFF) + 1
            val rowBuf = ByteArrayOutputStream(bytesPerLine)
            while (rowBuf.size() < bytesPerLine && pos < data.size) {
                val code = data[pos++].toInt() and 0xFF
                if (code <= 127) {
                    val count = code + 1
                    val px = data.copyOfRange(pos, pos + bppBytes)
                    pos += bppBytes
                    repeat(count) { rowBuf.write(px) }
                } else {
                    val count = 257 - code
                    val chunkLen = count * bppBytes
                    rowBuf.write(data, pos, chunkLen)
                    pos += chunkLen
                }
            }
            val rowBytes = rowBuf.toByteArray()
            val times = minOf(repeat, height - decodedRows)
            repeat(times) {
                pixelsOut.write(rowBytes, 0, minOf(bytesPerLine, rowBytes.size))
                decodedRows++
            }
        }

        return DecodedPwgRaster(
            mediaClass = mediaClass,
            mediaType = decodedMediaType,
            mediaSizeName = decodedMediaSizeName,
            dpiX = dpiX,
            dpiY = dpiY,
            width = width,
            height = height,
            bitsPerPixel = bpp,
            bytesPerLine = bytesPerLine,
            colorSpace = cspace,
            rawPixels = pixelsOut.toByteArray(),
        )
    }
}

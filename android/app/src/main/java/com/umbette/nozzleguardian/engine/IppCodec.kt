package com.umbette.nozzleguardian.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class IppResolution(val xDpi: Int, val yDpi: Int, val unit: Int)

data class IppResponse(
    val statusCode: Int,
    val attributes: Map<String, List<Any>>,
) {
    fun firstString(key: String): String? = attributes[key]?.firstOrNull()?.toString()

    fun firstInt(key: String): Int? = attributes[key]?.firstOrNull() as? Int

    fun stringList(key: String): List<String> = attributes[key].orEmpty().map { it.toString() }

    fun intList(key: String): List<Int> = attributes[key].orEmpty().mapNotNull { it as? Int }
}

/**
 * Pure-Kotlin RFC 8011 IPP binary codec, format/media negotiator, and printer hardware classifier.
 */
object IppCodec {
    private val IPP_VERSION = byteArrayOf(0x02, 0x00)
    const val OP_PRINT_JOB = 0x0002
    const val OP_GET_PRINTER_ATTRIBUTES = 0x000B

    const val TAG_OPERATION_ATTRIBUTES = 0x01
    const val TAG_JOB_ATTRIBUTES = 0x02
    const val TAG_END_OF_ATTRIBUTES = 0x03

    const val TAG_INTEGER = 0x21
    const val TAG_BOOLEAN = 0x22
    const val TAG_ENUM = 0x23
    const val TAG_RESOLUTION = 0x32
    const val TAG_TEXT_WITH_LANG = 0x35
    const val TAG_NAME_WITH_LANG = 0x36
    const val TAG_TEXT_WITHOUT_LANG = 0x41
    const val TAG_NAME_WITHOUT_LANG = 0x42
    const val TAG_KEYWORD = 0x44
    const val TAG_URI = 0x45
    const val TAG_CHARSET = 0x47
    const val TAG_NATURAL_LANGUAGE = 0x48
    const val TAG_MIME_TYPE = 0x49

    val IPP_PATHS = listOf("/ipp/print", "/ipp/printer", "/ipp", "/")

    val FORMAT_PREFERENCE = listOf(
        "image/pwg-raster",
        "application/pdf",
        "image/png",
        "image/jpeg",
    )

    val REQUESTED_PRINTER_ATTRS = listOf(
        "printer-name",
        "printer-make-and-model",
        "printer-info",
        "printer-state",
        "printer-state-reasons",
        "document-format-supported",
        "media-supported",
        "media-default",
        "color-supported",
        "pwg-raster-document-resolution-supported",
        "marker-names",
        "marker-levels",
        "marker-types",
        "marker-colors",
        "printer-impressions-completed",
    )

    val PRINTER_STATE_MAP = mapOf(
        3 to "idle",
        4 to "printing",
        5 to "stopped",
    )

    private fun writeShortBE(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeIntBE(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun encodeAttr(out: ByteArrayOutputStream, tag: Int, name: String, value: ByteArray) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        out.write(tag)
        writeShortBE(out, nameBytes.size)
        out.write(nameBytes)
        writeShortBE(out, value.size)
        out.write(value)
    }

    fun buildGetPrinterAttributesRequest(
        printerUri: String,
        requestedAttributes: List<String> = REQUESTED_PRINTER_ATTRS,
        requestId: Int = 1,
    ): ByteArray {
        val out = ByteArrayOutputStream(512)
        out.write(IPP_VERSION)
        writeShortBE(out, OP_GET_PRINTER_ATTRIBUTES)
        writeIntBE(out, requestId)
        out.write(TAG_OPERATION_ATTRIBUTES)
        encodeAttr(out, TAG_CHARSET, "attributes-charset", "utf-8".toByteArray(Charsets.UTF_8))
        encodeAttr(out, TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en".toByteArray(Charsets.UTF_8))
        encodeAttr(out, TAG_URI, "printer-uri", printerUri.toByteArray(Charsets.UTF_8))
        encodeAttr(out, TAG_NAME_WITHOUT_LANG, "requesting-user-name", "nozzle-guardian".toByteArray(Charsets.UTF_8))
        requestedAttributes.forEachIndexed { idx, attr ->
            encodeAttr(out, TAG_KEYWORD, if (idx == 0) "requested-attributes" else "", attr.toByteArray(Charsets.UTF_8))
        }
        out.write(TAG_END_OF_ATTRIBUTES)
        return out.toByteArray()
    }

    fun buildPrintJobRequest(
        printerUri: String,
        docFormat: String = "image/pwg-raster",
        media: String = "iso_a4_210x297mm",
        colorMode: String = "color",
        jobName: String = "Nozzle Guardian Purge",
        requestId: Int = 2,
    ): ByteArray {
        val out = ByteArrayOutputStream(384)
        out.write(IPP_VERSION)
        writeShortBE(out, OP_PRINT_JOB)
        writeIntBE(out, requestId)
        out.write(TAG_OPERATION_ATTRIBUTES)
        encodeAttr(out, TAG_CHARSET, "attributes-charset", "utf-8".toByteArray(Charsets.UTF_8))
        encodeAttr(out, TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en".toByteArray(Charsets.UTF_8))
        encodeAttr(out, TAG_URI, "printer-uri", printerUri.toByteArray(Charsets.UTF_8))
        encodeAttr(out, TAG_NAME_WITHOUT_LANG, "requesting-user-name", "nozzle-guardian".toByteArray(Charsets.UTF_8))
        encodeAttr(out, TAG_NAME_WITHOUT_LANG, "job-name", jobName.toByteArray(Charsets.UTF_8))
        encodeAttr(out, TAG_MIME_TYPE, "document-format", docFormat.toByteArray(Charsets.UTF_8))

        out.write(TAG_JOB_ATTRIBUTES)
        encodeAttr(out, TAG_KEYWORD, "media", media.toByteArray(Charsets.UTF_8))
        encodeAttr(out, TAG_KEYWORD, "print-color-mode", colorMode.toByteArray(Charsets.UTF_8))
        encodeAttr(out, TAG_KEYWORD, "sides", "one-sided".toByteArray(Charsets.UTF_8))
        out.write(TAG_END_OF_ATTRIBUTES)
        return out.toByteArray()
    }

    private fun readShortBE(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun decodeValue(tag: Int, raw: ByteArray): Any {
        if ((tag == TAG_INTEGER || tag == TAG_ENUM) && raw.size == 4) {
            return ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).int
        }
        if (tag == TAG_BOOLEAN && raw.isNotEmpty()) {
            return raw[0].toInt() != 0
        }
        if (tag == TAG_RESOLUTION && raw.size == 9) {
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            return IppResolution(bb.int, bb.int, bb.get().toInt() and 0xFF)
        }
        if ((tag == TAG_TEXT_WITH_LANG || tag == TAG_NAME_WITH_LANG) && raw.size >= 4) {
            val langLen = readShortBE(raw, 0)
            val valStart = 2 + langLen + 2
            if (valStart <= raw.size) {
                return String(raw, valStart, raw.size - valStart, Charsets.UTF_8)
            }
        }
        return String(raw, Charsets.UTF_8)
    }

    fun parseResponse(data: ByteArray): IppResponse {
        require(data.size >= 8) { "Truncated IPP response" }
        val statusCode = readShortBE(data, 2)
        val attrs = linkedMapOf<String, MutableList<Any>>()
        var pos = 8
        var lastName: String? = null

        while (pos < data.size) {
            val tag = data[pos++].toInt() and 0xFF
            if (tag == TAG_END_OF_ATTRIBUTES) break
            if (tag < 0x10) {
                lastName = null
                continue
            }
            if (pos + 2 > data.size) break
            val nameLen = readShortBE(data, pos)
            pos += 2
            if (pos + nameLen + 2 > data.size) break
            val name = String(data, pos, nameLen, Charsets.UTF_8)
            pos += nameLen
            val valLen = readShortBE(data, pos)
            pos += 2
            if (pos + valLen > data.size) break
            val rawVal = data.copyOfRange(pos, pos + valLen)
            pos += valLen

            val value = decodeValue(tag, rawVal)
            if (name.isNotEmpty()) {
                lastName = name
                attrs.getOrPut(name) { mutableListOf() }.add(value)
            } else if (lastName != null) {
                attrs.getOrPut(lastName) { mutableListOf() }.add(value)
            }
        }

        return IppResponse(statusCode = statusCode, attributes = attrs)
    }

    fun classifyHardware(modelOrText: String, markerTypes: List<String>): Pair<PrinterKind, HeadTechnology> {
        val low = modelOrText.lowercase()
        val mtLow = markerTypes.joinToString(" ").lowercase()
        if (
            "toner" in mtLow ||
            "fuser" in mtLow ||
            listOf("laserjet", "color laser", "hl-l", "mfc-l", "ecosys", "phaser", "versalink").any { it in low }
        ) {
            return PrinterKind.LASER to HeadTechnology.LASER
        }
        if (listOf("canon", "megatank", "pixma", "maxify", "hp ", "smart tank", "deskjet", "officejet", "envy", "productusagedyn", "lexmark").any { it in low }) {
            return PrinterKind.INKJET to HeadTechnology.THERMAL
        }
        return PrinterKind.INKJET to HeadTechnology.PIEZO
    }

    fun negotiateFormat(supportedFormats: List<String>): String {
        val normalized = supportedFormats.map { it.lowercase().trim() }.toSet()
        if (normalized.isEmpty()) return "image/pwg-raster"
        for (candidate in FORMAT_PREFERENCE) {
            if (candidate in normalized) return candidate
        }
        return "image/png"
    }

    fun negotiateMedia(
        preferredMedia: String,
        ippDefault: String?,
        ippSupported: List<String>,
    ): String {
        if (preferredMedia.isNotBlank() && preferredMedia != "auto") return preferredMedia
        if (ippDefault == "iso_a4_210x297mm" || ippDefault == "na_letter_8.5x11in") return ippDefault
        if ("iso_a4_210x297mm" in ippSupported) return "iso_a4_210x297mm"
        if ("na_letter_8.5x11in" in ippSupported) return "na_letter_8.5x11in"
        return "iso_a4_210x297mm"
    }
}

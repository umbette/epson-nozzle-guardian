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
    const val TAG_BEGIN_COLLECTION = 0x34
    const val TAG_TEXT_WITH_LANG = 0x35
    const val TAG_NAME_WITH_LANG = 0x36
    const val TAG_END_COLLECTION = 0x37
    const val TAG_TEXT_WITHOUT_LANG = 0x41
    const val TAG_NAME_WITHOUT_LANG = 0x42
    const val TAG_KEYWORD = 0x44
    const val TAG_URI = 0x45
    const val TAG_CHARSET = 0x47
    const val TAG_NATURAL_LANGUAGE = 0x48
    const val TAG_MIME_TYPE = 0x49
    const val TAG_MEMBER_NAME = 0x4A

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
        "media-type-supported",
        "color-supported",
        "print-color-mode-supported",
        "pwg-raster-document-resolution-supported",
        "pwg-raster-document-type-supported",
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
        mediaType: String? = null,
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
        if (!mediaType.isNullOrBlank() && mediaType.lowercase().trim() != "auto") {
            encodeAttr(out, TAG_BEGIN_COLLECTION, "media-col", ByteArray(0))
            encodeAttr(out, TAG_MEMBER_NAME, "", "media-size-name".toByteArray(Charsets.UTF_8))
            encodeAttr(out, TAG_KEYWORD, "", media.toByteArray(Charsets.UTF_8))
            encodeAttr(out, TAG_MEMBER_NAME, "", "media-type".toByteArray(Charsets.UTF_8))
            encodeAttr(out, TAG_KEYWORD, "", mediaType.trim().toByteArray(Charsets.UTF_8))
            encodeAttr(out, TAG_END_COLLECTION, "", ByteArray(0))
        } else {
            encodeAttr(out, TAG_KEYWORD, "media", media.toByteArray(Charsets.UTF_8))
        }
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

    fun parsePwgResolutions(rawValues: List<Any>?): List<Int> {
        val out = linkedSetOf<Int>()
        rawValues.orEmpty().forEach { item ->
            when (item) {
                is IppResolution -> {
                    val dpi = when (item.unit) {
                        3 -> item.xDpi
                        4 -> (item.xDpi * 2.54).toInt()
                        else -> item.xDpi
                    }
                    if (dpi > 0) out.add(dpi)
                }
                is Int -> if (item > 0) out.add(item)
            }
        }
        return out.sorted()
    }

    fun detectInkProfile(model: String, markers: List<MarkerSupply>): Pair<Int, String> {
        val low = model.lowercase()
        val sixTankModels = listOf("et-8550", "et-8500", "l8180", "l8160", "l8050", "l18050", "xp-15000", "xp-8700", "xp-8600", "xp-8500")
        if (sixTankModels.any { it in low }) {
            return maxOf(6, markers.size) to "cmyk6"
        }
        val inkMarkers = markers.filter {
            "waste" !in it.type.lowercase() && "maintenance" !in it.name.lowercase()
        }
        val namesLow = inkMarkers.joinToString(" ") { it.name.lowercase() }
        val hasPhotoGray = listOf("photo black", "gray", "grey", "pb", "gy", "matte black").any { it in namesLow }
        if (inkMarkers.size >= 5 && (hasPhotoGray || inkMarkers.size >= 6)) {
            return inkMarkers.size to "cmyk6"
        }
        return 4 to "cmyk"
    }

    fun negotiateFormat(supportedFormats: List<String>, preferredFormat: String = "auto"): String {
        val normalized = supportedFormats.map { it.lowercase().trim() }.toSet()
        val pref = preferredFormat.lowercase().trim()
        if (pref.isNotBlank() && pref != "auto") {
            if (normalized.isEmpty() || pref in normalized) return pref
        }
        if (normalized.isEmpty()) return "image/pwg-raster"
        for (candidate in FORMAT_PREFERENCE) {
            if (candidate in normalized) return candidate
        }
        return "image/png"
    }

    fun getFallbackFormats(primaryFormat: String, supportedFormats: List<String>): List<String> {
        val primary = primaryFormat.lowercase().trim()
        val normalized = supportedFormats.map { it.lowercase().trim() }.toSet()
        return if (normalized.isNotEmpty()) {
            FORMAT_PREFERENCE.filter { it != primary && it in normalized }
        } else {
            FORMAT_PREFERENCE.filter { it != primary }
        }
    }

    fun negotiatePwgDpi(supportedResolutions: List<Int>, preferredDpi: Int = 300): Int {
        val clean = supportedResolutions.filter { it > 0 }
        if (clean.isEmpty()) return preferredDpi
        if (preferredDpi in clean) return preferredDpi
        for (candidate in listOf(300, 360, 600, 720, 180, 150, 200)) {
            if (candidate in clean) return candidate
        }
        return clean.minByOrNull { kotlin.math.abs(it - preferredDpi) } ?: preferredDpi
    }

    fun negotiatePwgForceRgb(modeCode: String, pwgTypesSupported: List<String>): Boolean {
        if (modeCode.lowercase().trim() !in listOf("k", "black", "bk")) return false
        val types = pwgTypesSupported.map { it.lowercase().trim() }.toSet()
        return types.isNotEmpty() && "sgray_8" !in types && "srgb_8" in types
    }

    fun negotiateColorMode(modeCode: String, printColorModesSupported: List<String>, forceRgb: Boolean = false): String {
        val supported = printColorModesSupported.map { it.lowercase().trim() }.toSet()
        if (modeCode.lowercase().trim() in listOf("k", "black", "bk") && !forceRgb) {
            if (supported.isEmpty() || "monochrome" in supported) return "monochrome"
        }
        if (supported.isEmpty() || "color" in supported) return "color"
        if ("auto" in supported) return "auto"
        return supported.first()
    }

    fun negotiateMediaType(modeCode: String, preferredMediaType: String, mediaTypesSupported: List<String>): String? {
        val pref = preferredMediaType.lowercase().trim()
        if (pref.isNotBlank() && pref != "auto") return pref
        if (modeCode.lowercase().trim() == "pb_gy") {
            val supported = mediaTypesSupported.map { it.lowercase().trim() }
            for (candidate in listOf("photographic-glossy", "photographic-semi-gloss", "photographic-matte", "photographic")) {
                if (candidate in supported) return candidate
            }
        }
        return null
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

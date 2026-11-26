package com.umbette.nozzleguardian.engine

/**
 * Parses HP XML (ProductUsageDyn.xml) and Epson/Canon/Brother HTML EWS pages
 * for channel-decoupled Monochrome vs Color counters in both English and Italian UIs.
 */
object EwsParser {
    val VENDOR_EWS_ENDPOINTS = listOf(
        "/PRESENTATION/HTML/TOP/PRTINFO.HTML",
        "/PRESENTATION/ADVANCED/INFO_PRTINFO.HTML",
        "/DevMgmt/ProductUsageDyn.xml",
        "/device/status.html",
        "/English/pages_dev/status.html",
        "/etc/mnt_info.html",
        "/general/information.html",
        "/status.html",
        "/index.html",
    )

    private val SCRIPT_STYLE_REGEX = Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val MULTI_WS_REGEX = Regex("\\s+")

    private val HP_MONO_REGEX = Regex("<(?:pudyn:)?MonochromeImpressions>(\\d+)</(?:pudyn:)?MonochromeImpressions>")
    private val HP_COLOR_REGEX = Regex("<(?:pudyn:)?ColorImpressions>(\\d+)</(?:pudyn:)?ColorImpressions>")
    private val HP_TOTAL_REGEX = Regex("<(?:pudyn:)?TotalImpressions>(\\d+)</(?:pudyn:)?TotalImpressions>")

    private val MONO_REGEX = Regex(
        "(?:Monochrome|B/?W|Black\\s*(?:&|and)\\s*White|Pagine\\s*(?:in\\s*)?bianco\\s*e\\s*nero|Monocromatic[oe]|B\\s*e\\s*N)(?:\\s*(?:Pages?|Impressions?))?\\s*[:=]?\\s*(\\d+)",
        RegexOption.IGNORE_CASE,
    )
    private val COLOR_REGEX = Regex(
        "(?:Colou?r|Pagine\\s*a\\s*colori|Color[ei])(?:\\s*(?:Pages?|Impressions?))?\\s*[:=]?\\s*(\\d+)",
        RegexOption.IGNORE_CASE,
    )
    private val TOTAL_REGEX = Regex(
        "(?:Total\\s*(?:Number\\s*of\\s*)?Pages?(?:\\s*Printed)?|Total\\s*Impressions|Pagine\\s*totali|Totale\\s*pagine|Numero\\s*totale\\s*di\\s*pagine|Page\\s*Counter)\\s*[:=]?\\s*(\\d+)",
        RegexOption.IGNORE_CASE,
    )
    private val STATUS_REGEX = Regex(
        "Printer\\s*Status\\s*[:=]?\\s*(Available|Idle|Ready|Printing|Pronto|Disponibile)",
        RegexOption.IGNORE_CASE,
    )
    private val KNOWN_PRINTER_REGEX = Regex(
        "(?:ET-\\d+|EcoTank|MegaTank|Smart\\s*Tank|PIXMA|ProductUsageDyn|Product\\s*Status)",
        RegexOption.IGNORE_CASE,
    )

    fun parseWebInfo(rawContent: String): PrinterStatus {
        var monoPages: Int? = null
        var colorPages: Int? = null
        var totalPages: Int? = null

        if ("ProductUsageDyn" in rawContent || "pudyn:" in rawContent) {
            monoPages = HP_MONO_REGEX.find(rawContent)?.groupValues?.get(1)?.toIntOrNull()
            colorPages = HP_COLOR_REGEX.find(rawContent)?.groupValues?.get(1)?.toIntOrNull()
            totalPages = HP_TOTAL_REGEX.find(rawContent)?.groupValues?.get(1)?.toIntOrNull()
        }

        val stripped = SCRIPT_STYLE_REGEX.replace(rawContent, " ")
        val text = MULTI_WS_REGEX.replace(HTML_TAG_REGEX.replace(stripped, " "), " ").trim()

        if (monoPages == null) {
            monoPages = MONO_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (colorPages == null) {
            colorPages = COLOR_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (totalPages == null) {
            totalPages = TOTAL_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (totalPages == null && monoPages != null && colorPages != null) {
            totalPages = monoPages + colorPages
        }

        val hasTelemetry = monoPages != null || colorPages != null || totalPages != null
        val statusMatch = STATUS_REGEX.find(text)
        val isKnownPrinter = KNOWN_PRINTER_REGEX.containsMatchIn(rawContent)

        if (!hasTelemetry && statusMatch == null && !isKnownPrinter) {
            return PrinterStatus(
                online = false,
                state = "unknown",
                errorMessage = "Web endpoint responded without recognizable printer telemetry",
            )
        }

        val low = rawContent.lowercase()
        val makeAndModel = when {
            "epson" in low || "ecotank" in low || "et-" in low -> "Epson EcoTank Series"
            "brother" in low || "dcp-" in low || "mfc-" in low -> "Brother Inkvestment Series"
            "canon" in low || "megatank" in low || "pixma" in low || "maxify" in low -> "Canon MegaTank Series"
            "hp" in low || "smart tank" in low || "productusagedyn" in low || "deskjet" in low -> "HP Smart Tank Series"
            else -> "Inkjet Printer"
        }
        val (kind, headTech) = IppCodec.classifyHardware(makeAndModel + " " + low.take(300), emptyList())
        val state = if (statusMatch != null && "print" in statusMatch.groupValues[1].lowercase()) "printing" else "idle"

        return PrinterStatus(
            online = true,
            monochromePages = monoPages,
            colorPages = colorPages,
            totalPages = totalPages,
            state = state,
            makeAndModel = makeAndModel,
            printerKind = kind,
            headTechnology = headTech,
        )
    }

    private val HP_CONSUMABLE_BLOCK_REGEX = Regex(
        "<(?:\\w+:)?ConsumableInfo\\b[^>]*>([\\s\\S]*?)</(?:\\w+:)?ConsumableInfo>",
        RegexOption.IGNORE_CASE,
    )
    private val HP_LABEL_CODE_REGEX = Regex(
        "<(?:\\w+:)?ConsumableLabelCode>([^<]+)</(?:\\w+:)?ConsumableLabelCode>",
        RegexOption.IGNORE_CASE,
    )
    private val HP_TYPE_ENUM_REGEX = Regex(
        "<(?:\\w+:)?ConsumableTypeEnum>([^<]+)</(?:\\w+:)?ConsumableTypeEnum>",
        RegexOption.IGNORE_CASE,
    )
    private val HP_PCT_LEVEL_REGEX = Regex(
        "<(?:\\w+:)?ConsumablePercentageLevelRemaining>(\\d{1,3})</(?:\\w+:)?ConsumablePercentageLevelRemaining>",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parses HP LEDM ConsumableConfigDyn.xml when explicit 0..100% remaining values are provided.
     * Any consumable without an explicit 0..100 integer is skipped rather than guessed.
     */
    fun parseConsumablesXml(rawContent: String): List<MarkerSupply> {
        if ("ConsumableInfo" !in rawContent && "ConsumableConfigDyn" !in rawContent) return emptyList()
        val out = mutableListOf<MarkerSupply>()
        for (blockMatch in HP_CONSUMABLE_BLOCK_REGEX.findAll(rawContent)) {
            val block = blockMatch.groupValues[1]
            val pct = HP_PCT_LEVEL_REGEX.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (pct !in 0..100) continue
            val labelCode = HP_LABEL_CODE_REGEX.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            if (labelCode.isBlank()) continue
            val typeRaw = HP_TYPE_ENUM_REGEX.find(block)?.groupValues?.get(1)?.trim()?.lowercase().orEmpty()
            val friendlyName = when (labelCode.uppercase()) {
                "K", "BK" -> "Black"
                "C" -> "Cyan"
                "M" -> "Magenta"
                "Y" -> "Yellow"
                "CMY" -> "Tri-Color"
                "PB", "PK" -> "Photo Black"
                "GY" -> "Gray"
                else -> labelCode
            }
            val supplyType = when {
                "toner" in typeRaw -> "toner"
                "waste" in typeRaw -> "waste-ink"
                "ink" in typeRaw -> "ink"
                else -> "ink"
            }
            out.add(
                MarkerSupply(
                    name = friendlyName,
                    level = pct,
                    type = supplyType,
                    colorHex = SnmpCodec.inferMarkerColorHex(friendlyName),
                ),
            )
        }
        return out
    }
}

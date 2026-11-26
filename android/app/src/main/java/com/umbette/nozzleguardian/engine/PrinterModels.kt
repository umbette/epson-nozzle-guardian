package com.umbette.nozzleguardian.engine

enum class PrinterKind {
    INKJET,
    LASER,
}

enum class HeadTechnology(val label: String) {
    PIEZO("Micro-Piezo (Epson / Brother)"),
    THERMAL("Thermal BubbleJet (Canon / HP Safe)"),
    LASER("Laser / Toner"),
}

enum class PurgeMode(val code: String) {
    K("k"),
    CMY("cmy"),
    CMYK("cmyk"),
    CMYK6("cmyk6"),
    PB_GY("pb_gy"),
}

enum class InkProfile(val code: String, val label: String) {
    AUTO("auto", "Serbatoi: Auto"),
    CMYK("cmyk", "4-Tank (CMYK)"),
    CMYK6("cmyk6", "6-Tank Photo (CMY+BK+PB+GY)"),
}

enum class NozzleChannel(val code: String, val displayName: String, val argb: Int) {
    CYAN("cyan", "Ciano (C)", 0xFF00FFFF.toInt()),
    MAGENTA("magenta", "Magenta (M)", 0xFFFF00FF.toInt()),
    YELLOW("yellow", "Giallo (Y)", 0xFFFFFF00.toInt()),
    BLACK("black", "Nero (K/BK)", 0xFF000000.toInt()),
    PHOTO_BLACK("photo_black", "Photo Black (PB)", 0xFF080808.toInt()),
    GRAY("gray", "Gray (GY)", 0xFF808080.toInt()),
}

data class MarkerSupply(
    val name: String,
    val level: Int,
    val type: String = "ink",
    val colorHex: String = "",
) {
    val isWasteSupply: Boolean
        get() {
            val t = type.lowercase()
            val n = name.lowercase()
            return "waste" in t || "maintenance" in n || "waste" in n || "scarico" in n || "manutenzione" in n
        }

    val isOtherConsumable: Boolean
        get() {
            val t = type.lowercase()
            val n = name.lowercase()
            val nonInkTypes = listOf("fuser", "opc", "developer", "transfer", "cleaner", "staples", "binding", "banding", "stitching")
            val nonInkNames = listOf("fuser", "drum", "transfer belt", "imaging unit", "opc")
            return nonInkTypes.any { it in t } || nonInkNames.any { it in n }
        }

    val hasReliableInkLevel: Boolean
        get() {
            val trimmed = name.trim()
            return level in 0..100 &&
                trimmed.any { it.isLetterOrDigit() } &&
                !trimmed.equals("unknown", ignoreCase = true) &&
                !isWasteSupply &&
                !isOtherConsumable
        }

    val isLowInk: Boolean
        get() = hasReliableInkLevel && level <= 15
}

data class PrinterStatus(
    val online: Boolean,
    val monochromePages: Int? = null,
    val colorPages: Int? = null,
    val totalPages: Int? = null,
    val state: String = "unknown",
    val stateReasons: List<String> = emptyList(),
    val errorMessage: String? = null,
    val makeAndModel: String? = null,
    val printerKind: PrinterKind = PrinterKind.INKJET,
    val headTechnology: HeadTechnology = HeadTechnology.PIEZO,
    val mediaDefault: String = "iso_a4_210x297mm",
    val supportedFormats: List<String> = emptyList(),
    val markers: List<MarkerSupply> = emptyList(),
    val endpointPath: String = "/ipp/print",
    val usedTls: Boolean = false,
    val pwgResolutionsSupported: List<Int> = emptyList(),
    val pwgDocumentTypesSupported: List<String> = emptyList(),
    val printColorModesSupported: List<String> = emptyList(),
    val mediaTypesSupported: List<String> = emptyList(),
    val tankCount: Int = 4,
    val inkProfile: String = "cmyk",
) {
    val isMediaEmpty: Boolean
        get() = stateReasons.any { it.contains("media-empty", ignoreCase = true) }

    val hasChannelTelemetry: Boolean
        get() = monochromePages != null && colorPages != null

    val reliableInkMarkers: List<MarkerSupply>
        get() = markers.filter { it.hasReliableInkLevel }

    val lowInkMarkers: List<MarkerSupply>
        get() = reliableInkMarkers.filter { it.isLowInk }
}

data class DiscoveredPrinter(
    val host: String,
    val port: Int = 631,
    val useTls: Boolean = false,
    val endpointPath: String = "/ipp/print",
    val makeAndModel: String,
    val printerKind: PrinterKind = PrinterKind.INKJET,
    val headTechnology: HeadTechnology = HeadTechnology.PIEZO,
    val mediaDefault: String = "iso_a4_210x297mm",
    val supportedFormats: List<String> = emptyList(),
    val source: String = "lan-nsd",
)

data class GuardianState(
    val printerHost: String = "",
    val printerPort: Int = 631,
    val useTls: Boolean = false,
    val pinnedCertSha256: String? = null,
    val mediaSize: String = "auto",
    val preferredFormat: String = "auto",
    val mediaType: String = "auto",
    val inkProfile: String = "auto",
    val inactivityThresholdDays: Int = 5,
    val autoPurgeEnabled: Boolean = false,
    val lastMonoPages: Int? = null,
    val lastColorPages: Int? = null,
    val lastTotalPages: Int? = null,
    val lastMonoActivityIso: String? = null,
    val lastColorActivityIso: String? = null,
    val lastTotalActivityIso: String? = null,
    val lastMonoPurgeIso: String? = null,
    val lastColorPurgeIso: String? = null,
    val lastModel: String? = null,
    val lastPrinterKind: PrinterKind = PrinterKind.INKJET,
    val lastHeadTechnology: HeadTechnology = HeadTechnology.PIEZO,
    val microPurgesCount: Int = 0,
    val surgicalCleansCount: Int = 0,
    val surgicalNozzlesTargeted: Int = 0,
)

data class InkSavingsEstimate(
    val microPurgesCount: Int,
    val surgicalCleansCount: Int,
    val surgicalNozzlesTargeted: Int,
    val usedMl: Double,
    val estimatedSavedMl: Double,
    val avoidedStandardCleans: Double,
    val avoidedDeepCleans: Double,
) {
    val totalInterventions: Int
        get() = microPurgesCount + surgicalCleansCount
}

object InkSavingsCalculator {
    const val MICRO_PURGE_INK_ML = 0.006
    const val SURGICAL_STEP_INK_ML = 0.002
    const val AVOIDED_STANDARD_CLEAN_ML_PER_PURGE = 1.5
    const val AVOIDED_STANDARD_CLEAN_ML_PER_SURGICAL = 3.0
    const val STANDARD_HEAD_CLEAN_ML = 3.0
    const val FACTORY_DEEP_CLEAN_ML = 10.0

    fun estimate(
        microPurgesCount: Int,
        surgicalCleansCount: Int,
        surgicalNozzlesTargeted: Int,
    ): InkSavingsEstimate {
        val purges = microPurgesCount.coerceAtLeast(0)
        val surgical = surgicalCleansCount.coerceAtLeast(0)
        val steps = surgicalNozzlesTargeted.coerceAtLeast(surgical)
        val used = purges * MICRO_PURGE_INK_ML + steps * SURGICAL_STEP_INK_ML
        val grossAvoided = purges * AVOIDED_STANDARD_CLEAN_ML_PER_PURGE +
            surgical * AVOIDED_STANDARD_CLEAN_ML_PER_SURGICAL
        val saved = (grossAvoided - used).coerceAtLeast(0.0)
        return InkSavingsEstimate(
            microPurgesCount = purges,
            surgicalCleansCount = surgical,
            surgicalNozzlesTargeted = if (surgical == 0) 0 else steps,
            usedMl = used,
            estimatedSavedMl = saved,
            avoidedStandardCleans = saved / STANDARD_HEAD_CLEAN_ML,
            avoidedDeepCleans = saved / FACTORY_DEEP_CLEAN_ML,
        )
    }

    fun estimate(state: GuardianState): InkSavingsEstimate =
        estimate(state.microPurgesCount, state.surgicalCleansCount, state.surgicalNozzlesTargeted)
}

enum class DormancyDecisionType {
    UNCONFIGURED,
    OFFLINE,
    LASER_ABORTED,
    MEDIA_EMPTY_ABORTED,
    UNSUPPORTED_COUNTERS,
    NO_ACTION_ACTIVE,
    AUTO_PURGE_DISABLED,
    PURGE_REQUIRED,
}

data class DormancyDecision(
    val updatedState: GuardianState,
    val type: DormancyDecisionType,
    val monoDormant: Boolean = false,
    val colorDormant: Boolean = false,
    val totalDormant: Boolean = false,
    val recommendedPurgeMode: PurgeMode? = null,
    val message: String,
)

data class PurgeOutcome(
    val success: Boolean,
    val message: String,
    val mode: String,
    val docFormat: String = "",
    val effectiveBursts: Int = 0,
)

data class ChannelScanResult(
    val present: Boolean,
    val healthy: Boolean?,
    val healthPct: Double?,
    val missingSteps: List<Int>,
)

data class NozzleScanReport(
    val success: Boolean,
    val error: String? = null,
    val overallHealthPct: Double = 0.0,
    val sheetMode: String = "unknown",
    val channels: Map<String, ChannelScanResult> = emptyMap(),
    val missingRungs: List<String> = emptyList(),
    val recommendedAction: String = "none",
    val recommendedActions: List<String> = emptyList(),
    val targetChannel: String? = null,
    val targetStep: Int? = null,
    val message: String = "",
)

enum class PrinterSilhouette(val label: String) {
    TANK_4_COMPACT("Ink Tank 4-Color"),
    TANK_4_ADF("Multifunzione ADF"),
    TANK_6_PHOTO("6-Tank Photo"),
    CARTRIDGE_COMPACT("Inkjet Compatta"),
    LASER_TONER("Laser / Toner"),
}

enum class BrandBadge(val displayName: String, val accentHex: Long) {
    EPSON("EPSON", 0xFF0055B8),
    CANON("CANON", 0xFFDC2626),
    HP("HP", 0xFF0284C7),
    BROTHER("BROTHER", 0xFF4F46E5),
    GENERIC("IPP", 0xFF64748B),
}

object PrinterClassification {
    fun inferVisuals(
        makeAndModel: String?,
        tankCount: Int = 4,
        kind: PrinterKind = PrinterKind.INKJET,
    ): Pair<BrandBadge, PrinterSilhouette> {
        val low = (makeAndModel ?: "").lowercase()
        val brand = when {
            "epson" in low || "et-" in low || "ecotank" in low || "l81" in low || "l31" in low -> BrandBadge.EPSON
            "canon" in low || "pixma" in low || "maxify" in low || "megatank" in low -> BrandBadge.CANON
            "hp " in low || "hewlett" in low || "smart tank" in low || "deskjet" in low || "officejet" in low || "envy" in low -> BrandBadge.HP
            "brother" in low || "dcp-" in low || "mfc-" in low -> BrandBadge.BROTHER
            else -> BrandBadge.GENERIC
        }
        val silhouette = when {
            kind == PrinterKind.LASER -> PrinterSilhouette.LASER_TONER
            tankCount >= 6 || listOf("et-8550", "et-8500", "l8180", "l8160", "xp-15000").any { it in low } -> PrinterSilhouette.TANK_6_PHOTO
            listOf("et-48", "et-38", "et-47", "maxify", "officejet", "mfc-").any { it in low } -> PrinterSilhouette.TANK_4_ADF
            listOf("et-", "ecotank", "megatank", "smart tank", "g30", "g20", "g60", "inkvestment", "l31").any { it in low } -> PrinterSilhouette.TANK_4_COMPACT
            else -> PrinterSilhouette.CARTRIDGE_COMPACT
        }
        return brand to silhouette
    }

    fun recommendedThresholdDays(
        kind: PrinterKind,
        headTechnology: HeadTechnology,
        tankCount: Int = 4,
    ): Int = when {
        kind == PrinterKind.LASER -> 0
        headTechnology == HeadTechnology.THERMAL -> 4
        tankCount >= 6 -> 4
        else -> 6
    }
}

data class LocationGroup(
    val id: String,
    val name: String,
    val wifiSsid: String = "",
    val iconKey: String = "home",
    val networkFingerprint: String = "",
    val networkLabel: String = "",
)

data class PrinterProfile(
    val id: String,
    val locationId: String,
    val displayName: String,
    val brand: BrandBadge = BrandBadge.GENERIC,
    val silhouette: PrinterSilhouette = PrinterSilhouette.TANK_4_COMPACT,
    val state: GuardianState = GuardianState(),
)

data class MultiPrinterWorkspace(
    val locations: List<LocationGroup> = listOf(LocationGroup(id = "loc_home", name = "Casa")),
    val printers: List<PrinterProfile> = emptyList(),
    val activePrinterId: String? = null,
) {
    val activePrinter: PrinterProfile?
        get() = printers.firstOrNull { it.id == activePrinterId } ?: printers.firstOrNull()
}

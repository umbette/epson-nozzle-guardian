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
}

enum class NozzleChannel(val code: String, val displayName: String, val argb: Int) {
    CYAN("cyan", "Ciano (C)", 0xFF00FFFF.toInt()),
    MAGENTA("magenta", "Magenta (M)", 0xFFFF00FF.toInt()),
    YELLOW("yellow", "Giallo (Y)", 0xFFFFFF00.toInt()),
    BLACK("black", "Nero (K)", 0xFF000000.toInt()),
}

data class MarkerSupply(
    val name: String,
    val level: Int,
    val type: String = "ink",
    val colorHex: String = "",
)

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
) {
    val isMediaEmpty: Boolean
        get() = stateReasons.any { it.contains("media-empty", ignoreCase = true) }

    val hasChannelTelemetry: Boolean
        get() = monochromePages != null && colorPages != null
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
)

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

package com.umbette.nozzleguardian.engine

enum class PlanTier(val displayName: String) {
    FREE("Free (Locale)"),
    PRO("Pro"),
}

enum class ProFeature(val title: String, val description: String) {
    AUTO_PURGE("Auto-spurgo automatico", "Controllo e micro-spurgo autonomo in background via WorkManager"),
    CAMERA_SCANNER("Scanner ottico fotocamera", "Rilevamento automatico gradini mancanti da foto + target chirurgico"),
    MULTI_PRINTER_LOCATIONS("Multi-stampante & Location", "Gestione illimitata di stampanti raggruppate per Casa/Ufficio e Wi-Fi"),
    CLOUD_SYNC("Account & Sync multi-dispositivo", "Backup e sincronizzazione profili stampanti tra smartphone e homelab"),
}

data class EntitlementState(
    val tier: PlanTier = PlanTier.FREE,
    val source: String = "free",
    val productId: String? = null,
    val isPlayStoreInstall: Boolean = false,
    val previewProEnabled: Boolean = false,
) {
    val isProActive: Boolean
        get() = tier == PlanTier.PRO || (!isPlayStoreInstall && previewProEnabled)

    fun hasAccess(feature: ProFeature, currentPrinterCount: Int = 1, currentLocationCount: Int = 1): Boolean {
        if (isProActive) return true
        return when (feature) {
            ProFeature.MULTI_PRINTER_LOCATIONS -> currentPrinterCount <= 1 && currentLocationCount <= 1
            ProFeature.AUTO_PURGE, ProFeature.CAMERA_SCANNER, ProFeature.CLOUD_SYNC -> false
        }
    }
}

object EntitlementPolicy {
    const val SKU_PRO_LIFETIME = "nozzle_guardian_pro_lifetime"
    // Legacy subscription SKUs kept only for honoring pre-existing Play Store subscribers.
    const val SKU_PRO_YEARLY = "nozzle_guardian_pro_yearly"
    const val SKU_PRO_MONTHLY = "nozzle_guardian_pro_monthly"

    val SUBSCRIPTION_SKUS = emptyList<String>()
    val LEGACY_SUBSCRIPTION_SKUS = listOf(SKU_PRO_YEARLY, SKU_PRO_MONTHLY)
    val ONE_TIME_SKUS = listOf(SKU_PRO_LIFETIME)

    fun canAddPrinter(entitlement: EntitlementState, workspace: MultiPrinterWorkspace): Boolean =
        entitlement.isProActive || workspace.printers.isEmpty()

    fun canAddLocation(entitlement: EntitlementState, workspace: MultiPrinterWorkspace): Boolean =
        entitlement.isProActive || workspace.locations.isEmpty()
}

data class TelemetrySettings(
    val optIn: Boolean = false,
    val installId: String = "",
    val endpointUrl: String = "",
)

/**
 * Privacy-preserving anonymized telemetry serializer (Opt-In only).
 * Never includes IPs, Wi-Fi SSIDs, emails, document filenames, photos, or printer serial numbers.
 */
object TelemetryPayloadBuilder {
    private fun esc(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    fun sanitizeModelFamily(makeAndModel: String?): String {
        val raw = (makeAndModel ?: "Generic IPP").trim()
        // Strip potential trailing serial-like tokens inside brackets
        return raw.replace(Regex("\\([^)]*\\)"), "").trim().ifBlank { "Generic IPP" }
    }

    fun buildEventJson(
        installId: String,
        appVersion: String,
        tier: PlanTier,
        brand: BrandBadge,
        modelFamily: String?,
        headTechnology: HeadTechnology,
        inkProfile: String,
        preferredFormat: String,
        inactivityDays: Int,
        eventType: String,
        outcomeSuccess: Boolean,
        healthBeforePct: Int? = null,
        healthAfterPct: Int? = null,
    ): String {
        val sb = StringBuilder(256)
        sb.append("{\"schema\":1")
        sb.append(",\"installId\":\"").append(esc(installId)).append("\"")
        sb.append(",\"appVersion\":\"").append(esc(appVersion)).append("\"")
        sb.append(",\"tier\":\"").append(tier.name).append("\"")
        sb.append(",\"brand\":\"").append(brand.name).append("\"")
        sb.append(",\"modelFamily\":\"").append(esc(sanitizeModelFamily(modelFamily))).append("\"")
        sb.append(",\"headTech\":\"").append(headTechnology.name).append("\"")
        sb.append(",\"inkProfile\":\"").append(esc(inkProfile)).append("\"")
        sb.append(",\"format\":\"").append(esc(preferredFormat)).append("\"")
        sb.append(",\"thresholdDays\":").append(inactivityDays)
        sb.append(",\"event\":\"").append(esc(eventType)).append("\"")
        sb.append(",\"success\":").append(outcomeSuccess)
        if (healthBeforePct != null) sb.append(",\"healthBeforePct\":").append(healthBeforePct)
        if (healthAfterPct != null) sb.append(",\"healthAfterPct\":").append(healthAfterPct)
        sb.append('}')
        return sb.toString()
    }
}

data class AccountSession(
    val signedIn: Boolean = false,
    val email: String = "",
    val displayName: String = "",
    val pairingToken: String = "",
    val syncServerUrl: String = "",
    val lastSyncedIso: String? = null,
)

/**
 * Dependency-free deterministic workspace & account snapshot serializer for cross-device backup/sync.
 */
object SyncSnapshotCodec {
    private fun esc(value: String): String = buildString(value.length + 4) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    fun exportSnapshotJson(workspace: MultiPrinterWorkspace, account: AccountSession): String {
        val sb = StringBuilder(1024)
        sb.append("{\"schema\":1,")
        sb.append("\"account\":{\"signedIn\":").append(account.signedIn)
        sb.append(",\"email\":\"").append(esc(account.email))
        sb.append("\",\"displayName\":\"").append(esc(account.displayName))
        sb.append("\",\"syncServerUrl\":\"").append(esc(account.syncServerUrl)).append("\"},")
        sb.append("\"activePrinterId\":").append(
            workspace.activePrinterId?.let { "\"${esc(it)}\"" } ?: "null"
        ).append(",")
        sb.append("\"locations\":[")
        workspace.locations.forEachIndexed { idx, loc ->
            if (idx > 0) sb.append(',')
            sb.append("{\"id\":\"").append(esc(loc.id))
            sb.append("\",\"name\":\"").append(esc(loc.name))
            sb.append("\",\"wifiSsid\":\"").append(esc(loc.wifiSsid))
            sb.append("\",\"iconKey\":\"").append(esc(loc.iconKey))
            sb.append("\",\"networkFingerprint\":\"").append(esc(loc.networkFingerprint))
            sb.append("\",\"networkLabel\":\"").append(esc(loc.networkLabel)).append("\"}")
        }
        sb.append("],\"printers\":[")
        workspace.printers.forEachIndexed { idx, p ->
            if (idx > 0) sb.append(',')
            sb.append("{\"id\":\"").append(esc(p.id))
            sb.append("\",\"locationId\":\"").append(esc(p.locationId))
            sb.append("\",\"displayName\":\"").append(esc(p.displayName))
            sb.append("\",\"brand\":\"").append(p.brand.name)
            sb.append("\",\"silhouette\":\"").append(p.silhouette.name)
            sb.append("\",\"host\":\"").append(esc(p.state.printerHost))
            sb.append("\",\"port\":").append(p.state.printerPort)
            sb.append(",\"useTls\":").append(p.state.useTls)
            sb.append(",\"preferredFormat\":\"").append(esc(p.state.preferredFormat))
            sb.append("\",\"mediaType\":\"").append(esc(p.state.mediaType))
            sb.append("\",\"inkProfile\":\"").append(esc(p.state.inkProfile))
            sb.append("\",\"thresholdDays\":").append(p.state.inactivityThresholdDays)
            sb.append(",\"autoPurgeEnabled\":").append(p.state.autoPurgeEnabled)
            sb.append("}")
        }
        sb.append("]}")
        return sb.toString()
    }
}

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
    const val SKU_PRO_MONTHLY = "nozzle_guardian_pro_monthly"
    const val SKU_PRO_YEARLY = "nozzle_guardian_pro_yearly"
    const val SKU_PRO_LIFETIME = "nozzle_guardian_pro_lifetime"

    val SUBSCRIPTION_SKUS = listOf(SKU_PRO_MONTHLY, SKU_PRO_YEARLY)
    val ONE_TIME_SKUS = listOf(SKU_PRO_LIFETIME)

    fun canAddPrinter(entitlement: EntitlementState, workspace: MultiPrinterWorkspace): Boolean =
        entitlement.isProActive || workspace.printers.isEmpty()

    fun canAddLocation(entitlement: EntitlementState, workspace: MultiPrinterWorkspace): Boolean =
        entitlement.isProActive || workspace.locations.isEmpty()
}

data class AccountSession(
    val signedIn: Boolean = false,
    val email: String = "",
    val displayName: String = "",
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
            sb.append("\",\"iconKey\":\"").append(esc(loc.iconKey)).append("\"}")
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

package com.umbette.nozzleguardian.engine

/**
 * Pure-Kotlin manager for Multi-Printer Profiles and Home/Office Location groups.
 * Handles SSID-aware location filtering so background workers skip unreachable remote locations.
 */
object WorkspaceEngine {
    const val DEFAULT_LOCATION_ID = "loc_home"

    fun ensureDefaultLocation(workspace: MultiPrinterWorkspace): MultiPrinterWorkspace {
        if (workspace.locations.isNotEmpty()) return workspace
        return workspace.copy(locations = listOf(LocationGroup(id = DEFAULT_LOCATION_ID, name = "Casa")))
    }

    fun addLocation(
        workspace: MultiPrinterWorkspace,
        name: String,
        wifiSsid: String = "",
        iconKey: String = "home",
        id: String = "loc_${System.currentTimeMillis()}",
    ): MultiPrinterWorkspace {
        val cleanName = name.trim().ifBlank { "Nuova Casa" }
        val newLoc = LocationGroup(
            id = id,
            name = cleanName,
            wifiSsid = wifiSsid.trim(),
            iconKey = iconKey,
        )
        return ensureDefaultLocation(workspace).copy(locations = workspace.locations + newLoc)
    }

    fun removeLocation(workspace: MultiPrinterWorkspace, locationId: String): MultiPrinterWorkspace {
        if (workspace.locations.size <= 1) return workspace
        val fallbackId = workspace.locations.first { it.id != locationId }.id
        val reassigned = workspace.printers.map { p ->
            if (p.locationId == locationId) p.copy(locationId = fallbackId) else p
        }
        return workspace.copy(
            locations = workspace.locations.filterNot { it.id == locationId },
            printers = reassigned,
        )
    }

    fun upsertPrinter(
        workspace: MultiPrinterWorkspace,
        profile: PrinterProfile,
        setActive: Boolean = true,
    ): MultiPrinterWorkspace {
        val base = ensureDefaultLocation(workspace)
        val validLocId = if (base.locations.any { it.id == profile.locationId }) {
            profile.locationId
        } else {
            base.locations.first().id
        }
        val normalized = profile.copy(locationId = validLocId)
        val idx = base.printers.indexOfFirst { it.id == normalized.id }
        val nextPrinters = if (idx >= 0) {
            base.printers.toMutableList().apply { set(idx, normalized) }
        } else {
            base.printers + normalized
        }
        val nextActive = if (setActive || base.activePrinterId == null) normalized.id else base.activePrinterId
        return base.copy(printers = nextPrinters, activePrinterId = nextActive)
    }

    fun selectActivePrinter(workspace: MultiPrinterWorkspace, printerId: String): MultiPrinterWorkspace {
        if (workspace.printers.none { it.id == printerId }) return workspace
        return workspace.copy(activePrinterId = printerId)
    }

    fun removePrinter(workspace: MultiPrinterWorkspace, printerId: String): MultiPrinterWorkspace {
        val remaining = workspace.printers.filterNot { it.id == printerId }
        val nextActive = if (workspace.activePrinterId == printerId) {
            remaining.firstOrNull()?.id
        } else {
            workspace.activePrinterId
        }
        return workspace.copy(printers = remaining, activePrinterId = nextActive)
    }

    fun printersForCurrentWifi(
        workspace: MultiPrinterWorkspace,
        currentWifiSsid: String?,
    ): List<PrinterProfile> {
        val cleanSsid = currentWifiSsid?.trim()?.removePrefix("\"")?.removeSuffix("\"").orEmpty()
        val locById = workspace.locations.associateBy { it.id }
        return workspace.printers.filter { printer ->
            if (printer.state.printerHost.isBlank()) return@filter false
            val locSsid = locById[printer.locationId]?.wifiSsid?.trim().orEmpty()
            locSsid.isBlank() || cleanSsid.isBlank() || locSsid.equals(cleanSsid, ignoreCase = true)
        }
    }

    fun updatePrinterStatusVisuals(
        profile: PrinterProfile,
        status: PrinterStatus,
    ): PrinterProfile {
        val (brand, silhouette) = PrinterClassification.inferVisuals(
            makeAndModel = status.makeAndModel ?: profile.state.lastModel ?: profile.displayName,
            tankCount = status.tankCount,
            kind = status.printerKind,
        )
        val nextName = if (profile.displayName.startsWith("Stampante") && !status.makeAndModel.isNullOrBlank()) {
            status.makeAndModel
        } else {
            profile.displayName
        }
        return profile.copy(displayName = nextName, brand = brand, silhouette = silhouette)
    }
}

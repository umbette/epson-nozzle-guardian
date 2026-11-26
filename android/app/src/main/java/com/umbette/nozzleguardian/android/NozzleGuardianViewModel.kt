package com.umbette.nozzleguardian.android

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.umbette.nozzleguardian.BuildConfig
import com.umbette.nozzleguardian.R
import com.umbette.nozzleguardian.engine.AccountSession
import com.umbette.nozzleguardian.engine.BrandBadge
import com.umbette.nozzleguardian.engine.DiscoveredPrinter
import com.umbette.nozzleguardian.engine.DormancyDecisionType
import com.umbette.nozzleguardian.engine.DormancyEngine
import com.umbette.nozzleguardian.engine.EntitlementPolicy
import com.umbette.nozzleguardian.engine.EntitlementState
import com.umbette.nozzleguardian.engine.GuardianState
import com.umbette.nozzleguardian.engine.HeadTechnology
import com.umbette.nozzleguardian.engine.MultiPrinterWorkspace
import com.umbette.nozzleguardian.engine.NozzleChannel
import com.umbette.nozzleguardian.engine.NozzleScanReport
import com.umbette.nozzleguardian.engine.PlanTier
import com.umbette.nozzleguardian.engine.PrinterClassification
import com.umbette.nozzleguardian.engine.PrinterKind
import com.umbette.nozzleguardian.engine.PrinterProfile
import com.umbette.nozzleguardian.engine.PrinterStatus
import com.umbette.nozzleguardian.engine.ProFeature
import com.umbette.nozzleguardian.engine.PurgeMode
import com.umbette.nozzleguardian.engine.SyncSnapshotCodec
import com.umbette.nozzleguardian.engine.TelemetryPayloadBuilder
import com.umbette.nozzleguardian.engine.TelemetrySettings
import com.umbette.nozzleguardian.engine.WorkspaceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

enum class BannerSeverity { INFO, SUCCESS, WARNING, ERROR }

data class UiBanner(
    val severity: BannerSeverity,
    @StringRes val messageRes: Int? = null,
    val formatArgs: List<Any> = emptyList(),
    val fallbackText: String = "",
)

data class MainUiState(
    val workspace: MultiPrinterWorkspace = MultiPrinterWorkspace(),
    val detailPrinterId: String? = null,
    val printerStatuses: Map<String, PrinterStatus> = emptyMap(),
    val entitlement: EntitlementState = EntitlementState(),
    val account: AccountSession = AccountSession(),
    val telemetry: TelemetrySettings = TelemetrySettings(),
    val billing: BillingUiState = BillingUiState(),
    val discovered: List<DiscoveredPrinter> = emptyList(),
    val scanReport: NozzleScanReport? = null,
    val isDiscovering: Boolean = false,
    val isBusy: Boolean = false,
    val banner: UiBanner? = null,
    val paywallFeaturePrompt: ProFeature? = null,
    val laserNoticePrinterId: String? = null,
) {
    val activePrinter: PrinterProfile?
        get() = detailPrinterId?.let { id -> workspace.printers.firstOrNull { it.id == id } }
            ?: workspace.activePrinter

    val config: GuardianState
        get() = activePrinter?.state ?: GuardianState()

    val status: PrinterStatus?
        get() = activePrinter?.id?.let { printerStatuses[it] }
}

class NozzleGuardianViewModel(application: Application) : AndroidViewModel(application) {
    private val store = StateStore(application)
    private val client = LanPrinterClient(application, store)
    private val acknowledgedLaserIds = mutableSetOf<String>()

    private val billingManager = BillingManager(application) { tier, source, sku ->
        val nextEnt = store.loadEntitlement().copy(tier = tier, source = source, productId = sku)
        store.saveEntitlement(nextEnt)
        _uiState.update { it.copy(entitlement = nextEnt) }
    }

    private val initialWorkspace = store.loadWorkspace()
    private val _uiState = MutableStateFlow(
        MainUiState(
            workspace = initialWorkspace,
            entitlement = store.loadEntitlement(),
            account = store.loadAccount(),
            telemetry = store.loadTelemetrySettings(),
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            billingManager.billingState.collect { bState ->
                _uiState.update { it.copy(billing = bState) }
            }
        }
        billingManager.start()
        PurgeCheckWorker.schedulePeriodic(application, initialWorkspace.printers.any { it.state.autoPurgeEnabled })
        if (initialWorkspace.activePrinter?.state?.printerHost?.isNotBlank() == true) {
            refreshStatus(printerId = initialWorkspace.activePrinter?.id, runDormancyCheck = false)
        }
    }

    override fun onCleared() {
        billingManager.end()
        super.onCleared()
    }

    fun openPrinterDetail(printerId: String) {
        val ws = WorkspaceEngine.selectActivePrinter(store.loadWorkspace(), printerId)
        store.saveWorkspace(ws)
        _uiState.update { it.copy(workspace = ws, detailPrinterId = printerId) }
        val target = ws.printers.firstOrNull { it.id == printerId }
        if (target?.state?.printerHost?.isNotBlank() == true && !_uiState.value.printerStatuses.containsKey(printerId)) {
            refreshStatus(printerId = printerId, runDormancyCheck = false)
        }
    }

    fun closePrinterDetail() {
        _uiState.update { it.copy(detailPrinterId = null) }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(banner = null) }
    }

    fun dismissLaserNotice() {
        _uiState.update { it.copy(laserNoticePrinterId = null) }
    }

    fun dismissPaywall() {
        _uiState.update { it.copy(paywallFeaturePrompt = null) }
    }

    fun requestPaywall(feature: ProFeature) {
        _uiState.update { it.copy(paywallFeaturePrompt = feature) }
    }

    fun launchProPurchase(activity: Activity, offer: BillingProductOffer) {
        billingManager.launchPurchase(activity, offer)
    }

    fun restorePurchases() {
        billingManager.restorePurchases()
    }

    fun setPreviewProEnabled(enabled: Boolean) {
        val current = store.loadEntitlement()
        if (current.isPlayStoreInstall) return
        val updated = current.copy(previewProEnabled = enabled)
        store.saveEntitlement(updated)
        _uiState.update {
            it.copy(
                entitlement = updated,
                paywallFeaturePrompt = null,
                banner = UiBanner(
                    severity = BannerSeverity.INFO,
                    messageRes = if (enabled) R.string.banner_preview_on else R.string.banner_preview_off,
                ),
            )
        }
    }

    fun discoverPrinters() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDiscovering = true,
                    banner = UiBanner(BannerSeverity.INFO, R.string.banner_scanning_mdns),
                )
            }
            val list = client.discoverLanPrinters()
            _uiState.update {
                it.copy(
                    isDiscovering = false,
                    discovered = list,
                    banner = if (list.isEmpty()) {
                        UiBanner(BannerSeverity.WARNING, R.string.banner_mdns_none)
                    } else {
                        UiBanner(BannerSeverity.SUCCESS, R.string.banner_mdns_found, listOf(list.size.toString()))
                    },
                )
            }
        }
    }

    fun selectDiscoveredPrinter(printer: DiscoveredPrinter, targetLocationId: String? = null) {
        val ws = store.loadWorkspace()
        val ent = store.loadEntitlement()
        val existing = ws.printers.firstOrNull { it.state.printerHost.equals(printer.host, ignoreCase = true) }
        if (existing != null) {
            openPrinterDetail(existing.id)
            if (printer.printerKind == PrinterKind.LASER) {
                triggerLaserNoticeIfNew(existing.id)
            }
            return
        }
        val active = ws.activePrinter
        if (active != null && !EntitlementPolicy.canAddPrinter(ent, ws)) {
            val (brand, silhouette) = PrinterClassification.inferVisuals(printer.makeAndModel, kind = printer.printerKind)
            val suggestedDays = PrinterClassification.recommendedThresholdDays(printer.printerKind, printer.headTechnology)
                .coerceAtLeast(3)
            val updated = active.copy(
                displayName = printer.makeAndModel,
                brand = brand,
                silhouette = silhouette,
                state = active.state.copy(
                    printerHost = printer.host,
                    printerPort = printer.port,
                    useTls = printer.useTls,
                    pinnedCertSha256 = null,
                    lastPrinterKind = printer.printerKind,
                    lastHeadTechnology = printer.headTechnology,
                    inactivityThresholdDays = suggestedDays,
                ),
            )
            val nextWs = WorkspaceEngine.upsertPrinter(ws, updated, setActive = true)
            store.saveWorkspace(nextWs)
            _uiState.update {
                it.copy(
                    workspace = nextWs,
                    detailPrinterId = updated.id,
                    banner = UiBanner(BannerSeverity.SUCCESS, R.string.banner_printer_updated, listOf(printer.makeAndModel)),
                )
            }
            if (printer.printerKind == PrinterKind.LASER) {
                triggerLaserNoticeIfNew(updated.id)
            }
            refreshStatus(printerId = updated.id, runDormancyCheck = false)
            return
        }
        addPrinterProfile(
            locationId = targetLocationId ?: active?.locationId ?: ws.locations.first().id,
            displayName = printer.makeAndModel,
            host = printer.host,
            port = printer.port,
            useTls = printer.useTls,
            kind = printer.printerKind,
            headTechnology = printer.headTechnology,
            openDetail = true,
        )
    }

    fun addLocationGroup(name: String, wifiSsid: String, iconKey: String = "home") {
        val ws = store.loadWorkspace()
        val ent = store.loadEntitlement()
        if (!EntitlementPolicy.canAddLocation(ent, ws) && ws.locations.isNotEmpty()) {
            requestPaywall(ProFeature.MULTI_PRINTER_LOCATIONS)
            return
        }
        val nextWs = WorkspaceEngine.addLocation(ws, name = name, wifiSsid = wifiSsid, iconKey = iconKey)
        store.saveWorkspace(nextWs)
        _uiState.update {
            it.copy(
                workspace = nextWs,
                banner = UiBanner(BannerSeverity.SUCCESS, R.string.banner_location_added, listOf(name.trim())),
            )
        }
    }

    fun updateLocationGroup(locationId: String, name: String, wifiSsid: String, iconKey: String) {
        val nextWs = WorkspaceEngine.updateLocation(store.loadWorkspace(), locationId, name, wifiSsid, iconKey)
        store.saveWorkspace(nextWs)
        _uiState.update {
            it.copy(
                workspace = nextWs,
                banner = UiBanner(BannerSeverity.SUCCESS, R.string.banner_location_updated, listOf(name.trim())),
            )
        }
    }

    fun removeLocationGroup(locationId: String) {
        val nextWs = WorkspaceEngine.removeLocation(store.loadWorkspace(), locationId)
        store.saveWorkspace(nextWs)
        _uiState.update {
            it.copy(
                workspace = nextWs,
                banner = UiBanner(BannerSeverity.INFO, R.string.banner_location_removed),
            )
        }
    }

    fun addPrinterProfile(
        locationId: String,
        displayName: String,
        host: String,
        port: Int = 631,
        useTls: Boolean = true,
        kind: PrinterKind = PrinterKind.INKJET,
        headTechnology: HeadTechnology = HeadTechnology.PIEZO,
        openDetail: Boolean = true,
    ) {
        val ws = store.loadWorkspace()
        val ent = store.loadEntitlement()
        if (!EntitlementPolicy.canAddPrinter(ent, ws)) {
            requestPaywall(ProFeature.MULTI_PRINTER_LOCATIONS)
            return
        }
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) return
        val label = displayName.trim().ifBlank { cleanHost }
        val (brand, silhouette) = PrinterClassification.inferVisuals(label, kind = kind)
        val suggestedDays = PrinterClassification.recommendedThresholdDays(kind, headTechnology).coerceAtLeast(3)
        val profile = PrinterProfile(
            id = "prt_${System.currentTimeMillis()}",
            locationId = locationId,
            displayName = label,
            brand = brand,
            silhouette = silhouette,
            state = GuardianState(
                printerHost = cleanHost,
                printerPort = port.coerceIn(1, 65535),
                useTls = useTls,
                lastPrinterKind = kind,
                lastHeadTechnology = headTechnology,
                inactivityThresholdDays = suggestedDays,
            ),
        )
        val nextWs = WorkspaceEngine.upsertPrinter(ws, profile, setActive = true)
        store.saveWorkspace(nextWs)
        _uiState.update {
            it.copy(
                workspace = nextWs,
                detailPrinterId = if (openDetail) profile.id else it.detailPrinterId,
                banner = UiBanner(BannerSeverity.SUCCESS, R.string.banner_printer_added, listOf(label)),
            )
        }
        if (kind == PrinterKind.LASER) {
            triggerLaserNoticeIfNew(profile.id)
        }
        refreshStatus(printerId = profile.id, runDormancyCheck = false)
    }

    fun removePrinterProfile(printerId: String) {
        val nextWs = WorkspaceEngine.removePrinter(store.loadWorkspace(), printerId)
        store.saveWorkspace(nextWs)
        PurgeCheckWorker.schedulePeriodic(getApplication(), nextWs.printers.any { it.state.autoPurgeEnabled })
        _uiState.update {
            it.copy(
                workspace = nextWs,
                detailPrinterId = if (it.detailPrinterId == printerId) null else it.detailPrinterId,
                printerStatuses = it.printerStatuses - printerId,
                banner = UiBanner(BannerSeverity.INFO, R.string.banner_printer_removed),
            )
        }
    }

    fun updatePrinterById(
        printerId: String,
        host: String,
        port: Int,
        useTls: Boolean,
        thresholdDays: Int,
        autoPurgeEnabled: Boolean,
        preferredFormat: String,
        mediaType: String,
        inkProfile: String,
        locationId: String,
    ) {
        val ent = store.loadEntitlement()
        val effectiveAutoPurge = if (autoPurgeEnabled && !ent.hasAccess(ProFeature.AUTO_PURGE)) {
            requestPaywall(ProFeature.AUTO_PURGE)
            false
        } else {
            autoPurgeEnabled
        }

        val ws = store.loadWorkspace()
        val target = ws.printers.firstOrNull { it.id == printerId } ?: return
        val current = target.state
        val cleanHost = host.trim()
        val pinReset = if (current.printerHost != cleanHost) null else current.pinnedCertSha256
        val updatedState = current.copy(
            printerHost = cleanHost,
            printerPort = port.coerceIn(1, 65535),
            useTls = useTls,
            pinnedCertSha256 = pinReset,
            preferredFormat = preferredFormat,
            mediaType = mediaType,
            inkProfile = inkProfile,
            inactivityThresholdDays = thresholdDays.coerceIn(1, 30),
            autoPurgeEnabled = effectiveAutoPurge,
        )
        val nextProfile = target.copy(locationId = locationId, state = updatedState)
        val nextWs = WorkspaceEngine.upsertPrinter(ws, nextProfile, setActive = true)
        store.saveWorkspace(nextWs)
        PurgeCheckWorker.schedulePeriodic(getApplication(), nextWs.printers.any { it.state.autoPurgeEnabled })

        _uiState.update {
            it.copy(
                workspace = nextWs,
                banner = UiBanner(BannerSeverity.SUCCESS, R.string.banner_printer_saved, listOf(cleanHost.ifBlank { target.displayName })),
            )
        }
        if (cleanHost.isNotBlank()) {
            refreshStatus(printerId = printerId, runDormancyCheck = false)
        }
    }

    fun refreshStatus(printerId: String? = _uiState.value.activePrinter?.id, runDormancyCheck: Boolean = true) {
        viewModelScope.launch {
            val wsNow = store.loadWorkspace()
            val target = printerId?.let { id -> wsNow.printers.firstOrNull { it.id == id } } ?: wsNow.activePrinter
            val cfg = target?.state
            if (target == null || cfg == null || cfg.printerHost.isBlank()) {
                _uiState.update { it.copy(banner = UiBanner(BannerSeverity.WARNING, R.string.banner_configure_first)) }
                return@launch
            }
            _uiState.update { it.copy(isBusy = true, banner = UiBanner(BannerSeverity.INFO, R.string.banner_checking_printer)) }
            val st = client.getStatus(cfg.printerHost, cfg.printerPort, cfg.useTls, cfg.mediaSize)
            val decision = DormancyEngine.evaluate(cfg, st)
            var savedState = decision.updatedState
            val ent = store.loadEntitlement()

            var nextBanner: UiBanner = when {
                !st.online -> UiBanner(BannerSeverity.ERROR, R.string.banner_offline, listOf(st.errorMessage ?: cfg.printerHost))
                decision.type == DormancyDecisionType.LASER_ABORTED -> {
                    triggerLaserNoticeIfNew(target.id)
                    UiBanner(BannerSeverity.WARNING, R.string.banner_laser_safe, listOf(st.makeAndModel ?: target.displayName))
                }
                decision.type == DormancyDecisionType.MEDIA_EMPTY_ABORTED ->
                    UiBanner(BannerSeverity.WARNING, R.string.detail_media_empty)
                runDormancyCheck && decision.type == DormancyDecisionType.PURGE_REQUIRED && decision.recommendedPurgeMode != null -> {
                    val mode = decision.recommendedPurgeMode
                    if (!ent.hasAccess(ProFeature.AUTO_PURGE)) {
                        UiBanner(BannerSeverity.WARNING, R.string.notif_dormant_free_body, listOf(target.displayName, mode.name))
                    } else {
                        val outcome = client.dispatchPurge(cfg.printerHost, cfg.printerPort, mode, st, savedState)
                        if (outcome.success) {
                            savedState = DormancyEngine.markPurgeExecuted(savedState, mode)
                            sendTelemetryIfOptIn(target, st, savedState, "auto_purge", true)
                            UiBanner(BannerSeverity.SUCCESS, R.string.banner_purge_ok, listOf(mode.name))
                        } else {
                            sendTelemetryIfOptIn(target, st, savedState, "auto_purge", false)
                            UiBanner(BannerSeverity.ERROR, R.string.banner_purge_fail, listOf(outcome.message))
                        }
                    }
                }
                else -> UiBanner(BannerSeverity.SUCCESS, R.string.banner_all_active, listOf(cfg.inactivityThresholdDays.toString()))
            }

            var ws = store.loadWorkspace()
            val refreshed = WorkspaceEngine.updatePrinterStatusVisuals(target, st).copy(state = savedState)
            ws = WorkspaceEngine.upsertPrinter(ws, refreshed, setActive = (ws.activePrinterId == refreshed.id))
            store.saveWorkspace(ws)

            _uiState.update {
                it.copy(
                    workspace = ws,
                    printerStatuses = it.printerStatuses + (refreshed.id to st),
                    isBusy = false,
                    banner = nextBanner,
                )
            }
        }
    }

    fun triggerManualPurge(mode: PurgeMode, printerId: String? = _uiState.value.activePrinter?.id) {
        viewModelScope.launch {
            val ws = store.loadWorkspace()
            val target = printerId?.let { id -> ws.printers.firstOrNull { it.id == id } } ?: ws.activePrinter
            val cfg = target?.state
            if (target == null || cfg == null || cfg.printerHost.isBlank()) {
                _uiState.update { it.copy(banner = UiBanner(BannerSeverity.WARNING, R.string.banner_configure_first)) }
                return@launch
            }
            _uiState.update {
                it.copy(isBusy = true, banner = UiBanner(BannerSeverity.INFO, R.string.banner_purge_running, listOf(mode.name)))
            }
            val cachedStatus = _uiState.value.printerStatuses[target.id]
            val outcome = client.dispatchPurge(cfg.printerHost, cfg.printerPort, mode, cachedStatus, cfg)
            var nextWs = store.loadWorkspace()
            if (outcome.success) {
                val updatedTarget = target.copy(state = DormancyEngine.markPurgeExecuted(cfg, mode))
                nextWs = WorkspaceEngine.upsertPrinter(nextWs, updatedTarget, setActive = (nextWs.activePrinterId == target.id))
                store.saveWorkspace(nextWs)
            }
            sendTelemetryIfOptIn(target, cachedStatus, cfg, "manual_purge_${mode.code}", outcome.success)
            _uiState.update {
                it.copy(
                    workspace = nextWs,
                    isBusy = false,
                    banner = if (outcome.success) {
                        UiBanner(BannerSeverity.SUCCESS, R.string.banner_purge_ok, listOf(mode.name))
                    } else {
                        UiBanner(BannerSeverity.ERROR, R.string.banner_purge_fail, listOf(outcome.message))
                    },
                )
            }
        }
    }

    fun triggerSurgicalPurge(channel: NozzleChannel, step: Int, bursts: Int = 5) {
        viewModelScope.launch {
            val target = _uiState.value.activePrinter
            val cfg = target?.state
            if (target == null || cfg == null || cfg.printerHost.isBlank()) {
                _uiState.update { it.copy(banner = UiBanner(BannerSeverity.WARNING, R.string.banner_configure_first)) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    isBusy = true,
                    banner = UiBanner(BannerSeverity.INFO, R.string.banner_surgical_running, listOf("${channel.name} #$step")),
                )
            }
            val cachedStatus = _uiState.value.printerStatuses[target.id]
            val outcome = client.dispatchSurgicalPurge(cfg.printerHost, cfg.printerPort, channel, step, bursts, cachedStatus, cfg)
            sendTelemetryIfOptIn(target, cachedStatus, cfg, "surgical_${channel.code}", outcome.success)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    banner = if (outcome.success) {
                        UiBanner(BannerSeverity.SUCCESS, R.string.banner_purge_ok, listOf("${channel.name} #$step"))
                    } else {
                        UiBanner(BannerSeverity.ERROR, R.string.banner_purge_fail, listOf(outcome.message))
                    },
                )
            }
        }
    }

    fun scanSheetUri(uri: Uri) {
        val ent = store.loadEntitlement()
        if (!ent.hasAccess(ProFeature.CAMERA_SCANNER)) {
            requestPaywall(ProFeature.CAMERA_SCANNER)
            return
        }
        viewModelScope.launch {
            val prevHealth = _uiState.value.scanReport?.overallHealthPct
            _uiState.update { it.copy(isBusy = true, banner = UiBanner(BannerSeverity.INFO, R.string.banner_scanning_sheet)) }
            val report = client.scanPhotoUri(uri)
            val target = _uiState.value.activePrinter
            if (target != null) {
                sendTelemetryIfOptIn(
                    profile = target,
                    status = _uiState.value.printerStatuses[target.id],
                    state = target.state,
                    eventType = "optical_scan",
                    success = true,
                    healthBeforePct = prevHealth,
                    healthAfterPct = report.overallHealthPct,
                )
            }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    scanReport = report,
                    banner = UiBanner(BannerSeverity.SUCCESS, fallbackText = report.message),
                )
            }
        }
    }

    fun setTelemetryOptIn(enabled: Boolean) {
        val current = store.loadTelemetrySettings()
        val next = current.copy(optIn = enabled)
        store.saveTelemetrySettings(next)
        _uiState.update {
            it.copy(
                telemetry = next,
                banner = UiBanner(
                    severity = BannerSeverity.INFO,
                    messageRes = if (enabled) R.string.banner_telemetry_on else R.string.banner_telemetry_off,
                ),
            )
        }
    }

    fun resetTelemetryId() {
        val current = store.loadTelemetrySettings()
        val next = current.copy(installId = UUID.randomUUID().toString())
        store.saveTelemetrySettings(next)
        _uiState.update {
            it.copy(
                telemetry = next,
                banner = UiBanner(BannerSeverity.INFO, R.string.banner_telemetry_reset),
            )
        }
    }

    fun sampleTelemetryJson(): String {
        val ui = _uiState.value
        val active = ui.activePrinter
        val st = ui.status
        return TelemetryPayloadBuilder.buildEventJson(
            installId = ui.telemetry.installId,
            appVersion = BuildConfig.VERSION_NAME,
            tier = if (ui.entitlement.isProActive) PlanTier.PRO else PlanTier.FREE,
            brand = active?.brand ?: BrandBadge.EPSON,
            modelFamily = active?.displayName ?: "EPSON ET-2810 Series",
            headTechnology = st?.headTechnology ?: active?.state?.lastHeadTechnology ?: HeadTechnology.PIEZO,
            inkProfile = active?.state?.inkProfile ?: "cmyk",
            preferredFormat = active?.state?.preferredFormat ?: "image/pwg-raster",
            inactivityDays = active?.state?.inactivityThresholdDays ?: 5,
            eventType = "manual_purge_cmyk",
            outcomeSuccess = true,
            healthBeforePct = 80,
            healthAfterPct = 100,
        )
    }

    fun notifyJsonCopied() {
        _uiState.update {
            it.copy(banner = UiBanner(BannerSeverity.SUCCESS, R.string.banner_json_copied))
        }
    }

    fun saveAccountSession(email: String, displayName: String, syncServerUrl: String) {
        val cleanEmail = email.trim()
        val acc = AccountSession(
            signedIn = cleanEmail.isNotBlank(),
            email = cleanEmail,
            displayName = displayName.trim().ifBlank { cleanEmail.substringBefore('@') },
            syncServerUrl = syncServerUrl.trim().trimEnd('/'),
            lastSyncedIso = store.loadAccount().lastSyncedIso,
        )
        store.saveAccount(acc)
        _uiState.update {
            it.copy(
                account = acc,
                banner = UiBanner(
                    severity = BannerSeverity.SUCCESS,
                    messageRes = R.string.banner_account_saved,
                    formatArgs = listOf(acc.displayName.ifBlank { acc.email.ifBlank { "OK" } }),
                ),
            )
        }
    }

    fun signOutAccount() {
        val acc = AccountSession()
        store.saveAccount(acc)
        _uiState.update {
            it.copy(
                account = acc,
                banner = UiBanner(BannerSeverity.INFO, R.string.banner_account_signed_out),
            )
        }
    }

    fun exportSnapshotJson(): String =
        SyncSnapshotCodec.exportSnapshotJson(store.loadWorkspace(), store.loadAccount())

    fun syncWorkspaceToServer() {
        val ent = store.loadEntitlement()
        if (!ent.hasAccess(ProFeature.CLOUD_SYNC)) {
            requestPaywall(ProFeature.CLOUD_SYNC)
            return
        }
        val acc = store.loadAccount()
        if (acc.syncServerUrl.isBlank()) {
            val updated = acc.copy(lastSyncedIso = Instant.now().toString())
            store.saveAccount(updated)
            _uiState.update {
                it.copy(
                    account = updated,
                    banner = UiBanner(BannerSeverity.INFO, R.string.banner_sync_local_saved),
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val json = exportSnapshotJson()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL("${acc.syncServerUrl}/sync/workspace").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 4000
                        readTimeout = 4000
                        setRequestProperty("Content-Type", "application/json")
                    }
                    conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    conn.disconnect()
                    code in 200..299
                }.getOrDefault(false)
            }
            val updated = if (ok) acc.copy(lastSyncedIso = Instant.now().toString()) else acc
            if (ok) store.saveAccount(updated)
            _uiState.update {
                it.copy(
                    account = updated,
                    isBusy = false,
                    banner = UiBanner(
                        severity = if (ok) BannerSeverity.SUCCESS else BannerSeverity.ERROR,
                        messageRes = if (ok) R.string.banner_sync_ok else R.string.banner_sync_fail,
                        formatArgs = listOf(acc.syncServerUrl),
                    ),
                )
            }
        }
    }

    private fun triggerLaserNoticeIfNew(printerId: String) {
        if (acknowledgedLaserIds.add(printerId)) {
            _uiState.update { it.copy(laserNoticePrinterId = printerId) }
        }
    }

    private fun sendTelemetryIfOptIn(
        profile: PrinterProfile,
        status: PrinterStatus?,
        state: GuardianState,
        eventType: String,
        success: Boolean,
        healthBeforePct: Int? = null,
        healthAfterPct: Int? = null,
    ) {
        val tele = store.loadTelemetrySettings()
        if (!tele.optIn) return
        val endpoint = tele.endpointUrl.ifBlank { store.loadAccount().syncServerUrl }
        if (endpoint.isBlank()) return

        val payload = TelemetryPayloadBuilder.buildEventJson(
            installId = tele.installId,
            appVersion = BuildConfig.VERSION_NAME,
            tier = if (store.loadEntitlement().isProActive) PlanTier.PRO else PlanTier.FREE,
            brand = profile.brand,
            modelFamily = status?.makeAndModel ?: profile.displayName,
            headTechnology = status?.headTechnology ?: state.lastHeadTechnology,
            inkProfile = state.inkProfile,
            preferredFormat = state.preferredFormat,
            inactivityDays = state.inactivityThresholdDays,
            eventType = eventType,
            outcomeSuccess = success,
            healthBeforePct = healthBeforePct,
            healthAfterPct = healthAfterPct,
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val conn = (URL("${endpoint.trimEnd('/')}/telemetry").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            }
        }
    }
}

package com.umbette.nozzleguardian.android

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.umbette.nozzleguardian.engine.AccountSession
import com.umbette.nozzleguardian.engine.DiscoveredPrinter
import com.umbette.nozzleguardian.engine.DormancyDecisionType
import com.umbette.nozzleguardian.engine.DormancyEngine
import com.umbette.nozzleguardian.engine.EntitlementPolicy
import com.umbette.nozzleguardian.engine.EntitlementState
import com.umbette.nozzleguardian.engine.GuardianState
import com.umbette.nozzleguardian.engine.MultiPrinterWorkspace
import com.umbette.nozzleguardian.engine.NozzleChannel
import com.umbette.nozzleguardian.engine.NozzleScanReport
import com.umbette.nozzleguardian.engine.PlanTier
import com.umbette.nozzleguardian.engine.PrinterClassification
import com.umbette.nozzleguardian.engine.PrinterProfile
import com.umbette.nozzleguardian.engine.PrinterStatus
import com.umbette.nozzleguardian.engine.ProFeature
import com.umbette.nozzleguardian.engine.PurgeMode
import com.umbette.nozzleguardian.engine.SyncSnapshotCodec
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

data class MainUiState(
    val workspace: MultiPrinterWorkspace = MultiPrinterWorkspace(),
    val config: GuardianState = GuardianState(),
    val entitlement: EntitlementState = EntitlementState(),
    val account: AccountSession = AccountSession(),
    val billing: BillingUiState = BillingUiState(),
    val status: PrinterStatus? = null,
    val discovered: List<DiscoveredPrinter> = emptyList(),
    val scanReport: NozzleScanReport? = null,
    val isDiscovering: Boolean = false,
    val isBusy: Boolean = false,
    val bannerMessage: String = "",
    val paywallFeaturePrompt: ProFeature? = null,
)

class NozzleGuardianViewModel(application: Application) : AndroidViewModel(application) {
    private val store = StateStore(application)
    private val client = LanPrinterClient(application, store)

    private val billingManager = BillingManager(application) { tier, source, sku ->
        val nextEnt = store.loadEntitlement().copy(tier = tier, source = source, productId = sku)
        store.saveEntitlement(nextEnt)
        _uiState.update { it.copy(entitlement = nextEnt) }
    }

    private val initialWorkspace = store.loadWorkspace()
    private val _uiState = MutableStateFlow(
        MainUiState(
            workspace = initialWorkspace,
            config = initialWorkspace.activePrinter?.state ?: store.loadState(),
            entitlement = store.loadEntitlement(),
            account = store.loadAccount(),
            bannerMessage = store.getLastSummary(),
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        PurgeCheckWorker.schedule(application)
        billingManager.start()
        viewModelScope.launch {
            billingManager.billingState.collect { bState ->
                _uiState.update { it.copy(billing = bState) }
            }
        }
        if (_uiState.value.config.printerHost.isNotBlank()) {
            refreshStatus(runDormancyCheck = false)
        }
    }

    override fun onCleared() {
        billingManager.end()
        super.onCleared()
    }

    fun dismissPaywall() {
        _uiState.update { it.copy(paywallFeaturePrompt = null) }
    }

    fun requestPaywall(feature: ProFeature) {
        _uiState.update { it.copy(paywallFeaturePrompt = feature) }
    }

    fun launchProPurchase(activity: Activity, offer: BillingProductOffer) {
        val started = billingManager.launchPurchase(activity, offer)
        if (!started) {
            _uiState.update { it.copy(bannerMessage = "Play Billing non disponibile su questa build.") }
        }
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
                bannerMessage = if (enabled) "Modalità anteprima Pro attiva (test)." else "Modalità Free attiva per test paywall.",
            )
        }
    }

    fun discoverPrinters() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscovering = true, bannerMessage = "Ricerca stampanti Wi-Fi via mDNS...") }
            val list = client.discoverLanPrinters()
            _uiState.update {
                it.copy(
                    isDiscovering = false,
                    discovered = list,
                    bannerMessage = if (list.isEmpty()) {
                        "Nessuna stampante rilevata con mDNS. Puoi inserire l'IP manualmente."
                    } else {
                        "Trovate ${list.size} stampanti IPP sulla rete locale."
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
            switchActivePrinter(existing.id)
            return
        }
        val active = ws.activePrinter
        if (active == null || !EntitlementPolicy.canAddPrinter(ent, ws)) {
            // Update the active/primary slot on Free, or create a new slot on Pro
            if (active != null && !ent.isProActive && ws.printers.isNotEmpty()) {
                val (brand, silhouette) = PrinterClassification.inferVisuals(printer.makeAndModel, kind = printer.printerKind)
                val updated = active.copy(
                    displayName = printer.makeAndModel,
                    brand = brand,
                    silhouette = silhouette,
                    state = active.state.copy(
                        printerHost = printer.host,
                        printerPort = printer.port,
                        useTls = printer.useTls,
                        pinnedCertSha256 = null,
                    ),
                )
                val nextWs = WorkspaceEngine.upsertPrinter(ws, updated, setActive = true)
                store.saveWorkspace(nextWs)
                _uiState.update {
                    it.copy(
                        workspace = nextWs,
                        config = updated.state,
                        bannerMessage = "Stampante attiva aggiornata: ${printer.makeAndModel}.",
                    )
                }
                refreshStatus(runDormancyCheck = false)
                return
            }
        }
        addPrinterProfile(
            locationId = targetLocationId ?: active?.locationId ?: ws.locations.first().id,
            displayName = printer.makeAndModel,
            host = printer.host,
            port = printer.port,
            useTls = printer.useTls,
        )
    }

    fun switchActivePrinter(printerId: String) {
        val ws = WorkspaceEngine.selectActivePrinter(store.loadWorkspace(), printerId)
        store.saveWorkspace(ws)
        val activeCfg = ws.activePrinter?.state ?: GuardianState()
        _uiState.update {
            it.copy(
                workspace = ws,
                config = activeCfg,
                status = null,
                bannerMessage = "Stampante attiva: ${ws.activePrinter?.displayName ?: "-"}",
            )
        }
        if (activeCfg.printerHost.isNotBlank()) {
            refreshStatus(runDormancyCheck = false)
        }
    }

    fun addLocationGroup(name: String, wifiSsid: String) {
        val ws = store.loadWorkspace()
        val ent = store.loadEntitlement()
        if (!EntitlementPolicy.canAddLocation(ent, ws) && ws.locations.isNotEmpty()) {
            requestPaywall(ProFeature.MULTI_PRINTER_LOCATIONS)
            return
        }
        val nextWs = WorkspaceEngine.addLocation(ws, name = name, wifiSsid = wifiSsid)
        store.saveWorkspace(nextWs)
        _uiState.update { it.copy(workspace = nextWs, bannerMessage = "Location '$name' aggiunta.") }
    }

    fun removeLocationGroup(locationId: String) {
        val nextWs = WorkspaceEngine.removeLocation(store.loadWorkspace(), locationId)
        store.saveWorkspace(nextWs)
        _uiState.update { it.copy(workspace = nextWs) }
    }

    fun addPrinterProfile(
        locationId: String,
        displayName: String,
        host: String,
        port: Int = 631,
        useTls: Boolean = true,
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
        val (brand, silhouette) = PrinterClassification.inferVisuals(label)
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
            ),
        )
        val nextWs = WorkspaceEngine.upsertPrinter(ws, profile, setActive = true)
        store.saveWorkspace(nextWs)
        _uiState.update {
            it.copy(
                workspace = nextWs,
                config = profile.state,
                status = null,
                bannerMessage = "Stampante '$label' aggiunta.",
            )
        }
        refreshStatus(runDormancyCheck = false)
    }

    fun removePrinterProfile(printerId: String) {
        val nextWs = WorkspaceEngine.removePrinter(store.loadWorkspace(), printerId)
        store.saveWorkspace(nextWs)
        val nextCfg = nextWs.activePrinter?.state ?: GuardianState()
        _uiState.update {
            it.copy(
                workspace = nextWs,
                config = nextCfg,
                status = null,
                bannerMessage = "Stampante rimossa.",
            )
        }
    }

    fun updatePrinterConfig(
        host: String,
        port: Int,
        useTls: Boolean,
        thresholdDays: Int,
        autoPurgeEnabled: Boolean,
        preferredFormat: String = _uiState.value.config.preferredFormat,
        mediaType: String = _uiState.value.config.mediaType,
        inkProfile: String = _uiState.value.config.inkProfile,
        locationId: String? = _uiState.value.workspace.activePrinter?.locationId,
    ) {
        val ent = store.loadEntitlement()
        val effectiveAutoPurge = if (autoPurgeEnabled && !ent.hasAccess(ProFeature.AUTO_PURGE)) {
            requestPaywall(ProFeature.AUTO_PURGE)
            false
        } else {
            autoPurgeEnabled
        }

        val current = store.loadState()
        val pinReset = if (current.printerHost != host.trim()) null else current.pinnedCertSha256
        val updated = current.copy(
            printerHost = host.trim(),
            printerPort = port.coerceIn(1, 65535),
            useTls = useTls,
            pinnedCertSha256 = pinReset,
            preferredFormat = preferredFormat,
            mediaType = mediaType,
            inkProfile = inkProfile,
            inactivityThresholdDays = thresholdDays.coerceIn(1, 30),
            autoPurgeEnabled = effectiveAutoPurge,
        )
        var ws = store.loadWorkspace()
        val active = ws.activePrinter
        if (active != null) {
            val nextProfile = active.copy(
                locationId = locationId ?: active.locationId,
                state = updated,
            )
            ws = WorkspaceEngine.upsertPrinter(ws, nextProfile, setActive = true)
            store.saveWorkspace(ws)
        } else {
            store.saveState(updated)
            ws = store.loadWorkspace()
        }
        _uiState.update {
            it.copy(
                workspace = ws,
                config = updated,
                bannerMessage = "Configurazione salvata per ${updated.printerHost}.",
            )
        }
        if (updated.printerHost.isNotBlank()) {
            refreshStatus(runDormancyCheck = false)
        }
    }

    fun refreshStatus(runDormancyCheck: Boolean = true) {
        viewModelScope.launch {
            val cfg = store.loadState()
            if (cfg.printerHost.isBlank()) {
                _uiState.update { it.copy(bannerMessage = "Inserisci l'IP o tocca Cerca mDNS.") }
                return@launch
            }
            _uiState.update { it.copy(isBusy = true, bannerMessage = "Interrogazione IPP / SNMP / EWS...") }
            val st = client.getStatus(cfg.printerHost, cfg.printerPort, cfg.useTls, cfg.mediaSize)
            val decision = DormancyEngine.evaluate(cfg, st)
            var savedState = decision.updatedState
            val ent = store.loadEntitlement()

            var summary = if (
                runDormancyCheck &&
                decision.type == DormancyDecisionType.PURGE_REQUIRED &&
                decision.recommendedPurgeMode != null
            ) {
                if (!ent.hasAccess(ProFeature.AUTO_PURGE)) {
                    "Canale ${decision.recommendedPurgeMode.name} inattivo: attiva Auto-Spurgo (Pro) o avvia lo spurgo manuale."
                } else {
                    val outcome = client.dispatchPurge(cfg.printerHost, cfg.printerPort, decision.recommendedPurgeMode, st)
                    if (outcome.success) {
                        savedState = DormancyEngine.markPurgeExecuted(savedState, decision.recommendedPurgeMode)
                    }
                    outcome.message
                }
            } else {
                decision.message
            }

            if (!st.online) summary = st.errorMessage ?: "Stampante non raggiungibile."
            store.saveState(savedState)
            var ws = store.loadWorkspace()
            ws.activePrinter?.let { act ->
                val vis = WorkspaceEngine.updatePrinterStatusVisuals(act, st).copy(state = savedState)
                ws = WorkspaceEngine.upsertPrinter(ws, vis, setActive = true)
                store.saveWorkspace(ws)
            }
            store.saveLastSummary(summary)
            _uiState.update {
                it.copy(
                    workspace = ws,
                    config = savedState,
                    status = st,
                    isBusy = false,
                    bannerMessage = summary,
                )
            }
        }
    }

    fun triggerManualPurge(mode: PurgeMode) {
        viewModelScope.launch {
            val cfg = store.loadState()
            if (cfg.printerHost.isBlank()) return@launch
            _uiState.update { it.copy(isBusy = true, bannerMessage = "Compilazione ed invio spurgo ${mode.name}...") }
            val outcome = client.dispatchPurge(cfg.printerHost, cfg.printerPort, mode, _uiState.value.status)
            var nextState = cfg
            if (outcome.success) {
                nextState = DormancyEngine.markPurgeExecuted(cfg, mode)
                store.saveState(nextState)
            }
            store.saveLastSummary(outcome.message)
            _uiState.update {
                it.copy(
                    workspace = store.loadWorkspace(),
                    config = nextState,
                    isBusy = false,
                    bannerMessage = outcome.message,
                )
            }
        }
    }

    fun triggerSurgicalPurge(channel: NozzleChannel, step: Int, bursts: Int = 5) {
        viewModelScope.launch {
            val cfg = store.loadState()
            if (cfg.printerHost.isBlank()) {
                _uiState.update { it.copy(bannerMessage = "Configura prima una stampante nella scheda Stampante.") }
                return@launch
            }
            _uiState.update { it.copy(isBusy = true, bannerMessage = "Sparo chirurgico ${channel.displayName} #$step...") }
            val outcome = client.dispatchSurgicalPurge(cfg.printerHost, cfg.printerPort, channel, step, bursts, _uiState.value.status)
            store.saveLastSummary(outcome.message)
            _uiState.update { it.copy(isBusy = false, bannerMessage = outcome.message) }
        }
    }

    fun scanSheetUri(uri: Uri) {
        val ent = store.loadEntitlement()
        if (!ent.hasAccess(ProFeature.CAMERA_SCANNER)) {
            requestPaywall(ProFeature.CAMERA_SCANNER)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, bannerMessage = "Analisi ottica ugelli in corso...") }
            val report = client.scanPhotoUri(uri)
            _uiState.update { it.copy(isBusy = false, scanReport = report, bannerMessage = report.message) }
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
                bannerMessage = if (acc.signedIn) "Account locale/sync collegato: ${acc.email}" else "Profilo sync aggiornato.",
            )
        }
    }

    fun signOutAccount() {
        val acc = AccountSession()
        store.saveAccount(acc)
        _uiState.update { it.copy(account = acc, bannerMessage = "Account disconnesso. Tutti i dati restano salvati in locale.") }
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
                    bannerMessage = "Snapshot locale salvato. Inserisci un URL server per inviare il backup remoto.",
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, bannerMessage = "Sincronizzazione profilo stampanti...") }
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
                    bannerMessage = if (ok) "Backup/Sync completato su ${acc.syncServerUrl}." else "Server sync non raggiungibile (${acc.syncServerUrl}).",
                )
            }
        }
    }
}

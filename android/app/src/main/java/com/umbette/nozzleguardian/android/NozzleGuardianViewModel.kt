package com.umbette.nozzleguardian.android

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.umbette.nozzleguardian.engine.DiscoveredPrinter
import com.umbette.nozzleguardian.engine.DormancyDecisionType
import com.umbette.nozzleguardian.engine.DormancyEngine
import com.umbette.nozzleguardian.engine.GuardianState
import com.umbette.nozzleguardian.engine.NozzleChannel
import com.umbette.nozzleguardian.engine.NozzleScanReport
import com.umbette.nozzleguardian.engine.PrinterStatus
import com.umbette.nozzleguardian.engine.PurgeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val config: GuardianState = GuardianState(),
    val status: PrinterStatus? = null,
    val discovered: List<DiscoveredPrinter> = emptyList(),
    val scanReport: NozzleScanReport? = null,
    val isDiscovering: Boolean = false,
    val isBusy: Boolean = false,
    val bannerMessage: String = "",
)

class NozzleGuardianViewModel(application: Application) : AndroidViewModel(application) {
    private val store = StateStore(application)
    private val client = LanPrinterClient(application, store)

    private val _uiState = MutableStateFlow(
        MainUiState(
            config = store.loadState(),
            bannerMessage = store.getLastSummary(),
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        PurgeCheckWorker.schedule(application)
        if (_uiState.value.config.printerHost.isNotBlank()) {
            refreshStatus(runDormancyCheck = false)
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

    fun selectPrinter(printer: DiscoveredPrinter) {
        updatePrinterConfig(
            host = printer.host,
            port = printer.port,
            useTls = printer.useTls,
            thresholdDays = _uiState.value.config.inactivityThresholdDays,
            autoPurgeEnabled = _uiState.value.config.autoPurgeEnabled,
        )
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
    ) {
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
            autoPurgeEnabled = autoPurgeEnabled,
        )
        store.saveState(updated)
        _uiState.update { it.copy(config = updated, bannerMessage = "Configurazione salvata per ${updated.printerHost}.") }
        if (updated.printerHost.isNotBlank()) {
            refreshStatus(runDormancyCheck = false)
        }
    }

    fun refreshStatus(runDormancyCheck: Boolean = true) {
        viewModelScope.launch {
            val cfg = store.loadState()
            if (cfg.printerHost.isBlank()) {
                _uiState.update { it.copy(bannerMessage = "Inserisci l'IP o tocca Cerca Stampante.") }
                return@launch
            }
            _uiState.update { it.copy(isBusy = true, bannerMessage = "Interrogazione IPP / SNMP / EWS...") }
            val st = client.getStatus(cfg.printerHost, cfg.printerPort, cfg.useTls, cfg.mediaSize)
            val decision = DormancyEngine.evaluate(cfg, st)
            var savedState = decision.updatedState

            var summary = if (runDormancyCheck && decision.type == DormancyDecisionType.PURGE_REQUIRED && decision.recommendedPurgeMode != null) {
                val outcome = client.dispatchPurge(cfg.printerHost, cfg.printerPort, decision.recommendedPurgeMode, st)
                if (outcome.success) {
                    savedState = DormancyEngine.markPurgeExecuted(savedState, decision.recommendedPurgeMode)
                }
                outcome.message
            } else {
                decision.message
            }

            if (!st.online) summary = st.errorMessage ?: "Stampante non raggiungibile."
            store.saveState(savedState)
            store.saveLastSummary(summary)
            _uiState.update {
                it.copy(
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
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, bannerMessage = "Analisi ottica ugelli in corso...") }
            val report = client.scanPhotoUri(uri)
            _uiState.update { it.copy(isBusy = false, scanReport = report, bannerMessage = report.message) }
        }
    }
}

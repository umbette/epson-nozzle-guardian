package com.umbette.nozzleguardian.android

import android.content.Context
import android.net.wifi.WifiManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.umbette.nozzleguardian.engine.DormancyDecisionType
import com.umbette.nozzleguardian.engine.DormancyEngine
import com.umbette.nozzleguardian.engine.ProFeature
import com.umbette.nozzleguardian.engine.WorkspaceEngine
import java.time.Instant
import java.util.concurrent.TimeUnit

class PurgeCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @Suppress("DEPRECATION")
    private fun currentWifiSsid(): String? = runCatching {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val raw = wm?.connectionInfo?.ssid ?: return null
        if (raw == "<unknown ssid>" || raw.isBlank()) null else raw.removePrefix("\"").removeSuffix("\"")
    }.getOrNull()

    override suspend fun doWork(): Result {
        val store = StateStore(applicationContext)
        var workspace = store.loadWorkspace()
        val entitlement = store.loadEntitlement()
        val ssid = currentWifiSsid()

        val reachablePrinters = WorkspaceEngine.printersForCurrentWifi(workspace, ssid)
        if (reachablePrinters.isEmpty()) {
            return Result.success()
        }

        val client = LanPrinterClient(applicationContext, store)
        val canAutoPurge = entitlement.hasAccess(ProFeature.AUTO_PURGE)
        var lastSummary = store.getLastSummary()

        for ((idx, printer) in reachablePrinters.withIndex()) {
            val st = printer.state
            val status = client.getStatus(
                host = st.printerHost,
                port = st.printerPort,
                useTls = st.useTls,
                preferredMedia = st.mediaSize,
            )
            val decision = DormancyEngine.evaluate(st, status, Instant.now())
            var nextPrinterState = decision.updatedState
            var updatedProfile = WorkspaceEngine.updatePrinterStatusVisuals(printer, status)

            when (decision.type) {
                DormancyDecisionType.MEDIA_EMPTY_ABORTED -> {
                    lastSummary = "${updatedProfile.displayName}: ${decision.message}"
                    NotificationHelper.showAlert(
                        applicationContext,
                        "Nozzle Guardian: Inserisci Carta",
                        "${updatedProfile.displayName}: vassoio vuoto (media-empty).",
                        notificationId = 1100 + idx,
                    )
                }
                DormancyDecisionType.PURGE_REQUIRED -> {
                    val mode = decision.recommendedPurgeMode
                    if (mode != null && canAutoPurge) {
                        val outcome = client.dispatchPurge(st.printerHost, st.printerPort, mode, status)
                        if (outcome.success) {
                            nextPrinterState = DormancyEngine.markPurgeExecuted(nextPrinterState, mode)
                            lastSummary = "${updatedProfile.displayName}: spurgo ${mode.name} eseguito (${outcome.docFormat})."
                            NotificationHelper.showAlert(
                                applicationContext,
                                "Nozzle Guardian: Spurgo ${mode.name} completato",
                                "${updatedProfile.displayName}: ${outcome.message}",
                                notificationId = 1001 + idx,
                            )
                        } else {
                            lastSummary = "${updatedProfile.displayName}: spurgo ${mode.name} fallito (${outcome.message})."
                        }
                    } else if (mode != null) {
                        lastSummary = "${updatedProfile.displayName}: canale ${mode.name} inattivo (Auto-Spurgo richiede Pro)."
                    }
                }
                else -> {
                    lastSummary = "${updatedProfile.displayName}: ${decision.message}"
                }
            }

            updatedProfile = updatedProfile.copy(state = nextPrinterState)
            workspace = WorkspaceEngine.upsertPrinter(
                workspace,
                updatedProfile,
                setActive = (workspace.activePrinterId == updatedProfile.id),
            )
        }

        store.saveWorkspace(workspace)
        store.saveLastSummary(lastSummary)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "nozzle_guardian_wifi_guard"

        fun schedule(context: Context, intervalHours: Long = 12L) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
            val request = PeriodicWorkRequestBuilder<PurgeCheckWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}

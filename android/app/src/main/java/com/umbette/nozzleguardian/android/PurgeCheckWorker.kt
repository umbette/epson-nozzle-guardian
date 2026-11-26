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
import com.umbette.nozzleguardian.R
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
        val lanInfo = LocalNetworkDetector.detectCurrentWifiLan(applicationContext)

        val reachablePrinters = WorkspaceEngine.printersForCurrentWifi(workspace, ssid, lanInfo?.fingerprint)
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
                    lastSummary = applicationContext.getString(R.string.notif_media_empty_body, updatedProfile.displayName)
                    NotificationHelper.showAlert(
                        applicationContext,
                        applicationContext.getString(R.string.notif_media_empty_title),
                        lastSummary,
                        notificationId = 1100 + idx,
                    )
                }
                DormancyDecisionType.PURGE_REQUIRED -> {
                    val mode = decision.recommendedPurgeMode
                    if (mode != null && canAutoPurge) {
                        val outcome = client.dispatchPurge(
                            host = st.printerHost,
                            port = st.printerPort,
                            mode = mode,
                            status = status,
                            printerState = st,
                        )
                        if (outcome.success) {
                            nextPrinterState = DormancyEngine.markPurgeExecuted(nextPrinterState, mode)
                            lastSummary = applicationContext.getString(R.string.banner_purge_ok, "${updatedProfile.displayName} • ${mode.name}")
                            NotificationHelper.showAlert(
                                applicationContext,
                                applicationContext.getString(R.string.notif_purge_ok_title),
                                lastSummary,
                                notificationId = 1001 + idx,
                            )
                        } else {
                            lastSummary = applicationContext.getString(R.string.banner_purge_fail, "${updatedProfile.displayName}: ${outcome.message}")
                        }
                    } else if (mode != null) {
                        lastSummary = applicationContext.getString(
                            R.string.notif_dormant_free_body,
                            updatedProfile.displayName,
                            mode.name,
                        )
                        NotificationHelper.showAlert(
                            applicationContext,
                            applicationContext.getString(R.string.notif_dormant_free_title),
                            lastSummary,
                            notificationId = 1200 + idx,
                        )
                    }
                }
                DormancyDecisionType.LASER_ABORTED -> {
                    lastSummary = applicationContext.getString(R.string.banner_laser_safe, updatedProfile.displayName)
                }
                else -> {
                    lastSummary = applicationContext.getString(R.string.banner_all_active, st.inactivityThresholdDays.toString())
                }
            }

            if (status.online && status.lowInkMarkers.isNotEmpty()) {
                val lowText = status.lowInkMarkers.joinToString(", ") { "${it.name} (${it.level}%)" }
                NotificationHelper.showAlert(
                    applicationContext,
                    applicationContext.getString(R.string.notif_low_ink_title),
                    applicationContext.getString(R.string.notif_low_ink_body, updatedProfile.displayName, lowText),
                    notificationId = 1300 + idx,
                )
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
        private const val WORK_NAME = "nozzle_guardian_periodic_check"

        fun schedulePeriodic(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
            val req = PeriodicWorkRequestBuilder<PurgeCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}

package com.umbette.nozzleguardian.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.umbette.nozzleguardian.engine.DormancyDecisionType
import com.umbette.nozzleguardian.engine.DormancyEngine
import java.time.Instant
import java.util.concurrent.TimeUnit

class PurgeCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = StateStore(applicationContext)
        val currentState = store.loadState()
        if (currentState.printerHost.isBlank()) {
            return Result.success()
        }

        val client = LanPrinterClient(applicationContext, store)
        val status = client.getStatus(
            host = currentState.printerHost,
            port = currentState.printerPort,
            useTls = currentState.useTls,
            preferredMedia = currentState.mediaSize,
        )
        val decision = DormancyEngine.evaluate(currentState, status, Instant.now())
        var finalState = decision.updatedState

        when (decision.type) {
            DormancyDecisionType.MEDIA_EMPTY_ABORTED -> {
                store.saveState(finalState)
                store.saveLastSummary(decision.message)
                NotificationHelper.showAlert(
                    applicationContext,
                    "Nozzle Guardian: Inserisci Carta",
                    "${status.makeAndModel ?: currentState.printerHost}: vassoio vuoto (media-empty).",
                    notificationId = 1002,
                )
            }
            DormancyDecisionType.PURGE_REQUIRED -> {
                val mode = decision.recommendedPurgeMode
                if (mode != null) {
                    val outcome = client.dispatchPurge(currentState.printerHost, currentState.printerPort, mode, status)
                    if (outcome.success) {
                        finalState = DormancyEngine.markPurgeExecuted(finalState, mode)
                        store.saveLastSummary("Spurgo automatico ${mode.name} eseguito (${outcome.docFormat}).")
                        NotificationHelper.showAlert(
                            applicationContext,
                            "Nozzle Guardian: Spurgo ${mode.name} completato",
                            "${status.makeAndModel ?: currentState.printerHost}: ${outcome.message}",
                            notificationId = 1001,
                        )
                    } else {
                        store.saveLastSummary("Tentativo spurgo ${mode.name} fallito: ${outcome.message}")
                    }
                }
                store.saveState(finalState)
            }
            else -> {
                store.saveState(finalState)
                store.saveLastSummary(decision.message)
            }
        }
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

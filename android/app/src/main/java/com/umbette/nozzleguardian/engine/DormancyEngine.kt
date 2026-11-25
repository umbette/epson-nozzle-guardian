package com.umbette.nozzleguardian.engine

import java.time.Duration
import java.time.Instant

/**
 * Pure state-evaluation engine tracking Monochrome (K) and Color (CMY) dormancy
 * independently with total-page fallback, laser protection, and media-empty interlock.
 */
object DormancyEngine {
    private fun parseInstantOrNow(iso: String?, now: Instant): Instant {
        if (iso.isNullOrBlank()) return now
        return try {
            Instant.parse(iso)
        } catch (_: Exception) {
            now
        }
    }

    fun evaluate(
        currentState: GuardianState,
        status: PrinterStatus,
        now: Instant = Instant.now(),
    ): DormancyDecision {
        if (currentState.printerHost.isBlank()) {
            return DormancyDecision(
                updatedState = currentState,
                type = DormancyDecisionType.UNCONFIGURED,
                message = "Nessuna stampante configurata.",
            )
        }

        if (!status.online || status.state == "unknown") {
            return DormancyDecision(
                updatedState = currentState,
                type = DormancyDecisionType.OFFLINE,
                message = status.errorMessage ?: "Stampante offline o non raggiungibile.",
            )
        }

        var nextState = currentState.copy(
            lastModel = status.makeAndModel ?: currentState.lastModel,
            lastPrinterKind = status.printerKind,
            lastHeadTechnology = status.headTechnology,
        )

        if (status.printerKind == PrinterKind.LASER) {
            return DormancyDecision(
                updatedState = nextState,
                type = DormancyDecisionType.LASER_ABORTED,
                message = "Stampante Laser/Toner rilevata: protezione attiva, nessun micro-spurgo inchiostro necessario.",
            )
        }

        if (status.isMediaEmpty) {
            return DormancyDecision(
                updatedState = nextState,
                type = DormancyDecisionType.MEDIA_EMPTY_ABORTED,
                message = "Vassoio carta vuoto (media-empty): inserisci un foglio di scarto per consentire lo spurgo.",
            )
        }

        val threshold = Duration.ofDays(currentState.inactivityThresholdDays.coerceIn(1, 30).toLong())
        val monoSupported = status.monochromePages != null
        val colorSupported = status.colorPages != null
        val totalOnlySupported = status.totalPages != null && !monoSupported && !colorSupported
        val nowIso = now.toString()

        var monoDormant = false
        if (monoSupported) {
            val prevMono = nextState.lastMonoPages
            val lastMonoIso = if (prevMono == null || status.monochromePages != prevMono) {
                nowIso
            } else {
                nextState.lastMonoActivityIso ?: nowIso
            }
            nextState = nextState.copy(
                lastMonoPages = status.monochromePages,
                lastMonoActivityIso = lastMonoIso,
            )
            monoDormant = !Duration.between(parseInstantOrNow(lastMonoIso, now), now).minus(threshold).isNegative
        }

        var colorDormant = false
        if (colorSupported) {
            val prevColor = nextState.lastColorPages
            val lastColorIso = if (prevColor == null || status.colorPages != prevColor) {
                nowIso
            } else {
                nextState.lastColorActivityIso ?: nowIso
            }
            nextState = nextState.copy(
                lastColorPages = status.colorPages,
                lastColorActivityIso = lastColorIso,
            )
            colorDormant = !Duration.between(parseInstantOrNow(lastColorIso, now), now).minus(threshold).isNegative
        }

        var totalDormant = false
        if (totalOnlySupported) {
            val prevTotal = nextState.lastTotalPages
            val lastTotalIso = if (prevTotal == null || status.totalPages != prevTotal) {
                nowIso
            } else {
                nextState.lastTotalActivityIso ?: nowIso
            }
            nextState = nextState.copy(
                lastTotalPages = status.totalPages,
                lastTotalActivityIso = lastTotalIso,
                lastMonoActivityIso = if (prevTotal == null || status.totalPages != prevTotal) nowIso else nextState.lastMonoActivityIso,
                lastColorActivityIso = if (prevTotal == null || status.totalPages != prevTotal) nowIso else nextState.lastColorActivityIso,
            )
            totalDormant = !Duration.between(parseInstantOrNow(lastTotalIso, now), now).minus(threshold).isNegative
        }

        if (!monoSupported && !colorSupported && !totalOnlySupported) {
            return DormancyDecision(
                updatedState = nextState,
                type = DormancyDecisionType.UNSUPPORTED_COUNTERS,
                message = "La stampante non espone contatori pagine: puoi comunque lanciare lo spurgo o la scansione manualmente.",
            )
        }

        val recommendedMode = when {
            (monoDormant && colorDormant) || totalDormant -> PurgeMode.CMYK
            colorDormant -> PurgeMode.CMY
            monoDormant -> PurgeMode.K
            else -> null
        }

        if (recommendedMode == null) {
            return DormancyDecision(
                updatedState = nextState,
                type = DormancyDecisionType.NO_ACTION_ACTIVE,
                monoDormant = false,
                colorDormant = false,
                totalDormant = false,
                message = "Tutti i canali risultano attivi entro la soglia di ${currentState.inactivityThresholdDays} giorni.",
            )
        }

        if (!currentState.autoPurgeEnabled) {
            return DormancyDecision(
                updatedState = nextState,
                type = DormancyDecisionType.AUTO_PURGE_DISABLED,
                monoDormant = monoDormant,
                colorDormant = colorDormant,
                totalDormant = totalDormant,
                recommendedPurgeMode = recommendedMode,
                message = "Canale inattivo (${recommendedMode.name}) ma auto-spurgo non ancora abilitato dall'utente.",
            )
        }

        return DormancyDecision(
            updatedState = nextState,
            type = DormancyDecisionType.PURGE_REQUIRED,
            monoDormant = monoDormant,
            colorDormant = colorDormant,
            totalDormant = totalDormant,
            recommendedPurgeMode = recommendedMode,
            message = "Spurgo preventivo ${recommendedMode.name} necessario per inattività.",
        )
    }

    fun markPurgeExecuted(state: GuardianState, mode: PurgeMode, now: Instant = Instant.now()): GuardianState {
        val nowIso = now.toString()
        return when (mode) {
            PurgeMode.K -> state.copy(lastMonoActivityIso = nowIso, lastMonoPurgeIso = nowIso)
            PurgeMode.CMY -> state.copy(lastColorActivityIso = nowIso, lastColorPurgeIso = nowIso)
            PurgeMode.CMYK -> state.copy(
                lastTotalActivityIso = nowIso,
                lastMonoActivityIso = nowIso,
                lastColorActivityIso = nowIso,
                lastMonoPurgeIso = nowIso,
                lastColorPurgeIso = nowIso,
            )
        }
    }
}

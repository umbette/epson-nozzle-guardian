package com.umbette.nozzleguardian.android

import android.content.Context
import com.umbette.nozzleguardian.engine.GuardianState
import com.umbette.nozzleguardian.engine.HeadTechnology
import com.umbette.nozzleguardian.engine.PrinterKind

class StateStore(context: Context) {
    private val prefs = context.getSharedPreferences("nozzle_guardian_prefs", Context.MODE_PRIVATE)

    fun loadState(): GuardianState = GuardianState(
        printerHost = prefs.getString("printer_host", "") ?: "",
        printerPort = prefs.getInt("printer_port", 631),
        useTls = prefs.getBoolean("use_tls", false),
        pinnedCertSha256 = prefs.getString("pinned_cert_sha256", null),
        mediaSize = prefs.getString("media_size", "auto") ?: "auto",
        inactivityThresholdDays = prefs.getInt("inactivity_threshold_days", 5),
        autoPurgeEnabled = prefs.getBoolean("auto_purge_enabled", false),
        lastMonoPages = if (prefs.contains("last_mono_pages")) prefs.getInt("last_mono_pages", 0) else null,
        lastColorPages = if (prefs.contains("last_color_pages")) prefs.getInt("last_color_pages", 0) else null,
        lastTotalPages = if (prefs.contains("last_total_pages")) prefs.getInt("last_total_pages", 0) else null,
        lastMonoActivityIso = prefs.getString("last_mono_activity_iso", null),
        lastColorActivityIso = prefs.getString("last_color_activity_iso", null),
        lastTotalActivityIso = prefs.getString("last_total_activity_iso", null),
        lastMonoPurgeIso = prefs.getString("last_mono_purge_iso", null),
        lastColorPurgeIso = prefs.getString("last_color_purge_iso", null),
        lastModel = prefs.getString("last_model", null),
        lastPrinterKind = enumValueOr(prefs.getString("last_printer_kind", null), PrinterKind.INKJET),
        lastHeadTechnology = enumValueOr(prefs.getString("last_head_technology", null), HeadTechnology.PIEZO),
    )

    fun saveState(state: GuardianState) {
        prefs.edit().apply {
            putString("printer_host", state.printerHost.trim())
            putInt("printer_port", state.printerPort)
            putBoolean("use_tls", state.useTls)
            putNullableString("pinned_cert_sha256", state.pinnedCertSha256)
            putString("media_size", state.mediaSize)
            putInt("inactivity_threshold_days", state.inactivityThresholdDays.coerceIn(1, 30))
            putBoolean("auto_purge_enabled", state.autoPurgeEnabled)
            putNullableInt("last_mono_pages", state.lastMonoPages)
            putNullableInt("last_color_pages", state.lastColorPages)
            putNullableInt("last_total_pages", state.lastTotalPages)
            putNullableString("last_mono_activity_iso", state.lastMonoActivityIso)
            putNullableString("last_color_activity_iso", state.lastColorActivityIso)
            putNullableString("last_total_activity_iso", state.lastTotalActivityIso)
            putNullableString("last_mono_purge_iso", state.lastMonoPurgeIso)
            putNullableString("last_color_purge_iso", state.lastColorPurgeIso)
            putNullableString("last_model", state.lastModel)
            putString("last_printer_kind", state.lastPrinterKind.name)
            putString("last_head_technology", state.lastHeadTechnology.name)
            apply()
        }
    }

    fun getLastSummary(): String = prefs.getString("last_summary", "Nessun controllo ancora eseguito.") ?: ""

    fun saveLastSummary(summary: String) {
        prefs.edit().putString("last_summary", summary).apply()
    }

    private fun android.content.SharedPreferences.Editor.putNullableInt(key: String, value: Int?) {
        if (value == null) remove(key) else putInt(key, value)
    }

    private fun android.content.SharedPreferences.Editor.putNullableString(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    private inline fun <reified T : Enum<T>> enumValueOr(name: String?, fallback: T): T =
        if (name == null) fallback else runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
}

package com.umbette.nozzleguardian.android

import android.content.Context
import com.umbette.nozzleguardian.engine.AccountSession
import com.umbette.nozzleguardian.engine.BrandBadge
import com.umbette.nozzleguardian.engine.EntitlementState
import com.umbette.nozzleguardian.engine.GuardianState
import com.umbette.nozzleguardian.engine.HeadTechnology
import com.umbette.nozzleguardian.engine.LocationGroup
import com.umbette.nozzleguardian.engine.MultiPrinterWorkspace
import com.umbette.nozzleguardian.engine.PlanTier
import com.umbette.nozzleguardian.engine.PrinterClassification
import com.umbette.nozzleguardian.engine.PrinterKind
import com.umbette.nozzleguardian.engine.PrinterProfile
import com.umbette.nozzleguardian.engine.PrinterSilhouette
import com.umbette.nozzleguardian.engine.WorkspaceEngine
import org.json.JSONArray
import org.json.JSONObject

class StateStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("nozzle_guardian_prefs", Context.MODE_PRIVATE)

    fun loadState(): GuardianState {
        val ws = loadWorkspace()
        return ws.activePrinter?.state ?: loadLegacySingleState()
    }

    fun saveState(state: GuardianState) {
        saveLegacySingleState(state)
        val ws = loadWorkspace()
        val active = ws.activePrinter
        val updatedWorkspace = if (active != null) {
            val updatedProfile = active.copy(state = state)
            WorkspaceEngine.upsertPrinter(ws, updatedProfile, setActive = true)
        } else if (state.printerHost.isNotBlank()) {
            val (brand, silhouette) = PrinterClassification.inferVisuals(state.lastModel)
            val newProfile = PrinterProfile(
                id = "prt_primary",
                locationId = ws.locations.firstOrNull()?.id ?: WorkspaceEngine.DEFAULT_LOCATION_ID,
                displayName = state.lastModel ?: state.printerHost,
                brand = brand,
                silhouette = silhouette,
                state = state,
            )
            WorkspaceEngine.upsertPrinter(ws, newProfile, setActive = true)
        } else {
            ws
        }
        saveWorkspace(updatedWorkspace, syncLegacy = false)
    }

    fun loadWorkspace(): MultiPrinterWorkspace {
        val raw = prefs.getString("workspace_json", null)
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching { parseWorkspaceJson(raw) }.getOrNull()
            if (parsed != null) return WorkspaceEngine.ensureDefaultLocation(parsed)
        }
        // Seamless one-time migration from legacy single-printer prefs
        val legacy = loadLegacySingleState()
        val defaultLoc = LocationGroup(id = WorkspaceEngine.DEFAULT_LOCATION_ID, name = "Casa")
        return if (legacy.printerHost.isNotBlank()) {
            val (brand, silhouette) = PrinterClassification.inferVisuals(legacy.lastModel)
            val p = PrinterProfile(
                id = "prt_primary",
                locationId = defaultLoc.id,
                displayName = legacy.lastModel ?: legacy.printerHost,
                brand = brand,
                silhouette = silhouette,
                state = legacy,
            )
            MultiPrinterWorkspace(
                locations = listOf(defaultLoc),
                printers = listOf(p),
                activePrinterId = p.id,
            )
        } else {
            MultiPrinterWorkspace(locations = listOf(defaultLoc), printers = emptyList(), activePrinterId = null)
        }
    }

    fun saveWorkspace(workspace: MultiPrinterWorkspace, syncLegacy: Boolean = true) {
        val normalized = WorkspaceEngine.ensureDefaultLocation(workspace)
        prefs.edit().putString("workspace_json", serializeWorkspaceJson(normalized)).apply()
        if (syncLegacy) {
            normalized.activePrinter?.state?.let { saveLegacySingleState(it) }
        }
    }

    fun loadEntitlement(): EntitlementState {
        val isPlay = BillingManager.detectPlayStoreInstaller(context)
        val tier = enumValueOr(prefs.getString("entitlement_tier", null), PlanTier.FREE)
        val source = prefs.getString("entitlement_source", "free") ?: "free"
        val productId = prefs.getString("entitlement_product_id", null)
        val previewDefault = !isPlay && !prefs.contains("preview_pro_enabled")
        val previewPro = if (isPlay) false else prefs.getBoolean("preview_pro_enabled", previewDefault)
        return EntitlementState(
            tier = tier,
            source = source,
            productId = productId,
            isPlayStoreInstall = isPlay,
            previewProEnabled = previewPro,
        )
    }

    fun saveEntitlement(entitlement: EntitlementState) {
        prefs.edit().apply {
            putString("entitlement_tier", entitlement.tier.name)
            putString("entitlement_source", entitlement.source)
            putNullableString("entitlement_product_id", entitlement.productId)
            putBoolean("preview_pro_enabled", entitlement.previewProEnabled)
            apply()
        }
    }

    fun loadAccount(): AccountSession = AccountSession(
        signedIn = prefs.getBoolean("account_signed_in", false),
        email = prefs.getString("account_email", "") ?: "",
        displayName = prefs.getString("account_display_name", "") ?: "",
        syncServerUrl = prefs.getString("account_sync_url", "") ?: "",
        lastSyncedIso = prefs.getString("account_last_synced_iso", null),
    )

    fun saveAccount(account: AccountSession) {
        prefs.edit().apply {
            putBoolean("account_signed_in", account.signedIn)
            putString("account_email", account.email.trim())
            putString("account_display_name", account.displayName.trim())
            putString("account_sync_url", account.syncServerUrl.trim())
            putNullableString("account_last_synced_iso", account.lastSyncedIso)
            apply()
        }
    }

    fun getLastSummary(): String = prefs.getString("last_summary", "Nessun controllo ancora eseguito.") ?: ""

    fun saveLastSummary(summary: String) {
        prefs.edit().putString("last_summary", summary).apply()
    }

    private fun loadLegacySingleState(): GuardianState = GuardianState(
        printerHost = prefs.getString("printer_host", "") ?: "",
        printerPort = prefs.getInt("printer_port", 631),
        useTls = prefs.getBoolean("use_tls", false),
        pinnedCertSha256 = prefs.getString("pinned_cert_sha256", null),
        mediaSize = prefs.getString("media_size", "auto") ?: "auto",
        preferredFormat = prefs.getString("preferred_format", "auto") ?: "auto",
        mediaType = prefs.getString("media_type", "auto") ?: "auto",
        inkProfile = prefs.getString("ink_profile", "auto") ?: "auto",
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

    private fun saveLegacySingleState(state: GuardianState) {
        prefs.edit().apply {
            putString("printer_host", state.printerHost.trim())
            putInt("printer_port", state.printerPort)
            putBoolean("use_tls", state.useTls)
            putNullableString("pinned_cert_sha256", state.pinnedCertSha256)
            putString("media_size", state.mediaSize)
            putString("preferred_format", state.preferredFormat)
            putString("media_type", state.mediaType)
            putString("ink_profile", state.inkProfile)
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

    private fun serializeWorkspaceJson(ws: MultiPrinterWorkspace): String {
        val root = JSONObject()
        root.put("activePrinterId", ws.activePrinterId ?: JSONObject.NULL)
        val locsArr = JSONArray()
        ws.locations.forEach { loc ->
            locsArr.put(
                JSONObject().apply {
                    put("id", loc.id)
                    put("name", loc.name)
                    put("wifiSsid", loc.wifiSsid)
                    put("iconKey", loc.iconKey)
                }
            )
        }
        root.put("locations", locsArr)

        val prtArr = JSONArray()
        ws.printers.forEach { p ->
            prtArr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("locationId", p.locationId)
                    put("displayName", p.displayName)
                    put("brand", p.brand.name)
                    put("silhouette", p.silhouette.name)
                    put("state", serializeGuardianState(p.state))
                }
            )
        }
        root.put("printers", prtArr)
        return root.toString()
    }

    private fun parseWorkspaceJson(raw: String): MultiPrinterWorkspace {
        val root = JSONObject(raw)
        val activeId = if (root.isNull("activePrinterId")) null else root.optString("activePrinterId", "").ifBlank { null }
        val locations = mutableListOf<LocationGroup>()
        val locsArr = root.optJSONArray("locations") ?: JSONArray()
        for (i in 0 until locsArr.length()) {
            val o = locsArr.getJSONObject(i)
            locations.add(
                LocationGroup(
                    id = o.optString("id", "loc_$i"),
                    name = o.optString("name", "Casa"),
                    wifiSsid = o.optString("wifiSsid", ""),
                    iconKey = o.optString("iconKey", "home"),
                )
            )
        }
        val printers = mutableListOf<PrinterProfile>()
        val prtArr = root.optJSONArray("printers") ?: JSONArray()
        for (i in 0 until prtArr.length()) {
            val o = prtArr.getJSONObject(i)
            printers.add(
                PrinterProfile(
                    id = o.optString("id", "prt_$i"),
                    locationId = o.optString("locationId", WorkspaceEngine.DEFAULT_LOCATION_ID),
                    displayName = o.optString("displayName", "Stampante"),
                    brand = enumValueOr(o.optString("brand", ""), BrandBadge.GENERIC),
                    silhouette = enumValueOr(o.optString("silhouette", ""), PrinterSilhouette.TANK_4_COMPACT),
                    state = parseGuardianState(o.optJSONObject("state") ?: JSONObject()),
                )
            )
        }
        return MultiPrinterWorkspace(locations = locations, printers = printers, activePrinterId = activeId)
    }

    private fun serializeGuardianState(s: GuardianState): JSONObject = JSONObject().apply {
        put("printerHost", s.printerHost)
        put("printerPort", s.printerPort)
        put("useTls", s.useTls)
        put("pinnedCertSha256", s.pinnedCertSha256 ?: JSONObject.NULL)
        put("mediaSize", s.mediaSize)
        put("preferredFormat", s.preferredFormat)
        put("mediaType", s.mediaType)
        put("inkProfile", s.inkProfile)
        put("inactivityThresholdDays", s.inactivityThresholdDays)
        put("autoPurgeEnabled", s.autoPurgeEnabled)
        put("lastMonoPages", s.lastMonoPages ?: JSONObject.NULL)
        put("lastColorPages", s.lastColorPages ?: JSONObject.NULL)
        put("lastTotalPages", s.lastTotalPages ?: JSONObject.NULL)
        put("lastMonoActivityIso", s.lastMonoActivityIso ?: JSONObject.NULL)
        put("lastColorActivityIso", s.lastColorActivityIso ?: JSONObject.NULL)
        put("lastTotalActivityIso", s.lastTotalActivityIso ?: JSONObject.NULL)
        put("lastMonoPurgeIso", s.lastMonoPurgeIso ?: JSONObject.NULL)
        put("lastColorPurgeIso", s.lastColorPurgeIso ?: JSONObject.NULL)
        put("lastModel", s.lastModel ?: JSONObject.NULL)
        put("lastPrinterKind", s.lastPrinterKind.name)
        put("lastHeadTechnology", s.lastHeadTechnology.name)
    }

    private fun parseGuardianState(o: JSONObject): GuardianState = GuardianState(
        printerHost = o.optString("printerHost", ""),
        printerPort = o.optInt("printerPort", 631),
        useTls = o.optBoolean("useTls", false),
        pinnedCertSha256 = if (o.isNull("pinnedCertSha256")) null else o.optString("pinnedCertSha256", "").ifBlank { null },
        mediaSize = o.optString("mediaSize", "auto"),
        preferredFormat = o.optString("preferredFormat", "auto"),
        mediaType = o.optString("mediaType", "auto"),
        inkProfile = o.optString("inkProfile", "auto"),
        inactivityThresholdDays = o.optInt("inactivityThresholdDays", 5),
        autoPurgeEnabled = o.optBoolean("autoPurgeEnabled", false),
        lastMonoPages = if (o.isNull("lastMonoPages")) null else o.optInt("lastMonoPages"),
        lastColorPages = if (o.isNull("lastColorPages")) null else o.optInt("lastColorPages"),
        lastTotalPages = if (o.isNull("lastTotalPages")) null else o.optInt("lastTotalPages"),
        lastMonoActivityIso = if (o.isNull("lastMonoActivityIso")) null else o.optString("lastMonoActivityIso", "").ifBlank { null },
        lastColorActivityIso = if (o.isNull("lastColorActivityIso")) null else o.optString("lastColorActivityIso", "").ifBlank { null },
        lastTotalActivityIso = if (o.isNull("lastTotalActivityIso")) null else o.optString("lastTotalActivityIso", "").ifBlank { null },
        lastMonoPurgeIso = if (o.isNull("lastMonoPurgeIso")) null else o.optString("lastMonoPurgeIso", "").ifBlank { null },
        lastColorPurgeIso = if (o.isNull("lastColorPurgeIso")) null else o.optString("lastColorPurgeIso", "").ifBlank { null },
        lastModel = if (o.isNull("lastModel")) null else o.optString("lastModel", "").ifBlank { null },
        lastPrinterKind = enumValueOr(o.optString("lastPrinterKind", ""), PrinterKind.INKJET),
        lastHeadTechnology = enumValueOr(o.optString("lastHeadTechnology", ""), HeadTechnology.PIEZO),
    )

    private fun android.content.SharedPreferences.Editor.putNullableInt(key: String, value: Int?) {
        if (value == null) remove(key) else putInt(key, value)
    }

    private fun android.content.SharedPreferences.Editor.putNullableString(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    private inline fun <reified T : Enum<T>> enumValueOr(name: String?, fallback: T): T =
        if (name.isNullOrBlank()) fallback else runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
}

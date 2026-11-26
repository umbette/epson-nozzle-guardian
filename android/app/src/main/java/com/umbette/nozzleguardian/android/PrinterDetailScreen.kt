package com.umbette.nozzleguardian.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umbette.nozzleguardian.R
import com.umbette.nozzleguardian.engine.PrinterClassification
import com.umbette.nozzleguardian.engine.PrinterKind
import com.umbette.nozzleguardian.engine.PrinterProfile
import com.umbette.nozzleguardian.engine.ProFeature
import com.umbette.nozzleguardian.engine.PurgeMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrinterDetailScreen(
    profile: PrinterProfile,
    ui: MainUiState,
    viewModel: NozzleGuardianViewModel,
    onOpenScannerTab: () -> Unit,
) {
    BackHandler { viewModel.closePrinterDetail() }

    val st = ui.printerStatuses[profile.id]
    val cfg = profile.state
    var hostInput by remember(profile.id, cfg.printerHost) { mutableStateOf(cfg.printerHost) }
    var portInput by remember(profile.id, cfg.printerPort) { mutableStateOf(cfg.printerPort.toString()) }
    var useTls by remember(profile.id, cfg.useTls) { mutableStateOf(cfg.useTls) }
    var autoPurge by remember(profile.id, cfg.autoPurgeEnabled) { mutableStateOf(cfg.autoPurgeEnabled) }
    var preferredFormat by remember(profile.id, cfg.preferredFormat) { mutableStateOf(cfg.preferredFormat) }
    var mediaType by remember(profile.id, cfg.mediaType) { mutableStateOf(cfg.mediaType) }
    var inkProfile by remember(profile.id, cfg.inkProfile) { mutableStateOf(cfg.inkProfile) }
    var locationId by remember(profile.id, profile.locationId) { mutableStateOf(profile.locationId) }
    var daysFloat by remember(profile.id, cfg.inactivityThresholdDays) {
        mutableFloatStateOf(cfg.inactivityThresholdDays.toFloat())
    }
    var showTechSettings by remember { mutableStateOf(cfg.printerHost.isBlank()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = CardSurface,
            title = { Text(stringResource(R.string.detail_delete_printer), color = TextPrimary) },
            text = { Text(stringResource(R.string.detail_delete_printer_confirm, profile.displayName), color = TextMuted) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.removePrinterProfile(profile.id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed, contentColor = TextPrimary),
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = TextMuted)
                }
            },
        )
    }

    // Top Navigation Sub-header
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        IconButton(onClick = { viewModel.closePrinterDetail() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = TextPrimary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                profile.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val locName = ui.workspace.locations.firstOrNull { it.id == profile.locationId }?.name ?: ""
            Text(
                "$locName • ${cfg.printerHost.ifBlank { stringResource(R.string.home_unconfigured_host) }}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 1,
            )
        }
        IconButton(onClick = { viewModel.refreshStatus(profile.id, runDormancyCheck = true) }, enabled = !ui.isBusy) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh), tint = AccentCyan)
        }
        IconButton(onClick = { showDeleteConfirm = true }) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete), tint = StatusRed)
        }
    }

    // 1. Hero Status & Visual Ink/Toner Card
    val effectiveKind = st?.printerKind ?: cfg.lastPrinterKind
    val effectiveTech = st?.headTechnology ?: cfg.lastHeadTechnology
    val (heroBrand, heroSilhouette) = PrinterClassification.inferVisuals(
        makeAndModel = st?.makeAndModel ?: profile.displayName,
        tankCount = st?.tankCount ?: 4,
        kind = effectiveKind,
    )

    ModernCard(
        borderColor = when {
            effectiveKind == PrinterKind.LASER -> StatusAmber.copy(alpha = 0.45f)
            st?.online == true -> StatusGreen.copy(alpha = 0.35f)
            st?.online == false -> StatusRed.copy(alpha = 0.35f)
            else -> CardBorder
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PrinterSilhouetteArt(heroSilhouette, heroBrand, size = 54.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrandPill(heroBrand)
                    Text(
                        st?.makeAndModel ?: profile.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "${stringResource(headTechTitleRes(effectiveTech))} • ${stringResource(silhouetteTitleRes(heroSilhouette))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
            when {
                effectiveKind == PrinterKind.LASER -> StatusBadge(stringResource(R.string.badge_laser), StatusAmber)
                st?.online == true -> StatusBadge(stringResource(R.string.badge_online), StatusGreen)
                st?.online == false -> StatusBadge(stringResource(R.string.badge_offline), StatusRed)
            }
        }

        if (st == null) {
            Text(stringResource(R.string.home_never_checked), style = MaterialTheme.typography.bodySmall, color = TextMuted)
            OutlinedButton(
                onClick = { viewModel.refreshStatus(profile.id, runDormancyCheck = false) },
                enabled = !ui.isBusy && cfg.printerHost.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(42.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.detail_verify_now))
            }
        } else {
            if (st.isMediaEmpty) {
                Text(stringResource(R.string.detail_media_empty), color = StatusRed, style = MaterialTheme.typography.bodySmall)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill(stringResource(R.string.detail_counters_bw), st.monochromePages?.toString() ?: "—", Modifier.weight(1f))
                StatPill(stringResource(R.string.detail_counters_color), st.colorPages?.toString() ?: "—", Modifier.weight(1f))
                StatPill(stringResource(R.string.detail_counters_total), st.totalPages?.toString() ?: "—", Modifier.weight(1f))
            }
            if (st.markers.isNotEmpty()) {
                val chunks = st.markers.chunked(2)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    chunks.forEach { rowMarkers ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowMarkers.forEach { marker ->
                                InkTankBar(marker, modifier = Modifier.weight(1f))
                            }
                            if (rowMarkers.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    // 2. Educational Laser Notice OR Inkjet Controls
    if (effectiveKind == PrinterKind.LASER) {
        ModernCard(borderColor = StatusAmber.copy(alpha = 0.45f), containerColor = StatusAmber.copy(alpha = 0.08f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = StatusAmber, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.detail_laser_title), style = MaterialTheme.typography.titleSmall, color = StatusAmber)
            }
            Text(
                stringResource(R.string.detail_laser_body, st?.makeAndModel ?: profile.displayName),
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
            )
        }
    } else {
        // Direct Micro-Purge Card (Always Free)
        ModernCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.CleaningServices, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.detail_purge_card_title), style = MaterialTheme.typography.titleSmall)
            }
            val canPurge = cfg.printerHost.isNotBlank() && !ui.isBusy
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = { viewModel.triggerManualPurge(PurgeMode.K, profile.id) },
                    enabled = canPurge,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).height(46.dp),
                ) {
                    Text(stringResource(R.string.detail_purge_k), maxLines = 1, softWrap = false, fontSize = 13.sp)
                }
                FilledTonalButton(
                    onClick = { viewModel.triggerManualPurge(PurgeMode.CMY, profile.id) },
                    enabled = canPurge,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).height(46.dp),
                ) {
                    Text(stringResource(R.string.detail_purge_color), maxLines = 1, softWrap = false, fontSize = 13.sp)
                }
                Button(
                    onClick = { viewModel.triggerManualPurge(PurgeMode.CMYK, profile.id) },
                    enabled = canPurge,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).height(46.dp),
                ) {
                    Text(stringResource(R.string.detail_purge_cmyk), maxLines = 1, softWrap = false, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            val isSixTank = inkProfile == "cmyk6" || st?.inkProfile == "cmyk6" || (st?.tankCount ?: 4) >= 6
            if (isSixTank) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { viewModel.triggerManualPurge(PurgeMode.CMYK6, profile.id) },
                        enabled = canPurge,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E), contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Text(stringResource(R.string.detail_purge_6tank), maxLines = 1, softWrap = false, fontSize = 13.sp)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.triggerManualPurge(PurgeMode.PB_GY, profile.id) },
                        enabled = canPurge,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Text(stringResource(R.string.detail_purge_pb_gy), maxLines = 1, softWrap = false, fontSize = 13.sp)
                    }
                }
            }
        }

        // Per-Printer Frequency & Schedule Card
        val hasAutoPurgeAccess = ui.entitlement.hasAccess(ProFeature.AUTO_PURGE)
        val switchColors = SwitchDefaults.colors(
            checkedThumbColor = BgDeep,
            checkedTrackColor = AccentCyan,
            uncheckedTrackColor = CardSurfaceAlt,
            uncheckedBorderColor = CardBorder,
        )
        val recommendedDays = PrinterClassification.recommendedThresholdDays(effectiveKind, effectiveTech, st?.tankCount ?: 4)

        ModernCard {
            Text(stringResource(R.string.detail_schedule_card_title), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.detail_auto_purge_title), fontWeight = FontWeight.Medium)
                        if (!hasAutoPurgeAccess) {
                            ProBadge(stringResource(R.string.badge_pro))
                        }
                    }
                    Text(stringResource(R.string.detail_auto_purge_subtitle), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
                Switch(
                    checked = autoPurge && hasAutoPurgeAccess,
                    onCheckedChange = { wantEnable ->
                        if (wantEnable && !hasAutoPurgeAccess) {
                            viewModel.requestPaywall(ProFeature.AUTO_PURGE)
                        } else {
                            autoPurge = wantEnable
                        }
                    },
                    colors = switchColors,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.detail_inactivity_threshold), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.detail_days_unit, daysFloat.toInt()),
                        color = AccentCyan,
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 14.sp,
                    )
                }
                if (recommendedDays > 0) {
                    Text(
                        stringResource(R.string.detail_recommended_days, stringResource(headTechTitleRes(effectiveTech)), recommendedDays),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
                Slider(
                    value = daysFloat,
                    onValueChange = { daysFloat = it },
                    valueRange = 1f..14f,
                    steps = 12,
                    colors = SliderDefaults.colors(
                        thumbColor = AccentCyan,
                        activeTrackColor = AccentCyan,
                        inactiveTrackColor = CardBorder,
                    ),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        viewModel.updatePrinterById(
                            printerId = profile.id,
                            host = hostInput,
                            port = portInput.toIntOrNull() ?: 631,
                            useTls = useTls,
                            thresholdDays = daysFloat.toInt(),
                            autoPurgeEnabled = autoPurge && hasAutoPurgeAccess,
                            preferredFormat = preferredFormat,
                            mediaType = mediaType,
                            inkProfile = inkProfile,
                            locationId = locationId,
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { viewModel.refreshStatus(profile.id, runDormancyCheck = true) },
                    enabled = !ui.isBusy,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Text(stringResource(R.string.detail_verify_now), maxLines = 1, softWrap = false)
                }
            }

            OutlinedButton(
                onClick = onOpenScannerTab,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth().height(42.dp),
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.detail_open_scanner), color = AccentCyan, fontSize = 13.sp, maxLines = 1)
            }
        }
    }

    // 3. Collapsible Technical Connection & Format Settings
    ModernCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showTechSettings = !showTechSettings },
        ) {
            Text(stringResource(R.string.detail_tech_settings_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (showTechSettings) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = TextMuted,
            )
        }

        AnimatedVisibility(visible = showTechSettings) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NgTextField(
                        value = hostInput,
                        onValueChange = { hostInput = it },
                        label = stringResource(R.string.detail_ip_label),
                        modifier = Modifier.weight(2.2f),
                    )
                    NgTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter { c -> c.isDigit() } },
                        label = stringResource(R.string.detail_port_label),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.detail_ipps_tls), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = useTls, onCheckedChange = { useTls = it })
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactSelector(
                        label = stringResource(R.string.detail_location_assign),
                        current = locationId,
                        options = ui.workspace.locations.map { it.id to it.name },
                        onSelect = { locationId = it },
                    )
                    CompactSelector(
                        label = stringResource(R.string.detail_format_label),
                        current = preferredFormat,
                        options = listOf(
                            "auto" to stringResource(R.string.detail_format_auto),
                            "image/pwg-raster" to "PWG-Raster",
                            "application/pdf" to "PDF",
                            "image/jpeg" to "JPEG",
                            "image/png" to "PNG",
                        ),
                        onSelect = { preferredFormat = it },
                    )
                    CompactSelector(
                        label = stringResource(R.string.detail_paper_label),
                        current = mediaType,
                        options = listOf(
                            "auto" to stringResource(R.string.detail_paper_auto),
                            "stationery" to stringResource(R.string.detail_paper_plain),
                            "photographic-glossy" to stringResource(R.string.detail_paper_glossy),
                            "photographic-matte" to stringResource(R.string.detail_paper_matte),
                        ),
                        onSelect = { mediaType = it },
                    )
                    CompactSelector(
                        label = stringResource(R.string.detail_tanks_label),
                        current = inkProfile,
                        options = listOf(
                            "auto" to stringResource(R.string.detail_tanks_auto),
                            "cmyk" to stringResource(R.string.detail_tanks_4),
                            "cmyk6" to stringResource(R.string.detail_tanks_6),
                        ),
                        onSelect = { inkProfile = it },
                    )
                }

                Button(
                    onClick = {
                        viewModel.updatePrinterById(
                            printerId = profile.id,
                            host = hostInput,
                            port = portInput.toIntOrNull() ?: 631,
                            useTls = useTls,
                            thresholdDays = daysFloat.toInt(),
                            autoPurgeEnabled = autoPurge && ui.entitlement.hasAccess(ProFeature.AUTO_PURGE),
                            preferredFormat = preferredFormat,
                            mediaType = mediaType,
                            inkProfile = inkProfile,
                            locationId = locationId,
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

package com.umbette.nozzleguardian.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Opacity
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
) {
    BackHandler { viewModel.closePrinterDetail() }

    val st = ui.printerStatuses[profile.id]
    val cfg = profile.state
    var subTab by remember(profile.id) { mutableIntStateOf(0) } // 0 = Clean & Verify, 1 = Settings
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

    // Printer Hub Header
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

    // Natural 2-Pill Hub Switcher: Clean & Verify (unified print + verify flow) | Settings
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardSurface)
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
            .padding(4.dp),
    ) {
        val tabs = listOf(
            R.string.subtab_clean_verify,
            R.string.subtab_settings,
        )
        tabs.forEachIndexed { idx, titleRes ->
            val selected = subTab == idx
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) AccentCyan.copy(alpha = 0.20f) else Color.Transparent)
                    .clickable { subTab = idx },
            ) {
                Text(
                    text = stringResource(titleRes),
                    fontSize = 12.5.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) AccentCyan else TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }

    val effectiveKind = st?.printerKind ?: cfg.lastPrinterKind
    val effectiveTech = st?.headTechnology ?: cfg.lastHeadTechnology
    val (heroBrand, heroSilhouette) = PrinterClassification.inferVisuals(
        makeAndModel = st?.makeAndModel ?: profile.displayName,
        tankCount = st?.tankCount ?: 4,
        kind = effectiveKind,
    )

    if (subTab == 0) {
        // Unified Maintenance View: Status -> Step 1 Print Test/Purge -> Step 2 Verify Sheet
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
                    Spacer(Modifier.size(6.dp))
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
            }
        }

        val reliableInk = st?.reliableInkMarkers.orEmpty()
        if (reliableInk.isNotEmpty()) {
            ModernCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Opacity, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(
                            if (effectiveKind == PrinterKind.LASER) R.string.ink_card_title_toner else R.string.ink_card_title
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (st?.lowInkMarkers?.isNotEmpty() == true) {
                        StatusBadge(
                            label = stringResource(
                                if (effectiveKind == PrinterKind.LASER) R.string.toner_low_badge else R.string.ink_low_badge
                            ),
                            color = StatusAmber,
                        )
                    }
                }
                val chunks = reliableInk.chunked(2)
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
            // Step 1: Neutral selectable chips (none pre-highlighted!) + explicit Confirm & Print CTA
            ModernCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CleaningServices, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.detail_purge_card_title), style = MaterialTheme.typography.titleSmall)
                }
                val canPurge = cfg.printerHost.isNotBlank() && !ui.isBusy
                var selectedMode by remember(profile.id) { mutableStateOf<PurgeMode?>(null) }

                @Composable
                fun PurgeChoiceChip(
                    mode: PurgeMode,
                    title: String,
                    swatches: List<Color>,
                    modifier: Modifier = Modifier,
                ) {
                    val isSelected = selectedMode == mode
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = modifier
                            .height(54.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) AccentCyan.copy(alpha = 0.20f) else CardSurfaceAlt)
                            .border(
                                width = if (isSelected) 1.6.dp else 1.dp,
                                color = if (isSelected) AccentCyan else CardBorder,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable(enabled = canPurge) {
                                selectedMode = if (isSelected) null else mode
                            }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            InkSwatchRow(swatches)
                            Text(
                                text = title,
                                maxLines = 1,
                                softWrap = false,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) AccentCyan else TextPrimary,
                            )
                        }
                    }
                }

                val labelK = stringResource(R.string.detail_purge_k)
                val labelColor = stringResource(R.string.detail_purge_color)
                val labelAll = stringResource(R.string.detail_purge_cmyk)
                val labelAll6 = stringResource(R.string.detail_purge_6tank)
                val labelPbGy = stringResource(R.string.detail_purge_pb_gy)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    PurgeChoiceChip(PurgeMode.K, labelK, listOf(SwatchBlack), Modifier.weight(1f))
                    PurgeChoiceChip(PurgeMode.CMY, labelColor, listOf(SwatchCyan, SwatchMagenta, SwatchYellow), Modifier.weight(1f))
                    PurgeChoiceChip(PurgeMode.CMYK, labelAll, listOf(SwatchCyan, SwatchMagenta, SwatchYellow, SwatchBlack), Modifier.weight(1f))
                }
                val isSixTank = inkProfile == "cmyk6" || st?.inkProfile == "cmyk6" || (st?.tankCount ?: 4) >= 6
                if (isSixTank) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        PurgeChoiceChip(
                            PurgeMode.CMYK6,
                            labelAll6,
                            listOf(SwatchCyan, SwatchMagenta, SwatchYellow, SwatchBlack, SwatchGray),
                            Modifier.weight(1f),
                        )
                        PurgeChoiceChip(PurgeMode.PB_GY, labelPbGy, listOf(SwatchBlack, SwatchGray), Modifier.weight(1f))
                    }
                }

                val selectedTitle = when (selectedMode) {
                    PurgeMode.K -> labelK
                    PurgeMode.CMY -> labelColor
                    PurgeMode.CMYK -> labelAll
                    PurgeMode.CMYK6 -> labelAll6
                    PurgeMode.PB_GY -> labelPbGy
                    null -> null
                }

                if (selectedTitle == null) {
                    Text(stringResource(R.string.purge_select_hint), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }

                Button(
                    onClick = {
                        selectedMode?.let { mode ->
                            viewModel.triggerManualPurge(mode, profile.id)
                            selectedMode = null
                        }
                    },
                    enabled = canPurge && selectedMode != null,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Text(
                        text = if (selectedTitle != null) {
                            stringResource(R.string.purge_confirm_btn, selectedTitle)
                        } else {
                            stringResource(R.string.purge_select_hint)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Step 2: Verify Printed Sheet (right below Step 1 in the SAME screen!)
            PrinterScannerSection(profile = profile, ui = ui, viewModel = viewModel)
        }
    } else {
        // Sub-Tab 1: Rules, Dormancy Threshold & Network Settings
        val hasAutoPurgeAccess = ui.entitlement.hasAccess(ProFeature.AUTO_PURGE)
        val switchColors = SwitchDefaults.colors(
            checkedThumbColor = BgDeep,
            checkedTrackColor = AccentCyan,
            uncheckedTrackColor = CardSurfaceAlt,
            uncheckedBorderColor = CardBorder,
        )
        val recommendedDays = PrinterClassification.recommendedThresholdDays(effectiveKind, effectiveTech, st?.tankCount ?: 4)

        if (effectiveKind != PrinterKind.LASER) {
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
            }
        }

        ModernCard {
            Text(stringResource(R.string.detail_tech_settings_title), style = MaterialTheme.typography.titleSmall)
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
                Switch(checked = useTls, onCheckedChange = { useTls = it }, colors = switchColors)
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
                        autoPurgeEnabled = autoPurge && hasAutoPurgeAccess,
                        preferredFormat = preferredFormat,
                        mediaType = mediaType,
                        inkProfile = inkProfile,
                        locationId = locationId,
                    )
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

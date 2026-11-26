package com.umbette.nozzleguardian.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umbette.nozzleguardian.R
import com.umbette.nozzleguardian.engine.LocationGroup
import com.umbette.nozzleguardian.engine.PrinterClassification
import com.umbette.nozzleguardian.engine.PrinterKind
import com.umbette.nozzleguardian.engine.PrinterProfile
import com.umbette.nozzleguardian.engine.ProFeature

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeDashboardScreen(ui: MainUiState, viewModel: NozzleGuardianViewModel) {
    var selectedFilterLocId by remember { mutableStateOf<String?>(null) } // null = All locations
    var showLocationSheet by remember { mutableStateOf(false) }
    var editingLocation by remember { mutableStateOf<LocationGroup?>(null) }
    var showAddPrinterSheet by remember { mutableStateOf(false) }

    val locById = ui.workspace.locations.associateBy { it.id }
    val activeLocationId = selectedFilterLocId ?: ui.workspace.locations.firstOrNull()?.id ?: "loc_home"

    if (showLocationSheet) {
        LocationManagerSheet(
            locations = ui.workspace.locations,
            isPro = ui.entitlement.isProActive,
            initialEdit = editingLocation,
            onDismiss = {
                showLocationSheet = false
                editingLocation = null
            },
            onCreate = { name, ssid, icon ->
                viewModel.addLocationGroup(name, ssid, icon)
                showLocationSheet = false
                editingLocation = null
            },
            onUpdate = { id, name, ssid, icon ->
                viewModel.updateLocationGroup(id, name, ssid, icon)
                showLocationSheet = false
                editingLocation = null
            },
            onDelete = { id ->
                viewModel.removeLocationGroup(id)
                if (selectedFilterLocId == id) selectedFilterLocId = null
                showLocationSheet = false
                editingLocation = null
            },
            onRequestPro = {
                showLocationSheet = false
                viewModel.requestPaywall(ProFeature.MULTI_PRINTER_LOCATIONS)
            },
        )
    }

    if (showAddPrinterSheet) {
        AddPrinterSheet(
            locationName = locById[activeLocationId]?.name ?: stringResource(R.string.loc_icon_home),
            onDismiss = { showAddPrinterSheet = false },
            onSave = { name, host, port, useTls ->
                viewModel.addPrinterProfile(activeLocationId, name, host, port, useTls, openDetail = true)
                showAddPrinterSheet = false
            },
        )
    }

    // 1. Locations Bar + Manage Action
    ModernCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.home_locations_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (!ui.entitlement.isProActive) {
                ProBadge(stringResource(R.string.badge_multi_pro))
            }
            FilledTonalButton(
                onClick = {
                    editingLocation = selectedFilterLocId?.let { locById[it] }
                    showLocationSheet = true
                },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.home_location_manage), fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val allSelected = selectedFilterLocId == null
            Surface(
                shape = RoundedCornerShape(50),
                color = if (allSelected) AccentCyan.copy(alpha = 0.20f) else CardSurfaceAlt,
                border = BorderStroke(1.dp, if (allSelected) AccentCyan else CardBorder),
                modifier = Modifier.clickable { selectedFilterLocId = null },
            ) {
                Text(
                    text = stringResource(R.string.home_location_all),
                    fontSize = 12.sp,
                    fontWeight = if (allSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (allSelected) AccentCyan else TextPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            ui.workspace.locations.forEach { loc ->
                val selected = loc.id == selectedFilterLocId
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (selected) AccentCyan.copy(alpha = 0.20f) else CardSurfaceAlt,
                    border = BorderStroke(1.dp, if (selected) AccentCyan else CardBorder),
                    modifier = Modifier.clickable { selectedFilterLocId = loc.id },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = locationIconVector(loc.iconKey),
                            contentDescription = null,
                            tint = if (selected) AccentCyan else TextMuted,
                            modifier = Modifier.size(14.dp),
                        )
                        val ssidSuffix = if (loc.wifiSsid.isNotBlank()) " • ${loc.wifiSsid}" else ""
                        Text(
                            text = "${loc.name}$ssidSuffix",
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) AccentCyan else TextPrimary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    // 2. Printer Cards (filtered strictly by chosen location, no cross-location leak!)
    val visiblePrinters = if (selectedFilterLocId == null) {
        ui.workspace.printers
    } else {
        ui.workspace.printers.filter { it.locationId == selectedFilterLocId }
    }

    if (visiblePrinters.isEmpty()) {
        val locName = selectedFilterLocId?.let { locById[it]?.name } ?: stringResource(R.string.loc_icon_home)
        ModernCard {
            Text(
                stringResource(R.string.home_empty_location_title, locName),
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Text(
                stringResource(R.string.home_empty_location_body),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.discoverPrinters() },
                    enabled = !ui.isDiscovering,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Icon(Icons.Filled.WifiFind, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.home_discover_mdns), maxLines = 1, softWrap = false, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = {
                        if (!ui.entitlement.isProActive && ui.workspace.printers.isNotEmpty()) {
                            viewModel.requestPaywall(ProFeature.MULTI_PRINTER_LOCATIONS)
                        } else {
                            showAddPrinterSheet = true
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.home_add_printer_manual), maxLines = 1, softWrap = false, fontSize = 12.sp)
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            visiblePrinters.forEach { profile ->
                val cached = ui.printerStatuses[profile.id]
                val locationName = locById[profile.locationId]?.name
                PrinterOverviewCard(
                    profile = profile,
                    locationName = locationName,
                    cachedOnline = cached?.online,
                    cachedKind = cached?.printerKind ?: profile.state.lastPrinterKind,
                    onClick = { viewModel.openPrinterDetail(profile.id) },
                )
            }
        }

        // Quick actions row (mDNS scan + Add printer)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = { viewModel.discoverPrinters() },
                enabled = !ui.isDiscovering,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(44.dp),
            ) {
                Icon(Icons.Filled.WifiFind, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.home_discover_mdns), maxLines = 1, softWrap = false, fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = {
                    if (!ui.entitlement.isProActive && ui.workspace.printers.isNotEmpty()) {
                        viewModel.requestPaywall(ProFeature.MULTI_PRINTER_LOCATIONS)
                    } else {
                        showAddPrinterSheet = true
                    }
                },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.45f)),
                modifier = Modifier.weight(1f).height(44.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.home_add_printer_manual), color = AccentCyan, maxLines = 1, softWrap = false, fontSize = 12.sp)
            }
        }
    }

    // 3. Discovered Wi-Fi Printers Card (shown when mDNS found devices)
    if (ui.discovered.isNotEmpty()) {
        ModernCard {
            Text(stringResource(R.string.home_discovered_title), style = MaterialTheme.typography.titleSmall)
            ui.discovered.forEach { dev ->
                val (devBrand, devSilhouette) = PrinterClassification.inferVisuals(dev.makeAndModel, kind = dev.printerKind)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardSurfaceAlt)
                        .border(1.dp, AccentCyan.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        .clickable { viewModel.selectDiscoveredPrinter(dev, activeLocationId) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrinterSilhouetteArt(devSilhouette, devBrand, size = 38.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BrandPill(devBrand)
                            Text(
                                dev.makeAndModel,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "${dev.host}:${dev.port} • ${stringResource(headTechTitleRes(dev.headTechnology))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (dev.printerKind == PrinterKind.LASER) {
                        StatusBadge(stringResource(R.string.badge_laser), StatusAmber)
                    } else {
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AccentCyan)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrinterOverviewCard(
    profile: PrinterProfile,
    locationName: String?,
    cachedOnline: Boolean?,
    cachedKind: PrinterKind,
    onClick: () -> Unit,
) {
    ModernCard(
        borderColor = when (cachedOnline) {
            true -> StatusGreen.copy(alpha = 0.35f)
            false -> StatusRed.copy(alpha = 0.35f)
            null -> CardBorder
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PrinterSilhouetteArt(profile.silhouette, profile.brand, size = 50.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrandPill(profile.brand)
                    Text(
                        profile.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                val hostStr = profile.state.printerHost.ifBlank { stringResource(R.string.home_unconfigured_host) }
                val locTag = locationName?.let { "$it • " } ?: ""
                Text(
                    "$locTag$hostStr:${profile.state.printerPort}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val scheduleLabel = when {
                        cachedKind == PrinterKind.LASER -> stringResource(R.string.home_schedule_laser)
                        profile.state.autoPurgeEnabled -> stringResource(R.string.home_schedule_auto_days, profile.state.inactivityThresholdDays)
                        else -> stringResource(R.string.home_schedule_manual_days, profile.state.inactivityThresholdDays)
                    }
                    StatusBadge(
                        label = scheduleLabel,
                        color = if (cachedKind == PrinterKind.LASER) StatusAmber else AccentCyan,
                    )
                    when (cachedOnline) {
                        true -> StatusBadge(stringResource(R.string.badge_online), StatusGreen)
                        false -> StatusBadge(stringResource(R.string.badge_offline), StatusRed)
                        null -> Text(stringResource(R.string.home_tap_to_configure), fontSize = 11.sp, color = TextMuted)
                    }
                }
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationManagerSheet(
    locations: List<LocationGroup>,
    isPro: Boolean,
    initialEdit: LocationGroup?,
    onDismiss: () -> Unit,
    onCreate: (name: String, ssid: String, iconKey: String) -> Unit,
    onUpdate: (id: String, name: String, ssid: String, iconKey: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onRequestPro: () -> Unit,
) {
    var editing by remember(initialEdit) { mutableStateOf(initialEdit) }
    var creatingNew by remember(initialEdit) { mutableStateOf(false) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }

    var nameInput by remember(editing, creatingNew) { mutableStateOf(editing?.name ?: "") }
    var ssidInput by remember(editing, creatingNew) { mutableStateOf(editing?.wifiSsid ?: "") }
    var iconKey by remember(editing, creatingNew) { mutableStateOf(editing?.iconKey ?: "home") }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CardSurface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (editing != null || creatingNew) {
                Text(
                    if (editing != null) stringResource(R.string.home_edit_location) else stringResource(R.string.home_add_location),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                NgTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = stringResource(R.string.home_location_name_label),
                    modifier = Modifier.fillMaxWidth(),
                )
                NgTextField(
                    value = ssidInput,
                    onValueChange = { ssidInput = it },
                    label = stringResource(R.string.home_location_ssid_label),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.home_location_icon_label), fontSize = 12.sp, color = TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val iconOpts = listOf(
                        "home" to R.string.loc_icon_home,
                        "work" to R.string.loc_icon_work,
                        "studio" to R.string.loc_icon_studio,
                        "lab" to R.string.loc_icon_lab,
                    )
                    iconOpts.forEach { (key, titleRes) ->
                        val active = iconKey == key
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (active) AccentCyan.copy(alpha = 0.2f) else CardSurfaceAlt,
                            border = BorderStroke(1.dp, if (active) AccentCyan else CardBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { iconKey = key },
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(locationIconVector(key), contentDescription = null, tint = if (active) AccentCyan else TextMuted, modifier = Modifier.size(18.dp))
                                Text(stringResource(titleRes), fontSize = 11.sp, color = if (active) AccentCyan else TextPrimary, maxLines = 1)
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { editing = null; creatingNew = false },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = {
                            val currentEdit = editing
                            if (currentEdit != null) {
                                onUpdate(currentEdit.id, nameInput, ssidInput, iconKey)
                            } else {
                                onCreate(nameInput, ssidInput, iconKey)
                            }
                        },
                        enabled = nameInput.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_locations_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            if (!isPro && locations.isNotEmpty()) onRequestPro() else creatingNew = true
                        },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.home_add_location))
                    }
                }

                locations.forEach { loc ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardSurfaceAlt)
                            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(locationIconVector(loc.iconKey), contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(loc.name, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                if (loc.wifiSsid.isNotBlank()) {
                                    Text("Wi-Fi: ${loc.wifiSsid}", fontSize = 11.sp, color = TextMuted)
                                }
                            }
                            IconButton(onClick = { editing = loc }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit), tint = AccentCyan, modifier = Modifier.size(17.dp))
                            }
                            if (locations.size > 1) {
                                IconButton(onClick = { confirmDeleteId = loc.id }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete), tint = StatusRed, modifier = Modifier.size(17.dp))
                                }
                            }
                        }
                        if (confirmDeleteId == loc.id) {
                            Text(stringResource(R.string.home_delete_location_confirm, loc.name), fontSize = 12.sp, color = StatusAmber)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { confirmDeleteId = null }) {
                                    Text(stringResource(R.string.cancel), color = TextMuted)
                                }
                                Button(
                                    onClick = { onDelete(loc.id) },
                                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed, contentColor = TextPrimary),
                                ) {
                                    Text(stringResource(R.string.home_delete_location))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPrinterSheet(
    locationName: String,
    onDismiss: () -> Unit,
    onSave: (name: String, host: String, port: Int, useTls: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("631") }
    var useTls by remember { mutableStateOf(true) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CardSurface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.add_printer_title, locationName), style = MaterialTheme.typography.titleMedium)
            NgTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.add_printer_model_hint),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NgTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = stringResource(R.string.add_printer_ip_hint),
                    modifier = Modifier.weight(2.2f),
                )
                NgTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = stringResource(R.string.add_printer_port_hint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.detail_ipps_tls), color = TextPrimary, modifier = Modifier.weight(1f))
                Switch(checked = useTls, onCheckedChange = { useTls = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(46.dp)) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { onSave(name, host, port.toIntOrNull() ?: 631, useTls) },
                    enabled = host.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(46.dp),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

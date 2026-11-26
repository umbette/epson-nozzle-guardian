package com.umbette.nozzleguardian.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.umbette.nozzleguardian.engine.HeadTechnology
import com.umbette.nozzleguardian.engine.NozzleChannel
import com.umbette.nozzleguardian.engine.PrinterKind
import com.umbette.nozzleguardian.engine.PurgeMode
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<NozzleGuardianViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val darkPalette = darkColorScheme(
                primary = Color(0xFF38BDF8),
                secondary = Color(0xFFE879F9),
                background = Color(0xFF0F172A),
                surface = Color(0xFF1E293B),
            )
            MaterialTheme(colorScheme = darkPalette) {
                NozzleGuardianApp(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NozzleGuardianApp(viewModel: NozzleGuardianViewModel) {
    val ui by viewModel.uiState.collectAsState()
    var activeTab by remember { mutableIntStateOf(0) }

    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nozzle Guardian™", fontWeight = FontWeight.Bold) },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.Filled.Print, contentDescription = null) },
                    label = { Text("Stampante") },
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                    label = { Text("Scanner") },
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    label = { Text("Guida & Licenza") },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.isBusy || ui.isDiscovering) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (ui.bannerMessage.isNotBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(
                        text = ui.bannerMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            when (activeTab) {
                0 -> PrinterControlTab(ui, viewModel)
                1 -> ScannerSurgicalTab(ui, viewModel)
                else -> AboutLicenseTab()
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrinterControlTab(ui: MainUiState, viewModel: NozzleGuardianViewModel) {
    var hostInput by remember(ui.config.printerHost) { mutableStateOf(ui.config.printerHost) }
    var portInput by remember(ui.config.printerPort) { mutableStateOf(ui.config.printerPort.toString()) }
    var useTls by remember(ui.config.useTls) { mutableStateOf(ui.config.useTls) }
    var autoPurge by remember(ui.config.autoPurgeEnabled) { mutableStateOf(ui.config.autoPurgeEnabled) }
    var preferredFormat by remember(ui.config.preferredFormat) { mutableStateOf(ui.config.preferredFormat) }
    var mediaType by remember(ui.config.mediaType) { mutableStateOf(ui.config.mediaType) }
    var inkProfile by remember(ui.config.inkProfile) { mutableStateOf(ui.config.inkProfile) }
    var daysFloat by remember(ui.config.inactivityThresholdDays) {
        mutableFloatStateOf(ui.config.inactivityThresholdDays.toFloat())
    }

    Card {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "1. Stampante Wi-Fi (Zero Server)",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = { viewModel.discoverPrinters() }, enabled = !ui.isDiscovering) {
                    Icon(Icons.Filled.WifiFind, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Cerca mDNS", maxLines = 1, softWrap = false)
                }
            }

            ui.discovered.forEach { dev ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { viewModel.selectPrinter(dev) }
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(dev.makeAndModel, fontWeight = FontWeight.Medium)
                        Text("${dev.host}:${dev.port} • ${dev.headTechnology.label}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Usa", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hostInput,
                    onValueChange = { hostInput = it },
                    label = { Text("IP / Hostname") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Porta") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Usa IPPS (TLS locale)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = useTls, onCheckedChange = { useTls = it })
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-spurgo automatico (Wi-Fi casa)", fontWeight = FontWeight.Medium)
                    Text("WorkManager controlla ogni 12h quando sei sul Wi-Fi domestico", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = autoPurge, onCheckedChange = { autoPurge = it })
            }

            Text("Soglia inattività: ${daysFloat.toInt()} giorni", style = MaterialTheme.typography.bodyMedium)
            Slider(value = daysFloat, onValueChange = { daysFloat = it }, valueRange = 1f..14f, steps = 12)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactSelector(
                    label = "Formato",
                    current = preferredFormat,
                    options = listOf(
                        "auto" to "Auto",
                        "image/pwg-raster" to "PWG-Raster",
                        "application/pdf" to "PDF",
                        "image/jpeg" to "JPEG",
                        "image/png" to "PNG",
                    ),
                    onSelect = { preferredFormat = it },
                )
                CompactSelector(
                    label = "Carta",
                    current = mediaType,
                    options = listOf(
                        "auto" to "Auto (Plain/BK)",
                        "stationery" to "Plain (BK)",
                        "photographic-glossy" to "Photo Glossy (PB+GY)",
                        "photographic-matte" to "Photo Matte (PB+GY)",
                    ),
                    onSelect = { mediaType = it },
                )
                CompactSelector(
                    label = "Serbatoi",
                    current = inkProfile,
                    options = listOf(
                        "auto" to "Auto",
                        "cmyk" to "4-Tank CMYK",
                        "cmyk6" to "6-Tank Photo",
                    ),
                    onSelect = { inkProfile = it },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        viewModel.updatePrinterConfig(
                            host = hostInput,
                            port = portInput.toIntOrNull() ?: 631,
                            useTls = useTls,
                            thresholdDays = daysFloat.toInt(),
                            autoPurgeEnabled = autoPurge,
                            preferredFormat = preferredFormat,
                            mediaType = mediaType,
                            inkProfile = inkProfile,
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Salva") }
                OutlinedButton(
                    onClick = { viewModel.refreshStatus(runDormancyCheck = true) },
                    enabled = !ui.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Verifica ora")
                }
            }
        }
    }

    ui.status?.let { st ->
        Card {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(st.makeAndModel ?: "Stampante", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Stato: ${if (st.online) "ONLINE (${st.state})" else "OFFLINE"} • ${st.headTechnology.label}",
                    color = if (st.online) Color(0xFF4ADE80) else Color(0xFFF87171),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (st.printerKind == PrinterKind.LASER) {
                    Text("Laser/Toner rilevata: spurghi inchiostro bloccati.", color = Color(0xFFFBBF24))
                }
                if (st.isMediaEmpty) {
                    Text("Vassoio vuoto (media-empty): inserisci un foglio di scarto.", color = Color(0xFFF87171))
                }
                Text("Contatori -> B/N: ${st.monochromePages ?: "-"} • Colori: ${st.colorPages ?: "-"} • Totali: ${st.totalPages ?: "-"}")
                if (st.markers.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        st.markers.forEach { m ->
                            Text("${m.name}: ${if (m.level >= 0) "${m.level}%" else "N/D"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    Card {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CleaningServices, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("2. Spurgo Manuale Diretto (PWG / PDF)", fontWeight = FontWeight.SemiBold)
            }
            val isInkjetOk = ui.status?.printerKind != PrinterKind.LASER && ui.config.printerHost.isNotBlank() && !ui.isBusy
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { viewModel.triggerManualPurge(PurgeMode.K) }, enabled = isInkjetOk, modifier = Modifier.weight(1f)) {
                    Text("Nero (K)")
                }
                FilledTonalButton(onClick = { viewModel.triggerManualPurge(PurgeMode.CMY) }, enabled = isInkjetOk, modifier = Modifier.weight(1f)) {
                    Text("Colori (CMY)")
                }
                Button(onClick = { viewModel.triggerManualPurge(PurgeMode.CMYK) }, enabled = isInkjetOk, modifier = Modifier.weight(1f)) {
                    Text("Tutti")
                }
            }
            val isSixTank = ui.config.inkProfile == "cmyk6" ||
                ui.status?.inkProfile == "cmyk6" ||
                (ui.status?.tankCount ?: 4) >= 6
            if (isSixTank) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { viewModel.triggerManualPurge(PurgeMode.CMYK6) }, enabled = isInkjetOk, modifier = Modifier.weight(1f)) {
                        Text("6-Tank Photo")
                    }
                    FilledTonalButton(onClick = { viewModel.triggerManualPurge(PurgeMode.PB_GY) }, enabled = isInkjetOk, modifier = Modifier.weight(1f)) {
                        Text("PB + Gray")
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactSelector(
    label: String,
    current: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == current }?.second ?: current
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("$label: $currentLabel", style = MaterialTheme.typography.labelSmall)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ScannerSurgicalTab(ui: MainUiState, viewModel: NozzleGuardianViewModel) {
    val context = LocalContext.current
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { viewModel.scanSheetUri(it) }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.scanSheetUri(it) }
    }

    Card {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Scanner Ottico Foglio Test", fontWeight = FontWeight.SemiBold)
            Text("Stampa un test (CMYK o Nero) e fotografalo con buona luce per individuare i singoli ugelli mancanti (1–10).", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val dir = File(context.cacheDir, "scans").apply { mkdirs() }
                        val file = File(dir, "nozzle_capture.jpg")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        cameraUri = uri
                        takePicture.launch(uri)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Scatta Foto")
                }
                OutlinedButton(onClick = { pickImage.launch("image/*") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Galleria")
                }
            }

            ui.scanReport?.let { rep ->
                Text("Salute complessiva: ${rep.overallHealthPct}% (foglio ${rep.sheetMode.uppercase()})", fontWeight = FontWeight.Bold)
                rep.channels.forEach { (ch, info) ->
                    if (info.present) {
                        Text(
                            "${ch.uppercase()}: ${info.healthPct}% • mancanti: ${if (info.missingSteps.isEmpty()) "nessuno" else info.missingSteps.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (rep.targetChannel != null && rep.targetStep != null) {
                    val chEnum = NozzleChannel.entries.firstOrNull { it.code == rep.targetChannel }
                    if (chEnum != null) {
                        Button(
                            onClick = { viewModel.triggerSurgicalPurge(chEnum, rep.targetStep) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Sblocca ora ${chEnum.displayName} gradino #${rep.targetStep}")
                        }
                    }
                }
            }
        }
    }

    Card {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Matrice Chirurgica Manuale (1–10)", fontWeight = FontWeight.SemiBold)
            if (ui.status?.headTechnology == HeadTechnology.THERMAL) {
                Text("Modalità Canon/HP Thermal BubbleJet attiva: impulsi limitati a ≤3 con passo termico sicuro (0.34 mm).", style = MaterialTheme.typography.bodySmall, color = Color(0xFF38BDF8))
            }
            val isSixTankMatrix = ui.config.inkProfile == "cmyk6" ||
                ui.status?.inkProfile == "cmyk6" ||
                (ui.status?.tankCount ?: 4) >= 6
            val visibleChannels = if (isSixTankMatrix) {
                NozzleChannel.entries
            } else {
                listOf(NozzleChannel.CYAN, NozzleChannel.MAGENTA, NozzleChannel.YELLOW, NozzleChannel.BLACK)
            }
            visibleChannels.forEach { channel ->
                Text(channel.displayName, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    for (step in 1..10) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(channel.argb).copy(alpha = if (channel == NozzleChannel.BLACK) 0.8f else 0.25f))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                                .clickable(enabled = !ui.isBusy) {
                                    viewModel.triggerSurgicalPurge(channel, step)
                                },
                        ) {
                            Text("$step", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutLicenseTab() {
    val context = LocalContext.current
    Card {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nozzle Guardian™ per Android", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text("Consumo tipico micro-spurgo: ~0.006 ml contro gli 8–15 ml di una pulizia forzata in fabbrica. Usa lo stesso foglio di scarto decine di volte.", style = MaterialTheme.typography.bodySmall)
            Text("• Stampanti Wi-Fi: scoperta mDNS automatica + IPP/PWG-Raster/SNMP/EWS direttamente dal telefono.", style = MaterialTheme.typography.bodySmall)
            Text("• Stampanti solo USB (es. Canon G2770): collega la stampante al PC con l'app Desktop 1-Click su Windows/macOS/Linux.", style = MaterialTheme.typography.bodySmall)
            Text("• Privacy: Zero Telemetry, nessuna API cloud, nessuna raccolta dati.", style = MaterialTheme.typography.bodySmall)
            Text("• Licenza Software: AGPL-3.0-only • Copyright © 2026 Umberto (umbette).", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/umbette/nozzle-guardian"))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Codice Sorgente & Guide su GitHub")
            }
        }
    }
}

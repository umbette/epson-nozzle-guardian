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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.umbette.nozzleguardian.engine.HeadTechnology
import com.umbette.nozzleguardian.engine.MarkerSupply
import com.umbette.nozzleguardian.engine.NozzleChannel
import com.umbette.nozzleguardian.engine.PrinterKind
import com.umbette.nozzleguardian.engine.PurgeMode
import java.io.File

private val BgDeep = Color(0xFF0B1120)
private val CardSurface = Color(0xFF151F32)
private val CardSurfaceAlt = Color(0xFF1B2840)
private val CardBorder = Color(0xFF26354F)
private val AccentCyan = Color(0xFF38BDF8)
private val AccentMagenta = Color(0xFFE879F9)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val StatusGreen = Color(0xFF22C55E)
private val StatusAmber = Color(0xFFF59E0B)
private val StatusRed = Color(0xFFEF4444)

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<NozzleGuardianViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val darkPalette = darkColorScheme(
                primary = AccentCyan,
                onPrimary = Color(0xFF062033),
                secondary = AccentMagenta,
                background = BgDeep,
                surface = CardSurface,
                surfaceVariant = CardSurfaceAlt,
                onBackground = TextPrimary,
                onSurface = TextPrimary,
                onSurfaceVariant = TextMuted,
                outline = CardBorder,
            )
            MaterialTheme(colorScheme = darkPalette) {
                NozzleGuardianApp(viewModel)
            }
        }
    }
}

@Composable
fun NozzleGuardianLogo(size: Dp = 32.dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val shield = Path().apply {
            moveTo(w * 0.50f, h * 0.06f)
            lineTo(w * 0.89f, h * 0.21f)
            lineTo(w * 0.89f, h * 0.52f)
            cubicTo(w * 0.89f, h * 0.74f, w * 0.71f, h * 0.89f, w * 0.50f, h * 0.95f)
            cubicTo(w * 0.29f, h * 0.89f, w * 0.11f, h * 0.74f, w * 0.11f, h * 0.52f)
            lineTo(w * 0.11f, h * 0.21f)
            close()
        }
        drawPath(shield, color = Color(0xFF18263D))
        drawPath(shield, color = AccentCyan, style = Stroke(width = w * 0.065f))

        val barColors = listOf(
            Color(0xFF06B6D4),
            Color(0xFFEC4899),
            Color(0xFFEAB308),
            Color(0xFFF8FAFC),
        )
        val barH = h * 0.085f
        val gap = h * 0.045f
        val startY = h * 0.30f
        barColors.forEachIndexed { idx, col ->
            val widthFrac = if (idx == 3) 0.42f else 0.52f
            val bx = w * (0.5f - widthFrac / 2f)
            val by = startY + idx * (barH + gap)
            drawRoundRect(
                color = col,
                topLeft = Offset(bx, by),
                size = Size(w * widthFrac, barH),
                cornerRadius = CornerRadius(barH / 2f, barH / 2f),
            )
        }
    }
}

@Composable
private fun ModernCard(
    modifier: Modifier = Modifier,
    borderColor: Color = CardBorder,
    containerColor: Color = CardSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
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
        containerColor = BgDeep,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgDeep,
                    titleContentColor = TextPrimary,
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        NozzleGuardianLogo(size = 30.dp)
                        Text(
                            "Nozzle Guardian",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = AccentCyan.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.35f)),
                        ) {
                            Text(
                                "v1.2.1",
                                color = AccentCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0E1628),
                tonalElevation = 0.dp,
            ) {
                val navColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentCyan,
                    selectedTextColor = AccentCyan,
                    indicatorColor = AccentCyan.copy(alpha = 0.16f),
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                )
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    colors = navColors,
                    icon = { Icon(Icons.Filled.Print, contentDescription = null) },
                    label = { Text("Stampante") },
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    colors = navColors,
                    icon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                    label = { Text("Scanner") },
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    colors = navColors,
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    label = { Text("Guida") },
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
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50)),
                    color = AccentCyan,
                    trackColor = CardBorder,
                )
            }
            if (ui.bannerMessage.isNotBlank()) {
                val isError = ui.bannerMessage.contains("errore", ignoreCase = true) ||
                    ui.bannerMessage.contains("rifiutato", ignoreCase = true) ||
                    ui.bannerMessage.contains("non raggiungibile", ignoreCase = true)
                val isOk = ui.bannerMessage.contains("attivi", ignoreCase = true) ||
                    ui.bannerMessage.contains("accettato", ignoreCase = true) ||
                    ui.bannerMessage.contains("trovat", ignoreCase = true)
                val accent = when {
                    isError -> StatusRed
                    isOk -> StatusGreen
                    else -> AccentCyan
                }
                val icon = when {
                    isError -> Icons.Filled.Warning
                    isOk -> Icons.Filled.CheckCircle
                    else -> Icons.Filled.Info
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = accent.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                        Text(
                            text = ui.bannerMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary,
                        )
                    }
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

    ui.status?.let { st ->
        ModernCard(
            borderColor = if (st.online) StatusGreen.copy(alpha = 0.35f) else StatusRed.copy(alpha = 0.35f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        st.makeAndModel ?: "Stampante IPP",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                    )
                    Text(
                        "${st.headTechnology.label} • ${st.mediaDefault}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
                val badgeColor = if (st.online) StatusGreen else StatusRed
                Surface(
                    shape = RoundedCornerShape(50),
                    color = badgeColor.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f)),
                ) {
                    Text(
                        if (st.online) "ONLINE" else "OFFLINE",
                        color = badgeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            if (st.printerKind == PrinterKind.LASER) {
                Text("Laser/Toner rilevata: spurghi inchiostro bloccati.", color = StatusAmber, style = MaterialTheme.typography.bodySmall)
            }
            if (st.isMediaEmpty) {
                Text("Vassoio vuoto (media-empty): inserisci un foglio di scarto.", color = StatusRed, style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatPill("B/N", st.monochromePages?.toString() ?: "—", Modifier.weight(1f))
                StatPill("Colori", st.colorPages?.toString() ?: "—", Modifier.weight(1f))
                StatPill("Totali", st.totalPages?.toString() ?: "—", Modifier.weight(1f))
            }

            if (st.markers.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    st.markers.forEach { marker ->
                        InkTankChip(marker)
                    }
                }
            }
        }
    }

    ModernCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "1. Stampante Wi-Fi",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = { viewModel.discoverPrinters() },
                enabled = !ui.isDiscovering,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.WifiFind, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Cerca mDNS", maxLines = 1, softWrap = false, fontSize = 13.sp)
            }
        }

        ui.discovered.forEach { dev ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardSurfaceAlt)
                    .border(1.dp, AccentCyan.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .clickable { viewModel.selectPrinter(dev) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(dev.makeAndModel, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text("${dev.host}:${dev.port} • ${dev.headTechnology.label}", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AccentCyan.copy(alpha = 0.16f),
                ) {
                    Text(
                        "Usa",
                        color = AccentCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }

        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentCyan,
            unfocusedBorderColor = CardBorder,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = hostInput,
                onValueChange = { hostInput = it },
                label = { Text("IP / Hostname") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
                modifier = Modifier.weight(2.2f),
            )
            OutlinedTextField(
                value = portInput,
                onValueChange = { portInput = it.filter { ch -> ch.isDigit() } },
                label = { Text("Porta") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        val switchColors = SwitchDefaults.colors(
            checkedThumbColor = BgDeep,
            checkedTrackColor = AccentCyan,
            uncheckedTrackColor = CardSurfaceAlt,
            uncheckedBorderColor = CardBorder,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Usa IPPS (TLS locale)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = useTls, onCheckedChange = { useTls = it }, colors = switchColors)
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Auto-spurgo automatico (Wi-Fi casa)", fontWeight = FontWeight.Medium)
                Text("Controllo ogni 12h con WorkManager", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            Switch(checked = autoPurge, onCheckedChange = { autoPurge = it }, colors = switchColors)
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Soglia inattività", style = MaterialTheme.typography.bodyMedium)
                Text("${daysFloat.toInt()} giorni", color = AccentCyan, fontWeight = FontWeight.Bold)
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

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
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
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(46.dp),
            ) {
                Text("Salva", maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = { viewModel.refreshStatus(runDormancyCheck = true) },
                enabled = !ui.isBusy,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier.weight(1f).height(46.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Verifica ora", maxLines = 1, softWrap = false)
            }
        }
    }

    ModernCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.CleaningServices, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
            Text("2. Spurgo Manuale Diretto", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        }
        val isInkjetOk = ui.status?.printerKind != PrinterKind.LASER && ui.config.printerHost.isNotBlank() && !ui.isBusy
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = { viewModel.triggerManualPurge(PurgeMode.K) },
                enabled = isInkjetOk,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).height(46.dp),
            ) {
                Text("Nero (K)", maxLines = 1, softWrap = false, fontSize = 13.sp)
            }
            FilledTonalButton(
                onClick = { viewModel.triggerManualPurge(PurgeMode.CMY) },
                enabled = isInkjetOk,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).height(46.dp),
            ) {
                Text("Colori", maxLines = 1, softWrap = false, fontSize = 13.sp)
            }
            Button(
                onClick = { viewModel.triggerManualPurge(PurgeMode.CMYK) },
                enabled = isInkjetOk,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).height(46.dp),
            ) {
                Text("CMYK", maxLines = 1, softWrap = false, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        val isSixTank = ui.config.inkProfile == "cmyk6" ||
            ui.status?.inkProfile == "cmyk6" ||
            (ui.status?.tankCount ?: 4) >= 6
        if (isSixTank) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.triggerManualPurge(PurgeMode.CMYK6) },
                    enabled = isInkjetOk,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E), contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).height(46.dp),
                ) {
                    Text("6-Tank Photo", maxLines = 1, softWrap = false, fontSize = 13.sp)
                }
                FilledTonalButton(
                    onClick = { viewModel.triggerManualPurge(PurgeMode.PB_GY) },
                    enabled = isInkjetOk,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).height(46.dp),
                ) {
                    Text("PB + Gray", maxLines = 1, softWrap = false, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurfaceAlt)
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 11.sp, color = TextMuted)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
    }
}

@Composable
private fun InkTankChip(marker: MarkerSupply) {
    val dotColor = remember(marker.colorHex, marker.name) {
        val clean = marker.colorHex.trim().removePrefix("#")
        val parsed = if (clean.length == 6) clean.toLongOrNull(16)?.let { Color(0xFF000000L or it) } else null
        parsed ?: when {
            "cyan" in marker.name.lowercase() || marker.name.equals("C", true) -> Color(0xFF06B6D4)
            "magenta" in marker.name.lowercase() || marker.name.equals("M", true) -> Color(0xFFEC4899)
            "yellow" in marker.name.lowercase() || marker.name.equals("Y", true) -> Color(0xFFEAB308)
            "gray" in marker.name.lowercase() || "grey" in marker.name.lowercase() || marker.name.equals("GY", true) -> Color(0xFF94A3B8)
            else -> Color(0xFFE2E8F0)
        }
    }
    val shortName = marker.name.replace(" Ink Bottle", "", ignoreCase = true)
        .replace(" Ink Cartridge", "", ignoreCase = true)
    val lvlStr = if (marker.level >= 0) "${marker.level}%" else "OK"

    Surface(
        shape = RoundedCornerShape(50),
        color = CardSurfaceAlt,
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text("$shortName: $lvlStr", fontSize = 11.sp, color = TextPrimary, maxLines = 1)
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
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, CardBorder),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text("$label: $currentLabel", style = MaterialTheme.typography.labelSmall, color = TextPrimary, maxLines = 1)
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

    ModernCard {
        Text("Scanner Ottico Foglio Test", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Text(
            "Stampa un test (CMYK o Nero) e fotografalo con buona luce per individuare i singoli ugelli mancanti (1–10).",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val dir = File(context.cacheDir, "scans").apply { mkdirs() }
                    val file = File(dir, "nozzle_capture.jpg")
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    cameraUri = uri
                    takePicture.launch(uri)
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(46.dp),
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Scatta Foto", maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = { pickImage.launch("image/*") },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier.weight(1f).height(46.dp),
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Galleria", maxLines = 1, softWrap = false)
            }
        }

        ui.scanReport?.let { rep ->
            Text("Salute complessiva: ${rep.overallHealthPct}% (foglio ${rep.sheetMode.uppercase()})", fontWeight = FontWeight.Bold)
            rep.channels.forEach { (ch, info) ->
                if (info.present) {
                    Text(
                        "${ch.uppercase()}: ${info.healthPct}% • mancanti: ${if (info.missingSteps.isEmpty()) "nessuno" else info.missingSteps.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
            }
            if (rep.targetChannel != null && rep.targetStep != null) {
                val chEnum = NozzleChannel.entries.firstOrNull { it.code == rep.targetChannel }
                if (chEnum != null) {
                    Button(
                        onClick = { viewModel.triggerSurgicalPurge(chEnum, rep.targetStep) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) {
                        Text("Sblocca ora ${chEnum.displayName} gradino #${rep.targetStep}", maxLines = 1)
                    }
                }
            }
        }
    }

    ModernCard {
        Text("Matrice Chirurgica Manuale (1–10)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        if (ui.status?.headTechnology == HeadTechnology.THERMAL) {
            Text(
                "Modalità Canon/HP Thermal BubbleJet attiva: impulsi limitati a ≤3 con passo termico sicuro (0.34 mm).",
                style = MaterialTheme.typography.bodySmall,
                color = AccentCyan,
            )
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
            val accent = when (channel) {
                NozzleChannel.BLACK, NozzleChannel.PHOTO_BLACK -> Color(0xFF94A3B8)
                else -> Color(channel.argb)
            }
            Text(channel.displayName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = accent)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                for (step in 1..10) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(accent.copy(alpha = 0.16f))
                            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                            .clickable(enabled = !ui.isBusy) {
                                viewModel.triggerSurgicalPurge(channel, step)
                            },
                    ) {
                        Text("$step", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutLicenseTab() {
    val context = LocalContext.current
    ModernCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NozzleGuardianLogo(size = 42.dp)
            Column {
                Text("Nozzle Guardian", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("v1.2.1 • Driverless IPP / PWG-Raster", style = MaterialTheme.typography.bodySmall, color = AccentCyan)
            }
        }
        Text(
            "Consumo tipico micro-spurgo: ~0.006 ml contro gli 8–15 ml di una pulizia forzata in fabbrica. Usa lo stesso foglio di scarto decine di volte.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        Text("• Stampanti Wi-Fi: scoperta mDNS automatica + IPP/PWG-Raster/SNMP/EWS direttamente dal telefono.", style = MaterialTheme.typography.bodySmall)
        Text("• 4-Tank & 6-Tank Photo: supporta EcoTank standard (ET-2810, ET-4810) e Claria 6-Tank (ET-8550, L8180).", style = MaterialTheme.typography.bodySmall)
        Text("• Stampanti solo USB (es. Canon G2770): collega la stampante al PC con l'app Desktop 1-Click su Windows/macOS/Linux.", style = MaterialTheme.typography.bodySmall)
        Text("• Privacy: Zero Telemetry, nessuna API cloud, nessuna raccolta dati.", style = MaterialTheme.typography.bodySmall)
        Text("• Licenza Software: AGPL-3.0-only • Copyright © 2026 Umberto (umbette).", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        OutlinedButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/umbette/nozzle-guardian"))
                context.startActivity(intent)
            },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) {
            Text("Codice Sorgente & Guide su GitHub", color = AccentCyan)
        }
    }
}

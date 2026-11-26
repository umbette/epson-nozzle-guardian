package com.umbette.nozzleguardian.android

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umbette.nozzleguardian.R
import com.umbette.nozzleguardian.engine.BrandBadge
import com.umbette.nozzleguardian.engine.HeadTechnology
import com.umbette.nozzleguardian.engine.MarkerSupply
import com.umbette.nozzleguardian.engine.NozzleChannel
import com.umbette.nozzleguardian.engine.PrinterSilhouette
import com.umbette.nozzleguardian.engine.ProFeature

val BgDeep = Color(0xFF0B1120)
val CardSurface = Color(0xFF151F32)
val CardSurfaceAlt = Color(0xFF1B2840)
val CardBorder = Color(0xFF283854)
val AccentCyan = Color(0xFF38BDF8)
val AccentMagenta = Color(0xFFE879F9)
val ProGold = Color(0xFFF59E0B)
val TextPrimary = Color(0xFFF8FAFC)
val TextMuted = Color(0xFF94A3B8)
val StatusGreen = Color(0xFF22C55E)
val StatusAmber = Color(0xFFF59E0B)
val StatusRed = Color(0xFFEF4444)
val SwatchCyan = Color(0xFF06B6D4)
val SwatchMagenta = Color(0xFFEC4899)
val SwatchYellow = Color(0xFFEAB308)
val SwatchBlack = Color(0xFF0F172A)
val SwatchGray = Color(0xFF94A3B8)

@Composable
fun InkSwatchRow(colors: List<Color>, dotSize: Dp = 9.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        colors.forEach { col ->
            val borderCol = if (col == SwatchBlack) Color(0xFF64748B) else Color.White.copy(alpha = 0.28f)
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(col)
                    .border(0.8.dp, borderCol, RoundedCornerShape(2.5.dp)),
            )
        }
    }
}

private val DarkPalette = darkColorScheme(
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

private val NgTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
)

@Composable
fun NozzleGuardianTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkPalette, typography = NgTypography, content = content)
}

/**
 * Upgraded Silver-Shield Nozzle Guardian Logo aligned across Android, Desktop, Web, and SVG.
 * Safe-zone proportions keep the shield inside OEM circular/squircle launcher masks,
 * while true Ink-Black (#0F172A) stands out crisply against the brushed silver backdrop.
 */
@Composable
fun NozzleGuardianLogo(size: Dp = 32.dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val shield = Path().apply {
            moveTo(w * 0.50f, h * 0.14f)
            lineTo(w * 0.80f, h * 0.25f)
            lineTo(w * 0.80f, h * 0.50f)
            cubicTo(w * 0.80f, h * 0.70f, w * 0.66f, h * 0.83f, w * 0.50f, h * 0.90f)
            cubicTo(w * 0.34f, h * 0.83f, w * 0.20f, h * 0.70f, w * 0.20f, h * 0.50f)
            lineTo(w * 0.20f, h * 0.25f)
            close()
        }
        val silverFill = Brush.linearGradient(
            colors = listOf(Color(0xFFF8FAFC), Color(0xFFCBD5E1), Color(0xFF94A3B8)),
            start = Offset(w * 0.20f, h * 0.14f),
            end = Offset(w * 0.85f, h * 0.88f),
        )
        val silverRim = Brush.linearGradient(
            colors = listOf(Color(0xFFFFFFFF), Color(0xFF64748B), AccentCyan),
            start = Offset.Zero,
            end = Offset(w, h),
        )
        drawPath(shield, brush = silverFill)
        drawPath(shield, brush = silverRim, style = Stroke(width = w * 0.045f))

        val barColors = listOf(
            Color(0xFF06B6D4),
            Color(0xFFEC4899),
            Color(0xFFEAB308),
            Color(0xFF0F172A), // True Ink-Black (K)
        )
        val barH = h * 0.068f
        val gap = h * 0.038f
        val startY = h * 0.34f
        barColors.forEachIndexed { idx, col ->
            val widthFrac = if (idx == 3) 0.34f else 0.42f
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
fun PrinterSilhouetteArt(
    silhouette: PrinterSilhouette,
    brand: BrandBadge,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    val accent = Color(brand.accentHex)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurfaceAlt)
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
    ) {
        Canvas(modifier = Modifier.size(size * 0.78f)) {
            val w = this.size.width
            val h = this.size.height

            if (silhouette == PrinterSilhouette.TANK_4_ADF) {
                drawRoundRect(
                    color = Color(0xFF334155),
                    topLeft = Offset(w * 0.18f, h * 0.10f),
                    size = Size(w * 0.64f, h * 0.20f),
                    cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
                )
            } else if (silhouette != PrinterSilhouette.LASER_TONER) {
                drawRoundRect(
                    color = Color(0xFFCBD5E1),
                    topLeft = Offset(w * 0.28f, h * 0.10f),
                    size = Size(w * 0.44f, h * 0.22f),
                    cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
                )
            }

            val bodyTop = if (silhouette == PrinterSilhouette.LASER_TONER) h * 0.18f else h * 0.28f
            val bodyH = if (silhouette == PrinterSilhouette.LASER_TONER) h * 0.62f else h * 0.46f
            drawRoundRect(
                color = Color(0xFF1E293B),
                topLeft = Offset(w * 0.08f, bodyTop),
                size = Size(w * 0.84f, bodyH),
                cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.75f),
                topLeft = Offset(w * 0.08f, bodyTop),
                size = Size(w * 0.84f, bodyH),
                cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
                style = Stroke(width = w * 0.045f),
            )

            drawRoundRect(
                color = Color(0xFF0F172A),
                topLeft = Offset(w * 0.24f, h * 0.66f),
                size = Size(w * 0.52f, h * 0.16f),
                cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
            )
            drawRoundRect(
                color = Color(0xFFE2E8F0),
                topLeft = Offset(w * 0.30f, h * 0.72f),
                size = Size(w * 0.40f, h * 0.14f),
                cornerRadius = CornerRadius(w * 0.03f, w * 0.03f),
            )

            when (silhouette) {
                PrinterSilhouette.TANK_4_COMPACT, PrinterSilhouette.TANK_4_ADF -> {
                    val tankColors = listOf(Color(0xFF06B6D4), Color(0xFFEC4899), Color(0xFFEAB308), Color(0xFF0F172A))
                    tankColors.forEachIndexed { idx, col ->
                        drawRoundRect(
                            color = col,
                            topLeft = Offset(w * (0.64f + idx * 0.06f), h * 0.44f),
                            size = Size(w * 0.042f, h * 0.16f),
                            cornerRadius = CornerRadius(w * 0.015f, w * 0.015f),
                        )
                    }
                }
                PrinterSilhouette.TANK_6_PHOTO -> {
                    val tankColors = listOf(
                        Color(0xFF06B6D4), Color(0xFFEC4899), Color(0xFFEAB308),
                        Color(0xFF0F172A), Color(0xFF64748B), Color(0xFF94A3B8),
                    )
                    tankColors.forEachIndexed { idx, col ->
                        drawRoundRect(
                            color = col,
                            topLeft = Offset(w * (0.56f + idx * 0.05f), h * 0.44f),
                            size = Size(w * 0.036f, h * 0.16f),
                            cornerRadius = CornerRadius(w * 0.012f, w * 0.012f),
                        )
                    }
                }
                PrinterSilhouette.CARTRIDGE_COMPACT -> {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(w * 0.18f, h * 0.38f),
                        size = Size(w * 0.64f, h * 0.08f),
                        cornerRadius = CornerRadius(w * 0.03f, w * 0.03f),
                    )
                }
                PrinterSilhouette.LASER_TONER -> {
                    drawRoundRect(
                        color = StatusAmber,
                        topLeft = Offset(w * 0.18f, h * 0.32f),
                        size = Size(w * 0.28f, h * 0.08f),
                        cornerRadius = CornerRadius(w * 0.03f, w * 0.03f),
                    )
                    drawRoundRect(
                        color = Color(0xFF334155),
                        topLeft = Offset(w * 0.18f, h * 0.48f),
                        size = Size(w * 0.64f, h * 0.14f),
                        cornerRadius = CornerRadius(w * 0.03f, w * 0.03f),
                    )
                }
            }
        }
    }
}

@Composable
fun BrandPill(brand: BrandBadge) {
    val accent = Color(brand.accentHex)
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = accent.copy(alpha = 0.20f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.50f)),
    ) {
        Text(
            text = brand.displayName,
            color = TextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun ProBadge(label: String = "PRO") {
    Surface(
        shape = RoundedCornerShape(50),
        color = ProGold.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, ProGold.copy(alpha = 0.55f)),
    ) {
        Text(
            text = label,
            color = ProGold,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun StatusBadge(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun ModernCard(
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

@Composable
fun NgTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentCyan,
            unfocusedBorderColor = CardBorder,
            focusedLabelColor = AccentCyan,
            unfocusedLabelColor = TextMuted,
        ),
        modifier = modifier,
    )
}

@Composable
fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurfaceAlt)
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 11.sp, color = TextMuted, maxLines = 1)
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
            ),
            color = TextPrimary,
        )
    }
}

@Composable
fun InkTankBar(marker: MarkerSupply, modifier: Modifier = Modifier) {
    val barColor = remember(marker.colorHex, marker.name) {
        val clean = marker.colorHex.trim().removePrefix("#")
        val parsed = if (clean.length == 6) clean.toLongOrNull(16)?.let { Color(0xFF000000L or it) } else null
        parsed ?: when {
            "cyan" in marker.name.lowercase() || marker.name.equals("C", true) -> Color(0xFF06B6D4)
            "magenta" in marker.name.lowercase() || marker.name.equals("M", true) -> Color(0xFFEC4899)
            "yellow" in marker.name.lowercase() || marker.name.equals("Y", true) -> Color(0xFFEAB308)
            "gray" in marker.name.lowercase() || "grey" in marker.name.lowercase() || marker.name.equals("GY", true) -> Color(0xFF94A3B8)
            else -> Color(0xFF0F172A)
        }
    }
    val isDarkSwatch = (barColor.red + barColor.green + barColor.blue) < 0.6f
    val shortName = marker.name
        .replace(" Ink Bottle", "", ignoreCase = true)
        .replace(" Ink Cartridge", "", ignoreCase = true)
        .replace(" Toner Cartridge", "", ignoreCase = true)
    val lvlStr = if (marker.level >= 0) "${marker.level}%" else "OK"
    val fillFrac = if (marker.level in 0..100) (marker.level / 100f).coerceAtLeast(0.06f) else 1f
    val trackBorder = if (isDarkSwatch) Color(0xFF475569) else barColor.copy(alpha = 0.45f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CardSurfaceAlt)
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(shortName, fontSize = 11.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                lvlStr,
                style = MaterialTheme.typography.labelMedium,
                color = if (marker.level in 0..15) StatusAmber else TextMuted,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(50))
                .background(BgDeep)
                .border(0.8.dp, trackBorder, RoundedCornerShape(50)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fillFrac)
                    .height(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(barColor),
            )
        }
    }
}

@Composable
fun CompactSelector(
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
fun UiBannerCard(banner: UiBanner, onDismiss: () -> Unit) {
    val accent = when (banner.severity) {
        BannerSeverity.ERROR -> StatusRed
        BannerSeverity.WARNING -> StatusAmber
        BannerSeverity.SUCCESS -> StatusGreen
        BannerSeverity.INFO -> AccentCyan
    }
    val icon = when (banner.severity) {
        BannerSeverity.ERROR, BannerSeverity.WARNING -> Icons.Filled.Warning
        BannerSeverity.SUCCESS -> Icons.Filled.CheckCircle
        BannerSeverity.INFO -> Icons.Filled.Info
    }
    val resolvedText = if (banner.messageRes != null) {
        if (banner.formatArgs.isEmpty()) {
            stringResource(banner.messageRes)
        } else {
            stringResource(banner.messageRes, *banner.formatArgs.toTypedArray())
        }
    } else {
        banner.fallbackText
    }
    if (resolvedText.isBlank()) return

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.11f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Text(
                text = resolvedText,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = TextMuted, modifier = Modifier.size(15.dp))
            }
        }
    }
}

fun locationIconVector(iconKey: String): ImageVector = when (iconKey) {
    "work" -> Icons.Filled.Business
    "studio" -> Icons.Filled.Brush
    "lab" -> Icons.Filled.Science
    else -> Icons.Filled.Home
}

@StringRes
fun headTechTitleRes(tech: HeadTechnology): Int = when (tech) {
    HeadTechnology.PIEZO -> R.string.head_piezo
    HeadTechnology.THERMAL -> R.string.head_thermal
    HeadTechnology.LASER -> R.string.head_laser
}

@StringRes
fun silhouetteTitleRes(sil: PrinterSilhouette): Int = when (sil) {
    PrinterSilhouette.TANK_4_COMPACT -> R.string.sil_tank4_compact
    PrinterSilhouette.TANK_4_ADF -> R.string.sil_tank4_adf
    PrinterSilhouette.TANK_6_PHOTO -> R.string.sil_tank6_photo
    PrinterSilhouette.CARTRIDGE_COMPACT -> R.string.sil_cartridge_compact
    PrinterSilhouette.LASER_TONER -> R.string.sil_laser_toner
}

@StringRes
fun channelTitleRes(channel: NozzleChannel): Int = when (channel) {
    NozzleChannel.CYAN -> R.string.channel_cyan
    NozzleChannel.MAGENTA -> R.string.channel_magenta
    NozzleChannel.YELLOW -> R.string.channel_yellow
    NozzleChannel.BLACK -> R.string.channel_black
    NozzleChannel.PHOTO_BLACK -> R.string.channel_photo_black
    NozzleChannel.GRAY -> R.string.channel_gray
}

@StringRes
fun featureTitleRes(feature: ProFeature): Int = when (feature) {
    ProFeature.AUTO_PURGE -> R.string.feature_auto_purge_title
    ProFeature.CAMERA_SCANNER -> R.string.feature_camera_title
    ProFeature.MULTI_PRINTER_LOCATIONS -> R.string.feature_multi_title
    ProFeature.CLOUD_SYNC -> R.string.feature_sync_title
}

@StringRes
fun featureDescRes(feature: ProFeature): Int = when (feature) {
    ProFeature.AUTO_PURGE -> R.string.feature_auto_purge_desc
    ProFeature.CAMERA_SCANNER -> R.string.feature_camera_desc
    ProFeature.MULTI_PRINTER_LOCATIONS -> R.string.feature_multi_desc
    ProFeature.CLOUD_SYNC -> R.string.feature_sync_desc
}

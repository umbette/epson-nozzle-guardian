package com.umbette.nozzleguardian.android

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.umbette.nozzleguardian.R
import com.umbette.nozzleguardian.engine.HeadTechnology
import com.umbette.nozzleguardian.engine.NozzleChannel
import com.umbette.nozzleguardian.engine.PrinterKind
import com.umbette.nozzleguardian.engine.PrinterProfile
import com.umbette.nozzleguardian.engine.ProFeature
import java.io.File

/**
 * Optical Nozzle Scanner & Manual Surgical Matrix bound directly inside a concrete Printer Hub.
 * Ensures every camera scan and surgical shot applies cleanly to the selected printer model.
 */
@Composable
fun PrinterScannerSection(
    profile: PrinterProfile,
    ui: MainUiState,
    viewModel: NozzleGuardianViewModel,
) {
    val context = LocalContext.current
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val hasCameraAccess = ui.entitlement.hasAccess(ProFeature.CAMERA_SCANNER)
    val st = ui.printerStatuses[profile.id]
    val cfg = profile.state
    val isLaser = (st?.printerKind ?: cfg.lastPrinterKind) == PrinterKind.LASER

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { viewModel.scanSheetUri(it, printerId = profile.id) }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.scanSheetUri(it, printerId = profile.id) }
    }

    if (isLaser) {
        ModernCard(borderColor = StatusAmber.copy(alpha = 0.45f), containerColor = StatusAmber.copy(alpha = 0.08f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = StatusAmber, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.detail_laser_title), style = MaterialTheme.typography.titleSmall, color = StatusAmber)
            }
            Text(
                stringResource(R.string.scanner_laser_notice, profile.displayName),
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
            )
        }
        return
    }

    ModernCard(borderColor = if (hasCameraAccess) CardBorder else ProGold.copy(alpha = 0.4f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.scanner_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            ProBadge(stringResource(R.string.badge_pro))
        }
        Text(stringResource(R.string.scanner_subtitle), style = MaterialTheme.typography.bodySmall, color = TextMuted)

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = AccentCyan.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.scanner_guide_steps),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = AccentCyan,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }

        if (!hasCameraAccess) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = ProGold.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, ProGold.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = ProGold, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.scanner_pro_locked),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (!hasCameraAccess) {
                        viewModel.requestPaywall(ProFeature.CAMERA_SCANNER)
                    } else {
                        val dir = File(context.cacheDir, "scans").apply { mkdirs() }
                        val file = File(dir, "nozzle_capture.jpg")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        cameraUri = uri
                        takePicture.launch(uri)
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(46.dp),
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.scanner_take_photo), maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = {
                    if (!hasCameraAccess) {
                        viewModel.requestPaywall(ProFeature.CAMERA_SCANNER)
                    } else {
                        pickImage.launch("image/*")
                    }
                },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier.weight(1f).height(46.dp),
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.scanner_gallery), maxLines = 1, softWrap = false)
            }
        }

        ui.scanReport?.let { rep ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardSurfaceAlt)
                    .padding(12.dp),
            ) {
                val healthInt = rep.overallHealthPct.toInt().coerceIn(0, 100)
                val gaugeColor = when {
                    healthInt >= 90 -> StatusGreen
                    healthInt >= 65 -> StatusAmber
                    else -> StatusRed
                }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
                    Canvas(modifier = Modifier.size(64.dp)) {
                        drawArc(
                            color = CardBorder,
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = 6.dp.toPx()),
                        )
                        drawArc(
                            color = gaugeColor,
                            startAngle = -90f,
                            sweepAngle = 360f * (healthInt / 100f),
                            useCenter = false,
                            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                    Text("${healthInt}%", style = MaterialTheme.typography.labelMedium, color = gaugeColor)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stringResource(R.string.scanner_health_label, rep.sheetMode.uppercase()),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                    rep.channels.forEach { (ch, info) ->
                        if (info.present) {
                            val missing = if (info.missingSteps.isEmpty()) {
                                stringResource(R.string.scanner_missing_none)
                            } else {
                                info.missingSteps.joinToString()
                            }
                            Text(
                                stringResource(R.string.scanner_channel_row, ch.uppercase(), (info.healthPct ?: 0.0).toInt(), missing),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                    }
                }
            }

            if (rep.targetChannel != null && rep.targetStep != null) {
                val chEnum = NozzleChannel.entries.firstOrNull { it.code == rep.targetChannel }
                if (chEnum != null) {
                    Button(
                        onClick = { viewModel.triggerSurgicalPurge(chEnum, rep.targetStep, printerId = profile.id) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) {
                        Text(
                            stringResource(R.string.scanner_fix_btn, stringResource(channelTitleRes(chEnum)), rep.targetStep),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    // Accessible 2x5 Surgical Matrix adapted specifically to this PrinterProfile
    ModernCard {
        Text(stringResource(R.string.surgical_title), style = MaterialTheme.typography.titleSmall)
        if ((st?.headTechnology ?: cfg.lastHeadTechnology) == HeadTechnology.THERMAL) {
            Text(stringResource(R.string.surgical_thermal_note), style = MaterialTheme.typography.bodySmall, color = AccentCyan)
        }
        val isSixTankMatrix = cfg.inkProfile == "cmyk6" ||
            st?.inkProfile == "cmyk6" ||
            (st?.tankCount ?: 4) >= 6
        val visibleChannels = if (isSixTankMatrix) {
            NozzleChannel.entries
        } else {
            listOf(NozzleChannel.CYAN, NozzleChannel.MAGENTA, NozzleChannel.YELLOW, NozzleChannel.BLACK)
        }

        visibleChannels.forEach { channel ->
            val accent = when (channel) {
                NozzleChannel.CYAN -> Color(0xFF22D3EE)
                NozzleChannel.MAGENTA -> Color(0xFFF472B6)
                NozzleChannel.YELLOW -> Color(0xFFFACC15)
                NozzleChannel.BLACK, NozzleChannel.PHOTO_BLACK -> Color(0xFF94A3B8)
                NozzleChannel.GRAY -> Color(0xFFCBD5E1)
            }
            Text(stringResource(channelTitleRes(channel)), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = accent)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1..5, 6..10).forEach { range ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        for (step in range) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(accent.copy(alpha = 0.16f))
                                    .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                    .clickable(enabled = !ui.isBusy) {
                                        viewModel.triggerSurgicalPurge(channel, step, printerId = profile.id)
                                    },
                            ) {
                                Text(
                                    "$step",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

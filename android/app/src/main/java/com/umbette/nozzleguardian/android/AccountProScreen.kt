package com.umbette.nozzleguardian.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umbette.nozzleguardian.BuildConfig
import com.umbette.nozzleguardian.R
import com.umbette.nozzleguardian.engine.ProFeature

@Composable
fun AccountProScreen(ui: MainUiState, viewModel: NozzleGuardianViewModel) {
    val context = LocalContext.current
    val activity = context as? Activity
    val clipboard = LocalClipboardManager.current

    var emailInput by remember(ui.account.email) { mutableStateOf(ui.account.email) }
    var nameInput by remember(ui.account.displayName) { mutableStateOf(ui.account.displayName) }
    var pairingInput by remember(ui.account.pairingToken) { mutableStateOf(ui.account.pairingToken) }
    var syncUrlInput by remember(ui.account.syncServerUrl) { mutableStateOf(ui.account.syncServerUrl) }
    var showTelemetrySample by remember { mutableStateOf(false) }

    if (showTelemetrySample) {
        val sample = remember(ui.telemetry.installId, ui.activePrinter?.id) { viewModel.sampleTelemetryJson() }
        AlertDialog(
            onDismissRequest = { showTelemetrySample = false },
            containerColor = CardSurface,
            title = { Text(stringResource(R.string.telemetry_preview_title), color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        sample,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = AccentCyan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(BgDeep)
                            .padding(10.dp),
                    )
                    Text(stringResource(R.string.telemetry_preview_note), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            },
            confirmButton = {
                TextButton(onClick = { showTelemetrySample = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    // 1. Lifetime Pro Hero Card
    ModernCard(borderColor = if (ui.entitlement.isProActive) ProGold.copy(alpha = 0.5f) else CardBorder) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = ProGold, modifier = Modifier.size(20.dp))
            Text(
                if (ui.entitlement.isProActive) stringResource(R.string.pro_plan_active_title) else stringResource(R.string.pro_plan_free_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            ProBadge(stringResource(if (ui.entitlement.isProActive) R.string.badge_pro else R.string.badge_free))
        }

        Text(stringResource(R.string.pro_free_summary), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.pro_paid_summary), style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Text(stringResource(R.string.pro_family_note), style = MaterialTheme.typography.bodySmall, color = AccentCyan)

        if (!ui.entitlement.isPlayStoreInstall) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardSurfaceAlt)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(stringResource(R.string.pro_preview_title), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text(stringResource(R.string.pro_preview_subtitle), fontSize = 11.sp, color = TextMuted)
                }
                Switch(
                    checked = ui.entitlement.previewProEnabled,
                    onCheckedChange = { viewModel.setPreviewProEnabled(it) },
                )
            }
        }

        val billingText = when (ui.billing.statusKind) {
            BillingStatusKind.ACTIVE_PRO -> stringResource(R.string.pro_billing_active, ui.billing.activeProductId ?: "lifetime")
            BillingStatusKind.READY -> stringResource(R.string.pro_billing_ready)
            BillingStatusKind.SIDELOAD, BillingStatusKind.UNAVAILABLE_PLAY, BillingStatusKind.INIT -> stringResource(R.string.pro_billing_unavailable)
        }
        Text(billingText, style = MaterialTheme.typography.bodySmall, color = TextMuted)

        if (ui.billing.offers.isNotEmpty() && activity != null) {
            ui.billing.offers.forEach { offer ->
                Button(
                    onClick = { viewModel.launchProPurchase(activity, offer) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ProGold, contentColor = Color(0xFF1F1200)),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text(stringResource(R.string.pro_buy_lifetime, offer.formattedPrice), fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }

        if (ui.entitlement.isPlayStoreInstall) {
            OutlinedButton(
                onClick = { viewModel.restorePurchases() },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier.fillMaxWidth().height(42.dp),
            ) {
                Text(stringResource(R.string.pro_restore_purchases))
            }
        }
    }

    // 2. Opt-In Telemetry ("Help us improve")
    ModernCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Analytics, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.telemetry_card_title), style = MaterialTheme.typography.titleSmall)
        }
        Text(stringResource(R.string.telemetry_optin_body), style = MaterialTheme.typography.bodySmall, color = TextMuted)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardSurfaceAlt)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(stringResource(R.string.telemetry_toggle_label), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                val statusStr = if (ui.telemetry.optIn) {
                    stringResource(R.string.telemetry_status_on, ui.telemetry.installId.take(8))
                } else {
                    stringResource(R.string.telemetry_status_off)
                }
                Text(statusStr, fontSize = 11.sp, color = if (ui.telemetry.optIn) StatusGreen else TextMuted)
            }
            Switch(
                checked = ui.telemetry.optIn,
                onCheckedChange = { viewModel.setTelemetryOptIn(it) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { showTelemetrySample = true },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.4f)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f).height(40.dp),
            ) {
                Text(
                    stringResource(R.string.telemetry_preview_btn),
                    color = AccentCyan,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = { viewModel.resetTelemetryId() },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CardBorder),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f).height(40.dp),
            ) {
                Text(
                    stringResource(R.string.telemetry_reset_id_btn),
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // 3. Independent Passwordless / Pairing Account (Zero Google / Social Login!)
    ModernCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Sync, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.account_card_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (!ui.entitlement.hasAccess(ProFeature.CLOUD_SYNC)) {
                ProBadge(stringResource(R.string.badge_pro))
            }
        }
        Text(stringResource(R.string.account_card_body), style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Text(stringResource(R.string.account_no_google_note), style = MaterialTheme.typography.bodySmall, color = AccentCyan)

        NgTextField(
            value = emailInput,
            onValueChange = { emailInput = it },
            label = stringResource(R.string.account_email_label),
            modifier = Modifier.fillMaxWidth(),
        )
        NgTextField(
            value = nameInput,
            onValueChange = { nameInput = it },
            label = stringResource(R.string.account_name_label),
            modifier = Modifier.fillMaxWidth(),
        )
        NgTextField(
            value = pairingInput,
            onValueChange = { pairingInput = it },
            label = stringResource(R.string.account_pairing_label),
            modifier = Modifier.fillMaxWidth(),
        )
        NgTextField(
            value = syncUrlInput,
            onValueChange = { syncUrlInput = it },
            label = stringResource(R.string.account_sync_url_label),
            modifier = Modifier.fillMaxWidth(),
        )

        ui.account.lastSyncedIso?.let { ts ->
            Text(stringResource(R.string.account_last_sync, ts), style = MaterialTheme.typography.bodySmall, color = StatusGreen)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { viewModel.saveAccountSession(emailInput, nameInput, pairingInput, syncUrlInput) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(44.dp),
            ) {
                Text(stringResource(R.string.account_save_btn), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            if (ui.account.signedIn) {
                OutlinedButton(
                    onClick = { viewModel.signOutAccount() },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Text(stringResource(R.string.account_signout_btn), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = {
                    val json = viewModel.exportSnapshotJson()
                    clipboard.setText(AnnotatedString(json))
                    viewModel.notifyJsonCopied()
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(44.dp),
            ) {
                Text(stringResource(R.string.account_copy_json_btn), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = { viewModel.syncWorkspaceToServer() },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.45f)),
                modifier = Modifier.weight(1f).height(44.dp),
            ) {
                Text(stringResource(R.string.account_sync_now_btn), color = AccentCyan, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    // 4. About & License Card
    ModernCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NozzleGuardianLogo(size = 44.dp)
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                Text("v${BuildConfig.VERSION_NAME} • Driverless IPP / PWG-Raster", style = MaterialTheme.typography.bodySmall, color = AccentCyan)
            }
        }
        Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Text(stringResource(R.string.about_wifi_bullet), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.about_tanks_bullet), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.about_usb_bullet), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.about_privacy_bullet), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodySmall, color = TextMuted)
        OutlinedButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/umbette/nozzle-guardian"))
                context.startActivity(intent)
            },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) {
            Text(stringResource(R.string.about_github_btn), color = AccentCyan)
        }
    }
}

package com.umbette.nozzleguardian.android

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbette.nozzleguardian.R
import com.umbette.nozzleguardian.engine.ProFeature

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<NozzleGuardianViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NozzleGuardianTheme {
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

    ui.paywallFeaturePrompt?.let { feature ->
        PaywallModalSheet(
            feature = feature,
            ui = ui,
            viewModel = viewModel,
            onDismiss = { viewModel.dismissPaywall() },
        )
    }

    ui.laserNoticePrinterId?.let { laserId ->
        val laserPrinter = ui.workspace.printers.firstOrNull { it.id == laserId }
        val laserStatus = ui.printerStatuses[laserId]
        val modelName = laserStatus?.makeAndModel ?: laserPrinter?.displayName ?: "Laser"
        AlertDialog(
            onDismissRequest = { viewModel.dismissLaserNotice() },
            containerColor = CardSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = StatusAmber)
                    Text(stringResource(R.string.detail_laser_title), style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                }
            },
            text = {
                Text(stringResource(R.string.detail_laser_body, modelName), style = MaterialTheme.typography.bodySmall, color = TextPrimary)
            },
            confirmButton = {
                Button(onClick = { viewModel.dismissLaserNotice() }) {
                    Text(stringResource(R.string.detail_laser_cta_close))
                }
            },
        )
    }

    Scaffold(
        containerColor = BgDeep,
        topBar = {
            androidx.compose.material3.TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = BgDeep,
                    titleContentColor = TextPrimary,
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        NozzleGuardianLogo(size = 34.dp)
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (ui.entitlement.isProActive) {
                            ProBadge(stringResource(R.string.badge_pro))
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
                    label = { Text(stringResource(R.string.nav_printers), maxLines = 1) },
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    colors = navColors,
                    icon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_scanner), maxLines = 1) },
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    colors = navColors,
                    icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_pro_info), maxLines = 1) },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (ui.isBusy || ui.isDiscovering) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(50)),
                    color = AccentCyan,
                    trackColor = CardBorder,
                )
            }
            AnimatedVisibility(visible = ui.banner != null) {
                ui.banner?.let { b ->
                    Column(modifier = Modifier.padding(bottom = 10.dp)) {
                        UiBannerCard(banner = b, onDismiss = { viewModel.dismissBanner() })
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (activeTab) {
                    0 -> {
                        val detailPrinter = ui.detailPrinterId?.let { id ->
                            ui.workspace.printers.firstOrNull { it.id == id }
                        }
                        if (detailPrinter != null) {
                            PrinterDetailScreen(
                                profile = detailPrinter,
                                ui = ui,
                                viewModel = viewModel,
                                onOpenScannerTab = { activeTab = 1 },
                            )
                        } else {
                            HomeDashboardScreen(ui, viewModel)
                        }
                    }
                    1 -> ScannerScreen(ui, viewModel)
                    else -> AccountProScreen(ui, viewModel)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaywallModalSheet(
    feature: ProFeature,
    ui: MainUiState,
    viewModel: NozzleGuardianViewModel,
    onDismiss: () -> Unit,
) {
    val activity = LocalContext.current as? Activity
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CardSurface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProBadge(stringResource(R.string.badge_pro))
                Text(
                    stringResource(R.string.paywall_unlock_prefix, stringResource(featureTitleRes(feature))),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(stringResource(featureDescRes(feature)), style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Text(stringResource(R.string.paywall_lifetime_tagline), fontWeight = FontWeight.SemiBold, color = ProGold)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.paywall_benefit_auto), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.paywall_benefit_scanner), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.paywall_benefit_multi), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.paywall_benefit_sync), style = MaterialTheme.typography.bodySmall)
            }

            if (ui.billing.offers.isNotEmpty() && activity != null) {
                ui.billing.offers.forEach { offer ->
                    Button(
                        onClick = { viewModel.launchProPurchase(activity, offer) },
                        colors = ButtonDefaults.buttonColors(containerColor = ProGold, contentColor = Color(0xFF1F1200)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) {
                        Text(stringResource(R.string.pro_buy_lifetime, offer.formattedPrice), fontWeight = FontWeight.Bold)
                    }
                }
            } else if (!ui.entitlement.isPlayStoreInstall) {
                Button(
                    onClick = { viewModel.setPreviewProEnabled(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgDeep),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Text(stringResource(R.string.paywall_activate_preview), fontWeight = FontWeight.Bold)
                }
            }

            if (ui.entitlement.isPlayStoreInstall) {
                OutlinedButton(
                    onClick = { viewModel.restorePurchases() },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                ) {
                    Text(stringResource(R.string.pro_restore_purchases))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

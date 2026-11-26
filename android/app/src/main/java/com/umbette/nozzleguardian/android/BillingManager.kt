package com.umbette.nozzleguardian.android

import android.app.Activity
import android.content.Context
import android.os.Build
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.umbette.nozzleguardian.engine.EntitlementPolicy
import com.umbette.nozzleguardian.engine.PlanTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BillingProductOffer(
    val productId: String,
    val title: String,
    val formattedPrice: String,
    val isSubscription: Boolean,
    val rawDetails: ProductDetails,
)

data class BillingUiState(
    val billingAvailable: Boolean = false,
    val isPlayStoreInstall: Boolean = false,
    val activeProductId: String? = null,
    val offers: List<BillingProductOffer> = emptyList(),
    val statusText: String = "Inizializzazione Play Billing...",
)

/**
 * Google Play Billing (v7.1) integration for Nozzle Guardian Pro.
 * Automatically queries active subscriptions/one-time licenses and acknowledges purchases.
 */
class BillingManager(
    private val context: Context,
    private val onProStateResolved: (tier: PlanTier, source: String, productId: String?) -> Unit,
) : PurchasesUpdatedListener {

    private val _billingState = MutableStateFlow(
        BillingUiState(isPlayStoreInstall = detectPlayStoreInstaller(context))
    )
    val billingState: StateFlow<BillingUiState> = _billingState.asStateFlow()

    private var billingClient: BillingClient? = null

    fun start() {
        val isPlay = detectPlayStoreInstaller(context)
        if (billingClient != null) return

        val client = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()
        billingClient = client

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    _billingState.update {
                        it.copy(
                            billingAvailable = true,
                            isPlayStoreInstall = isPlay,
                            statusText = "Google Play Billing connesso",
                        )
                    }
                    queryAllProductsAndPurchases()
                } else {
                    _billingState.update {
                        it.copy(
                            billingAvailable = false,
                            isPlayStoreInstall = isPlay,
                            statusText = if (isPlay) {
                                "Play Billing non disponibile (${result.debugMessage})"
                            } else {
                                "Build open-source/sideload: acquisti Play Store attivi su Google Play"
                            },
                        )
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                _billingState.update { it.copy(billingAvailable = false) }
            }
        })
    }

    fun end() {
        billingClient?.endConnection()
        billingClient = null
    }

    fun launchPurchase(activity: Activity, offer: BillingProductOffer): Boolean {
        val client = billingClient ?: return false
        if (!client.isReady) return false

        val productParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(offer.rawDetails)

        if (offer.isSubscription) {
            val offerToken = offer.rawDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return false
            productParamsBuilder.setOfferToken(offerToken)
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParamsBuilder.build()))
            .build()
        val res = client.launchBillingFlow(activity, flowParams)
        return res.responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            handlePurchases(purchases)
        }
    }

    fun queryAllProductsAndPurchases() {
        val client = billingClient ?: return
        if (!client.isReady) return

        val collectedOffers = mutableListOf<BillingProductOffer>()

        val subsProducts = EntitlementPolicy.SUBSCRIPTION_SKUS.map { sku ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(sku)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(subsProducts).build()
        ) { res, detailsList ->
            if (res.responseCode == BillingClient.BillingResponseCode.OK) {
                detailsList.forEach { d ->
                    val price = d.subscriptionOfferDetails?.firstOrNull()
                        ?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice ?: ""
                    collectedOffers.add(
                        BillingProductOffer(
                            productId = d.productId,
                            title = d.name,
                            formattedPrice = price,
                            isSubscription = true,
                            rawDetails = d,
                        )
                    )
                }
                _billingState.update { it.copy(offers = collectedOffers.toList()) }
            }
        }

        val inappProducts = EntitlementPolicy.ONE_TIME_SKUS.map { sku ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(sku)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(inappProducts).build()
        ) { res, detailsList ->
            if (res.responseCode == BillingClient.BillingResponseCode.OK) {
                detailsList.forEach { d ->
                    val price = d.oneTimePurchaseOfferDetails?.formattedPrice ?: ""
                    collectedOffers.add(
                        BillingProductOffer(
                            productId = d.productId,
                            title = d.name,
                            formattedPrice = price,
                            isSubscription = false,
                            rawDetails = d,
                        )
                    )
                }
                _billingState.update { it.copy(offers = collectedOffers.toList()) }
            }
        }

        queryOwnedPurchases(client)
    }

    private fun queryOwnedPurchases(client: BillingClient) {
        val allPurchases = mutableListOf<Purchase>()
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { resSubs, subsList ->
            if (resSubs.responseCode == BillingClient.BillingResponseCode.OK) {
                allPurchases.addAll(subsList)
            }
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            ) { resInapp, inappList ->
                if (resInapp.responseCode == BillingClient.BillingResponseCode.OK) {
                    allPurchases.addAll(inappList)
                }
                handlePurchases(allPurchases)
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        val valid = purchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (valid != null) {
            if (!valid.isAcknowledged) {
                val ackParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(valid.purchaseToken)
                    .build()
                billingClient?.acknowledgePurchase(ackParams) { }
            }
            val sku = valid.products.firstOrNull()
            _billingState.update { it.copy(activeProductId = sku, statusText = "Licenza Pro attiva ($sku)") }
            onProStateResolved(PlanTier.PRO, "play_billing", sku)
        } else {
            _billingState.update { it.copy(activeProductId = null) }
            onProStateResolved(PlanTier.FREE, "free", null)
        }
    }

    companion object {
        fun detectPlayStoreInstaller(context: Context): Boolean = runCatching {
            val pm = context.packageManager
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
            installer == "com.android.vending"
        }.getOrDefault(false)
    }
}

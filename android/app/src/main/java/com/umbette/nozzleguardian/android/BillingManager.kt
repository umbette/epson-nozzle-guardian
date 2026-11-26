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

enum class BillingStatusKind {
    INIT,
    READY,
    UNAVAILABLE_PLAY,
    SIDELOAD,
    ACTIVE_PRO,
}

data class BillingUiState(
    val billingAvailable: Boolean = false,
    val isPlayStoreInstall: Boolean = false,
    val activeProductId: String? = null,
    val offers: List<BillingProductOffer> = emptyList(),
    val statusKind: BillingStatusKind = BillingStatusKind.INIT,
)

/**
 * Google Play Billing (v7.1) integration for Nozzle Guardian Lifetime Pro.
 * Prefers one-time lifetime licenses while still honoring legacy subscriptions if active.
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
                            statusKind = BillingStatusKind.READY,
                        )
                    }
                    queryAllProductsAndPurchases()
                } else {
                    _billingState.update {
                        it.copy(
                            billingAvailable = false,
                            isPlayStoreInstall = isPlay,
                            statusKind = if (isPlay) BillingStatusKind.UNAVAILABLE_PLAY else BillingStatusKind.SIDELOAD,
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

    fun restorePurchases() {
        val client = billingClient ?: return
        if (client.isReady) {
            queryOwnedPurchases(client)
        }
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
                val offers = detailsList.map { d ->
                    val price = d.oneTimePurchaseOfferDetails?.formattedPrice ?: ""
                    BillingProductOffer(
                        productId = d.productId,
                        title = d.name,
                        formattedPrice = price,
                        isSubscription = false,
                        rawDetails = d,
                    )
                }
                _billingState.update { it.copy(offers = offers) }
            }
        }

        queryOwnedPurchases(client)
    }

    private fun queryOwnedPurchases(client: BillingClient) {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { resInapp, inappList ->
            val ownedInapp = if (resInapp.responseCode == BillingClient.BillingResponseCode.OK) inappList else emptyList()
            if (ownedInapp.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }) {
                handlePurchases(ownedInapp)
                return@queryPurchasesAsync
            }
            // Fallback: honor any legacy active subscription cleanly without exposing new recurring SKUs.
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
            ) { resSubs, subsList ->
                val ownedSubs = if (resSubs.responseCode == BillingClient.BillingResponseCode.OK) subsList else emptyList()
                handlePurchases(ownedSubs)
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
            _billingState.update {
                it.copy(
                    activeProductId = sku,
                    statusKind = BillingStatusKind.ACTIVE_PRO,
                )
            }
            onProStateResolved(PlanTier.PRO, "play_billing", sku)
        } else {
            val isPlay = detectPlayStoreInstaller(context)
            _billingState.update {
                it.copy(
                    activeProductId = null,
                    statusKind = if (billingClient?.isReady == true) BillingStatusKind.READY
                    else if (isPlay) BillingStatusKind.UNAVAILABLE_PLAY
                    else BillingStatusKind.SIDELOAD,
                )
            }
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

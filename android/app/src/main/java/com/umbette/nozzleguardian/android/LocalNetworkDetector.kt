package com.umbette.nozzleguardian.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

data class DetectedLanInfo(
    val fingerprint: String,
    val label: String,
)

/**
 * Zero-permission automatic Wi-Fi LAN recognizer using Android LinkProperties
 * (no ACCESS_FINE_LOCATION / GPS permission required).
 */
object LocalNetworkDetector {
    fun detectCurrentWifiLan(context: Context): DetectedLanInfo? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val linkProps = cm.getLinkProperties(net) ?: return null
        val ipv4Link = linkProps.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val addrBytes = ipv4Link.address.address
        val prefix = ipv4Link.prefixLength.coerceIn(8, 30)
        val mask = (-1 shl (32 - prefix))
        val ipInt = ((addrBytes[0].toInt() and 0xFF) shl 24) or
            ((addrBytes[1].toInt() and 0xFF) shl 16) or
            ((addrBytes[2].toInt() and 0xFF) shl 8) or
            (addrBytes[3].toInt() and 0xFF)
        val netInt = ipInt and mask
        val subnetStr = "${(netInt ushr 24) and 0xFF}.${(netInt ushr 16) and 0xFF}.${(netInt ushr 8) and 0xFF}.${netInt and 0xFF}/$prefix"

        val gatewayIp = linkProps.routes
            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress
            ?: ""

        val fp = if (gatewayIp.isNotBlank()) "gw=$gatewayIp|net=$subnetStr" else "net=$subnetStr"
        val label = if (gatewayIp.isNotBlank()) "$subnetStr • GW $gatewayIp" else subnetStr
        DetectedLanInfo(fingerprint = fp, label = label)
    }.getOrNull()
}

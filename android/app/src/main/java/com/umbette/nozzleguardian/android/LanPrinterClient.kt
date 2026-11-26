package com.umbette.nozzleguardian.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.umbette.nozzleguardian.engine.DiscoveredPrinter
import com.umbette.nozzleguardian.engine.EwsParser
import com.umbette.nozzleguardian.engine.GuardianState
import com.umbette.nozzleguardian.engine.HeadTechnology
import com.umbette.nozzleguardian.engine.IppCodec
import com.umbette.nozzleguardian.engine.IppResponse
import com.umbette.nozzleguardian.engine.MarkerSupply
import com.umbette.nozzleguardian.engine.NozzleChannel
import com.umbette.nozzleguardian.engine.NozzleScanReport
import com.umbette.nozzleguardian.engine.OpticalNozzleScanner
import com.umbette.nozzleguardian.engine.PatternGenerator
import com.umbette.nozzleguardian.engine.PrinterKind
import com.umbette.nozzleguardian.engine.PrinterStatus
import com.umbette.nozzleguardian.engine.PurgeMode
import com.umbette.nozzleguardian.engine.PurgeOutcome
import com.umbette.nozzleguardian.engine.PwgRasterEncoder
import com.umbette.nozzleguardian.engine.RasterSurface
import com.umbette.nozzleguardian.engine.SnmpCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume

/**
 * Trust-On-First-Use (TOFU) X.509 TrustManager scoped strictly to per-printer LAN connections.
 * Never sets global defaults; enforces strict SHA-256 certificate pin verification once paired.
 */
private class TofuLanTrustManager(
    private val expectedSha256: String?,
    private val onObservedPin: (String) -> Unit,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Empty TLS certificate chain from LAN printer.")
        }
        val leaf = chain[0]
        val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        val fp = digest.joinToString(":") { "%02x".format(it) }
        if (!expectedSha256.isNullOrBlank()) {
            if (!fp.equals(expectedSha256, ignoreCase = true)) {
                throw CertificateException("Printer TLS certificate changed (expected $expectedSha256, got $fp)")
            }
        } else {
            onObservedPin(fp)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

class LanPrinterClient(private val context: Context, private val stateStore: StateStore) {
    private fun isLocalLanHost(host: String): Boolean = runCatching {
        val addr = InetAddress.getByName(host)
        addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress
    }.getOrDefault(false)

    private fun openConnection(urlStr: String, host: String, state: GuardianState, timeoutMs: Int): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        if (conn is HttpsURLConnection && isLocalLanHost(host)) {
            val tm = TofuLanTrustManager(state.pinnedCertSha256) { pin ->
                val cur = stateStore.loadState()
                if (cur.printerHost == host && cur.pinnedCertSha256 == null) {
                    stateStore.saveState(cur.copy(pinnedCertSha256 = pin))
                }
            }
            val sslCtx = SSLContext.getInstance("TLS")
            sslCtx.init(null, arrayOf(tm), SecureRandom())
            conn.sslSocketFactory = sslCtx.socketFactory
            conn.hostnameVerifier = HostnameVerifier { hostname, _ -> isLocalLanHost(hostname) }
        }
        return conn
    }

    private fun postIpp(
        host: String,
        port: Int,
        scheme: String,
        path: String,
        body: ByteArray,
        state: GuardianState,
        timeoutMs: Int,
    ): Pair<Int, IppResponse>? {
        val url = "$scheme://$host:$port$path"
        return runCatching {
            val conn = openConnection(url, host, state, timeoutMs)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/ipp")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val httpCode = conn.responseCode
            val raw = if (httpCode == 200) conn.inputStream.use { it.readBytes() } else ByteArray(0)
            conn.disconnect()
            if (httpCode == 200 && raw.size >= 8) {
                httpCode to IppCodec.parseResponse(raw)
            } else null
        }.getOrNull()
    }

    private data class IppProbeResult(
        val success: Boolean,
        val scheme: String = "ipp",
        val useTls: Boolean = false,
        val path: String = "/ipp/print",
        val attributes: IppResponse? = null,
        val error: String? = null,
    )

    private fun probeIpp(host: String, port: Int, preferTls: Boolean, state: GuardianState): IppProbeResult {
        val schemes = if (preferTls) listOf("https" to true, "http" to false) else listOf("http" to false, "https" to true)
        for ((httpScheme, isTls) in schemes) {
            val ippScheme = if (isTls) "ipps" else "ipp"
            for (path in IppCodec.IPP_PATHS) {
                val uri = "$ippScheme://$host:$port$path"
                val req = IppCodec.buildGetPrinterAttributesRequest(uri)
                val res = postIpp(host, port, httpScheme, path, req, state, 3500)
                if (res != null) {
                    val (_, parsed) = res
                    if (parsed.statusCode < 0x0400 || parsed.attributes.isNotEmpty()) {
                        return IppProbeResult(
                            success = true,
                            scheme = ippScheme,
                            useTls = isTls,
                            path = path,
                            attributes = parsed,
                        )
                    }
                }
            }
        }
        return IppProbeResult(success = false, error = "Nessuna risposta IPP su $host:$port")
    }

    private fun probeEws(host: String, port: Int, preferTls: Boolean, state: GuardianState): PrinterStatus? {
        val schemes = if (preferTls) listOf("https", "http") else listOf("http", "https")
        val target = if (port !in listOf(80, 443, 631)) "$host:$port" else host
        for (scheme in schemes) {
            for (ep in EwsParser.VENDOR_EWS_ENDPOINTS) {
                val res = runCatching {
                    val conn = openConnection("$scheme://$target$ep", host, state, 2000)
                    conn.requestMethod = "GET"
                    conn.instanceFollowRedirects = true
                    val code = conn.responseCode
                    val body = if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
                    conn.disconnect()
                    if (code == 200 && body.isNotBlank()) EwsParser.parseWebInfo(body) else null
                }.getOrNull()
                if (res != null && res.online) return res
            }
        }
        return null
    }

    private fun probeHpConsumables(host: String, port: Int, preferTls: Boolean, state: GuardianState): List<MarkerSupply> {
        val schemes = if (preferTls) listOf("https", "http") else listOf("http", "https")
        val target = if (port !in listOf(80, 443, 631)) "$host:$port" else host
        for (scheme in schemes) {
            val parsed = runCatching {
                val conn = openConnection("$scheme://$target/DevMgmt/ConsumableConfigDyn.xml", host, state, 2000)
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                val body = if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
                conn.disconnect()
                if (code == 200 && body.isNotBlank()) EwsParser.parseConsumablesXml(body) else emptyList()
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }
        return emptyList()
    }

    suspend fun getStatus(host: String, port: Int = 631, useTls: Boolean = false, preferredMedia: String = "auto"): PrinterStatus =
        withContext(Dispatchers.IO) {
            if (host.isBlank()) {
                return@withContext PrinterStatus(online = false, state = "unconfigured", errorMessage = "Nessuna stampante configurata.")
            }
            val state = stateStore.loadState()
            val ipp = probeIpp(host.trim(), port, useTls, state)
            val snmp = if (ipp.success) SnmpCodec.queryPrinterTelemetry(host.trim(), timeoutMs = 1200) else null
            val ews = probeEws(host.trim(), port, useTls, state)

            if (!ipp.success && (ews == null || !ews.online)) {
                return@withContext PrinterStatus(
                    online = false,
                    state = "offline",
                    errorMessage = ipp.error ?: "Stampante non raggiungibile su $host:$port",
                )
            }

            val attrs = ipp.attributes
            val model = attrs?.firstString("printer-make-and-model")
                ?: attrs?.firstString("printer-name")
                ?: ews?.makeAndModel
                ?: "Inkjet Printer"

            val mNames = attrs?.stringList("marker-names").orEmpty()
            val mLevels = attrs?.intList("marker-levels").orEmpty()
            val mTypes = attrs?.stringList("marker-types").orEmpty()
            val mColors = attrs?.stringList("marker-colors").orEmpty()

            val ippMarkers = mNames.mapIndexed { idx, mName ->
                MarkerSupply(
                    name = mName,
                    level = mLevels.getOrElse(idx) { -2 },
                    type = mTypes.getOrElse(idx) { "ink" },
                    colorHex = mColors.getOrElse(idx) { SnmpCodec.inferMarkerColorHex(mName) },
                )
            }

            var (kind, headTech) = IppCodec.classifyHardware(model, mTypes)
            if (ews != null && !ipp.success) {
                headTech = ews.headTechnology
                kind = ews.printerKind
            }

            val ippStateCode = attrs?.firstInt("printer-state")
            val stateStr = IppCodec.PRINTER_STATE_MAP[ippStateCode] ?: ews?.state ?: "idle"
            val reasons = attrs?.stringList("printer-state-reasons").orEmpty().filter { it != "none" }
            val supportedFormats = attrs?.stringList("document-format-supported").orEmpty()
            val mediaSupported = attrs?.stringList("media-supported").orEmpty()
            val mediaDefault = IppCodec.negotiateMedia(preferredMedia, attrs?.firstString("media-default"), mediaSupported)
            val pwgResolutions = IppCodec.parsePwgResolutions(attrs?.attributes?.get("pwg-raster-document-resolution-supported"))
            val pwgDocTypes = attrs?.stringList("pwg-raster-document-type-supported").orEmpty().map { it.lowercase() }
            val printColorModes = attrs?.stringList("print-color-mode-supported").orEmpty().map { it.lowercase() }
            val mediaTypes = attrs?.stringList("media-type-supported").orEmpty().map { it.lowercase() }

            val mono = ews?.monochromePages
            val color = ews?.colorPages
            var total = ews?.totalPages
            val ippTotal = attrs?.firstInt("printer-impressions-completed")
            if (total == null && ippTotal != null && ippTotal >= 0) total = ippTotal
            val needsSnmp = total == null || ippMarkers.none { it.hasReliableInkLevel }
            val finalSnmp = if (needsSnmp) snmp ?: SnmpCodec.queryPrinterTelemetry(host.trim(), timeoutMs = 1200) else snmp
            if (total == null && finalSnmp?.totalPages != null) total = finalSnmp.totalPages

            val snmpMarkers = finalSnmp?.markers.orEmpty()
            val hasInkFromIppOrSnmp = ippMarkers.any { it.hasReliableInkLevel } || snmpMarkers.any { it.hasReliableInkLevel }
            val modelLow = (model + " " + (ews?.makeAndModel ?: "")).lowercase()
            val ewsMarkers = if (!hasInkFromIppOrSnmp && listOf("hp", "deskjet", "officejet", "smart tank", "laserjet", "envy").any { it in modelLow }) {
                probeHpConsumables(host.trim(), port, useTls, state)
            } else {
                emptyList()
            }
            val markers = IppCodec.mergeMarkers(ippMarkers, snmpMarkers, ewsMarkers)

            val (detectedTanks, detectedProfile) = IppCodec.detectInkProfile(model, markers)
            val effectiveProfile = if (state.inkProfile in listOf("cmyk", "cmyk6")) state.inkProfile else detectedProfile
            val effectiveTanks = if (effectiveProfile == "cmyk6" && detectedTanks < 6) 6 else detectedTanks

            PrinterStatus(
                online = true,
                monochromePages = mono,
                colorPages = color,
                totalPages = total,
                state = stateStr,
                stateReasons = reasons,
                makeAndModel = model,
                printerKind = kind,
                headTechnology = headTech,
                mediaDefault = mediaDefault,
                supportedFormats = supportedFormats,
                markers = markers,
                endpointPath = ipp.path,
                usedTls = ipp.useTls,
                pwgResolutionsSupported = pwgResolutions,
                pwgDocumentTypesSupported = pwgDocTypes,
                printColorModesSupported = printColorModes,
                mediaTypesSupported = mediaTypes,
                tankCount = effectiveTanks,
                inkProfile = effectiveProfile,
            )
        }

    private fun encodeSurfaceForFormat(
        surface: RasterSurface,
        docFormat: String,
        dpi: Int,
        media: String,
        mediaType: String = "Stationery",
    ): ByteArray {
        val fmt = docFormat.lowercase().trim()
        if (fmt == "image/pwg-raster") {
            return PwgRasterEncoder.encode(surface, dpi = dpi, mediaName = media, mediaType = mediaType)
        }

        val bmp = Bitmap.createBitmap(surface.pixels, surface.width, surface.height, Bitmap.Config.ARGB_8888)
        return try {
            when (fmt) {
                "application/pdf" -> {
                    val spec = PwgRasterEncoder.mediaSpecFor(media)
                    val doc = PdfDocument()
                    val pageInfo = PdfDocument.PageInfo.Builder(spec.pointsWidth, spec.pointsHeight, 1).create()
                    val page = doc.startPage(pageInfo)
                    page.canvas.drawBitmap(
                        bmp,
                        null,
                        Rect(0, 0, spec.pointsWidth, spec.pointsHeight),
                        null,
                    )
                    doc.finishPage(page)
                    val out = ByteArrayOutputStream()
                    doc.writeTo(out)
                    doc.close()
                    out.toByteArray()
                }
                "image/jpeg" -> ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
                else -> ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            }
        } finally {
            bmp.recycle()
        }
    }

    suspend fun dispatchPurge(
        host: String,
        port: Int,
        mode: PurgeMode,
        status: PrinterStatus? = null,
        printerState: GuardianState? = null,
    ): PurgeOutcome =
        withContext(Dispatchers.IO) {
            val state = printerState ?: stateStore.loadState()
            val st = status ?: getStatus(host, port, state.useTls, state.mediaSize)
            if (!st.online) return@withContext PurgeOutcome(false, st.errorMessage ?: "Offline", mode.code)
            if (st.printerKind == PrinterKind.LASER) {
                return@withContext PurgeOutcome(false, "Laser/Toner: ink purge disabled", mode.code)
            }
            if (st.isMediaEmpty) {
                return@withContext PurgeOutcome(false, "media-empty", mode.code)
            }

            val primaryFmt = IppCodec.negotiateFormat(st.supportedFormats, state.preferredFormat)
            val formatsToTry = listOf(primaryFmt) + IppCodec.getFallbackFormats(primaryFmt, st.supportedFormats)
            val negotiatedDpi = IppCodec.negotiatePwgDpi(st.pwgResolutionsSupported, 300)
            val forceRgb = IppCodec.negotiatePwgForceRgb(mode.code, st.pwgDocumentTypesSupported)
            val colorMode = IppCodec.negotiateColorMode(mode.code, st.printColorModesSupported, forceRgb)
            val mediaType = IppCodec.negotiateMediaType(mode.code, state.mediaType, st.mediaTypesSupported)
            val pwgMediaType = mediaType ?: "Stationery"

            val gen = PatternGenerator(dpi = negotiatedDpi, mediaName = st.mediaDefault)
            var lastOutcome = PurgeOutcome(false, "No supported format succeeded", mode.code)
            for (docFormat in formatsToTry) {
                val useRgbForPwg = (docFormat == "image/pwg-raster" && forceRgb)
                val surface = gen.buildPurgeSurface(mode, forceRgb = useRgbForPwg)
                val payload = encodeSurfaceForFormat(surface, docFormat, gen.dpi, st.mediaDefault, pwgMediaType)
                lastOutcome = submitIppJob(host, port, st, payload, docFormat, colorMode, mode.code, 0, mediaType)
                if (lastOutcome.success) return@withContext lastOutcome
            }
            lastOutcome
        }

    suspend fun dispatchSurgicalPurge(
        host: String,
        port: Int,
        channel: NozzleChannel,
        step: Int,
        bursts: Int = 5,
        status: PrinterStatus? = null,
        printerState: GuardianState? = null,
    ): PurgeOutcome = dispatchSurgicalBatchPurge(
        host = host,
        port = port,
        targets = listOf(channel to step),
        bursts = bursts,
        status = status,
        printerState = printerState,
    )

    suspend fun dispatchSurgicalBatchPurge(
        host: String,
        port: Int,
        targets: List<Pair<NozzleChannel, Int>>,
        bursts: Int = 5,
        status: PrinterStatus? = null,
        printerState: GuardianState? = null,
    ): PurgeOutcome = withContext(Dispatchers.IO) {
        if (targets.isEmpty()) return@withContext PurgeOutcome(false, "No targets selected", "surgical")
        val state = printerState ?: stateStore.loadState()
        val st = status ?: getStatus(host, port, state.useTls, state.mediaSize)
        if (!st.online) return@withContext PurgeOutcome(false, st.errorMessage ?: "Offline", "surgical")
        if (st.printerKind == PrinterKind.LASER) {
            return@withContext PurgeOutcome(false, "Laser/Toner: ink purge disabled", "surgical")
        }
        if (st.isMediaEmpty) {
            return@withContext PurgeOutcome(false, "media-empty", "surgical")
        }

        val primaryFmt = IppCodec.negotiateFormat(st.supportedFormats, state.preferredFormat)
        val formatsToTry = listOf(primaryFmt) + IppCodec.getFallbackFormats(primaryFmt, st.supportedFormats)
        val negotiatedDpi = IppCodec.negotiatePwgDpi(st.pwgResolutionsSupported, 300)
        val hasPbOrGy = targets.any { it.first == NozzleChannel.PHOTO_BLACK || it.first == NozzleChannel.GRAY }
        val allBlackOnly = targets.all { it.first == NozzleChannel.BLACK }
        val modeCode = when {
            allBlackOnly -> "k"
            hasPbOrGy -> "cmyk6"
            else -> "color"
        }
        val forceRgb = IppCodec.negotiatePwgForceRgb(modeCode, st.pwgDocumentTypesSupported)
        val colorMode = IppCodec.negotiateColorMode(modeCode, st.printColorModesSupported, forceRgb)
        val mediaType = IppCodec.negotiateMediaType(modeCode, state.mediaType, st.mediaTypesSupported)
        val pwgMediaType = mediaType ?: "Stationery"

        val label = if (targets.size == 1) {
            "${targets.first().first.code}#${targets.first().second}"
        } else {
            "batch(${targets.size})"
        }
        val gen = PatternGenerator(dpi = negotiatedDpi, mediaName = st.mediaDefault)
        var lastOutcome = PurgeOutcome(false, "No supported format succeeded.", label)
        for (docFormat in formatsToTry) {
            val useRgbForPwg = (docFormat == "image/pwg-raster" && forceRgb)
            val built = gen.buildSurgicalBatchSheet(targets, bursts, st.headTechnology, forceRgb = useRgbForPwg)
            val payload = encodeSurfaceForFormat(built.surface, docFormat, gen.dpi, st.mediaDefault, pwgMediaType)
            lastOutcome = submitIppJob(host, port, st, payload, docFormat, colorMode, label, built.effectiveBursts, mediaType)
            if (lastOutcome.success) return@withContext lastOutcome
        }
        lastOutcome
    }

    private fun submitIppJob(
        host: String,
        port: Int,
        st: PrinterStatus,
        payload: ByteArray,
        docFormat: String,
        colorMode: String,
        modeLabel: String,
        effectiveBursts: Int,
        mediaType: String? = null,
    ): PurgeOutcome {
        val state = stateStore.loadState()
        val httpScheme = if (st.usedTls) "https" else "http"
        val ippScheme = if (st.usedTls) "ipps" else "ipp"
        val ippUri = "$ippScheme://$host:$port${st.endpointPath}"
        val header = IppCodec.buildPrintJobRequest(
            printerUri = ippUri,
            docFormat = docFormat,
            media = st.mediaDefault,
            colorMode = colorMode,
            mediaType = mediaType,
        )
        val body = header + payload
        val res = postIpp(host, port, httpScheme, st.endpointPath, body, state, 25000)
            ?: return PurgeOutcome(false, "Errore invio IPP a $ippUri", modeLabel, docFormat, effectiveBursts)
        val (_, parsed) = res
        return if (parsed.statusCode < 0x0100) {
            PurgeOutcome(
                success = true,
                message = "Job accettato ($docFormat, ${st.headTechnology.name})",
                mode = modeLabel,
                docFormat = docFormat,
                effectiveBursts = effectiveBursts,
            )
        } else {
            val reason = parsed.firstString("status-message") ?: "IPP 0x%04x".format(parsed.statusCode)
            PurgeOutcome(false, "Rifiutato dalla stampante: $reason", modeLabel, docFormat, effectiveBursts)
        }
    }

    suspend fun discoverLanPrinters(timeoutMs: Long = 3500L): List<DiscoveredPrinter> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val mLock = wifi?.createMulticastLock("nozzle_guardian_nsd")?.apply { setReferenceCounted(false) }
        val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val rawFound = ConcurrentHashMap<String, Pair<String, Int>>()
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()

        try {
            runCatching { mLock?.acquire() }
            for (serviceType in listOf("_ipps._tcp.", "_ipp._tcp.")) {
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) = Unit
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        resolveNsdService(nsdManager, serviceInfo) { host, port, name ->
                            if (host.isNotBlank()) {
                                rawFound.putIfAbsent("$host:$port", name to port)
                            }
                        }
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                    override fun onDiscoveryStopped(serviceType: String) = Unit
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                }
                listeners.add(listener)
                runCatching { nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
            }
            delay(timeoutMs)
        } finally {
            listeners.forEach { l -> runCatching { nsdManager.stopServiceDiscovery(l) } }
            runCatching { if (mLock?.isHeld == true) mLock.release() }
        }

        val state = stateStore.loadState()
        rawFound.entries.mapNotNull { (key, pair) ->
            val host = key.substringBeforeLast(':')
            val port = pair.second
            val probe = probeIpp(host, port, port == 443 || port == 631, state)
            if (probe.success && probe.attributes != null) {
                val attrs = probe.attributes
                val model = attrs.firstString("printer-make-and-model")
                    ?: attrs.firstString("printer-name")
                    ?: pair.first
                val mTypes = attrs.stringList("marker-types")
                val (kind, headTech) = IppCodec.classifyHardware(model, mTypes)
                DiscoveredPrinter(
                    host = host,
                    port = port,
                    useTls = probe.useTls,
                    endpointPath = probe.path,
                    makeAndModel = model,
                    printerKind = kind,
                    headTechnology = headTech,
                    mediaDefault = attrs.firstString("media-default") ?: "iso_a4_210x297mm",
                    supportedFormats = attrs.stringList("document-format-supported"),
                    source = "android-nsd",
                )
            } else {
                DiscoveredPrinter(
                    host = host,
                    port = port,
                    useTls = false,
                    makeAndModel = pair.first,
                    source = "android-nsd",
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNsdService(
        nsdManager: NsdManager,
        serviceInfo: NsdServiceInfo,
        onResolved: (String, Int, String) -> Unit,
    ) {
        val done = AtomicBoolean(false)
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                if (!done.compareAndSet(false, true)) return
                val hostAddr: InetAddress? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    resolved.hostAddresses.firstOrNull { it is Inet4Address } ?: resolved.hostAddresses.firstOrNull()
                } else {
                    resolved.host
                }
                val ip = hostAddr?.hostAddress ?: return
                onResolved(ip, resolved.port.takeIf { it > 0 } ?: 631, resolved.serviceName ?: "Stampante Wi-Fi")
            }
        }
        runCatching { nsdManager.resolveService(serviceInfo, listener) }
    }

    suspend fun scanPhotoUri(uri: Uri): NozzleScanReport = withContext(Dispatchers.Default) {
        val resolver = context.contentResolver
        val rotation = resolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        val decoded = resolver.openInputStream(uri)?.use { stream ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, bounds)
            val sample = maxOf(1, bounds.outWidth / 1400)
            resolver.openInputStream(uri)?.use { s2 ->
                BitmapFactory.decodeStream(s2, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } ?: return@withContext NozzleScanReport(success = false, error = "Immagine illeggibile", message = "Impossibile decodificare la foto.")

        val oriented = if (rotation != 0f) {
            val matrix = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else decoded

        val scaled = if (oriented.width > 1400) {
            val targetH = maxOf(1, (oriented.height * (1400.0 / oriented.width)).toInt())
            Bitmap.createScaledBitmap(oriented, 1400, targetH, true).also {
                if (it !== oriented) oriented.recycle()
            }
        } else oriented

        try {
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            OpticalNozzleScanner.analyze(pixels, scaled.width, scaled.height)
        } finally {
            scaled.recycle()
        }
    }
}

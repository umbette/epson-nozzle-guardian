package com.umbette.nozzleguardian.engine

import kotlin.math.round

/**
 * Lighting-adaptive optical analyzer for Nozzle Guardian printout photos.
 * Normalizes illuminated paper white, strips desk margins, detects K-only vs CMY/CMYK sheets,
 * and isolates missing nozzle rungs (1..10) per channel to recommend exact surgical recovery.
 */
object OpticalNozzleScanner {
    private fun estimatePaperWhite(pixels: IntArray, w: Int, h: Int): Triple<Double, Double, Double> {
        val samples = mutableListOf<DoubleArray>()
        val stepX = maxOf(1, w / 48)
        val stepY = maxOf(1, h / 48)
        var y = (h * 0.08).toInt()
        while (y < h) {
            var x = (w * 0.12).toInt()
            val endX = (w * 0.88).toInt()
            while (x < endX) {
                val p = pixels[y * w + x]
                val r = ((p ushr 16) and 0xFF).toDouble()
                val g = ((p ushr 8) and 0xFF).toDouble()
                val b = (p and 0xFF).toDouble()
                samples.add(doubleArrayOf((r + g + b) / 3.0, r, g, b))
                x += stepX
            }
            y += stepY
        }
        if (samples.isEmpty()) return Triple(255.0, 255.0, 255.0)
        samples.sortBy { it[0] }
        val start = (samples.size * 0.78).toInt()
        val end = maxOf(start + 1, (samples.size * 0.96).toInt())
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        val count = end - start
        for (i in start until end) {
            sumR += samples[i][1]
            sumG += samples[i][2]
            sumB += samples[i][3]
        }
        return Triple(maxOf(80.0, sumR / count), maxOf(80.0, sumG / count), maxOf(80.0, sumB / count))
    }

    private fun classifyPixel(argb: Int, wr: Double, wg: Double, wb: Double): String? {
        val nr = ((argb ushr 16) and 0xFF) / wr
        val ng = ((argb ushr 8) and 0xFF) / wg
        val nb = (argb and 0xFF) / wb
        val maxC = maxOf(nr, ng, nb)
        val minC = minOf(nr, ng, nb)
        val chroma = maxC - minC
        val lumRatio = (nr + ng + nb) / 3.0

        if ((ng - nr) >= 0.035 && (nb - nr) >= 0.065 && nr < 0.80 && minOf(ng, nb) > 0.45) return "cyan"
        if ((nr - ng) >= 0.14 && (nb - ng) >= 0.05 && ng < 0.78 && nr > 0.45 && nb > 0.30) return "magenta"
        if ((minOf(nr, ng) - nb) >= 0.14 && nb < 0.82 && minOf(nr, ng) > 0.56) return "yellow"
        if (lumRatio < 0.76 && chroma < 0.16 && (nb - nr) < 0.06) return "black"
        return null
    }

    private fun findPaperBounds(pixels: IntArray, w: Int, h: Int, lumWhite: Double): Triple<Int, Int, Int> {
        val thresh = lumWhite * 0.65
        val cx0 = (w * 0.25).toInt()
        val cx1 = maxOf(cx0 + 4, (w * 0.75).toInt())
        val strideX = maxOf(2, (cx1 - cx0) / 40)

        var paperTop = 0
        var streak = 0
        for (y in 0 until h) {
            var bright = 0
            var total = 0
            var x = cx0
            while (x < cx1) {
                val p = pixels[y * w + x]
                val lum = (((p ushr 16) and 0xFF) + ((p ushr 8) and 0xFF) + (p and 0xFF)) / 3.0
                if (lum >= thresh) bright++
                total++
                x += strideX
            }
            if (total > 0 && bright.toDouble() / total >= 0.65) {
                streak++
                if (streak >= 4) {
                    paperTop = maxOf(0, y - 3)
                    break
                }
            } else {
                streak = 0
            }
        }

        val probeY0 = minOf(h - 1, paperTop + 4)
        val probeY1 = minOf(h, paperTop + maxOf(20, ((h - paperTop) * 0.55).toInt()))
        val strideY = maxOf(2, (probeY1 - probeY0) / 25)

        fun isPaperCol(x: Int): Boolean {
            var cnt = 0
            var tot = 0
            var y = probeY0
            while (y < probeY1) {
                val p = pixels[y * w + x]
                val lum = (((p ushr 16) and 0xFF) + ((p ushr 8) and 0xFF) + (p and 0xFF)) / 3.0
                if (lum >= thresh) cnt++
                tot++
                y += strideY
            }
            return tot > 0 && cnt.toDouble() / tot >= 0.55
        }

        var px0 = 0
        while (px0 < (w * 0.35).toInt() && !isPaperCol(px0)) px0 += 2
        var px1 = w - 1
        while (px1 > (w * 0.65).toInt() && !isPaperCol(px1)) px1 -= 2
        if (px1 - px0 < (w * 0.4).toInt()) {
            px0 = 0
            px1 = w - 1
        }
        return Triple(paperTop, px0, px1 + 1)
    }

    private data class BandBounds(
        val ly0: Int,
        val ly1: Int,
        val activeX0: Int,
        val activeX1: Int,
        val barBottomSamples: List<Pair<Int, Int>>,
    )

    private fun locateLadderBand(
        pixels: IntArray,
        w: Int,
        x0: Int,
        x1: Int,
        yStart: Int,
        yEnd: Int,
        wr: Double,
        wg: Double,
        wb: Double,
    ): BandBounds? {
        val stepX = maxOf(1, (x1 - x0) / 220)
        val samplesPerRow = maxOf(1, (x1 - x0) / stepX)
        val rowDensity = DoubleArray(maxOf(0, yEnd - yStart))
        for (y in yStart until yEnd) {
            var hits = 0
            var x = x0
            while (x < x1) {
                if (classifyPixel(pixels[y * w + x], wr, wg, wb) != null) hits++
                x += stepX
            }
            rowDensity[y - yStart] = hits.toDouble() / samplesPerRow
        }

        val denseRows = (yStart until yEnd).filter { rowDensity[it - yStart] >= 0.32 }
        var barCluster = emptyList<Int>()
        if (denseRows.isNotEmpty()) {
            val clusters = mutableListOf<MutableList<Int>>()
            var cur = mutableListOf<Int>()
            val tol = maxOf(14, ((yEnd - yStart) * 0.03).toInt())
            for (yr in denseRows) {
                if (cur.isEmpty() || yr - cur.last() <= tol) {
                    cur.add(yr)
                } else {
                    clusters.add(cur)
                    cur = mutableListOf(yr)
                }
            }
            if (cur.isNotEmpty()) clusters.add(cur)
            barCluster = clusters.firstOrNull { it.size >= 4 } ?: clusters.first()
        }

        var activeX0 = x0
        var activeX1 = x1
        val barBottoms = mutableListOf<Pair<Int, Int>>()
        val searchFrom: Int
        if (barCluster.isNotEmpty()) {
            val lastBarY = barCluster.last()
            val spanStep = maxOf(2, (x1 - x0) / 250)
            val sampleYs = barCluster.filterIndexed { idx, _ -> idx % maxOf(1, barCluster.size / 8) == 0 }
            val colHits = mutableListOf<Int>()
            var x = x0
            while (x < x1) {
                if (sampleYs.any { by -> classifyPixel(pixels[by * w + x], wr, wg, wb) != null }) {
                    colHits.add(x)
                }
                x += spanStep
            }
            if (colHits.size >= 10) {
                var splitIdx = 0
                val gapJump = maxOf(6, ((x1 - x0) * 0.015).toInt())
                for (k in 1 until minOf(colHits.size, 15)) {
                    if (colHits[k] - colHits[k - 1] > gapJump) {
                        splitIdx = k
                        break
                    }
                }
                activeX0 = colHits[splitIdx]
                activeX1 = minOf(x1, colHits.last() + spanStep)
            }
            val barWinTop = maxOf(yStart, lastBarY - maxOf(10, ((yEnd - yStart) * 0.04).toInt()))
            val barWinBot = minOf(yEnd, lastBarY + maxOf(8, ((yEnd - yStart) * 0.025).toInt()))
            val numSlices = 12
            val sliceW = maxOf(1, (activeX1 - activeX0) / numSlices)
            for (si in 0 until numSlices) {
                val sx = activeX0 + ((si + 0.5) * sliceW).toInt()
                if (sx >= activeX1) continue
                var maxBy = -1
                for (by in barWinTop until barWinBot) {
                    if (classifyPixel(pixels[by * w + sx], wr, wg, wb) != null) maxBy = by
                }
                if (maxBy >= 0) barBottoms.add(sx to maxBy)
            }
            val deepestBarY = barBottoms.maxOfOrNull { it.second } ?: lastBarY
            searchFrom = deepestBarY + maxOf(3, ((yEnd - yStart) * 0.012).toInt())
        } else {
            searchFrom = yStart
        }

        val segments = mutableListOf<MutableList<Int>>()
        var curSeg = mutableListOf<Int>()
        val gapTol = maxOf(6, ((yEnd - yStart) * 0.015).toInt())
        for (y in searchFrom until yEnd) {
            if (rowDensity[y - yStart] >= 0.012) {
                if (curSeg.isEmpty() || (y - curSeg.last()) <= gapTol) {
                    curSeg.add(y)
                } else {
                    segments.add(curSeg)
                    curSeg = mutableListOf(y)
                }
            }
        }
        if (curSeg.isNotEmpty()) segments.add(curSeg)

        val minH = maxOf(16, ((yEnd - yStart) * 0.045).toInt())
        val chosen = segments.firstOrNull { (it.last() - it.first()) >= minH }
            ?: segments.maxByOrNull { it.last() - it.first() }
            ?: return null
        if (chosen.size < 6) return null
        return BandBounds(chosen.first(), chosen.last() + 1, activeX0, activeX1, barBottoms)
    }

    private fun evaluateSteps(
        pixels: IntArray,
        w: Int,
        x0: Int,
        x1: Int,
        ly0: Int,
        ly1: Int,
        targetChannel: String,
        wr: Double,
        wg: Double,
        wb: Double,
        leftInsetPct: Double = 0.03,
        rightInsetPct: Double = 0.03,
    ): ChannelScanResult {
        val spanH = maxOf(10, ly1 - ly0)
        val colW = maxOf(4, x1 - x0)
        val ix0 = x0 + maxOf(1, (colW * leftInsetPct).toInt())
        val ix1 = maxOf(ix0 + 2, x1 - maxOf(1, (colW * rightInsetPct).toInt()))
        val stepX = if ((ix1 - ix0) < 260) 1 else 2
        val stepH = spanH / 10.0
        val minBandHits = maxOf(2, (((ix1 - ix0) / stepX.toDouble()) * 0.025).toInt())

        val missing = mutableListOf<Int>()
        for (s in 0 until 10) {
            val top = ly0 + s * stepH
            val bot = ly0 + (s + 1) * stepH
            val sy0 = round(top + stepH * 0.12).toInt()
            val sy1 = maxOf(sy0 + 1, round(bot - stepH * 0.12).toInt())
            var hits = 0
            for (y in sy0 until minOf(ly1, sy1)) {
                var x = ix0
                while (x < ix1) {
                    if (classifyPixel(pixels[y * w + x], wr, wg, wb) == targetChannel) hits++
                    x += stepX
                }
            }
            if (hits < minBandHits) missing.add(s + 1)
        }

        val health = round(((10 - missing.size) / 10.0) * 1000.0) / 10.0
        return ChannelScanResult(
            present = true,
            healthy = missing.isEmpty(),
            healthPct = health,
            missingSteps = missing,
        )
    }

    fun analyze(argbPixels: IntArray, width: Int, height: Int): NozzleScanReport {
        if (width <= 0 || height <= 0 || argbPixels.size < width * height) {
            return NozzleScanReport(
                success = false,
                error = "Immagine non valida",
                message = "Impossibile analizzare i pixel dell'immagine.",
            )
        }

        val sampleH = minOf(height, maxOf(240, (height * 0.48).toInt()))
        val (wr, wg, wb) = estimatePaperWhite(argbPixels, width, sampleH)
        val lumWhite = (wr + wg + wb) / 3.0
        val (paperTop, paperX0, paperX1) = findPaperBounds(argbPixels, width, sampleH, lumWhite)
        val band = locateLadderBand(argbPixels, width, paperX0, paperX1, paperTop, sampleH, wr, wg, wb)
            ?: return NozzleScanReport(
                success = false,
                error = "Nessun pattern Nozzle Guardian rilevato",
                message = "Nessuna scala test rilevata nella fascia superiore del foglio.",
            )

        val topCounts = mutableMapOf("cyan" to 0, "magenta" to 0, "yellow" to 0, "black" to 0)
        val stepX = maxOf(1, (band.activeX1 - band.activeX0) / 180)
        val stepY = maxOf(1, (band.ly1 - paperTop) / 90)
        var y = paperTop
        while (y < band.ly1) {
            var x = band.activeX0
            while (x < band.activeX1) {
                val cls = classifyPixel(argbPixels[y * width + x], wr, wg, wb)
                if (cls != null) topCounts[cls] = (topCounts[cls] ?: 0) + 1
                x += stepX
            }
            y += stepY
        }

        val hasCmy = (topCounts["cyan"] ?: 0) >= 12 || (topCounts["magenta"] ?: 0) >= 12 || (topCounts["yellow"] ?: 0) >= 12
        val hasK = (topCounts["black"] ?: 0) >= 12

        val report = linkedMapOf<String, ChannelScanResult>()
        val activeChannels: List<String>
        val sheetMode: String

        if (!hasCmy && hasK) {
            sheetMode = "k"
            activeChannels = listOf("black")
            val activeW = band.activeX1 - band.activeX0
            report["black"] = evaluateSteps(
                argbPixels,
                width,
                band.activeX0 + (activeW * 0.20).toInt(),
                band.activeX1 - (activeW * 0.09).toInt(),
                band.ly0,
                band.ly1,
                "black",
                wr,
                wg,
                wb,
            )
            listOf("cyan", "magenta", "yellow").forEach {
                report[it] = ChannelScanResult(present = false, healthy = null, healthPct = null, missingSteps = emptyList())
            }
        } else {
            sheetMode = if (hasK) "cmyk" else "cmy"
            activeChannels = if (hasK) listOf("cyan", "magenta", "yellow", "black") else listOf("cyan", "magenta", "yellow")
            val activeW = band.activeX1 - band.activeX0
            val colW = maxOf(4, activeW / activeChannels.size)
            val barBottoms = band.barBottomSamples
            val minBarY = barBottoms.minOfOrNull { it.second } ?: 0
            val tiltSpan = if (barBottoms.isNotEmpty()) (barBottoms.maxOf { it.second } - minBarY) else 0
            val unskewedH = maxOf(10, (band.ly1 - band.ly0) - minOf(tiltSpan, maxOf(2, ((band.ly1 - band.ly0) * 0.15).toInt())))

            fun colYBounds(cx0: Int, cx1: Int): Pair<Int, Int> {
                if (barBottoms.isEmpty()) return band.ly0 to band.ly1
                val inCol = barBottoms.filter { (bx, _) -> bx in cx0..cx1 }.map { it.second }
                val localB = if (inCol.isNotEmpty()) {
                    inCol.sum() / inCol.size
                } else {
                    val mid = (cx0 + cx1) / 2
                    barBottoms.minBy { kotlin.math.abs(it.first - mid) }.second
                }
                val colY0 = band.ly0 + maxOf(0, localB - minBarY)
                return colY0 to minOf(sampleH, colY0 + unskewedH)
            }

            activeChannels.forEachIndexed { idx, ch ->
                val cx0 = band.activeX0 + idx * colW
                val cx1 = if (idx == activeChannels.size - 1) band.activeX1 else cx0 + colW
                val (cy0, cy1) = colYBounds(cx0, cx1)
                var res = evaluateSteps(argbPixels, width, cx0, cx1, cy0, cy1, ch, wr, wg, wb, leftInsetPct = 0.45, rightInsetPct = 0.02)
                if (idx == 0 || (res.healthPct ?: 100.0) < 100.0) {
                    for (shiftFrac in listOf(0.02, -0.02)) {
                        val shift = (activeW * shiftFrac).toInt()
                        val scX0 = band.activeX0 + shift
                        val scW = maxOf(4, (band.activeX1 - scX0) / activeChannels.size)
                        val altX0 = scX0 + idx * scW
                        val altX1 = if (idx == activeChannels.size - 1) band.activeX1 else altX0 + scW
                        val (ay0, ay1) = colYBounds(altX0, altX1)
                        val alt = evaluateSteps(argbPixels, width, altX0, altX1, ay0, ay1, ch, wr, wg, wb, leftInsetPct = 0.45, rightInsetPct = 0.02)
                        if ((alt.healthPct ?: 0.0) > (res.healthPct ?: 0.0)) res = alt
                    }
                }
                report[ch] = res
            }
            if (!hasK) {
                report["black"] = ChannelScanResult(present = false, healthy = null, healthPct = null, missingSteps = emptyList())
            }
        }

        val missingRungs = mutableListOf<String>()
        var targetChannel: String? = null
        var targetStep: Int? = null
        activeChannels.forEach { ch ->
            val missing = report[ch]?.missingSteps.orEmpty()
            if (missing.isNotEmpty()) {
                missing.forEach { missingRungs.add("${ch.uppercase()} #$it") }
                if (targetChannel == null) {
                    targetChannel = ch
                    targetStep = missing.first()
                }
            }
        }

        val overall = if (activeChannels.isEmpty()) 0.0 else {
            val avg = activeChannels.map { report[it]?.healthPct ?: 100.0 }.average()
            round(avg * 10.0) / 10.0
        }

        val action = if (targetChannel != null && targetStep != null) "surgical" else "none"
        val msg = if (missingRungs.isEmpty()) {
            "Tutti i canali rilevati ($sheetMode) sono al 100%."
        } else {
            "Rilevati ${missingRungs.size} gradini mancanti (${missingRungs.take(3).joinToString(", ")}). Consigliato sparo chirurgico su ${targetChannel?.uppercase()} #$targetStep."
        }

        return NozzleScanReport(
            success = true,
            overallHealthPct = overall,
            sheetMode = sheetMode,
            channels = report,
            missingRungs = missingRungs,
            recommendedAction = action,
            recommendedActions = if (targetChannel != null) listOf("purge_${targetChannel}") else emptyList(),
            targetChannel = targetChannel,
            targetStep = targetStep,
            message = msg,
        )
    }
}

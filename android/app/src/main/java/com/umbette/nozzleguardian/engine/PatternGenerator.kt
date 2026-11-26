package com.umbette.nozzleguardian.engine

import kotlin.math.roundToInt

/**
 * Generates full-width (~210mm) micro-purge stripes + 1..10 staircase calibration ladders
 * and thermal-safe surgical single-nozzle bursts in pure Kotlin.
 */
class PatternGenerator(
    val dpi: Int = 300,
    mediaName: String = "iso_a4_210x297mm",
) {
    private val spec = PwgRasterEncoder.mediaSpecFor(mediaName)
    val mediaName: String = spec.mediaName
    val widthPx: Int = ((spec.widthMm / 25.4) * dpi).toInt()
    val heightPx: Int = ((spec.heightMm / 25.4) * dpi).toInt()
    private val topMarginPx: Int = mm(10.0)
    private val sideMarginPx: Int = mm(14.0)
    private val stripeHeightPx: Int = mm(3.0)
    private val stripeSpacingPx: Int = mm(1.4)
    private val stripeWidthPx: Int = widthPx - (2 * sideMarginPx)
    val colHeightPx: Int = mm(18.0)

    private fun mm(valMm: Double): Int = maxOf(1, ((valMm / 25.4) * dpi).roundToInt())

    fun ladderY0(mode: PurgeMode): Int = when (mode) {
        PurgeMode.K -> computeLadderY(1)
        PurgeMode.CMY -> computeLadderY(3)
        PurgeMode.PB_GY -> computeLadderY(3)
        PurgeMode.CMYK -> computeLadderY(4)
        PurgeMode.CMYK6 -> computeLadderY(6)
    }

    private fun drawFiducial(surface: RasterSurface, x0: Int, y0: Int, argb: Int): Int {
        val sz = maxOf(8, mm(2.8))
        val pad = maxOf(2, (sz * 0.28).roundToInt())
        surface.fillRect(x0, y0, x0 + sz, y0 + sz, argb)
        surface.fillRect(x0 + pad, y0 + pad, x0 + sz - pad, y0 + sz - pad, 0xFFFFFFFF.toInt())
        return sz
    }

    private fun drawDigit(surface: RasterSurface, x0: Int, y0: Int, scale: Int, ch: Char, argb: Int) {
        val glyph = FONT_5X7[ch] ?: return
        for (row in glyph.indices) {
            val bits = glyph[row]
            for (col in 0 until 5) {
                if ((bits ushr (4 - col)) and 1 == 1) {
                    surface.fillRect(
                        x0 + col * scale,
                        y0 + row * scale,
                        x0 + (col + 1) * scale,
                        y0 + (row + 1) * scale,
                        argb,
                    )
                }
            }
        }
    }

    private fun drawText(surface: RasterSurface, x0: Int, y0: Int, text: String, scale: Int, argb: Int) {
        var cx = x0
        val advance = 6 * scale
        for (c in text.uppercase()) {
            if (c != ' ') {
                drawDigit(surface, cx, y0, scale, c, argb)
            }
            cx += advance
        }
    }

    private fun drawRulerLabels(
        surface: RasterSurface,
        x: Int,
        y0: Int,
        colH: Int,
        argb: Int,
        alignRight: Boolean,
    ) {
        val scale = maxOf(2, mm(0.32))
        val pitch = colH / 10.0
        for (i in 0 until 10) {
            val sy = (y0 + (i + 0.5) * pitch).toInt() - (3 * scale)
            val label = (i + 1).toString()
            val textW = label.length * 6 * scale - scale
            val tx = if (alignRight) x - textW else x
            drawText(surface, tx, sy, label, scale, argb)
        }
    }

    private fun drawLadderPattern(
        surface: RasterSurface,
        x0: Int,
        y0: Int,
        colW: Int,
        colH: Int,
        argb: Int,
        diagLabelArgb: Int? = null,
    ) {
        val numSteps = 10
        val pitch = colH / numSteps.toDouble()
        val rungThick = maxOf(2, mm(0.55))
        val diagThick = maxOf(1, mm(0.22))
        val stepLen = maxOf(8, mm(2.2))
        val stepDx = maxOf(6, mm(1.4))
        val ladderW = (numSteps - 1) * stepDx + stepLen
        val blockGap = maxOf(6, mm(2.4))
        val diagPad = if (diagLabelArgb != null) mm(3.6) else 0
        val lblScale = maxOf(1, mm(0.28))
        val lblGap = maxOf(2, mm(0.8))

        var currX = x0 + diagPad
        var blockIdx = 0
        while (currX + ladderW <= x0 + colW) {
            for (i in 0 until numSteps) {
                val sy = (y0 + (i + 0.5) * pitch).toInt()
                val sx = currX + i * stepDx
                if (blockIdx == 0 && diagLabelArgb != null) {
                    val label = (i + 1).toString()
                    val tw = label.length * 6 * lblScale - lblScale
                    val tx = maxOf(x0, sx - tw - lblGap)
                    val ty = sy - (3 * lblScale)
                    drawText(surface, tx, ty, label, lblScale, diagLabelArgb)
                }
                surface.drawLine(sx, sy, sx + stepLen, sy, argb, rungThick)
                if (i < numSteps - 1) {
                    val nextSy = (y0 + (i + 1.5) * pitch).toInt()
                    surface.drawLine(sx + stepLen, sy, currX + (i + 1) * stepDx, nextSy, argb, diagThick)
                }
            }
            currX += ladderW + blockGap
            blockIdx++
        }
    }

    private fun computeLadderY(numStripes: Int): Int {
        val barsH = numStripes * (stripeHeightPx + stripeSpacingPx)
        return topMarginPx + mm(6.5) + barsH + mm(5.5)
    }

    fun buildPurgeSurface(mode: PurgeMode, forceRgb: Boolean = false): RasterSurface {
        val colH = mm(18.0)
        val x0 = sideMarginPx
        val x1 = x0 + stripeWidthPx
        val badgeW = mm(if (mode == PurgeMode.CMYK6 || mode == PurgeMode.PB_GY) 7.5 else 6.0)
        val hdrScale = maxOf(2, mm(0.42))
        val fidSz = maxOf(8, mm(2.8))

        if (mode == PurgeMode.K) {
            val surface = RasterSurface(widthPx, heightPx, isGrayscale = !forceRgb)
            val black = 0xFF000000.toInt()
            var yCursor = topMarginPx
            drawText(surface, x0 + mm(5.0), yCursor, "NOZZLE GUARDIAN K 1-10", hdrScale, black)
            yCursor += mm(6.5)
            drawText(surface, x0, yCursor, "K", hdrScale, black)
            surface.fillRect(x0 + badgeW, yCursor, x1, yCursor + stripeHeightPx, black)

            val ladderY0 = computeLadderY(1)
            val rulerW = mm(7.0)
            drawFiducial(surface, x0, ladderY0, black)
            drawFiducial(surface, x1 - fidSz, ladderY0, black)
            drawFiducial(surface, x0, ladderY0 + colH - fidSz, black)
            drawFiducial(surface, x1 - fidSz, ladderY0 + colH - fidSz, black)
            drawRulerLabels(surface, x0 + mm(5.8), ladderY0, colH, black, alignRight = true)
            drawRulerLabels(surface, x1 - mm(5.8), ladderY0, colH, black, alignRight = false)
            drawLadderPattern(surface, x0 + rulerW, ladderY0, stripeWidthPx - 2 * rulerW, colH, black, diagLabelArgb = black)
            return surface
        }

        val stripeSpecs: List<Triple<String, Int, Boolean>> = when (mode) {
            PurgeMode.CMY -> listOf(
                Triple("C", NozzleChannel.CYAN.argb, false),
                Triple("M", NozzleChannel.MAGENTA.argb, false),
                Triple("Y", NozzleChannel.YELLOW.argb, false),
            )
            PurgeMode.CMYK -> listOf(
                Triple("C", NozzleChannel.CYAN.argb, false),
                Triple("M", NozzleChannel.MAGENTA.argb, false),
                Triple("Y", NozzleChannel.YELLOW.argb, false),
                Triple("K", NozzleChannel.BLACK.argb, false),
            )
            PurgeMode.CMYK6 -> listOf(
                Triple("C", NozzleChannel.CYAN.argb, false),
                Triple("M", NozzleChannel.MAGENTA.argb, false),
                Triple("Y", NozzleChannel.YELLOW.argb, false),
                Triple("BK", NozzleChannel.BLACK.argb, false),
                Triple("PB", NozzleChannel.PHOTO_BLACK.argb, false),
                Triple("GY", NozzleChannel.GRAY.argb, false),
            )
            PurgeMode.PB_GY -> listOf(
                Triple("PB", NozzleChannel.PHOTO_BLACK.argb, false),
                Triple("GY", NozzleChannel.GRAY.argb, false),
                Triple("LG", 0xFFB6B6B6.toInt(), true),
            )
            PurgeMode.K -> emptyList()
        }
        val includeBlack = mode != PurgeMode.CMY
        val surface = RasterSurface(widthPx, heightPx, isGrayscale = false)
        val hdrArgb = if (includeBlack) 0xFF373737.toInt() else NozzleChannel.MAGENTA.argb
        val fidArgb = if (includeBlack) NozzleChannel.BLACK.argb else NozzleChannel.MAGENTA.argb

        var yCursor = topMarginPx
        drawText(surface, x0 + mm(5.0), yCursor, "NOZZLE GUARDIAN ${mode.name} 1-10", hdrScale, hdrArgb)
        yCursor += mm(6.5)

        for ((shortCode, colorArgb, isLight) in stripeSpecs) {
            val lblColor = if (includeBlack && (shortCode == "Y" || isLight)) 0xFF373737.toInt() else colorArgb
            drawText(surface, x0, yCursor, shortCode, hdrScale, lblColor)
            surface.fillRect(x0 + badgeW, yCursor, x1, yCursor + stripeHeightPx, colorArgb)
            yCursor += stripeHeightPx + stripeSpacingPx
        }

        val ladderY0 = computeLadderY(stripeSpecs.size)
        drawFiducial(surface, x0, ladderY0, fidArgb)
        drawFiducial(surface, x1 - fidSz, ladderY0, fidArgb)
        drawFiducial(surface, x0, ladderY0 + colH - fidSz, fidArgb)
        drawFiducial(surface, x1 - fidSz, ladderY0 + colH - fidSz, fidArgb)

        val matrixX0 = x0 + badgeW
        val matrixW = stripeWidthPx - badgeW - fidSz - mm(1.5)
        val gapPx = mm(if (stripeSpecs.size >= 6) 2.8 else 4.0)
        val colW = (matrixW - (stripeSpecs.size - 1) * gapPx) / stripeSpecs.size

        stripeSpecs.forEachIndexed { idx, (shortCode, colorArgb, _) ->
            val colX = matrixX0 + idx * (colW + gapPx)
            val diagArgb = if (shortCode == "Y") {
                if (includeBlack) 0xFF373737.toInt() else 0xFFD26E00.toInt()
            } else {
                colorArgb
            }
            drawLadderPattern(surface, colX, ladderY0, colW, colH, colorArgb, diagLabelArgb = diagArgb)
        }
        return surface
    }

    data class SurgicalSheetResult(
        val surface: RasterSurface,
        val effectiveBursts: Int,
    )

    fun buildSurgicalSheet(
        channel: NozzleChannel,
        step: Int,
        requestedBursts: Int = 5,
        headTechnology: HeadTechnology = HeadTechnology.PIEZO,
        forceRgb: Boolean = false,
    ): SurgicalSheetResult = buildSurgicalBatchSheet(
        targets = listOf(channel to step),
        requestedBursts = requestedBursts,
        headTechnology = headTechnology,
        forceRgb = forceRgb,
    )

    fun buildSurgicalBatchSheet(
        targets: List<Pair<NozzleChannel, Int>>,
        requestedBursts: Int = 5,
        headTechnology: HeadTechnology = HeadTechnology.PIEZO,
        forceRgb: Boolean = false,
    ): SurgicalSheetResult {
        val cleanTargets = targets
            .map { (ch, st) -> ch to st.coerceIn(1, 10) }
            .distinct()
        require(cleanTargets.isNotEmpty()) { "At least one surgical target is required." }

        val rawBursts = requestedBursts.coerceIn(1, 9)
        val isThermal = headTechnology == HeadTechnology.THERMAL
        val effectiveBursts = if (isThermal) minOf(rawBursts, 3) else rawBursts
        val linePitch = maxOf(2, mm(if (isThermal) 0.34 else 0.17))

        val colH = mm(18.0)
        val pitch = colH / 10.0
        val allBlackOnly = cleanTargets.all { it.first == NozzleChannel.BLACK }
        val ladderY0 = computeLadderY(if (allBlackOnly) 1 else 4)

        val x0 = sideMarginPx
        val x1 = x0 + stripeWidthPx
        val surface = RasterSurface(widthPx, heightPx, isGrayscale = (allBlackOnly && !forceRgb))
        val hdrScale = maxOf(2, mm(0.36))
        val headerLabel = if (cleanTargets.size == 1) {
            val (ch, st) = cleanTargets.first()
            "SURGICAL ${ch.name} #$st"
        } else {
            "SURGICAL BATCH ${cleanTargets.size}"
        }
        drawText(
            surface,
            x0,
            maxOf(10, ladderY0 - mm(6.0)),
            headerLabel,
            hdrScale,
            cleanTargets.first().first.argb,
        )

        val half = effectiveBursts / 2
        val thick = maxOf(2, mm(0.25))
        val groupedByStep = cleanTargets.groupBy { it.second }
        for ((stepNum, stepTargets) in groupedByStep) {
            val targetY = (ladderY0 + (stepNum - 0.5) * pitch).toInt()
            val segCount = stepTargets.size
            val segWidth = (x1 - x0) / segCount
            stepTargets.forEachIndexed { idx, (ch, _) ->
                val sx0 = x0 + idx * segWidth
                val sx1 = if (idx == segCount - 1) x1 else sx0 + segWidth - mm(1.5)
                for (b in -half..half) {
                    val ly = targetY + b * linePitch
                    surface.drawLine(sx0, ly, sx1, ly, ch.argb, thick)
                }
            }
        }
        return SurgicalSheetResult(surface = surface, effectiveBursts = effectiveBursts)
    }

    private companion object {
        val FONT_5X7: Map<Char, IntArray> = mapOf(
            '0' to intArrayOf(0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110),
            '1' to intArrayOf(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
            '2' to intArrayOf(0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111),
            '3' to intArrayOf(0b11110, 0b00001, 0b00001, 0b01110, 0b00001, 0b00001, 0b11110),
            '4' to intArrayOf(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010),
            '5' to intArrayOf(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110),
            '6' to intArrayOf(0b01110, 0b10000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110),
            '7' to intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000),
            '8' to intArrayOf(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110),
            '9' to intArrayOf(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00001, 0b01110),
            'A' to intArrayOf(0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001),
            'B' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110),
            'C' to intArrayOf(0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110),
            'D' to intArrayOf(0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110),
            'E' to intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111),
            'F' to intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000),
            'G' to intArrayOf(0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01110),
            'H' to intArrayOf(0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001),
            'I' to intArrayOf(0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
            'K' to intArrayOf(0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001),
            'L' to intArrayOf(0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111),
            'M' to intArrayOf(0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001),
            'N' to intArrayOf(0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001),
            'O' to intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110),
            'P' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000),
            'R' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001),
            'S' to intArrayOf(0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110),
            'T' to intArrayOf(0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100),
            'U' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110),
            'W' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b11011, 0b10001),
            'Y' to intArrayOf(0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100),
            'Z' to intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111),
            '-' to intArrayOf(0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000),
            '#' to intArrayOf(0b01010, 0b11111, 0b01010, 0b01010, 0b11111, 0b01010, 0b00000),
        )
    }
}

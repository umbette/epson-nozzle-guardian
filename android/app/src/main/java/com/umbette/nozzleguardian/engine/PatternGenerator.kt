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
        PurgeMode.CMYK -> computeLadderY(4)
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
    ) {
        val numSteps = 10
        val pitch = colH / numSteps.toDouble()
        val rungThick = maxOf(2, mm(0.55))
        val diagThick = maxOf(1, mm(0.22))
        val stepLen = maxOf(8, mm(2.2))
        val stepDx = maxOf(6, mm(1.4))
        val ladderW = (numSteps - 1) * stepDx + stepLen
        val blockGap = maxOf(8, mm(2.8))

        var currX = x0
        while (currX + ladderW <= x0 + colW) {
            for (i in 0 until numSteps) {
                val sy = (y0 + (i + 0.5) * pitch).toInt()
                val sx = currX + i * stepDx
                surface.drawLine(sx, sy, sx + stepLen, sy, argb, rungThick)
                if (i < numSteps - 1) {
                    val nextSy = (y0 + (i + 1.5) * pitch).toInt()
                    surface.drawLine(sx + stepLen, sy, currX + (i + 1) * stepDx, nextSy, argb, diagThick)
                }
            }
            currX += ladderW + blockGap
        }
    }

    private fun computeLadderY(numStripes: Int): Int {
        val barsH = numStripes * (stripeHeightPx + stripeSpacingPx)
        return topMarginPx + mm(6.5) + barsH + mm(5.5)
    }

    fun buildPurgeSurface(mode: PurgeMode): RasterSurface {
        val colH = mm(18.0)
        val x0 = sideMarginPx
        val x1 = x0 + stripeWidthPx
        val badgeW = mm(8.0)
        val hdrScale = maxOf(2, mm(0.42))
        val fidSz = maxOf(8, mm(2.8))

        if (mode == PurgeMode.K) {
            val surface = RasterSurface(widthPx, heightPx, isGrayscale = true)
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
            drawLadderPattern(surface, x0 + rulerW, ladderY0, stripeWidthPx - 2 * rulerW, colH, black)
            return surface
        }

        val channels = if (mode == PurgeMode.CMYK) {
            listOf(NozzleChannel.CYAN, NozzleChannel.MAGENTA, NozzleChannel.YELLOW, NozzleChannel.BLACK)
        } else {
            listOf(NozzleChannel.CYAN, NozzleChannel.MAGENTA, NozzleChannel.YELLOW)
        }
        val includeBlack = mode == PurgeMode.CMYK
        val surface = RasterSurface(widthPx, heightPx, isGrayscale = false)
        val hdrArgb = if (includeBlack) 0xFF373737.toInt() else NozzleChannel.MAGENTA.argb
        val fidArgb = if (includeBlack) NozzleChannel.BLACK.argb else NozzleChannel.MAGENTA.argb

        var yCursor = topMarginPx
        drawText(surface, x0 + mm(5.0), yCursor, "NOZZLE GUARDIAN ${mode.name} 1-10", hdrScale, hdrArgb)
        yCursor += mm(6.5)

        for (ch in channels) {
            val lblColor = if (includeBlack && ch == NozzleChannel.YELLOW) 0xFF373737.toInt() else ch.argb
            drawText(surface, x0, yCursor, ch.name.take(1), hdrScale, lblColor)
            surface.fillRect(x0 + badgeW, yCursor, x1, yCursor + stripeHeightPx, ch.argb)
            yCursor += stripeHeightPx + stripeSpacingPx
        }

        val ladderY0 = computeLadderY(channels.size)
        val rulerW = mm(7.5)
        drawFiducial(surface, x0, ladderY0, fidArgb)
        drawFiducial(surface, x1 - fidSz, ladderY0, fidArgb)
        drawFiducial(surface, x0, ladderY0 + colH - fidSz, fidArgb)
        drawFiducial(surface, x1 - fidSz, ladderY0 + colH - fidSz, fidArgb)
        drawRulerLabels(surface, x0 + mm(5.8), ladderY0, colH, hdrArgb, alignRight = true)

        val matrixX0 = x0 + rulerW
        val matrixW = stripeWidthPx - rulerW - fidSz - mm(1.5)
        val gapPx = mm(5.0)
        val colW = (matrixW - (channels.size - 1) * gapPx) / channels.size

        channels.forEachIndexed { idx, ch ->
            val colX = matrixX0 + idx * (colW + gapPx)
            drawLadderPattern(surface, colX, ladderY0, colW, colH, ch.argb)
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
    ): SurgicalSheetResult {
        val clampedStep = step.coerceIn(1, 10)
        val rawBursts = requestedBursts.coerceIn(1, 9)
        val isThermal = headTechnology == HeadTechnology.THERMAL
        val effectiveBursts = if (isThermal) minOf(rawBursts, 3) else rawBursts
        val linePitch = maxOf(2, mm(if (isThermal) 0.34 else 0.17))

        val colH = mm(18.0)
        val pitch = colH / 10.0
        val ladderY0 = computeLadderY(if (channel == NozzleChannel.BLACK) 1 else 4)
        val targetY = (ladderY0 + (clampedStep - 0.5) * pitch).toInt()

        val x0 = sideMarginPx
        val x1 = x0 + stripeWidthPx
        val surface = RasterSurface(widthPx, heightPx, isGrayscale = (channel == NozzleChannel.BLACK))
        val hdrScale = maxOf(2, mm(0.36))
        drawText(
            surface,
            x0,
            maxOf(10, targetY - mm(5.5)),
            "SURGICAL ${channel.name} #$clampedStep",
            hdrScale,
            channel.argb,
        )
        val half = effectiveBursts / 2
        val thick = maxOf(2, mm(0.25))
        for (b in -half..half) {
            val ly = targetY + b * linePitch
            surface.drawLine(x0, ly, x1, ly, channel.argb, thick)
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
            'G' to intArrayOf(0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01110),
            'I' to intArrayOf(0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
            'K' to intArrayOf(0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001),
            'L' to intArrayOf(0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111),
            'M' to intArrayOf(0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001),
            'N' to intArrayOf(0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001),
            'O' to intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110),
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

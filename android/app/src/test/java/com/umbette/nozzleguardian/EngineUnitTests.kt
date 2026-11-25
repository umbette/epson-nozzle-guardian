package com.umbette.nozzleguardian

import com.umbette.nozzleguardian.engine.DormancyDecisionType
import com.umbette.nozzleguardian.engine.DormancyEngine
import com.umbette.nozzleguardian.engine.EwsParser
import com.umbette.nozzleguardian.engine.GuardianState
import com.umbette.nozzleguardian.engine.HeadTechnology
import com.umbette.nozzleguardian.engine.IppCodec
import com.umbette.nozzleguardian.engine.NozzleChannel
import com.umbette.nozzleguardian.engine.OpticalNozzleScanner
import com.umbette.nozzleguardian.engine.PatternGenerator
import com.umbette.nozzleguardian.engine.PrinterKind
import com.umbette.nozzleguardian.engine.PrinterStatus
import com.umbette.nozzleguardian.engine.PurgeMode
import com.umbette.nozzleguardian.engine.PwgRasterEncoder
import com.umbette.nozzleguardian.engine.SnmpCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit

class EngineUnitTests {
    @Test
    fun pwgRasterRoundtripA4AndLetter() {
        val genA4 = PatternGenerator(dpi = 150, mediaName = "iso_a4_210x297mm")
        val surfK = genA4.buildPurgeSurface(PurgeMode.K)
        val encodedK = PwgRasterEncoder.encode(surfK, dpi = 150, mediaName = genA4.mediaName)
        val decodedK = PwgRasterEncoder.decode(encodedK)
        assertEquals("PwgRaster", decodedK.mediaClass)
        assertEquals(8, decodedK.bitsPerPixel)
        assertEquals(surfK.width, decodedK.width)
        assertEquals(surfK.height, decodedK.height)

        val genLetter = PatternGenerator(dpi = 150, mediaName = "na_letter_8.5x11in")
        val surfCmyk = genLetter.buildPurgeSurface(PurgeMode.CMYK)
        val encodedCmyk = PwgRasterEncoder.encode(surfCmyk, dpi = 150, mediaName = genLetter.mediaName)
        val decodedCmyk = PwgRasterEncoder.decode(encodedCmyk)
        assertEquals(24, decodedCmyk.bitsPerPixel)
        assertEquals(surfCmyk.width, decodedCmyk.width)
        assertEquals(surfCmyk.height, decodedCmyk.height)
    }

    @Test
    fun ippBinaryEncodeDecodeAndHardwareClassification() {
        val req = IppCodec.buildGetPrinterAttributesRequest("ipp://192.168.1.100:631/ipp/print")
        assertTrue(req.size > 40)

        val resp = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01))
            write(0x04)
            writeAttr(0x41, "printer-make-and-model", "Canon G3010 MegaTank")
            writeAttr(0x23, "printer-state", byteArrayOf(0x00, 0x00, 0x00, 0x03))
            writeAttr(0x44, "printer-state-reasons", "media-empty")
            writeAttr(0x49, "document-format-supported", "image/pwg-raster")
            writeAttr(0x49, "", "application/pdf")
            write(0x03)
        }.toByteArray()

        val parsed = IppCodec.parseResponse(resp)
        assertEquals(0, parsed.statusCode)
        assertEquals("Canon G3010 MegaTank", parsed.firstString("printer-make-and-model"))
        assertEquals(listOf("image/pwg-raster", "application/pdf"), parsed.stringList("document-format-supported"))

        val (kind, headTech) = IppCodec.classifyHardware("Canon G3010 MegaTank", emptyList())
        assertEquals(PrinterKind.INKJET, kind)
        assertEquals(HeadTechnology.THERMAL, headTech)

        val (laserKind, laserTech) = IppCodec.classifyHardware("HP LaserJet M209dw", listOf("toner"))
        assertEquals(PrinterKind.LASER, laserKind)
        assertEquals(HeadTechnology.LASER, laserTech)
    }

    @Test
    fun snmpV1BuildAndParse() {
        val pkt = SnmpCodec.buildSnmpV1Request(SnmpCodec.SNMP_GET_NEXT_REQUEST, SnmpCodec.OID_PRT_MARKER_LIFE_COUNT_PREFIX, requestId = 42)
        assertEquals(0x30, pkt[0].toInt() and 0xFF)
    }

    @Test
    fun ewsParsersSupportHpXmlAndItalianHtml() {
        val hpXml = """
            <pudyn:ProductUsageDyn>
                <pudyn:MonochromeImpressions>120</pudyn:MonochromeImpressions>
                <pudyn:ColorImpressions>45</pudyn:ColorImpressions>
            </pudyn:ProductUsageDyn>
        """.trimIndent()
        val hp = EwsParser.parseWebInfo(hpXml)
        assertTrue(hp.online)
        assertEquals(120, hp.monochromePages)
        assertEquals(45, hp.colorPages)
        assertEquals(165, hp.totalPages)
        assertEquals(HeadTechnology.THERMAL, hp.headTechnology)

        val itHtml = "<html><body>Epson ET-2810<p>Pagine in bianco e nero: 310</p><p>Pagine a colori: 88</p></body></html>"
        val ep = EwsParser.parseWebInfo(itHtml)
        assertTrue(ep.online)
        assertEquals(310, ep.monochromePages)
        assertEquals(88, ep.colorPages)
        assertEquals(HeadTechnology.PIEZO, ep.headTechnology)
    }

    @Test
    fun dormancyEngineSafetyAndDecoupling() {
        val now = Instant.parse("2026-11-25T08:00:00Z")
        val old = now.minus(6, ChronoUnit.DAYS).toString()

        val base = GuardianState(
            printerHost = "192.168.1.50",
            inactivityThresholdDays = 5,
            autoPurgeEnabled = true,
            lastMonoPages = 100,
            lastColorPages = 40,
            lastMonoActivityIso = now.toString(),
            lastColorActivityIso = old,
        )
        val activeKStaleCmy = PrinterStatus(online = true, monochromePages = 102, colorPages = 40, state = "idle")
        val decision = DormancyEngine.evaluate(base, activeKStaleCmy, now)
        assertEquals(DormancyDecisionType.PURGE_REQUIRED, decision.type)
        assertEquals(PurgeMode.CMY, decision.recommendedPurgeMode)

        val laserStatus = activeKStaleCmy.copy(printerKind = PrinterKind.LASER, headTechnology = HeadTechnology.LASER)
        assertEquals(DormancyDecisionType.LASER_ABORTED, DormancyEngine.evaluate(base, laserStatus, now).type)

        val emptyTray = activeKStaleCmy.copy(stateReasons = listOf("media-empty-error"))
        assertEquals(DormancyDecisionType.MEDIA_EMPTY_ABORTED, DormancyEngine.evaluate(base, emptyTray, now).type)
    }

    @Test
    fun surgicalThermalSafetyAndScannerDetectsDamagedCyanStep() {
        val gen = PatternGenerator(dpi = 300)
        val thermal = gen.buildSurgicalSheet(NozzleChannel.MAGENTA, step = 4, requestedBursts = 7, headTechnology = HeadTechnology.THERMAL)
        assertEquals(3, thermal.effectiveBursts)

        val sheet = gen.buildPurgeSurface(PurgeMode.CMYK)
        // Erase cyan ladder step #4 across the cyan column to simulate a single clogged nozzle #4
        val ladderY0 = gen.ladderY0(PurgeMode.CMYK)
        val colH = gen.colHeightPx
        val pitch = colH / 10.0
        val stepTop = (ladderY0 + 3 * pitch).toInt()
        val stepBot = (ladderY0 + 4 * pitch).toInt()
        sheet.fillRect(0, stepTop, (sheet.width * 0.30).toInt(), stepBot, 0xFFFFFFFF.toInt())

        val report = OpticalNozzleScanner.analyze(sheet.pixels, sheet.width, sheet.height)
        assertTrue(report.success)
        val cyan = report.channels["cyan"]
        assertNotNull(cyan)
        assertFalse(cyan!!.healthy ?: true)
        assertTrue(cyan.missingSteps.contains(4))
        assertEquals("cyan", report.targetChannel)
        assertEquals(4, report.targetStep)

        // All 3 diagonal-numbered sheet modes scan cleanly at 100% when healthy
        for (mode in listOf(PurgeMode.K, PurgeMode.CMY, PurgeMode.CMYK)) {
            val healthy = gen.buildPurgeSurface(mode)
            val rep = OpticalNozzleScanner.analyze(healthy.pixels, healthy.width, healthy.height)
            assertTrue("Expected success for $mode", rep.success)
            assertEquals("Expected 100% health for $mode", 100.0, rep.overallHealthPct, 0.01)
        }
    }

    private fun ByteArrayOutputStream.writeAttr(tag: Int, name: String, strVal: String) {
        writeAttr(tag, name, strVal.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArrayOutputStream.writeAttr(tag: Int, name: String, rawVal: ByteArray) {
        val nb = name.toByteArray(Charsets.UTF_8)
        write(tag)
        write((nb.size ushr 8) and 0xFF)
        write(nb.size and 0xFF)
        write(nb)
        write((rawVal.size ushr 8) and 0xFF)
        write(rawVal.size and 0xFF)
        write(rawVal)
    }
}

package com.umbette.nozzleguardian.engine

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class SnmpResponse(
    val requestId: Int,
    val errorStatus: Int,
    val oid: String,
    val value: Any?,
)

data class SnmpPrinterTelemetry(
    val reachable: Boolean = false,
    val totalPages: Int? = null,
    val markers: List<MarkerSupply> = emptyList(),
)

/**
 * Pure-Kotlin RFC 1157 / RFC 3805 ASN.1 BER SNMPv1 codec & UDP client for Printer-MIB telemetry.
 */
object SnmpCodec {
    const val OID_PRT_MARKER_LIFE_COUNT_PREFIX = "1.3.6.1.2.1.43.10.2.1.4"
    const val OID_PRT_MARKER_SUPPLIES_WALK_START = "1.3.6.1.2.1.43.11.1.1.5"
    const val OID_PRT_MARKER_SUPPLIES_START = "1.3.6.1.2.1.43.11.1.1.6"
    const val OID_PRT_MARKER_SUPPLIES_TYPE = "1.3.6.1.2.1.43.11.1.1.5."
    const val OID_PRT_MARKER_SUPPLIES_DESC = "1.3.6.1.2.1.43.11.1.1.6."
    const val OID_PRT_MARKER_SUPPLIES_UNIT = "1.3.6.1.2.1.43.11.1.1.7."
    const val OID_PRT_MARKER_SUPPLIES_MAX = "1.3.6.1.2.1.43.11.1.1.8."
    const val OID_PRT_MARKER_SUPPLIES_LEVEL = "1.3.6.1.2.1.43.11.1.1.9."

    private const val SUPPLY_UNIT_PERCENT = 19

    const val SNMP_GET_REQUEST = 0xA0
    const val SNMP_GET_NEXT_REQUEST = 0xA1

    private fun encodeBerLength(length: Int): ByteArray {
        if (length < 0x80) return byteArrayOf(length.toByte())
        val chunks = mutableListOf<Byte>()
        var n = length
        while (n > 0) {
            chunks.add((n and 0xFF).toByte())
            n = n ushr 8
        }
        chunks.reverse()
        val out = ByteArray(chunks.size + 1)
        out[0] = (0x80 or chunks.size).toByte()
        chunks.forEachIndexed { idx, b -> out[idx + 1] = b }
        return out
    }

    private fun encodeTlv(tag: Int, value: ByteArray): ByteArray {
        val lenBytes = encodeBerLength(value.size)
        val out = ByteArray(1 + lenBytes.size + value.size)
        out[0] = (tag and 0xFF).toByte()
        System.arraycopy(lenBytes, 0, out, 1, lenBytes.size)
        System.arraycopy(value, 0, out, 1 + lenBytes.size, value.size)
        return out
    }

    private fun encodeOid(oidStr: String): ByteArray {
        val parts = oidStr.trim('.').split('.').filter { it.isNotEmpty() }.map { it.toInt() }
        require(parts.size >= 2) { "Invalid OID: $oidStr" }
        val body = ByteArrayOutputStream()
        body.write(parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) {
            var v = parts[i]
            if (v == 0) {
                body.write(0)
            } else {
                val sub = mutableListOf<Int>()
                while (v > 0) {
                    sub.add(v and 0x7F)
                    v = v ushr 7
                }
                sub.reverse()
                for (k in 0 until sub.size - 1) body.write(sub[k] or 0x80)
                body.write(sub.last())
            }
        }
        return encodeTlv(0x06, body.toByteArray())
    }

    private fun encodeInt(value: Int): ByteArray {
        if (value == 0) return encodeTlv(0x02, byteArrayOf(0))
        val bytes = mutableListOf<Byte>()
        var v = value
        while (true) {
            val b = (v and 0xFF).toByte()
            bytes.add(b)
            v = v shr 8
            val signBitSet = (b.toInt() and 0x80) != 0
            if ((v == 0 && !signBitSet) || (v == -1 && signBitSet)) break
        }
        bytes.reverse()
        return encodeTlv(0x02, bytes.toByteArray())
    }

    fun buildSnmpV1Request(
        pduTag: Int,
        oid: String,
        community: String = "public",
        requestId: Int = 1,
    ): ByteArray {
        val varbindBody = ByteArrayOutputStream()
        varbindBody.write(encodeOid(oid))
        varbindBody.write(byteArrayOf(0x05, 0x00))
        val varbind = encodeTlv(0x30, varbindBody.toByteArray())
        val varbindList = encodeTlv(0x30, varbind)

        val pduBody = ByteArrayOutputStream()
        pduBody.write(encodeInt(requestId))
        pduBody.write(encodeInt(0))
        pduBody.write(encodeInt(0))
        pduBody.write(varbindList)
        val pdu = encodeTlv(pduTag, pduBody.toByteArray())

        val msgBody = ByteArrayOutputStream()
        msgBody.write(encodeInt(0))
        msgBody.write(encodeTlv(0x04, community.toByteArray(Charsets.US_ASCII)))
        msgBody.write(pdu)
        return encodeTlv(0x30, msgBody.toByteArray())
    }

    private data class Tlv(val tag: Int, val value: ByteArray, val nextPos: Int)

    private fun readTlv(buf: ByteArray, offset: Int): Tlv? {
        if (offset + 2 > buf.size) return null
        val tag = buf[offset].toInt() and 0xFF
        val lenByte = buf[offset + 1].toInt() and 0xFF
        var pos = offset + 2
        val length = if (lenByte < 0x80) {
            lenByte
        } else {
            val numOctets = lenByte and 0x7F
            if (numOctets == 0 || pos + numOctets > buf.size) return null
            var len = 0
            for (k in 0 until numOctets) {
                len = (len shl 8) or (buf[pos + k].toInt() and 0xFF)
            }
            pos += numOctets
            len
        }
        if (pos + length > buf.size) return null
        return Tlv(tag, buf.copyOfRange(pos, pos + length), pos + length)
    }

    private fun decodeSignedInt(raw: ByteArray): Int {
        if (raw.isEmpty()) return 0
        var acc = if ((raw[0].toInt() and 0x80) != 0) -1 else 0
        for (b in raw) {
            acc = (acc shl 8) or (b.toInt() and 0xFF)
        }
        return acc
    }

    private fun decodeUnsignedLongAsInt(raw: ByteArray): Int {
        var acc = 0L
        for (b in raw) {
            acc = (acc shl 8) or (b.toLong() and 0xFFL)
        }
        return acc.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun decodeOid(raw: ByteArray): String {
        if (raw.isEmpty()) return ""
        val first = raw[0].toInt() and 0xFF
        val parts = mutableListOf(first / 40, first % 40)
        var acc = 0
        for (i in 1 until raw.size) {
            val b = raw[i].toInt() and 0xFF
            acc = (acc shl 7) or (b and 0x7F)
            if ((b and 0x80) == 0) {
                parts.add(acc)
                acc = 0
            }
        }
        return parts.joinToString(".")
    }

    private fun decodeValue(tag: Int, raw: ByteArray): Any? = when (tag) {
        0x02 -> decodeSignedInt(raw)
        0x41, 0x42, 0x43 -> decodeUnsignedLongAsInt(raw)
        0x04 -> String(raw, Charsets.UTF_8).trim('\u0000', ' ')
        0x06 -> decodeOid(raw)
        0x05 -> null
        else -> raw
    }

    fun parseSnmpV1Response(packet: ByteArray): SnmpResponse? {
        val outer = readTlv(packet, 0) ?: return null
        if (outer.tag != 0x30) return null
        val ver = readTlv(outer.value, 0) ?: return null
        val comm = readTlv(outer.value, ver.nextPos) ?: return null
        val pdu = readTlv(outer.value, comm.nextPos) ?: return null
        if (pdu.tag != 0xA2) return null

        val pbuf = pdu.value
        val req = readTlv(pbuf, 0) ?: return null
        val err = readTlv(pbuf, req.nextPos) ?: return null
        val errIdx = readTlv(pbuf, err.nextPos) ?: return null
        val vbl = readTlv(pbuf, errIdx.nextPos) ?: return null
        if (vbl.tag != 0x30) return null
        val vb = readTlv(vbl.value, 0) ?: return null
        if (vb.tag != 0x30) return null
        val oidTlv = readTlv(vb.value, 0) ?: return null
        if (oidTlv.tag != 0x06) return null
        val valTlv = readTlv(vb.value, oidTlv.nextPos) ?: return null

        return SnmpResponse(
            requestId = decodeSignedInt(req.value),
            errorStatus = decodeUnsignedLongAsInt(err.value),
            oid = decodeOid(oidTlv.value),
            value = decodeValue(valTlv.tag, valTlv.value),
        )
    }

    fun inferMarkerColorHex(name: String): String {
        val low = name.lowercase().trim()
        return when {
            "light cyan" in low || low == "lc" -> "#67e8f9"
            "light magenta" in low || low == "lm" -> "#f472b6"
            "tri-color" in low || "tricolor" in low -> "#06b6d4"
            "cyan" in low || "ciano" in low || low == "c" -> "#00b7eb"
            "magenta" in low || low == "m" -> "#ff0090"
            "yellow" in low || "giallo" in low || low == "y" -> "#ffd700"
            "gray" in low || "grey" in low || "grigio" in low || low == "gy" -> "#94a3b8"
            "photo black" in low || "nero foto" in low || low == "pb" -> "#111827"
            "black" in low || "nero" in low || low == "bk" || low == "mb" || low == "k" -> "#1a1a1a"
            else -> ""
        }
    }

    fun mapSupplyType(code: Int?): String = when (code) {
        3 -> "toner"
        4 -> "waste-toner"
        5 -> "ink"
        6 -> "ink-cartridge"
        9 -> "opc"
        10 -> "developer"
        14 -> "fuser"
        15 -> "waste-ink"
        20 -> "transfer-unit"
        21 -> "ink-bottle"
        22 -> "cleaner-unit"
        else -> "ink"
    }

    /**
     * Converts RFC 3805 raw level + maxCapacity + supplyUnit into 0..100%.
     * Returns -2 when the printer does not provide reliable percentage bounds.
     */
    fun calculateReliablePercent(rawLevel: Int, maxCap: Int, supplyUnit: Int? = null): Int {
        if (rawLevel < 0) return rawLevel
        if (maxCap > 0) {
            if (rawLevel > maxCap) return -2
            return ((rawLevel.toLong() * 100L) / maxCap.toLong()).toInt().coerceIn(0, 100)
        }
        if (supplyUnit == SUPPLY_UNIT_PERCENT && rawLevel in 0..100) {
            return rawLevel
        }
        return -2
    }

    fun queryPrinterTelemetry(
        host: String,
        port: Int = 161,
        community: String = "public",
        timeoutMs: Int = 1200,
    ): SnmpPrinterTelemetry {
        if (host.isBlank()) return SnmpPrinterTelemetry()
        return try {
            val address = InetAddress.getByName(host)
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                fun exchange(oid: String, reqId: Int): SnmpResponse? {
                    val pktBytes = buildSnmpV1Request(SNMP_GET_NEXT_REQUEST, oid, community, reqId)
                    val packet = DatagramPacket(pktBytes, pktBytes.size, address, port)
                    repeat(2) {
                        socket.send(packet)
                        val rxBuf = ByteArray(2048)
                        val rxPkt = DatagramPacket(rxBuf, rxBuf.size)
                        try {
                            socket.receive(rxPkt)
                            val parsed = parseSnmpV1Response(rxPkt.data.copyOf(rxPkt.length))
                            if (parsed != null && parsed.requestId == reqId) return parsed
                        } catch (_: Exception) {
                        }
                    }
                    return null
                }

                var reqId = 1
                val life = exchange(OID_PRT_MARKER_LIFE_COUNT_PREFIX, reqId++)
                val totalPages = if (
                    life != null &&
                    life.errorStatus == 0 &&
                    life.oid.startsWith("$OID_PRT_MARKER_LIFE_COUNT_PREFIX.") &&
                    life.value is Int &&
                    life.value >= 0
                ) life.value else null

                val supplyTypes = linkedMapOf<String, Int>()
                val descs = linkedMapOf<String, String>()
                val units = linkedMapOf<String, Int>()
                val maxCaps = linkedMapOf<String, Int>()
                val levels = linkedMapOf<String, Int>()
                var curOid = OID_PRT_MARKER_SUPPLIES_WALK_START
                for (step in 0 until 64) {
                    val nxt = exchange(curOid, reqId++) ?: break
                    if (nxt.errorStatus != 0 || !nxt.oid.startsWith("1.3.6.1.2.1.43.11.1.1.")) break
                    val suffixCol = nxt.oid.removePrefix("1.3.6.1.2.1.43.11.1.1.").substringBefore('.').toIntOrNull()
                    if (suffixCol == null || suffixCol > 9) break
                    when {
                        nxt.oid.startsWith(OID_PRT_MARKER_SUPPLIES_TYPE) && nxt.value is Int ->
                            supplyTypes[nxt.oid.removePrefix(OID_PRT_MARKER_SUPPLIES_TYPE)] = nxt.value
                        nxt.oid.startsWith(OID_PRT_MARKER_SUPPLIES_DESC) && nxt.value is String ->
                            descs[nxt.oid.removePrefix(OID_PRT_MARKER_SUPPLIES_DESC)] = nxt.value
                        nxt.oid.startsWith(OID_PRT_MARKER_SUPPLIES_UNIT) && nxt.value is Int ->
                            units[nxt.oid.removePrefix(OID_PRT_MARKER_SUPPLIES_UNIT)] = nxt.value
                        nxt.oid.startsWith(OID_PRT_MARKER_SUPPLIES_MAX) && nxt.value is Int ->
                            maxCaps[nxt.oid.removePrefix(OID_PRT_MARKER_SUPPLIES_MAX)] = nxt.value
                        nxt.oid.startsWith(OID_PRT_MARKER_SUPPLIES_LEVEL) && nxt.value is Int ->
                            levels[nxt.oid.removePrefix(OID_PRT_MARKER_SUPPLIES_LEVEL)] = nxt.value
                    }
                    if (nxt.oid == curOid) break
                    curOid = nxt.oid
                }

                if (life == null && descs.isEmpty() && levels.isEmpty()) {
                    return SnmpPrinterTelemetry()
                }

                val markers = descs.entries
                    .sortedBy { it.key.split('.').firstOrNull()?.toIntOrNull() ?: 0 }
                    .map { (idx, name) ->
                        val rawLvl = levels[idx] ?: -2
                        val maxCap = maxCaps[idx] ?: -2
                        val unit = units[idx]
                        val pct = calculateReliablePercent(rawLvl, maxCap, unit)
                        MarkerSupply(
                            name = name,
                            level = pct,
                            type = mapSupplyType(supplyTypes[idx]),
                            colorHex = inferMarkerColorHex(name),
                        )
                    }

                SnmpPrinterTelemetry(reachable = true, totalPages = totalPages, markers = markers)
            }
        } catch (_: Exception) {
            SnmpPrinterTelemetry()
        }
    }
}

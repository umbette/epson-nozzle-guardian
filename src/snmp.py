"""
Pure-Python Async SNMPv1 Printer Telemetry Client (RFC 3805 Printer-MIB)
========================================================================
Zero-dependency ASN.1 BER + UDP SNMPv1 reader for total page counters
(``prtMarkerLifeCount``) and ink supply telemetry (``prtMarkerSupplies``) when
IPPS or vendor EWS HTML omits counters (e.g., Epson EcoTank Series).
"""

import asyncio
from dataclasses import dataclass, field
import logging
import socket
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger("nozzle_guardian.snmp")

OID_PRT_MARKER_LIFE_COUNT_PREFIX = "1.3.6.1.2.1.43.10.2.1.4"
OID_PRT_MARKER_SUPPLIES_START = "1.3.6.1.2.1.43.11.1.1.6"
OID_PRT_MARKER_SUPPLIES_DESC = "1.3.6.1.2.1.43.11.1.1.6."
OID_PRT_MARKER_SUPPLIES_MAX = "1.3.6.1.2.1.43.11.1.1.8."
OID_PRT_MARKER_SUPPLIES_LEVEL = "1.3.6.1.2.1.43.11.1.1.9."

SNMP_GET_REQUEST = 0xA0
SNMP_GET_NEXT_REQUEST = 0xA1


@dataclass
class SnmpPrinterTelemetry:
    reachable: bool = False
    total_pages: Optional[int] = None
    markers: List[Dict[str, Any]] = field(default_factory=list)


def _encode_ber_length(length: int) -> bytes:
    if length < 0x80:
        return bytes([length])
    chunks: List[int] = []
    n = length
    while n:
        chunks.append(n & 0xFF)
        n >>= 8
    chunks.reverse()
    return bytes([0x80 | len(chunks)]) + bytes(chunks)


def _encode_tlv(tag: int, value: bytes) -> bytes:
    return bytes([tag]) + _encode_ber_length(len(value)) + value


def _encode_oid(oid_str: str) -> bytes:
    parts = [int(x) for x in oid_str.strip(".").split(".") if x != ""]
    if len(parts) < 2:
        raise ValueError(f"Invalid OID: {oid_str}")
    body = bytearray([parts[0] * 40 + parts[1]])
    for arc in parts[2:]:
        if arc == 0:
            body.append(0)
        else:
            sub: List[int] = []
            v = arc
            while v:
                sub.append(v & 0x7F)
                v >>= 7
            sub.reverse()
            for i in range(len(sub) - 1):
                body.append(sub[i] | 0x80)
            body.append(sub[-1])
    return _encode_tlv(0x06, bytes(body))


def _encode_int(value: int) -> bytes:
    if value == 0:
        return _encode_tlv(0x02, b"\x00")
    bit_len = value.bit_length()
    byte_len = (bit_len + 8) // 8
    raw = value.to_bytes(byte_len, "big", signed=True)
    return _encode_tlv(0x02, raw)


def build_snmpv1_request(pdu_tag: int, oid: str, community: str = "public", request_id: int = 1) -> bytes:
    """Builds an RFC 1157 SNMPv1 GetRequest or GetNextRequest packet."""
    varbind = _encode_tlv(0x30, _encode_oid(oid) + b"\x05\x00")
    varbind_list = _encode_tlv(0x30, varbind)
    pdu = _encode_tlv(pdu_tag, _encode_int(request_id) + _encode_int(0) + _encode_int(0) + varbind_list)
    return _encode_tlv(0x30, _encode_int(0) + _encode_tlv(0x04, community.encode("ascii")) + pdu)


def _read_tlv(buf: bytes, offset: int) -> Optional[Tuple[int, bytes, int]]:
    if offset + 2 > len(buf):
        return None
    tag = buf[offset]
    len_byte = buf[offset + 1]
    pos = offset + 2
    if len_byte < 0x80:
        length = len_byte
    else:
        num_octets = len_byte & 0x7F
        if num_octets == 0 or pos + num_octets > len(buf):
            return None
        length = int.from_bytes(buf[pos:pos + num_octets], "big")
        pos += num_octets
    if pos + length > len(buf):
        return None
    return tag, buf[pos:pos + length], pos + length


def _decode_oid(raw: bytes) -> str:
    if not raw:
        return ""
    parts = [raw[0] // 40, raw[0] % 40]
    acc = 0
    for b in raw[1:]:
        acc = (acc << 7) | (b & 0x7F)
        if not (b & 0x80):
            parts.append(acc)
            acc = 0
    return ".".join(str(p) for p in parts)


def _decode_value(tag: int, raw: bytes) -> Any:
    if tag == 0x02:
        return int.from_bytes(raw, "big", signed=True)
    if tag in (0x41, 0x42, 0x43):
        return int.from_bytes(raw, "big", signed=False)
    if tag == 0x04:
        try:
            return raw.decode("utf-8", errors="replace").strip("\x00 ")
        except Exception:
            return raw.hex()
    if tag == 0x06:
        return _decode_oid(raw)
    if tag == 0x05:
        return None
    return raw


def parse_snmpv1_response(packet: bytes) -> Optional[Tuple[int, int, str, Any]]:
    """Decodes an SNMPv1 GetResponse into (request_id, error_status, oid, value)."""
    outer = _read_tlv(packet, 0)
    if not outer or outer[0] != 0x30:
        return None
    msg = outer[1]
    ver = _read_tlv(msg, 0)
    if not ver:
        return None
    comm = _read_tlv(msg, ver[2])
    if not comm:
        return None
    pdu = _read_tlv(msg, comm[2])
    if not pdu or pdu[0] != 0xA2:
        return None
    pbuf = pdu[1]
    req = _read_tlv(pbuf, 0)
    if not req:
        return None
    err = _read_tlv(pbuf, req[2])
    if not err:
        return None
    err_idx = _read_tlv(pbuf, err[2])
    if not err_idx:
        return None
    vbl = _read_tlv(pbuf, err_idx[2])
    if not vbl or vbl[0] != 0x30:
        return None
    vb = _read_tlv(vbl[1], 0)
    if not vb or vb[0] != 0x30:
        return None
    oid_tlv = _read_tlv(vb[1], 0)
    if not oid_tlv or oid_tlv[0] != 0x06:
        return None
    val_tlv = _read_tlv(vb[1], oid_tlv[2])
    if not val_tlv:
        return None
    return (
        int.from_bytes(req[1], "big", signed=True),
        int.from_bytes(err[1], "big", signed=False),
        _decode_oid(oid_tlv[1]),
        _decode_value(val_tlv[0], val_tlv[1]),
    )


def _infer_marker_color(name: str) -> str:
    low = name.lower()
    if "cyan" in low or "ciano" in low:
        return "#00b7eb"
    if "magenta" in low:
        return "#ff0090"
    if "yellow" in low or "giallo" in low:
        return "#ffd700"
    if "black" in low or "nero" in low or low.strip() == "bk":
        return "#1a1a1a"
    return ""


class _SnmpUdpProtocol(asyncio.DatagramProtocol):
    def __init__(self) -> None:
        self.transport: Optional[asyncio.DatagramTransport] = None
        self.waiters: Dict[int, asyncio.Future] = {}

    def connection_made(self, transport: asyncio.BaseTransport) -> None:
        self.transport = transport  # type: ignore[assignment]

    def datagram_received(self, data: bytes, addr: Tuple[str, int]) -> None:
        parsed = parse_snmpv1_response(data)
        if not parsed:
            return
        req_id = parsed[0]
        fut = self.waiters.pop(req_id, None)
        if fut and not fut.done():
            fut.set_result(parsed)

    def error_received(self, exc: Exception) -> None:
        for fut in list(self.waiters.values()):
            if not fut.done():
                fut.set_exception(exc)
        self.waiters.clear()


async def _snmp_exchange(
    transport: asyncio.DatagramTransport,
    proto: _SnmpUdpProtocol,
    host: str,
    port: int,
    pdu_tag: int,
    oid: str,
    community: str,
    req_id: int,
    timeout_sec: float,
    retries: int = 1,
) -> Optional[Tuple[int, int, str, Any]]:
    loop = asyncio.get_running_loop()
    pkt = build_snmpv1_request(pdu_tag, oid, community=community, request_id=req_id)
    for attempt in range(retries + 1):
        fut: asyncio.Future = loop.create_future()
        proto.waiters[req_id] = fut
        transport.sendto(pkt, (host, port))
        try:
            return await asyncio.wait_for(fut, timeout=timeout_sec)
        except Exception:
            proto.waiters.pop(req_id, None)
            if attempt >= retries:
                return None
    return None


async def query_snmp_printer_telemetry(
    host: str,
    port: int = 161,
    community: str = "public",
    timeout_sec: float = 1.2,
) -> SnmpPrinterTelemetry:
    """Queries RFC 3805 total page count and ink bottle/cartridge supplies over SNMPv1."""
    telemetry = SnmpPrinterTelemetry()
    if not host:
        return telemetry

    loop = asyncio.get_running_loop()
    transport: Optional[asyncio.DatagramTransport] = None
    try:
        family = socket.AF_INET6 if ":" in host else socket.AF_INET
        transport, protocol = await loop.create_datagram_endpoint(
            _SnmpUdpProtocol,
            family=family,
        )
        proto = protocol  # type: ignore[assignment]
        req_id = 1

        res = await _snmp_exchange(
            transport, proto, host, port, SNMP_GET_NEXT_REQUEST, OID_PRT_MARKER_LIFE_COUNT_PREFIX, community, req_id, timeout_sec
        )
        req_id += 1
        if not res:
            return telemetry

        telemetry.reachable = True
        _, err_status, life_oid, val = res
        if err_status == 0 and life_oid.startswith(OID_PRT_MARKER_LIFE_COUNT_PREFIX + ".") and isinstance(val, int) and val >= 0:
            telemetry.total_pages = val

        descs: Dict[str, str] = {}
        max_caps: Dict[str, int] = {}
        levels: Dict[str, int] = {}
        cur_oid = OID_PRT_MARKER_SUPPLIES_START
        for _ in range(48):
            nxt = await _snmp_exchange(
                transport, proto, host, port, SNMP_GET_NEXT_REQUEST, cur_oid, community, req_id, timeout_sec
            )
            req_id += 1
            if not nxt:
                break
            _, n_err, n_oid, n_val = nxt
            if n_err != 0 or not n_oid.startswith("1.3.6.1.2.1.43.11.1.1."):
                break
            suffix_parts = n_oid[len("1.3.6.1.2.1.43.11.1.1."):].split(".", 1)
            if not suffix_parts or not suffix_parts[0].isdigit() or int(suffix_parts[0]) > 9:
                break
            if n_oid.startswith(OID_PRT_MARKER_SUPPLIES_DESC) and isinstance(n_val, str):
                descs[n_oid[len(OID_PRT_MARKER_SUPPLIES_DESC):]] = n_val
            elif n_oid.startswith(OID_PRT_MARKER_SUPPLIES_MAX) and isinstance(n_val, int):
                max_caps[n_oid[len(OID_PRT_MARKER_SUPPLIES_MAX):]] = n_val
            elif n_oid.startswith(OID_PRT_MARKER_SUPPLIES_LEVEL) and isinstance(n_val, int):
                levels[n_oid[len(OID_PRT_MARKER_SUPPLIES_LEVEL):]] = n_val
            cur_oid = n_oid

        for idx, name in sorted(descs.items(), key=lambda kv: [int(x) for x in kv[0].split(".") if x.isdigit()]):
            raw_lvl = levels.get(idx, -2)
            max_cap = max_caps.get(idx, -2)
            if raw_lvl >= 0 and max_cap > 0:
                pct = min(100, round(raw_lvl * 100 / max_cap))
            elif raw_lvl >= 0 and max_cap <= 0:
                pct = min(100, raw_lvl) if raw_lvl <= 100 else -2
            else:
                pct = raw_lvl
            telemetry.markers.append({
                "name": name,
                "level": pct,
                "type": "ink",
                "color": _infer_marker_color(name),
            })
    except Exception as e:
        logger.debug(f"SNMP query failed for {host}:{port}: {e}")
    finally:
        if transport is not None:
            transport.close()

    return telemetry

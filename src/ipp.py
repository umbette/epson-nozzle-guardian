"""
Pure-Python RFC 8011 IPP Client & Printer Discovery Engine
==========================================================
Zero dependency on CUPS/ipptool binaries. Probes local IPP/IPPS endpoints,
ipp-usb (127.0.0.1:60000), mDNS (_ipp._tcp.local / _ipps._tcp.local),
and LAN ports for broad compatibility across Epson, Canon, HP, Brother, and AirPrint devices.
"""

import asyncio
import logging
import socket
import struct
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple
import httpx

logger = logging.getLogger("nozzle_guardian.ipp")

IPP_VERSION = b"\x02\x00"
IPP_OP_PRINT_JOB = 0x0002
IPP_OP_GET_PRINTER_ATTRIBUTES = 0x000B

TAG_OPERATION_ATTRIBUTES = 0x01
TAG_JOB_ATTRIBUTES = 0x02
TAG_END_OF_ATTRIBUTES = 0x03
TAG_PRINTER_ATTRIBUTES = 0x04

TAG_INTEGER = 0x21
TAG_BOOLEAN = 0x22
TAG_ENUM = 0x23
TAG_OCTET_STRING = 0x30
TAG_RESOLUTION = 0x32
TAG_TEXT_WITH_LANG = 0x35
TAG_NAME_WITH_LANG = 0x36
TAG_TEXT_WITHOUT_LANG = 0x41
TAG_NAME_WITHOUT_LANG = 0x42
TAG_KEYWORD = 0x44
TAG_URI = 0x45
TAG_CHARSET = 0x47
TAG_NATURAL_LANGUAGE = 0x48
TAG_MIME_TYPE = 0x49

IPP_PATHS = ["/ipp/print", "/ipp/printer", "/ipp", "/"]

REQUESTED_PRINTER_ATTRS = [
    "printer-name",
    "printer-make-and-model",
    "printer-info",
    "printer-state",
    "printer-state-reasons",
    "document-format-supported",
    "media-supported",
    "media-default",
    "color-supported",
    "pwg-raster-document-resolution-supported",
    "marker-names",
    "marker-levels",
    "marker-types",
    "marker-colors",
    "printer-impressions-completed",
]

PRINTER_STATE_MAP = {
    3: "idle",
    4: "printing",
    5: "stopped",
}


@dataclass
class IppAttributesResult:
    success: bool
    status_code: int = 0
    endpoint_path: str = "/ipp/print"
    scheme: str = "ipp"
    attributes: Dict[str, Any] = field(default_factory=dict)
    error: Optional[str] = None


def _encode_attr(tag: int, name: str, value: bytes) -> bytes:
    name_b = name.encode("utf-8")
    return struct.pack(">BH", tag, len(name_b)) + name_b + struct.pack(">H", len(value)) + value


def build_get_printer_attributes_request(
    printer_uri: str,
    requested_attributes: Optional[List[str]] = None,
    request_id: int = 1,
) -> bytes:
    """Encodes an RFC 8011 Get-Printer-Attributes (0x000B) binary IPP request."""
    buf = bytearray(IPP_VERSION)
    buf.extend(struct.pack(">HI", IPP_OP_GET_PRINTER_ATTRIBUTES, request_id))
    buf.append(TAG_OPERATION_ATTRIBUTES)
    buf.extend(_encode_attr(TAG_CHARSET, "attributes-charset", b"utf-8"))
    buf.extend(_encode_attr(TAG_NATURAL_LANGUAGE, "attributes-natural-language", b"en"))
    buf.extend(_encode_attr(TAG_URI, "printer-uri", printer_uri.encode("utf-8")))
    buf.extend(_encode_attr(TAG_NAME_WITHOUT_LANG, "requesting-user-name", b"nozzle-guardian"))

    attrs = requested_attributes or REQUESTED_PRINTER_ATTRS
    for idx, attr_name in enumerate(attrs):
        key = "requested-attributes" if idx == 0 else ""
        buf.extend(_encode_attr(TAG_KEYWORD, key, attr_name.encode("utf-8")))

    buf.append(TAG_END_OF_ATTRIBUTES)
    return bytes(buf)


def build_print_job_request(
    printer_uri: str,
    doc_format: str = "image/pwg-raster",
    media: str = "iso_a4_210x297mm",
    color_mode: str = "color",
    job_name: str = "Nozzle Guardian Purge",
    request_id: int = 2,
) -> bytes:
    """Encodes an RFC 8011 Print-Job (0x0002) binary IPP header."""
    buf = bytearray(IPP_VERSION)
    buf.extend(struct.pack(">HI", IPP_OP_PRINT_JOB, request_id))
    buf.append(TAG_OPERATION_ATTRIBUTES)
    buf.extend(_encode_attr(TAG_CHARSET, "attributes-charset", b"utf-8"))
    buf.extend(_encode_attr(TAG_NATURAL_LANGUAGE, "attributes-natural-language", b"en"))
    buf.extend(_encode_attr(TAG_URI, "printer-uri", printer_uri.encode("utf-8")))
    buf.extend(_encode_attr(TAG_NAME_WITHOUT_LANG, "requesting-user-name", b"nozzle-guardian"))
    buf.extend(_encode_attr(TAG_NAME_WITHOUT_LANG, "job-name", job_name.encode("utf-8")))
    buf.extend(_encode_attr(TAG_MIME_TYPE, "document-format", doc_format.encode("utf-8")))

    buf.append(TAG_JOB_ATTRIBUTES)
    buf.extend(_encode_attr(TAG_KEYWORD, "media", media.encode("utf-8")))
    buf.extend(_encode_attr(TAG_KEYWORD, "print-color-mode", color_mode.encode("utf-8")))
    buf.extend(_encode_attr(TAG_KEYWORD, "sides", b"one-sided"))

    buf.append(TAG_END_OF_ATTRIBUTES)
    return bytes(buf)


def _decode_ipp_value(tag: int, raw: bytes) -> Any:
    if tag in (TAG_INTEGER, TAG_ENUM) and len(raw) == 4:
        return struct.unpack(">i", raw)[0]
    if tag == TAG_BOOLEAN and len(raw) >= 1:
        return raw[0] != 0
    if tag == TAG_RESOLUTION and len(raw) == 9:
        x_res, y_res, unit = struct.unpack(">IIB", raw)
        return (x_res, y_res, unit)
    if tag in (TAG_TEXT_WITH_LANG, TAG_NAME_WITH_LANG) and len(raw) >= 4:
        lang_len = struct.unpack(">H", raw[:2])[0]
        val_start = 2 + lang_len + 2
        if val_start <= len(raw):
            return raw[val_start:].decode("utf-8", errors="replace")
    return raw.decode("utf-8", errors="replace")


def parse_ipp_response(data: bytes) -> Tuple[int, Dict[str, Any]]:
    """Parses an RFC 8011 binary IPP response into (status_code, attributes_dict)."""
    if len(data) < 8:
        raise ValueError("Truncated IPP response")

    _major, _minor, status_code, _req_id = struct.unpack(">BBHI", data[:8])
    pos = 8
    attrs: Dict[str, Any] = {}
    last_name: Optional[str] = None

    while pos < len(data):
        tag = data[pos]
        pos += 1
        if tag == TAG_END_OF_ATTRIBUTES:
            break
        if tag < 0x10:
            last_name = None
            continue

        if pos + 2 > len(data):
            break
        name_len = struct.unpack(">H", data[pos : pos + 2])[0]
        pos += 2
        if pos + name_len + 2 > len(data):
            break
        name = data[pos : pos + name_len].decode("utf-8", errors="replace")
        pos += name_len

        val_len = struct.unpack(">H", data[pos : pos + 2])[0]
        pos += 2
        if pos + val_len > len(data):
            break
        raw_val = data[pos : pos + val_len]
        pos += val_len

        val = _decode_ipp_value(tag, raw_val)
        if name:
            last_name = name
            attrs[name] = val
        elif last_name:
            existing = attrs.get(last_name)
            if isinstance(existing, list):
                existing.append(val)
            else:
                attrs[last_name] = [existing, val]

    return status_code, attrs


async def query_ipp_attributes(
    host: str,
    port: int = 631,
    use_tls: bool = True,
    timeout_sec: float = 4.0,
    client: Optional[httpx.AsyncClient] = None,
) -> IppAttributesResult:
    """Probes standard IPP paths and returns decoded printer attributes."""
    schemes = [("https", "ipps"), ("http", "ipp")] if use_tls and port != 60000 else [("http", "ipp"), ("https", "ipps")]
    close_client = client is None
    if client is None:
        client = httpx.AsyncClient(verify=False, timeout=timeout_sec)

    last_err = None
    try:
        for http_scheme, ipp_scheme in schemes:
            for path in IPP_PATHS:
                http_url = f"{http_scheme}://{host}:{port}{path}"
                ipp_uri = f"{ipp_scheme}://{host}:{port}{path}"
                req_bytes = build_get_printer_attributes_request(ipp_uri)
                try:
                    resp = await client.post(
                        http_url,
                        content=req_bytes,
                        headers={"Content-Type": "application/ipp"},
                        timeout=timeout_sec,
                    )
                    if resp.status_code == 200:
                        status_code, attrs = parse_ipp_response(resp.content)
                        if status_code < 0x0400 or attrs:
                            return IppAttributesResult(
                                success=True,
                                status_code=status_code,
                                endpoint_path=path,
                                scheme=ipp_scheme,
                                attributes=attrs,
                            )
                except Exception as exc:
                    last_err = str(exc)
        return IppAttributesResult(success=False, error=last_err or "IPP attributes probe failed")
    finally:
        if close_client:
            await client.aclose()


async def send_ipp_print_job(
    host: str,
    port: int,
    payload: bytes,
    doc_format: str = "image/pwg-raster",
    media: str = "iso_a4_210x297mm",
    color_mode: str = "color",
    use_tls: bool = True,
    endpoint_path: str = "/ipp/print",
    timeout_sec: float = 25.0,
    client: Optional[httpx.AsyncClient] = None,
) -> Tuple[bool, str]:
    """Submits a Print-Job over HTTP/HTTPS IPP without external ipptool binary."""
    http_scheme = "https" if (use_tls and port != 60000) else "http"
    ipp_scheme = "ipps" if http_scheme == "https" else "ipp"
    http_url = f"{http_scheme}://{host}:{port}{endpoint_path}"
    ipp_uri = f"{ipp_scheme}://{host}:{port}{endpoint_path}"

    ipp_header = build_print_job_request(
        printer_uri=ipp_uri,
        doc_format=doc_format,
        media=media,
        color_mode=color_mode,
    )
    body = ipp_header + payload

    close_client = client is None
    if client is None:
        client = httpx.AsyncClient(verify=False, timeout=timeout_sec)

    try:
        resp = await client.post(
            http_url,
            content=body,
            headers={"Content-Type": "application/ipp"},
            timeout=timeout_sec,
        )
        if resp.status_code != 200:
            return False, f"HTTP {resp.status_code} from IPP endpoint {http_url}"
        status_code, attrs = parse_ipp_response(resp.content)
        if status_code < 0x0100:
            job_id = attrs.get("job-id", "ok")
            return True, f"Accepted by printer ({doc_format}, job={job_id})"
        status_msg = attrs.get("status-message", f"IPP error 0x{status_code:04x}")
        return False, f"IPP rejected job (0x{status_code:04x}): {status_msg}"
    except Exception as exc:
        return False, f"IPP transmission failed: {exc}"
    finally:
        if close_client:
            await client.aclose()


def _build_mdns_ptr_query(service_name: str) -> bytes:
    """Builds a minimal DNS-SD PTR multicast query for `_ipp._tcp.local` or `_ipps._tcp.local`."""
    header = struct.pack(">HHHHHH", 0, 0, 1, 0, 0, 0)
    qname = b"".join(bytes([len(p)]) + p.encode("ascii") for p in service_name.strip(".").split(".")) + b"\x00"
    question = qname + struct.pack(">HH", 12, 1)
    return header + question


def _parse_dns_name(packet: bytes, offset: int) -> Tuple[str, int]:
    labels: List[str] = []
    jumped = False
    orig_next = offset
    seen = set()
    while offset < len(packet):
        if offset in seen:
            break
        seen.add(offset)
        length = packet[offset]
        if length == 0:
            if not jumped:
                orig_next = offset + 1
            break
        if (length & 0xC0) == 0xC0:
            if offset + 1 >= len(packet):
                break
            ptr = ((length & 0x3F) << 8) | packet[offset + 1]
            if not jumped:
                orig_next = offset + 2
            jumped = True
            offset = ptr
            continue
        offset += 1
        labels.append(packet[offset : offset + length].decode("utf-8", errors="replace"))
        offset += length
    return ".".join(labels), orig_next


def _extract_mdns_ipv4_and_ports(packet: bytes) -> List[Tuple[str, int]]:
    if len(packet) < 12:
        return []
    _tid, _flags, qdcount, ancount, nscount, arcount = struct.unpack(">HHHHHH", packet[:12])
    pos = 12
    for _ in range(qdcount):
        _, pos = _parse_dns_name(packet, pos)
        pos += 4

    ips: List[str] = []
    ports: List[int] = []
    for _ in range(ancount + nscount + arcount):
        if pos >= len(packet):
            break
        _name, pos = _parse_dns_name(packet, pos)
        if pos + 10 > len(packet):
            break
        rtype, _rclass, _ttl, rdlen = struct.unpack(">HHIH", packet[pos : pos + 10])
        pos += 10
        rdata = packet[pos : pos + rdlen]
        pos += rdlen
        if rtype == 1 and len(rdata) == 4:
            ips.append(socket.inet_ntoa(rdata))
        elif rtype == 33 and len(rdata) >= 6:
            _prio, _weight, port = struct.unpack(">HHH", rdata[:6])
            ports.append(port)

    port = ports[0] if ports else 631
    return [(ip, port) for ip in ips]


async def _mdns_discover_hosts(timeout_sec: float = 1.2) -> List[Tuple[str, int]]:
    found: List[Tuple[str, int]] = []
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.setblocking(False)
        try:
            for svc in ("_ipps._tcp.local", "_ipp._tcp.local"):
                sock.sendto(_build_mdns_ptr_query(svc), ("224.0.0.251", 5353))
        except Exception:
            sock.close()
            return []

        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout_sec
        while loop.time() < deadline:
            remaining = max(0.05, deadline - loop.time())
            try:
                data, addr = await asyncio.wait_for(loop.sock_recvfrom(sock, 4096), timeout=remaining)
                pairs = _extract_mdns_ipv4_and_ports(data)
                if pairs:
                    found.extend(pairs)
                elif addr and addr[0]:
                    found.append((addr[0], 631))
            except (asyncio.TimeoutError, OSError):
                break
        sock.close()
    except Exception as exc:
        logger.debug(f"mDNS discovery notice: {exc}")

    unique: List[Tuple[str, int]] = []
    seen = set()
    for host, port in found:
        if (host, port) not in seen:
            seen.add((host, port))
            unique.append((host, port))
    return unique


async def discover_printers(
    candidate_hosts: Optional[List[str]] = None,
    timeout_sec: float = 1.5,
) -> List[Dict[str, Any]]:
    """
    Discovers IPP / IPPS / ipp-usb printers across:
      1. Local USB (`ipp-usb` on 127.0.0.1:60000)
      2. mDNS `_ipps._tcp.local` & `_ipp._tcp.local`
      3. Explicitly provided candidate LAN hosts
    """
    targets: List[Tuple[str, int, bool]] = [("127.0.0.1", 60000, False)]

    mdns_targets = await _mdns_discover_hosts(timeout_sec=min(1.2, timeout_sec))
    for host, port in mdns_targets:
        targets.append((host, port, port in (443, 631)))

    for h in candidate_hosts or []:
        clean = (h or "").strip()
        if clean:
            targets.append((clean, 631, True))

    dedup: List[Tuple[str, int, bool]] = []
    seen = set()
    for host, port, tls in targets:
        if (host, port) not in seen:
            seen.add((host, port))
            dedup.append((host, port, tls))

    discovered: List[Dict[str, Any]] = []
    async with httpx.AsyncClient(verify=False, timeout=timeout_sec) as client:
        async def _probe(host: str, port: int, tls: bool):
            res = await query_ipp_attributes(host, port=port, use_tls=tls, timeout_sec=timeout_sec, client=client)
            if not res.success:
                return
            attrs = res.attributes
            model = str(attrs.get("printer-make-and-model") or attrs.get("printer-name") or f"IPP Printer ({host})")
            marker_types = attrs.get("marker-types") or []
            if isinstance(marker_types, str):
                marker_types = [marker_types]
            is_laser = any("toner" in str(mt).lower() for mt in marker_types) or "laser" in model.lower()
            head_tech = "laser" if is_laser else (
                "thermal" if any(k in model.lower() for k in ("canon", "megatank", "pixma", "maxify", "hp ", "smart tank", "deskjet", "officejet", "envy"))
                else "piezo"
            )
            formats = attrs.get("document-format-supported") or []
            if isinstance(formats, str):
                formats = [formats]
            discovered.append({
                "host": host,
                "port": port,
                "use_tls": res.scheme == "ipps",
                "endpoint_path": res.endpoint_path,
                "make_and_model": model,
                "printer_kind": "laser" if is_laser else "inkjet",
                "head_technology": head_tech,
                "media_default": str(attrs.get("media-default") or "iso_a4_210x297mm"),
                "supported_formats": formats,
                "source": "ipp-usb" if (host == "127.0.0.1" and port == 60000) else "lan-ipp",
            })

        await asyncio.gather(*(_probe(h, p, t) for h, p, t in dedup), return_exceptions=True)

    # Also include USB-only / host-based OS print queues (e.g., Canon G2770 without Wi-Fi)
    try:
        from .local_spooler import discover_local_printers
        local_list = await discover_local_printers()
        discovered.extend(local_list)
    except Exception:
        pass

    return discovered

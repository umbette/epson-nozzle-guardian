"""
Minimalist MicroPython IPP (Internet Printing Protocol / RFC 8011) Client
Transmits driverless PWG-raster print jobs directly over raw TCP sockets to port 631
with zero external dependencies.
"""

import usocket as socket
import ubinascii
import ussl

TAG_OPERATION_ATTRIBUTES = 0x01
TAG_JOB_ATTRIBUTES = 0x02
TAG_END_OF_ATTRIBUTES = 0x03

TAG_CHARSET = 0x47
TAG_NATURAL_LANGUAGE = 0x48
TAG_URI = 0x45
TAG_NAME_WITHOUT_LANG = 0x42
TAG_MIME_TYPE = 0x49
TAG_KEYWORD = 0x44

IPP_OP_PRINT_JOB = 0x0002
IPP_STATUS_OK = 0x0000


def _encode_attr(tag: int, name: str, value: str) -> bytes:
    name_bytes = name.encode("utf-8")
    val_bytes = value.encode("utf-8")
    return bytes([tag]) + len(name_bytes).to_bytes(2, "big") + name_bytes + len(val_bytes).to_bytes(2, "big") + val_bytes


def build_print_job_request(printer_host: str, printer_port: int, doc_format: str = "image/pwg-raster") -> bytes:
    """Builds the RFC 8011 binary header for an IPP Print-Job operation."""
    buf = bytearray()
    # IPP version 2.0
    buf.extend(b"\x02\x00")
    # Operation ID: Print-Job (0x0002)
    buf.extend(IPP_OP_PRINT_JOB.to_bytes(2, "big"))
    # Request ID: 1
    buf.extend((1).to_bytes(4, "big"))

    # Operation attributes tag
    buf.append(TAG_OPERATION_ATTRIBUTES)
    buf.extend(_encode_attr(TAG_CHARSET, "attributes-charset", "utf-8"))
    buf.extend(_encode_attr(TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en-us"))
    buf.extend(_encode_attr(TAG_URI, "printer-uri", f"ipp://{printer_host}:{printer_port}/ipp/print"))
    buf.extend(_encode_attr(TAG_NAME_WITHOUT_LANG, "requesting-user-name", "nozzle-guardian-dongle"))
    buf.extend(_encode_attr(TAG_NAME_WITHOUT_LANG, "job-name", "Nozzle Guardian Autonomous Purge"))
    buf.extend(_encode_attr(TAG_MIME_TYPE, "document-format", doc_format))

    # Job attributes tag
    buf.append(TAG_JOB_ATTRIBUTES)
    buf.extend(_encode_attr(TAG_KEYWORD, "media", "iso_a4_210x297mm"))
    buf.extend(_encode_attr(TAG_KEYWORD, "print-color-mode", "color"))
    buf.extend(_encode_attr(TAG_KEYWORD, "sides", "one-sided"))

    # End of attributes tag
    buf.append(TAG_END_OF_ATTRIBUTES)
    return bytes(buf)


def send_purge_job(printer_host: str, printer_port: int, pwg_payload: bytes, use_tls: bool = False) -> bool:
    """Sends an IPP Print-Job HTTP POST request containing PWG-raster data."""
    ipp_header = build_print_job_request(printer_host, printer_port)
    total_body = ipp_header + pwg_payload

    http_headers = (
        f"POST /ipp/print HTTP/1.1\r\n"
        f"Host: {printer_host}:{printer_port}\r\n"
        f"User-Agent: NozzleGuardianDongle/1.1\r\n"
        f"Content-Type: application/ipp\r\n"
        f"Content-Length: {len(total_body)}\r\n"
        f"Connection: close\r\n\r\n"
    ).encode("utf-8")

    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(10)
    try:
        s.connect((printer_host, printer_port))
        if use_tls:
            s = ussl.wrap_socket(s)
        s.write(http_headers + total_body)

        resp = s.read(512)
        if b"200 OK" in resp or b"HTTP/1.1 200" in resp:
            return True
        return False
    except Exception as e:
        print("IPP dispatch error:", e)
        return False
    finally:
        s.close()

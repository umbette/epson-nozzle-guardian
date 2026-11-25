import asyncio
import io
import struct
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch

from PIL import Image, ImageDraw
import pytest
from starlette.testclient import TestClient

from src.config import Settings
from src.daemon import NozzleGuardianDaemon
from src.generator import PatternGenerator
from src.ipp import (
    IPP_VERSION,
    TAG_END_OF_ATTRIBUTES,
    TAG_INTEGER,
    TAG_MIME_TYPE,
    TAG_OPERATION_ATTRIBUTES,
    TAG_PRINTER_ATTRIBUTES,
    TAG_TEXT_WITHOUT_LANG,
    _encode_attr,
    build_get_printer_attributes_request,
    build_print_job_request,
    parse_ipp_response,
    send_ipp_print_job,
)
from src.main import app, settings
from src.printer import PrinterStatus, PurgeJobResult, PurgeJobStatus
from src.pwg import (
    CUPS_CSPACE_SGRAY,
    CUPS_CSPACE_SRGB,
    PWG_MAGIC,
    decode_pwg_raster,
    encode_image_for_format,
    encode_pwg_raster,
)
from src.scanner import analyze_nozzle_sheet
from src.snmp import (
    OID_PRT_MARKER_LIFE_COUNT_PREFIX,
    OID_PRT_MARKER_SUPPLIES_DESC,
    OID_PRT_MARKER_SUPPLIES_LEVEL,
    OID_PRT_MARKER_SUPPLIES_MAX,
    _encode_oid,
    _encode_tlv,
    query_snmp_printer_telemetry,
)


def test_pwg_raster_roundtrip_a4_and_letter():
    # Grayscale A4 strip
    gray_img = Image.new("L", (120, 40), 255)
    ImageDraw.Draw(gray_img).rectangle([10, 5, 110, 12], fill=0)
    stream = encode_pwg_raster(gray_img, dpi=300, media_name="iso_a4_210x297mm")
    assert stream.startswith(PWG_MAGIC)
    meta, raw = decode_pwg_raster(stream)
    assert meta["width"] == 120
    assert meta["height"] == 40
    assert meta["dpi"] == (300, 300)
    assert meta["colorspace"] == CUPS_CSPACE_SGRAY
    assert raw == gray_img.tobytes()

    # RGB US-Letter strip
    rgb_img = Image.new("RGB", (100, 30), (255, 255, 255))
    ImageDraw.Draw(rgb_img).rectangle([5, 4, 95, 10], fill=(0, 255, 255))
    stream_rgb = encode_pwg_raster(rgb_img, dpi=360, media_name="na_letter_8.5x11in")
    meta_rgb, raw_rgb = decode_pwg_raster(stream_rgb)
    assert meta_rgb["media_class"] == "PwgRaster"
    assert meta_rgb["width"] == 100
    assert meta_rgb["dpi"] == (360, 360)
    assert meta_rgb["colorspace"] == CUPS_CSPACE_SRGB
    assert raw_rgb == rgb_img.tobytes()


def test_multi_format_raster_encoders():
    img = Image.new("RGB", (64, 64), (255, 255, 255))
    pdf_bytes = encode_image_for_format(img, "application/pdf", dpi=300)
    assert pdf_bytes.startswith(b"%PDF")

    png_bytes = encode_image_for_format(img, "image/png", dpi=300)
    assert png_bytes.startswith(b"\x89PNG")

    jpg_bytes = encode_image_for_format(img, "image/jpeg", dpi=300)
    assert jpg_bytes.startswith(b"\xff\xd8")


def test_ipp_binary_wire_builders_and_parser():
    req = build_get_printer_attributes_request("ipp://printer.local:631/ipp/print")
    assert req.startswith(IPP_VERSION + struct.pack(">HI", 0x000B, 1))
    assert b"nozzle-guardian" in req
    assert b"document-format-supported" in req

    pj = build_print_job_request(
        "ipp://printer.local:631/ipp/print",
        doc_format="image/pwg-raster",
        media="na_letter_8.5x11in",
        color_mode="monochrome",
    )
    assert pj.startswith(IPP_VERSION + struct.pack(">HI", 0x0002, 2))
    assert b"na_letter_8.5x11in" in pj

    # Build a synthetic RFC 8011 response with multi-value document-format-supported
    resp_buf = bytearray(IPP_VERSION + struct.pack(">HI", 0x0000, 1))
    resp_buf.append(TAG_OPERATION_ATTRIBUTES)
    resp_buf.extend(_encode_attr(TAG_TEXT_WITHOUT_LANG, "status-message", b"successful-ok"))
    resp_buf.append(TAG_PRINTER_ATTRIBUTES)
    resp_buf.extend(_encode_attr(TAG_TEXT_WITHOUT_LANG, "printer-make-and-model", b"Canon G3010"))
    resp_buf.extend(_encode_attr(TAG_MIME_TYPE, "document-format-supported", b"application/pdf"))
    resp_buf.extend(_encode_attr(TAG_MIME_TYPE, "", b"image/pwg-raster"))
    resp_buf.extend(_encode_attr(TAG_INTEGER, "job-id", struct.pack(">i", 42)))
    resp_buf.append(TAG_END_OF_ATTRIBUTES)

    status_code, attrs = parse_ipp_response(bytes(resp_buf))
    assert status_code == 0x0000
    assert attrs["printer-make-and-model"] == "Canon G3010"
    assert attrs["document-format-supported"] == ["application/pdf", "image/pwg-raster"]
    assert attrs["job-id"] == 42


@pytest.mark.asyncio
async def test_send_ipp_print_job_httpx():
    resp_buf = bytearray(IPP_VERSION + struct.pack(">HI", 0x0000, 2))
    resp_buf.append(TAG_OPERATION_ATTRIBUTES)
    resp_buf.extend(_encode_attr(TAG_INTEGER, "job-id", struct.pack(">i", 99)))
    resp_buf.append(TAG_END_OF_ATTRIBUTES)

    mock_http_resp = MagicMock()
    mock_http_resp.status_code = 200
    mock_http_resp.content = bytes(resp_buf)

    mock_client = MagicMock()
    mock_client.post = AsyncMock(return_value=mock_http_resp)

    ok, msg = await send_ipp_print_job(
        host="192.168.1.50",
        port=631,
        payload=b"%PDF-1.4 dummy",
        doc_format="application/pdf",
        client=mock_client,
    )
    assert ok is True
    assert "job=99" in msg


def _build_snmpv1_response(req_id: int, oid: str, val_tlv: bytes) -> bytes:
    varbind = _encode_tlv(0x30, _encode_oid(oid) + val_tlv)
    varbind_list = _encode_tlv(0x30, varbind)
    pdu_body = (
        _encode_tlv(0x02, req_id.to_bytes(4, "big", signed=True))
        + _encode_tlv(0x02, b"\x00")
        + _encode_tlv(0x02, b"\x00")
        + varbind_list
    )
    pdu = _encode_tlv(0xA2, pdu_body)
    msg = _encode_tlv(0x02, b"\x00") + _encode_tlv(0x04, b"public") + pdu
    return _encode_tlv(0x30, msg)


def _encode_snmp_int(value: int) -> bytes:
    raw = value.to_bytes(max(1, (value.bit_length() + 8) // 8), "big", signed=True)
    return _encode_tlv(0x02, raw)


@pytest.mark.asyncio
async def test_snmpv1_telemetry_udp_probe():
    class _MockSnmpResponder(asyncio.DatagramProtocol):
        def connection_made(self, transport):
            self.transport = transport

        def datagram_received(self, data, addr):
            # Walk: req_id 1 -> life count, 2 -> desc, 3 -> max, 4 -> level, >=5 -> end-of-mib
            from src.snmp import _decode_oid, _read_tlv
            outer = _read_tlv(data, 0)
            msg = outer[1]
            ver = _read_tlv(msg, 0)
            comm = _read_tlv(msg, ver[2])
            pdu = _read_tlv(msg, comm[2])
            req = _read_tlv(pdu[1], 0)
            req_id = int.from_bytes(req[1], "big", signed=True)
            err = _read_tlv(pdu[1], req[2])
            err_i = _read_tlv(pdu[1], err[2])
            vbl = _read_tlv(pdu[1], err_i[2])
            vb = _read_tlv(vbl[1], 0)
            oid_tlv = _read_tlv(vb[1], 0)
            asked_oid = _decode_oid(oid_tlv[1])

            if asked_oid == OID_PRT_MARKER_LIFE_COUNT_PREFIX:
                pkt = _build_snmpv1_response(req_id, f"{OID_PRT_MARKER_LIFE_COUNT_PREFIX}.1.1", _encode_snmp_int(742))
            elif asked_oid == OID_PRT_MARKER_SUPPLIES_DESC.rstrip("."):
                pkt = _build_snmpv1_response(req_id, f"{OID_PRT_MARKER_SUPPLIES_DESC}1.1", _encode_tlv(0x04, b"Cyan Ink"))
            elif asked_oid == f"{OID_PRT_MARKER_SUPPLIES_DESC}1.1":
                pkt = _build_snmpv1_response(req_id, f"{OID_PRT_MARKER_SUPPLIES_MAX}1.1", _encode_snmp_int(100))
            elif asked_oid == f"{OID_PRT_MARKER_SUPPLIES_MAX}1.1":
                pkt = _build_snmpv1_response(req_id, f"{OID_PRT_MARKER_SUPPLIES_LEVEL}1.1", _encode_snmp_int(82))
            else:
                pkt = _build_snmpv1_response(req_id, "1.3.6.1.2.1.43.99.1", _encode_tlv(0x05, b""))
            self.transport.sendto(pkt, addr)

    loop = asyncio.get_running_loop()
    transport, _ = await loop.create_datagram_endpoint(_MockSnmpResponder, local_addr=("127.0.0.1", 0))
    port = transport.get_extra_info("sockname")[1]
    try:
        tel = await query_snmp_printer_telemetry("127.0.0.1", port=port, timeout_sec=0.8)
    finally:
        transport.close()

    assert tel.reachable is True
    assert tel.total_pages == 742
    assert len(tel.markers) == 1
    assert tel.markers[0]["name"] == "Cyan Ink"
    assert tel.markers[0]["level"] == 82
    assert tel.markers[0]["color"] == "#00b7eb"


def test_api_guards_laser_and_media_empty():
    client = TestClient(app)
    headers = {"x-api-key": settings.api_key} if settings.api_key else {}

    laser_status = PrinterStatus(
        online=True,
        total_pages=500,
        state="idle",
        make_and_model="Brother HL-L2350DW",
        printer_kind="laser",
        head_technology="laser",
    )
    with patch("src.main.printer_client.get_status", AsyncMock(return_value=laser_status)):
        resp_purge = client.post("/purge/cmyk", headers=headers)
        assert resp_purge.status_code == 422
        assert "laser" in resp_purge.json()["detail"].lower()

        resp_surg = client.post(
            "/purge/surgical",
            json={"channel": "cyan", "step": 3, "bursts": 4},
            headers=headers,
        )
        assert resp_surg.status_code == 422

    empty_tray_status = PrinterStatus(
        online=True,
        monochrome_pages=10,
        color_pages=10,
        state="stopped",
        state_reasons=["media-empty-error"],
        make_and_model="Epson ET-2810",
        printer_kind="inkjet",
        head_technology="piezo",
    )
    with patch("src.main.printer_client.get_status", AsyncMock(return_value=empty_tray_status)):
        resp_empty = client.post("/purge/cmy", headers=headers)
        assert resp_empty.status_code == 422
        assert "media-empty" in resp_empty.json()["detail"].lower()


@pytest.mark.asyncio
async def test_daemon_laser_media_empty_and_total_page_fallback(tmp_path):
    daemon = NozzleGuardianDaemon(state_dir=str(tmp_path), printer_host="192.168.1.50")
    state = daemon.load_state()
    state["auto_purge_enabled"] = True
    state["last_total_pages"] = 100
    state["last_total_activity"] = (datetime.now(timezone.utc) - timedelta(days=10)).isoformat()
    daemon.save_state(state)

    # 1. Laser printer never gets ink purges
    laser = PrinterStatus(online=True, total_pages=100, state="idle", printer_kind="laser", head_technology="laser")
    with patch.object(daemon.printer_client, "get_status", AsyncMock(return_value=laser)), \
         patch.object(daemon, "execute_purge", AsyncMock()) as mock_exec:
        await daemon.evaluate_channels()
        assert not mock_exec.called

    # 2. Empty paper tray pauses purges
    empty = PrinterStatus(online=True, total_pages=100, state="idle", state_reasons=["media-empty-warning"], printer_kind="inkjet")
    with patch.object(daemon.printer_client, "get_status", AsyncMock(return_value=empty)), \
         patch.object(daemon, "execute_purge", AsyncMock()) as mock_exec:
        await daemon.evaluate_channels()
        assert not mock_exec.called

    # 3. Total-page fallback triggers CMYK purge when mono/color split counters are absent
    total_only = PrinterStatus(
        online=True,
        monochrome_pages=None,
        color_pages=None,
        total_pages=100,
        state="idle",
        printer_kind="inkjet",
        head_technology="thermal",
    )
    with patch.object(daemon.printer_client, "get_status", AsyncMock(return_value=total_only)), \
         patch.object(daemon, "execute_purge", AsyncMock(return_value=PurgeJobResult(PurgeJobStatus.ACCEPTED, "ok"))) as mock_exec:
        await daemon.evaluate_channels()
        mock_exec.assert_awaited_once()
        assert mock_exec.call_args[0][0] == "cmyk"


def test_scanner_exif_rotation_and_damaged_rung_surgical_target(tmp_path):
    gen = PatternGenerator(Settings(), assets_dir=tmp_path)
    sheet = gen.build_purge_image("cmyk")

    # White-out rung #4 in Magenta column (2nd of 4 columns) to simulate a single clogged nozzle
    draw = ImageDraw.Draw(sheet)
    col_h = gen._mm(18.0)
    ladder_y0 = gen._compute_ladder_y(4)
    ruler_w = gen._mm(7.5)
    fid_sz = max(8, gen._mm(2.8))
    matrix_x0 = gen.side_margin_px + ruler_w
    matrix_w = gen.stripe_width_px - ruler_w - fid_sz - gen._mm(1.5)
    gap_px = gen._mm(5.0)
    col_w = int((matrix_w - 3 * gap_px) / 4)
    magenta_x0 = matrix_x0 + 1 * (col_w + gap_px)
    pitch = col_h / 10.0
    step4_y0 = int(ladder_y0 + 3 * pitch)
    step4_y1 = int(ladder_y0 + 4 * pitch)
    draw.rectangle([magenta_x0 - 2, step4_y0 + 2, magenta_x0 + col_w + 2, step4_y1 - 2], fill=(255, 255, 255))

    # Rotate 90 CCW and attach EXIF Orientation=6 (rotate 90 CW) so scanner must auto-transpose via EXIF
    rotated = sheet.rotate(90, expand=True)
    exif = rotated.getexif()
    exif[0x0112] = 6  # Rotate 90 CW to restore upright orientation
    buf = io.BytesIO()
    rotated.save(buf, format="JPEG", quality=96, exif=exif)

    report = analyze_nozzle_sheet(buf.getvalue())
    assert report["success"] is True
    assert report["sheet_mode"] == "cmyk"
    assert 4 in report["channels"]["magenta"]["missing_steps"]
    assert report["recommended_action"] == "surgical_purge"
    assert report["target_channel"] == "magenta"
    assert report["target_step"] == 4

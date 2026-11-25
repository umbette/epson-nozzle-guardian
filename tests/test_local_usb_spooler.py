from unittest.mock import AsyncMock, patch

import pytest

from src.ipp import discover_printers
from src.local_spooler import (
    _classify_local_model,
    _parse_lpstat_output,
    _parse_windows_printers_json,
    extract_queue_name,
    is_local_queue_target,
    send_local_print_job,
)
from src.printer import PurgeJobStatus, UniversalPrinterClient


def test_parse_cups_lpstat_usb_canon_g2770():
    lpstat_p = "printer Canon_G2770_series is idle.  enabled since Mon 25 Nov 2026\n"
    lpstat_v = "device for Canon_G2770_series: usb://Canon/G2770%20series?serial=12345\n"

    printers = _parse_lpstat_output(lpstat_p, lpstat_v)
    assert len(printers) == 1
    p = printers[0]
    assert p["host"] == "local://Canon_G2770_series"
    assert p["source"] == "usb-spooler"
    assert p["printer_kind"] == "inkjet"
    assert p["head_technology"] == "thermal"
    assert "image/pwg-raster" not in p["supported_formats"]
    assert "application/pdf" in p["supported_formats"]


def test_parse_windows_spooler_json_canon_g2770():
    win_json = """
    [
      {"Name": "Microsoft Print to PDF", "DriverName": "Microsoft Print To PDF", "PortName": "PORTPROMPT:", "PrinterStatus": "Normal"},
      {"Name": "Canon G2770 series", "DriverName": "Canon Inkjet G2770 series", "PortName": "USB001", "PrinterStatus": "Normal"}
    ]
    """
    printers = _parse_windows_printers_json(win_json)
    assert len(printers) == 1
    assert printers[0]["host"] == "local://Canon G2770 series"
    assert printers[0]["head_technology"] == "thermal"
    assert printers[0]["supported_formats"] == ["image/png"]


def test_local_queue_validation_rejects_shell_metacharacters():
    assert is_local_queue_target("local://Canon_G2770") is True
    assert extract_queue_name("local://Canon G2770") == "Canon G2770"
    with pytest.raises(ValueError):
        extract_queue_name("local://bad;rm -rf /")
    assert _classify_local_model("Canon PIXMA G2770") == ("inkjet", "thermal")


@pytest.mark.asyncio
async def test_universal_client_local_usb_status_and_purge():
    client = UniversalPrinterClient(host="local://Canon_G2770_series", port=0, use_tls=False)
    mock_status = {
        "online": True,
        "state": "idle",
        "total_pages": 12,
        "make_and_model": "Canon G2770 series",
        "printer_kind": "inkjet",
        "head_technology": "thermal",
        "media_default": "iso_a4_210x297mm",
        "supported_formats": ["application/pdf", "image/png"],
    }
    with patch("src.printer.query_local_printer_status", AsyncMock(return_value=mock_status)):
        st = await client.get_status()

    assert st.online is True
    assert st.head_technology == "thermal"
    assert st.total_pages == 12
    assert client.negotiate_format(st.supported_formats) == "application/pdf"

    with patch("src.local_spooler.platform.system", return_value="Linux"), \
         patch("src.local_spooler._run_cmd", AsyncMock(return_value=(0, "request id is Canon_G2770_series-7", ""))) as mock_cmd:
        ok, msg = await send_local_print_job("local://Canon_G2770_series", b"%PDF-1.4", doc_format="application/pdf", color_mode="color")
        assert ok is True
        assert "Canon_G2770_series" in msg
        assert mock_cmd.call_args.args[0][0] == "lp"

    with patch("src.printer.send_local_print_job", AsyncMock(return_value=(True, "Dispatched PDF"))):
        res = await client.execute_purge_payload(b"%PDF-1.4", "application/pdf", mode="cmyk")
        assert res.status == PurgeJobStatus.ACCEPTED


@pytest.mark.asyncio
async def test_discover_printers_merges_local_usb_queues():
    fake_usb = [{"host": "local://Canon_G2770_series", "port": 0, "make_and_model": "Canon G2770 series", "head_technology": "thermal", "source": "usb-spooler"}]
    with patch("src.ipp._mdns_discover_hosts", AsyncMock(return_value=[])), \
         patch("src.ipp.query_ipp_attributes", AsyncMock()) as mock_ipp, \
         patch("src.local_spooler.discover_local_printers", AsyncMock(return_value=fake_usb)):
        mock_ipp.return_value.success = False
        found = await discover_printers()

    assert any(p["host"] == "local://Canon_G2770_series" for p in found)

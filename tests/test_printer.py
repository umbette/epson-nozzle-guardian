import pytest
from src.printer import EpsonPrinterClient

def test_parse_web_info_valid():
    client = EpsonPrinterClient()
    html = """
    <html><body>
    <table>
        <tr><td>Monochrome Pages : 450</td></tr>
        <tr><td>Color Pages : 120</td></tr>
    </table>
    </body></html>
    """
    status = client._parse_web_info(html)
    assert status.online is True
    assert status.monochrome_pages == 450
    assert status.color_pages == 120
    assert status.total_pages == 570
    assert status.state == "idle"

def test_parse_web_info_login_or_invalid_rejects():
    client = EpsonPrinterClient()
    # Login page or router 200 OK error page with no counters
    html = "<html><head><title>Router Login</title></head><body>Please login to access device</body></html>"
    status = client._parse_web_info(html)
    assert status.online is False
    assert status.state == "unknown"
    assert status.monochrome_pages is None
    assert status.color_pages is None

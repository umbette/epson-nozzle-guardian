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
    assert status.has_channel_telemetry is False

def test_parse_web_info_only_total():
    client = EpsonPrinterClient()
    html = "<html><body><div>Total Pages Printed : 350</div></body></html>"
    status = client._parse_web_info(html)
    assert status.online is True
    assert status.total_pages == 350
    assert status.monochrome_pages is None
    assert status.color_pages is None
    assert status.has_channel_telemetry is False

def test_parse_web_info_only_monochrome():
    client = EpsonPrinterClient()
    html = "<html><body><div>Pagine in bianco e nero: 210</div></body></html>"
    status = client._parse_web_info(html)
    assert status.online is True
    assert status.monochrome_pages == 210
    assert status.color_pages is None
    assert status.has_channel_telemetry is False

def test_parse_web_info_only_color():
    client = EpsonPrinterClient()
    html = "<html><body><div>Pagine a colori: 88</div></body></html>"
    status = client._parse_web_info(html)
    assert status.online is True
    assert status.monochrome_pages is None
    assert status.color_pages == 88
    assert status.has_channel_telemetry is False


def test_parse_hp_xml_telemetry():
    client = EpsonPrinterClient()
    hp_xml = """<?xml version="1.0" encoding="UTF-8"?>
    <pudyn:ProductUsageDyn xmlns:pudyn="http://www.hp.com/schemas/imaging/con/pudyn/2007/04/18">
      <pudyn:PrinterSubunit>
        <pudyn:TotalImpressions>520</pudyn:TotalImpressions>
        <pudyn:MonochromeImpressions>340</pudyn:MonochromeImpressions>
        <pudyn:ColorImpressions>180</pudyn:ColorImpressions>
      </pudyn:PrinterSubunit>
    </pudyn:ProductUsageDyn>"""
    status = client._parse_web_info(hp_xml)
    assert status.online is True
    assert status.monochrome_pages == 340
    assert status.color_pages == 180
    assert status.total_pages == 520
    assert status.has_channel_telemetry is True
    assert status.make_and_model == "HP Smart Tank Series"
    assert status.head_technology == "thermal"


def test_parse_canon_megatank_telemetry():
    client = EpsonPrinterClient()
    canon_html = """<html><head><title>Canon G6000 series</title></head>
    <body>
      <h1>Canon MegaTank System Status</h1>
      <div>Black & White Impressions : 410</div>
      <div>Color Impressions : 95</div>
      <div>Printer Status: Ready</div>
    </body></html>"""
    status = client._parse_web_info(canon_html)
    assert status.online is True
    assert status.monochrome_pages == 410
    assert status.color_pages == 95
    assert status.total_pages == 505
    assert status.has_channel_telemetry is True
    assert status.make_and_model == "Canon MegaTank Series"
    assert status.head_technology == "thermal"


def test_parse_brother_inkvestment_telemetry():
    client = EpsonPrinterClient()
    brother_html = """<html><head><title>Brother MFC-T920DW</title></head>
    <body>
      <h2>Brother Inkvestment Tank Maintenance</h2>
      <div>Page Counter: 850</div>
      <div>Monochrome : 600</div>
      <div>Color : 250</div>
      <div>Printer Status : Idle</div>
    </body></html>"""
    status = client._parse_web_info(brother_html)
    assert status.online is True
    assert status.monochrome_pages == 600
    assert status.color_pages == 250
    assert status.total_pages == 850
    assert status.has_channel_telemetry is True
    assert status.make_and_model == "Brother Inkvestment Series"
    assert status.head_technology == "piezo"

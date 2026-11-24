import io
from unittest.mock import AsyncMock, patch
from PIL import Image
import pytest
from starlette.testclient import TestClient

from src.config import Settings
from src.generator import PurgePatternGenerator
from src.scanner import analyze_nozzle_sheet
from src.printer import PurgeJobResult, PurgeJobStatus, PrinterStatus
from src.main import app, settings

def test_surgical_purge_png_generation(tmp_path):
    cfg = Settings()
    gen = PurgePatternGenerator(cfg, assets_dir=tmp_path)
    
    # Black channel (Grayscale)
    k_path, k_model = gen.generate_surgical_purge_png("black", step=3, bursts=5)
    assert k_path.exists()
    assert k_model == "Gray"
    with Image.open(k_path) as img:
        assert img.size == (2976, 4209)
        assert img.mode == "L"
        
    # Color channel (RGB)
    c_path, c_model = gen.generate_surgical_purge_png("cyan", step=1, bursts=2)
    assert c_path.exists()
    assert c_model == "RGB"
    with Image.open(c_path) as img:
        assert img.size == (2976, 4209)
        assert img.mode == "RGB"

def test_scanner_with_generated_sheet(tmp_path):
    cfg = Settings()
    gen = PurgePatternGenerator(cfg, assets_dir=tmp_path)
    
    sheet_path = gen.generate_cmyk_pattern_png()
    assert sheet_path.exists()
    
    with open(sheet_path, "rb") as f:
        img_bytes = f.read()
        
    result = analyze_nozzle_sheet(img_bytes)
    assert result["success"] is True
    assert "overall_health_pct" in result
    assert "channels" in result
    assert "black" in result["channels"]
    assert "cyan" in result["channels"]
    assert "magenta" in result["channels"]
    assert "yellow" in result["channels"]
    assert "missing_rungs" in result
    assert "recommended_action" in result
    assert result["overall_health_pct"] > 50.0

def test_scanner_with_empty_image():
    # Solid white image has no rungs -> 0% health
    img = Image.new("RGB", (200, 200), color=(255, 255, 255))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    
    result = analyze_nozzle_sheet(buf.getvalue())
    assert result["success"] is True
    assert result["overall_health_pct"] == 0.0
    assert result["recommended_action"] == "full_purge"

def test_web_ui_endpoint():
    client = TestClient(app)
    resp = client.get("/")
    assert resp.status_code == 200
    assert "Nozzle Guardian" in resp.text
    assert "Spurgo Chirurgico" in resp.text
    assert "Scanner Fotocamera" in resp.text
    assert 'rel="manifest"' in resp.text

    resp_ui = client.get("/ui")
    assert resp_ui.status_code == 200
    assert "Nozzle Guardian" in resp_ui.text

    # PWA Endpoints
    resp_manifest = client.get("/manifest.json")
    assert resp_manifest.status_code == 200
    assert resp_manifest.json()["display"] == "standalone"

    resp_sw = client.get("/sw.js")
    assert resp_sw.status_code == 200
    assert "addEventListener" in resp_sw.text

def test_surgical_purge_endpoint(tmp_path):
    client = TestClient(app)
    headers = {"x-api-key": settings.api_key} if settings.api_key else {}

    status_online = PrinterStatus(online=True, monochrome_pages=10, color_pages=20, total_pages=30, state="idle")
    success_result = PurgeJobResult(status=PurgeJobStatus.ACCEPTED, message="Accepted")

    with patch("src.main.printer_client.get_status", new_callable=AsyncMock) as mock_status, \
         patch("src.main.printer_client.execute_purge_job", new_callable=AsyncMock) as mock_exec, \
         patch("src.main.generator.compile_surgical_pwg") as mock_pwg:
        
        mock_status.return_value = status_online
        mock_exec.return_value = success_result
        mock_pwg.return_value = tmp_path / "dummy.pwg"
        
        resp = client.post("/purge/surgical", json={
            "channel": "cyan",
            "step": 4,
            "bursts": 3
        }, headers=headers)
        
        assert resp.status_code == 200
        data = resp.json()
        assert data["success"] is True
        assert "surgical_cyan_4" in data["mode"]
        assert mock_exec.called

def test_scan_nozzle_health_endpoint():
    client = TestClient(app)
    headers = {"x-api-key": settings.api_key} if settings.api_key else {}

    img = Image.new("RGB", (300, 300), color=(255, 255, 255))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    buf.seek(0)
    
    response = client.post(
        "/scan/nozzle-health",
        files={"file": ("test_scan.png", buf.getvalue(), "image/png")},
        headers=headers
    )
    assert response.status_code == 200
    data = response.json()
    assert data["success"] is True
    assert "overall_health_pct" in data
    assert "channels" in data

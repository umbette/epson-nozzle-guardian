import json
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from starlette.testclient import TestClient

from src.config import NotificationsConfig, Settings
from src.desktop import generate_autostart_artifacts
from src.main import app, daemon, settings
from src.notifier import Notifier


def test_hybrid_open_core_licenses_and_prior_art():
    root = Path(__file__).resolve().parent.parent
    assert "GNU AFFERO GENERAL PUBLIC LICENSE" in (root / "LICENSE").read_text(encoding="utf-8")
    assert "PolyForm Noncommercial License" in (root / "LICENSE-COMMERCIAL-HARDWARE").read_text(encoding="utf-8")
    assert "PolyForm Noncommercial License" in (root / "firmware" / "LICENSE").read_text(encoding="utf-8")
    assert "PolyForm Noncommercial License" in (root / "hardware" / "LICENSE").read_text(encoding="utf-8")
    assert "NG-PRIOR-ART-2026-01" in (root / "docs" / "PRIOR_ART.md").read_text(encoding="utf-8")


def test_config_ha_addon_options_and_notifications(tmp_path, monkeypatch):
    opt_file = tmp_path / "options.json"
    opt_file.write_text(
        json.dumps({
            "printer_host": "192.168.1.99",
            "printer_port": 631,
            "printer_use_tls": False,
            "inactivity_threshold_days": 4,
            "auto_purge_enabled": True,
            "media_size": "na_letter_8.5x11in",
            "ntfy_url": "https://ntfy.sh/test-topic",
            "webhook_url": "https://hooks.local/nozzle",
            "notify_service": "notify.mobile_app_iphone",
        }),
        encoding="utf-8",
    )
    monkeypatch.setenv("OPTIONS_PATH", str(opt_file))
    monkeypatch.setenv("ALLOW_UNAUTHENTICATED", "true")

    loaded = Settings.load(config_path=str(tmp_path / "missing.yaml"), data_dir=str(tmp_path))
    assert loaded.printer.host == "192.168.1.99"
    assert loaded.printer.use_tls is False
    assert loaded.printer.media_size == "na_letter_8.5x11in"
    assert loaded.notifications.ntfy_url == "https://ntfy.sh/test-topic"
    assert loaded.notifications.webhook_url == "https://hooks.local/nozzle"
    assert loaded.notifications.ha_notify_service == "mobile_app_iphone"


@pytest.mark.asyncio
async def test_notifier_dispatches_ntfy_ha_and_webhook():
    cfg = Settings(
        notifications=NotificationsConfig(
            ntfy_url="https://ntfy.sh/ng-unit-test",
            webhook_url="https://example.invalid/webhook",
            ha_url="http://supervisor/core",
            ha_token="ha-secret-token",
            ha_notify_service="mobile_app_phone",
            dashboard_url="http://192.168.1.10:8086",
        )
    )
    notifier = Notifier(cfg)

    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_client = MagicMock()
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)
    mock_client.post = AsyncMock(return_value=mock_resp)

    with patch("src.notifier.httpx.AsyncClient", return_value=mock_client):
        ok = await notifier.send_alert("Purge Done", "CMY micro-purge complete", event_type="purge")

    assert ok is True
    urls = [call.args[0] for call in mock_client.post.call_args_list]
    assert "https://ntfy.sh/ng-unit-test" in urls
    assert "http://supervisor/core/api/services/notify/mobile_app_phone" in urls
    assert "https://example.invalid/webhook" in urls


def test_desktop_autostart_generators(tmp_path):
    res = generate_autostart_artifacts(output_dir=tmp_path, install_local=False)
    assert (tmp_path / "com.umbette.nozzleguardian.plist").exists()
    assert (tmp_path / "nozzle-guardian-desktop.service").exists()
    assert (tmp_path / "nozzle-guardian-windows-task.xml").exists()
    assert "com.umbette.nozzleguardian" in res["macos_plist"]
    assert "src.cli desktop" in res["linux_user_service"]
    assert "Kernel-Power" in res["windows_task_xml"]


def test_one_click_autostart_api_and_frozen_zero_arg_launch(monkeypatch):
    client = TestClient(app)
    headers = {"x-api-key": settings.api_key} if settings.api_key else {}

    with patch("src.desktop.get_autostart_status", return_value={"platform": "Windows", "enabled": True, "target": "NozzleGuardian.exe"}):
        r_get = client.get("/daemon/autostart", headers=headers)
        assert r_get.status_code == 200
        assert r_get.json()["enabled"] is True

    with patch("src.desktop.set_autostart", return_value={"platform": "Windows", "enabled": False, "target": ""}) as mock_set:
        r_post = client.post("/daemon/autostart", json={"enabled": False}, headers=headers)
        assert r_post.status_code == 200
        assert r_post.json()["enabled"] is False
        mock_set.assert_called_once_with(False)

    # Zero-arg launch (double-clicking .exe / .app) starts 1-click desktop UI cleanly
    import sys
    from src.cli import main as cli_main
    with monkeypatch.context() as mp:
        mp.setattr(sys, "argv", ["NozzleGuardian.exe"])
        with patch("src.desktop.run_desktop") as mock_desktop:
            cli_main()
            mock_desktop.assert_called_once_with(host="127.0.0.1", port=8086, open_browser=True, enable_tray=True)


def test_mobile_shortcuts_and_check_endpoint(tmp_path):
    client = TestClient(app)
    headers = {"x-api-key": settings.api_key} if settings.api_key else {}

    # Unauthorized request must still fail closed when auth is active
    if settings.api_key:
        unauth = client.post("/check")
        assert unauth.status_code == 401

    resp_sc = client.get("/mobile/shortcut-config", headers=headers)
    assert resp_sc.status_code == 200
    sc_data = resp_sc.json()
    assert "/check?force=true" in sc_data["check_dormancy_post"]
    assert "/purge/cmyk" in sc_data["purge_cmyk_post"]

    with patch.object(daemon, "evaluate_channels", AsyncMock(return_value={"checked": True, "actions": ["cmy"]})):
        resp_chk = client.post("/check?force=true", headers=headers)
        assert resp_chk.status_code == 200
        assert resp_chk.json()["actions"] == ["cmy"]

    # Check UI renders relative links and Save IP controls
    ui_resp = client.get("/ui")
    assert ui_resp.status_code == 200
    assert "./status" in ui_resp.text
    assert "./check?force=true" in ui_resp.text
    assert "fetch('/status" not in ui_resp.text

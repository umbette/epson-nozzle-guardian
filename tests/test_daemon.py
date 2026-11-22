import os
import json
import asyncio
from datetime import datetime, timezone, timedelta
from unittest.mock import AsyncMock, patch, MagicMock

import pytest
from src.daemon import NozzleGuardianDaemon
from src.printer import PrinterStatus


@pytest.fixture
def daemon_instance(tmp_path):
    daemon = NozzleGuardianDaemon(state_dir=str(tmp_path / "data"))
    return daemon


def test_daemon_defaults_to_auto_purge_disabled(daemon_instance):
    state = daemon_instance.load_state()
    assert state["auto_purge_enabled"] is False
    assert state["last_mono_pages"] is None
    assert state["last_color_pages"] is None


def test_atomic_state_save_and_load(daemon_instance):
    state = daemon_instance.load_state()
    state["auto_purge_enabled"] = True
    state["last_mono_pages"] = 120
    daemon_instance.save_state(state)

    # State file must exist and temp file must not remain
    assert daemon_instance.state_file.exists()
    assert not daemon_instance.state_file.with_suffix(".tmp").exists()

    loaded = daemon_instance.load_state()
    assert loaded["auto_purge_enabled"] is True
    assert loaded["last_mono_pages"] == 120


@pytest.mark.asyncio
async def test_unsupported_counters_inhibits_auto_purge(daemon_instance):
    """When printer only returns total pages without mono/color channels, auto-purge must NOT trigger."""
    state = daemon_instance.load_state()
    state["auto_purge_enabled"] = True
    # Make past activity appear old
    old_time = (datetime.now(timezone.utc) - timedelta(days=10)).isoformat()
    state["last_mono_activity"] = old_time
    state["last_color_activity"] = old_time
    daemon_instance.save_state(state)

    # Status with only total pages
    status_only_total = PrinterStatus(
        online=True,
        monochrome_pages=None,
        color_pages=None,
        total_pages=500,
        state="idle"
    )

    with patch.object(daemon_instance.printer_client, "get_status", AsyncMock(return_value=status_only_total)), \
         patch.object(daemon_instance, "_execute_purge", AsyncMock(return_value=True)) as mock_purge:
        
        await daemon_instance.evaluate_channels()
        mock_purge.assert_not_called()

    # Verify state reflects unsupported channels
    updated = daemon_instance.load_state()
    assert updated["mono_channel_supported"] is False
    assert updated["color_channel_supported"] is False


@pytest.mark.asyncio
async def test_decreasing_counters_handles_printer_reset_without_purge(daemon_instance):
    """If counter decreases (e.g. EEPROM replacement or reset), baseline resets without spurious purge."""
    state = daemon_instance.load_state()
    state["auto_purge_enabled"] = True
    state["last_mono_pages"] = 500
    state["last_color_pages"] = 300
    daemon_instance.save_state(state)

    status_reset = PrinterStatus(
        online=True,
        monochrome_pages=10,  # Decreased from 500
        color_pages=5,        # Decreased from 300
        total_pages=15,
        state="idle"
    )

    with patch.object(daemon_instance.printer_client, "get_status", AsyncMock(return_value=status_reset)), \
         patch.object(daemon_instance, "_execute_purge", AsyncMock(return_value=True)) as mock_purge:
        
        await daemon_instance.evaluate_channels()
        mock_purge.assert_not_called()

    updated = daemon_instance.load_state()
    assert updated["last_mono_pages"] == 10
    assert updated["last_color_pages"] == 5


@pytest.mark.asyncio
async def test_dormancy_purge_triggers_only_when_enabled_and_supported(daemon_instance):
    """When auto_purge_enabled is True and a channel is dormant beyond threshold, purge executes."""
    state = daemon_instance.load_state()
    state["auto_purge_enabled"] = True
    state["last_mono_pages"] = 100
    state["last_color_pages"] = 50
    # Black is dormant (10 days old), Color was active recently
    state["last_mono_activity"] = (datetime.now(timezone.utc) - timedelta(days=10)).isoformat()
    state["last_color_activity"] = datetime.now(timezone.utc).isoformat()
    daemon_instance.save_state(state)

    status_active = PrinterStatus(
        online=True,
        monochrome_pages=100,  # Unchanged
        color_pages=50,        # Unchanged
        total_pages=150,
        state="idle"
    )

    with patch.object(daemon_instance.printer_client, "get_status", AsyncMock(return_value=status_active)), \
         patch.object(daemon_instance, "_execute_purge", AsyncMock(return_value=True)) as mock_purge:
        
        await daemon_instance.evaluate_channels()
        mock_purge.assert_called_once_with("k")

    updated = daemon_instance.load_state()
    assert updated["last_mono_purge"] is not None
    assert updated["last_color_purge"] is None


@pytest.mark.asyncio
async def test_evaluate_channels_only_monochrome_triggers_k_only(daemon_instance):
    """When only monochrome counter is present and dormant, only K purge must trigger."""
    state = daemon_instance.load_state()
    state["auto_purge_enabled"] = True
    state["last_mono_pages"] = 200
    state["last_mono_activity"] = (datetime.now(timezone.utc) - timedelta(days=10)).isoformat()
    daemon_instance.save_state(state)

    status_only_mono = PrinterStatus(
        online=True,
        monochrome_pages=200,
        color_pages=None,  # Not supported
        total_pages=200,
        state="idle"
    )

    with patch.object(daemon_instance.printer_client, "get_status", AsyncMock(return_value=status_only_mono)), \
         patch.object(daemon_instance, "_execute_purge", AsyncMock(return_value=True)) as mock_purge:
        
        await daemon_instance.evaluate_channels()
        mock_purge.assert_called_once_with("k")

    updated = daemon_instance.load_state()
    assert updated["mono_channel_supported"] is True
    assert updated["color_channel_supported"] is False


@pytest.mark.asyncio
async def test_evaluate_channels_only_color_triggers_cmy_only(daemon_instance):
    """When only color counter is present and dormant, only CMY purge must trigger."""
    state = daemon_instance.load_state()
    state["auto_purge_enabled"] = True
    state["last_color_pages"] = 80
    state["last_color_activity"] = (datetime.now(timezone.utc) - timedelta(days=10)).isoformat()
    daemon_instance.save_state(state)

    status_only_color = PrinterStatus(
        online=True,
        monochrome_pages=None,  # Not supported
        color_pages=80,
        total_pages=80,
        state="idle"
    )

    with patch.object(daemon_instance.printer_client, "get_status", AsyncMock(return_value=status_only_color)), \
         patch.object(daemon_instance, "_execute_purge", AsyncMock(return_value=True)) as mock_purge:
        
        await daemon_instance.evaluate_channels()
        mock_purge.assert_called_once_with("cmy")

    updated = daemon_instance.load_state()
    assert updated["mono_channel_supported"] is False
    assert updated["color_channel_supported"] is True


@pytest.mark.asyncio
async def test_shared_client_lock_serializes_daemon_and_api(daemon_instance):
    """Verifies that the printer client lock prevents simultaneous job dispatches between daemon and API."""
    client = daemon_instance.printer_client
    assert not client.is_busy

    async with client._lock:
        assert client.is_busy
        # When lock is held, execute_purge_job must reject immediately
        rejected = await client.execute_purge_job(daemon_instance.state_file, mode="k")
        assert rejected is False

    assert not client.is_busy


def test_fail_closed_api_key_generation(tmp_path, monkeypatch):
    """When API_KEY is not configured in env, a persistent 32-byte secret is created and enforced."""
    monkeypatch.delenv("API_KEY", raising=False)
    monkeypatch.delenv("ALLOW_UNAUTHENTICATED", raising=False)
    from src.config import Settings
    
    settings = Settings.load(config_path="nonexistent.yaml", data_dir=str(tmp_path))
    assert settings.api_key is not None
    assert len(settings.api_key) == 64  # 32 bytes hex
    assert (tmp_path / "api_key.secret").exists()
    assert (tmp_path / "api_key.secret").read_text(encoding="utf-8").strip() == settings.api_key

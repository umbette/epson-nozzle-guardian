import os
import json
import asyncio
from datetime import datetime, timezone, timedelta
from unittest.mock import AsyncMock, patch, MagicMock

import pytest
from src.daemon import NozzleGuardianDaemon
from src.printer import PrinterStatus
from src.main import purge_lock


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
async def test_purge_lock_concurrency():
    assert not purge_lock.locked()
    async with purge_lock:
        assert purge_lock.locked()
    assert not purge_lock.locked()

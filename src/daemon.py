import os
import asyncio
import json
import logging
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Optional

from .config import settings
from .printer import EpsonPrinterClient, PrinterStatus
from .generator import PatternGenerator

logger = logging.getLogger("nozzle_guardian.daemon")


class NozzleGuardianDaemon:
    def __init__(self, state_dir: str = "/app/data"):
        try:
            p = Path(state_dir)
            p.mkdir(parents=True, exist_ok=True)
            self.state_dir = p
        except (PermissionError, OSError):
            self.state_dir = Path("data").resolve()
            self.state_dir.mkdir(parents=True, exist_ok=True)
        self.state_file = self.state_dir / "guardian_state.json"
        
        self.printer_client = EpsonPrinterClient()
        self.generator = PatternGenerator(assets_dir="/app/assets")
        self._running = False
        self._task: Optional[asyncio.Task] = None
        self.poll_interval_hours = int(settings.printer.inactivity_threshold_days * 24 / 5) or 12

    def load_state(self) -> dict:
        default_state = {
            "last_mono_pages": None,
            "last_color_pages": None,
            "last_mono_activity": datetime.now(timezone.utc).isoformat(),
            "last_color_activity": datetime.now(timezone.utc).isoformat(),
            "last_mono_purge": None,
            "last_color_purge": None,
            "auto_purge_enabled": getattr(settings.printer, "auto_purge_enabled", False),
            "mono_channel_supported": False,
            "color_channel_supported": False
        }
        if self.state_file.exists():
            try:
                loaded = json.loads(self.state_file.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    for k, v in default_state.items():
                        if k not in loaded:
                            loaded[k] = v
                    return loaded
            except Exception as e:
                logger.warning(f"Could not load state file {self.state_file}: {e}")
        return default_state

    def save_state(self, state: dict):
        try:
            temp_file = self.state_file.with_suffix(".tmp")
            temp_file.write_text(json.dumps(state, indent=2), encoding="utf-8")
            os.replace(temp_file, self.state_file)
        except Exception as e:
            logger.error(f"Failed to save guardian state: {e}")

    async def evaluate_channels(self):
        """Polls printer status, computes channel dormancy, and triggers maintenance purges if needed."""
        logger.info(f"Running periodic nozzle health audit against {settings.printer.host}...")
        status = await self.printer_client.get_status()
        
        if not status.online or status.state == "unknown":
            logger.warning(f"Printer is offline or in unknown state ({status.error_message}). Skipping auto-purge check.")
            return

        state = self.load_state()
        now = datetime.now(timezone.utc)
        threshold = timedelta(days=settings.printer.inactivity_threshold_days)

        mono_supported = status.monochrome_pages is not None
        color_supported = status.color_pages is not None
        state["mono_channel_supported"] = mono_supported
        state["color_channel_supported"] = color_supported

        # Check Monochrome (K) channel
        mono_dormant = False
        if mono_supported:
            prev_mono = state.get("last_mono_pages")
            if prev_mono is not None:
                if status.monochrome_pages > prev_mono:
                    logger.info(f"Black channel active (+{status.monochrome_pages - prev_mono} pages). Resetting K dormancy timer.")
                    state["last_mono_activity"] = now.isoformat()
                elif status.monochrome_pages < prev_mono:
                    logger.warning(f"Monochrome counter decreased ({prev_mono} -> {status.monochrome_pages}). Printer counter reset detected.")
                    state["last_mono_activity"] = now.isoformat()
            else:
                state["last_mono_activity"] = now.isoformat()
            state["last_mono_pages"] = status.monochrome_pages
            
            last_mono_dt = datetime.fromisoformat(state.get("last_mono_activity", now.isoformat()))
            mono_dormant = (now - last_mono_dt) >= threshold
        else:
            logger.info("Monochrome page counter not exposed by printer telemetry; K channel dormancy cannot be verified.")

        # Check Color (CMY) channels
        color_dormant = False
        if color_supported:
            prev_color = state.get("last_color_pages")
            if prev_color is not None:
                if status.color_pages > prev_color:
                    logger.info(f"Color channels active (+{status.color_pages - prev_color} pages). Resetting CMY dormancy timer.")
                    state["last_color_activity"] = now.isoformat()
                elif status.color_pages < prev_color:
                    logger.warning(f"Color counter decreased ({prev_color} -> {status.color_pages}). Printer counter reset detected.")
                    state["last_color_activity"] = now.isoformat()
            else:
                state["last_color_activity"] = now.isoformat()
            state["last_color_pages"] = status.color_pages
            
            last_color_dt = datetime.fromisoformat(state.get("last_color_activity", now.isoformat()))
            color_dormant = (now - last_color_dt) >= threshold
        else:
            logger.info("Color page counter not exposed by printer telemetry; CMY channel dormancy cannot be verified.")

        if not state.get("auto_purge_enabled", False):
            logger.info("Auto-purge disabled by user policy (opt-in). State updated.")
            self.save_state(state)
            return

        # Fail-safe: if neither channel is supported, inhibit automated purges
        if not mono_supported and not color_supported:
            logger.warning("Neither monochrome nor color counters are available; inhibiting automated purge to prevent unintended prints.")
            self.save_state(state)
            return

        # Trigger surgical purges based on channel dormancy
        if mono_dormant and color_dormant:
            logger.info("Both K and CMY channels are dormant. Triggering Full CMYK micro-purge...")
            if await self._execute_purge("cmyk"):
                state["last_mono_activity"] = now.isoformat()
                state["last_color_activity"] = now.isoformat()
                state["last_mono_purge"] = now.isoformat()
                state["last_color_purge"] = now.isoformat()
        elif color_dormant:
            logger.info("CMY color channels dormant. Triggering targeted CMY micro-purge (K preserved)...")
            if await self._execute_purge("cmy"):
                state["last_color_activity"] = now.isoformat()
                state["last_color_purge"] = now.isoformat()
        elif mono_dormant:
            logger.info("Black channel dormant. Triggering targeted K micro-purge (CMY preserved)...")
            if await self._execute_purge("k"):
                state["last_mono_activity"] = now.isoformat()
                state["last_mono_purge"] = now.isoformat()
        else:
            logger.info("All printhead nozzle channels healthy and within active dormancy window.")

        self.save_state(state)

    async def _execute_purge(self, mode: str) -> bool:
        pwg_file = self.generator.get_pwg_path(mode)
        if not pwg_file.exists():
            pwg_file = await asyncio.to_thread(self.generator.compile_pwg, mode)
        return await self.printer_client.execute_purge_job(pwg_file, mode=mode)

    async def run_loop(self):
        self._running = True
        logger.info(f"Nozzle Guardian background scheduler started. Check interval: {self.poll_interval_hours}h.")
        while self._running:
            try:
                await self.evaluate_channels()
            except Exception as e:
                logger.error(f"Error in background nozzle evaluation loop: {e}")
            await asyncio.sleep(self.poll_interval_hours * 3600)

    def start(self):
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self.run_loop())

    def stop(self):
        self._running = False
        if self._task and not self._task.done():
            self._task.cancel()

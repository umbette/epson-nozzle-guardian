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
        self.state_dir = Path(state_dir)
        self.state_dir.mkdir(parents=True, exist_ok=True)
        self.state_file = self.state_dir / "guardian_state.json"
        
        self.printer_client = EpsonPrinterClient()
        self.generator = PatternGenerator(assets_dir="/app/assets")
        self._running = False
        self._task: Optional[asyncio.Task] = None
        self.poll_interval_hours = int(settings.printer.inactivity_threshold_days * 24 / 5) or 12

    def load_state(self) -> dict:
        if self.state_file.exists():
            try:
                return json.loads(self.state_file.read_text(encoding="utf-8"))
            except Exception as e:
                logger.warning(f"Could not load state file {self.state_file}: {e}")
        return {
            "last_mono_pages": None,
            "last_color_pages": None,
            "last_mono_activity": datetime.now(timezone.utc).isoformat(),
            "last_color_activity": datetime.now(timezone.utc).isoformat(),
            "last_mono_purge": None,
            "last_color_purge": None,
            "auto_purge_enabled": True
        }

    def save_state(self, state: dict):
        try:
            self.state_file.write_text(json.dumps(state, indent=2), encoding="utf-8")
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

        # Check Monochrome (K) channel
        if status.monochrome_pages is not None:
            prev_mono = state.get("last_mono_pages")
            if prev_mono is not None and status.monochrome_pages > prev_mono:
                logger.info(f"Black channel active (+{status.monochrome_pages - prev_mono} pages). Resetting K dormancy timer.")
                state["last_mono_activity"] = now.isoformat()
            state["last_mono_pages"] = status.monochrome_pages

        # Check Color (CMY) channels
        if status.color_pages is not None:
            prev_color = state.get("last_color_pages")
            if prev_color is not None and status.color_pages > prev_color:
                logger.info(f"Color channels active (+{status.color_pages - prev_color} pages). Resetting CMY dormancy timer.")
                state["last_color_activity"] = now.isoformat()
            state["last_color_pages"] = status.color_pages

        # Parse dormancy duration
        last_mono_dt = datetime.fromisoformat(state.get("last_mono_activity", now.isoformat()))
        last_color_dt = datetime.fromisoformat(state.get("last_color_activity", now.isoformat()))

        mono_dormant = (now - last_mono_dt) >= threshold
        color_dormant = (now - last_color_dt) >= threshold

        if not state.get("auto_purge_enabled", True):
            logger.info("Auto-purge disabled by user policy. State updated.")
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
        return await asyncio.to_thread(self.printer_client.print_purge_file, pwg_file, mode=mode)

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

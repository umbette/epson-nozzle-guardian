"""
Nozzle Guardian Background Scheduler & State Engine
===================================================
Tracks Monochrome (K) and Color (CMY) dormancy independently (with total-page fallback),
checks printer readiness (laser protection, media-empty guard), and executes
driverless PWG/PDF/PNG micro-purges in pure Python.
"""

import asyncio
from datetime import datetime, timedelta, timezone
import inspect
import json
import logging
import os
from pathlib import Path
from typing import Any, Callable, Dict, Optional

from .config import settings
from .generator import PatternGenerator
from .printer import EpsonPrinterClient, PrinterStatus, PurgeJobResult, PurgeJobStatus

logger = logging.getLogger("nozzle_guardian.daemon")

CURRENT_SCHEMA_VERSION = 2


class NozzleGuardianDaemon:
    def __init__(
        self,
        state_dir: str = "/app/data",
        printer_host: Optional[str] = None,
        printer_port: Optional[int] = None,
        use_tls: Optional[bool] = None,
        inactivity_threshold_days: Optional[int] = None,
        auto_purge_enabled: Optional[bool] = None,
        media_size: Optional[str] = None,
        dpi: Optional[int] = None,
        alert_callback: Optional[Callable[..., Any]] = None,
    ):
        try:
            p = Path(state_dir)
            p.mkdir(parents=True, exist_ok=True)
            self.state_dir = p
        except (PermissionError, OSError):
            self.state_dir = Path("data").resolve()
            self.state_dir.mkdir(parents=True, exist_ok=True)

        self.state_file = self.state_dir / "guardian_state.json"
        self.default_inactivity_days = max(1, int(inactivity_threshold_days if inactivity_threshold_days is not None else settings.printer.inactivity_threshold_days))
        self.default_auto_purge = bool(auto_purge_enabled if auto_purge_enabled is not None else settings.printer.auto_purge_enabled)
        self.dpi = int(dpi if dpi is not None else settings.pattern.dpi)
        self.alert_callback = alert_callback

        self.printer_client = EpsonPrinterClient(
            host=printer_host,
            port=printer_port,
            use_tls=use_tls,
            preferred_media=media_size,
        )
        self.generator = PatternGenerator(assets_dir=str(self.state_dir / "nozzle_assets"), dpi=self.dpi, media_name=self.printer_client.preferred_media)
        self._running = False
        self._task: Optional[asyncio.Task] = None
        self.poll_interval_hours = max(4, int(self.default_inactivity_days * 24 / 5) or 12)

        state = self.load_state()
        if printer_host is None and state.get("printer_host"):
            self.printer_client.configure(
                host=state["printer_host"],
                port=state.get("printer_port", 631),
                use_tls=state.get("printer_use_tls", True),
                preferred_media=state.get("media_size", "auto"),
                preferred_format=state.get("preferred_format", "auto"),
                preferred_media_type=state.get("media_type", "auto"),
                preferred_ink_profile=state.get("ink_profile", "auto"),
            )

    def load_state(self) -> Dict[str, Any]:
        default_state: Dict[str, Any] = {
            "schema_version": CURRENT_SCHEMA_VERSION,
            "printer_host": self.printer_client.host,
            "printer_port": self.printer_client.port,
            "printer_use_tls": self.printer_client.use_tls,
            "media_size": self.printer_client.preferred_media,
            "preferred_format": self.printer_client.preferred_format,
            "media_type": self.printer_client.preferred_media_type,
            "ink_profile": self.printer_client.preferred_ink_profile,
            "tank_count": 4,
            "inactivity_threshold_days": self.default_inactivity_days,
            "last_mono_pages": None,
            "last_color_pages": None,
            "last_total_pages": None,
            "last_mono_activity": datetime.now(timezone.utc).isoformat(),
            "last_color_activity": datetime.now(timezone.utc).isoformat(),
            "last_total_activity": datetime.now(timezone.utc).isoformat(),
            "last_mono_purge": None,
            "last_color_purge": None,
            "auto_purge_enabled": self.default_auto_purge,
            "mono_channel_supported": False,
            "color_channel_supported": False,
            "total_channel_supported": False,
            "last_model": None,
            "printer_kind": "inkjet",
            "head_technology": "piezo",
        }
        if self.state_file.exists():
            try:
                loaded = json.loads(self.state_file.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    version = loaded.get("schema_version", 1)
                    if version < CURRENT_SCHEMA_VERSION:
                        loaded["schema_version"] = CURRENT_SCHEMA_VERSION
                        loaded["auto_purge_enabled"] = False
                    for k, v in default_state.items():
                        if k not in loaded:
                            loaded[k] = v
                    return loaded
            except Exception as e:
                logger.warning(f"Could not load Nozzle Guardian state {self.state_file}: {e}")
        return default_state

    def save_state(self, state: Dict[str, Any]) -> None:
        try:
            temp_file = self.state_file.with_suffix(".tmp")
            temp_file.write_text(json.dumps(state, indent=2), encoding="utf-8")
            os.replace(temp_file, self.state_file)
        except Exception as e:
            logger.error(f"Failed to save Nozzle Guardian state: {e}")

    def update_config(
        self,
        printer_host: Optional[str] = None,
        printer_port: Optional[int] = None,
        use_tls: Optional[bool] = None,
        inactivity_threshold_days: Optional[int] = None,
        auto_purge_enabled: Optional[bool] = None,
        media_size: Optional[str] = None,
        preferred_format: Optional[str] = None,
        media_type: Optional[str] = None,
        ink_profile: Optional[str] = None,
    ) -> Dict[str, Any]:
        state = self.load_state()
        if printer_host is not None:
            state["printer_host"] = printer_host.strip()
        if printer_port is not None:
            state["printer_port"] = int(printer_port)
        if use_tls is not None:
            state["printer_use_tls"] = bool(use_tls)
        if inactivity_threshold_days is not None:
            state["inactivity_threshold_days"] = max(1, min(30, int(inactivity_threshold_days)))
        if auto_purge_enabled is not None:
            state["auto_purge_enabled"] = bool(auto_purge_enabled)
        if media_size is not None:
            state["media_size"] = media_size
        if preferred_format is not None:
            state["preferred_format"] = preferred_format
        if media_type is not None:
            state["media_type"] = media_type
        if ink_profile is not None:
            state["ink_profile"] = ink_profile

        self.printer_client.configure(
            host=state["printer_host"],
            port=state["printer_port"],
            use_tls=state["printer_use_tls"],
            preferred_media=state["media_size"],
            preferred_format=state.get("preferred_format", "auto"),
            preferred_media_type=state.get("media_type", "auto"),
            preferred_ink_profile=state.get("ink_profile", "auto"),
        )
        self.save_state(state)
        return state

    async def execute_purge(self, mode: str, status: Optional[PrinterStatus] = None) -> PurgeJobResult:
        """Executes a preventive micro-purge negotiating format, DPI, colorspace, and media."""
        st = status or await self.printer_client.get_status()
        if not st.online:
            return PurgeJobResult(status=PurgeJobStatus.ERROR, message=st.error_message or "Printer offline.")
        if st.printer_kind == "laser":
            return PurgeJobResult(
                status=PurgeJobStatus.REJECTED,
                message="Laser/Toner printer detected: nozzle purge not applicable.",
            )
        if st.is_media_empty:
            return PurgeJobResult(
                status=PurgeJobStatus.REJECTED,
                message="Paper tray empty (media-empty): insert scrap paper before purging.",
            )

        primary_fmt = self.printer_client.negotiate_format(st.supported_formats)
        formats_to_try = [primary_fmt] + self.printer_client.get_fallback_formats(primary_fmt, st.supported_formats)
        negotiated_dpi = self.printer_client.negotiate_pwg_dpi(st.pwg_resolutions_supported, preferred_dpi=self.dpi)
        force_rgb = self.printer_client.negotiate_pwg_force_rgb(mode, st.pwg_document_types_supported)
        color_mode = self.printer_client.negotiate_color_mode(mode, st.print_color_modes_supported, force_rgb=force_rgb)
        media_type = self.printer_client.negotiate_media_type(mode, st.media_types_supported)
        pwg_media_type = media_type or "Stationery"

        gen = PatternGenerator(assets_dir=str(self.state_dir / "nozzle_assets"), dpi=negotiated_dpi, media_name=st.media_default)
        last_res = PurgeJobResult(status=PurgeJobStatus.ERROR, message="No supported print format succeeded.")
        for doc_format in formats_to_try:
            payload = await asyncio.to_thread(
                gen.compile_payload,
                mode,
                doc_format=doc_format,
                force_rgb=(force_rgb if doc_format == "image/pwg-raster" else False),
                media_type=pwg_media_type,
            )
            last_res = await self.printer_client.execute_purge_payload(
                payload=payload,
                doc_format=doc_format,
                mode=mode,
                media=st.media_default,
                endpoint_path=st.endpoint_path,
                color_mode_override=color_mode,
                media_type=media_type,
            )
            if last_res.status != PurgeJobStatus.ERROR:
                return last_res
        return last_res

    async def _execute_purge(self, mode: str) -> bool:
        """Backward-compatible helper delegating to execute_purge and returning boolean."""
        res = await self.execute_purge(mode)
        return res.status == PurgeJobStatus.ACCEPTED

    async def execute_surgical_purge(
        self,
        channel: str,
        step: int,
        bursts: int = 5,
        status: Optional[PrinterStatus] = None,
    ) -> Dict[str, Any]:
        """Executes a single-nozzle surgical pass enforcing thermal head safety."""
        st = status or await self.printer_client.get_status()
        if not st.online:
            return {"accepted": False, "status": "offline", "message": st.error_message or "Printer offline.", "effective_bursts": 0}
        if st.printer_kind == "laser":
            return {"accepted": False, "status": "rejected", "message": "Laser/Toner printer detected: operation not supported.", "effective_bursts": 0}
        if st.is_media_empty:
            return {"accepted": False, "status": "rejected", "message": "Paper tray empty (media-empty).", "effective_bursts": 0}

        primary_fmt = self.printer_client.negotiate_format(st.supported_formats)
        formats_to_try = [primary_fmt] + self.printer_client.get_fallback_formats(primary_fmt, st.supported_formats)
        negotiated_dpi = self.printer_client.negotiate_pwg_dpi(st.pwg_resolutions_supported, preferred_dpi=self.dpi)
        purge_mode = "k" if channel.lower().strip() in ("k", "bk", "black") else ("pb_gy" if channel.lower().strip() in ("pb", "photo_black", "photoblack", "gy", "gray", "grey") else "color")
        force_rgb = self.printer_client.negotiate_pwg_force_rgb(purge_mode, st.pwg_document_types_supported)
        color_mode = self.printer_client.negotiate_color_mode(purge_mode, st.print_color_modes_supported, force_rgb=force_rgb)
        media_type = self.printer_client.negotiate_media_type(purge_mode, st.media_types_supported)
        pwg_media_type = media_type or "Stationery"

        gen = PatternGenerator(assets_dir=str(self.state_dir / "nozzle_assets"), dpi=negotiated_dpi, media_name=st.media_default)
        res = PurgeJobResult(status=PurgeJobStatus.ERROR, message="No supported print format succeeded.")
        used_format = primary_fmt
        effective_bursts = bursts
        for doc_format in formats_to_try:
            used_format = doc_format
            payload, effective_bursts = await asyncio.to_thread(
                gen.compile_surgical_payload,
                channel,
                step,
                bursts,
                st.head_technology,
                doc_format,
                force_rgb=(force_rgb if doc_format == "image/pwg-raster" else False),
                media_type=pwg_media_type,
            )
            res = await self.printer_client.execute_purge_payload(
                payload=payload,
                doc_format=doc_format,
                mode=purge_mode,
                media=st.media_default,
                endpoint_path=st.endpoint_path,
                color_mode_override=color_mode,
                media_type=media_type,
            )
            if res.status != PurgeJobStatus.ERROR:
                break
        return {
            "accepted": res.status == PurgeJobStatus.ACCEPTED,
            "status": res.status.value,
            "message": res.message,
            "effective_bursts": effective_bursts,
            "head_technology": st.head_technology,
            "doc_format": used_format,
        }

    async def evaluate_channels(self) -> Dict[str, Any]:
        """Polls printer status, tracks Mono vs Color dormancy, and runs opt-in purges safely."""
        if not self.printer_client.host:
            return {"checked": False, "skipped_reason": "unconfigured", "actions": []}

        status = await self.printer_client.get_status()
        if not status.online or status.state == "unknown":
            return {"checked": False, "skipped_reason": "offline", "actions": []}

        state = self.load_state()
        state["last_model"] = status.make_and_model
        state["printer_kind"] = status.printer_kind
        state["head_technology"] = status.head_technology
        state["tank_count"] = getattr(status, "tank_count", 4)
        if state.get("ink_profile", "auto") == "auto":
            state["detected_ink_profile"] = getattr(status, "ink_profile", "cmyk")

        if status.printer_kind == "laser":
            self.save_state(state)
            return {"checked": True, "printer_kind": "laser", "skipped_reason": "laser_hardware", "actions": []}

        if status.is_media_empty:
            logger.warning("Paper tray empty (media-empty): pausing automatic preventive purges.")
            self.save_state(state)
            await self._emit_alert(
                title="Nozzle Guardian: Vassoio Carta Vuoto",
                message=f"{status.make_and_model or self.printer_client.host}: inserisci un foglio A4/Letter (media-empty) per consentire lo spurgo preventivo.",
                event_type="media-empty",
            )
            return {"checked": True, "printer_kind": status.printer_kind, "skipped_reason": "media_empty", "actions": []}

        now = datetime.now(timezone.utc)
        threshold = timedelta(days=int(state.get("inactivity_threshold_days", self.default_inactivity_days)))

        mono_supported = status.monochrome_pages is not None
        color_supported = status.color_pages is not None
        total_only_supported = status.total_pages is not None and not mono_supported and not color_supported
        state["mono_channel_supported"] = mono_supported
        state["color_channel_supported"] = color_supported
        state["total_channel_supported"] = status.total_pages is not None

        mono_dormant = False
        if mono_supported:
            prev_mono = state.get("last_mono_pages")
            if prev_mono is None or status.monochrome_pages != prev_mono:
                state["last_mono_activity"] = now.isoformat()
            state["last_mono_pages"] = status.monochrome_pages
            last_mono_dt = datetime.fromisoformat(state.get("last_mono_activity", now.isoformat()))
            mono_dormant = (now - last_mono_dt) >= threshold

        color_dormant = False
        if color_supported:
            prev_color = state.get("last_color_pages")
            if prev_color is None or status.color_pages != prev_color:
                state["last_color_activity"] = now.isoformat()
            state["last_color_pages"] = status.color_pages
            last_color_dt = datetime.fromisoformat(state.get("last_color_activity", now.isoformat()))
            color_dormant = (now - last_color_dt) >= threshold

        total_dormant = False
        if total_only_supported:
            prev_total = state.get("last_total_pages")
            if prev_total is None or status.total_pages != prev_total:
                state["last_total_activity"] = now.isoformat()
                state["last_mono_activity"] = now.isoformat()
                state["last_color_activity"] = now.isoformat()
            state["last_total_pages"] = status.total_pages
            last_total_dt = datetime.fromisoformat(state.get("last_total_activity", now.isoformat()))
            total_dormant = (now - last_total_dt) >= threshold

        if not state.get("auto_purge_enabled", False) or (not mono_supported and not color_supported and not total_only_supported):
            self.save_state(state)
            return {
                "checked": True,
                "printer_kind": status.printer_kind,
                "dormant": {"mono": mono_dormant, "color": color_dormant, "total": total_dormant},
                "skipped_reason": "auto_purge_disabled" if not state.get("auto_purge_enabled", False) else "unsupported_counters",
                "actions": [],
            }

        async def _run_purge_step(m: str) -> PurgeJobResult:
            # Support tests patching either `_execute_purge` or `execute_purge`
            bound_exec = getattr(self, "_execute_purge", None)
            orig_exec = NozzleGuardianDaemon._execute_purge
            if type(self)._execute_purge is not orig_exec or getattr(bound_exec, "__func__", bound_exec) is not orig_exec:
                ok = await self._execute_purge(m)
                return PurgeJobResult(PurgeJobStatus.ACCEPTED if ok else PurgeJobStatus.ERROR, "Purge dispatched" if ok else "Purge failed")
            return await self.execute_purge(m, status=status)

        purge_mode: Optional[str] = None
        purge_res: Optional[PurgeJobResult] = None

        if (mono_dormant and color_dormant) or total_dormant:
            purge_mode = "cmyk6" if getattr(status, "ink_profile", "cmyk") == "cmyk6" else "cmyk"
            purge_res = await _run_purge_step(purge_mode)
            if purge_res.status == PurgeJobStatus.ACCEPTED:
                state["last_total_activity"] = now.isoformat()
                state["last_mono_activity"] = now.isoformat()
                state["last_color_activity"] = now.isoformat()
                state["last_mono_purge"] = now.isoformat()
                state["last_color_purge"] = now.isoformat()
        elif color_dormant:
            purge_mode = "cmy"
            purge_res = await _run_purge_step("cmy")
            if purge_res.status == PurgeJobStatus.ACCEPTED:
                state["last_color_activity"] = now.isoformat()
                state["last_color_purge"] = now.isoformat()
        elif mono_dormant:
            purge_mode = "k"
            purge_res = await _run_purge_step("k")
            if purge_res.status == PurgeJobStatus.ACCEPTED:
                state["last_mono_activity"] = now.isoformat()
                state["last_mono_purge"] = now.isoformat()

        self.save_state(state)

        if purge_mode and purge_res is not None:
            printer_label = status.make_and_model or self.printer_client.host or "Printer"
            if purge_res.status == PurgeJobStatus.ACCEPTED:
                await self._emit_alert(
                    title=f"Nozzle Guardian: {purge_mode.upper()} Purge Completed",
                    message=f"{printer_label}: preventive micro-purge succeeded ({purge_res.message}).",
                    event_type="purge",
                )
            elif purge_res.status in (PurgeJobStatus.REJECTED, PurgeJobStatus.ERROR):
                await self._emit_alert(
                    title="Nozzle Guardian: Purge Attention Required",
                    message=f"{printer_label}: {purge_mode.upper()} purge skipped — {purge_res.message}",
                    event_type="warning",
                )

        return {
            "checked": True,
            "printer_kind": status.printer_kind,
            "dormant": {"mono": mono_dormant, "color": color_dormant, "total": total_dormant},
            "actions": [purge_mode] if (purge_mode and purge_res and purge_res.status == PurgeJobStatus.ACCEPTED) else [],
            "purge_status": purge_res.status.value if purge_res else "none",
            "message": purge_res.message if purge_res else "All channels active within threshold.",
        }

    async def _emit_alert(self, title: str, message: str, event_type: str = "purge") -> None:
        if not self.alert_callback:
            return
        try:
            result = self.alert_callback(title, message, event_type=event_type)
            if inspect.isawaitable(result):
                await result
        except Exception as exc:
            logger.warning(f"Nozzle Guardian alert_callback failed: {exc}")

    async def run_loop(self) -> None:
        self._running = True
        logger.info(f"Nozzle Guardian scheduler active (interval: {self.poll_interval_hours}h)")
        while self._running:
            try:
                await self.evaluate_channels()
            except Exception as e:
                logger.error(f"Error in Nozzle Guardian evaluation loop: {e}")
            await asyncio.sleep(self.poll_interval_hours * 3600)

    def start(self) -> None:
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self.run_loop())

    def stop(self) -> None:
        self._running = False
        if self._task and not self._task.done():
            self._task.cancel()

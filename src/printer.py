"""
Universal Multi-Vendor Printer Client (Pure-Python IPP + Web EWS + SNMPv1)
===========================================================================
Supports Epson EcoTank, Canon MegaTank/PIXMA/MAXIFY, HP Smart Tank/OfficeJet,
Brother Inkvestment, and any IPP Everywhere / AirPrint / ipp-usb printer without CUPS.
"""

import asyncio
import logging
from pathlib import Path
import re
from dataclasses import dataclass, field
from enum import Enum
from html.parser import HTMLParser
from typing import Any, Dict, List, Optional, Tuple
import httpx

from .config import settings
from .ipp import PRINTER_STATE_MAP, query_ipp_attributes, send_ipp_print_job
from .local_spooler import is_local_queue_target, query_local_printer_status, send_local_print_job
from .snmp import query_snmp_printer_telemetry

logger = logging.getLogger("nozzle_guardian.printer")

VENDOR_EWS_ENDPOINTS = [
    "/PRESENTATION/HTML/TOP/PRTINFO.HTML",      # Epson EcoTank primary
    "/PRESENTATION/ADVANCED/INFO_PRTINFO.HTML", # Epson EcoTank advanced
    "/DevMgmt/ProductUsageDyn.xml",             # HP Smart Tank & OfficeJet (XML)
    "/device/status.html",                      # Canon MegaTank modern EWS
    "/English/pages_dev/status.html",           # Canon alternate EWS
    "/etc/mnt_info.html",                       # Brother Inkvestment maintenance
    "/general/information.html",                # Brother general status
    "/status.html",                             # Generic
    "/index.html",                              # Generic fallback
]

FORMAT_PREFERENCE = [
    "image/pwg-raster",
    "application/pdf",
    "image/png",
    "image/jpeg",
]


class PurgeJobStatus(str, Enum):
    ACCEPTED = "accepted"
    BUSY = "busy"
    REJECTED = "rejected"
    ERROR = "error"


@dataclass
class PurgeJobResult:
    status: PurgeJobStatus
    message: str


@dataclass
class PrinterStatus:
    online: bool
    monochrome_pages: Optional[int] = None
    color_pages: Optional[int] = None
    total_pages: Optional[int] = None
    state: str = "unknown"
    state_reasons: List[str] = field(default_factory=list)
    error_message: Optional[str] = None
    make_and_model: Optional[str] = None
    printer_kind: str = "inkjet"  # "inkjet" or "laser"
    head_technology: str = "piezo"  # "piezo", "thermal", or "laser"
    media_default: str = "iso_a4_210x297mm"
    supported_formats: List[str] = field(default_factory=list)
    markers: List[Dict[str, Any]] = field(default_factory=list)
    endpoint_path: str = "/ipp/print"
    pwg_resolutions_supported: List[int] = field(default_factory=list)
    pwg_document_types_supported: List[str] = field(default_factory=list)
    print_color_modes_supported: List[str] = field(default_factory=list)
    media_types_supported: List[str] = field(default_factory=list)
    tank_count: int = 4
    ink_profile: str = "cmyk"

    @property
    def has_channel_telemetry(self) -> bool:
        """Returns True if individual monochrome and color counters are both exposed."""
        return self.monochrome_pages is not None and self.color_pages is not None

    @property
    def is_media_empty(self) -> bool:
        return any("media-empty" in str(r).lower() for r in self.state_reasons)


class _TextExtractor(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts: List[str] = []
        self._skip = False

    def handle_starttag(self, tag, attrs):
        if tag.lower() in ("script", "style"):
            self._skip = True

    def handle_endtag(self, tag):
        if tag.lower() in ("script", "style"):
            self._skip = False
        if tag.lower() in ("tr", "div", "p", "li", "td", "th", "br"):
            self.parts.append(" ")

    def handle_data(self, data):
        if not self._skip and data:
            self.parts.append(data)

    def get_text(self) -> str:
        return " ".join(" ".join(self.parts).split())


def _html_to_text(raw_content: str) -> str:
    parser = _TextExtractor()
    try:
        parser.feed(raw_content)
        parser.close()
        return parser.get_text()
    except Exception:
        return re.sub(r"<[^>]+>", " ", raw_content)


def _as_list(value: Any) -> List[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def _classify_printer_hardware(model_or_text: str, marker_types: List[str]) -> Tuple[str, str]:
    """Determines (printer_kind, head_technology) from model string and RFC 3805 marker types."""
    low = (model_or_text or "").lower()
    mt_low = " ".join(str(m).lower() for m in marker_types)

    if "toner" in mt_low or "fuser" in mt_low or any(k in low for k in ("laserjet", "color laser", "hl-l", "mfc-l", "ecosys", "phaser", "versalink")):
        return "laser", "laser"

    if any(k in low for k in ("canon", "megatank", "pixma", "maxify", "hp ", "smart tank", "deskjet", "officejet", "envy", "productusagedyn", "lexmark")):
        return "inkjet", "thermal"

    return "inkjet", "piezo"


def _parse_pwg_resolutions(raw_res: Any) -> List[int]:
    """Extracts unique integer DPI values from IPP pwg-raster-document-resolution-supported."""
    out: List[int] = []
    for item in _as_list(raw_res):
        if isinstance(item, (tuple, list)) and len(item) >= 3:
            x_res, _y_res, unit = int(item[0]), int(item[1]), int(item[2])
            dpi = x_res if unit == 3 else (int(round(x_res * 2.54)) if unit == 4 else x_res)
            if dpi > 0 and dpi not in out:
                out.append(dpi)
        elif isinstance(item, int) and item > 0 and item not in out:
            out.append(item)
    return sorted(out)


def _detect_ink_profile(model: str, markers: List[Dict[str, Any]]) -> Tuple[int, str]:
    """Detects 6-tank Claria / photo printers (CMY + BK + PB + GY) vs standard 4-tank CMYK."""
    low = (model or "").lower()
    six_tank_models = ("et-8550", "et-8500", "l8180", "l8160", "l8050", "l18050", "xp-15000", "xp-8700", "xp-8600", "xp-8500")
    if any(m in low for m in six_tank_models):
        return max(6, len(markers)), "cmyk6"

    ink_markers = [
        m for m in markers
        if "waste" not in str(m.get("type", "")).lower()
        and "maintenance" not in str(m.get("name", "")).lower()
    ]
    marker_names_low = " ".join(str(m.get("name", "")).lower() for m in ink_markers)
    has_photo_gray = any(tok in marker_names_low for tok in ("photo black", "gray", "grey", "pb", "gy", "matte black"))
    if len(ink_markers) >= 5 and (has_photo_gray or len(ink_markers) >= 6):
        return len(ink_markers), "cmyk6"
    return 4, "cmyk"


class UniversalPrinterClient:
    """Pure-Python driverless IPP, Web EWS & SNMP printer client supporting all major vendors."""

    def __init__(
        self,
        host: Optional[str] = None,
        port: Optional[int] = None,
        use_tls: Optional[bool] = None,
        timeout_sec: Optional[float] = None,
        preferred_media: Optional[str] = None,
        snmp_port: int = 161,
        preferred_format: Optional[str] = None,
        preferred_media_type: Optional[str] = None,
        preferred_ink_profile: Optional[str] = None,
    ):
        self.host = settings.printer.host if host is None else host
        self.port = settings.printer.port if port is None else int(port)
        self.use_tls = settings.printer.use_tls if use_tls is None else bool(use_tls)
        self.timeout = settings.printer.timeout_sec if timeout_sec is None else float(timeout_sec)
        self.preferred_media = getattr(settings.printer, "media_size", "auto") if preferred_media is None else preferred_media
        self.preferred_format = getattr(settings.printer, "preferred_format", "auto") if preferred_format is None else preferred_format
        self.preferred_media_type = getattr(settings.printer, "media_type", "auto") if preferred_media_type is None else preferred_media_type
        self.preferred_ink_profile = getattr(settings.printer, "ink_profile", "auto") if preferred_ink_profile is None else preferred_ink_profile
        self.snmp_port = snmp_port
        self._lock = asyncio.Lock()
        self.last_status: Optional[PrinterStatus] = None

    @property
    def is_busy(self) -> bool:
        return self._lock.locked()

    def configure(
        self,
        host: str,
        port: int = 631,
        use_tls: bool = True,
        preferred_media: str = "auto",
        preferred_format: Optional[str] = None,
        preferred_media_type: Optional[str] = None,
        preferred_ink_profile: Optional[str] = None,
    ) -> None:
        self.host = host.strip()
        self.port = int(port)
        self.use_tls = bool(use_tls)
        self.preferred_media = preferred_media
        if preferred_format is not None:
            self.preferred_format = preferred_format
        if preferred_media_type is not None:
            self.preferred_media_type = preferred_media_type
        if preferred_ink_profile is not None:
            self.preferred_ink_profile = preferred_ink_profile

    def get_ipp_uri(self) -> str:
        proto = "ipps" if self.use_tls else "ipp"
        return f"{proto}://{self.host}:{self.port}/ipp/print"

    def negotiate_format(self, supported_formats: Optional[List[str]] = None) -> str:
        """Selects the safest driverless document format supported by the target printer."""
        supported = {str(f).lower().strip() for f in (supported_formats or [])}
        pref = (self.preferred_format or "auto").lower().strip()
        if pref and pref != "auto":
            if not supported or pref in supported:
                return pref
        if not supported:
            return "image/pwg-raster"
        for candidate in FORMAT_PREFERENCE:
            if candidate in supported:
                return candidate
        return "image/png"

    def get_fallback_formats(self, primary_format: str, supported_formats: Optional[List[str]] = None) -> List[str]:
        """Returns alternative supported document formats to try if primary_format fails."""
        primary = (primary_format or "").lower().strip()
        supported = {str(f).lower().strip() for f in (supported_formats or [])}
        if supported:
            return [f for f in FORMAT_PREFERENCE if f != primary and f in supported]
        return [f for f in FORMAT_PREFERENCE if f != primary]

    def negotiate_pwg_dpi(self, supported_resolutions: Optional[List[int]] = None, preferred_dpi: int = 300) -> int:
        """Selects a DPI supported by the printer's pwg-raster-document-resolution-supported."""
        res_list = [int(r) for r in (supported_resolutions or []) if int(r) > 0]
        if not res_list:
            return int(preferred_dpi)
        if preferred_dpi in res_list:
            return int(preferred_dpi)
        for candidate in (300, 360, 600, 720, 180, 150, 200):
            if candidate in res_list:
                return candidate
        return min(res_list, key=lambda r: abs(r - preferred_dpi))

    def negotiate_pwg_force_rgb(self, mode: str, pwg_types_supported: Optional[List[str]] = None) -> bool:
        """Returns True if a black-only job must be encoded as sRGB because the printer lacks sgray_8."""
        if mode.lower().strip() not in ("k", "black", "bk"):
            return False
        types = {str(t).lower().strip() for t in (pwg_types_supported or [])}
        return bool(types and "sgray_8" not in types and "srgb_8" in types)

    def negotiate_color_mode(
        self,
        mode: str,
        print_color_modes_supported: Optional[List[str]] = None,
        force_rgb: bool = False,
    ) -> str:
        """Selects RFC 8011 print-color-mode compatible with the job payload and printer capabilities."""
        supported = {str(c).lower().strip() for c in (print_color_modes_supported or [])}
        if mode.lower().strip() in ("k", "black", "bk") and not force_rgb:
            if not supported or "monochrome" in supported:
                return "monochrome"
        if not supported or "color" in supported:
            return "color"
        if "auto" in supported:
            return "auto"
        return next(iter(supported))

    def negotiate_media_type(
        self,
        mode: str,
        media_types_supported: Optional[List[str]] = None,
    ) -> Optional[str]:
        """Selects optional IPP media-type hint (e.g. photographic-glossy for ET-8550 PB+GY paths)."""
        pref = (self.preferred_media_type or "auto").lower().strip()
        if pref and pref != "auto":
            return pref
        if mode.lower().strip() == "pb_gy":
            supported = [str(m).lower().strip() for m in (media_types_supported or [])]
            for candidate in ("photographic-glossy", "photographic-semi-gloss", "photographic-matte", "photographic"):
                if candidate in supported:
                    return candidate
        return None

    def negotiate_media(self, ipp_media_default: Optional[str] = None, ipp_media_supported: Optional[List[str]] = None) -> str:
        if self.preferred_media and self.preferred_media != "auto":
            return self.preferred_media
        supported = [str(m) for m in (ipp_media_supported or [])]
        if ipp_media_default in ("iso_a4_210x297mm", "na_letter_8.5x11in"):
            return str(ipp_media_default)
        if "iso_a4_210x297mm" in supported:
            return "iso_a4_210x297mm"
        if "na_letter_8.5x11in" in supported:
            return "na_letter_8.5x11in"
        return "iso_a4_210x297mm"

    async def get_status(self) -> PrinterStatus:
        """
        Queries IPP Get-Printer-Attributes, optional SNMPv1 MIB counters, and Vendor Web EWS
        page counters cleanly without external binaries.
        """
        if not self.host:
            return PrinterStatus(online=False, state="unconfigured", error_message="No printer configured.")

        if is_local_queue_target(self.host):
            local_info = await query_local_printer_status(self.host)
            if not local_info.get("online"):
                status = PrinterStatus(
                    online=False,
                    state=local_info.get("state", "offline"),
                    make_and_model=local_info.get("make_and_model"),
                    error_message=local_info.get("error_message", "Local USB queue offline."),
                )
                self.last_status = status
                return status

            status = PrinterStatus(
                online=True,
                total_pages=local_info.get("total_pages"),
                state=local_info.get("state", "idle"),
                make_and_model=local_info.get("make_and_model"),
                printer_kind=local_info.get("printer_kind", "inkjet"),
                head_technology=local_info.get("head_technology", "thermal"),
                media_default=local_info.get("media_default", "iso_a4_210x297mm"),
                supported_formats=local_info.get("supported_formats", ["application/pdf", "image/png"]),
                endpoint_path=self.host,
            )
            self.last_status = status
            return status

        ipp_res = await query_ipp_attributes(
            self.host,
            port=self.port,
            use_tls=self.use_tls,
            timeout_sec=min(4.0, self.timeout),
        )

        snmp_res = None
        if ipp_res.success and self.port != 60000 and self.snmp_port > 0:
            snmp_res = await query_snmp_printer_telemetry(
                self.host,
                port=self.snmp_port,
                timeout_sec=min(1.5, self.timeout),
            )

        ews_status: Optional[PrinterStatus] = None
        if self.port != 60000:
            ews_status = await self._probe_ews_counters()

        if not ipp_res.success and (ews_status is None or not ews_status.online):
            err = ipp_res.error or (ews_status.error_message if ews_status else "Printer unreachable")
            status = PrinterStatus(online=False, state="offline", error_message=err)
            self.last_status = status
            return status

        attrs = ipp_res.attributes if ipp_res.success else {}
        model = str(attrs.get("printer-make-and-model") or attrs.get("printer-name") or (ews_status.make_and_model if ews_status else "Inkjet Printer"))
        marker_names = [str(x) for x in _as_list(attrs.get("marker-names"))]
        marker_levels = _as_list(attrs.get("marker-levels"))
        marker_types = [str(x) for x in _as_list(attrs.get("marker-types"))]
        marker_colors = [str(x) for x in _as_list(attrs.get("marker-colors"))]

        markers: List[Dict[str, Any]] = []
        for idx, mname in enumerate(marker_names):
            lvl = marker_levels[idx] if idx < len(marker_levels) else None
            mtype = marker_types[idx] if idx < len(marker_types) else "unknown"
            mcolor = marker_colors[idx] if idx < len(marker_colors) else ""
            markers.append({"name": mname, "level": lvl, "type": mtype, "color": mcolor})

        printer_kind, head_tech = _classify_printer_hardware(model, marker_types)
        if ews_status and ews_status.head_technology in ("piezo", "thermal") and not ipp_res.success:
            head_tech = ews_status.head_technology

        state_code = attrs.get("printer-state")
        ipp_state = PRINTER_STATE_MAP.get(state_code, ews_status.state if ews_status else "idle")
        state_reasons = [str(r) for r in _as_list(attrs.get("printer-state-reasons")) if str(r) != "none"]

        supported_formats = [str(f) for f in _as_list(attrs.get("document-format-supported"))]
        media_supported = [str(m) for m in _as_list(attrs.get("media-supported"))]
        media_default = self.negotiate_media(attrs.get("media-default"), media_supported)
        pwg_resolutions_supported = _parse_pwg_resolutions(attrs.get("pwg-raster-document-resolution-supported"))
        pwg_document_types_supported = [str(t).lower() for t in _as_list(attrs.get("pwg-raster-document-type-supported"))]
        print_color_modes_supported = [str(c).lower() for c in _as_list(attrs.get("print-color-mode-supported"))]
        media_types_supported = [str(mt).lower() for mt in _as_list(attrs.get("media-type-supported"))]

        mono_pages = ews_status.monochrome_pages if ews_status else None
        color_pages = ews_status.color_pages if ews_status else None
        total_pages = ews_status.total_pages if ews_status else None

        ipp_total = attrs.get("printer-impressions-completed")
        if total_pages is None and isinstance(ipp_total, int) and ipp_total >= 0:
            total_pages = ipp_total

        if (total_pages is None or not markers) and self.port != 60000 and self.snmp_port > 0:
            if snmp_res is None:
                snmp_res = await query_snmp_printer_telemetry(
                    self.host,
                    port=self.snmp_port,
                    timeout_sec=min(1.5, self.timeout),
                )
            if total_pages is None and snmp_res.total_pages is not None:
                total_pages = snmp_res.total_pages
            if not markers and snmp_res.markers:
                markers = snmp_res.markers

        tank_count, detected_profile = _detect_ink_profile(model, markers)
        if self.preferred_ink_profile in ("cmyk", "cmyk6"):
            ink_profile = self.preferred_ink_profile
            if ink_profile == "cmyk6" and tank_count < 6:
                tank_count = 6
        else:
            ink_profile = detected_profile

        status = PrinterStatus(
            online=True,
            monochrome_pages=mono_pages,
            color_pages=color_pages,
            total_pages=total_pages,
            state=ipp_state,
            state_reasons=state_reasons,
            make_and_model=model,
            printer_kind=printer_kind,
            head_technology=head_tech,
            media_default=media_default,
            supported_formats=supported_formats,
            markers=markers,
            endpoint_path=ipp_res.endpoint_path if ipp_res.success else "/ipp/print",
            pwg_resolutions_supported=pwg_resolutions_supported,
            pwg_document_types_supported=pwg_document_types_supported,
            print_color_modes_supported=print_color_modes_supported,
            media_types_supported=media_types_supported,
            tank_count=tank_count,
            ink_profile=ink_profile,
        )
        self.last_status = status
        return status

    async def _probe_ews_counters(self) -> Optional[PrinterStatus]:
        schemes = ["https", "http"] if self.use_tls else ["http", "https"]
        host_target = f"{self.host}:{self.port}" if self.port not in (80, 443, 631, 60000) else self.host
        sem = asyncio.Semaphore(3)
        timeout = min(2.0, self.timeout)

        async with httpx.AsyncClient(timeout=timeout, verify=False, follow_redirects=True) as client:
            for scheme in schemes:
                async def _fetch_ews(endpoint: str) -> Optional[PrinterStatus]:
                    url = f"{scheme}://{host_target}{endpoint}"
                    async with sem:
                        try:
                            resp = await client.get(url)
                            if resp.status_code == 200:
                                parsed = self._parse_web_info(resp.text)
                                if parsed.online:
                                    return parsed
                        except Exception:
                            pass
                    return None

                results = await asyncio.gather(*(_fetch_ews(ep) for ep in VENDOR_EWS_ENDPOINTS), return_exceptions=True)
                for res in results:
                    if isinstance(res, PrinterStatus) and res.online:
                        return res
        return None

    def _parse_web_info(self, raw_content: str) -> PrinterStatus:
        """Parses HP XML and Epson/Canon/Brother HTML EWS pages for decoupled Mono vs Color counters."""
        mono_pages = None
        color_pages = None
        total_pages = None

        if "ProductUsageDyn" in raw_content or "pudyn:" in raw_content:
            hp_mono = re.search(r"<(?:pudyn:)?MonochromeImpressions>(\d+)</(?:pudyn:)?MonochromeImpressions>", raw_content)
            hp_color = re.search(r"<(?:pudyn:)?ColorImpressions>(\d+)</(?:pudyn:)?ColorImpressions>", raw_content)
            hp_total = re.search(r"<(?:pudyn:)?TotalImpressions>(\d+)</(?:pudyn:)?TotalImpressions>", raw_content)
            if hp_mono:
                mono_pages = int(hp_mono.group(1))
            if hp_color:
                color_pages = int(hp_color.group(1))
            if hp_total:
                total_pages = int(hp_total.group(1))

        text = _html_to_text(raw_content)

        if mono_pages is None:
            m = re.search(
                r"(?:Monochrome|B/?W|Black\s*(?:&|and)\s*White|Pagine\s*(?:in\s*)?bianco\s*e\s*nero|Monocromatic[oe]|B\s*e\s*N)(?:\s*(?:Pages?|Impressions?))?\s*[:=]?\s*(\d+)",
                text,
                re.I,
            )
            if m:
                mono_pages = int(m.group(1))

        if color_pages is None:
            m = re.search(
                r"(?:Colou?r|Pagine\s*a\s*colori|Color[ei])(?:\s*(?:Pages?|Impressions?))?\s*[:=]?\s*(\d+)",
                text,
                re.I,
            )
            if m:
                color_pages = int(m.group(1))

        if total_pages is None:
            m = re.search(
                r"(?:Total\s*(?:Number\s*of\s*)?Pages?(?:\s*Printed)?|Total\s*Impressions|Pagine\s*totali|Totale\s*pagine|Numero\s*totale\s*di\s*pagine|Page\s*Counter)\s*[:=]?\s*(\d+)",
                text,
                re.I,
            )
            if m:
                total_pages = int(m.group(1))

        if total_pages is None and mono_pages is not None and color_pages is not None:
            total_pages = mono_pages + color_pages

        has_telemetry = any(v is not None for v in (mono_pages, color_pages, total_pages))
        status_match = re.search(r"Printer\s*Status\s*[:=]?\s*(Available|Idle|Ready|Printing|Pronto|Disponibile)", text, re.I)
        is_known_printer = bool(re.search(r"(?:ET-\d+|EcoTank|MegaTank|Smart\s*Tank|PIXMA|ProductUsageDyn|Product\s*Status)", raw_content, re.I))

        if not has_telemetry and not status_match and not is_known_printer:
            return PrinterStatus(
                online=False,
                state="unknown",
                error_message="Web endpoint responded without recognizable printer telemetry",
            )

        content_lower = raw_content.lower()
        make_and_model = "Inkjet Printer"
        if "epson" in content_lower or "ecotank" in content_lower or "et-" in content_lower:
            make_and_model = "Epson EcoTank Series"
        elif "brother" in content_lower or "dcp-" in content_lower or "mfc-" in content_lower:
            make_and_model = "Brother Inkvestment Series"
        elif "canon" in content_lower or "megatank" in content_lower or "pixma" in content_lower or "maxify" in content_lower:
            make_and_model = "Canon MegaTank Series"
        elif "hp" in content_lower or "smart tank" in content_lower or "productusagedyn" in content_lower or "deskjet" in content_lower:
            make_and_model = "HP Smart Tank Series"

        kind, head_tech = _classify_printer_hardware(make_and_model + " " + content_lower[:300], [])
        state = "printing" if (status_match and "print" in status_match.group(1).lower()) else "idle"

        return PrinterStatus(
            online=True,
            monochrome_pages=mono_pages,
            color_pages=color_pages,
            total_pages=total_pages,
            state=state,
            make_and_model=make_and_model,
            printer_kind=kind,
            head_technology=head_tech,
        )

    async def execute_purge_payload(
        self,
        payload: bytes,
        doc_format: str,
        mode: str,
        media: str = "iso_a4_210x297mm",
        endpoint_path: str = "/ipp/print",
        color_mode_override: Optional[str] = None,
        media_type: Optional[str] = None,
    ) -> PurgeJobResult:
        """Atomically dispatches a compiled purge job over pure-Python IPP."""
        if not self.host:
            return PurgeJobResult(status=PurgeJobStatus.REJECTED, message="No printer configured.")

        if self._lock.locked():
            return PurgeJobResult(status=PurgeJobStatus.BUSY, message="Printer client is currently busy executing another job.")

        color_mode = color_mode_override or ("monochrome" if mode == "k" else "color")
        async with self._lock:
            if is_local_queue_target(self.host):
                ok, msg = await send_local_print_job(
                    host=self.host,
                    payload=payload,
                    doc_format=doc_format,
                    color_mode=color_mode,
                )
                if ok:
                    return PurgeJobResult(status=PurgeJobStatus.ACCEPTED, message=msg)
                return PurgeJobResult(status=PurgeJobStatus.ERROR, message=msg)

            ok, msg = await send_ipp_print_job(
                host=self.host,
                port=self.port,
                payload=payload,
                doc_format=doc_format,
                media=media,
                color_mode=color_mode,
                use_tls=self.use_tls,
                endpoint_path=endpoint_path,
                media_type=media_type,
            )
            if ok:
                return PurgeJobResult(status=PurgeJobStatus.ACCEPTED, message=msg)
            return PurgeJobResult(status=PurgeJobStatus.ERROR, message=msg)

    async def execute_purge_job(self, file_path: Path, mode: str) -> PurgeJobResult:
        """Compatibility wrapper submitting a pre-compiled PWG/PNG/PDF file over pure-Python IPP."""
        if self._lock.locked():
            return PurgeJobResult(status=PurgeJobStatus.BUSY, message="Printer client is currently busy executing another job.")
        if not file_path.exists():
            return PurgeJobResult(status=PurgeJobStatus.ERROR, message=f"Purge file not found: {file_path}")

        ext = file_path.suffix.lower()
        doc_format = "image/pwg-raster" if ext == ".pwg" else ("application/pdf" if ext == ".pdf" else "image/png")
        media = self.last_status.media_default if self.last_status else "iso_a4_210x297mm"
        endpoint_path = self.last_status.endpoint_path if self.last_status else "/ipp/print"
        return await self.execute_purge_payload(
            payload=file_path.read_bytes(),
            doc_format=doc_format,
            mode=mode,
            media=media,
            endpoint_path=endpoint_path,
        )


EpsonPrinterClient = UniversalPrinterClient

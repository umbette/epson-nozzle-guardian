import asyncio
import os
import logging
import re
import subprocess
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Optional
import httpx
from bs4 import BeautifulSoup

from .config import settings

logger = logging.getLogger("epson_guardian.printer")

IPP_PRINT_JOB_TEST = """{
  OPERATION Print-Job
  GROUP operation-attributes-tag
  ATTR charset attributes-charset utf-8
  ATTR natural-language attributes-natural-language en
  ATTR uri printer-uri $uri
  ATTR nameWithoutLanguage requesting-user-name "epson-guardian"
  ATTR nameWithoutLanguage job-name "Nozzle Guardian Purge"
  ATTR mimeMediaType document-format $document-format
  GROUP job-attributes-tag
  ATTR keyword media iso_a4_210x297mm
  ATTR keyword print-color-mode $print-color-mode
  ATTR keyword sides one-sided
  FILE $filename
}
"""


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
    error_message: Optional[str] = None

    @property
    def has_channel_telemetry(self) -> bool:
        """Returns True if individual channel counters (mono and color) are both exposed."""
        return self.monochrome_pages is not None and self.color_pages is not None


class EpsonPrinterClient:
    def __init__(self):
        self.host = settings.printer.host
        self.port = settings.printer.port
        self.timeout = settings.printer.timeout_sec
        self.use_tls = settings.printer.use_tls
        
        # Use /app/data inside docker, or fallback to project local data/ directory
        base_data = Path("/app/data") if Path("/app").is_dir() and os.access("/app", os.W_OK) else Path(__file__).resolve().parent.parent / "data"
        base_data.mkdir(parents=True, exist_ok=True)
        self.test_template_path = base_data / "print-job.test"
        self._ensure_test_template()
        self._lock = asyncio.Lock()

    @property
    def is_busy(self) -> bool:
        """Returns True if a print job is currently being dispatched or executing."""
        return self._lock.locked()

    async def execute_purge_job(self, file_path: Path, mode: str) -> PurgeJobResult:
        """
        Atomically executes a purge job, serializing dispatches across both background daemon
        and HTTP API routes to prevent overlapping print jobs on the printer's IPP queue.
        Returns a structured PurgeJobResult.
        """
        if self._lock.locked():
            logger.warning("Purge job execution rejected: printer client lock is held by another active job.")
            return PurgeJobResult(status=PurgeJobStatus.BUSY, message="Printer client is currently busy executing another job.")

        async with self._lock:
            success = await asyncio.to_thread(self.print_purge_file, file_path, mode=mode)
            if success:
                return PurgeJobResult(status=PurgeJobStatus.ACCEPTED, message=f"Print job for mode '{mode}' successfully dispatched to printer.")
            return PurgeJobResult(status=PurgeJobStatus.ERROR, message=f"IPP print job execution failed for {file_path.name}.")

    def _ensure_test_template(self) -> None:
        self.test_template_path.parent.mkdir(parents=True, exist_ok=True)
        self.test_template_path.write_text(IPP_PRINT_JOB_TEST, encoding="utf-8")

    def get_ipp_uri(self) -> str:
        proto = "ipps" if self.use_tls else "ipp"
        return f"{proto}://{self.host}:{self.port}/ipp/print"

    async def get_status(self) -> PrinterStatus:
        """
        Polls the printer's Web Config HTML endpoint to parse page counters.
        Follows redirects and disables SSL verification for self-signed certificates.
        """
        schemes = ["https", "http"] if self.use_tls else ["http", "https"]
        for scheme in schemes:
            url = f"{scheme}://{self.host}/PRESENTATION/HTML/TOP/PRTINFO.HTML"
            try:
                async with httpx.AsyncClient(timeout=self.timeout, verify=False, follow_redirects=True) as client:
                    resp = await client.get(url)
                    if resp.status_code == 200:
                        parsed = self._parse_web_info(resp.text)
                        if parsed.monochrome_pages is not None or parsed.total_pages is not None or parsed.online:
                            return parsed
                    else:
                        logger.warning(f"Web Config on {url} returned status {resp.status_code}")
            except Exception as e:
                logger.debug(f"Query {url} failed: {e}")

        # Fallback: check if IPP port is responsive asynchronously without blocking event loop
        return await asyncio.to_thread(self._check_ipp_status)

    def _parse_web_info(self, html: str) -> PrinterStatus:
        soup = BeautifulSoup(html, "html.parser")
        text = soup.get_text(separator=" ")

        mono_pages = None
        color_pages = None
        total_pages = None

        # Robust regexes capturing English, Italian, table formats, and abbreviations
        # e.g. "Monochrome : 350", "B&W : 350", "Pagine in bianco e nero : 350", "B/N : 350"
        mono_match = re.search(
            r"(?:Monochrome(?:\s*Pages?)?|B/?W|Black\s*&\s*White|Pagine\s*(?:in\s*)?bianco\s*e\s*nero|Monocromatic[oe]|B\s*e\s*N)\s*[:=]?\s*(\d+)",
            text, re.I
        )
        if mono_match:
            mono_pages = int(mono_match.group(1))

        # e.g. "Color : 85", "Colour : 85", "Pagine a colori : 85", "Colori : 85"
        color_match = re.search(
            r"(?:Colou?r(?:\s*Pages?)?|Pagine\s*a\s*colori|Color[ei])\s*[:=]?\s*(\d+)",
            text, re.I
        )
        if color_match:
            color_pages = int(color_match.group(1))

        # e.g. "Total Number of Pages", "Total Pages Printed", "Pagine totali", "Totale pagine"
        total_match = re.search(
            r"(?:Total\s*(?:Number\s*of\s*)?Pages?(?:\s*Printed)?|Pagine\s*totali|Totale\s*pagine|Numero\s*totale\s*di\s*pagine)\s*[:=]?\s*(\d+)",
            text, re.I
        )
        if total_match:
            total_pages = int(total_match.group(1))

        # Infer total if missing
        if total_pages is None and mono_pages is not None and color_pages is not None:
            total_pages = mono_pages + color_pages

        # Validate that we actually found meaningful printer telemetry
        has_telemetry = any(v is not None for v in (mono_pages, color_pages, total_pages))
        if not has_telemetry:
            # Did not find genuine printer telemetry: do not assume idle
            return PrinterStatus(
                online=False,
                monochrome_pages=None,
                color_pages=None,
                total_pages=None,
                state="unknown",
                error_message="Web endpoint responded with HTTP 200 but contained no recognizable printer telemetry"
            )

        return PrinterStatus(
            online=True,
            monochrome_pages=mono_pages,
            color_pages=color_pages,
            total_pages=total_pages,
            state="idle"
        )

    def _check_ipp_status(self) -> PrinterStatus:
        """Runs a minimal socket/ipptool probe with a strict 2-second timeout."""
        uri = self.get_ipp_uri()
        cmd = [
            "ipptool", "-v", "-t",
            uri,
            "/usr/share/cups/ipptool/get-printer-attributes.test"
        ]
        try:
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=2.0)
            if res.returncode == 0:
                return PrinterStatus(online=True, state="idle")
            return PrinterStatus(online=False, state="offline", error_message=res.stderr)
        except Exception as e:
            return PrinterStatus(online=False, state="offline", error_message=str(e))

    def print_purge_file(self, file_path: Path, mode: str) -> bool:
        """
        Dispatches a PWG-raster print file to the printer over IPP using ipptool.
        Explicitly sets print-color-mode to 'monochrome' or 'color' to prevent
        the printer from mixing color ink into black purges.
        """
        if not file_path.exists():
            logger.error(f"Purge file does not exist: {file_path}")
            return False

        mime_type = "image/pwg-raster" if file_path.suffix == ".pwg" else "image/png"
        uri = self.get_ipp_uri()
        color_mode = "monochrome" if mode == "k" else "color"

        cmd = [
            "ipptool",
            "-v",
            "-t",
            "-f", str(file_path),
            "-d", f"document-format={mime_type}",
            "-d", f"print-color-mode={color_mode}",
            uri,
            str(self.test_template_path)
        ]

        logger.info(f"Submitting {color_mode} print job to {uri}: {file_path.name} ({mime_type})")
        try:
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
            if res.returncode == 0:
                logger.info(f"Print job successfully accepted by printer: {res.stdout.splitlines()[-1] if res.stdout else 'OK'}")
                return True
            else:
                logger.error(f"ipptool print job failed (code {res.returncode}): {res.stderr}")
                return False
        except Exception as e:
            logger.error(f"Failed to execute ipptool: {e}")
            return False

"""
Local OS USB Spooler Backend (No Wi-Fi / Non-IPP Printers)
===========================================================
Supports USB-only printers lacking Wi-Fi and lacking IPP-over-USB firmware
(such as Canon PIXMA G2770 / G2010 / G1010 and Epson L3110 / L3210) when
connected via USB cable to a Linux, macOS, Raspberry Pi, or Windows PC running
the vendor OS print driver:
  - Linux / macOS: CUPS `lpstat` + `lp` (passes `canon cnijfilter2` / Gutenprint)
  - Windows: PowerShell + .NET `System.Drawing.Printing.PrintDocument` (PNG 1:1)

All subprocess invocations strictly use argument arrays (shell=False) with strict
queue-name validation.
"""

import asyncio
import json
import logging
import os
from pathlib import Path
import platform
import re
import tempfile
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger("nozzle_guardian.local_spooler")

LOCAL_PREFIX = "local://"
_SAFE_QUEUE_RE = re.compile(r"^[A-Za-z0-9_.\- /()+@:]{1,128}$")


def is_local_queue_target(host: Optional[str]) -> bool:
    return bool(host and host.strip().lower().startswith(LOCAL_PREFIX))


def extract_queue_name(host: str) -> str:
    raw = host.strip()
    if raw.lower().startswith(LOCAL_PREFIX):
        raw = raw[len(LOCAL_PREFIX) :]
    if not _SAFE_QUEUE_RE.match(raw):
        raise ValueError(f"Invalid local printer queue name: {host!r}")
    return raw


def _classify_local_model(name: str, uri_or_driver: str = "") -> Tuple[str, str]:
    combined = f"{name} {uri_or_driver}".lower()
    if any(k in combined for k in ("laserjet", "color laser", "hl-l", "mfc-l", "toner", "ecosys", "phaser")):
        return "laser", "laser"
    if any(k in combined for k in ("canon", "megatank", "pixma", "g2770", "g2010", "g1010", "g3010", "maxify", "hp ", "smart tank", "deskjet", "officejet", "envy")):
        return "inkjet", "thermal"
    return "inkjet", "piezo"


def _parse_lpstat_output(lpstat_p: str, lpstat_v: str) -> List[Dict[str, Any]]:
    uris: Dict[str, str] = {}
    for line in lpstat_v.splitlines():
        m = re.match(r"^device for\s+([^\s:]+):\s*(.+)$", line.strip())
        if m:
            uris[m.group(1)] = m.group(2).strip()

    printers: List[Dict[str, Any]] = []
    for line in lpstat_p.splitlines():
        m = re.match(r"^printer\s+([^\s]+)\s+(.+)$", line.strip())
        if not m:
            continue
        qname = m.group(1)
        desc = m.group(2)
        low_desc = desc.lower()
        state = "printing" if "printing" in low_desc else ("stopped" if "disabled" in low_desc else "idle")
        uri = uris.get(qname, "")
        kind, tech = _classify_local_model(qname, uri)
        display = uri.split("://", 1)[-1].split("?", 1)[0].replace("/", " ").replace("%20", " ") if "://" in uri else qname
        printers.append({
            "host": f"{LOCAL_PREFIX}{qname}",
            "port": 0,
            "use_tls": False,
            "endpoint_path": f"{LOCAL_PREFIX}{qname}",
            "make_and_model": display or qname,
            "queue_name": qname,
            "device_uri": uri,
            "state": state,
            "online": state != "stopped",
            "printer_kind": kind,
            "head_technology": tech,
            "media_default": "iso_a4_210x297mm",
            "supported_formats": ["application/pdf", "image/png"],
            "source": "usb-spooler",
        })
    return printers


def _parse_windows_printers_json(raw_json: str) -> List[Dict[str, Any]]:
    if not raw_json.strip():
        return []
    parsed = json.loads(raw_json)
    items = parsed if isinstance(parsed, list) else [parsed]
    out: List[Dict[str, Any]] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        name = str(item.get("Name") or "").strip()
        if not name:
            continue
        driver = str(item.get("DriverName") or "")
        port = str(item.get("PortName") or "")
        status_str = str(item.get("PrinterStatus") or "Normal").lower()
        if any(skip in name.lower() for skip in ("microsoft print to pdf", "microsoft xps", "onenote", "fax")):
            continue
        kind, tech = _classify_local_model(name, f"{driver} {port}")
        offline = any(w in status_str for w in ("offline", "error", "paperout"))
        out.append({
            "host": f"{LOCAL_PREFIX}{name}",
            "port": 0,
            "use_tls": False,
            "endpoint_path": f"{LOCAL_PREFIX}{name}",
            "make_and_model": f"{name} ({driver})" if driver else name,
            "queue_name": name,
            "device_uri": port,
            "state": "stopped" if offline else "idle",
            "online": not offline,
            "printer_kind": kind,
            "head_technology": tech,
            "media_default": "iso_a4_210x297mm",
            "supported_formats": ["image/png"],
            "source": "usb-spooler",
        })
    return out


async def _run_cmd(args: List[str], timeout_sec: float = 4.0) -> Tuple[int, str, str]:
    proc = await asyncio.create_subprocess_exec(
        *args,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    try:
        stdout_b, stderr_b = await asyncio.wait_for(proc.communicate(), timeout=timeout_sec)
        return (
            proc.returncode or 0,
            stdout_b.decode("utf-8", errors="replace"),
            stderr_b.decode("utf-8", errors="replace"),
        )
    except asyncio.TimeoutError:
        proc.kill()
        return 124, "", "Command timed out"


async def discover_local_printers() -> List[Dict[str, Any]]:
    """Lists locally attached USB / OS spooler queues (CUPS or Windows)."""
    sys_name = platform.system()
    try:
        if sys_name == "Windows":
            ps_cmd = "Get-Printer | Select-Object Name,DriverName,PortName,PrinterStatus | ConvertTo-Json"
            rc, out, _ = await _run_cmd(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps_cmd], timeout_sec=5.0)
            if rc == 0:
                return _parse_windows_printers_json(out)
            return []

        rc_p, out_p, _ = await _run_cmd(["lpstat", "-p"])
        if rc_p != 0:
            return []
        _, out_v, _ = await _run_cmd(["lpstat", "-v"])
        return _parse_lpstat_output(out_p, out_v)
    except FileNotFoundError:
        return []
    except Exception as exc:
        logger.debug(f"Local OS spooler discovery unavailable: {exc}")
        return []


async def query_local_printer_status(host: str) -> Dict[str, Any]:
    """Queries status and completed spool job count for `local://<queue_name>`."""
    try:
        qname = extract_queue_name(host)
    except ValueError as exc:
        return {"online": False, "state": "error", "error_message": str(exc)}

    sys_name = platform.system()
    printers = await discover_local_printers()
    match = next((p for p in printers if p.get("queue_name") == qname), None)
    if not match:
        return {
            "online": False,
            "state": "offline",
            "make_and_model": qname,
            "error_message": f"Local print queue '{qname}' not found or driver not installed.",
        }

    completed_jobs: Optional[int] = None
    if sys_name != "Windows":
        try:
            rc_c, out_c, _ = await _run_cmd(["lpstat", "-W", "completed", "-o", qname], timeout_sec=2.5)
            if rc_c == 0:
                completed_jobs = len([ln for ln in out_c.splitlines() if ln.strip()])
        except Exception:
            pass

    match["total_pages"] = completed_jobs
    return match


async def send_local_print_job(
    host: str,
    payload: bytes,
    doc_format: str = "image/png",
    color_mode: str = "color",
) -> Tuple[bool, str]:
    """Submits a PNG or PDF raster directly to the local OS print queue via CUPS `lp` or Windows PrintDocument."""
    try:
        qname = extract_queue_name(host)
    except ValueError as exc:
        return False, str(exc)

    ext = ".pdf" if "pdf" in doc_format.lower() else ".png"
    tmp_path: Optional[Path] = None
    try:
        with tempfile.NamedTemporaryFile(prefix="ng_purge_", suffix=ext, delete=False) as tf:
            tf.write(payload)
            tmp_path = Path(tf.name)

        if platform.system() == "Windows":
            if ext == ".pdf":
                return False, "Windows local spooler requires PNG payload."
            escaped_q = qname.replace("'", "''")
            escaped_f = str(tmp_path).replace("'", "''")
            ps_script = (
                "Add-Type -AssemblyName System.Drawing; "
                "$doc = New-Object System.Drawing.Printing.PrintDocument; "
                f"$doc.PrinterSettings.PrinterName = '{escaped_q}'; "
                f"$img = [System.Drawing.Image]::FromFile('{escaped_f}'); "
                "$doc.add_PrintPage({ param($s,$e) $e.Graphics.DrawImage($img, $e.PageBounds); $e.HasMorePages = $false }); "
                "$doc.Print(); $img.Dispose()"
            )
            rc, _out, err = await _run_cmd(
                ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps_script],
                timeout_sec=20.0,
            )
            if rc == 0:
                return True, f"Dispatched PNG via Windows spooler ({qname})"
            return False, f"Windows spooler error ({rc}): {err.strip() or 'print failed'}"

        cmd = ["lp", "-d", qname, "-o", "fit-to-page"]
        if color_mode == "monochrome":
            cmd.extend(["-o", "ColorModel=Gray"])
        cmd.append(str(tmp_path))

        rc, out, err = await _run_cmd(cmd, timeout_sec=15.0)
        if rc == 0:
            return True, f"Dispatched {ext.upper().lstrip('.')} via OS queue ({out.strip() or qname})"
        return False, f"Local queue '{qname}' failed ({rc}): {err.strip() or out.strip()}"
    except FileNotFoundError:
        return False, "OS print spooler binary (`lp` / PowerShell) not available."
    except Exception as exc:
        return False, f"Local spooler print failed: {exc}"
    finally:
        if tmp_path and tmp_path.exists():
            try:
                os.unlink(tmp_path)
            except OSError:
                pass

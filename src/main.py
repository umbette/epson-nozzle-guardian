import asyncio
import hmac
import logging
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Optional
from fastapi import FastAPI, HTTPException, Header, Request, UploadFile, File
from fastapi.responses import HTMLResponse, JSONResponse, Response
from pydantic import BaseModel, Field

from .config import settings
from .generator import PatternGenerator
from .ipp import discover_printers
from .notifier import Notifier
from .printer import EpsonPrinterClient, PrinterStatus, PurgeJobStatus
from .daemon import NozzleGuardianDaemon
from .scanner import analyze_nozzle_sheet

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("nozzle_guardian.main")

def _resolve_dir(target_path: str, fallback_path: str) -> str:
    p = Path(target_path)
    try:
        p.mkdir(parents=True, exist_ok=True)
        return str(p)
    except (PermissionError, OSError):
        fb = Path(fallback_path).resolve()
        fb.mkdir(parents=True, exist_ok=True)
        return str(fb)

generator = PatternGenerator(assets_dir=_resolve_dir("/app/assets", "assets"))
printer_client = EpsonPrinterClient()
notifier = Notifier(settings)
daemon = NozzleGuardianDaemon(state_dir=_resolve_dir("/app/data", "data"), alert_callback=notifier.send_alert)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Initializing Nozzle Guardian...")
    logger.info(f"Configured printer target: {settings.printer.host}:{settings.printer.port}")
    if not settings.allow_unauthenticated:
        logger.info("Security: API Key authentication is ENFORCED for all state and mutating routes.")
    else:
        logger.warning("Security Warning: Running with ALLOW_UNAUTHENTICATED=true.")
    try:
        generator.ensure_all_patterns()
        logger.info("Purge patterns verified and ready.")
    except Exception as e:
        logger.warning(f"Could not pre-render all patterns on startup: {e}")
        
    daemon.start()
    yield
    daemon.stop()
    logger.info("Shutting down Nozzle Guardian...")


app = FastAPI(
    title="Nozzle Guardian",
    description="Proactive anti-clogging, diagnostic inspection, and surgical single-nozzle purge daemon",
    version="1.2.0",
    lifespan=lifespan
)


_FORMAT_PRIORITY = ("image/pwg-raster", "application/pdf", "image/png", "image/jpeg")


def _select_doc_format(status: PrinterStatus) -> str:
    supported = [f.lower() for f in (status.supported_formats or [])]
    if not supported:
        return "image/pwg-raster"
    for fmt in _FORMAT_PRIORITY:
        if fmt in supported:
            return fmt
    return "image/pwg-raster"


def _assert_inkjet_ready(status: PrinterStatus) -> None:
    if not status.online:
        raise HTTPException(
            status_code=503,
            detail=f"Printer at {settings.printer.host} is offline or unreachable."
        )
    if getattr(status, "printer_kind", "inkjet") == "laser":
        raise HTTPException(
            status_code=422,
            detail="Laser/LED printer detected: anti-clogging ink purges are disabled to protect toner hardware."
        )
    if getattr(status, "is_media_empty", False):
        raise HTTPException(
            status_code=422,
            detail="Printer tray is empty (media-empty): load A4/Letter paper before triggering a purge."
        )


class PurgeResponse(BaseModel):
    success: bool
    mode: str
    message: str


class AutoPurgeToggleRequest(BaseModel):
    enabled: bool


class DaemonConfigRequest(BaseModel):
    printer_host: Optional[str] = None
    printer_port: Optional[int] = None
    use_tls: Optional[bool] = None
    inactivity_threshold_days: Optional[int] = None
    auto_purge_enabled: Optional[bool] = None
    media_size: Optional[str] = None


class SurgicalPurgeRequest(BaseModel):
    channel: str = Field(..., description="Target color channel ('cyan', 'magenta', 'yellow', 'black')")
    step: int = Field(..., ge=1, le=10, description="Target nozzle step number (1 to 10)")
    bursts: int = Field(default=5, ge=1, le=9, description="Number of acoustic micro-burst lines (1-9)")


def _verify_api_key(x_api_key: Optional[str], request: Optional[Request] = None):
    if settings.allow_unauthenticated:
        return
    if request is not None:
        client_ip = (request.client.host if request.client else "") or ""
        is_ha_ingress = bool(request.headers.get("X-Ingress-Path")) and (
            client_ip.startswith("172.30.32.") or client_ip in ("127.0.0.1", "::1")
        )
        if is_ha_ingress:
            return
    provided = x_api_key or ""
    if not provided and request is not None:
        auth_hdr = request.headers.get("Authorization", "")
        if auth_hdr.lower().startswith("bearer "):
            provided = auth_hdr[7:].strip()
    expected = settings.api_key or ""
    if not expected or not provided or not hmac.compare_digest(provided, expected):
        raise HTTPException(status_code=401, detail="Unauthorized: invalid or missing API key.")


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "auth_enforced": not settings.allow_unauthenticated
    }


@app.get("/status", response_model=PrinterStatus)
async def get_printer_status(request: Request, x_api_key: Optional[str] = Header(None)):
    _verify_api_key(x_api_key, request)
    client = daemon.printer_client if daemon.printer_client.host else printer_client
    status = await client.get_status()
    return status


@app.get("/discover")
async def discover_network_printers(request: Request, x_api_key: Optional[str] = Header(None)):
    _verify_api_key(x_api_key, request)
    candidates = [daemon.printer_client.host or settings.printer.host] if (daemon.printer_client.host or settings.printer.host) else []
    printers = await discover_printers(candidate_hosts=candidates)
    return {"printers": printers}


@app.get("/daemon/state")
async def get_daemon_state(request: Request, x_api_key: Optional[str] = Header(None)):
    _verify_api_key(x_api_key, request)
    return daemon.load_state()


@app.post("/daemon/config")
async def update_daemon_config(req: DaemonConfigRequest, request: Request, x_api_key: Optional[str] = Header(None)):
    _verify_api_key(x_api_key, request)
    state = daemon.update_config(
        printer_host=req.printer_host,
        printer_port=req.printer_port,
        use_tls=req.use_tls,
        inactivity_threshold_days=req.inactivity_threshold_days,
        auto_purge_enabled=req.auto_purge_enabled,
        media_size=req.media_size,
    )
    if req.printer_host is not None:
        printer_client.configure(
            host=state["printer_host"],
            port=state["printer_port"],
            use_tls=state["printer_use_tls"],
            preferred_media=state["media_size"],
        )
    return {"status": "ok", "state": state}


@app.post("/check")
async def check_and_purge_dormant(request: Request, force: bool = False, x_api_key: Optional[str] = Header(None)):
    """
    Runs an on-demand channel dormancy audit (ideal for iOS Shortcuts, Tasker, or laptop wake).
    Pass `?force=true` to execute even if background auto_purge_enabled is False.
    """
    _verify_api_key(x_api_key, request)
    if force:
        state = daemon.load_state()
        prev = state.get("auto_purge_enabled", False)
        state["auto_purge_enabled"] = True
        daemon.save_state(state)
        try:
            report = await daemon.evaluate_channels()
        finally:
            cur = daemon.load_state()
            cur["auto_purge_enabled"] = prev
            daemon.save_state(cur)
        return report
    return await daemon.evaluate_channels()


@app.get("/mobile/shortcut-config")
async def mobile_shortcut_config(request: Request, x_api_key: Optional[str] = Header(None)):
    """Returns ready-to-import iOS Apple Shortcuts / Android Tasker webhook endpoints."""
    _verify_api_key(x_api_key, request)
    base_url = str(request.base_url).rstrip("/")
    return {
        "check_dormancy_post": f"{base_url}/check?force=true",
        "purge_cmy_post": f"{base_url}/purge/cmy",
        "purge_k_post": f"{base_url}/purge/k",
        "purge_cmyk_post": f"{base_url}/purge/cmyk",
        "status_get": f"{base_url}/status",
        "required_header": "X-API-Key" if not settings.allow_unauthenticated else None,
    }


@app.post("/daemon/auto-purge")
async def toggle_auto_purge(req: AutoPurgeToggleRequest, request: Request, x_api_key: Optional[str] = Header(None)):
    _verify_api_key(x_api_key, request)
    state = daemon.load_state()
    state["auto_purge_enabled"] = req.enabled
    daemon.save_state(state)
    logger.info(f"User updated auto-purge policy: enabled={req.enabled}")
    return {"status": "ok", "auto_purge_enabled": req.enabled}


@app.post("/purge/surgical", response_model=PurgeResponse)
async def trigger_surgical_purge(req: SurgicalPurgeRequest, x_api_key: Optional[str] = Header(None)):
    """
    Triggers a surgical single-nozzle resuscitation pass.
    Fires micro-burst acoustic pressure pulses exclusively at the specified nozzle step coordinate.
    """
    _verify_api_key(x_api_key)

    if printer_client.is_busy:
        raise HTTPException(status_code=409, detail="A purge print job is already in progress.")

    status = await printer_client.get_status()
    _assert_inkjet_ready(status)

    head_tech = getattr(status, "head_technology", "piezo")
    effective_bursts = min(req.bursts, 3) if head_tech == "thermal" else req.bursts
    doc_format = _select_doc_format(status)
    media = getattr(status, "media_default", None) or "iso_a4_210x297mm"
    endpoint = getattr(status, "endpoint_path", None) or "/ipp/print"

    if doc_format == "image/pwg-raster":
        try:
            pwg_file = await asyncio.to_thread(generator.compile_surgical_pwg, req.channel, req.step, effective_bursts)
        except Exception as e:
            logger.error(f"Surgical pattern compilation failed: {e}")
            raise HTTPException(status_code=500, detail=f"Failed to compile surgical pattern: {e}")

        job_res = await printer_client.execute_purge_job(pwg_file, mode=f"surgical_{req.channel}_s{req.step}")
    else:
        try:
            payload, effective_bursts = await asyncio.to_thread(
                generator.compile_surgical_payload,
                req.channel,
                req.step,
                bursts=effective_bursts,
                head_technology=head_tech,
                doc_format=doc_format,
            )
        except Exception as e:
            logger.error(f"Surgical pattern compilation failed: {e}")
            raise HTTPException(status_code=500, detail=f"Failed to compile surgical pattern: {e}")

        job_res = await printer_client.execute_purge_payload(
            payload=payload,
            doc_format=doc_format,
            mode=f"surgical_{req.channel}_s{req.step}",
            media=media,
            endpoint_path=endpoint,
        )

    if job_res.status == PurgeJobStatus.BUSY:
        raise HTTPException(status_code=409, detail="A purge print job is already in progress.")
    if job_res.status != PurgeJobStatus.ACCEPTED:
        raise HTTPException(status_code=500, detail=f"Surgical print job execution failed: {job_res.message}")

    return PurgeResponse(
        success=True,
        mode=f"surgical_{req.channel}_{req.step}",
        message=f"Targeted acoustic resuscitation pass dispatched for {req.channel.upper()} nozzle step #{req.step} ({effective_bursts} micro-bursts, {doc_format})."
    )


@app.post("/purge/{mode}", response_model=PurgeResponse)
async def trigger_purge(mode: str, x_api_key: Optional[str] = Header(None)):
    _verify_api_key(x_api_key)

    mode = mode.lower()
    if mode not in ("cmy", "k", "cmyk"):
        raise HTTPException(status_code=400, detail="Invalid purge mode. Must be 'cmy', 'k', or 'cmyk'.")

    if printer_client.is_busy:
        raise HTTPException(status_code=409, detail="A purge print job is already in progress.")

    status = await printer_client.get_status()
    _assert_inkjet_ready(status)

    doc_format = _select_doc_format(status)
    media = getattr(status, "media_default", None) or "iso_a4_210x297mm"
    endpoint = getattr(status, "endpoint_path", None) or "/ipp/print"

    if doc_format == "image/pwg-raster":
        pwg_file = generator.get_pwg_path(mode)
        if not pwg_file.exists():
            logger.info(f"Compiling missing pattern for mode: {mode}")
            pwg_file = await asyncio.to_thread(generator.compile_pwg, mode)

        job_res = await printer_client.execute_purge_job(pwg_file, mode=mode)
        if job_res.status == PurgeJobStatus.BUSY:
            raise HTTPException(status_code=409, detail="A purge print job is already in progress.")

        if job_res.status == PurgeJobStatus.ERROR:
            png_file = generator.get_png_path(mode)
            if png_file.exists() and pwg_file.suffix == ".pwg":
                logger.info("Retrying with raw PNG format fallback...")
                job_res = await printer_client.execute_purge_job(png_file, mode=mode)
                if job_res.status == PurgeJobStatus.BUSY:
                    raise HTTPException(status_code=409, detail="A purge print job is already in progress.")
    else:
        payload = await asyncio.to_thread(generator.compile_payload, mode, doc_format=doc_format)
        job_res = await printer_client.execute_purge_payload(
            payload=payload,
            doc_format=doc_format,
            mode=mode,
            media=media,
            endpoint_path=endpoint,
        )
        if job_res.status == PurgeJobStatus.BUSY:
            raise HTTPException(status_code=409, detail="A purge print job is already in progress.")

    if job_res.status != PurgeJobStatus.ACCEPTED:
        raise HTTPException(status_code=500, detail=f"IPP print job execution failed: {job_res.message}")

    return PurgeResponse(
        success=True,
        mode=mode,
        message=f"Targeted purge pattern '{mode.upper()}' successfully dispatched to printer."
    )


@app.post("/scan/nozzle-health")
async def scan_nozzle_sheet(
    file: Optional[UploadFile] = File(None),
    x_api_key: Optional[str] = Header(None)
):
    """
    Parses an uploaded image or photo of the Nozzle Guardian test printout,
    detects missing steps via color continuity profiling, and returns actionable recommendations.
    """
    _verify_api_key(x_api_key)

    if not file:
        raise HTTPException(status_code=400, detail="No image file uploaded.")

    content = await file.read()
    if not content:
        raise HTTPException(status_code=400, detail="Uploaded file is empty.")

    report = await asyncio.to_thread(analyze_nozzle_sheet, content)
    return report


@app.get("/manifest.json")
async def pwa_manifest():
    """Returns standard PWA manifest for Add to Home Screen on mobile/desktop."""
    return JSONResponse(content={
        "name": "Nozzle Guardian™",
        "short_name": "NozzleGuardian",
        "start_url": "./",
        "display": "standalone",
        "background_color": "#0f172a",
        "theme_color": "#0f172a",
        "description": "Controllo, telemetria e diagnosi ottica ugelli stampante",
        "icons": [
            {
                "src": "data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>🖨️</text></svg>",
                "sizes": "192x192 512x512",
                "type": "image/svg+xml"
            }
        ]
    })


@app.get("/sw.js")
async def pwa_service_worker():
    """Lightweight service worker for standalone PWA capability."""
    return Response(
        content="self.addEventListener('fetch', (e) => {});",
        media_type="application/javascript"
    )


@app.get("/", response_class=HTMLResponse)
@app.get("/ui", response_class=HTMLResponse)
async def web_dashboard():
    """Renders the standalone, responsive Nozzle Guardian Web Dashboard."""
    api_token = settings.api_key or ""
    return HTMLResponse(content=HTML_DASHBOARD_TEMPLATE.replace("%%API_KEY%%", api_token))


HTML_DASHBOARD_TEMPLATE = """<!DOCTYPE html>
<html lang="it">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta name="theme-color" content="#0f172a">
  <meta name="apple-mobile-web-app-capable" content="yes">
  <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
  <link rel="manifest" href="./manifest.json">
  <title>Nozzle Guardian™ — Controllo & Diagnostica Ugelli</title>
  <style>
    :root {
      --bg: #0f172a;
      --card-bg: #1e293b;
      --card-border: #334155;
      --text: #f8fafc;
      --text-muted: #94a3b8;
      --primary: #38bdf8;
      --cyan: #06b6d4;
      --magenta: #ec4899;
      --yellow: #eab308;
      --black: #64748b;
      --success: #22c55e;
      --danger: #ef4444;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
    body { background: var(--bg); color: var(--text); padding: 20px; line-height: 1.5; }
    .container { max-width: 900px; margin: 0 auto; }
    header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; padding-bottom: 16px; border-bottom: 1px solid var(--card-border); }
    h1 { font-size: 1.5rem; display: flex; align-items: center; gap: 8px; }
    .badge { padding: 4px 10px; border-radius: 9999px; font-size: 0.8rem; font-weight: 600; }
    .badge-online { background: rgba(34, 197, 94, 0.2); color: var(--success); border: 1px solid var(--success); }
    .badge-offline { background: rgba(239, 68, 68, 0.2); color: var(--danger); border: 1px solid var(--danger); }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 16px; margin-bottom: 24px; }
    .card { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 12px; padding: 20px; }
    .card-title { font-size: 1rem; color: var(--text-muted); margin-bottom: 12px; text-transform: uppercase; letter-spacing: 0.5px; font-weight: 700; }
    .stat-val { font-size: 1.8rem; font-weight: 800; color: var(--text); margin-bottom: 4px; }
    .btn-group { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
    button { background: #2563eb; color: white; border: none; padding: 10px 16px; border-radius: 8px; cursor: pointer; font-weight: 600; font-size: 0.9rem; transition: all 0.2s; }
    button:hover { filter: brightness(1.15); transform: translateY(-1px); }
    button:active { transform: translateY(0); }
    button.btn-danger { background: var(--danger); }
    button.btn-cmy { background: #0891b2; }
    button.btn-k { background: #475569; }
    button.btn-scan { background: #8b5cf6; }
    
    /* Interactive Nozzle Matrix */
    .matrix-container { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-top: 16px; }
    .channel-col { background: #0f172a; padding: 12px; border-radius: 8px; border: 1px solid var(--card-border); text-align: center; }
    .channel-header { font-weight: 800; font-size: 0.85rem; margin-bottom: 8px; }
    .step-list { display: flex; flex-direction: column; gap: 4px; }
    .step-btn { background: #1e293b; border: 1px solid #334155; color: var(--text); padding: 4px; border-radius: 4px; font-size: 0.75rem; font-weight: 600; cursor: pointer; text-align: center; }
    .step-btn:hover { background: #334155; }
    .step-btn.selected { background: var(--danger); border-color: var(--danger); color: white; }
    .surgical-panel { margin-top: 16px; padding: 14px; background: rgba(239, 68, 68, 0.1); border: 1px solid rgba(239, 68, 68, 0.3); border-radius: 8px; display: none; }
    #toast { position: fixed; bottom: 20px; right: 20px; background: #0284c7; color: white; padding: 12px 20px; border-radius: 8px; font-weight: 600; display: none; box-shadow: 0 4px 12px rgba(0,0,0,0.5); z-index: 100; }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <h1>🖨️ Nozzle Guardian™ <span style="font-size: 0.8rem; color: var(--primary);">v1.2</span></h1>
      <span id="printerStatusBadge" class="badge badge-offline">Caricamento...</span>
    </header>

    <div class="grid">
      <!-- Status Card -->
      <div class="card">
        <div class="card-title">Stato Telemetria Hardware</div>
        <div id="printerModel" style="font-weight: 600; margin-bottom: 4px;">Connessione in corso...</div>
        <div id="printerMeta" style="font-size: 0.78rem; color: var(--text-muted); margin-bottom: 8px;">Tecnologia: - | Formato: -</div>
        <div style="font-size: 0.85rem; color: var(--text-muted); margin-bottom: 12px;">
          Pagine B/N: <strong id="pagesMono">-</strong> | Pagine Colori: <strong id="pagesColor">-</strong> | Totali: <strong id="pagesTotal">-</strong>
        </div>
        <div class="card-title" style="margin-top: 14px;">Spurghi Preventivi Standard</div>
        <div class="btn-group">
          <button onclick="triggerPurge('cmyk')">Spurgo Completo (CMYK)</button>
          <button class="btn-cmy" onclick="triggerPurge('cmy')">Solo Colori (CMY)</button>
          <button class="btn-k" onclick="triggerPurge('k')">Solo Nero (K)</button>
          <button style="background:#334155;" onclick="discoverPrintersUi()">🔎 Scopri LAN/USB</button>
        </div>
        <div id="discoverList" style="margin-top:10px; font-size:0.8rem; color:var(--text-muted); display:none;"></div>
        <div style="display:flex; gap:6px; margin-top:10px; flex-wrap:wrap; align-items:center;">
          <input id="cfgHost" type="text" placeholder="IP / hostname stampante" style="flex:1; min-width:140px; padding:7px 10px; border-radius:6px; border:1px solid var(--card-border); background:#0f172a; color:var(--text); font-size:0.8rem;">
          <input id="cfgPort" type="number" value="631" style="width:72px; padding:7px 8px; border-radius:6px; border:1px solid var(--card-border); background:#0f172a; color:var(--text); font-size:0.8rem;">
          <button style="background:#334155; padding:7px 12px; font-size:0.8rem;" onclick="savePrinterCfg()">Salva IP</button>
          <button style="background:#0284c7; padding:7px 12px; font-size:0.8rem;" onclick="runSmartCheck()">⏱️ Check Inattività</button>
        </div>
      </div>

      <!-- AI Camera Scanner Card -->
      <div class="card">
        <div class="card-title">Diagnostica & Scanner Fotocamera</div>
        <p style="font-size: 0.85rem; color: var(--text-muted); margin-bottom: 12px;">
          Inquadra la scaletta a gradini stampata con il tuo smartphone. L'analisi ottica rileva automaticamente eventuali gradini mancanti.
        </p>
        <input type="file" id="scanInput" accept="image/*" capture="environment" style="display:none" onchange="handleImageUpload(event)">
        <button class="btn-scan" onclick="document.getElementById('scanInput').click()">📸 Scansiona con Fotocamera</button>
        <div id="scanReport" style="margin-top: 12px; font-size: 0.85rem; display: none; padding: 10px; border-radius: 6px; background: #0f172a;"></div>
      </div>
    </div>

    <!-- Interactive Surgical Strike Matrix -->
    <div class="card">
      <div class="card-title">Matrice Nozzle & Spurgo Chirurgico a Risonanza Acustica</div>
      <p style="font-size: 0.85rem; color: var(--text-muted);">
        Seleziona il gradino mancante notato sul foglio di prova per azionare <strong>esclusivamente il singolo cristallo piezoelettrico incriminato</strong> con micro-impulsi ad alta frequenza (zero inchiostro sprecato sugli altri ugelli).
      </p>

      <div class="matrix-container">
        <!-- Cyan -->
        <div class="channel-col" style="border-top: 3px solid var(--cyan);">
          <div class="channel-header" style="color: var(--cyan);">CIANO</div>
          <div class="step-list" id="col-cyan"></div>
        </div>
        <!-- Magenta -->
        <div class="channel-col" style="border-top: 3px solid var(--magenta);">
          <div class="channel-header" style="color: var(--magenta);">MAGENTA</div>
          <div class="step-list" id="col-magenta"></div>
        </div>
        <!-- Yellow -->
        <div class="channel-col" style="border-top: 3px solid var(--yellow);">
          <div class="channel-header" style="color: var(--yellow);">GIALLO</div>
          <div class="step-list" id="col-yellow"></div>
        </div>
        <!-- Black -->
        <div class="channel-col" style="border-top: 3px solid var(--black);">
          <div class="channel-header" style="color: #cbd5e1;">NERO (K)</div>
          <div class="step-list" id="col-black"></div>
        </div>
      </div>

      <div id="surgicalPanel" class="surgical-panel">
        <div style="font-weight: 700; color: #fca5a5; margin-bottom: 6px;" id="surgicalTargetText">Target selezionato</div>
        <p style="font-size: 0.8rem; color: var(--text-muted); margin-bottom: 10px;">
          Verrà inviato un treno di onde acustiche piezoelettriche mirate esclusivamente a disostruire il menisco dell'ugello selezionato.
        </p>
        <button class="btn-danger" id="surgicalFireBtn" onclick="fireSurgicalPurge()">🎯 Lancia Martello Acustico Mirato</button>
      </div>
    </div>
  </div>

  <div id="toast">Operazione completata con successo!</div>

  <script>
    const API_KEY = "%%API_KEY%%";
    let selectedChannel = null;
    let selectedStep = null;

    function authHeaders(extra = {}) {
      const h = { ...extra };
      if (API_KEY) h['x-api-key'] = API_KEY;
      return h;
    }

    function showToast(msg) {
      const t = document.getElementById('toast');
      t.innerText = msg;
      t.style.display = 'block';
      setTimeout(() => { t.style.display = 'none'; }, 4000);
    }

    async function loadStatus() {
      try {
        const res = await fetch('./status', { headers: authHeaders() });
        const data = await res.json();
        const badge = document.getElementById('printerStatusBadge');
        if (data.online) {
          badge.className = 'badge badge-online';
          badge.innerText = '🟢 Online (' + data.state + ')';
        } else {
          badge.className = 'badge badge-offline';
          badge.innerText = '🔴 Offline';
        }
        document.getElementById('printerModel').innerText = data.make_and_model || 'Stampante IPP';
        document.getElementById('printerMeta').innerText =
          `Tecnologia: ${(data.printer_kind || 'inkjet').toUpperCase()} (${data.head_technology || 'piezo'}) | Carta: ${data.media_default || 'iso_a4_210x297mm'}`;
        document.getElementById('pagesMono').innerText = data.monochrome_pages ?? 'N/D';
        document.getElementById('pagesColor').innerText = data.color_pages ?? 'N/D';
        document.getElementById('pagesTotal').innerText = data.total_pages ?? 'N/D';
        const stRes = await fetch('./daemon/state', { headers: authHeaders() });
        if (stRes.ok) {
          const stData = await stRes.json();
          const hEl = document.getElementById('cfgHost');
          const pEl = document.getElementById('cfgPort');
          if (hEl && !hEl.value && stData.printer_host) hEl.value = stData.printer_host;
          if (pEl && stData.printer_port) pEl.value = stData.printer_port;
        }
      } catch (e) {
        document.getElementById('printerStatusBadge').innerText = '⚠️ Connessione Fallita';
      }
    }

    async function savePrinterCfg() {
      const host = (document.getElementById('cfgHost').value || '').trim();
      const port = parseInt(document.getElementById('cfgPort').value || '631', 10);
      const res = await fetch('./daemon/config', {
        method: 'POST',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ printer_host: host, printer_port: port })
      });
      if (res.ok) {
        showToast('✅ Stampante salvata: ' + host + ':' + port);
        loadStatus();
      }
    }

    async function runSmartCheck() {
      const res = await fetch('./check?force=true', { method: 'POST', headers: authHeaders() });
      const data = await res.json();
      if (res.ok) {
        const actions = (data.actions || []).length ? data.actions.join(', ').toUpperCase() : 'nessuno spurgo necessario';
        showToast('⏱️ Audit completato: ' + actions);
      } else {
        showToast('❌ Errore check: ' + (data.detail || 'fallito'));
      }
    }

    async function discoverPrintersUi() {
      const box = document.getElementById('discoverList');
      box.style.display = 'block';
      box.innerText = '🔎 Scansione mDNS / ipp-usb in corso...';
      try {
        const res = await fetch('./discover', { headers: authHeaders() });
        const data = await res.json();
        const list = data.printers || [];
        if (!list.length) {
          box.innerText = 'Nessuna stampante IPP/USB rilevata automaticamente. Inserisci IP o "local://NomeCoda" per stampanti solo USB.';
          return;
        }
        box.innerHTML = list.map(p => {
          const safeHost = String(p.host || '').replace(/"/g, '&quot;');
          const safePort = Number(p.port || 0);
          return `<div style="margin-top:4px;"><button type="button" style="padding:4px 8px; font-size:0.75rem; background:#1e293b; border:1px solid #38bdf8;" onclick="pickPrinter('${safeHost}', ${safePort})">Usa: <strong>${p.make_and_model}</strong> (${p.source || 'ipp'}, ${p.head_technology})</button></div>`;
        }).join('');
      } catch (e) {
        box.innerText = '⚠️ Discovery non disponibile.';
      }
    }

    function pickPrinter(host, port) {
      document.getElementById('cfgHost').value = host;
      document.getElementById('cfgPort').value = port;
      savePrinterCfg();
    }

    function initMatrix() {
      const channels = ['cyan', 'magenta', 'yellow', 'black'];
      channels.forEach(ch => {
        const col = document.getElementById('col-' + ch);
        col.innerHTML = '';
        for (let i = 1; i <= 10; i++) {
          const btn = document.createElement('button');
          btn.className = 'step-btn';
          btn.id = `step-${ch}-${i}`;
          btn.innerText = `Ugello #${i}`;
          btn.onclick = () => selectNozzle(ch, i);
          col.appendChild(btn);
        }
      });
    }

    function selectNozzle(ch, step) {
      document.querySelectorAll('.step-btn').forEach(b => b.classList.remove('selected'));
      selectedChannel = ch;
      selectedStep = step;

      const btn = document.getElementById(`step-${ch}-${step}`);
      if (btn) btn.classList.add('selected');

      const panel = document.getElementById('surgicalPanel');
      panel.style.display = 'block';
      document.getElementById('surgicalTargetText').innerText = `Bersaglio Selezionato: Canale ${ch.toUpperCase()} — Ugello #${step}`;
    }

    async function fireSurgicalPurge() {
      if (!selectedChannel || !selectedStep) return;
      const fireBtn = document.getElementById('surgicalFireBtn');
      fireBtn.disabled = true;
      fireBtn.innerText = '⏳ Esecuzione in corso...';
      try {
        const res = await fetch('./purge/surgical', {
          method: 'POST',
          headers: authHeaders({ 'Content-Type': 'application/json' }),
          body: JSON.stringify({ channel: selectedChannel, step: selectedStep, bursts: 5 })
        });
        const data = await res.json();
        if (res.ok) {
          showToast(`✅ Impulso acustico inviato a ${selectedChannel.toUpperCase()} #${selectedStep}!`);
        } else {
          showToast('❌ Errore: ' + (data.detail || 'Operazione fallita'));
        }
      } catch (e) {
        showToast('❌ Errore di rete durante lo spurgo chirurgico.');
      } finally {
        fireBtn.disabled = false;
        fireBtn.innerText = '🎯 Lancia Martello Acustico Mirato';
      }
    }

    async function triggerPurge(mode) {
      try {
        showToast(`🖨️ Invio lavoro di spurgo '${mode.toUpperCase()}'...`);
        const res = await fetch(`./purge/${mode}`, {
          method: 'POST',
          headers: authHeaders()
        });
        const data = await res.json();
        if (res.ok) {
          showToast(`✅ Micro-stampa '${mode.toUpperCase()}' inviata con successo!`);
        } else {
          showToast('❌ Errore: ' + (data.detail || 'Spurgo fallito'));
        }
      } catch (e) {
        showToast('❌ Errore di connessione con il demone.');
      }
    }

    async function handleImageUpload(e) {
      const file = e.target.files[0];
      if (!file) return;
      const rep = document.getElementById('scanReport');
      rep.style.display = 'block';
      rep.innerHTML = '🔍 Analisi ottica fotometrica in corso...';

      const formData = new FormData();
      formData.append('file', file);

      try {
        const res = await fetch('./scan/nozzle-health', {
          method: 'POST',
          headers: authHeaders(),
          body: formData
        });
        const data = await res.json();
        if (data.success) {
          let html = `<strong>Salute Complessiva Ugelli: ${data.overall_health_pct}%</strong><br>`;
          if (data.target_channel && data.target_step) {
            html += `<span style="color: #f87171;">⚠️ Rilevato gradino mancante su <strong>${data.target_channel.toUpperCase()} #${data.target_step}</strong>.</span>`;
            selectNozzle(data.target_channel, data.target_step);
          } else {
            html += `<span style="color: #4ade80;">✅ Tutti i 40 segmenti analizzati risultano continui e puliti!</span>`;
          }
          rep.innerHTML = html;
        } else {
          rep.innerHTML = '⚠️ Analisi non riuscita: ' + (data.error || 'Immagine non chiara');
        }
      } catch (err) {
        rep.innerHTML = '❌ Errore durante l\\'upload dell\\'immagine.';
      }
    }

    initMatrix();
    loadStatus();
    setInterval(loadStatus, 15000);

    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.register('./sw.js').catch(() => {});
    }
  </script>
</body>
</html>"""

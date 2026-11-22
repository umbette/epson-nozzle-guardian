import asyncio
import logging
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Optional
from fastapi import FastAPI, HTTPException, Header
from pydantic import BaseModel

from .config import settings
from .generator import PatternGenerator
from .printer import EpsonPrinterClient, PrinterStatus
from .daemon import NozzleGuardianDaemon

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
daemon = NozzleGuardianDaemon(state_dir=_resolve_dir("/app/data", "data"))


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
    description="Proactive anti-clogging and targeted purge daemon for EcoTank and inkjet printers",
    version="1.0.0",
    lifespan=lifespan
)


class PurgeResponse(BaseModel):
    success: bool
    mode: str
    message: str


class AutoPurgeToggleRequest(BaseModel):
    enabled: bool


def _verify_api_key(x_api_key: Optional[str]):
    if not settings.allow_unauthenticated:
        if not settings.api_key or x_api_key != settings.api_key:
            raise HTTPException(status_code=401, detail="Unauthorized: invalid or missing API key.")


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "printer_host": settings.printer.host,
        "auth_enforced": not settings.allow_unauthenticated
    }


@app.get("/status", response_model=PrinterStatus)
async def get_printer_status(x_api_key: Optional[str] = Header(None)):
    _verify_api_key(x_api_key)
    status = await printer_client.get_status()
    return status


@app.get("/daemon/state")
async def get_daemon_state(x_api_key: Optional[str] = Header(None)):
    _verify_api_key(x_api_key)
    return daemon.load_state()


@app.post("/daemon/auto-purge")
async def toggle_auto_purge(req: AutoPurgeToggleRequest, x_api_key: Optional[str] = Header(None)):
    _verify_api_key(x_api_key)
    state = daemon.load_state()
    state["auto_purge_enabled"] = req.enabled
    daemon.save_state(state)
    logger.info(f"User updated auto-purge policy: enabled={req.enabled}")
    return {"status": "ok", "auto_purge_enabled": req.enabled}


@app.post("/purge/{mode}", response_model=PurgeResponse)
async def trigger_purge(mode: str, x_api_key: Optional[str] = Header(None)):
    _verify_api_key(x_api_key)

    mode = mode.lower()
    if mode not in ("cmy", "k", "cmyk"):
        raise HTTPException(status_code=400, detail="Invalid purge mode. Must be 'cmy', 'k', or 'cmyk'.")

    if printer_client.is_busy:
        raise HTTPException(status_code=409, detail="A purge print job is already in progress.")

    # Check printer status first
    status = await printer_client.get_status()
    if not status.online:
        raise HTTPException(
            status_code=503,
            detail=f"Printer at {settings.printer.host} is offline or unreachable."
        )

    # Locate raster or compile if missing
    pwg_file = Path(f"/app/assets/purge_{mode}.pwg")
    if not pwg_file.exists():
        logger.info(f"Compiling missing pattern for mode: {mode}")
        pwg_file = await asyncio.to_thread(generator.compile_pwg, mode)

    success = await printer_client.execute_purge_job(pwg_file, mode=mode)
    if not success and printer_client.is_busy:
        raise HTTPException(status_code=409, detail="A purge print job is already in progress.")

    if not success:
        # Fallback to PNG if PWG failed or wasn't supported
        png_file = Path(f"/app/assets/purge_{mode}.png")
        if png_file.exists() and pwg_file.suffix == ".pwg":
            logger.info("Retrying with raw PNG format fallback...")
            success = await printer_client.execute_purge_job(png_file, mode=mode)

    if not success:
        raise HTTPException(status_code=500, detail="IPP print job execution failed.")

    return PurgeResponse(
        success=True,
        mode=mode,
        message=f"Targeted purge pattern '{mode.upper()}' successfully dispatched to printer."
    )

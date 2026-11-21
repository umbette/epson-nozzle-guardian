import logging
from contextlib import asynccontextmanager
from pathlib import Path
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from .config import settings
from .generator import PatternGenerator
from .printer import EpsonPrinterClient, PrinterStatus

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("epson_guardian.main")

generator = PatternGenerator(assets_dir="/app/assets")
printer_client = EpsonPrinterClient()


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Initializing Epson Nozzle Guardian...")
    logger.info(f"Configured printer target: {settings.printer.host}:{settings.printer.port}")
    try:
        generator.ensure_all_patterns()
        logger.info("Purge patterns verified and ready.")
    except Exception as e:
        logger.warning(f"Could not pre-render all patterns on startup: {e}")
    yield
    logger.info("Shutting down Epson Nozzle Guardian...")


app = FastAPI(
    title="Epson Nozzle Guardian",
    description="Proactive anti-clogging and targeted purge daemon for Epson EcoTank ET-2814",
    version="1.0.0",
    lifespan=lifespan
)


class PurgeResponse(BaseModel):
    success: bool
    mode: str
    message: str


@app.get("/health")
async def health():
    return {"status": "ok", "printer_host": settings.printer.host}


@app.get("/status", response_model=PrinterStatus)
async def get_printer_status():
    status = await printer_client.get_status()
    return status


@app.post("/purge/{mode}", response_model=PurgeResponse)
async def trigger_purge(mode: str):
    mode = mode.lower()
    if mode not in ("cmy", "k", "cmyk"):
        raise HTTPException(status_code=400, detail="Invalid purge mode. Must be 'cmy', 'k', or 'cmyk'.")

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
        pwg_file = generator.compile_pwg(mode)

    success = printer_client.print_purge_file(pwg_file, mode=mode)
    if not success:
        # Fallback to PNG if PWG failed or wasn't supported
        png_file = Path(f"/app/assets/purge_{mode}.png")
        if png_file.exists() and pwg_file.suffix == ".pwg":
            logger.info("Retrying with raw PNG format fallback...")
            success = printer_client.print_purge_file(png_file, mode=mode)

    if not success:
        raise HTTPException(status_code=500, detail="IPP print job execution failed.")

    return PurgeResponse(
        success=True,
        mode=mode,
        message=f"Targeted purge pattern '{mode.upper()}' successfully dispatched to printer."
    )

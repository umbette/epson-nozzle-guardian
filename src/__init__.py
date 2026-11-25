"""Nozzle Guardian: Driverless Pure-Python Inkjet Maintenance & Optical Inspection Engine"""

from .printer import (
    UniversalPrinterClient,
    EpsonPrinterClient,
    PrinterStatus,
    PurgeJobResult,
    PurgeJobStatus,
)
from .generator import PatternGenerator, PurgePatternGenerator
from .scanner import analyze_nozzle_sheet
from .daemon import NozzleGuardianDaemon
from .ipp import discover_printers

__version__ = "1.2.0"

__all__ = [
    "UniversalPrinterClient",
    "EpsonPrinterClient",
    "PrinterStatus",
    "PurgeJobResult",
    "PurgeJobStatus",
    "PatternGenerator",
    "PurgePatternGenerator",
    "analyze_nozzle_sheet",
    "NozzleGuardianDaemon",
    "discover_printers",
]

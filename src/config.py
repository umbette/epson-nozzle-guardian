import os
from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, Field
from pydantic_settings import BaseSettings


class PrinterConfig(BaseModel):
    host: str = "epson-printer.local"
    port: int = 631
    timeout_sec: float = 5.0
    inactivity_threshold_days: int = 5
    use_tls: bool = True


class PatternConfig(BaseModel):
    dpi: int = 300
    stripe_height_mm: float = 3.0
    stripe_spacing_mm: float = 1.0
    top_margin_mm: float = 10.0
    margin_side_mm: float = 15.0


class Settings(BaseSettings):
    printer: PrinterConfig = Field(default_factory=PrinterConfig)
    pattern: PatternConfig = Field(default_factory=PatternConfig)

    @classmethod
    def load(cls, config_path: str = "config.yaml") -> "Settings":
        data: dict[str, Any] = {}
        path = Path(config_path)
        if path.exists():
            with open(path, "r", encoding="utf-8") as f:
                loaded = yaml.safe_load(f)
                if isinstance(loaded, dict):
                    data = loaded

        # Override printer via env (supporting both PRINTER_* and legacy EPSON_*)
        printer_data = data.get("printer", {})
        
        host_env = os.getenv("PRINTER_HOST") or os.getenv("EPSON_HOST")
        if host_env:
            printer_data["host"] = host_env
            
        port_env = os.getenv("PRINTER_PORT") or os.getenv("EPSON_PORT")
        if port_env:
            printer_data["port"] = int(port_env)
            
        tls_env = os.getenv("PRINTER_USE_TLS") if "PRINTER_USE_TLS" in os.environ else os.getenv("EPSON_USE_TLS")
        if tls_env is not None:
            printer_data["use_tls"] = str(tls_env).lower() in ("true", "1", "yes")
            
        if "INACTIVITY_DAYS" in os.environ:
            printer_data["inactivity_threshold_days"] = int(os.environ["INACTIVITY_DAYS"])
            
        data["printer"] = printer_data

        return cls(**data)


settings = Settings.load()

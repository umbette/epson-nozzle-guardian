import os
import secrets
import sys
from pathlib import Path
from typing import Any, Optional


def get_app_data_dir() -> str:
    env_dir = os.getenv("DATA_DIR")
    if env_dir:
        return env_dir
    if getattr(sys, "frozen", False):
        if sys.platform == "win32":
            base = Path(os.getenv("LOCALAPPDATA") or (Path.home() / "AppData" / "Local"))
            return str(base / "NozzleGuardian" / "data")
        if sys.platform == "darwin":
            return str(Path.home() / "Library" / "Application Support" / "NozzleGuardian" / "data")
        return str(Path.home() / ".local" / "share" / "nozzle-guardian" / "data")
    return "/app/data"


def get_app_assets_dir() -> str:
    if getattr(sys, "frozen", False):
        return str(Path(get_app_data_dir()).parent / "assets")
    return "/app/assets"

import yaml
from pydantic import BaseModel, Field
from pydantic_settings import BaseSettings


class PrinterConfig(BaseModel):
    host: str = "epson-printer.local"
    port: int = 631
    timeout_sec: float = 5.0
    inactivity_threshold_days: int = 5
    use_tls: bool = True
    auto_purge_enabled: bool = False
    media_size: str = "auto"
    preferred_format: str = "auto"
    media_type: str = "auto"
    ink_profile: str = "auto"


class PatternConfig(BaseModel):
    dpi: int = 300
    stripe_height_mm: float = 3.0
    stripe_spacing_mm: float = 1.0
    top_margin_mm: float = 10.0
    margin_side_mm: float = 15.0


class NotificationsConfig(BaseModel):
    ntfy_url: str = ""
    webhook_url: str = ""
    ha_url: str = ""
    ha_token: str = ""
    ha_notify_service: str = "notify"
    dashboard_url: str = ""


class Settings(BaseSettings):
    printer: PrinterConfig = Field(default_factory=PrinterConfig)
    pattern: PatternConfig = Field(default_factory=PatternConfig)
    notifications: NotificationsConfig = Field(default_factory=NotificationsConfig)
    api_key: Optional[str] = None
    allow_unauthenticated: bool = False

    @classmethod
    def load(cls, config_path: str = "config.yaml", data_dir: Optional[str] = None) -> "Settings":
        data: dict[str, Any] = {}
        data_dir = data_dir or get_app_data_dir()
        path = Path(config_path)
        if path.exists():
            with open(path, "r", encoding="utf-8") as f:
                loaded = yaml.safe_load(f)
                if isinstance(loaded, dict):
                    data = loaded

        # Home Assistant Add-on options (/data/options.json)
        options_path = os.getenv("OPTIONS_PATH", "/data/options.json")
        ha_options: dict[str, Any] = {}
        if os.path.exists(options_path):
            try:
                import json
                with open(options_path, "r", encoding="utf-8") as f:
                    parsed_opt = json.load(f)
                    if isinstance(parsed_opt, dict):
                        ha_options = parsed_opt
            except Exception:
                pass

        # Override printer via HA Add-on options and env (supporting both PRINTER_* and legacy EPSON_*)
        printer_data = data.get("printer", {})
        if "printer_host" in ha_options and ha_options["printer_host"] is not None:
            printer_data["host"] = str(ha_options["printer_host"])
        if "printer_port" in ha_options and ha_options["printer_port"] is not None:
            printer_data["port"] = int(ha_options["printer_port"])
        if "printer_use_tls" in ha_options:
            printer_data["use_tls"] = bool(ha_options["printer_use_tls"])
        if "inactivity_threshold_days" in ha_options:
            printer_data["inactivity_threshold_days"] = int(ha_options["inactivity_threshold_days"])
        if "auto_purge_enabled" in ha_options:
            printer_data["auto_purge_enabled"] = bool(ha_options["auto_purge_enabled"])
        if "media_size" in ha_options and ha_options["media_size"]:
            printer_data["media_size"] = str(ha_options["media_size"])
        if "preferred_format" in ha_options and ha_options["preferred_format"]:
            printer_data["preferred_format"] = str(ha_options["preferred_format"])
        if "media_type" in ha_options and ha_options["media_type"]:
            printer_data["media_type"] = str(ha_options["media_type"])
        if "ink_profile" in ha_options and ha_options["ink_profile"]:
            printer_data["ink_profile"] = str(ha_options["ink_profile"])
        
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

        auto_purge_env = os.getenv("AUTO_PURGE_ENABLED")
        if auto_purge_env is not None:
            printer_data["auto_purge_enabled"] = str(auto_purge_env).lower() in ("true", "1", "yes")

        media_env = os.getenv("PRINTER_MEDIA")
        if media_env:
            printer_data["media_size"] = media_env

        fmt_env = os.getenv("PRINTER_FORMAT")
        if fmt_env:
            printer_data["preferred_format"] = fmt_env

        mtype_env = os.getenv("PRINTER_MEDIA_TYPE")
        if mtype_env:
            printer_data["media_type"] = mtype_env

        profile_env = os.getenv("PRINTER_INK_PROFILE")
        if profile_env:
            printer_data["ink_profile"] = profile_env

        data["printer"] = printer_data

        notif_data = data.get("notifications", {})
        if "ntfy_url" in ha_options and ha_options["ntfy_url"]:
            notif_data["ntfy_url"] = str(ha_options["ntfy_url"])
        if "webhook_url" in ha_options and ha_options["webhook_url"]:
            notif_data["webhook_url"] = str(ha_options["webhook_url"])
        if "notify_service" in ha_options and ha_options["notify_service"]:
            notif_data["ha_notify_service"] = str(ha_options["notify_service"]).replace("notify.", "")

        supervisor_token = os.getenv("SUPERVISOR_TOKEN")
        if supervisor_token:
            notif_data["ha_token"] = supervisor_token
            notif_data["ha_url"] = "http://supervisor/core"
        if os.getenv("NTFY_URL"):
            notif_data["ntfy_url"] = os.getenv("NTFY_URL", "").strip()
        if os.getenv("WEBHOOK_URL"):
            notif_data["webhook_url"] = os.getenv("WEBHOOK_URL", "").strip()
        if os.getenv("HASS_URL"):
            notif_data["ha_url"] = os.getenv("HASS_URL", "").strip()
        if os.getenv("HASS_TOKEN"):
            notif_data["ha_token"] = os.getenv("HASS_TOKEN", "").strip()
        if os.getenv("HASS_NOTIFY_SERVICE"):
            notif_data["ha_notify_service"] = os.getenv("HASS_NOTIFY_SERVICE", "").replace("notify.", "").strip()
        if os.getenv("DASHBOARD_PUBLIC_URL"):
            notif_data["dashboard_url"] = os.getenv("DASHBOARD_PUBLIC_URL", "").strip()
        data["notifications"] = notif_data

        allow_unauth = os.getenv("ALLOW_UNAUTHENTICATED", "").lower() in ("true", "1", "yes")
        data["allow_unauthenticated"] = allow_unauth

        api_key_env = os.getenv("API_KEY")
        if api_key_env:
            data["api_key"] = api_key_env
        elif allow_unauth:
            data["api_key"] = None
        else:
            # Fail-closed security default: load or create persistent secret key in data directory
            try:
                state_path = Path(data_dir)
                state_path.mkdir(parents=True, exist_ok=True)
            except (PermissionError, OSError):
                state_path = Path("data").resolve()
                state_path.mkdir(parents=True, exist_ok=True)

            secret_file = state_path / "api_key.secret"
            if secret_file.exists():
                try:
                    data["api_key"] = secret_file.read_text(encoding="utf-8").strip()
                except Exception:
                    data["api_key"] = secrets.token_hex(32)
            else:
                generated_key = secrets.token_hex(32)
                try:
                    secret_file.write_text(generated_key, encoding="utf-8")
                    secret_file.chmod(0o600)
                except Exception:
                    pass
                data["api_key"] = generated_key

        return cls(**data)


settings = Settings.load()

"""
Nozzle Guardian Desktop Companion (Windows / macOS / Linux)
============================================================
Runs the local FastAPI UI & dormancy daemon in the background (with optional
system tray icon if pystray is installed) AND executes an immediate wake/boot
catch-up check so printers stay hydrated even when the user's PC sleeps.
Provides 1-click user-level autostart (no terminal or admin UAC required):
  - Windows: HKCU Run registry key (or PyInstaller executable path)
  - macOS: ~/Library/LaunchAgents/com.umbette.nozzleguardian.plist
  - Linux Desktop: ~/.config/systemd/user/nozzle-guardian-desktop.service
"""

import asyncio
import logging
import os
from pathlib import Path
import platform
import subprocess
import sys
import threading
import time
import webbrowser
from typing import Any, Dict, List, Optional

import uvicorn

from .config import get_app_data_dir
from .daemon import NozzleGuardianDaemon
from .main import app, daemon as main_daemon

logger = logging.getLogger("nozzle_guardian.desktop")


def _configure_frozen_stdio() -> None:
    """Prevents Uvicorn/logging crashes in GUI-frozen Windows builds where stdout is None."""
    if sys.stdout is not None and sys.stderr is not None:
        return
    try:
        log_dir = Path(get_app_data_dir())
        log_dir.mkdir(parents=True, exist_ok=True)
        log_file = open(log_dir / "desktop.log", "a", encoding="utf-8", buffering=1)
        if sys.stdout is None:
            sys.stdout = log_file
        if sys.stderr is None:
            sys.stderr = log_file
    except Exception:
        devnull = open(os.devnull, "w", encoding="utf-8")
        if sys.stdout is None:
            sys.stdout = devnull
        if sys.stderr is None:
            sys.stderr = devnull


def _desktop_launch_argv() -> List[str]:
    if getattr(sys, "frozen", False):
        return [sys.executable]
    return [sys.executable, "-m", "src.cli", "desktop", "--no-tray"]


def _wake_catchup_loop(target_daemon: NozzleGuardianDaemon) -> None:
    """Runs one immediate catch-up audit shortly after desktop launch / wake."""
    time.sleep(2.0)
    try:
        asyncio.run(target_daemon.evaluate_channels())
    except Exception as exc:
        logger.debug(f"Desktop wake catch-up skipped: {exc}")


def run_desktop(
    host: str = "127.0.0.1",
    port: int = 8086,
    open_browser: bool = False,
    enable_tray: bool = True,
) -> None:
    """Starts local Nozzle Guardian desktop agent + web cockpit."""
    _configure_frozen_stdio()
    threading.Thread(target=_wake_catchup_loop, args=(main_daemon,), daemon=True).start()

    url = f"http://{host}:{port}/ui"
    if open_browser:
        threading.Timer(1.5, lambda: webbrowser.open(url)).start()

    if enable_tray:
        try:
            import pystray  # type: ignore
            from PIL import Image, ImageDraw

            img = Image.new("RGBA", (64, 64), (11, 17, 32, 255))
            draw = ImageDraw.Draw(img)
            shield_pts = [(32, 12), (46, 18), (46, 32), (43, 40), (38, 47), (32, 51), (26, 47), (21, 40), (18, 32), (18, 18)]
            draw.polygon(shield_pts, fill=(216, 224, 234, 255), outline=(148, 163, 184, 255), width=2)
            draw.rounded_rectangle([22, 23, 42, 26], radius=1, fill=(6, 182, 212, 255))
            draw.rounded_rectangle([22, 28, 42, 31], radius=1, fill=(236, 72, 153, 255))
            draw.rounded_rectangle([22, 33, 42, 36], radius=1, fill=(234, 179, 8, 255))
            draw.rounded_rectangle([24, 38, 40, 41], radius=1, fill=(15, 23, 42, 255))

            server_cfg = uvicorn.Config(app, host=host, port=port, log_level="info")
            server = uvicorn.Server(server_cfg)
            threading.Thread(target=server.run, daemon=True).start()

            def _open(_icon, _item):
                webbrowser.open(url)

            def _check(_icon, _item):
                threading.Thread(target=lambda: asyncio.run(main_daemon.evaluate_channels()), daemon=True).start()

            def _toggle_auto(_icon, _item):
                set_autostart(not get_autostart_status()["enabled"])

            def _quit(icon, _item):
                server.should_exit = True
                icon.stop()

            tray = pystray.Icon(
                "NozzleGuardian",
                img,
                "Nozzle Guardian",
                menu=pystray.Menu(
                    pystray.MenuItem("Apri Dashboard", _open, default=True),
                    pystray.MenuItem("Controlla & Spurga ora", _check),
                    pystray.MenuItem("Attiva/Disattiva Avvio con PC", _toggle_auto),
                    pystray.MenuItem("Esci", _quit),
                ),
            )
            logger.info(f"Nozzle Guardian Desktop Tray live at {url}")
            tray.run()
            return
        except Exception:
            logger.info("pystray non disponibile; avvio Desktop Companion in modalità headless web.")

    logger.info(f"Nozzle Guardian Desktop Companion live at {url}")
    uvicorn.run(app, host=host, port=port, log_level="info")


def get_autostart_status() -> Dict[str, Any]:
    """Reports whether 1-click user-level OS autostart is currently enabled."""
    system = platform.system()
    if system == "Windows":
        try:
            import winreg  # type: ignore
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0, winreg.KEY_READ) as key:
                val, _ = winreg.QueryValueEx(key, "NozzleGuardian")
                return {"platform": system, "enabled": bool(val), "target": str(val)}
        except Exception:
            return {"platform": system, "enabled": False, "target": ""}
    if system == "Darwin":
        plist_dest = Path.home() / "Library" / "LaunchAgents" / "com.umbette.nozzleguardian.plist"
        return {"platform": system, "enabled": plist_dest.exists(), "target": str(plist_dest)}
    if system == "Linux" and os.getenv("TERMUX_VERSION") is None:
        svc_dest = Path.home() / ".config" / "systemd" / "user" / "nozzle-guardian-desktop.service"
        return {"platform": system, "enabled": svc_dest.exists(), "target": str(svc_dest)}
    return {"platform": system, "enabled": False, "target": ""}


def set_autostart(enabled: bool) -> Dict[str, Any]:
    """Enables or disables 1-click user-level autostart on Windows, macOS, or Linux."""
    system = platform.system()
    if system == "Windows":
        import winreg  # type: ignore
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0, winreg.KEY_SET_VALUE) as key:
            if enabled:
                cmd = subprocess.list2cmdline(_desktop_launch_argv())
                winreg.SetValueEx(key, "NozzleGuardian", 0, winreg.REG_SZ, cmd)
            else:
                try:
                    winreg.DeleteValue(key, "NozzleGuardian")
                except FileNotFoundError:
                    pass
        return get_autostart_status()

    if enabled:
        res = generate_autostart_artifacts(install_local=True)
        if system == "Darwin" and res.get("installed_path"):
            subprocess.run(["launchctl", "load", res["installed_path"]], check=False)
        elif system == "Linux" and res.get("installed_path"):
            subprocess.run(["systemctl", "--user", "enable", "--now", "nozzle-guardian-desktop.service"], check=False)
    else:
        if system == "Darwin":
            plist_dest = Path.home() / "Library" / "LaunchAgents" / "com.umbette.nozzleguardian.plist"
            if plist_dest.exists():
                subprocess.run(["launchctl", "unload", str(plist_dest)], check=False)
                plist_dest.unlink(missing_ok=True)
        elif system == "Linux" and os.getenv("TERMUX_VERSION") is None:
            svc_dest = Path.home() / ".config" / "systemd" / "user" / "nozzle-guardian-desktop.service"
            subprocess.run(["systemctl", "--user", "disable", "--now", "nozzle-guardian-desktop.service"], check=False)
            svc_dest.unlink(missing_ok=True)
    return get_autostart_status()


def generate_autostart_artifacts(
    output_dir: Optional[Path] = None,
    install_local: bool = False,
) -> Dict[str, Any]:
    """Generates (and optionally installs) native OS logon/wake autostart configs."""
    project_dir = Path(__file__).resolve().parent.parent
    argv = _desktop_launch_argv()
    python_bin = sys.executable
    system = platform.system()

    prog_args_xml = "\n".join(f"    <string>{arg}</string>" for arg in argv)
    macos_plist = f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>com.umbette.nozzleguardian</string>
  <key>WorkingDirectory</key>
  <string>{project_dir}</string>
  <key>ProgramArguments</key>
  <array>
{prog_args_xml}
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
</dict>
</plist>
"""

    exec_cmd = " ".join(argv)
    linux_user_service = f"""[Unit]
Description=Nozzle Guardian Desktop Companion
After=network-online.target

[Service]
Type=simple
WorkingDirectory={project_dir}
ExecStart={exec_cmd}
Restart=on-failure
RestartSec=10

[Install]
WantedBy=default.target
"""

    windows_task_xml = f"""<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <Triggers>
    <LogonTrigger><Enabled>true</Enabled></LogonTrigger>
    <EventTrigger>
      <Enabled>true</Enabled>
      <Subscription>&lt;QueryList&gt;&lt;Query Id="0" Path="System"&gt;&lt;Select Path="System"&gt;*[System[Provider[@Name='Microsoft-Windows-Kernel-Power'] and EventID=107]]&lt;/Select&gt;&lt;/Query&gt;&lt;/QueryList&gt;</Subscription>
    </EventTrigger>
  </Triggers>
  <Settings>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <StartWhenAvailable>true</StartWhenAvailable>
    <WakeToRun>false</WakeToRun>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>{python_bin}</Command>
      <Arguments>-m src.cli check --cron</Arguments>
      <WorkingDirectory>{project_dir}</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
"""

    installed_path = None
    if output_dir:
        out = Path(output_dir)
        out.mkdir(parents=True, exist_ok=True)
        (out / "com.umbette.nozzleguardian.plist").write_text(macos_plist, encoding="utf-8")
        (out / "nozzle-guardian-desktop.service").write_text(linux_user_service, encoding="utf-8")
        (out / "nozzle-guardian-windows-task.xml").write_text(windows_task_xml, encoding="utf-8")

    if install_local:
        if system == "Darwin":
            plist_dest = Path.home() / "Library" / "LaunchAgents" / "com.umbette.nozzleguardian.plist"
            plist_dest.parent.mkdir(parents=True, exist_ok=True)
            plist_dest.write_text(macos_plist, encoding="utf-8")
            installed_path = str(plist_dest)
        elif system == "Linux" and os.getenv("TERMUX_VERSION") is None:
            svc_dest = Path.home() / ".config" / "systemd" / "user" / "nozzle-guardian-desktop.service"
            svc_dest.parent.mkdir(parents=True, exist_ok=True)
            svc_dest.write_text(linux_user_service, encoding="utf-8")
            subprocess.run(["systemctl", "--user", "daemon-reload"], check=False)
            installed_path = str(svc_dest)

    return {
        "platform": system,
        "installed_path": installed_path,
        "macos_plist": macos_plist,
        "linux_user_service": linux_user_service,
        "windows_task_xml": windows_task_xml,
    }

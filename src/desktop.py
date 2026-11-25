"""
Nozzle Guardian Desktop Companion (Windows / macOS / Linux)
============================================================
Runs the local FastAPI UI & dormancy daemon in the background (with optional
system tray icon if pystray is installed) AND executes an immediate wake/boot
catch-up check so printers stay hydrated even when the user's PC sleeps.
Also generates native autostart installers:
  - Windows: Task Scheduler XML (OnLogon + Kernel-Power Wake)
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
from typing import Any, Dict, Optional

import uvicorn

from .daemon import NozzleGuardianDaemon
from .main import app, daemon as main_daemon

logger = logging.getLogger("nozzle_guardian.desktop")


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
    threading.Thread(target=_wake_catchup_loop, args=(main_daemon,), daemon=True).start()

    url = f"http://{host}:{port}/ui"
    if open_browser:
        threading.Timer(1.5, lambda: webbrowser.open(url)).start()

    if enable_tray:
        try:
            import pystray  # type: ignore
            from PIL import Image, ImageDraw

            img = Image.new("RGB", (64, 64), (15, 23, 42))
            draw = ImageDraw.Draw(img)
            draw.rounded_rectangle([8, 8, 56, 56], radius=12, outline=(56, 189, 248), width=4)
            draw.rectangle([16, 22, 48, 28], fill=(6, 182, 212))
            draw.rectangle([16, 31, 48, 37], fill=(236, 72, 153))
            draw.rectangle([16, 40, 48, 46], fill=(234, 179, 8))

            server_cfg = uvicorn.Config(app, host=host, port=port, log_level="info")
            server = uvicorn.Server(server_cfg)
            threading.Thread(target=server.run, daemon=True).start()

            def _open(_icon, _item):
                webbrowser.open(url)

            def _check(_icon, _item):
                threading.Thread(target=lambda: asyncio.run(main_daemon.evaluate_channels()), daemon=True).start()

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


def generate_autostart_artifacts(
    output_dir: Optional[Path] = None,
    install_local: bool = False,
) -> Dict[str, Any]:
    """Generates (and optionally installs) native OS logon/wake autostart configs."""
    project_dir = Path(__file__).resolve().parent.parent
    python_bin = sys.executable
    system = platform.system()

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
    <string>{python_bin}</string>
    <string>-m</string>
    <string>src.cli</string>
    <string>desktop</string>
    <string>--no-tray</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
</dict>
</plist>
"""

    linux_user_service = f"""[Unit]
Description=Nozzle Guardian Desktop Companion
After=network-online.target

[Service]
Type=simple
WorkingDirectory={project_dir}
ExecStart={python_bin} -m src.cli desktop --no-tray
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

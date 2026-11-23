"""
Nozzle Guardian — Command-Line Interface (CLI)
Provides headless one-shot audits, cron/systemd execution, manual purges,
and pattern diagnosis without running Docker or a background daemon.
"""

import argparse
import asyncio
import json
import logging
import sys
from pathlib import Path

from .config import settings
from .daemon import NozzleGuardianDaemon
from .generator import PatternGenerator
from .printer import EpsonPrinterClient
from .scanner import analyze_nozzle_sheet

logger = logging.getLogger("nozzle_guardian.cli")


def _setup_logging(verbose: bool = False):
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(
        level=level,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S"
    )


async def cmd_check(args):
    """Audits printer telemetry, tracks channel dormancy, and optionally runs cron purge."""
    client = EpsonPrinterClient()
    daemon = NozzleGuardianDaemon()
    
    print(f"🔍 Connecting to printer at {settings.printer.host}:{settings.printer.port}...")
    status = await client.get_status()
    
    if not status.online:
        print(f"❌ Printer is OFFLINE or unreachable ({status.error_message or 'No response'}).")
        sys.exit(1)
        
    print(f"🟢 Printer ONLINE: {status.state.upper()}")
    print(f"   - Monochrome Pages Printed: {status.monochrome_pages if status.monochrome_pages is not None else 'N/A'}")
    print(f"   - Color Pages Printed:      {status.color_pages if status.color_pages is not None else 'N/A'}")
    print(f"   - Total Pages Printed:      {status.total_pages if status.total_pages is not None else 'N/A'}")
    print(f"   - Channel Telemetry Mode:   {'Supported (Mono & Color Decoupled)' if status.has_channel_telemetry else 'Total Pages Only'}")

    state = daemon.load_state()
    auto_enabled = state.get("auto_purge_enabled", False)
    print(f"   - Auto-Purge Policy:        {'ENABLED' if auto_enabled else 'DISABLED (Opt-in)'}")

    if args.cron:
        print("\n⚙️ Evaluating channel dormancy thresholds...")
        if args.force:
            print("⚠️ Flag --force passed: Overriding auto_purge_enabled policy for this run.")
            state["auto_purge_enabled"] = True
            daemon.save_state(state)
            
        await daemon.evaluate_channels()
        updated_state = daemon.load_state()
        print(f"✅ Audit completed. State saved to {daemon.state_file}.")
        if updated_state.get("last_mono_purge"):
            print(f"   - Last Mono Purge:  {updated_state['last_mono_purge']}")
        if updated_state.get("last_color_purge"):
            print(f"   - Last Color Purge: {updated_state['last_color_purge']}")


async def cmd_purge(args):
    """Executes an immediate manual micro-purge for the selected channel."""
    mode = args.mode.lower()
    if mode not in ("cmy", "k", "cmyk"):
        print(f"❌ Invalid purge mode '{mode}'. Must be 'cmy', 'k', or 'cmyk'.")
        sys.exit(1)
        
    client = EpsonPrinterClient()
    gen = PatternGenerator(settings)
    
    print(f"🖨️ Preparing purge job for mode: {mode.upper()}...")
    pwg_path = gen.get_pwg_path(mode)
    if not pwg_path.exists():
        print(f"Compiling PWG-Raster pattern for {mode}...")
        pwg_path = await asyncio.to_thread(gen.compile_pwg, mode)
        
    res = await client.execute_purge_job(pwg_path, mode=mode)
    if res.status.value == "accepted":
        print(f"✅ Purge job '{mode.upper()}' successfully dispatched to printer!")
    else:
        print(f"❌ Purge job failed: {res.message}")
        sys.exit(1)


async def cmd_surgical(args):
    """Executes a surgical single-nozzle acoustic resuscitation pass."""
    channel = args.channel.lower()
    step = args.step
    bursts = args.bursts
    
    if channel not in ("cyan", "magenta", "yellow", "black"):
        print(f"❌ Invalid channel '{channel}'. Choose from 'cyan', 'magenta', 'yellow', 'black'.")
        sys.exit(1)
        
    client = EpsonPrinterClient()
    gen = PatternGenerator(settings)
    
    print(f"🎯 Compiling surgical resuscitation pass: {channel.upper()} Nozzle #{step} ({bursts} micro-bursts)...")
    pwg_path = await asyncio.to_thread(gen.compile_surgical_pwg, channel, step, bursts)
    
    res = await client.execute_purge_job(pwg_path, mode=f"surgical_{channel}_s{step}")
    if res.status.value == "accepted":
        print(f"✅ Surgical acoustic resuscitation pass dispatched for {channel.upper()} #{step}!")
    else:
        print(f"❌ Surgical job failed: {res.message}")
        sys.exit(1)


def cmd_scan(args):
    """Scans and diagnoses an image of a Nozzle Guardian test sheet."""
    img_path = Path(args.image_path)
    if not img_path.exists():
        print(f"❌ Image file not found: {img_path}")
        sys.exit(1)
        
    print(f"📸 Analyzing nozzle check printout: {img_path.name}...")
    with open(img_path, "rb") as f:
        img_bytes = f.read()
        
    res = analyze_nozzle_sheet(img_bytes)
    
    if args.json:
        print(json.dumps(res, indent=2))
        return

    print(f"\n================ NOZZLE HEALTH REPORT ================")
    print(f"Overall Printhead Health: {res['overall_health_pct']}%")
    print("------------------------------------------------------")
    for ch, data in res.get("channels", {}).items():
        status_sym = "✅" if data["healthy"] else "⚠️"
        missing_str = f"Missing steps: {data['missing_steps']}" if data["missing_steps"] else "All 10 steps firing"
        print(f" {status_sym} {ch.upper():<8}: {data['health_pct']:>5.1f}% | {missing_str}")
    print("------------------------------------------------------")
    print(f"Recommended Action: {res.get('recommended_action', 'none').upper()}")
    if res.get("target_channel") and res.get("target_step"):
        print(f"🎯 Surgical Target: {res['target_channel'].upper()} Nozzle #{res['target_step']}")
        print(f"   Command to fix: python -m src.cli surgical --channel {res['target_channel']} --step {res['target_step']}")
    print(f"Message: {res.get('message', '')}")
    print("======================================================\n")


def cmd_systemd(args):
    """Generates native systemd service and timer unit definitions."""
    python_bin = sys.executable
    project_dir = Path(__file__).resolve().parent.parent
    
    service_content = f"""# /etc/systemd/system/nozzle-guardian.service
[Unit]
Description=Nozzle Guardian Decoupled Anti-Clog Audit
After=network.target

[Service]
Type=oneshot
WorkingDirectory={project_dir}
ExecStart={python_bin} -m src.cli check --cron
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
"""

    timer_content = """# /etc/systemd/system/nozzle-guardian.timer
[Unit]
Description=Run Nozzle Guardian Daily Audit
Requires=nozzle-guardian.service

[Timer]
OnCalendar=*-*-* 12:00:00
RandomizedDelaySec=1800
Persistent=true

[Install]
WantedBy=timers.target
"""

    print("=== Systemd Service Unit ===")
    print(service_content)
    print("=== Systemd Timer Unit ===")
    print(timer_content)
    print("To install:")
    print("  sudo tee /etc/systemd/system/nozzle-guardian.service << 'EOF'\n" + service_content + "EOF")
    print("  sudo tee /etc/systemd/system/nozzle-guardian.timer << 'EOF'\n" + timer_content + "EOF")
    print("  sudo systemctl daemon-reload")
    print("  sudo systemctl enable --now nozzle-guardian.timer")


def main():
    parser = argparse.ArgumentParser(
        prog="nozzle-guardian",
        description="Nozzle Guardian: Channel-decoupled anti-clogging CLI and diagnostic tool"
    )
    parser.add_argument("-v", "--verbose", action="store_true", help="Enable debug logging")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # check command
    p_check = subparsers.add_parser("check", help="Audit printer telemetry and check channel dormancy")
    p_check.add_argument("--cron", action="store_true", help="Run in cron mode (triggers purge if channels are dormant)")
    p_check.add_argument("--force", action="store_true", help="Force purge execution even if auto-purge policy is disabled")

    # purge command
    p_purge = subparsers.add_parser("purge", help="Trigger a standard preventive micro-purge")
    p_purge.add_argument("mode", choices=["cmy", "k", "cmyk"], help="Channel mode to purge ('cmy', 'k', or 'cmyk')")

    # surgical command
    p_surg = subparsers.add_parser("surgical", help="Trigger a surgical single-nozzle acoustic resuscitation pass")
    p_surg.add_argument("--channel", required=True, choices=["cyan", "magenta", "yellow", "black"], help="Target color channel")
    p_surg.add_argument("--step", required=True, type=int, choices=range(1, 11), help="Target nozzle step number (1 to 10)")
    p_surg.add_argument("--bursts", type=int, default=5, choices=range(1, 10), help="Number of micro-burst acoustic lines (1-9)")

    # scan command
    p_scan = subparsers.add_parser("scan", help="Analyze an image of a Nozzle Guardian test sheet")
    p_scan.add_argument("image_path", help="Path to scanned image or photo")
    p_scan.add_argument("--json", action="store_true", help="Output raw JSON diagnostic report")

    # systemd command
    subparsers.add_parser("systemd", help="Generate systemd .service and .timer units for headless Linux setups")

    args = parser.parse_args()
    _setup_logging(args.verbose)

    if args.command == "check":
        asyncio.run(cmd_check(args))
    elif args.command == "purge":
        asyncio.run(cmd_purge(args))
    elif args.command == "surgical":
        asyncio.run(cmd_surgical(args))
    elif args.command == "scan":
        cmd_scan(args)
    elif args.command == "systemd":
        cmd_systemd(args)


if __name__ == "__main__":
    main()

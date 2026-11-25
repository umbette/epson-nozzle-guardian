#!/usr/bin/env bash
set -euo pipefail

# Nozzle Guardian Universal Setup for Repurposed / Old Hardware
# Supports: Android (Termux, NO ROOT required), Raspberry Pi, Old Laptops, Armbian TV Boxes, macOS.

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

echo "================================================="
echo "  Nozzle Guardian - Old Device / Local Setup"
echo "================================================="

if [ ! -d ".venv" ]; then
  echo "[1/4] Creating Python virtual environment..."
  python3 -m venv .venv
fi

echo "[2/4] Installing pure-Python dependencies (zero CUPS)..."
.venv/bin/pip install --upgrade pip >/dev/null
.venv/bin/pip install -r requirements.txt

if [ ! -f ".env" ] && [ -f ".env.example" ]; then
  cp .env.example .env
  echo "[*] Created .env from .env.example (edit PRINTER_HOST and NTFY_URL before production use)."
fi

if [ -n "${TERMUX_VERSION:-}" ]; then
  echo "[3/4] Detected Android Termux environment (NO ROOT required)!"
  mkdir -p "$HOME/.termux/boot"
  cat > "$HOME/.termux/boot/start-nozzle-guardian.sh" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock || true
cd "$PROJECT_DIR"
nohup "$PROJECT_DIR/.venv/bin/uvicorn" src.main:app --host 0.0.0.0 --port 8086 > "$PROJECT_DIR/nozzle-termux.log" 2>&1 &
EOF
  chmod +x "$HOME/.termux/boot/start-nozzle-guardian.sh"
  echo "[4/4] Termux Boot script installed at ~/.termux/boot/start-nozzle-guardian.sh"
  echo "      Start now with: $HOME/.termux/boot/start-nozzle-guardian.sh"
elif command -v systemctl >/dev/null 2>&1; then
  echo "[3/4] Installing user-level systemd service (no root needed)..."
  .venv/bin/python -m src.cli install-desktop
  echo "[4/4] Enable with: systemctl --user enable --now nozzle-guardian-desktop.service"
  echo "      (For headless servers without login session: sudo loginctl enable-linger \"\$USER\")"
elif [ "$(uname -s)" = "Darwin" ]; then
  echo "[3/4] Installing macOS LaunchAgent..."
  .venv/bin/python -m src.cli install-desktop
  echo "[4/4] Load with: launchctl load ~/Library/LaunchAgents/com.umbette.nozzleguardian.plist"
else
  echo "[3/4] Setup complete. Start manually with:"
  echo "      .venv/bin/uvicorn src.main:app --host 0.0.0.0 --port 8086"
fi

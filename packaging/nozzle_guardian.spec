# Nozzle Guardian — Cross-Platform PyInstaller Spec (Windows .exe / macOS .app / Linux)
# Build locally with: pyinstaller packaging/nozzle_guardian.spec

import sys
from PyInstaller.utils.hooks import collect_submodules

block_cipher = None

hiddenimports = (
    collect_submodules("src")
    + collect_submodules("uvicorn")
    + collect_submodules("pystray")
    + [
        "PIL._tkinter_finder",
        "multipart",
        "pydantic_settings",
    ]
)

a = Analysis(
    ['desktop_entry.py'],
    pathex=['..'],
    binaries=[],
    datas=[('../config.example.yaml', '.')],
    hiddenimports=hiddenimports,
    hookspath=[],
    runtime_hooks=[],
    excludes=[],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)
exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='NozzleGuardian',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
)

if sys.platform == "darwin":
    app = BUNDLE(
        exe,
        name='NozzleGuardian.app',
        bundle_identifier='com.umbette.nozzleguardian',
    )

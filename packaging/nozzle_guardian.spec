# Nozzle Guardian — Cross-Platform PyInstaller Spec (Windows .exe / macOS .app / Linux)
# Build locally with: pyinstaller packaging/nozzle_guardian.spec

import os
import sys
from PyInstaller.utils.hooks import collect_submodules

block_cipher = None

repo_root = os.path.abspath(os.path.join(SPECPATH, ".."))
if repo_root not in sys.path:
    sys.path.insert(0, repo_root)

hiddenimports = (
    collect_submodules("src")
    + collect_submodules("uvicorn")
    + [
        "pystray",
        "pystray._base",
        "pystray._win32",
        "pystray._darwin",
        "pystray._xorg",
        "pystray._gtk",
        "pystray._appindicator",
        "pystray._util",
        "PIL._tkinter_finder",
        "multipart",
        "pydantic_settings",
    ]
)

a = Analysis(
    [os.path.join(SPECPATH, "desktop_entry.py")],
    pathex=[repo_root],
    binaries=[],
    datas=[
        (os.path.join(repo_root, "config.example.yaml"), "."),
        (os.path.join(SPECPATH, "icon.png"), "packaging"),
    ],
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

if sys.platform == "darwin":
    exe = EXE(
        pyz,
        a.scripts,
        [],
        exclude_binaries=True,
        name="NozzleGuardian",
        icon=os.path.join(SPECPATH, "icon.icns"),
        debug=False,
        bootloader_ignore_signals=False,
        strip=False,
        upx=False,
        console=False,
    )
    coll = COLLECT(
        exe,
        a.binaries,
        a.zipfiles,
        a.datas,
        strip=False,
        upx=False,
        name="NozzleGuardian",
    )
    app = BUNDLE(
        coll,
        name="NozzleGuardian.app",
        icon=os.path.join(SPECPATH, "icon.icns"),
        bundle_identifier="com.umbette.nozzleguardian",
    )
else:
    win_or_linux_icon = os.path.join(SPECPATH, "icon.ico" if sys.platform == "win32" else "icon.png")
    exe = EXE(
        pyz,
        a.scripts,
        a.binaries,
        a.zipfiles,
        a.datas,
        [],
        name="NozzleGuardian",
        icon=win_or_linux_icon,
        debug=False,
        bootloader_ignore_signals=False,
        strip=False,
        upx=False,
        console=False,
    )

# Nozzle Guardian — Cross-Platform PyInstaller Spec (Windows .exe / macOS .app / Linux)
# Build locally with: pyinstaller packaging/nozzle_guardian.spec

block_cipher = None

a = Analysis(
    ['../src/cli.py'],
    pathex=['..'],
    binaries=[],
    datas=[('../config.example.yaml', '.')],
    hiddenimports=['uvicorn.logging', 'uvicorn.loops.auto', 'uvicorn.protocols.http.auto'],
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
    upx=True,
    console=False,
)

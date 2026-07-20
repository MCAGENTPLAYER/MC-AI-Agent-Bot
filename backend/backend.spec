import sys
import os
from pathlib import Path

sys.setrecursionlimit(5000)

block_cipher = None

current_dir = os.path.dirname(os.path.abspath(__file__))

a = Analysis(
    ['app.py'],
    pathex=[current_dir],
    binaries=[],
    datas=[
        (os.path.join(current_dir, 'static'), 'static'),
        (os.path.join(current_dir, 'data'), 'data'),
        (os.path.join(current_dir, '.env'), '.'),
        (os.path.join(current_dir, 'core'), 'core'),
        (os.path.join(current_dir, 'schemas'), 'schemas'),
        (os.path.join(current_dir, 'services'), 'services'),
    ],
    hiddenimports=[
        'fastapi',
        'uvicorn',
        'websockets',
        'pydantic',
        'pydantic_settings',
        'langchain',
        'langchain_openai',
        'langchain_community',
        'faiss',
        'dotenv',
        'requests',
        'httpx',
        'asyncio',
        'json',
        'uuid',
        'time',
        'pathlib',
        'typing',
        'logging',
    ],
    hookspath=[],
    hooksconfig={},
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
    name='ai-bot-backend',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

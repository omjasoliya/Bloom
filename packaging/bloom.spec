# PyInstaller spec for Bloom. Build from the project root:
#   pyinstaller --noconfirm packaging/bloom.spec
import os
import re
import sys
from PyInstaller.utils.hooks import collect_data_files, collect_dynamic_libs

ROOT = os.path.abspath(os.path.join(SPECPATH, ".."))

datas = [(os.path.join(ROOT, "bloom", "assets"), "assets")]
IS_WINDOWS = sys.platform.startswith("win")
if IS_WINDOWS:                                                           # Linux uses the ffmpeg package
    datas += collect_data_files("imageio_ffmpeg", includes=["binaries/*"])   # bundled ffmpeg.exe
datas += collect_data_files("pypdfium2_raw")                             # version.json etc.
binaries = collect_dynamic_libs("pypdfium2_raw")                         # pdfium.dll
binaries += collect_dynamic_libs("pillow_heif")                         # libheif, for HEIC photos

a = Analysis(
    [os.path.join(ROOT, "run_bloom.py")],
    pathex=[ROOT],
    datas=datas,
    binaries=binaries,
    hiddenimports=["PySide6.QtSvg", "PySide6.QtNetwork", "pillow_heif"],
    excludes=["tkinter", "PySide6.QtQml", "PySide6.QtQuick", "PySide6.QtWebEngineCore"],
)
if not IS_WINDOWS:
    # Ship only Qt, Python and the libraries wheels vendor (hashed names). The
    # desktop's own libraries (X11, GTK, glib, systemd...) come from the system,
    # so the build keeps working on other distro versions; the .deb depends on them.
    ours = re.compile(r"^(libQt6|libicu|libpython|libpyside6|libshiboken6|lib.+-[0-9a-f]{8}\.so)")
    a.binaries = [b for b in a.binaries
                  if ("/" in b[0] or ours.match(b[0])) and "platformthemes/libqgtk3" not in b[0]]
    a.binaries = [b for b in a.binaries if not b[0].startswith("imageio_ffmpeg/")]
    a.datas = [d for d in a.datas if not d[0].startswith("imageio_ffmpeg/")]
pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, [],
    exclude_binaries=True,
    name="Bloom",
    console=False,                                   # tray app: no console window
    icon=os.path.join(ROOT, "bloom", "assets", "icon.ico"),
)
coll = COLLECT(exe, a.binaries, a.datas, name="Bloom")

# Bloom (Windows + Linux)

Offline file converter with a petal wheel. Start dragging any file, then press a key:

| Key | Wheel | What it does |
|---|---|---|
| **Shift** | Convert | 186 format conversions (images, video, audio, PDF, documents, spreadsheets, slides) |
| **Ctrl** | Tools | Compress, half size, rotate, remove metadata, grayscale, to PDF, video 720p/mute, audio normalize, PDF merge/split |
| **Alt** | Actions | Copy path, terminal here, copy/move to Desktop, zip, duplicate |

Drop on a petal to run it. Drop in the middle (or outside) to cancel. Pressing another key mid-drag switches wheels.
Results are new files next to the original; only **Move to Desktop** moves the original.

## Build on Windows

**Option A: on the Windows PC**

1. Install **Python 3.12 or 3.13** from python.org (tick *Add python.exe to PATH*).
2. Optional, for a real installer: install **Inno Setup 6** from jrsoftware.org.
3. Copy this whole `bloom-app` folder to the PC and double-click `packaging\build_windows.bat`.
4. You get `dist\Bloom-Setup.exe` (with Inno Setup) or `dist\Bloom\Bloom.exe` (without).

**Option B: GitHub Actions (no Python needed on the PC)**

Push this folder to a GitHub repository, open **Actions → Build → Run workflow**, and download
`Bloom-Windows` (and `Bloom-Linux`) from the finished run. Pushing a tag such as `v0.2.0` also publishes
both installers as a GitHub Release, which is the link to put on the website.

The installer installs for your user only (no admin rights) into `%LocalAppData%\Programs\Bloom`, and offers
two options: start at login, and add **Open in Bloom** to the right-click menu.

## Build on Linux

```
./packaging/build_linux.sh          # makes dist/bloom_<version>_amd64.deb
sudo apt install ./dist/bloom_*.deb # install; then open Bloom from your apps
```

The .deb installs to `/opt/bloom`, adds Bloom to the app menu, and turns on *Start when I log in* the first
time it runs. It uses the system's ffmpeg (a dependency). A .deb runs on Linux versions at least as new as the
one it was built on, so the GitHub Actions build (Ubuntu 22.04) is the one to share.

## Windows test checklist

1. Install, and check the Bloom flower appears in the tray (notification area; it may be under the ^ arrow).
2. In Explorer, start dragging a **.png**, then press **Shift**: the wheel should appear under the cursor.
   Drop on a petal and check a converted copy appears next to the original.
3. Drag again and press **Ctrl** (Tools) → Compress. Then press **Alt** mid-drag to switch to Actions → Copy path,
   and paste somewhere to check.
4. Shift-drag and drop in the **middle** of the wheel: nothing should happen, and the file must **not** move
   (Shift normally means *move* in Explorer; Bloom always takes a copy).
5. After an Alt-drag, check that Explorer's ribbon/menu key tips did **not** pop up.
6. Right-click a file → *Show more options* (Windows 11) → **Open in Bloom** → click a petal.
   Select 3 files and do the same: one wheel should open for all three.
7. Try a video (Shift → MP4/GIF/MP3) and a PDF (Ctrl → Merge with 2 PDFs selected, Split).
8. Tray menu: pause/resume, *Start when I log in*, and *Quit Bloom*.

If something fails, note the step and what happened. Bloom's settings live in `%AppData%\Bloom\`.

## Known limits

- **Windows SmartScreen** will warn about an unknown publisher, because the app isn't code-signed.
  Choose *More info → Run anyway*. Signing needs a paid code-signing certificate.
- **Documents, spreadsheets and slides** need LibreOffice installed (free). Without it, Bloom says so.
- **Windows 11** puts classic right-click entries under *Show more options*.
- **Linux** needs an X11 session; on Wayland only the right-click menu works.

## Run from source (Linux or Windows)

```
python -m venv .venv
.venv/bin/pip install -r requirements.txt        # Windows: .venv\Scripts\pip
.venv/bin/python -m bloom                         # Windows: .venv\Scripts\python -m bloom
```

## Layout

- `bloom/app.py`: tray, key + drag watcher, right-click hand-off, background jobs
- `bloom/wheel.py`: the glass petal wheel (Qt)
- `bloom/inputstate.py`: global mouse/keys (Win32 on Windows, X11 on Linux)
- `bloom/platform_utils.py`: Desktop folder, terminal, start at login, right-click menu, tool paths
- `bloom/converters.py`, `bloom/tools.py`: the conversion, tools and actions engine
- `packaging/`: PyInstaller spec, Inno Setup script, Windows build script

## License

Apache License 2.0, see [LICENSE](LICENSE).

"""Everything that differs between Windows and Linux lives here."""
import os
import shutil
import subprocess
import sys
from pathlib import Path

IS_WINDOWS = sys.platform.startswith("win")
APP_NAME = "Bloom"

# Hide the console window that would otherwise flash up for every ffmpeg call.
NO_WINDOW = subprocess.CREATE_NO_WINDOW if IS_WINDOWS else 0


def run(args, timeout=600):
    subprocess.run(args, check=True, capture_output=True, timeout=timeout, creationflags=NO_WINDOW)


def app_dir():
    """Folder of the running app (the PyInstaller bundle, or the source tree)."""
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
    return Path(__file__).resolve().parent


def asset(name):
    return str(app_dir() / "assets" / name) if getattr(sys, "frozen", False) else \
        str(Path(__file__).resolve().parent / "assets" / name)


# ------------------------------------------------------------ external tools --

def ffmpeg_exe():
    """System ffmpeg on Linux when present; the bundled one otherwise."""
    if not IS_WINDOWS and shutil.which("ffmpeg"):
        return "ffmpeg"
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return shutil.which("ffmpeg") or "ffmpeg"


def soffice_exe():
    """LibreOffice, used for documents, spreadsheets and slides. None if missing."""
    found = shutil.which("soffice") or shutil.which("libreoffice")
    if found:
        return found
    if IS_WINDOWS:
        for base in (os.environ.get("ProgramFiles", r"C:\Program Files"),
                     os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")):
            exe = Path(base) / "LibreOffice" / "program" / "soffice.exe"
            if exe.exists():
                return str(exe)
    return None


# ------------------------------------------------------------------ folders --

def desktop_dir():
    if IS_WINDOWS:
        # Ask Windows, so a Desktop moved into OneDrive is found too.
        import ctypes
        from ctypes import wintypes
        folderid_desktop = ctypes.c_char_p(bytes.fromhex("3ACCBFB42CDB4C42B0297FE99A87C641"))
        path = ctypes.c_wchar_p()
        if ctypes.windll.shell32.SHGetKnownFolderPath(folderid_desktop, 0, None,
                                                      ctypes.byref(path)) == 0:
            result = Path(path.value)
            ctypes.windll.ole32.CoTaskMemFree(path)
            return result
        return Path.home() / "Desktop"
    try:
        out = subprocess.run(["xdg-user-dir", "DESKTOP"], capture_output=True, text=True).stdout.strip()
        if out and os.path.isdir(out):
            return Path(out)
    except OSError:
        pass
    return Path.home() / "Desktop"


def open_terminal(folder):
    folder = str(folder)
    if IS_WINDOWS:
        if shutil.which("wt"):
            subprocess.Popen(["wt", "-d", folder])
        else:
            subprocess.Popen(["cmd.exe", "/K", "cd", "/d", folder],
                             creationflags=subprocess.CREATE_NEW_CONSOLE)
        return
    for cmd in (["exo-open", "--launch", "TerminalEmulator", "--working-directory", folder],
                ["gnome-terminal", "--working-directory", folder],
                ["x-terminal-emulator"]):
        if shutil.which(cmd[0]):
            subprocess.Popen(cmd, cwd=folder, start_new_session=True,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return
    raise RuntimeError("No terminal app found")


# ------------------------------------------------------- start at login --------

def launch_command():
    if getattr(sys, "frozen", False):
        return [sys.executable]
    return [sys.executable, "-m", "bloom"]


def _quote(parts):
    return " ".join(f'"{p}"' for p in parts)


def autostart_enabled():
    if IS_WINDOWS:
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run") as k:
                winreg.QueryValueEx(k, APP_NAME)
                return True
        except OSError:
            return False
    return (Path.home() / ".config/autostart/bloom-qt.desktop").exists()


def set_autostart(on):
    if IS_WINDOWS:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run",
                            0, winreg.KEY_SET_VALUE) as k:
            if on:
                winreg.SetValueEx(k, APP_NAME, 0, winreg.REG_SZ, _quote(launch_command()))
            else:
                try:
                    winreg.DeleteValue(k, APP_NAME)
                except OSError:
                    pass
        return
    path = Path.home() / ".config/autostart/bloom-qt.desktop"
    if on:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("[Desktop Entry]\nType=Application\nName=Bloom\n"
                        f"Exec={_quote(launch_command())}\nIcon={asset('icon.png')}\nTerminal=false\n")
    elif path.exists():
        path.unlink()


# ------------------------------------------------- right-click menu (Windows) --

_MENU_KEYS = (r"Software\Classes\*\shell\Bloom", r"Software\Classes\Directory\shell\Bloom")


def context_menu_enabled():
    if not IS_WINDOWS:
        return False
    import winreg
    try:
        winreg.OpenKey(winreg.HKEY_CURRENT_USER, _MENU_KEYS[0]).Close()
        return True
    except OSError:
        return False


def set_context_menu(on):
    """Adds "Open in Bloom" to Explorer's right-click menu for files and folders.
    On Windows 11 it appears under "Show more options"."""
    if not IS_WINDOWS:
        return
    import winreg
    if on:
        cmd = _quote(launch_command() + ["--pick"]) + ' "%1"'
        icon = sys.executable if getattr(sys, "frozen", False) else asset("icon.ico")
        for key in _MENU_KEYS:
            with winreg.CreateKey(winreg.HKEY_CURRENT_USER, key) as k:
                winreg.SetValueEx(k, None, 0, winreg.REG_SZ, "Open in Bloom")
                winreg.SetValueEx(k, "Icon", 0, winreg.REG_SZ, icon)
            with winreg.CreateKey(winreg.HKEY_CURRENT_USER, key + r"\command") as k:
                winreg.SetValueEx(k, None, 0, winreg.REG_SZ, cmd)
    else:
        for key in _MENU_KEYS:
            for sub in (key + r"\command", key):
                try:
                    winreg.DeleteKey(winreg.HKEY_CURRENT_USER, sub)
                except OSError:
                    pass

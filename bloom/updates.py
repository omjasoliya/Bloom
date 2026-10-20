"""Optional update check. Bloom asks once; when allowed, it asks GitHub for the
latest version number at most once a day. Files never leave the computer.

On Windows the new installer runs silently over the old version and starts Bloom
again; on Linux the new .deb opens in the system's software installer."""
import json
import os
import platform
import subprocess
import tempfile
import urllib.request

from . import __version__
from . import platform_utils as pu

LATEST = "https://api.github.com/repos/omjasoliya/Bloom/releases/latest"
DAY = 24 * 60 * 60


def newer(a, b):
    def parts(v):
        return [int(x) if x.isdigit() else 0 for x in v.split(".")]
    x, y = parts(a), parts(b)
    n = max(len(x), len(y))
    return x + [0] * (n - len(x)) > y + [0] * (n - len(y))


def asset_name():
    if pu.IS_WINDOWS:
        return "Bloom-Setup.exe"
    arch = "arm64" if platform.machine().lower() in ("aarch64", "arm64") else "amd64"
    return f"bloom_{arch}.deb"


def _get(url, accept=None):
    request = urllib.request.Request(url, headers={"User-Agent": "Bloom-Desktop", **({"Accept": accept} if accept else {})})
    return urllib.request.urlopen(request, timeout=20)


def check(current=__version__):
    """{"version", "notes", "url"} for a newer release, else None. Raises on network errors."""
    with _get(LATEST, "application/vnd.github+json") as response:
        data = json.load(response)
    version = data["tag_name"].lstrip("v")
    if not newer(version, current):
        return None
    url = next((a["browser_download_url"] for a in data.get("assets", []) if a["name"] == asset_name()), None)
    if not url:
        return None
    notes = next((line.strip().lstrip("-*# ") for line in (data.get("body") or "").splitlines() if line.strip()), "")
    return {"version": version, "notes": notes[:140], "url": url}


def download(release):
    folder = os.path.join(tempfile.gettempdir(), "bloom-update")
    os.makedirs(folder, exist_ok=True)
    target = os.path.join(folder, asset_name())
    with _get(release["url"]) as response, open(target, "wb") as out:
        while chunk := response.read(256 * 1024):
            out.write(chunk)
    return target


def install(path):
    """Starts the installer. On Windows Bloom must quit right after (the caller does)."""
    if pu.IS_WINDOWS:
        subprocess.Popen([path, "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART"], close_fds=True)
    else:
        subprocess.Popen(["xdg-open", path], start_new_session=True,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

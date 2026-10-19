#!/usr/bin/env bash
# Builds Bloom for Linux: dist/bloom_<version>_<arch>.deb (amd64 or arm64, whichever machine runs it)
# Needs: python3 with venv, dpkg-deb. Run from anywhere.
# The .deb runs on Linux versions at least as new as the one it was built on
# (glibc), so build on the oldest Ubuntu you want to support.
set -euo pipefail
cd "$(dirname "$0")/.."
ARCH=$(dpkg --print-architecture)
VERSION=$(sed -n 's/^__version__ = "\(.*\)"/\1/p' bloom/__init__.py)

echo "[1/3] Build environment"
[ -x .venv/bin/python ] || python3 -m venv .venv
.venv/bin/pip install -q -r requirements.txt pyinstaller

echo "[2/3] Bundling Bloom"
.venv/bin/pyinstaller --noconfirm --log-level WARN --distpath dist --workpath build packaging/bloom.spec

echo "[3/3] Packaging .deb"
PKG=build/deb
rm -rf "$PKG"
mkdir -p "$PKG/DEBIAN" "$PKG/opt" "$PKG/usr/bin" "$PKG/usr/share/applications" \
         "$PKG/usr/share/icons/hicolor/256x256/apps"
cp -a dist/Bloom "$PKG/opt/bloom"
ln -s /opt/bloom/Bloom "$PKG/usr/bin/bloom"
cp bloom/assets/icon.png "$PKG/usr/share/icons/hicolor/256x256/apps/bloom.png"
cat > "$PKG/usr/share/applications/bloom.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Bloom
GenericName=File converter
Comment=Drag a file, tap Shift, Ctrl or Alt, and convert it offline
Exec=/opt/bloom/Bloom %F
Icon=bloom
Terminal=false
Categories=Utility;
Keywords=convert;pdf;image;video;compress;
StartupNotify=false
DESKTOP
cat > "$PKG/DEBIAN/control" <<CONTROL
Package: bloom
Version: $VERSION
Section: utils
Priority: optional
Architecture: $ARCH
Maintainer: Bloom <bloom@localhost>
Installed-Size: $(du -sk "$PKG" | cut -f1)
Depends: ffmpeg, xdg-user-dirs, libx11-6, libx11-xcb1, libxcb1, libxcb-cursor0, libxcb-icccm4, libxcb-image0, libxcb-keysyms1, libxcb-randr0, libxcb-render0, libxcb-render-util0, libxcb-shape0, libxcb-shm0, libxcb-sync1, libxcb-util1, libxcb-xfixes0, libxcb-xkb1, libxcb-glx0, libxkbcommon0, libxkbcommon-x11-0, libegl1, libgl1, libfontconfig1, libfreetype6, libdbus-1-3, libglib2.0-0t64 | libglib2.0-0, libpng16-16t64 | libpng16-16, libssl3t64 | libssl3
Recommends: libreoffice-core
Description: Offline file converter with a petal wheel
 Start dragging any file, then press Shift to convert, Ctrl for tools or
 Alt for actions, and drop it on a petal. Everything runs on your computer.
CONTROL
cat > "$PKG/DEBIAN/postinst" <<'POST'
#!/bin/sh
set -e
command -v update-desktop-database >/dev/null && update-desktop-database -q /usr/share/applications || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q /usr/share/icons/hicolor || true
POST
chmod 755 "$PKG/DEBIAN/postinst"
chmod -R go-w "$PKG"
dpkg-deb --root-owner-group --build -Zxz "$PKG" "dist/bloom_${VERSION}_${ARCH}.deb" >/dev/null
echo "Done: dist/bloom_${VERSION}_${ARCH}.deb"

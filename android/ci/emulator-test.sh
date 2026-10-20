#!/usr/bin/env bash
# Tries the Android app on an emulator the way a person would: share a file to
# Bloom, tap a petal, and check the result lands in Downloads/Bloom.
# Screenshots of each step go to android-shots/.
set -euo pipefail

PKG=io.github.omjasoliya.bloom
HERE=$(dirname "$0")
OUT=android-shots
mkdir -p "$OUT"
trap 'adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true' EXIT

shot() { sleep 1; adb exec-out screencap -p > "$OUT/$1.png"; }

# Taps the on-screen item whose text (or description) is $1.
tap() {
  for _ in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
    adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1 || true
    if xy=$(python3 - "$1" "$OUT/ui.xml" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
label, path = sys.argv[1], sys.argv[2]
for node in ET.parse(path).iter("node"):
    if label in (node.get("text"), node.get("content-desc")):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY
    ); then
      adb shell input tap $xy
      return 0
    fi
    sleep 1
  done
  echo "Couldn't find '$1' on screen"; return 1
}

# Waits for Downloads/Bloom/$1 to appear.
expect() {
  for _ in $(seq 1 90); do
    if adb shell ls /sdcard/Download/Bloom/ 2>/dev/null | tr -d '\r' | grep -qx "$1"; then
      echo "ok: $1"; return 0
    fi
    sleep 1
  done
  echo "missing: $1"; adb shell ls -la /sdcard/Download/Bloom/ || true; return 1
}

share() {   # share <file> <mime>
  adb shell am start -W -a android.intent.action.SEND -t "$2" \
    --eu android.intent.extra.STREAM "file:///data/user/0/$PKG/files/$1" -n "$PKG/.BloomActivity" > /dev/null
  sleep 2
}

adb install -r android/app/build/outputs/apk/debug/app-debug.apk
# Put the sample files where the app can read them by path.
adb shell run-as "$PKG" mkdir -p files
for f in test-photo.jpg test-doc.pdf test-clip.mp4; do
  adb push "$HERE/$f" "/data/local/tmp/$f" > /dev/null
  adb shell "cat /data/local/tmp/$f | run-as $PKG sh -c 'cat > files/$f'"
done

adb shell am start -W -n "$PKG/.BloomActivity" > /dev/null
shot 01-home

share test-photo.jpg image/jpeg
shot 02-photo-convert
tap PDF
expect test-photo.pdf
shot 03-photo-to-pdf
tap Done

share test-photo.jpg image/jpeg
tap Tools
shot 04-photo-tools
tap Compress
expect test-photo-small.jpg
tap Done

share test-photo.jpg image/jpeg
tap Actions
shot 05-photo-actions
tap Zip
expect test-photo.zip
tap Done

share test-doc.pdf application/pdf
shot 06-pdf-convert
tap JPG
expect test-doc-p1.jpg
expect test-doc-p2.jpg
shot 07-pdf-to-jpg
tap Done

share test-doc.pdf application/pdf
tap Tools
tap "Split pages"
expect test-doc-p1.pdf
expect test-doc-p2.pdf
tap Done

share test-clip.mp4 video/mp4
shot 08-video-convert
tap M4A
expect test-clip.m4a
shot 09-video-to-m4a
tap Done

share test-clip.mp4 video/mp4
tap Tools
tap Mute
expect test-clip-muted.mp4
tap Done

adb shell ls -la /sdcard/Download/Bloom/ | tee "$OUT/outputs.txt"

# The signed release build installs and opens too.
adb uninstall "$PKG" > /dev/null
adb install dist/Bloom.apk
adb shell am start -W -n "$PKG/.BloomActivity" > /dev/null
shot 10-release-home
adb shell pidof "$PKG" > /dev/null
echo "All good"

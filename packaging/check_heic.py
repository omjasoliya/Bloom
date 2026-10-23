"""Build check: HEIC photos convert, and a built bundle (if given) ships libheif.
Usage: python packaging/check_heic.py [path/to/dist/Bloom]"""
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))   # the repo root

from PIL import Image
from pillow_heif import register_heif_opener

from bloom import converters

register_heif_opener()
with tempfile.TemporaryDirectory(prefix="bloom-heic-") as tmp:
    src = Path(tmp) / "IMG_0001.HEIC"
    Image.new("RGB", (64, 48), "red").save(src)
    assert converters.formats_for([src]) == ["png", "jpg"], converters.formats_for([src])
    for ext, fmt in (("png", "PNG"), ("jpg", "JPEG")):
        out = converters.convert(src, ext)[0]
        with Image.open(out) as im:
            assert im.format == fmt and im.size == (64, 48), (ext, im.format, im.size)
print("HEIC -> PNG/JPG: ok")

if len(sys.argv) > 1:
    bundle = Path(sys.argv[1])
    found = [p.name for p in bundle.rglob("*") if "heif" in p.name.lower() and p.is_file()]
    assert any(n.lower().startswith(("libheif", "heif")) and (".so" in n or n.endswith(".dll")) for n in found), \
        f"libheif is missing from {bundle}: {found}"
    print("bundle ships:", ", ".join(sorted(found)[:4]))

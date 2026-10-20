"""Bloom's Tools wheel (Ctrl+drag) and Actions wheel (Alt+drag). Windows and Linux.

Tools make a new, modified copy next to the original. Actions do something with
the file itself. Only "Move to Desktop" moves the original, and it says so.
"""
import io
import math
import os
import shutil
import zipfile
from pathlib import Path

from . import converters
from . import platform_utils as pu


# ---------------------------------------------------------------- helpers ---

def _sibling(src, suffix, ext=None):
    """photo.jpg -> photo-compressed.jpg (never overwrites: photo-compressed (1).jpg)."""
    src = Path(src)
    ext = ext or src.suffix.lstrip(".")
    target = src.with_name(f"{src.stem}-{suffix}.{ext}")
    n = 1
    while target.exists():
        target = src.with_name(f"{src.stem}-{suffix} ({n}).{ext}")
        n += 1
    return target


def _unique(path):
    path = Path(path)
    if not path.exists():
        return path
    n = 1
    while True:
        alt = path.with_name(f"{path.stem} ({n}){path.suffix}")
        if not alt.exists():
            return alt
        n += 1


def _open_image(path):
    from PIL import Image, ImageOps
    with Image.open(path) as im:
        return ImageOps.exif_transpose(im).copy()


def _save_like(im, target):
    if target.suffix.lower() in (".jpg", ".jpeg") and im.mode not in ("RGB", "L"):
        im = im.convert("RGB")
    im.save(target)


def _ffmpeg(*args, timeout=3600):
    pu.run([pu.ffmpeg_exe(), "-y", "-loglevel", "error", *map(str, args)], timeout=timeout)


# ------------------------------------------------------------------ tools ---

def t_compress_image(paths):
    out = []
    for p in paths:
        im = _open_image(p)
        ext = Path(p).suffix.lower().lstrip(".")
        if ext in ("jpg", "jpeg", "webp"):
            target = _sibling(p, "compressed")
            im.convert("RGB").save(target, quality=70, optimize=True)
        elif ext == "png":
            target = _sibling(p, "compressed")
            im.convert("RGBA").quantize(colors=256, method=2).save(target, optimize=True)
        else:
            target = _sibling(p, "compressed", "jpg")
            im.convert("RGB").save(target, quality=72, optimize=True)
        out.append(target)
    return out


def t_resize_half(paths):
    out = []
    for p in paths:
        im = _open_image(p)
        target = _sibling(p, "half")
        _save_like(im.resize((max(1, im.width // 2), max(1, im.height // 2))), target)
        out.append(target)
    return out


def t_rotate(paths):
    out = []
    for p in paths:
        target = _sibling(p, "rotated")
        _save_like(_open_image(p).rotate(-90, expand=True), target)
        out.append(target)
    return out


def t_strip_metadata(paths):
    from PIL import Image
    out = []
    for p in paths:
        im = _open_image(p)
        clean = Image.new(im.mode, im.size)
        clean.putdata(list(im.getdata()))
        target = _sibling(p, "clean")
        _save_like(clean, target)
        out.append(target)
    return out


def t_grayscale(paths):
    out = []
    for p in paths:
        target = _sibling(p, "gray")
        _save_like(_open_image(p).convert("L"), target)
        out.append(target)
    return out


# Forms (exams, jobs, passports, KYC) ask for things like "photo under 50 KB, 200x230 px".
FIT_SIZES = [("20", "20 KB"), ("50", "50 KB"), ("100", "100 KB"), ("200", "200 KB"), ("500", "500 KB"),
             ("custom", "Custom")]


def _jpeg(im, quality):
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=quality, optimize=True)
    return buf.getvalue()


def _best_quality(im, limit, lo, hi):
    """Highest JPEG quality in lo..hi that fits in limit bytes: (data, quality) or None."""
    best = None
    while lo <= hi:
        mid = (lo + hi) // 2
        data = _jpeg(im, mid)
        if len(data) <= limit:
            best, lo = (data, mid), mid + 1
        else:
            hi = mid - 1
    return best


def _fit_jpeg(im, limit, exact):
    """JPEG bytes under limit, keeping as many pixels and as much quality as fit.
    With exact dimensions only the quality can give way."""
    from PIL import Image
    if exact:
        found = _best_quality(im, limit, 5, 100)        # up to 100: forms often set a minimum size too
        return (found[0], im.size) if found else None
    # Start at a size that should fit at good quality; never larger than the original.
    scale = min(1.0, math.sqrt(limit * 8 / 1.8 / (im.width * im.height)))
    best, cur = None, im
    for _ in range(10):
        cur = im if scale >= 1 else im.resize((max(16, round(im.width * scale)), max(16, round(im.height * scale))),
                                              Image.LANCZOS)
        found = _best_quality(cur, limit, 40, 92)
        if found:
            best = (found[0], cur.size)
            if found[1] < 88 or scale >= 1:
                return best
            scale = min(1.0, scale * 1.2)          # room to spare: try more pixels
        elif best:
            return best
        elif min(cur.size) <= 16:
            break
        else:
            scale *= min(0.95, max(0.3, math.sqrt(limit / len(_jpeg(cur, 40))) * 0.95))
    found = _best_quality(cur, limit, 5, 39)
    return best or ((found[0], cur.size) if found else None)


def fit_image(path, kb, width=0, height=0):
    """A JPEG copy of the image that fits in kb KB, optionally at exactly width x height
    (cropped from the centre) or at that width / height. Returns (path, (w, h), bytes)."""
    from PIL import Image, ImageOps
    im = _open_image(path)
    if im.mode in ("RGBA", "LA", "P"):
        im = im.convert("RGBA")
        flat = Image.new("RGB", im.size, "white")
        flat.paste(im, mask=im.getchannel("A"))
        im = flat
    im = im.convert("RGB")
    if width and height:
        im = ImageOps.fit(im, (width, height), Image.LANCZOS)
    elif width:
        im = im.resize((width, max(1, round(im.height * width / im.width))), Image.LANCZOS)
    elif height:
        im = im.resize((max(1, round(im.width * height / im.height)), height), Image.LANCZOS)
    found = _fit_jpeg(im, kb * 1024, bool(width or height))
    if not found:
        raise ValueError(f"{Path(path).name} can't be made small enough for {kb} KB"
                         + (f" at {width or '?'}x{height or '?'} px" if width or height else "") + ". Try a larger size.")
    data, size = found
    target = _sibling(path, f"{kb}kb", "jpg")
    target.write_bytes(data)
    return target, size, len(data)


def t_images_to_pdf(paths):
    ims = [_open_image(p).convert("RGB") for p in paths]
    target = _sibling(paths[0], "combined", "pdf") if len(paths) > 1 else \
        _unique(Path(paths[0]).with_suffix(".pdf"))
    ims[0].save(target, save_all=True, append_images=ims[1:])
    return [target]


def t_compress_pdf(paths):
    from pypdf import PdfReader, PdfWriter
    out = []
    for p in paths:
        writer = PdfWriter(clone_from=PdfReader(p))
        for page in writer.pages:
            for img in page.images:
                try:
                    img.replace(img.image, quality=60)       # re-save pictures as smaller JPEGs
                except Exception:
                    pass                                    # leave images pypdf can't re-encode
            page.compress_content_streams()
        writer.compress_identical_objects(remove_identicals=True, remove_orphans=True)
        target = _sibling(p, "compressed")
        with open(target, "wb") as f:
            writer.write(f)
        out.append(target)
    return out


def t_merge_pdfs(paths):
    from pypdf import PdfWriter
    writer = PdfWriter()
    for p in paths:
        writer.append(p)
    target = _sibling(paths[0], "merged")
    with open(target, "wb") as f:
        writer.write(f)
    return [target]


def t_split_pdf(paths):
    from pypdf import PdfReader, PdfWriter
    out = []
    for p in paths:
        folder = _unique(Path(p).with_name(Path(p).stem + " pages"))
        folder.mkdir()
        for i, page in enumerate(PdfReader(p).pages, start=1):
            writer = PdfWriter()
            writer.add_page(page)
            target = folder / f"{Path(p).stem}-page-{i}.pdf"
            with open(target, "wb") as f:
                writer.write(f)
            out.append(target)
    return out


def t_compress_video(paths):
    out = []
    for p in paths:
        target = _sibling(p, "compressed", "mp4")
        _ffmpeg("-i", p, "-c:v", "libx264", "-crf", "28", "-preset", "veryfast",
                "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", target)
        out.append(target)
    return out


def t_video_720p(paths):
    out = []
    for p in paths:
        target = _sibling(p, "720p", "mp4")
        _ffmpeg("-i", p, "-vf", "scale=-2:'min(720,ih)'", "-c:v", "libx264", "-crf", "23",
                "-preset", "veryfast", "-c:a", "aac", target)
        out.append(target)
    return out


def t_mute_video(paths):
    out = []
    for p in paths:
        target = _sibling(p, "muted")
        _ffmpeg("-i", p, "-c:v", "copy", "-an", target)
        out.append(target)
    return out


def t_compress_audio(paths):
    out = []
    for p in paths:
        target = _sibling(p, "compressed", "mp3")
        _ffmpeg("-i", p, "-vn", "-b:a", "128k", target)
        out.append(target)
    return out


def t_normalize_audio(paths):
    out = []
    for p in paths:
        target = _sibling(p, "normalized")
        _ffmpeg("-i", p, "-af", "loudnorm=I=-16:TP=-1.5:LRA=11", target)
        out.append(target)
    return out


# key: (label shown on the petal, function, needs 2+ files)
TOOLS = {
    # "fit" has no function here: the app asks for a size first, then calls fit_image().
    "image": [("fit", "Fit size", None, False), ("compress", "Compress", t_compress_image, False),
              ("resize", "Half size", t_resize_half, False), ("rotate", "Rotate", t_rotate, False),
              ("strip", "Metadata", t_strip_metadata, False), ("gray", "Grayscale", t_grayscale, False),
              ("topdf", "To PDF", t_images_to_pdf, False)],
    "pdf": [("compress", "Compress", t_compress_pdf, False), ("merge", "Merge", t_merge_pdfs, True),
            ("split", "Split", t_split_pdf, False)],
    "video": [("compress", "Compress", t_compress_video, False), ("720p", "720p", t_video_720p, False),
              ("mute", "Mute", t_mute_video, False)],
    "audio": [("compress", "Compress", t_compress_audio, False), ("normalize", "Normalize", t_normalize_audio, False)],
}


def _same_category(paths):
    files = [p for p in paths if os.path.isfile(p)]
    if not files:
        return None, []
    cat = converters.category(files[0])
    return cat, [p for p in files if converters.category(p) == cat]


def tools_for(paths):
    cat, same = _same_category(paths)
    return [(key, label) for key, label, _fn, multi in TOOLS.get(cat, []) if not multi or len(same) > 1]


def run_tool(key, paths):
    cat, same = _same_category(paths)
    for k, _label, fn, _multi in TOOLS.get(cat, []):
        if k == key:
            return fn(same)
    raise ValueError(f"Unknown tool {key}")


# ---------------------------------------------------------------- actions ---

ACTIONS = [("copypath", "Copy path"), ("terminal", "Terminal"), ("copydesk", "To Desktop"),
           ("movedesk", "Move to Desktop"), ("zip", "Zip"), ("duplicate", "Duplicate")]


def actions_for(paths):
    return ACTIONS if paths else []


def run_action(key, paths, set_clipboard=None):
    """Returns (summary, created_paths). Copy path needs set_clipboard(text)."""
    if key == "copypath":
        set_clipboard("\n".join(str(Path(p)) for p in paths))
        return (f"Copied {len(paths)} path{'s' if len(paths) > 1 else ''}", [])
    if key == "terminal":
        first = Path(paths[0])
        folder = first if first.is_dir() else first.parent
        pu.open_terminal(folder)
        return (f"Opened a terminal in {folder.name or folder}", [])
    if key in ("copydesk", "movedesk"):
        desk = pu.desktop_dir()
        desk.mkdir(parents=True, exist_ok=True)
        made = []
        for p in paths:
            target = _unique(desk / Path(p).name)
            if key == "movedesk":
                shutil.move(str(p), str(target))
            elif os.path.isdir(p):
                shutil.copytree(p, target)
            else:
                shutil.copy2(p, target)
            made.append(target)
        verb = "Moved" if key == "movedesk" else "Copied"
        return (f"{verb} {len(made)} item{'s' if len(made) > 1 else ''} to the Desktop", made)
    if key == "zip":
        first = Path(paths[0])
        target = _unique(first.with_name((first.stem if len(paths) == 1 else "Archive") + ".zip"))
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as zf:
            for p in map(Path, paths):
                if p.is_dir():
                    for f in p.rglob("*"):
                        zf.write(f, f.relative_to(p.parent))
                else:
                    zf.write(p, p.name)
        return ("Zipped", [target])
    if key == "duplicate":
        made = []
        for p in map(Path, paths):
            target = _unique(p.with_name(f"{p.stem} copy{p.suffix}"))
            shutil.copytree(p, target) if p.is_dir() else shutil.copy2(p, target)
            made.append(target)
        return ("Duplicated", made)
    raise ValueError(f"Unknown action {key}")
